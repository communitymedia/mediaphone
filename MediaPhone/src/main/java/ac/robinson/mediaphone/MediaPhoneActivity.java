/*
 *  Copyright (C) 2012 Simon Robinson
 * 
 *  This file is part of Com-Me.
 * 
 *  Com-Me is free software; you can redistribute it and/or modify it 
 *  under the terms of the GNU Lesser General Public License as 
 *  published by the Free Software Foundation; either version 3 of the 
 *  License, or (at your option) any later version.
 *
 *  Com-Me is distributed in the hope that it will be useful, but WITHOUT 
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY 
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General 
 *  Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with Com-Me.
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package ac.robinson.mediaphone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.AnimationDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.provider.MediaStore.Video;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ac.robinson.mediaphone.activity.FrameEditorActivity;
import ac.robinson.mediaphone.activity.NarrativeBrowserActivity;
import ac.robinson.mediaphone.activity.PreferencesActivity;
import ac.robinson.mediaphone.activity.SaveNarrativeActivity;
import ac.robinson.mediaphone.activity.TemplateBrowserActivity;
import ac.robinson.mediaphone.importing.ImportedFileParser;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.FrameItem.NavigationMode;
import ac.robinson.mediaphone.provider.FramesManager;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediautilities.FrameMediaContainer;
import ac.robinson.mediautilities.HTMLUtilities;
import ac.robinson.mediautilities.MOVUtilities;
import ac.robinson.mediautilities.MediaUtilities;
import ac.robinson.mediautilities.SMILUtilities;
import ac.robinson.util.AndroidUtilities;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.ImageCacheUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.util.ViewServer;
import ac.robinson.view.CenteredImageTextButton;
import ac.robinson.view.CrossFadeDrawable;

public abstract class MediaPhoneActivity extends AppCompatActivity {

	private static final int PERMISSION_EXPORT_STORAGE = 100;

	private ImportFramesTask mImportFramesTask;
	private ProgressDialog mImportFramesProgressDialog;
	private boolean mImportFramesDialogShown = false;

	private ExportNarrativesTask mExportNarrativesTask;
	private boolean mExportNarrativeDialogShown = false;
	private boolean mExportVideoDialogShown = false;

	private QueuedBackgroundRunnerTask mBackgroundRunnerTask;
	private boolean mBackgroundRunnerDialogShown = false;

	private GestureDetector mGestureDetector = null;
	private boolean mCanSwipe;
	private boolean mHasSwiped;

	// stores the time of the last call to onResume() so we can filter out touch events that happened before this
	// activity was visible (happens on HTC Desire S for example) - see: http://stackoverflow.com/a/13988083/1993220
	private long mResumeTime = 0;

	// bit of a hack in a similar vein to above to prevent some devices from multiple-clicking
	private int mRecentlyClickedButton = -1;

	// load preferences that don't affect the interface
	abstract protected void loadPreferences(SharedPreferences mediaPhoneSettings);

	// load preferences that need to be configured after onCreate
	abstract protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (MediaPhone.DEBUG) {
			ViewServer.get(this).addWindow(this);
		}
		Window window = getWindow();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
			window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
			window.setStatusBarColor(getResources().getColor(R.color.primary_dark));
		}

		UIUtilities.setPixelDithering(window);
		checkDirectoriesExist();

		Object retained = getLastCustomNonConfigurationInstance();
		if (retained instanceof Object[]) {
			Object[] retainedTasks = (Object[]) retained;
			if (retainedTasks.length == 3) {
				if (retainedTasks[0] instanceof ImportFramesTask) {
					// reconnect to the task; dialog is shown automatically
					mImportFramesTask = (ImportFramesTask) retainedTasks[0];
					mImportFramesTask.setActivity(this);
				}
				if (retainedTasks[1] instanceof ExportNarrativesTask) {
					// reconnect to the task; dialog is shown automatically
					mExportNarrativesTask = (ExportNarrativesTask) retainedTasks[1];
					mExportNarrativesTask.setActivity(this);
				}
				if (retainedTasks[2] instanceof QueuedBackgroundRunnerTask) {
					// reconnect to the task; dialog is shown automatically
					mBackgroundRunnerTask = (QueuedBackgroundRunnerTask) retainedTasks[2];
					mBackgroundRunnerTask.setActivity(this);
				}
			}
		}

		loadAllPreferences(); // must do this before loading so that, e.g., audio knows high/low setting before setup
	}

	@Override
	protected void onStart() {
		configureInterfacePreferences(PreferenceManager.getDefaultSharedPreferences(MediaPhoneActivity.this));
		super.onStart();
	}

	@Override
	protected void onResume() {
		super.onResume();
		mResumeTime = SystemClock.uptimeMillis();
		if (MediaPhone.DEBUG) {
			ViewServer.get(this).setFocusedWindow(this);
		}
		((MediaPhoneApplication) getApplication()).registerActivityHandle(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
		mImportFramesProgressDialog = null;
		((MediaPhoneApplication) getApplication()).removeActivityHandle(this);
	}

	@Override
	protected void onDestroy() {
		if (MediaPhone.DEBUG) {
			ViewServer.get(this).removeWindow(this);
		}
		super.onDestroy();
	}

	@Override
	public Object onRetainCustomNonConfigurationInstance() {
		// called before screen change - have to remove the parent activity
		if (mImportFramesTask != null) {
			mImportFramesTask.setActivity(null);
		}
		if (mExportNarrativesTask != null) {
			mExportNarrativesTask.setActivity(null);
		}
		if (mBackgroundRunnerTask != null) {
			mBackgroundRunnerTask.setActivity(null);
		}
		return new Object[]{mImportFramesTask, mExportNarrativesTask, mBackgroundRunnerTask};
	}

	protected void registerForSwipeEvents() {
		mHasSwiped = false;
		mCanSwipe = true;
		if (mGestureDetector == null) { // so we can re-call any time
			mGestureDetector = new GestureDetector(MediaPhoneActivity.this, new SwipeDetector());
		}
	}

	protected void setSwipeEventsEnabled(boolean enabled) {
		mCanSwipe = enabled;
	}

	// see: http://stackoverflow.com/a/7767610
	private class SwipeDetector extends SimpleOnGestureListener {
		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
			if (!mCanSwipe) {
				return false;
			}
			try {
				// must swipe along a fairly horizontal path
				if (Math.abs(e1.getY() - e2.getY()) > MediaPhone.SWIPE_MAX_OFF_PATH) {
					return false;
				}

				// right to left
				if (e1.getX() - e2.getX() > MediaPhone.SWIPE_MIN_DISTANCE && Math.abs(velocityX) > MediaPhone
						.SWIPE_THRESHOLD_VELOCITY) {
					if (!mHasSwiped) {
						mHasSwiped = swipeNext(); // so that we don't double-swipe and crash
					}
					return true;
				}

				// left to right
				if (e2.getX() - e1.getX() > MediaPhone.SWIPE_MIN_DISTANCE && Math.abs(velocityX) > MediaPhone
						.SWIPE_THRESHOLD_VELOCITY) {
					if (!mHasSwiped) {
						mHasSwiped = swipePrevious(); // so that we don't double-swipe and crash
					}
					return true;
				}
			} catch (NullPointerException e) {
				// this happens when we get the first or last fling motion event (so only one event) - safe to ignore
			}
			return false;

		}
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent e) {
		if (e.getEventTime() < mResumeTime) {
			if (MediaPhone.DEBUG) {
				Log.d(DebugUtilities.getLogTag(this), "Discarded touch event with start time earlier than onResume()");
			}
			return true;
		}

		if (mGestureDetector != null) {
			if (mGestureDetector.onTouchEvent(e)) {
				e.setAction(MotionEvent.ACTION_CANCEL); // swipe detected - don't do the normal event
			}
		}

		try {
			return super.dispatchTouchEvent(e);
		} catch (NullPointerException ex) {
			if (MediaPhone.DEBUG) {
				Log.d(DebugUtilities.getLogTag(this), "Catching touch event Null Pointer Exception; ignoring touch");
			}
			return true; // reported on Play Store - see: http://stackoverflow.com/a/13031529/1993220
		}
	}

	// overridden where appropriate
	protected boolean swipePrevious() {
		return false;
	}

	// overridden where appropriate
	protected boolean swipeNext() {
		return false;
	}

	protected boolean verifyButtonClick(View currentButton) {
		// handle a problem on some devices where touch events get passed twice if the finger moves slightly
		final int buttonId = currentButton.getId();
		if (mRecentlyClickedButton == buttonId) {
			mRecentlyClickedButton = -1; // just in case - don't want to get stuck in the unclickable state
			if (MediaPhone.DEBUG) {
				Log.d(DebugUtilities.getLogTag(this), "Discarding button click too soon after previous");
			}
			return false;
		}

		// allow button clicks after a tap-length timeout
		mRecentlyClickedButton = buttonId;
		currentButton.postDelayed(new Runnable() {
			@Override
			public void run() {
				mRecentlyClickedButton = -1;
			}
		}, ViewConfiguration.getTapTimeout());

		return true;
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
			case R.id.dialog_importing_in_progress:
				ProgressDialog importDialog = new ProgressDialog(MediaPhoneActivity.this);
				importDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
				importDialog.setMessage(getString(R.string.import_progress));
				importDialog.setCancelable(false);
				mImportFramesProgressDialog = importDialog;
				mImportFramesDialogShown = true;
				return importDialog;
			case R.id.dialog_export_narrative_in_progress:
				ProgressDialog exportDialog = new ProgressDialog(MediaPhoneActivity.this);
				exportDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
				exportDialog.setMessage(getString(R.string.background_task_progress));
				exportDialog.setCancelable(false);
				exportDialog.setIndeterminate(true);
				mExportNarrativeDialogShown = true;
				return exportDialog;
			case R.id.dialog_mov_creator_in_progress:
				ProgressDialog movDialog = new ProgressDialog(MediaPhoneActivity.this);
				movDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
				movDialog.setMessage(getString(R.string.mov_export_task_progress));
				movDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.mov_export_run_in_background), new
						DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						mExportVideoDialogShown = false;
					}
				});
				movDialog.setCancelable(false);
				movDialog.setIndeterminate(true);
				mExportVideoDialogShown = true;
				return movDialog;
			case R.id.dialog_background_runner_in_progress:
				ProgressDialog runnerDialog = new ProgressDialog(MediaPhoneActivity.this);
				runnerDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
				runnerDialog.setMessage(getString(R.string.background_task_progress));
				runnerDialog.setCancelable(false);
				runnerDialog.setIndeterminate(true);
				mBackgroundRunnerDialogShown = true;
				return runnerDialog;
			default:
				return super.onCreateDialog(id);
		}
	}

	@Override
	protected void onPrepareDialog(int id, Dialog dialog) {
		super.onPrepareDialog(id, dialog);
		switch (id) {
			case R.id.dialog_importing_in_progress:
				mImportFramesProgressDialog = (ProgressDialog) dialog;
				if (mImportFramesTask != null) {
					mImportFramesProgressDialog.setMax(mImportFramesTask.getMaximumProgress());
					mImportFramesProgressDialog.setProgress(mImportFramesTask.getCurrentProgress());
				}
				mImportFramesDialogShown = true;
				break;
			case R.id.dialog_export_narrative_in_progress:
				mExportNarrativeDialogShown = true;
				break;
			case R.id.dialog_mov_creator_in_progress:
				mExportVideoDialogShown = true;
				break;
			case R.id.dialog_background_runner_in_progress:
				mBackgroundRunnerDialogShown = true;
				break;
			default:
				break;
		}
	}

	private void safeDismissDialog(int id) {
		try {
			dismissDialog(id);
		} catch (IllegalArgumentException e) { // we didn't show the dialog
		} catch (Throwable t) {
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.preferences, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				onBackPressed(); // to make sure we update frames - home is essentially back in this case
				return true;

			case R.id.menu_preferences:
				final Intent preferencesIntent = new Intent(MediaPhoneActivity.this, PreferencesActivity.class);
				startActivityForResult(preferencesIntent, MediaPhone.R_id_intent_preferences);
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	protected void createMediaMenuNavigationButtons(MenuInflater inflater, Menu menu, boolean edited) {
		inflater.inflate(R.menu.add_frame, menu);
		// if (edited) {
		inflater.inflate(R.menu.finished_editing, menu);
		// } else {
		// 	inflater.inflate(R.menu.back_without_editing, menu);
		// }
	}

	protected void prepareMediaMenuNavigationButtons(Menu menu, String mediaId) {
		boolean allowSpannedMediaNavigation = false;
		if (mediaId != null) {
			MediaItem mediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mediaId);
			if (mediaItem != null) {
				// don't allow spanned frames to add frame after as this doesn't make any sense
				allowSpannedMediaNavigation = !mediaItem.getSpanFrames();
			}
		}
		menu.findItem(R.id.menu_add_frame).setEnabled(allowSpannedMediaNavigation);
	}

	protected void setupFrameMenuNavigationButtons(MenuInflater inflater, Menu menu, String frameId, boolean edited, boolean
			preventSpannedMediaNavigation) {
		inflater.inflate(R.menu.previous_frame, menu);
		inflater.inflate(R.menu.next_frame, menu);
		// we should have already got focus by the time this is called, so can try to disable invalid buttons
		if (frameId != null) {
			NavigationMode navigationAllowed = FrameItem.getNavigationAllowed(getContentResolver(), frameId);
			if (navigationAllowed == NavigationMode.PREVIOUS || navigationAllowed == NavigationMode.NONE ||
					preventSpannedMediaNavigation) {
				menu.findItem(R.id.menu_next_frame).setEnabled(false);
			}
			if (navigationAllowed == NavigationMode.NEXT || navigationAllowed == NavigationMode.NONE) {
				menu.findItem(R.id.menu_previous_frame).setEnabled(false);
			}
		}
		inflater.inflate(R.menu.add_frame, menu);
		if (preventSpannedMediaNavigation) {
			menu.findItem(R.id.menu_add_frame).setEnabled(false);
		}
		// if (edited) {
		inflater.inflate(R.menu.finished_editing, menu);
		// } else {
		// 	inflater.inflate(R.menu.back_without_editing, menu);
		// }
	}

	protected void setBackButtonIcons(Activity activity, int button1, int button2, boolean isEdited) {
		//if (button1 != 0) {
		//	((CenteredImageTextButton) findViewById(button1)).setCompoundDrawablesWithIntrinsicBounds(0, (isEdited ? R.drawable
		//			.ic_menu_accept : R.drawable.ic_menu_back), 0, 0);
		//}
		//if (button2 != 0) {
		//	((CenteredImageTextButton) findViewById(button2)).setCompoundDrawablesWithIntrinsicBounds(0, (isEdited ? R.drawable
		//			.ic_menu_accept : R.drawable.ic_menu_back), 0, 0);
		//}
		//UIUtilities.refreshActionBar(activity);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case MediaPhone.R_id_intent_preferences:
				loadAllPreferences();
				break;
			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		switch (requestCode) {
			case PERMISSION_EXPORT_STORAGE:
				if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
					UIUtilities.showFormattedToast(MediaPhoneActivity.this, R.string.permission_storage_unavailable_hint,
							getString(R.string.app_name));
				}
				break;
		}
	}

	private void loadAllPreferences() {
		SharedPreferences mediaPhoneSettings = PreferenceManager.getDefaultSharedPreferences(MediaPhoneActivity.this);
		Resources res = getResources();

		// bluetooth observer
		configureBluetoothObserver(mediaPhoneSettings, res);

		// importing confirmation
		boolean confirmImporting = res.getBoolean(R.bool.default_confirm_importing);
		try {
			confirmImporting = mediaPhoneSettings.getBoolean(getString(R.string.key_confirm_importing), confirmImporting);
		} catch (Exception e) {
			confirmImporting = res.getBoolean(R.bool.default_confirm_importing);
		}
		MediaPhone.IMPORT_CONFIRM_IMPORTING = confirmImporting;

		// delete after import
		boolean deleteAfterImport = res.getBoolean(R.bool.default_delete_after_importing);
		try {
			deleteAfterImport = mediaPhoneSettings.getBoolean(getString(R.string.key_delete_after_importing), deleteAfterImport);
		} catch (Exception e) {
			deleteAfterImport = res.getBoolean(R.bool.default_delete_after_importing);
		}
		MediaPhone.IMPORT_DELETE_AFTER_IMPORTING = deleteAfterImport;

		// minimum frame duration
		TypedValue resourceValue = new TypedValue();
		res.getValue(R.attr.default_minimum_frame_duration, resourceValue, true);
		float minimumFrameDuration;
		try {
			minimumFrameDuration = mediaPhoneSettings.getFloat(getString(R.string.key_minimum_frame_duration), resourceValue
					.getFloat());
			if (minimumFrameDuration <= 0) {
				throw new NumberFormatException();
			}
		} catch (Exception e) {
			minimumFrameDuration = resourceValue.getFloat();
		}
		MediaPhone.PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION = Math.round(minimumFrameDuration * 1000);

		// word duration
		// TODO: currently a one-time setting - should we queue a background task to select text items with negative or
		// zero/ duration values (i.e., not user-set) and update them to the new duration?
		res.getValue(R.attr.default_word_duration, resourceValue, true);
		float wordDuration;
		try {
			wordDuration = mediaPhoneSettings.getFloat(getString(R.string.key_word_duration), resourceValue.getFloat());
			if (wordDuration <= 0) {
				throw new NumberFormatException();
			}
		} catch (Exception e) {
			wordDuration = resourceValue.getFloat();
		}
		MediaPhone.PLAYBACK_EXPORT_WORD_DURATION = Math.round(wordDuration * 1000);

		// screen orientation
		int requestedOrientation = res.getInteger(R.integer.default_screen_orientation);
		try {
			String requestedOrientationString = mediaPhoneSettings.getString(getString(R.string.key_screen_orientation), null);
			requestedOrientation = Integer.valueOf(requestedOrientationString);
		} catch (Exception e) {
			requestedOrientation = res.getInteger(R.integer.default_screen_orientation);
		}
		setRequestedOrientation(requestedOrientation);

		// other preferences
		loadPreferences(mediaPhoneSettings);
	}

	protected void configureBluetoothObserver(SharedPreferences mediaPhoneSettings, Resources res) {
		boolean watchForFiles = res.getBoolean(R.bool.default_watch_for_files);
		try {
			watchForFiles = mediaPhoneSettings.getBoolean(getString(R.string.key_watch_for_files), watchForFiles);
		} catch (Exception e) {
			watchForFiles = res.getBoolean(R.bool.default_watch_for_files);
		}
		try {
			if (watchForFiles) {
				// file changes are handled in startWatchingBluetooth();
				((MediaPhoneApplication) getApplication()).startWatchingBluetooth(false); // don't watch if bt not enabled

			} else {
				((MediaPhoneApplication) getApplication()).stopWatchingBluetooth();
			}
		} catch (ClassCastException e) {
			// LGE LG X Style (k6b) somehow causes this exception with the cast to MediaPhoneApplication
			// (reported via Google Play)
		}
	}

	public void checkDirectoriesExist() {

		// nothing will work, and previously saved files will not load
		if (MediaPhone.DIRECTORY_STORAGE == null) {

			// if we're not in the main activity, quit everything else and launch the narrative browser to exit
			if (!((Object) MediaPhoneActivity.this instanceof NarrativeBrowserActivity)) {
				Intent homeIntent = new Intent(this, NarrativeBrowserActivity.class);
				homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
				startActivity(homeIntent);
				Log.d(DebugUtilities.getLogTag(this), "Couldn't open storage directory - clearing top to exit");
				return;
			}

			SharedPreferences mediaPhoneSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
			final String storageKey = getString(R.string.key_use_external_storage);
			if (mediaPhoneSettings.contains(storageKey)) {
				if (mediaPhoneSettings.getBoolean(storageKey, true)) { // defValue is irrelevant, we know value exists
					if (!isFinishing()) {
						UIUtilities.showToast(MediaPhoneActivity.this, R.string.error_opening_narrative_content_sd);
					}
					Log.d(DebugUtilities.getLogTag(this), "Couldn't open storage directory (SD card) - exiting");
					finish();
					return;
				}
			}

			if (!isFinishing()) {
				UIUtilities.showToast(MediaPhoneActivity.this, R.string.error_opening_narrative_content);
			}
			Log.d(DebugUtilities.getLogTag(this), "Couldn't open storage directory - exiting");
			finish();
			return;
		}

		// thumbnail cache won't work, but not really fatal (thumbnails will be loaded into memory on demand)
		if (MediaPhone.DIRECTORY_THUMBS == null) {
			Log.d(DebugUtilities.getLogTag(this), "Thumbnail directory not found");
		}

		// external narrative sending (Bluetooth/YouTube etc) may not work, but not really fatal (will warn on export)
		if (MediaPhone.DIRECTORY_TEMP == null) {
			Log.d(DebugUtilities.getLogTag(this), "Temporary directory not found - will warn before narrative export");
		}

		// bluetooth directory availability may have changed if we're calling from an SD card availability notification
		configureBluetoothObserver(PreferenceManager.getDefaultSharedPreferences(MediaPhoneActivity.this), getResources());
	}

	protected void onBluetoothServiceRegistered() {
		// override this method to get a notification when the bluetooth service has been registered
	}

	public void processIncomingFiles(Message msg) {

		// deal with messages from the BluetoothObserver
		Bundle fileData = msg.peekData();
		if (fileData == null) {
			return; // error - no parameters passed
		}

		String importedFileName = fileData.getString(MediaUtilities.KEY_FILE_NAME);
		if (importedFileName == null) {
			if (msg.what == MediaUtilities.MSG_IMPORT_SERVICE_REGISTERED) {
				onBluetoothServiceRegistered();
			}
			return; // error - no filename
		}

		// get the imported file object
		final File importedFile = new File(importedFileName);
		if (!importedFile.canRead() || !importedFile.canWrite()) {
			if (MediaPhone.IMPORT_DELETE_AFTER_IMPORTING) {
				importedFile.delete(); // error - probably won't work, but might
				// as well try; doesn't throw, so is okay
			}
			return;
		}

		final int messageType = msg.what;
		switch (messageType) {
			case MediaUtilities.MSG_RECEIVED_IMPORT_FILE:
				UIUtilities.showToast(MediaPhoneActivity.this, R.string.import_starting);
				break;

			case MediaUtilities.MSG_RECEIVED_SMIL_FILE:
			case MediaUtilities.MSG_RECEIVED_HTML_FILE:
			case MediaUtilities.MSG_RECEIVED_MOV_FILE:
				if (MediaPhone.IMPORT_CONFIRM_IMPORTING) {
					if (MediaPhoneActivity.this.isFinishing()) {
						if (!(MediaPhoneActivity.this instanceof NarrativeBrowserActivity)) {
							// TODO: send a delayed message to the next task? (can't from NarrativeBrowser - app exit)
						}
					} else {
						AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
						builder.setTitle(R.string.import_file_confirmation);
						// fake that we're using the SMIL file if we're actually using .sync.jpg
						builder.setMessage(getString(R.string.import_file_hint, importedFile.getName().replace(MediaUtilities
								.SYNC_FILE_EXTENSION, "").replace(MediaUtilities.SMIL_FILE_EXTENSION, "")));
						builder.setNegativeButton(R.string.import_not_now, null);
						builder.setPositiveButton(R.string.import_file, new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int whichButton) {
								importFiles(messageType, importedFile);
							}
						});
						AlertDialog alert = builder.create();
						alert.show();
					}
				} else {
					importFiles(messageType, importedFile);
				}
				break;
		}
	}

	private void importFiles(int type, File receivedFile) {
		ArrayList<FrameMediaContainer> narrativeFrames = null;
		int sequenceIncrement = getResources().getInteger(R.integer.frame_narrative_sequence_increment);

		switch (type) {
			case MediaUtilities.MSG_RECEIVED_SMIL_FILE:
				narrativeFrames = ImportedFileParser.importSMILNarrative(getContentResolver(), receivedFile, sequenceIncrement);
				break;
			case MediaUtilities.MSG_RECEIVED_HTML_FILE:
				// UIUtilities.showToast(MediaPhoneActivity.this, R.string.html_feature_coming_soon);
				// TODO: will we ever realistically implement this?
				narrativeFrames = ImportedFileParser.importHTMLNarrative(getContentResolver(), receivedFile, sequenceIncrement);
				break;

			case MediaUtilities.MSG_RECEIVED_MOV_FILE:

				narrativeFrames = ImportedFileParser.importMOVNarrative(receivedFile);
				break;
		}

		importFrames(narrativeFrames);
	}

	private void importFrames(ArrayList<FrameMediaContainer> narrativeFrames) {
		// import - start a new task or add to existing
		// TODO: do we need to keep the screen alive? (so cancelled tasks don't get stuck - better to use fragments...)
		if (narrativeFrames != null && narrativeFrames.size() > 0) {
			if (mImportFramesTask != null) {
				mImportFramesTask.addFramesToImport(narrativeFrames);
			} else {
				mImportFramesTask = new ImportFramesTask(MediaPhoneActivity.this);
				mImportFramesTask.addFramesToImport(narrativeFrames);
				mImportFramesTask.execute(); // TODO: deal with post-4.0 single thread AsyncTask - use executeOnExecutor
			}
		}
	}

	protected void onImportProgressUpdate(int currentProgress, int newMaximum) {
		if (mImportFramesDialogShown && mImportFramesProgressDialog != null) {
			mImportFramesProgressDialog.setProgress(currentProgress);
			mImportFramesProgressDialog.setMax(newMaximum);
		}
	}

	protected void onImportTaskCompleted() {
		// all frames imported - remove the reference, but start a new thread for any frames added since we finished
		ArrayList<FrameMediaContainer> newFrames = null;
		if (mImportFramesTask != null) {
			if (mImportFramesTask.getFramesSize() > 0) {
				newFrames = (ArrayList<FrameMediaContainer>) mImportFramesTask.getFrames();
			}
			mImportFramesTask = null;
		}

		// can only interact with dialogs this instance actually showed
		if (mImportFramesDialogShown) {
			if (mImportFramesProgressDialog != null) {
				mImportFramesProgressDialog.setProgress(mImportFramesProgressDialog.getMax());
			}
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					safeDismissDialog(R.id.dialog_importing_in_progress);
				}
			}, 250); // delayed so the view has time to update with the completed state
			mImportFramesDialogShown = false;
		}
		mImportFramesProgressDialog = null;

		// import any frames that were queued after we finished
		if (newFrames != null) {
			importFrames(newFrames);
		} else {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.import_finished);

			// re-enable/disable bluetooth watcher (for manual scans, the observer needs to be temporarily enabled)
			SharedPreferences mediaPhoneSettings = PreferenceManager.getDefaultSharedPreferences(MediaPhoneActivity.this);
			configureBluetoothObserver(mediaPhoneSettings, getResources());
		}
	}

	protected void saveLastEditedFrame(String frameInternalId) {
		SharedPreferences frameIdSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor prefsEditor = frameIdSettings.edit();
		prefsEditor.putString(getString(R.string.key_last_edited_frame), frameInternalId);
		prefsEditor.commit(); // this *must* happen before we return
	}

	protected String loadLastEditedFrame() {
		SharedPreferences frameIdSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		return frameIdSettings.getString(getString(R.string.key_last_edited_frame), null);
	}

	/**
	 * Inserts a frame after the one containing this media item, and returns the new frame's internal id
	 *
	 * @param existingMedia
	 * @return
	 */
	protected String insertFrameAfterMedia(MediaItem existingMedia) {
		String insertAfterId = existingMedia.getParentId();
		ContentResolver contentResolver = getContentResolver();
		FrameItem existingParent = FramesManager.findFrameByInternalId(contentResolver, insertAfterId);
		if (existingParent == null) {
			return null;
		}

		// create a new frame (but don't load the its icon yet - it will be loaded (or deleted) when we return)
		final String narrativeId = existingParent.getParentId();
		final FrameItem newFrame = new FrameItem(narrativeId, -1);
		final String newFrameId = newFrame.getInternalId();
		FramesManager.addFrame(getResources(), contentResolver, newFrame, false); // must add before calculating seq id

		// get and update any inherited media
		ArrayList<MediaItem> inheritedMedia = MediaManager.findMediaByParentId(contentResolver, insertAfterId);
		for (final MediaItem media : inheritedMedia) {
			if (media.getSpanFrames()) {
				MediaManager.addMediaLink(contentResolver, newFrameId, media.getInternalId());
			}
		}

		// get and update the required narrative sequence id
		final int narrativeSequenceId = FramesManager.adjustNarrativeSequenceIds(getResources(), getContentResolver(),
				narrativeId, insertAfterId);
		newFrame.setNarrativeSequenceId(narrativeSequenceId);
		FramesManager.updateFrame(contentResolver, newFrame);

		return newFrameId;
	}

	/**
	 * Switch from one frame to another. Will call onBackPressed() on the calling activity
	 *
	 * @param currentFrameId
	 * @param buttonId
	 * @param newFrameId
	 * @param showOptionsMenu
	 * @return
	 */
	protected boolean switchFrames(String currentFrameId, int buttonId, String newFrameId, boolean showOptionsMenu) {
		if (currentFrameId == null) {
			return false;
		}
		ContentResolver contentResolver = getContentResolver();
		FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, currentFrameId);
		ArrayList<String> narrativeFrameIds = FramesManager.findFrameIdsByParentId(contentResolver, currentFrame.getParentId());
		int currentPosition = narrativeFrameIds.indexOf(currentFrameId);
		int newFramePosition = -1;
		int inAnimation = R.anim.slide_in_from_right;
		int outAnimation = R.anim.slide_out_to_left;
		switch (buttonId) {
			case 0:
				newFramePosition = narrativeFrameIds.indexOf(newFrameId);
				if (currentPosition < newFramePosition) {
					inAnimation = R.anim.slide_in_from_left;
					outAnimation = R.anim.slide_out_to_right;
				}
				break;
			case R.id.menu_previous_frame:
				if (currentPosition > 0) {
					newFramePosition = currentPosition - 1;
				}
				inAnimation = R.anim.slide_in_from_left;
				outAnimation = R.anim.slide_out_to_right;
				break;
			case R.id.menu_next_frame:
				if (currentPosition < narrativeFrameIds.size() - 1) {
					newFramePosition = currentPosition + 1;
				}
				break;
		}
		if (newFramePosition >= 0) {
			final Intent nextPreviousFrameIntent = new Intent(MediaPhoneActivity.this, FrameEditorActivity.class);
			nextPreviousFrameIntent.putExtra(getString(R.string.extra_internal_id), narrativeFrameIds.get(newFramePosition));

			// this allows us to prevent showing first activity launch hints repeatedly
			nextPreviousFrameIntent.putExtra(getString(R.string.extra_switched_frames), true);

			// for API 11 and above, buttons are in the action bar, so this is unnecessary
			//if (showOptionsMenu && Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
			//	nextPreviousFrameIntent.putExtra(getString(R.string.extra_show_options_menu), true);
			//}

			startActivity(nextPreviousFrameIntent); // no result so that the original can exit (TODO: will it?)
			closeOptionsMenu(); // so onBackPressed doesn't just do this
			onBackPressed();
			overridePendingTransition(inAnimation, outAnimation);

			// may have switched without actually adding media - delete frame if so (only applicable from media, as the
			// frame editor handles this on back pressed, but setting a frame to deleted twice has no ill effects)
			if (MediaManager.countMediaByParentId(contentResolver, currentFrameId) <= 0) {
				currentFrame.setDeleted(true);
				FramesManager.updateFrame(contentResolver, currentFrame);
			}
			return true;
		} else {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.next_previous_no_more_frames);
			return false;
		}
	}

	/**
	 * Update the span frames button with the given id to show the correct icon for media spanning multiple frames
	 *
	 * @param buttonId
	 * @param spanFrames
	 * @param animate    Whether to animate the transition between button icons
	 */
	protected void updateSpanFramesButtonIcon(int buttonId, boolean spanFrames, boolean animate) {
		CenteredImageTextButton spanFramesButton = ((CenteredImageTextButton) findViewById(buttonId));
		if (animate) {
			AnimationDrawable spanAnimation = (AnimationDrawable) getResources().getDrawable(spanFrames ? R.drawable
					.span_frames_animation_on : R.drawable.span_frames_animation_off);
			spanFramesButton.setCompoundDrawablesWithIntrinsicBounds(null, spanAnimation, null, null);
			spanAnimation.start();
		} else {
			spanFramesButton.setCompoundDrawablesWithIntrinsicBounds(0, spanFrames ? R.drawable.ic_menu_span_frames_on : R
					.drawable.ic_menu_span_frames_off, 0, 0);
		}
	}

	/**
	 * Update the icon of the parent frame of the given media item; and, if this media item is spanning, update any
	 * applicable frame icons after the one containing this media item (used when the media has changed)
	 *
	 * @param mediaItem
	 * @param preUpdateTask a Runnable that will be run before updating anything - used, for example, to make sure text
	 *                      is saved before updating icons
	 */
	protected void updateMediaFrameIcons(MediaItem mediaItem, Runnable preUpdateTask) {
		// Because database/file edits are buffered, we need to use a runnable to update icons in the same thread as
		// those edits (currently only used for text). A side-effect of updating text in a thread is that the file is
		// locked when we try to get the summary for the frame editor - for now, running the save and icon tasks on the
		// UI thread deal with this. TODO: in future, save the text snippet to the database, or to a shared preference?
		final boolean updateCurrentIcon;
		if (preUpdateTask != null) {
			preUpdateTask.run();
			FramesManager.reloadFrameIcon(getResources(), getContentResolver(), mediaItem.getParentId());
			updateCurrentIcon = false;
		} else {
			// ensure the previous icon is not shown - remove from cache
			ImageCacheUtilities.setLoadingIcon(FrameItem.getCacheId(mediaItem.getParentId()));
			updateCurrentIcon = true;
		}

		final String parentId = mediaItem.getParentId();
		final String internalId = mediaItem.getInternalId();
		final boolean updateMultipleIcons = mediaItem.getSpanFrames();
		runQueuedBackgroundTask(new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {
				ContentResolver contentResolver = getContentResolver();
				Resources resources = getResources();
				if (updateCurrentIcon) {
					FramesManager.reloadFrameIcon(getResources(), contentResolver, parentId);
				}

				// if applicable, update all frame items that link to this media item
				if (updateMultipleIcons) {
					FramesManager.reloadFrameIcons(resources, contentResolver, MediaManager.findLinkedParentIdsByMediaId
							(contentResolver, internalId));
				}
			}
		});
	}

	/**
	 * Removes the link to the media item with mediaId from startFrameId and all frames following - used when replacing
	 * a linked media item with a new item
	 *
	 * @param mediaId
	 * @param startFrameId
	 */
	protected void endLinkedMediaItem(final String mediaId, final String startFrameId) {
		// because database access can take time, we need to do db and icon updates in the same thread
		runQueuedBackgroundTask(new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {
				// we only need the parent ids of frames after (not including) the current one
				ContentResolver contentResolver = getContentResolver();
				ArrayList<String> narrativeFrameIds = FramesManager.getFollowingFrameIds(contentResolver, startFrameId, false);
				if (narrativeFrameIds == null) {
					return;
				}

				// hold a list of icons to update, so we only can do database manipulation first
				ArrayList<String> iconsToUpdate = new ArrayList<>();

				// now remove the media link from all of these frames - we do this way rather than just selecting links
				// because links are not ordered TODO: could join select with frames table, ordering by sequence id?
				for (final String frameId : narrativeFrameIds) {
					boolean mediaFound = false;

					// need to check all following frames until we find one that doesn't link to this item
					ArrayList<String> frameMedia = MediaManager.findLinkedMediaIdsByParentId(contentResolver, frameId);
					for (String media : frameMedia) {
						if (mediaId.equals(media)) {
							mediaFound = true;
							break;
						}
					}

					// remove the media link and queue updating icons for changed frames; remove frames now blank
					if (mediaFound) {
						MediaManager.deleteMediaLink(contentResolver, frameId, mediaId);

						if (MediaManager.countMediaByParentId(contentResolver, frameId, false) > 0) {
							iconsToUpdate.add(frameId);
						} else {
							// don't allow frames that don't have any normal (i.e., non-linked media) - set deleted
							FrameItem frameToDelete = FramesManager.findFrameByInternalId(contentResolver, frameId);
							frameToDelete.setDeleted(true);
							FramesManager.updateFrame(contentResolver, frameToDelete);
						}
					} else {
						break;
					}
				}

				// finally, update icons for changed frames
				FramesManager.reloadFrameIcons(getResources(), contentResolver, iconsToUpdate);
			}
		});

		// the current media item is a special case - we do it separately because we don't want to accidentally delete
		// the frame if its only current content is the inherited item; there's also no need to update the icon because
		// if they do edit we'll update automatically; if they don't then we'll have to undo all these changes...
		MediaManager.deleteMediaLink(getContentResolver(), startFrameId, mediaId);
	}

	/**
	 * Make sure any linked media prior to the given frame is propagated to that frame and any after it that apply. Will
	 * always update startFrameId's icon. Used when deleting a media item or frame.
	 *
	 * @param startFrameId
	 * @param deletedMediaItem        a media item from startFrameId has already been deleted, and so should also be checked in
	 *                                the following frames for spanning - used only from media activities (may be null)
	 * @param frameMediaItemsToDelete a list of media item ids that should be removed from startFrameId and all
	 *                                following frames - used only from frame editor (may be null)
	 */
	protected void inheritMediaAndDeleteItemLinks(final String startFrameId, final MediaItem deletedMediaItem, final
	ArrayList<String> frameMediaItemsToDelete) {

		// first get a list of the frames that could need updating
		ContentResolver contentResolver = getContentResolver();
		FrameItem parentFrame = FramesManager.findFrameByInternalId(contentResolver, startFrameId);
		final ArrayList<String> narrativeFrameIds = FramesManager.getFollowingFrameIds(contentResolver, parentFrame, true);
		if (narrativeFrameIds == null) {
			return; // no frames found - error; won't be able to update anything
		}

		// and a list of icons that we will need to update
		// this also fixes an issue where database conflicts were occurring (random quits) in the task thread
		final ArrayList<String> iconsToUpdate = new ArrayList<>();
		if (deletedMediaItem != null) {
			iconsToUpdate.addAll(MediaManager.findLinkedParentIdsByMediaId(contentResolver, deletedMediaItem.getInternalId()));
		}
		if (frameMediaItemsToDelete != null) {
			for (String mediaId : frameMediaItemsToDelete) {
				iconsToUpdate.addAll(MediaManager.findLinkedParentIdsByMediaId(contentResolver, mediaId));
			}
		}
		// remove duplicates by adding to a hash set then retrieving (LinkedHashSet also preserves order)
		LinkedHashSet<String> setItems = new LinkedHashSet<>(iconsToUpdate);
		iconsToUpdate.clear();
		iconsToUpdate.addAll(setItems);

		// when using from the frame editor the previous icons are briefly displayed - remove from cache to prevent this
		// - we won't definitely be updating all of these, but removing from the cache gives a better experience
		ImageCacheUtilities.setLoadingIcon(FrameItem.getCacheId(startFrameId));
		for (String frameId : iconsToUpdate) {
			ImageCacheUtilities.setLoadingIcon(FrameItem.getCacheId(frameId));
		}

		// save loading things twice if we can - get the previous frame and its media
		final String previousFrameId;
		if (narrativeFrameIds.size() >= 1) {
			previousFrameId = narrativeFrameIds.remove(0);
		} else {
			previousFrameId = null;
		}

		// get inherited media (items from the previous frame that span multiple frames)
		final ArrayList<MediaItem> inheritedMedia = new ArrayList<>();
		if (previousFrameId != null) {
			ArrayList<MediaItem> prevMedia = MediaManager.findMediaByParentId(contentResolver, previousFrameId);
			// filter - we only need items that span frames
			for (final MediaItem media : prevMedia) {
				if (media.getSpanFrames()) {
					inheritedMedia.add(media);
				}
			}
		}

		// because we delete and propagate all icons at once, the current frame's propagated media may not have been
		// updated by the time we return, and may not show in the frame editor - to deal with this we propagate the
		// current frame's media first, on the UI thread, but only for the type of deletedMediaItem (no others apply)
		if (deletedMediaItem != null) {
			// link the previous frame's media of this item's type (if not currently linked)
			int mediaType = deletedMediaItem.getType();
			if (previousFrameId != null) {
				ArrayList<String> currentMedia = MediaManager.findLinkedMediaIdsByParentId(contentResolver, startFrameId); //
				// need to compare with existing links so we don't re-add audio when deleting one
				for (MediaItem media : inheritedMedia) {
					final String mediaId = media.getInternalId();
					if (media.getType() == mediaType && !currentMedia.contains(mediaId)) {
						MediaManager.addMediaLink(contentResolver, startFrameId, mediaId);
					}
				}
			}
		}

		// because database access can take time, we need to do db and icon updates in the same thread
		runQueuedBackgroundTask(new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {

				// first, delete the media items as requested
				ContentResolver contentResolver = getContentResolver();
				if (frameMediaItemsToDelete != null) {
					for (String mediaId : frameMediaItemsToDelete) {
						final MediaItem deletedMedia = MediaManager.findMediaByInternalId(contentResolver, mediaId);
						if (deletedMedia != null) {
							deletedMedia.setDeleted(true);
							MediaManager.updateMedia(contentResolver, deletedMedia);
						}
					}
				}

				// if we're removing a frame's media, we need to add the current frame to propagate media to (in this
				// case, startFrameId is the frame after the deleted frame, rather than the frame media is removed from)
				if (frameMediaItemsToDelete != null) {
					narrativeFrameIds.add(0, startFrameId);
				}

				// check we have enough frames to operate on
				if (narrativeFrameIds.size() == 0 || (inheritedMedia.size() == 0 && iconsToUpdate.size() == 0)) {
					// no spanning items or media to delete; nothing to do except update the current frame's icon
					FramesManager.reloadFrameIcon(getResources(), contentResolver, startFrameId);
					return;
				}

				// now remove previously propagated media and propagate media from earlier frames
				boolean deletedMediaComplete = false;
				iconsToUpdate.add(0, startFrameId); // always update the current icon (first for better appearance)
				for (final String frameId : narrativeFrameIds) {

					// update icons to remove this media item from its propagated frames
					if (!deletedMediaComplete) {

						// delete frames that are now blank
						if (iconsToUpdate.contains(frameId)) {
							if (MediaManager.countMediaByParentId(contentResolver, frameId, false) <= 0) {
								// don't allow frames that don't have any normal (i.e., non-linked media) - set deleted
								iconsToUpdate.remove(frameId); // no need to update this icon any more - will not exist
								FrameItem frameToDelete = FramesManager.findFrameByInternalId(contentResolver, frameId);
								frameToDelete.setDeleted(true);
								FramesManager.updateFrame(contentResolver, frameToDelete);
							}
						} else {
							// we've reached the end of spanning media if there's no link to a frame
							deletedMediaComplete = true;
						}
					}

					// need to check all following frames until we find those with media of this type
					if (inheritedMedia.size() > 0) {
						// check this frame's media for collisions with spanning items
						ArrayList<MediaItem> frameMedia = MediaManager.findMediaByParentId(contentResolver, frameId, false); //
						// no inherited items needed (now allow only one spanning audio item per frame)
						ArrayList<MediaItem> mediaToRemove = new ArrayList<>();
						int audioCount = 0;
						boolean hasSpanningAudio = false;
						for (MediaItem existingMedia : frameMedia) {
							if (existingMedia.getType() == MediaPhoneProvider.TYPE_AUDIO) {
								audioCount += 1;
								if (existingMedia.getSpanFrames()) {
									hasSpanningAudio = true;
									break;
								}
							}
						}
						for (final MediaItem newMedia : inheritedMedia) {
							int currentType = newMedia.getType();
							if (currentType == MediaPhoneProvider.TYPE_AUDIO && (audioCount >= MediaPhone.MAX_AUDIO_ITEMS ||
									hasSpanningAudio)) {
								mediaToRemove.add(newMedia); // spanning audio or >= max stops spanning - finished item
							} else {
								for (MediaItem existingMedia : frameMedia) {
									if (existingMedia.getType() == currentType) {
										mediaToRemove.add(newMedia); // any other media overrides spanning - item done
									}
								}
							}
						}
						frameMedia.removeAll(mediaToRemove);

						// any media still in the propagated list should be added to this frame
						if (inheritedMedia.size() > 0) {
							for (MediaItem propagatedMedia : inheritedMedia) {
								MediaManager.addMediaLink(contentResolver, frameId, propagatedMedia.getInternalId());
							}
							if (!iconsToUpdate.contains(frameId)) { // only add items we haven't already queued
								iconsToUpdate.add(frameId);
							}
						}

					} else if (deletedMediaComplete) {
						break; // both deleting and propagating items are complete
					}
				}

				// remove all links to the deleted media items
				if (deletedMediaItem != null) {
					MediaManager.deleteMediaLinks(contentResolver, deletedMediaItem.getInternalId());
				}
				if (frameMediaItemsToDelete != null) {
					for (String mediaLink : frameMediaItemsToDelete) {
						MediaManager.deleteMediaLinks(contentResolver, mediaLink);
					}
				}

				// finally, update icons for changed frames (must be done last so the links no longer exist)
				FramesManager.reloadFrameIcons(getResources(), contentResolver, iconsToUpdate);
			}
		});
	}

	/**
	 * Toggle whether the media item with this id spans multiple frames or not
	 *
	 * @param mediaItem
	 * @return The new state of the media item (true for frame spanning; false otherwise)
	 */
	@SuppressLint("RestrictedApi") // for incorrect detection of invalidateOptionsMenu(); as an error
	protected boolean toggleFrameSpanningMedia(MediaItem mediaItem) {

		final boolean isFrameSpanning = mediaItem.getSpanFrames();
		final String mediaId = mediaItem.getInternalId();
		final String parentId = mediaItem.getParentId();
		final int mediaType = mediaItem.getType();

		// because database access can take time, we need to do db and icon updates in the same thread
		runQueuedBackgroundTask(new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {

				// hold a list of icons to update, so we update icons after all database edits are complete
				ArrayList<String> iconsToUpdate;
				ContentResolver contentResolver = getContentResolver();

				if (isFrameSpanning) {

					// TODO: here we need to inherit previous audio when turning spanning off - combine this function
					// with inheritMediaAndDeleteItemLinks?

					// get the known links to this media item and check the frames contain other media; remove if not
					iconsToUpdate = MediaManager.findLinkedParentIdsByMediaId(contentResolver, mediaId);
					ArrayList<String> removedIcons = new ArrayList<>();
					for (final String frameId : iconsToUpdate) {
						if (MediaManager.countMediaByParentId(contentResolver, frameId, false) <= 0) {
							// don't allow frames that don't have any normal (i.e., non-linked media) - set deleted
							FrameItem frameToDelete = FramesManager.findFrameByInternalId(contentResolver, frameId);
							frameToDelete.setDeleted(true);
							FramesManager.updateFrame(contentResolver, frameToDelete);
							removedIcons.add(frameId); // no need to update this icon any more - will not exist
						}
					}
					iconsToUpdate.removeAll(removedIcons);

					// delete all links to this media item
					MediaManager.deleteMediaLinks(contentResolver, mediaId);

				} else {

					iconsToUpdate = new ArrayList<>();
					ArrayList<String> narrativeFrameIds = FramesManager.getFollowingFrameIds(contentResolver, parentId, false);
					if (narrativeFrameIds == null) {
						return; // nothing we can do - we have no frame ids to propagate to, so can't enable spanning
					}

					// turn this item into a frame-spanning media item by extending it to other frames
					for (String frameId : narrativeFrameIds) {
						// need to add this media to all following frames until one that already has media of this type
						ArrayList<MediaItem> frameMedia = MediaManager.findMediaByParentId(contentResolver, frameId, false); //
						// no inherited items needed (now allow only one spanning audio item per frame)
						boolean mediaFound = false;
						if (mediaType == MediaPhoneProvider.TYPE_AUDIO) {
							int audioCount = 0;
							boolean hasSpanningAudio = false;
							for (MediaItem existingMedia : frameMedia) {
								if (existingMedia.getType() == MediaPhoneProvider.TYPE_AUDIO) {
									audioCount += 1;
									if (existingMedia.getSpanFrames()) {
										hasSpanningAudio = true;
										break;
									}
								}
							}
							if (audioCount >= MediaPhone.MAX_AUDIO_ITEMS || hasSpanningAudio) {
								mediaFound = true; // over max audio items stops spanning - finished item
							}
						} else {
							for (MediaItem media : frameMedia) {
								if (media.getType() == mediaType) {
									mediaFound = true; // any other media overrides spanning - item done
									break;
								}
							}
						}
						if (!mediaFound) {
							// add a linked media element and update the icon of the frame in question
							MediaManager.addMediaLink(contentResolver, frameId, mediaId);
							ImageCacheUtilities.setLoadingIcon(FrameItem.getCacheId(frameId)); // for better ui flow
							iconsToUpdate.add(frameId);
						} else {
							break;
						}
					}
				}

				// finally, update icons for changed frames (must be done last so the link no longer exists)
				FramesManager.reloadFrameIcons(getResources(), contentResolver, iconsToUpdate);
			}
		});

		// state has changed, so disabled menu items may be enabled, and vice-versa
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.invalidateOptionsMenu();
		}

		// finally, return the new media spanning state
		mediaItem.setSpanFrames(!isFrameSpanning);
		MediaManager.updateMedia(getContentResolver(), mediaItem);
		return !isFrameSpanning;
	}

	private void sendFiles(final ArrayList<Uri> filesToSend) {
		// send files in a separate task without a dialog so we don't leave the previous progress dialog behind on
		// screen rotation - this is a bit of a hack, but it works
		runImmediateBackgroundTask(new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {
				if (filesToSend == null || filesToSend.size() <= 0) {
					return;
				}

				// ensure files are accessible to send - bit of a last-ditch effort for when temp is on internal storage
				for (Uri fileUri : filesToSend) {
					IOUtilities.setFullyPublic(new File(fileUri.getPath()));
				}

				// also see: http://stackoverflow.com/questions/2344768/
				// could use application/smil+xml (or html), or video/quicktime, but then there's no bluetooth option
				final Intent sendIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
				sendIntent.setType(getString(R.string.export_mime_type));
				sendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, filesToSend);

				final Intent chooserIntent = Intent.createChooser(sendIntent, getString(R.string.export_narrative_title));

				// an extra activity at the start of the list that moves exported files to SD, but only if SD available
				if (IOUtilities.externalStorageIsWritable()) {
					final Intent targetedShareIntent = new Intent(MediaPhoneActivity.this, SaveNarrativeActivity.class);
					targetedShareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
					targetedShareIntent.setType(getString(R.string.export_mime_type));
					targetedShareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, filesToSend);
					chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{targetedShareIntent});
				}

				startActivity(chooserIntent); // single task mode; no return value given
			}
		});
	}

	protected void deleteNarrativeDialog(final String narrativeInternalId) {
		AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
		builder.setTitle(R.string.delete_narrative_confirmation);
		builder.setMessage(R.string.delete_narrative_hint);
		builder.setNegativeButton(R.string.button_cancel, null);
		builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int whichButton) {
				int numFramesDeleted = FramesManager.countFramesByParentId(getContentResolver(), narrativeInternalId);
				AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
				builder.setTitle(R.string.delete_narrative_second_confirmation);
				builder.setMessage(getResources().getQuantityString(R.plurals.delete_narrative_second_hint, numFramesDeleted,
						numFramesDeleted));
				builder.setNegativeButton(R.string.button_cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						NarrativeItem narrativeToDelete = NarrativesManager.findNarrativeByInternalId(contentResolver,
								narrativeInternalId);
						narrativeToDelete.setDeleted(true);
						NarrativesManager.updateNarrative(contentResolver, narrativeToDelete);
						UIUtilities.showToast(MediaPhoneActivity.this, R.string.delete_narrative_succeeded);
						onBackPressed();
					}
				});
				AlertDialog alert = builder.create();
				alert.show();
			}
		});
		AlertDialog alert = builder.create();
		alert.show();
	}

	protected void exportContent(final String narrativeId, final boolean isTemplate) {
		if (ContextCompat.checkSelfPermission(MediaPhoneActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
				PackageManager.PERMISSION_GRANTED) {
			if (ActivityCompat.shouldShowRequestPermissionRationale(MediaPhoneActivity.this, Manifest.permission
					.WRITE_EXTERNAL_STORAGE)) {
				UIUtilities.showFormattedToast(MediaPhoneActivity.this, R.string.permission_storage_rationale, getString(R
						.string.app_name));
			}
			ActivityCompat.requestPermissions(MediaPhoneActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
					PERMISSION_EXPORT_STORAGE);
		}

		if (MediaPhone.DIRECTORY_TEMP == null) {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.export_missing_directory, true);
			return;
		}
		if (IOUtilities.isInternalPath(MediaPhone.DIRECTORY_TEMP.getAbsolutePath())) {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.export_potential_problem, true);
		}

		// important to keep awake to export because we only have one chance to display the export options
		// after creating mov or smil file (will be cancelled on screen unlock; Android is weird)
		// TODO: move to a better (e.g. notification bar) method of exporting?
		UIUtilities.acquireKeepScreenOn(getWindow());

		final CharSequence[] items = {getString(R.string.export_mov), getString(R.string.export_html), getString(R.string
				.export_smil, getString(R.string.app_name))};

		AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
		builder.setTitle(R.string.export_narrative_title);
		// builder.setMessage(R.string.send_narrative_hint); //breaks dialog
		builder.setNegativeButton(R.string.button_cancel, null);
		builder.setItems(items, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int item) {
				ContentResolver contentResolver = getContentResolver();

				NarrativeItem thisNarrative;
				if (isTemplate) {
					thisNarrative = NarrativesManager.findTemplateByInternalId(contentResolver, narrativeId);
				} else {
					thisNarrative = NarrativesManager.findNarrativeByInternalId(contentResolver, narrativeId);
				}
				final ArrayList<FrameMediaContainer> contentList = thisNarrative.getContentList(contentResolver);

				// random name to counter repeat sending name issues
				String exportId = MediaPhoneProvider.getNewInternalId().substring(0, 8);
				final String exportName = String.format(Locale.ENGLISH, "%s-%s", getString(R.string.app_name).replaceAll
						("[^a-zA-Z0-9]+", "-").toLowerCase(Locale.ENGLISH), exportId);

				Resources res = getResources();
				final Map<Integer, Object> settings = new Hashtable<>();
				settings.put(MediaUtilities.KEY_AUDIO_RESOURCE_ID, R.raw.ic_audio_playback);

				// some output settings (TODO: make sure HTML version respects these)
				settings.put(MediaUtilities.KEY_BACKGROUND_COLOUR, res.getColor(R.color.export_background));
				settings.put(MediaUtilities.KEY_TEXT_COLOUR_NO_IMAGE, res.getColor(R.color.export_text_no_image));
				settings.put(MediaUtilities.KEY_TEXT_COLOUR_WITH_IMAGE, res.getColor(R.color.export_text_with_image));
				settings.put(MediaUtilities.KEY_TEXT_BACKGROUND_COLOUR, res.getColor(R.color.export_text_background));
				// TODO: do we want to do getDimensionPixelSize for export?
				settings.put(MediaUtilities.KEY_TEXT_SPACING, res.getDimensionPixelSize(R.dimen.export_icon_text_padding));
				settings.put(MediaUtilities.KEY_TEXT_CORNER_RADIUS, res.getDimensionPixelSize(R.dimen
						.export_icon_text_corner_radius));
				settings.put(MediaUtilities.KEY_TEXT_BACKGROUND_SPAN_WIDTH, Build.VERSION.SDK_INT >= Build.VERSION_CODES
						.HONEYCOMB);
				settings.put(MediaUtilities.KEY_MAX_TEXT_FONT_SIZE, res.getDimensionPixelSize(R.dimen.export_maximum_text_size));
				settings.put(MediaUtilities.KEY_MAX_TEXT_CHARACTERS_PER_LINE, res.getInteger(R.integer
						.export_maximum_text_characters_per_line));
				settings.put(MediaUtilities.KEY_MAX_TEXT_HEIGHT_WITH_IMAGE, res.getDimensionPixelSize(R.dimen
						.export_maximum_text_height_with_image));

				if (contentList != null && contentList.size() > 0) {
					switch (item) {
						case 0:
							settings.put(MediaUtilities.KEY_OUTPUT_WIDTH, res.getInteger(R.integer.export_mov_width));
							settings.put(MediaUtilities.KEY_OUTPUT_HEIGHT, res.getInteger(R.integer.export_mov_height));
							settings.put(MediaUtilities.KEY_IMAGE_QUALITY, res.getInteger(R.integer.camera_jpeg_save_quality));

							// set audio resampling rate
							SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(MediaPhoneActivity.this);
							int newBitrate;
							try {
								String requestedBitrateString = preferences.getString(getString(R.string.key_audio_resampling_rate), null);
								newBitrate = Integer.valueOf(requestedBitrateString);
							} catch (Exception e) {
								newBitrate = res.getInteger(R.integer.default_resampling_rate); // 0 = no resampling
							}
							settings.put(MediaUtilities.KEY_RESAMPLE_AUDIO, newBitrate);

							// all image files are compatible - we just convert to JPEG when writing the movie,
							// but we need to check for incompatible audio that we can't convert to PCM
							boolean incompatibleAudio = false;
							for (FrameMediaContainer frame : contentList) {
								for (String audioPath : frame.mAudioPaths) {
									if (!AndroidUtilities.arrayContains(MediaUtilities.MOV_AUDIO_FILE_EXTENSIONS, IOUtilities
											.getFileExtension(audioPath))) {
										incompatibleAudio = true;
										break;
									}
								}
								if (incompatibleAudio) {
									break;
								}
							}

							if (incompatibleAudio) {
								AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
								builder.setTitle(R.string.mov_export_mov_incompatible_title);
								builder.setMessage(R.string.mov_export_mov_incompatible_summary);
								builder.setNegativeButton(R.string.button_cancel, null);
								builder.setPositiveButton(R.string.button_continue, new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog, int whichButton) {
										exportMovie(settings, exportName, contentList);
									}
								});
								AlertDialog alert = builder.create();
								alert.show();
							} else {
								exportMovie(settings, exportName, contentList);
							}
							break;

						case 1:
							settings.put(MediaUtilities.KEY_OUTPUT_WIDTH, res.getInteger(R.integer.export_html_width));
							settings.put(MediaUtilities.KEY_OUTPUT_HEIGHT, res.getInteger(R.integer.export_html_height));
							runExportNarrativesTask(new BackgroundRunnable() {
								private int mTaskResult = 0;

								@Override
								public int getTaskId() {
									return mTaskResult;
								}

								@Override
								public boolean getShowDialog() {
									return true;
								}

								@Override
								public void run() {
									ArrayList<Uri> filesToSend = HTMLUtilities.generateNarrativeHTML(getResources(), new File
											(MediaPhone.DIRECTORY_TEMP, exportName + MediaUtilities.HTML_FILE_EXTENSION),
											contentList, settings);

									if (filesToSend == null || filesToSend.size() <= 0) {
										mTaskResult = R.id.export_creation_failed;
									} else {
										sendFiles(filesToSend);
									}
								}
							});
							break;

						case 2:
							settings.put(MediaUtilities.KEY_OUTPUT_WIDTH, res.getInteger(R.integer.export_smil_width));
							settings.put(MediaUtilities.KEY_OUTPUT_HEIGHT, res.getInteger(R.integer.export_smil_height));
							settings.put(MediaUtilities.KEY_PLAYER_BAR_ADJUSTMENT, res.getInteger(R.integer
									.export_smil_player_bar_adjustment));
							runExportNarrativesTask(new BackgroundRunnable() {
								private int mTaskResult = 0;

								@Override
								public int getTaskId() {
									return mTaskResult;
								}

								@Override
								public boolean getShowDialog() {
									return true;
								}

								@Override
								public void run() {
									ArrayList<Uri> filesToSend = SMILUtilities.generateNarrativeSMIL(getResources(), new File
											(MediaPhone.DIRECTORY_TEMP, exportName + MediaUtilities.SMIL_FILE_EXTENSION),
											contentList, settings);

									if (filesToSend == null || filesToSend.size() <= 0) {
										mTaskResult = R.id.export_creation_failed;
									} else {
										sendFiles(filesToSend);
									}
								}
							});
							break;
					}
				} else {
					UIUtilities.showToast(MediaPhoneActivity.this, (isTemplate ? R.string.export_template_failed : R.string
							.export_narrative_failed));
				}
				dialog.dismiss();
			}
		});
		AlertDialog alert = builder.create();
		alert.show();
	}

	private void exportMovie(final Map<Integer, Object> settings, final String exportName, final ArrayList<FrameMediaContainer>
			contentList) {
		runExportNarrativesTask(new BackgroundRunnable() {
			// mov export is a special case - the id matters at task start time (so we can show the right dialog)
			private int mTaskResult = R.id.export_mov_task_complete;

			@Override
			public int getTaskId() {
				return mTaskResult;
			}

			@Override
			public boolean getShowDialog() {
				return true;
			}

			@Override
			public void run() {
				ArrayList<Uri> movFiles = MOVUtilities.generateNarrativeMOV(getResources(), new File(MediaPhone.DIRECTORY_TEMP,
						exportName + MediaUtilities.MOV_FILE_EXTENSION), contentList, settings);

				// must use media store parameters properly, or YouTube export fails
				// see: http://stackoverflow.com/questions/5884092/
				ArrayList<Uri> filesToSend = new ArrayList<>();
				for (Uri fileUri : movFiles) {
					File outputFile = new File(fileUri.getPath());
					ContentValues content = new ContentValues(5);
					content.put(MediaStore.Video.Media.DATA, outputFile.getAbsolutePath());
					content.put(MediaStore.Video.VideoColumns.SIZE, outputFile.length());
					content.put(Video.VideoColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
					content.put(Video.Media.MIME_TYPE, "video/quicktime");
					content.put(Video.VideoColumns.TITLE, IOUtilities.removeExtension(outputFile.getName()));
					try {
						filesToSend.add(getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, content));
					} catch (SecurityException e) {
						// we don't have permission to insert into the MediaStore (on API > 23 we need to request
						// WRITE_EXTERNAL_STORAGE to obtain this, and if the permission is denied we don't persist in asking)
						filesToSend.add(fileUri);
					}
				}

				if (filesToSend.size() <= 0) {
					mTaskResult = R.id.export_creation_failed;
				} else {
					sendFiles(filesToSend);
				}
			}
		});
	}

	protected void runExportNarrativesTask(BackgroundRunnable r) {
		// export - start a new task or add to existing
		// TODO: do we need to keep the screen alive? (so cancelled tasks don't get stuck - better to use fragments...)
		if (mExportNarrativesTask != null) {
			mExportNarrativesTask.addTask(r);
		} else {
			mExportNarrativesTask = new ExportNarrativesTask(this);
			mExportNarrativesTask.addTask(r);
			mExportNarrativesTask.execute(); // TODO: deal with post-4.0 single thread AsyncTask - use executeOnExecutor
		}
	}

	private void onExportNarrativesTaskProgressUpdate(int taskId) {
		// dismiss dialogs first so we don't leak if onBackgroundTaskCompleted finishes the activity
		if (mExportNarrativeDialogShown) {
			safeDismissDialog(R.id.dialog_export_narrative_in_progress);
			mExportNarrativeDialogShown = false;
		}
		if (mExportVideoDialogShown) {
			safeDismissDialog(R.id.dialog_mov_creator_in_progress);
		}

		switch (taskId) {
			case R.id.export_mov_task_complete:
				if (!mExportVideoDialogShown) {
					// if they dismissed the mov export dialog let them know that it has finished
					UIUtilities.showToast(MediaPhoneActivity.this, R.string.mov_export_task_complete);
				}
				break;
			case R.id.export_creation_failed:
				UIUtilities.showToast(MediaPhoneActivity.this, R.string.export_creation_failed, true);
				break;
		}

		mExportVideoDialogShown = false;
	}

	private void onAllExportNarrativesTasksCompleted() {
		// all tasks complete - remove the reference, but start a new thread for any tasks started since we finished
		List<BackgroundRunnable> newTasks = null;
		if (mExportNarrativesTask != null) {
			if (mExportNarrativesTask.getTasksSize() > 0) {
				newTasks = mExportNarrativesTask.getTasks();
			}
			mExportNarrativesTask = null;
		}

		// can only interact with dialogs this instance actually showed
		if (mExportNarrativeDialogShown) {
			safeDismissDialog(R.id.dialog_export_narrative_in_progress);
			mExportNarrativeDialogShown = false;
		}
		if (mExportVideoDialogShown) {
			safeDismissDialog(R.id.dialog_mov_creator_in_progress);
			mExportVideoDialogShown = false;
		}

		// run any tasks that were queued after we finished
		if (newTasks != null) {
			for (BackgroundRunnable task : newTasks) {
				runExportNarrativesTask(task);
			}
		}
	}

	/**
	 * Run a BackgroundRunnable immediately (i.e. not queued). After running no result will be given (i.e.
	 * onBackgroundTaskCompleted will <b>not</b> be called), and there is no guarantee that any dialogs shown will be
	 * cancelled (e.g. if the screen has rotated). This method is therefore most suitable for tasks whose getTaskId()
	 * returns 0 (indicating that no result is needed), and whose getShowDialog() returns false.
	 */
	protected void runImmediateBackgroundTask(Runnable r) {
		ImmediateBackgroundRunnerTask backgroundTask = new ImmediateBackgroundRunnerTask(r);
		backgroundTask.execute(); // TODO: deal with post-4.0 single thread AsyncTask - use executeOnExecutor
	}

	protected void runQueuedBackgroundTask(BackgroundRunnable r) {
		// queue a job - start a new task or add to existing
		// TODO: do we need to keep the screen alive? (so cancelled tasks don't get stuck - better to use fragments...)
		if (mBackgroundRunnerTask != null) {
			mBackgroundRunnerTask.addTask(r);
		} else {
			mBackgroundRunnerTask = new QueuedBackgroundRunnerTask(this);
			mBackgroundRunnerTask.addTask(r);
			mBackgroundRunnerTask.execute(); // TODO: deal with post-4.0 single thread AsyncTask - use executeOnExecutor
		}
	}

	protected void onBackgroundTaskCompleted(int taskId) {
		// override this in subclasses to get task updates
	}

	// a single task has completed
	private void onBackgroundTaskProgressUpdate(int taskId) {
		// dismiss dialogs first so we don't leak if onBackgroundTaskCompleted finishes the activity
		if (mBackgroundRunnerDialogShown) {
			safeDismissDialog(R.id.dialog_background_runner_in_progress);
			mBackgroundRunnerDialogShown = false;
		}

		// report any task results
		onBackgroundTaskCompleted(taskId);

		// alert when template creation is complete - here as template creation can happen in several places
		// don't do this from template browser as in that case we're copying the other way (i.e. creating narrative)
		if (taskId == R.id.make_load_template_task_complete && !(MediaPhoneActivity.this instanceof TemplateBrowserActivity) &&
				!MediaPhoneActivity.this.isFinishing()) {
			AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
			builder.setTitle(R.string.make_template_confirmation);
			builder.setMessage(R.string.make_template_hint);
			builder.setPositiveButton(R.string.button_ok, null);
			AlertDialog alert = builder.create();
			alert.show();
		}
	}

	private void onAllBackgroundTasksCompleted() {
		// all tasks complete - remove the reference, but start a new thread for any tasks started since we finished
		List<BackgroundRunnable> newTasks = null;
		if (mBackgroundRunnerTask != null) {
			if (mBackgroundRunnerTask.getTasksSize() > 0) {
				newTasks = mBackgroundRunnerTask.getTasks();
			}
			mBackgroundRunnerTask = null;
		}

		// can only interact with dialogs this instance actually showed
		if (mBackgroundRunnerDialogShown) {
			safeDismissDialog(R.id.dialog_background_runner_in_progress);
			mBackgroundRunnerDialogShown = false;
		}

		// run any tasks that were queued after we finished
		if (newTasks != null) {
			for (BackgroundRunnable task : newTasks) {
				runQueuedBackgroundTask(task);
			}
		}
	}

	private class ImportFramesTask extends AsyncTask<FrameMediaContainer, Void, Void> {

		private MediaPhoneActivity mParentActivity;
		private boolean mImportTaskCompleted;
		private List<FrameMediaContainer> mFrameItems;
		private int mMaximumListLength;

		private ImportFramesTask(MediaPhoneActivity activity) {
			mParentActivity = activity;
			mImportTaskCompleted = false;
			mFrameItems = Collections.synchronizedList(new ArrayList<FrameMediaContainer>());
			mMaximumListLength = 0;
		}

		private void addFramesToImport(ArrayList<FrameMediaContainer> newFrames) {
			mMaximumListLength += newFrames.size();
			mFrameItems.addAll(0, newFrames); // add at the start for better UI (can be seen as they appear)
			if (!mParentActivity.isFinishing()) {
				mParentActivity.showDialog(R.id.dialog_importing_in_progress);
			}
		}

		private int getFramesSize() {
			return mFrameItems.size();
		}

		private List<FrameMediaContainer> getFrames() {
			return mFrameItems;
		}

		@Override
		protected void onPreExecute() {
			if (!mParentActivity.isFinishing()) {
				mParentActivity.showDialog(R.id.dialog_importing_in_progress);
			}
		}

		@Override
		protected Void doInBackground(FrameMediaContainer... framesToImport) {
			mMaximumListLength += framesToImport.length;
			for (int i = 0, n = framesToImport.length; i < n; i++) {
				mFrameItems.add(framesToImport[i]);
			}

			boolean framesAvailable = mFrameItems.size() > 0;
			while (framesAvailable) {
				// get resources and content resolver each time in case the activity changes
				ImportedFileParser.importNarrativeFrame(mParentActivity.getResources(), mParentActivity.getContentResolver(),
						mFrameItems.remove(0));
				framesAvailable = mFrameItems.size() > 0;
				publishProgress();
			}

			return null;
		}

		@Override
		protected void onProgressUpdate(Void... unused) {
			if (mParentActivity != null) {
				mParentActivity.onImportProgressUpdate(getCurrentProgress(), getMaximumProgress());
			}
		}

		@Override
		protected void onPostExecute(Void unused) {
			mImportTaskCompleted = true;
			notifyActivityTaskCompleted();
		}

		public int getCurrentProgress() {
			return mMaximumListLength - mFrameItems.size();
		}

		public int getMaximumProgress() {
			return mMaximumListLength;
		}

		private void setActivity(MediaPhoneActivity activity) {
			this.mParentActivity = activity;
			if (mImportTaskCompleted) {
				notifyActivityTaskCompleted();
			}
		}

		private void notifyActivityTaskCompleted() {
			if (mParentActivity != null) {
				mParentActivity.onImportTaskCompleted();
			}
		}
	}

	private class ExportNarrativesTask extends AsyncTask<BackgroundRunnable, int[], Void> {

		private MediaPhoneActivity mParentActivity;
		private boolean mTasksCompleted;
		private List<BackgroundRunnable> mTasks;

		private ExportNarrativesTask(MediaPhoneActivity activity) {
			mParentActivity = activity;
			mTasksCompleted = false;
			mTasks = Collections.synchronizedList(new ArrayList<BackgroundRunnable>());
		}

		private void addTask(BackgroundRunnable task) {
			mTasks.add(task);
		}

		private int getTasksSize() {
			return mTasks.size();
		}

		private List<BackgroundRunnable> getTasks() {
			return mTasks;
		}

		@Override
		protected Void doInBackground(BackgroundRunnable... tasks) {
			for (int i = 0, n = tasks.length; i < n; i++) {
				mTasks.add(tasks[i]);
			}

			while (mTasks.size() > 0) {
				BackgroundRunnable r = mTasks.remove(0);
				if (r != null) {
					publishProgress(new int[]{r.getTaskId(), r.getShowDialog() ? 1 : 0, 0});
					try {
						r.run();
					} catch (Throwable t) {
						Log.e(DebugUtilities.getLogTag(this), "Error running background task: " + t.getLocalizedMessage());
					}
					publishProgress(new int[]{r.getTaskId(), r.getShowDialog() ? 1 : 0, 1});
				}
			}

			return null;
		}

		@Override
		protected void onProgressUpdate(int[]... taskIds) {
			if (mParentActivity != null) {
				for (int i = 0, n = taskIds.length; i < n; i++) {
					// bit of a hack to tell us when to show a dialog and when to report progress
					if (taskIds[i][2] == 1) { // 1 == task complete
						mParentActivity.onExportNarrativesTaskProgressUpdate(taskIds[i][0]);
					} else if (taskIds[i][1] == 1 && !mParentActivity.isFinishing()) { // 1 == show dialog
						mParentActivity.showDialog(taskIds[i][0] == R.id.export_mov_task_complete ? R.id
								.dialog_mov_creator_in_progress : R.id.dialog_export_narrative_in_progress); // special dialog
						// for mov
					}
				}
			}
		}

		@Override
		protected void onPostExecute(Void unused) {
			mTasksCompleted = true;
			notifyActivityTaskCompleted();
		}

		private void setActivity(MediaPhoneActivity activity) {
			this.mParentActivity = activity;
			if (mTasksCompleted) {
				notifyActivityTaskCompleted();
			}
		}

		private void notifyActivityTaskCompleted() {
			if (mParentActivity != null) {
				mParentActivity.onAllExportNarrativesTasksCompleted();
			}
		}
	}

	private class ImmediateBackgroundRunnerTask extends AsyncTask<Runnable, Void, Void> {
		private Runnable backgroundTask;

		private ImmediateBackgroundRunnerTask(Runnable task) {
			backgroundTask = task;
		}

		@Override
		protected Void doInBackground(Runnable... tasks) {
			if (backgroundTask != null) {
				try {
					backgroundTask.run();
				} catch (Throwable t) {
					Log.e(DebugUtilities.getLogTag(this), "Error running background task: " + t.getLocalizedMessage());
				}
			}
			return null;
		}
	}

	private class QueuedBackgroundRunnerTask extends AsyncTask<BackgroundRunnable, int[], Void> {

		private MediaPhoneActivity mParentActivity;
		private boolean mTasksCompleted;
		private List<BackgroundRunnable> mTasks;

		private QueuedBackgroundRunnerTask(MediaPhoneActivity activity) {
			mParentActivity = activity;
			mTasksCompleted = false;
			mTasks = Collections.synchronizedList(new ArrayList<BackgroundRunnable>());
		}

		private void addTask(BackgroundRunnable task) {
			mTasks.add(task);
		}

		private int getTasksSize() {
			return mTasks.size();
		}

		private List<BackgroundRunnable> getTasks() {
			return mTasks;
		}

		@Override
		protected Void doInBackground(BackgroundRunnable... tasks) {
			for (int i = 0, n = tasks.length; i < n; i++) {
				mTasks.add(tasks[i]);
			}

			while (mTasks.size() > 0) {
				BackgroundRunnable r = mTasks.remove(0);
				if (r != null) {
					publishProgress(new int[]{r.getTaskId(), r.getShowDialog() ? 1 : 0, 0});
					try {
						r.run();
					} catch (Throwable t) {
						Log.e(DebugUtilities.getLogTag(this), "Error running background task: " + t.getLocalizedMessage());
					}
					publishProgress(new int[]{r.getTaskId(), r.getShowDialog() ? 1 : 0, 1});
				}
			}

			return null;
		}

		@Override
		protected void onProgressUpdate(int[]... taskIds) {
			if (mParentActivity != null) {
				for (int i = 0, n = taskIds.length; i < n; i++) {
					// bit of a hack to tell us when to show a dialog and when to report progress
					if (taskIds[i][2] == 1) { // 1 == task complete
						mParentActivity.onBackgroundTaskProgressUpdate(taskIds[i][0]);
					} else if (taskIds[i][1] == 1 && !mParentActivity.isFinishing()) { // 1 == show dialog
						mParentActivity.showDialog(R.id.dialog_background_runner_in_progress);
					}
				}
			}
		}

		@Override
		protected void onPostExecute(Void unused) {
			mTasksCompleted = true;
			notifyActivityTaskCompleted();
		}

		private void setActivity(MediaPhoneActivity activity) {
			this.mParentActivity = activity;
			if (mTasksCompleted) {
				notifyActivityTaskCompleted();
			}
		}

		private void notifyActivityTaskCompleted() {
			if (mParentActivity != null) {
				mParentActivity.onAllBackgroundTasksCompleted();
			}
		}
	}

	public interface BackgroundRunnable extends Runnable {
		/**
		 * @return The id of this task, for reference in onBackgroundTaskCompleted - this method will be queried both
		 * before and after execution; the value <b>after</b> the task is complete will be returned via
		 * onBackgroundTaskCompleted. Return 0 if no result is needed.
		 */
		int getTaskId();

		/**
		 * @return Whether the task should show a generic, un-cancellable progress dialog
		 */
		boolean getShowDialog();
	}

	protected BackgroundRunnable getMediaCleanupRunnable() {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {
				ContentResolver contentResolver = getContentResolver();

				// find narratives and templates marked as deleted
				ArrayList<String> deletedNarratives = NarrativesManager.findDeletedNarratives(contentResolver);
				ArrayList<String> deletedTemplates = NarrativesManager.findDeletedTemplates(contentResolver);
				deletedNarratives.addAll(deletedTemplates); // templates can be handled at the same time as narratives

				// find frames marked as deleted, and also frames whose parent narrative/template is marked as deleted
				ArrayList<String> deletedFrames = FramesManager.findDeletedFrames(contentResolver);
				for (String narrativeId : deletedNarratives) {
					deletedFrames.addAll(FramesManager.findFrameIdsByParentId(contentResolver, narrativeId));
				}

				// find media marked as deleted, and also media whose parent frame is marked as deleted
				// (but not inherited links)
				ArrayList<String> deletedMedia = MediaManager.findDeletedMedia(contentResolver);
				for (String frameId : deletedFrames) {
					deletedMedia.addAll(MediaManager.findMediaIdsByParentId(contentResolver, frameId, false));
				}

				// delete the actual media items on disk and the items and any links to them from the database
				int deletedMediaCount = 0;
				int deletedLinkCount = 0;
				for (String mediaId : deletedMedia) {
					final MediaItem mediaToDelete = MediaManager.findMediaByInternalId(contentResolver, mediaId);
					if (mediaToDelete != null) {
						final File fileToDelete = mediaToDelete.getFile();
						if (fileToDelete != null && fileToDelete.exists()) {
							if (fileToDelete.delete()) {
								deletedMediaCount += 1;
							}
						}
						MediaManager.deleteMediaFromBackgroundTask(contentResolver, mediaId);
					}

					// links should have already been removed, but we might as well check for stragglers
					deletedLinkCount += MediaManager.deleteMediaLinks(contentResolver, mediaId);
				}

				// remove links marked as deleted
				ArrayList<String> deletedLinks = MediaManager.findDeletedMediaLinks(contentResolver);
				for (String linkId : deletedLinks) {
					MediaManager.deleteMediaLinkFromBackgroundTask(contentResolver, linkId);
				}

				// delete the actual frame items on disk and from the database
				int deletedFrameCount = 0;
				for (String frameId : deletedFrames) {
					final FrameItem frameToDelete = FramesManager.findFrameByInternalId(contentResolver, frameId);
					if (frameToDelete != null) {
						final File directoryToDelete = frameToDelete.getStorageDirectory();
						if (directoryToDelete != null && directoryToDelete.exists()) {
							if (IOUtilities.deleteRecursive(directoryToDelete)) {
								deletedFrameCount += 1;
							}
						}
						FramesManager.deleteFrameFromBackgroundTask(contentResolver, frameId);
					}
				}

				// finally, delete the narratives/templates themselves (must do separately)
				deletedNarratives.removeAll(deletedTemplates);
				for (String narrativeId : deletedNarratives) {
					NarrativesManager.deleteNarrativeFromBackgroundTask(contentResolver, narrativeId);
				}
				for (String templateId : deletedTemplates) {
					NarrativesManager.deleteTemplateFromBackgroundTask(contentResolver, templateId);
				}

				// report progress
				Log.i(DebugUtilities.getLogTag(this), "Media cleanup: removed " + deletedNarratives.size() + "/" +
						deletedTemplates.size() + " narratives/templates, " + deletedFrames.size() + " (" + deletedFrameCount +
						") frames, and " + deletedMedia.size() + " (" + deletedMediaCount + ") media items (" + deletedLinks
						.size() + "/" + deletedLinkCount + " links)");
			}
		};
	}

	protected BackgroundRunnable getNarrativeTemplateRunnable(final String fromId, final boolean toTemplate) {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return R.id.make_load_template_task_complete;
			}

			@Override
			public boolean getShowDialog() {
				return true;
			}

			@Override
			public void run() {
				ContentResolver contentResolver = getContentResolver();
				Resources resources = getResources();
				ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver, fromId);

				final String toId = MediaPhoneProvider.getNewInternalId();
				final NarrativeItem newItem;
				if (toTemplate) {
					newItem = new NarrativeItem(toId, NarrativesManager.getNextTemplateExternalId(contentResolver));
					NarrativesManager.addTemplate(contentResolver, newItem);
				} else {
					newItem = new NarrativeItem(toId, NarrativesManager.getNextNarrativeExternalId(contentResolver));
					NarrativesManager.addNarrative(contentResolver, newItem);
				}
				final long newCreationDate = newItem.getCreationDate();

				boolean updateFirstFrame = true;
				ArrayList<String> fromFiles = new ArrayList<>();
				ArrayList<String> toFiles = new ArrayList<>();
				HashMap<String, String> linkedMedia = new HashMap<>();
				for (FrameItem frame : narrativeFrames) {
					final FrameItem newFrame = FrameItem.fromExisting(frame, MediaPhoneProvider.getNewInternalId(), toId,
							newCreationDate);
					final String newFrameId = newFrame.getInternalId();
					if (MediaPhone.DIRECTORY_THUMBS != null) {
						try {
							IOUtilities.copyFile(new File(MediaPhone.DIRECTORY_THUMBS, frame.getCacheId()), new File(MediaPhone
									.DIRECTORY_THUMBS, newFrame.getCacheId()));
						} catch (Throwable t) {
							// thumbnails will be generated on first view
						}
					}

					for (MediaItem media : MediaManager.findMediaByParentId(contentResolver, frame.getInternalId())) {
						// this is a linked item - create a new link rather than copying media
						boolean spanningMedia = media.getSpanFrames();
						if (spanningMedia && !media.getParentId().equals(frame.getInternalId())) {
							final String linkedId = linkedMedia.get(media.getInternalId()); // get the new linked id;
							if (linkedId != null) {
								MediaManager.addMediaLink(contentResolver, newFrameId, linkedId);
							}
						} else {
							final MediaItem newMedia = MediaItem.fromExisting(media, MediaPhoneProvider.getNewInternalId(),
									newFrameId, newCreationDate);
							MediaManager.addMedia(contentResolver, newMedia);
							if (spanningMedia) {
								linkedMedia.put(media.getInternalId(), newMedia.getInternalId()); // for copying links
							}
							if (updateFirstFrame) {
								// must always copy the first frame's media
								try {
									IOUtilities.copyFile(media.getFile(), newMedia.getFile());
								} catch (IOException e) {
									// TODO: error
								}
							} else {
								// queue copying other media
								fromFiles.add(media.getFile().getAbsolutePath());
								try {
									newMedia.getFile().createNewFile(); // add an empty file so that if they open the
									// item before copying it won't get deleted
									// TODO: checking length() > 0 negates this...
								} catch (IOException e) {
									// TODO: error
								}
								toFiles.add(newMedia.getFile().getAbsolutePath());
							}
						}
					}
					FramesManager.addFrame(resources, contentResolver, newFrame, updateFirstFrame);
					updateFirstFrame = false;
				}

				if (fromFiles.size() == toFiles.size()) {
					runImmediateBackgroundTask(getMediaCopierRunnable(fromFiles, toFiles));
				} else {
					// TODO: error
				}
			}
		};
	}

	// to speed up template creation - duplicate media in a separate background task
	private BackgroundRunnable getMediaCopierRunnable(final ArrayList<String> fromFiles, final ArrayList<String> toFiles) {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {
				for (int i = 0, n = fromFiles.size(); i < n; i++) {
					try {
						IOUtilities.copyFile(new File(fromFiles.get(i)), new File(toFiles.get(i)));
					} catch (IOException e) {
						// TODO: error
					}
				}
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "Finished copying " + fromFiles.size() + " media items");
			}
		};
	}

	protected BackgroundRunnable getMediaLibraryAdderRunnable(final String mediaPath, final String outputDirectoryType) {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return 0;
			}

			@Override
			public boolean getShowDialog() {
				return false;
			}

			@Override
			public void run() {
				if (IOUtilities.externalStorageIsWritable()) {
					File outputDirectory = Environment.getExternalStoragePublicDirectory(outputDirectoryType);
					try {
						outputDirectory.mkdirs();
						File mediaFile = new File(mediaPath);
						// use current time as this happens at creation; newDatedFileName guarantees no collisions
						File outputFile = IOUtilities.newDatedFileName(outputDirectory, IOUtilities.getFileExtension(mediaFile
								.getName()));
						IOUtilities.copyFile(mediaFile, outputFile);
						MediaScannerConnection.scanFile(MediaPhoneActivity.this, new String[]{outputFile.getAbsolutePath()},
								null, new MediaScannerConnection.OnScanCompletedListener() {
							@Override
							public void onScanCompleted(String path, Uri uri) {
								if (MediaPhone.DEBUG) Log.d(DebugUtilities.getLogTag(this), "MediaScanner imported " + path);
							}
						});
					} catch (IOException e) {
						if (MediaPhone.DEBUG) Log.d(DebugUtilities.getLogTag(this), "Unable to save media to " +
								outputDirectory);
					}
				}
			}
		};
	}

	public enum FadeType {
		NONE, FADEIN // CROSSFADE - disabled because of memory issues (holding previous and next bitmaps in memory)
	}

	protected void loadScreenSizedImageInBackground(ImageView imageView, String imagePath, boolean forceReloadSameImage,
	                                                FadeType fadeType) {
		// forceReloadSameImage is for, e.g., reloading image after rotation (normally this extra load would be ignored)
		if (cancelExistingTask(imagePath, imageView, forceReloadSameImage)) {
			final BitmapLoaderTask task = new BitmapLoaderTask(imageView, fadeType);
			final BitmapLoaderHolder loaderTaskHolder = new BitmapLoaderHolder(task);
			imageView.setTag(loaderTaskHolder);
			task.execute(imagePath); // TODO: deal with post-4.0 single thread AsyncTask - use executeOnExecutor
		}
	}

	protected void cancelLoadingScreenSizedImageInBackground(ImageView imageView) {
		final BitmapLoaderTask bitmapLoaderTask = getBitmapLoaderTask(imageView);
		if (bitmapLoaderTask != null) {
			bitmapLoaderTask.cancel(true);
			imageView.setTag(null); // clear the tag to signal that we've finished/cancelled loading
		}
	}

	private static boolean cancelExistingTask(String imagePath, ImageView imageView, boolean forceReload) {
		final BitmapLoaderTask bitmapLoaderTask = getBitmapLoaderTask(imageView);
		if (bitmapLoaderTask != null) {
			final String loadingImagePath = bitmapLoaderTask.mImagePath;
			if (imagePath != null && (forceReload || !imagePath.equals(loadingImagePath))) {
				bitmapLoaderTask.cancel(true); // cancel previous task for this ImageView
				imageView.setTag(null); // clear the tag to signal that we've finished/cancelled loading
			} else {
				return false; // already loading the same image (or new path is null)
			}
		}
		return true; // no existing task, or we've cancelled a task
	}

	private static BitmapLoaderTask getBitmapLoaderTask(ImageView imageView) {
		if (imageView != null) {
			final Object loaderTaskHolder = imageView.getTag();
			if (loaderTaskHolder instanceof BitmapLoaderHolder) {
				final BitmapLoaderHolder asyncDrawable = (BitmapLoaderHolder) loaderTaskHolder;
				return asyncDrawable.getBitmapWorkerTask();
			}
		}
		return null;
	}

	private class BitmapLoaderTask extends AsyncTask<String, Void, Bitmap> {
		private final WeakReference<ImageView> mImageView; // WeakReference to allow garbage collection
		private FadeType mFadeType;

		public String mImagePath;

		public BitmapLoaderTask(ImageView imageView, FadeType fadeType) {
			mImageView = new WeakReference<>(imageView);
			mFadeType = fadeType;
		}

		@Override
		protected Bitmap doInBackground(String... params) {
			mImagePath = params[0];
			Point screenSize = UIUtilities.getScreenSize(getWindowManager());
			try {
				return BitmapUtilities.loadAndCreateScaledBitmap(mImagePath, screenSize.x, screenSize.y, BitmapUtilities
						.ScalingLogic.FIT, true);
			} catch (Throwable t) {
				return null; // out of memory...
			}
		}

		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (isCancelled()) {
				if (bitmap != null) {
					bitmap.recycle();
				}
				bitmap = null;
				return;
			}

			if (mImageView != null && bitmap != null) {
				final ImageView imageView = mImageView.get();
				final BitmapLoaderTask bitmapLoaderTask = getBitmapLoaderTask(imageView);
				if (this == bitmapLoaderTask && imageView != null) {
					if (mFadeType == FadeType.NONE) {
						imageView.setImageBitmap(bitmap);
					} else {
						// Drawable currentDrawable = imageView.getDrawable();
						// final Bitmap initialState;
						// if (currentDrawable instanceof BitmapDrawable) {
						// initialState = ((BitmapDrawable) currentDrawable).getBitmap();
						// } else if (currentDrawable instanceof CrossFadeDrawable) {
						// initialState = ((CrossFadeDrawable) currentDrawable).getEnd();
						// } else {
						// initialState = Bitmap.createBitmap(1, 1,
						// ImageCacheUtilities.mBitmapFactoryOptions.inPreferredConfig);
						// }
						final CrossFadeDrawable transition = new CrossFadeDrawable(Bitmap.createBitmap(1, 1, Bitmap.Config
								.ALPHA_8), bitmap);
						transition.setCallback(imageView);
						// if (mFadeType == FadeType.CROSSFADE) {
						// transition.setCrossFadeEnabled(true);
						// } else {
						// transition.setCrossFadeEnabled(false);
						// }
						transition.startTransition(MediaPhone.ANIMATION_FADE_TRANSITION_DURATION);
						imageView.setImageDrawable(transition);
					}
				}

				imageView.setTag(null); // clear the tag to signal that we've finished loading
			}
		}
	}

	private static class BitmapLoaderHolder {
		private final WeakReference<BitmapLoaderTask> bitmapWorkerTaskReference;

		public BitmapLoaderHolder(BitmapLoaderTask bitmapWorkerTask) {
			bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
		}

		public BitmapLoaderTask getBitmapWorkerTask() {
			return bitmapWorkerTaskReference.get();
		}
	}
}
