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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ac.robinson.mediaphone.activity.NarrativeBrowserActivity;
import ac.robinson.mediaphone.activity.PreferencesActivity;
import ac.robinson.mediaphone.activity.SaveNarrativeActivity;
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
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.util.ViewServer;
import ac.robinson.view.CenteredImageTextButton;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.Parcelable;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.provider.MediaStore.Video;
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

public abstract class MediaPhoneActivity extends Activity {

	private ImportFramesTask mImportFramesTask;
	private ProgressDialog mImportFramesProgressDialog;
	private boolean mImportFramesDialogShown = false;

	private BackgroundRunnerTask mBackgroundRunnerTask;
	private boolean mBackgroundRunnerDialogShown = false;
	private boolean mMovExportDialogShown = false;

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
		UIUtilities.setPixelDithering(getWindow());
		checkDirectoriesExist();

		Object retained = getLastNonConfigurationInstance();
		if (retained instanceof Object[]) {
			Object[] retainedTasks = (Object[]) retained;
			if (retainedTasks.length == 2) {
				if (retainedTasks[0] instanceof ImportFramesTask) {
					// reconnect to the task; dialog is shown automatically
					mImportFramesTask = (ImportFramesTask) retainedTasks[0];
					mImportFramesTask.setActivity(this);
				}
				if (retainedTasks[1] instanceof BackgroundRunnerTask) {
					// reconnect to the task; dialog is shown automatically
					mBackgroundRunnerTask = (BackgroundRunnerTask) retainedTasks[1];
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
	public Object onRetainNonConfigurationInstance() {
		// called before screen change - have to remove the parent activity
		if (mImportFramesTask != null) {
			mImportFramesTask.setActivity(null);
		}
		if (mBackgroundRunnerTask != null) {
			mBackgroundRunnerTask.setActivity(null);
		}
		return new Object[] { mImportFramesTask, mBackgroundRunnerTask };
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
				if (e1.getX() - e2.getX() > MediaPhone.SWIPE_MIN_DISTANCE
						&& Math.abs(velocityX) > MediaPhone.SWIPE_THRESHOLD_VELOCITY) {
					if (!mHasSwiped) {
						mHasSwiped = swipeNext(); // so that we don't double-swipe and crash
					}
					return true;
				}

				// left to right
				if (e2.getX() - e1.getX() > MediaPhone.SWIPE_MIN_DISTANCE
						&& Math.abs(velocityX) > MediaPhone.SWIPE_THRESHOLD_VELOCITY) {
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
		return super.dispatchTouchEvent(e);
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
				if (mImportFramesTask != null) {
					importDialog.setMax(mImportFramesTask.getMaximumProgress());
					importDialog.setProgress(mImportFramesTask.getCurrentProgress());
				}
				mImportFramesProgressDialog = importDialog;
				mImportFramesDialogShown = true;
				return importDialog;
			case R.id.dialog_background_runner_in_progress:
				ProgressDialog runnerDialog = new ProgressDialog(MediaPhoneActivity.this);
				runnerDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
				runnerDialog.setMessage(getString(R.string.background_task_progress));
				runnerDialog.setCancelable(false);
				runnerDialog.setIndeterminate(true);
				mBackgroundRunnerDialogShown = true;
				return runnerDialog;
			case R.id.dialog_mov_creator_in_progress:
				ProgressDialog movDialog = new ProgressDialog(MediaPhoneActivity.this);
				movDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
				movDialog.setMessage(getString(R.string.mov_export_task_progress));
				movDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.mov_export_run_in_background),
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								dialog.dismiss();
								mMovExportDialogShown = false;
							}
						});
				movDialog.setCancelable(false);
				movDialog.setIndeterminate(true);
				mMovExportDialogShown = true;
				return movDialog;
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
			case R.id.dialog_background_runner_in_progress:
				mBackgroundRunnerDialogShown = true;
				break;
			case R.id.dialog_mov_creator_in_progress:
				mMovExportDialogShown = true;
				break;
			default:
				break;
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
				startActivityForResult(preferencesIntent, R.id.intent_preferences);
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	protected void setupMenuNavigationButtonsFromMedia(MenuInflater inflater, Menu menu,
			ContentResolver contentResolver, String mediaId, boolean edited) {
		String parentId = null;
		if (mediaId != null) {
			MediaItem mediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mediaId);
			if (mediaItem != null) {
				parentId = mediaItem.getParentId();
			}
		}
		setupMenuNavigationButtons(inflater, menu, parentId, edited);
	}

	protected void setupMenuNavigationButtons(MenuInflater inflater, Menu menu, String frameId, boolean edited) {
		inflater.inflate(R.menu.previous_frame, menu);
		inflater.inflate(R.menu.next_frame, menu);
		// we should have already got focus by the time this is called, so can try to disable invalid buttons
		if (frameId != null) {
			NavigationMode navigationAllowed = FrameItem.getNavigationAllowed(getContentResolver(), frameId);
			if (navigationAllowed == NavigationMode.PREVIOUS || navigationAllowed == NavigationMode.NONE) {
				menu.findItem(R.id.menu_next_frame).setEnabled(false);
			}
			if (navigationAllowed == NavigationMode.NEXT || navigationAllowed == NavigationMode.NONE) {
				menu.findItem(R.id.menu_previous_frame).setEnabled(false);
			}
		}
		inflater.inflate(R.menu.add_frame, menu);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			if (edited) {
				inflater.inflate(R.menu.finished_editing, menu);
			} else {
				inflater.inflate(R.menu.back_without_editing, menu);
			}
		}
	}

	protected void setBackButtonIcons(Activity activity, int button1, int button2, boolean isEdited) {
		if (button1 != 0) {
			((CenteredImageTextButton) findViewById(button1)).setCompoundDrawablesWithIntrinsicBounds(0,
					(isEdited ? R.drawable.ic_finished_editing : android.R.drawable.ic_menu_revert), 0, 0);
		}
		if (button2 != 0) {
			((CenteredImageTextButton) findViewById(button2)).setCompoundDrawablesWithIntrinsicBounds(0,
					(isEdited ? R.drawable.ic_finished_editing : android.R.drawable.ic_menu_revert), 0, 0);
		}
		UIUtilities.refreshActionBar(activity);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case R.id.intent_preferences:
				loadAllPreferences();
				break;
			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
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
			confirmImporting = mediaPhoneSettings.getBoolean(getString(R.string.key_confirm_importing),
					confirmImporting);
		} catch (Exception e) {
			confirmImporting = res.getBoolean(R.bool.default_confirm_importing);
		}
		MediaPhone.IMPORT_CONFIRM_IMPORTING = confirmImporting;

		// delete after import
		boolean deleteAfterImport = res.getBoolean(R.bool.default_delete_after_importing);
		try {
			deleteAfterImport = mediaPhoneSettings.getBoolean(getString(R.string.key_delete_after_importing),
					deleteAfterImport);
		} catch (Exception e) {
			deleteAfterImport = res.getBoolean(R.bool.default_delete_after_importing);
		}
		MediaPhone.IMPORT_DELETE_AFTER_IMPORTING = deleteAfterImport;

		// minimum frame duration
		TypedValue resourceValue = new TypedValue();
		res.getValue(R.attr.default_minimum_frame_duration, resourceValue, true);
		float minimumFrameDuration;
		try {
			minimumFrameDuration = mediaPhoneSettings.getFloat(getString(R.string.key_minimum_frame_duration),
					resourceValue.getFloat());
			if (minimumFrameDuration <= 0) {
				throw new NumberFormatException();
			}
		} catch (Exception e) {
			minimumFrameDuration = resourceValue.getFloat();
		}
		MediaPhone.PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION = Math.round(minimumFrameDuration * 1000);

		// word duration
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
			String requestedOrientationString = mediaPhoneSettings.getString(
					getString(R.string.key_screen_orientation), null);
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
		if (watchForFiles) {
			// file changes are handled in startWatchingBluetooth();
			((MediaPhoneApplication) getApplication()).startWatchingBluetooth(false); // don't watch if bt not enabled
		} else {
			((MediaPhoneApplication) getApplication()).stopWatchingBluetooth();
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

			SharedPreferences mediaPhoneSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME,
					Context.MODE_PRIVATE);
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
		configureBluetoothObserver(PreferenceManager.getDefaultSharedPreferences(MediaPhoneActivity.this),
				getResources());
	}

	protected void onBluetoothServiceRegistered() {
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
					AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
					builder.setTitle(R.string.import_file_confirmation);
					// fake that we're using the SMIL file if we're actually using .sync.jpg
					builder.setMessage(getString(
							R.string.import_file_hint,
							importedFile.getName().replace(MediaUtilities.SYNC_FILE_EXTENSION,
									MediaUtilities.SMIL_FILE_EXTENSION)));
					builder.setIcon(android.R.drawable.ic_dialog_info);
					builder.setNegativeButton(R.string.import_not_now, null);
					builder.setPositiveButton(R.string.import_file, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int whichButton) {
							importFiles(messageType, importedFile);
						}
					});
					AlertDialog alert = builder.create();
					alert.show();
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
				narrativeFrames = ImportedFileParser.importSMILNarrative(getContentResolver(), receivedFile,
						sequenceIncrement);
				break;
			case MediaUtilities.MSG_RECEIVED_HTML_FILE:
				UIUtilities.showToast(MediaPhoneActivity.this, R.string.html_feature_coming_soon);
				narrativeFrames = ImportedFileParser.importHTMLNarrative(getContentResolver(), receivedFile,
						sequenceIncrement);
				break;

			case MediaUtilities.MSG_RECEIVED_MOV_FILE:
				narrativeFrames = ImportedFileParser.importMOVNarrative(receivedFile);
				break;
		}

		// import - start a new task or add to existing
		// TODO: set up wake lock (and in onCreate if task is running):
		// http://stackoverflow.com/questions/2241049
		if (narrativeFrames != null && narrativeFrames.size() > 0) {
			if (mImportFramesTask != null) {
				mImportFramesTask.addFramesToImport(narrativeFrames);
			} else {
				mImportFramesTask = new ImportFramesTask(MediaPhoneActivity.this);
				mImportFramesTask.addFramesToImport(narrativeFrames);
				mImportFramesTask.execute();
			}
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
	 * Switch from one frame to another. Will call onBackPressed() on the calling activity
	 * 
	 * @param currentFrameId
	 * @param buttonId
	 * @param idExtra
	 * @param showOptionsMenu
	 * @param targetActivityClass
	 * @return
	 */
	protected boolean switchFrames(String currentFrameId, int buttonId, int idExtra, boolean showOptionsMenu,
			Class<?> targetActivityClass) {
		if (currentFrameId == null) {
			return false;
		}
		ContentResolver contentResolver = getContentResolver();
		FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, currentFrameId);
		ArrayList<String> narrativeFrameIds = FramesManager.findFrameIdsByParentId(contentResolver,
				currentFrame.getParentId());
		int currentPosition = narrativeFrameIds.indexOf(currentFrameId);
		int newFramePosition = -1;
		int inAnimation = R.anim.slide_in_from_right;
		int outAnimation = R.anim.slide_out_to_left;
		switch (buttonId) {
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
			final Intent nextPreviousFrameIntent = new Intent(MediaPhoneActivity.this, targetActivityClass);
			nextPreviousFrameIntent.putExtra(getString(idExtra), narrativeFrameIds.get(newFramePosition));

			// this allows us to prevent showing first activity launch hints repeatedly
			nextPreviousFrameIntent.putExtra(getString(R.string.extra_switched_frames), true);

			// for API 11 and above, buttons are in the action bar, so this is unnecessary
			if (showOptionsMenu && Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
				nextPreviousFrameIntent.putExtra(getString(R.string.extra_show_options_menu), true);
			}

			startActivity(nextPreviousFrameIntent); // no result so that the original can exit (TODO: will it?)
			closeOptionsMenu(); // so onBackPressed doesn't just do this
			onBackPressed();
			overridePendingTransition(inAnimation, outAnimation);
			return true;
		} else {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.next_previous_no_more_frames);
			return false;
		}
	}

	private void sendFiles(final ArrayList<Uri> filesToSend) {
		// send files in a separate task without a dialog so we don't leave the previous progress dialog behind on
		// screen rotation - this is a bit of a hack, but it works
		runBackgroundTask(new BackgroundRunnable() {
			int mTaskResult = -1; // don't show a dialog

			@Override
			public int getTaskId() {
				return mTaskResult;
			}

			@Override
			public void run() {
				if (filesToSend == null || filesToSend.size() <= 0) {
					mTaskResult = -Math.abs(R.id.export_creation_failed); // don't show a dialog
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

				final Intent chooserIntent = Intent.createChooser(sendIntent,
						getString(R.string.export_narrative_title));

				// an extra activity at the start of the list that moves exported files to SD, but only if SD available
				if (IOUtilities.externalStorageIsWritable()) {
					final Intent targetedShareIntent = new Intent(MediaPhoneActivity.this, SaveNarrativeActivity.class);
					targetedShareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
					targetedShareIntent.setType(getString(R.string.export_mime_type));
					targetedShareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, filesToSend);
					chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[] { targetedShareIntent });
				}

				startActivity(chooserIntent); // single task mode; no return value given
			}
		});
	}

	protected void deleteNarrativeDialog(final String frameInternalId) {
		AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
		builder.setTitle(R.string.delete_narrative_confirmation);
		builder.setMessage(R.string.delete_narrative_hint);
		builder.setIcon(android.R.drawable.ic_dialog_alert);
		builder.setNegativeButton(android.R.string.cancel, null);
		builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int whichButton) {
				ContentResolver contentResolver = getContentResolver();
				FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, frameInternalId);
				final String narrativeId = currentFrame.getParentId();
				int numFramesDeleted = FramesManager.countFramesByParentId(contentResolver, narrativeId);
				AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
				builder.setTitle(R.string.delete_narrative_second_confirmation);
				builder.setMessage(getResources().getQuantityString(R.plurals.delete_narrative_second_hint,
						numFramesDeleted, numFramesDeleted));
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						NarrativeItem narrativeToDelete = NarrativesManager.findNarrativeByInternalId(contentResolver,
								narrativeId);
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

		final CharSequence[] items = { getString(R.string.export_mov), getString(R.string.export_html),
				getString(R.string.export_smil, getString(R.string.app_name)) };

		AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
		builder.setTitle(R.string.export_narrative_title);
		// builder.setMessage(R.string.send_narrative_hint); //breaks dialog
		builder.setIcon(android.R.drawable.ic_dialog_info);
		builder.setNegativeButton(android.R.string.cancel, null);
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
				final String exportName = String.format(Locale.ENGLISH, "%s-%s", getString(R.string.app_name)
						.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase(Locale.ENGLISH), exportId);

				Resources res = getResources();
				final Map<Integer, Object> settings = new Hashtable<Integer, Object>();
				settings.put(MediaUtilities.KEY_AUDIO_RESOURCE_ID, R.raw.ic_audio_playback);

				// some output settings (TODO: make sure HTML version respects these)
				settings.put(MediaUtilities.KEY_BACKGROUND_COLOUR, res.getColor(R.color.export_background));
				settings.put(MediaUtilities.KEY_TEXT_COLOUR_NO_IMAGE, res.getColor(R.color.export_text_no_image));
				settings.put(MediaUtilities.KEY_TEXT_COLOUR_WITH_IMAGE, res.getColor(R.color.export_text_with_image));
				settings.put(MediaUtilities.KEY_TEXT_BACKGROUND_COLOUR, res.getColor(R.color.export_text_background));
				// TODO: do we want to do getDimensionPixelSize for export?
				settings.put(MediaUtilities.KEY_TEXT_SPACING,
						res.getDimensionPixelSize(R.dimen.export_icon_text_padding));
				settings.put(MediaUtilities.KEY_TEXT_CORNER_RADIUS,
						res.getDimensionPixelSize(R.dimen.export_icon_text_corner_radius));
				settings.put(MediaUtilities.KEY_TEXT_BACKGROUND_SPAN_WIDTH,
						Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB);
				settings.put(MediaUtilities.KEY_MAX_TEXT_FONT_SIZE,
						res.getDimensionPixelSize(R.dimen.export_maximum_text_size));
				settings.put(MediaUtilities.KEY_MAX_TEXT_CHARACTERS_PER_LINE,
						res.getInteger(R.integer.export_maximum_text_characters_per_line));
				settings.put(MediaUtilities.KEY_MAX_TEXT_HEIGHT_WITH_IMAGE,
						res.getDimensionPixelSize(R.dimen.export_maximum_text_height_with_image));

				if (contentList != null && contentList.size() > 0) {
					switch (item) {
						case 0:
							settings.put(MediaUtilities.KEY_OUTPUT_WIDTH, res.getInteger(R.integer.export_mov_width));
							settings.put(MediaUtilities.KEY_OUTPUT_HEIGHT, res.getInteger(R.integer.export_mov_height));
							settings.put(MediaUtilities.KEY_IMAGE_QUALITY,
									res.getInteger(R.integer.camera_jpeg_save_quality));

							// all image files are compatible - we just convert to JPEG when writing the movie,
							// but we need to check for incompatible audio that we can't convert to PCM
							boolean incompatibleAudio = false;
							for (FrameMediaContainer frame : contentList) {
								for (String audioPath : frame.mAudioPaths) {
									if (!AndroidUtilities.arrayContains(MediaUtilities.MOV_AUDIO_FILE_EXTENSIONS,
											IOUtilities.getFileExtension(audioPath))) {
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
								builder.setTitle(android.R.string.dialog_alert_title);
								builder.setMessage(R.string.mov_export_mov_incompatible);
								builder.setIcon(android.R.drawable.ic_dialog_alert);
								builder.setNegativeButton(android.R.string.cancel, null);
								builder.setPositiveButton(R.string.button_continue,
										new DialogInterface.OnClickListener() {
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
							runBackgroundTask(new BackgroundRunnable() {
								@Override
								public int getTaskId() {
									return 0; // we want a dialog, but don't care about the result
								}

								@Override
								public void run() {
									sendFiles(HTMLUtilities.generateNarrativeHTML(getResources(),
											new File(MediaPhone.DIRECTORY_TEMP, exportName
													+ MediaUtilities.HTML_FILE_EXTENSION), contentList, settings));
								}
							});
							break;

						case 2:
							settings.put(MediaUtilities.KEY_OUTPUT_WIDTH, res.getInteger(R.integer.export_smil_width));
							settings.put(MediaUtilities.KEY_OUTPUT_HEIGHT, res.getInteger(R.integer.export_smil_height));
							settings.put(MediaUtilities.KEY_PLAYER_BAR_ADJUSTMENT,
									res.getInteger(R.integer.export_smil_player_bar_adjustment));
							runBackgroundTask(new BackgroundRunnable() {
								@Override
								public int getTaskId() {
									return 0; // we want a dialog, but don't care about the result
								}

								@Override
								public void run() {
									sendFiles(SMILUtilities.generateNarrativeSMIL(getResources(),
											new File(MediaPhone.DIRECTORY_TEMP, exportName
													+ MediaUtilities.SMIL_FILE_EXTENSION), contentList, settings));
								}
							});
							break;
					}
				} else {
					UIUtilities.showToast(MediaPhoneActivity.this, (isTemplate ? R.string.export_template_failed
							: R.string.export_narrative_failed));
				}
				dialog.dismiss();
			}
		});
		AlertDialog alert = builder.create();
		alert.show();
	}

	private void exportMovie(final Map<Integer, Object> settings, final String exportName,
			final ArrayList<FrameMediaContainer> contentList) {
		runBackgroundTask(new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return Math.abs(R.id.export_mov_task_complete); // positive to show dialog
			}

			@Override
			public void run() {
				ArrayList<Uri> movFiles = MOVUtilities.generateNarrativeMOV(getResources(), new File(
						MediaPhone.DIRECTORY_TEMP, exportName + MediaUtilities.MOV_FILE_EXTENSION), contentList,
						settings);

				// must use media store parameters properly, or YouTube export fails
				// see: http://stackoverflow.com/questions/5884092/
				ArrayList<Uri> filesToSend = new ArrayList<Uri>();
				for (Uri fileUri : movFiles) {
					File outputFile = new File(fileUri.getPath());
					ContentValues content = new ContentValues(5);
					content.put(MediaStore.Video.Media.DATA, outputFile.getAbsolutePath());
					content.put(MediaStore.Video.VideoColumns.SIZE, outputFile.length());
					content.put(Video.VideoColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
					content.put(Video.Media.MIME_TYPE, "video/quicktime");
					content.put(Video.VideoColumns.TITLE, IOUtilities.removeExtension(outputFile.getName()));
					filesToSend.add(getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, content));

				}

				sendFiles(filesToSend);
			}
		});
	}

	protected void onImportProgressUpdate(int currentProgress, int newMaximum) {
		if (mImportFramesDialogShown && mImportFramesProgressDialog != null) {
			mImportFramesProgressDialog.setProgress(currentProgress);
			mImportFramesProgressDialog.setMax(newMaximum);
		}
	}

	protected void onImportTaskCompleted() {
		mImportFramesTask = null;
		// can only interact with dialogs this instance actually showed
		if (mImportFramesDialogShown) {
			if (mImportFramesProgressDialog != null) {
				mImportFramesProgressDialog.setMax(mImportFramesProgressDialog.getMax());
			}
			try {
				dismissDialog(R.id.dialog_importing_in_progress);
			} catch (IllegalArgumentException e) {
				// we didn't show the dialog...
			}
			mImportFramesDialogShown = false;
		}
		mImportFramesProgressDialog = null;
		UIUtilities.showToast(MediaPhoneActivity.this, R.string.import_finished);
	}

	protected void runImmediateBackgroundTask(Runnable r) {
		ImmediateBackgroundRunnerTask backgroundTask = new ImmediateBackgroundRunnerTask(r);
		backgroundTask.execute();
	}

	protected void runBackgroundTask(BackgroundRunnable r) {
		// import - start a new task or add to existing
		// TODO: set up wake lock (and in onCreate if task is running):
		// http://stackoverflow.com/questions/2241049
		if (mBackgroundRunnerTask != null) {
			mBackgroundRunnerTask.addTask(r);
		} else {
			mBackgroundRunnerTask = new BackgroundRunnerTask(this);
			mBackgroundRunnerTask.addTask(r);
			mBackgroundRunnerTask.execute();
		}
	}

	// a single task has completed
	protected void onBackgroundTaskProgressUpdate(int taskId) {
		taskId = Math.abs(taskId);

		if (mBackgroundRunnerDialogShown) {
			try {
				dismissDialog(R.id.dialog_background_runner_in_progress);
			} catch (IllegalArgumentException e) {
				// we didn't show the dialog...
			}
			mBackgroundRunnerDialogShown = false;
		}

		// so that we know if they dismissed the dialog or waited and can show a hint if necessary
		if (taskId == Math.abs(R.id.export_mov_task_complete) && !mMovExportDialogShown) {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.mov_export_task_complete);
		}

		if (mMovExportDialogShown) {
			try {
				dismissDialog(R.id.dialog_mov_creator_in_progress);
			} catch (IllegalArgumentException e) {
				// we didn't show the dialog...
			}
			mMovExportDialogShown = false;
		}

		if (taskId == Math.abs(R.id.make_load_template_task_complete)) {
			// alert when template creation is complete - here as template creation can happen in several places
			AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
			builder.setTitle(R.string.make_template_confirmation);
			builder.setMessage(R.string.make_template_hint);
			builder.setIcon(android.R.drawable.ic_dialog_info);
			builder.setPositiveButton(android.R.string.ok, null);
			AlertDialog alert = builder.create();
			alert.show();

		} else if (taskId == Math.abs(R.id.export_creation_failed)) {
			// alert if export fails - here as export can happen in several places
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.export_creation_failed, true);
		}
	}

	// all tasks complete
	protected void onBackgroundTasksCompleted() {
		mBackgroundRunnerTask = null;
		// can only interact with dialogs this instance actually showed
		if (mBackgroundRunnerDialogShown) {
			try {
				dismissDialog(R.id.dialog_background_runner_in_progress);
			} catch (IllegalArgumentException e) {
				// we didn't show the dialog...
			}
			mBackgroundRunnerDialogShown = false;
		}
		if (mMovExportDialogShown) {
			try {
				dismissDialog(R.id.dialog_mov_creator_in_progress);
			} catch (IllegalArgumentException e) {
				// we didn't show the dialog...
			}
			mMovExportDialogShown = false;
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
			mFrameItems.addAll(newFrames);
			if (!mParentActivity.isFinishing()) {
				mParentActivity.showDialog(R.id.dialog_importing_in_progress);
			}
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
				ImportedFileParser.importNarrativeFrame(mParentActivity.getResources(),
						mParentActivity.getContentResolver(), mFrameItems.remove(0));
				framesAvailable = mFrameItems.size() > 0;
				publishProgress();
			}

			if (mFrameItems.size() <= 0) {
				mMaximumListLength = 0;
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

	private class BackgroundRunnerTask extends AsyncTask<BackgroundRunnable, int[], Void> {

		private MediaPhoneActivity mParentActivity;
		private boolean mTasksCompleted;
		private List<BackgroundRunnable> mTasks;

		private BackgroundRunnerTask(MediaPhoneActivity activity) {
			mParentActivity = activity;
			mTasksCompleted = false;
			mTasks = Collections.synchronizedList(new ArrayList<BackgroundRunnable>());
		}

		private void addTask(BackgroundRunnable task) {
			mTasks.add(task);
		}

		@Override
		protected Void doInBackground(BackgroundRunnable... tasks) {
			for (int i = 0, n = tasks.length; i < n; i++) {
				mTasks.add(tasks[i]);
			}

			while (mTasks.size() > 0) {
				BackgroundRunnable r = mTasks.remove(0);
				publishProgress(new int[] { r.getTaskId(), 0 });
				r.run();
				publishProgress(new int[] { r.getTaskId(), 1 });
			}

			return null;
		}

		@Override
		protected void onProgressUpdate(int[]... taskIds) {
			if (mParentActivity != null) {
				for (int i = 0, n = taskIds.length; i < n; i++) {
					// bit of a hack to tell us when to update the dialog and when to report progress
					if (taskIds[i][1] == 1) {
						mParentActivity.onBackgroundTaskProgressUpdate(taskIds[i][0]); // task complete
					} else if (taskIds[i][0] >= 0) {
						if (!mParentActivity.isFinishing()) {
							if (taskIds[i][0] == R.id.export_mov_task_complete) {
								mParentActivity.showDialog(R.id.dialog_mov_creator_in_progress); // special case for mov
							} else {
								mParentActivity.showDialog(R.id.dialog_background_runner_in_progress); // id >= 0 -> dlg
							}
						}
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
				mParentActivity.onBackgroundTasksCompleted();
			}
		}
	}

	private class ImmediateBackgroundRunnerTask extends AsyncTask<Runnable, Void, Void> {
		private Runnable backgroundTask = null;

		private ImmediateBackgroundRunnerTask(Runnable task) {
			backgroundTask = task;
		}

		@Override
		protected Void doInBackground(Runnable... tasks) {
			if (backgroundTask != null) {
				try {
					backgroundTask.run();
				} catch (Throwable t) {
					Log.d(DebugUtilities.getLogTag(this), "Error running background task: " + t.getLocalizedMessage());
				}
			}
			return null;
		}
	}

	public interface BackgroundRunnable extends Runnable {
		/**
		 * @return Zero or a positive taskId for tasks that should show a non-cancellable progress dialog; a negative
		 *         taskId when a dialog should not be shown
		 */
		public abstract int getTaskId();
	}

	protected Runnable getMediaCleanupRunnable() {
		return new Runnable() {
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
				ArrayList<String> deletedMedia = MediaManager.findDeletedMedia(contentResolver);
				for (String frameId : deletedFrames) {
					deletedMedia.addAll(MediaManager.findMediaIdsByParentId(contentResolver, frameId));
				}

				// delete the actual media items on disk and from the database
				int deletedMediaCount = 0;
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
				Log.d(DebugUtilities.getLogTag(this), "Media cleanup: removed " + deletedNarratives.size() + "/"
						+ deletedTemplates.size() + " narratives/templates, " + deletedFrames.size() + " ("
						+ deletedFrameCount + ") frames, and " + deletedMedia.size() + " (" + deletedMediaCount
						+ ") media items");
			}
		};
	}

	protected BackgroundRunnable getFrameSplitterRunnable(final String currentMediaItemInternalId) {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return Math.abs(R.id.split_frame_task_complete); // positive to show dialog
			}

			@Override
			public void run() {
				// get the current media item and its parent
				ContentResolver contentResolver = getContentResolver();
				Resources resources = getResources();
				MediaItem currentMediaItem = MediaManager.findMediaByInternalId(contentResolver,
						currentMediaItemInternalId);
				FrameItem parentFrame = FramesManager.findFrameByInternalId(contentResolver,
						currentMediaItem.getParentId());
				String parentFrameInternalId = parentFrame.getInternalId();
				ArrayList<MediaItem> frameComponents = MediaManager.findMediaByParentId(contentResolver,
						parentFrameInternalId);

				// get all the frames in this narrative
				ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver,
						parentFrame.getParentId());

				// if the new frame is the first frame, we need to update the second frame's icon, as it's possible
				// that this task was launched by adding a frame at the start of the narrative
				if (narrativeFrames.size() >= 2) {
					if (narrativeFrames.get(0).getInternalId().equals(currentMediaItemInternalId)) {
						FramesManager.reloadFrameIcon(resources, contentResolver, narrativeFrames.get(1), true);
					}
				}

				// insert new frame - increment necessary frames after the new frame's position
				boolean frameFound = false;
				int newFrameSequenceId = 0;
				int previousNarrativeSequenceId = 0;
				for (FrameItem frame : narrativeFrames) {
					if (!frameFound && (parentFrameInternalId.equals(frame.getInternalId()))) {
						frameFound = true;
						newFrameSequenceId = frame.getNarrativeSequenceId();
					}
					if (frameFound) {
						if (newFrameSequenceId <= frame.getNarrativeSequenceId()
								|| frame.getNarrativeSequenceId() <= previousNarrativeSequenceId) {

							frame.setNarrativeSequenceId(frame.getNarrativeSequenceId() + 1);
							FramesManager.updateFrame(contentResolver, frame);
							previousNarrativeSequenceId = frame.getNarrativeSequenceId();
						} else {
							break;
						}
					}
				}

				// create a new frame and move all the old media to it
				FrameItem newFrame = new FrameItem(parentFrame.getParentId(), newFrameSequenceId);
				String newFrameInternalId = newFrame.getInternalId();
				for (MediaItem currentItem : frameComponents) {
					// need to know where the existing file is stored before editing the database record
					if (!currentMediaItemInternalId.equals(currentItem.getInternalId())) {
						File tempMediaFile = currentItem.getFile();
						currentItem.setParentId(newFrameInternalId);
						tempMediaFile.renameTo(currentItem.getFile());
						MediaManager.updateMedia(contentResolver, currentItem);
					}
				}

				// the current media item is a special case - need to silently create a new copy
				String newMediaItemId = MediaPhoneProvider.getNewInternalId();
				File tempMediaFile = currentMediaItem.getFile();

				currentMediaItem.setParentId(newFrameInternalId);
				MediaManager.updateMedia(contentResolver, currentMediaItem);

				MediaManager.changeMediaId(contentResolver, currentMediaItemInternalId, newMediaItemId);
				File newMediaFile = MediaItem.getFile(newFrameInternalId, newMediaItemId,
						currentMediaItem.getFileExtension());
				tempMediaFile.renameTo(newMediaFile);

				MediaManager.addMedia(contentResolver, new MediaItem(currentMediaItemInternalId, parentFrameInternalId,
						currentMediaItem.getFileExtension(), currentMediaItem.getType()));

				// add the frame and regenerate the icon
				FramesManager.addFrame(resources, contentResolver, newFrame, true);
			}
		};
	}

	protected BackgroundRunnable getNarrativeTemplateRunnable(final String fromId, final String toId,
			final boolean toTemplate) {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return Math.abs(R.id.make_load_template_task_complete); // positive to show dialog
			}

			@Override
			public void run() {
				ContentResolver contentResolver = getContentResolver();
				Resources resources = getResources();
				ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver, fromId);

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
				ArrayList<String> fromFiles = new ArrayList<String>();
				ArrayList<String> toFiles = new ArrayList<String>();
				for (FrameItem frame : narrativeFrames) {
					final FrameItem newFrame = FrameItem.fromExisting(frame, MediaPhoneProvider.getNewInternalId(),
							toId, newCreationDate);
					final String newFrameId = newFrame.getInternalId();
					if (MediaPhone.DIRECTORY_THUMBS != null) {
						try {
							IOUtilities.copyFile(new File(MediaPhone.DIRECTORY_THUMBS, frame.getCacheId()), new File(
									MediaPhone.DIRECTORY_THUMBS, newFrame.getCacheId()));
						} catch (Throwable t) {
							// thumbnails will be generated on first view
						}
					}

					for (MediaItem media : MediaManager.findMediaByParentId(contentResolver, frame.getInternalId())) {
						final MediaItem newMedia = MediaItem.fromExisting(media, MediaPhoneProvider.getNewInternalId(),
								newFrameId, newCreationDate);
						MediaManager.addMedia(contentResolver, newMedia);
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
								newMedia.getFile().createNewFile(); // add an empty file so that if they open the item
																	// before copying completes it won't get deleted
							} catch (IOException e) {
								// TODO: error
							}
							toFiles.add(newMedia.getFile().getAbsolutePath());
						}
					}
					FramesManager.addFrame(resources, contentResolver, newFrame, updateFirstFrame);
					updateFirstFrame = false;
				}

				if (fromFiles.size() == toFiles.size()) {
					runBackgroundTask(getMediaCopierRunnable(fromFiles, toFiles));
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
				return -1; // don't show a dialog
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

	protected BackgroundRunnable getFrameIconUpdaterRunnable(final String frameInternalId) {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return -1; // don't show a dialog
			}

			@Override
			public void run() {
				// update the icon
				ContentResolver contentResolver = getContentResolver();
				FrameItem thisFrame = FramesManager.findFrameByInternalId(contentResolver, frameInternalId);
				if (thisFrame != null) { // if run from switchFrames then the existing frame could have been deleted
					FramesManager.reloadFrameIcon(getResources(), contentResolver, thisFrame, true);
				}
			}
		};
	}

	protected BackgroundRunnable getMediaLibraryAdderRunnable(final String mediaPath, final String outputDirectoryType) {
		return new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return -1; // don't show a dialog
			}

			@Override
			public void run() {
				if (IOUtilities.externalStorageIsWritable()) {
					File outputDirectory = Environment.getExternalStoragePublicDirectory(outputDirectoryType);
					try {
						outputDirectory.mkdirs();
						File mediaFile = new File(mediaPath);
						// use current time as this happens at creation; newDatedFileName guarantees no collisions
						File outputFile = IOUtilities.newDatedFileName(outputDirectory,
								IOUtilities.getFileExtension(mediaFile.getName()));
						IOUtilities.copyFile(mediaFile, outputFile);
						MediaScannerConnection.scanFile(MediaPhoneActivity.this,
								new String[] { outputFile.getAbsolutePath() }, null,
								new MediaScannerConnection.OnScanCompletedListener() {
									@Override
									public void onScanCompleted(String path, Uri uri) {
										if (MediaPhone.DEBUG)
											Log.d(DebugUtilities.getLogTag(this), "MediaScanner imported " + path);
									}
								});
					} catch (IOException e) {
						if (MediaPhone.DEBUG)
							Log.d(DebugUtilities.getLogTag(this), "Unable to save media to " + outputDirectory);
					}
				}
			}
		};
	}
}
