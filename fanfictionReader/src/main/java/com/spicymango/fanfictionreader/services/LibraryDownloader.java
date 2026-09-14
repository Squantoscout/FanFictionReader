package com.spicymango.fanfictionreader.services;

import android.annotation.SuppressLint;
import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebView;
import android.widget.Toast;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.spicymango.fanfictionreader.R;
import com.spicymango.fanfictionreader.Settings;
import com.spicymango.fanfictionreader.menu.librarymenu.LibraryMenuActivity;
import com.spicymango.fanfictionreader.util.Sites;
import com.spicymango.fanfictionreader.util.Story;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;

/**
 * Downloads a story into the library. In order to use it, the story URI must be passed in the
 * intent.
 * <p>
 * The class queues up every intent and downloads a single story at a time. Multiple simultaneous
 * downloads have not been implemented since they could cause FanFiction.net to issue a temporary ip
 * ban due to excessive connections from a single device.
 * <p>
 * This class displays two independent notifications. The first notification shows the progress when
 * an individual story is being updated or downloaded. The second notification displays the lists of
 * downloaded/updated stories during batch updates.
 *
 * @author Michael Chen
 */
public class LibraryDownloader extends IntentService {
	/**
	 * Key for the offset desired. This is used to pass the offset on an intent.
	 * <p>
	 * The offset (an optional parameter) ensures that the user's position along the story is saved
	 * in the database whenever the user downloads a story.
	 */
	final static String EXTRA_OFFSET = "Offset";

	/**
	 * Key for the last chapter read. This is used to pass the current chapter on an intent.
	 * <p>
	 * The offset (an optional parameter) ensures that the user's position along the story is saved
	 * in the database whenever the user downloads a story.
	 */
	final static String EXTRA_LAST_PAGE = "Last page";

	/**
	 * Key for an integrity check flag, which forces the downloader to scan for missing files.
	 */
	final static String EXTRA_INTEGRITY = "Integrity Check";

	/**
	 * IDs for the following notifications
	 * <ul>
	 *     <li>"Checking for updates"</li>
	 *     <li>"Error Notifications"</li>
	 *     <li>"Update Completed"</li>
	 * </ul>
	 */
	private final static int NOTIFICATION_UPDATE_ID = 0;

	/**
	 * IDs for the following notifications
	 * <ul>
	 *     <li>"Downloading Story"</li>
	 *     <li>"Downloading Chapter #/#"</li>
	 *     <li>"Saving Story</li>
	 * </ul>
	 */
	private final static int NOTIFICATION_DOWNLOAD_ID = 1;

	/**
	 * ID For the required foreground notification
	 */
	private final static int NOTIFICATION_FOREGROUND_ID =  3;

	private final static String NOTIFICATION_CHANNEL = "Channel";

	/**
	 * The number of stories that have been already been checked for updates. This variable is used
	 * in order to derive the total number of stories queued, which is used to generate the progress
	 * bar.
	 */
	private int currentProgress = 0;

	/** Keeps track of errors*/
	private boolean hasParsingError, hasConnectionError, hasIoError;
	private int consecutiveConnectionErrors;

	/**
	 * Holds the detailed message from the most recent connection failure, so it can be shown to
	 * the user in the error notification instead of a generic message.
	 */
	private String lastConnectionErrorDetail;

	/**
	 * Stores the time at which the update process began. This is used to calculate the time elapsed
	 * displayed in the notification.
	 */
	private long updateStartTime;

	/**
	 * Counts how many more stories need to be parsed before the complete notification is
	 * shown.
	 */
	private AtomicInteger mStoryQueueLength;

	/** Keeps track of story names for update purposes*/
	private final List<String> storiesUpdated = new ArrayList<>();

	private WebView mWebView;

	/**
	 * True if {@link #mWebView} was successfully attached to a system overlay window. Used by
	 * {@link #onDestroy()} to know whether the view needs to be detached.
	 */
	private boolean mWebViewAttachedToWindow;

	public LibraryDownloader() {
		super(LibraryDownloader.class.getName());
	}

	/**
	 * Downloads a story into the device. The reader's current location in the story is saved with
	 * the rest of the story properties. If the story already exists, the downloader will update the
	 * story details.
	 *
	 * @param context     The current context
	 * @param uri         The url that points to any chapter in the story
	 * @param currentPage The reader's current page
	 * @param offset      The reader's current scroll offset
	 */
	public static void download(Context context, Uri uri, int currentPage, int offset) {
		if (!ensureOverlayPermission(context)) return;

		Intent i = new Intent(context, LibraryDownloader.class);
		i.setData(uri);
		i.putExtra(EXTRA_LAST_PAGE, currentPage);
		i.putExtra(EXTRA_OFFSET, offset);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startForegroundService(i);
		} else {
			context.startService(i);
		}
	}

	/**
	 * Checks whether the app has permission to draw overlay windows. This permission is required
	 * because the downloader must attach its background {@link WebView} to a real window in order
	 * for FanFiction.net's bot-detection JavaScript challenge to run correctly; a WebView that is
	 * never attached to any window has its JavaScript timers throttled by Android, which prevents
	 * the challenge from ever completing.
	 * <p>
	 * If the permission has not been granted, the user is redirected to the system settings screen
	 * where it can be enabled, and the download is not started.
	 *
	 * @param context The calling context. If it is not an overlay-permission-eligible context the
	 *                 permission prompt is still shown, since {@code ACTION_MANAGE_OVERLAY_PERMISSION}
	 *                 works from any context via {@code FLAG_ACTIVITY_NEW_TASK}.
	 * @return True if the permission is already granted (or not required on this API level), false
	 * otherwise.
	 */
	private static boolean ensureOverlayPermission(Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			// The permission is granted automatically at install time on pre-Marshmallow devices.
			return true;
		}

		if (android.provider.Settings.canDrawOverlays(context)) {
			return true;
		}

		Toast.makeText(context, R.string.downloader_overlay_permission_required, Toast.LENGTH_LONG).show();

		final Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
				Uri.parse("package:" + context.getPackageName()));
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);

		return false;
	}

	/**
	 * Scans a story for missing chapters and downloads them as required.
	 *
	 * @param context     The current context
	 * @param uri         The url that points to any chapter in the story
	 * @param currentPage The reader's current page
	 * @param offset      The reader's current scroll offset
	 */
	public static void integrityCheck(Context context, Uri uri, int currentPage, int offset) {
		if (!ensureOverlayPermission(context)) return;

		Intent i = new Intent(context, LibraryDownloader.class);
		i.setData(uri);
		i.putExtra(EXTRA_LAST_PAGE, currentPage);
		i.putExtra(EXTRA_OFFSET, offset);
		i.putExtra(EXTRA_INTEGRITY, true);
		context.startService(i);
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	public void onCreate() {
		super.onCreate();

		// Clear error flags when the service is initialized.
		hasParsingError = false;
		hasConnectionError = false;
		hasIoError = false;
		consecutiveConnectionErrors = 0;

		// An atomic integer is used to synchronize incoming requests (which occur on the main
		// thread) with the website downloads, which occur asynchronously.
		mStoryQueueLength = new AtomicInteger(0);

		// The time at which the service starts.
		updateStartTime = System.currentTimeMillis();

		// Create the WebView through which HTTP requests will be performed
		initializeCookies();
		mWebView = new WebView(this);
		mWebView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36");
		mWebView.getSettings().setJavaScriptEnabled(true);
		mWebView.getSettings().setDomStorageEnabled(true);

		// Attach the WebView to an invisible system overlay window. This is required because a
		// WebView that is never attached to any window has its JavaScript execution throttled by
		// Android, which prevents FanFiction.net's bot-detection challenge page from ever finishing.
		attachWebViewToWindow();


		// Create the Notification Channel
		if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O){
			final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL,
																  getString(R.string.app_name),
																  NotificationManager.IMPORTANCE_LOW);
			final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			assert manager != null;
			manager.createNotificationChannel(channel);
