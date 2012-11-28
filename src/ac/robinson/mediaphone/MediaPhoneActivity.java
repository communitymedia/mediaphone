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

import ac.robinson.mediaphone.activity.PreferencesActivity;
import ac.robinson.mediaphone.activity.SaveNarrativeActivity;
import ac.robinson.mediaphone.importing.ImportedFileParser;
import ac.robinson.mediaphone.provider.FrameItem;
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
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.util.ViewServer;
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
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.provider.MediaStore.Video;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;

public abstract class MediaPhoneActivity extends Activity {

	private boolean mCanSendNarratives;

	private ImportFramesTask mImportFramesTask;
	private ProgressDialog mImportFramesProgressDialog;
	private boolean mImportFramesDialogShown = false;

	private BackgroundRunnerTask mBackgroundRunnerTask;
	private boolean mBackgroundRunnerDialogShown = false;
	private boolean mMovExportDialogShown = false;

	private GestureDetector mGestureDetector = null;
	private boolean mHasSwiped;

	abstract protected void loadPreferences(SharedPreferences mediaPhoneSettings);

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
	}

	@Override
	protected void onStart() {
		loadAllPreferences();
		super.onStart();
	}

	@Override
	protected void onResume() {
		super.onResume();
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
		mGestureDetector = new GestureDetector(new SwipeDetector());
	}

	// see: http://stackoverflow.com/a/7767610
	private class SwipeDetector extends SimpleOnGestureListener {
		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
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
				// TODO: fix this properly
				if (MediaPhone.DEBUG)
					Log.e(DebugUtilities.getLogTag(this), "Null pointer on swipe");
			}
			return false;

		}
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent e) {
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

	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
			case R.id.dialog_importing_in_progress:
				ProgressDialog importDialog = new ProgressDialog(MediaPhoneActivity.this);
				importDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
				importDialog.setMessage(getString(R.string.file_import_progress));
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
				// TODO: add this, but beware of frame updating in parent activities etc.
				// // app icon in action bar clicked; go home
				// final Intent intent = new Intent(MediaPhoneActivity.this, NarrativeBrowserActivity.class);
				// intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
				// startActivity(intent);
				// return true;
				return true;

			case R.id.menu_preferences:
				final Intent preferencesIntent = new Intent(MediaPhoneActivity.this, PreferencesActivity.class);
				startActivityForResult(preferencesIntent, R.id.intent_preferences);
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
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

		// TODO: this is currently a one-time setting in some cases (i.e. previous narratives will not be updated)
		// minimum frame duration
		TypedValue resourceValue = new TypedValue();
		res.getValue(R.attr.default_minimum_frame_duration, resourceValue, true);
		float minimumFrameDuration = resourceValue.getFloat();
		try {
			String minimumFrameDurationString = mediaPhoneSettings.getString(
					getString(R.string.key_minimum_frame_duration), null);
			minimumFrameDuration = Float.valueOf(minimumFrameDurationString);
			if (minimumFrameDuration <= 0) {
				throw new NumberFormatException();
			}
		} catch (Exception e) {
			minimumFrameDuration = resourceValue.getFloat();
		}
		MediaPhone.PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION = Math.round(minimumFrameDuration * 1000);

		// TODO: this is currently a one-time setting (i.e. previous narratives will not be updated)
		// word duration
		res.getValue(R.attr.default_word_duration, resourceValue, true);
		float wordDuration = resourceValue.getFloat();
		try {
			String wordDurationString = mediaPhoneSettings.getString(getString(R.string.key_word_duration), null);
			wordDuration = Float.valueOf(wordDurationString);
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

	private void checkDirectoriesExist() {

		// nothing will work, and previously saved files will not load
		if (MediaPhone.DIRECTORY_STORAGE == null) {

			SharedPreferences mediaPhoneSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME,
					Context.MODE_PRIVATE);

			// TODO: add a way of moving content to internal locations
			if (mediaPhoneSettings.contains(MediaPhone.KEY_USE_EXTERNAL_STORAGE)) {
				if (mediaPhoneSettings.getBoolean(MediaPhone.KEY_USE_EXTERNAL_STORAGE,
						IOUtilities.isInstalledOnSdCard(this))) {

					UIUtilities.showToast(MediaPhoneActivity.this, R.string.error_opening_narrative_content_sd, true);

					finish();
					return;
				}
			}

			UIUtilities.showToast(MediaPhoneActivity.this, R.string.error_opening_narrative_content, true);

			finish();
			return;
		}

		// thumbnails and sending narratives won't work, but not really fatal
		// TODO: check these on each use, rather than just at the start (SD card could have been removed...)
		if (MediaPhone.DIRECTORY_THUMBS == null || MediaPhone.DIRECTORY_TEMP == null) {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.error_opening_cache_content);
		}

		mCanSendNarratives = true;
		if (MediaPhone.DIRECTORY_TEMP == null) {
			mCanSendNarratives = false;
		}
	}

	protected boolean canSendNarratives() {
		return mCanSendNarratives;
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
				UIUtilities.showToast(MediaPhoneActivity.this, R.string.file_import_starting);
				break;

			case MediaUtilities.MSG_RECEIVED_SMIL_FILE:
			case MediaUtilities.MSG_RECEIVED_HTML_FILE:
			case MediaUtilities.MSG_RECEIVED_MOV_FILE:
				if (MediaPhone.IMPORT_CONFIRM_IMPORTING) {
					AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
					builder.setTitle(R.string.import_file_confirmation);
					// fake that we're using the SMIL file if we're actually using .sync.jpg
					builder.setMessage(String.format(getString(R.string.import_file_hint), importedFile.getName()
							.replace(MediaUtilities.SYNC_FILE_EXTENSION, MediaUtilities.SMIL_FILE_EXTENSION)));
					builder.setIcon(android.R.drawable.ic_dialog_info);
					builder.setNegativeButton(R.string.import_not_now, null);
					builder.setPositiveButton(R.string.import_file, new DialogInterface.OnClickListener() {
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
		prefsEditor.commit(); // apply is better, but only in API > 8
	}

	// idResult is now ignored
	protected boolean switchFrames(String currentFrameId, int buttonId, int idExtra, int idResult,
			boolean showOptionsMenu, Class<?> targetActivityClass) {
		ContentResolver contentResolver = getContentResolver();
		FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, currentFrameId);
		ArrayList<String> narrativeFrameIds = FramesManager.findFrameIdsByParentId(contentResolver,
				currentFrame.getParentId());
		int currentPosition = 0;
		for (String frameId : narrativeFrameIds) {
			if (currentFrameId.equals(frameId)) {
				break;
			}
			currentPosition += 1;
		}
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
			// for API 11 and above, buttons are in the action bar, so this is unnecessary
			if (showOptionsMenu && Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
				nextPreviousFrameIntent.putExtra(getString(R.string.extra_show_options_menu), true);
			}
			startActivity(nextPreviousFrameIntent); // ignoring idResult so that the original can exit
			overridePendingTransition(inAnimation, outAnimation);
			runBackgroundTask(getFrameIconUpdaterRunnable(currentFrameId)); // in case they edited the current frame
			onBackPressed();
			return true;
		} else {
			UIUtilities.showToast(MediaPhoneActivity.this, R.string.next_previous_no_more_frames);
			return false;
		}
	}

	private void sendFiles(ArrayList<Uri> filesToSend) {

		if (filesToSend == null || filesToSend.size() <= 0) {
			// TODO: show error (but remember it's from a background task, so we can't show a Toast)
			return;
		}

		// also see: http://stackoverflow.com/questions/2344768/
		final Intent sendIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);

		// could use application/smil+xml (or html), or video/quicktime, but then there's no bluetooth option
		sendIntent.setType(getString(R.string.export_mime_type));
		sendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, filesToSend);

		// an extra activity at the start of the list that just moves the exported files
		Intent targetedShareIntent = new Intent(MediaPhoneActivity.this, SaveNarrativeActivity.class);
		targetedShareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
		targetedShareIntent.setType(getString(R.string.export_mime_type));
		targetedShareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, filesToSend);

		Intent chooserIntent = Intent.createChooser(sendIntent, getString(R.string.send_narrative_title));
		chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[] { targetedShareIntent });
		startActivity(chooserIntent); // single task mode; no return value given
	}

	protected void deleteNarrativeDialog(final String frameInternalId) {
		AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
		builder.setTitle(R.string.delete_narrative_confirmation);
		builder.setMessage(R.string.delete_narrative_hint);
		builder.setIcon(android.R.drawable.ic_dialog_alert);
		builder.setNegativeButton(android.R.string.cancel, null);
		builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				ContentResolver contentResolver = getContentResolver();
				FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, frameInternalId);
				final String narrativeId = currentFrame.getParentId();
				AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
				builder.setTitle(R.string.delete_narrative_second_confirmation);
				builder.setMessage(String.format(getString(R.string.delete_narrative_second_hint),
						FramesManager.countFramesByParentId(contentResolver, narrativeId)));
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
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
		final CharSequence[] items = { getString(R.string.send_smil), getString(R.string.send_html),
				getString(R.string.send_mov) };

		AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
		builder.setTitle(R.string.send_narrative_title);
		// builder.setMessage(R.string.send_narrative_hint); //breaks dialog
		builder.setIcon(android.R.drawable.ic_dialog_info);
		builder.setNegativeButton(android.R.string.cancel, null);
		builder.setItems(items, new DialogInterface.OnClickListener() {
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
				final String exportName = String.format("%s-%s",
						getString(R.string.app_name).replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase(Locale.ENGLISH),
						exportId);

				Resources res = getResources();
				final Map<Integer, Object> settings = new Hashtable<Integer, Object>();
				settings.put(MediaUtilities.KEY_AUDIO_RESOURCE_ID, R.raw.ic_audio_playback);

				// some output settings (TODO: make sure HTML version respects these)
				TypedValue resourceValue = new TypedValue();
				settings.put(MediaUtilities.KEY_BACKGROUND_COLOUR, res.getColor(R.color.export_background));
				settings.put(MediaUtilities.KEY_TEXT_COLOUR_NO_IMAGE, res.getColor(R.color.export_text_no_image));
				settings.put(MediaUtilities.KEY_TEXT_COLOUR_WITH_IMAGE, res.getColor(R.color.export_text_with_image));
				settings.put(MediaUtilities.KEY_TEXT_BACKGROUND_COLOUR, res.getColor(R.color.export_text_background));
				settings.put(MediaUtilities.KEY_TEXT_SPACING,
						res.getDimensionPixelSize(R.dimen.export_icon_text_padding));
				res.getValue(R.attr.export_icon_text_corner_radius, resourceValue, true);
				settings.put(MediaUtilities.KEY_TEXT_CORNER_RADIUS, resourceValue.getFloat());
				// TODO: do we want to do getDimensionPixelSize for export?
				settings.put(MediaUtilities.KEY_MAX_TEXT_FONT_SIZE,
						res.getDimensionPixelSize(R.dimen.export_maximum_text_size));
				settings.put(MediaUtilities.KEY_MAX_TEXT_CHARACTERS_PER_LINE,
						res.getInteger(R.integer.export_maximum_text_characters_per_line));
				settings.put(MediaUtilities.KEY_MAX_TEXT_HEIGHT_WITH_IMAGE,
						res.getDimensionPixelSize(R.dimen.export_maximum_text_height_with_image));

				// output files must be in a public directory for sending (/data directory will *not* work)
				settings.put(MediaUtilities.KEY_COPY_FILES_TO_OUTPUT,
						IOUtilities.mustCreateTempDirectory(MediaPhoneActivity.this));

				if (contentList != null && contentList.size() > 0) {
					switch (item) {
						case 0:
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
									sendFiles(SMILUtilities.generateNarrativeSMIL(
											getResources(),
											new File(MediaPhone.DIRECTORY_TEMP, String.format("%s%s", exportName,
													MediaUtilities.SMIL_FILE_EXTENSION)), contentList, settings));
								}
							});
							break;
						case 1:
							settings.put(MediaUtilities.KEY_OUTPUT_WIDTH, res.getInteger(R.integer.export_html_width));
							settings.put(MediaUtilities.KEY_OUTPUT_HEIGHT, res.getInteger(R.integer.export_html_height));
							settings.put(MediaUtilities.KEY_PLAYER_BAR_ADJUSTMENT,
									res.getInteger(R.integer.export_html_player_bar_adjustment));
							runBackgroundTask(new BackgroundRunnable() {
								@Override
								public int getTaskId() {
									return 0; // we want a dialog, but don't care about the result
								}

								@Override
								public void run() {
									sendFiles(HTMLUtilities.generateNarrativeHTML(
											getResources(),
											new File(MediaPhone.DIRECTORY_TEMP, String.format("play-%s%s", exportName,
													MediaUtilities.HTML_FILE_EXTENSION)), contentList, settings));
								}
							});
							break;
						case 2:
							boolean incompatibleAudio = false;
							for (FrameMediaContainer frame : contentList) {
								// all image files are compatible - we just convert to JPEG when writing the movie,
								// but we need to check for non-m4a audio
								for (String audioPath : frame.mAudioPaths) {
									if (audioPath != null && !audioPath.endsWith(MediaPhone.EXTENSION_AUDIO_FILE)) {
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
					}
				} else {
					UIUtilities.showToast(MediaPhoneActivity.this, (isTemplate ? R.string.send_template_failed
							: R.string.send_narrative_failed));
				}
				dialog.dismiss();
			}
		});
		AlertDialog alert = builder.create();
		alert.show();
	}

	private void exportMovie(final Map<Integer, Object> settings, final String exportName,
			final ArrayList<FrameMediaContainer> contentList) {
		Resources res = getResources();
		settings.put(MediaUtilities.KEY_OUTPUT_WIDTH, res.getInteger(R.integer.export_mov_width));
		settings.put(MediaUtilities.KEY_OUTPUT_HEIGHT, res.getInteger(R.integer.export_mov_height));
		settings.put(MediaUtilities.KEY_IMAGE_QUALITY, res.getInteger(R.integer.camera_jpeg_save_quality));
		runBackgroundTask(new BackgroundRunnable() {
			@Override
			public int getTaskId() {
				return Math.abs(R.id.export_mov_task_complete); // positive to show dialog
			}

			@Override
			public void run() {
				Resources res = getResources();
				// TODO: if saving locally, just generate in the media library, rather than copying?
				ArrayList<Uri> movFiles = MOVUtilities.generateNarrativeMOV(res, new File(MediaPhone.DIRECTORY_TEMP,
						String.format("play-%s%s", exportName, MediaUtilities.MOV_FILE_EXTENSION)), contentList,
						settings);
				ArrayList<Uri> filesToSend;
				if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
					filesToSend = new ArrayList<Uri>();
					final File movieDirectory = Environment
							.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
					movieDirectory.mkdirs(); // just in case
					for (Uri file : movFiles) {
						final File movFile = new File(file.getPath());
						final File newMovFile = new File(movieDirectory, movFile.getName());
						String newMovName = newMovFile.getName();
						if (newMovName.length() > MediaUtilities.MOV_FILE_EXTENSION.length()) {
							newMovName = newMovName.substring(0, newMovName.length()
									- MediaUtilities.MOV_FILE_EXTENSION.length() - 1);
						}
						try {
							// this will take a *long* time, but is necessary to be able to send
							IOUtilities.copyFile(movFile, newMovFile);
							newMovFile.setReadable(true, false);
							newMovFile.setWritable(true, false);
							newMovFile.setExecutable(true, false);

							// must use Uri and media store parameters properly, or YouTube export fails
							// see: http://stackoverflow.com/questions/5884092/
							// filesToSend.add(Uri.fromFile(newMovFile));
							ContentValues content = new ContentValues(4);
							content.put(MediaStore.Video.Media.DATA, newMovFile.getAbsolutePath());
							content.put(MediaStore.Video.VideoColumns.SIZE, newMovFile.length());
							content.put(Video.VideoColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
							content.put(Video.Media.MIME_TYPE, "video/quicktime");
							content.put(Video.VideoColumns.TITLE, newMovName);
							filesToSend.add(getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
									content));

							// no point, as most Android devices can't play our movies; and they can be played in our
							// application anyway
							// runBackgroundTask(getMediaLibraryAdderRunnable(movFile.getAbsolutePath(),
							// Environment.DIRECTORY_MOVIES));

						} catch (IOException e) {
							filesToSend = movFiles;
							break;
						}
					}
				} else {
					filesToSend = movFiles;
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
		UIUtilities.showToast(MediaPhoneActivity.this, R.string.file_import_finished);
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
		if (mBackgroundRunnerDialogShown) {
			try {
				dismissDialog(R.id.dialog_background_runner_in_progress);
			} catch (IllegalArgumentException e) {
				// we didn't show the dialog...
			}
			mBackgroundRunnerDialogShown = false;
		}

		// so that we know if they dismissed the dialog or waited and can show a hint if necessary
		if (taskId == R.id.export_mov_task_complete && !mMovExportDialogShown) {
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
			AlertDialog.Builder builder = new AlertDialog.Builder(MediaPhoneActivity.this);
			builder.setTitle(R.string.make_template_confirmation);
			builder.setMessage(R.string.make_template_hint);
			builder.setIcon(android.R.drawable.ic_dialog_info);
			builder.setPositiveButton(android.R.string.ok, null);
			AlertDialog alert = builder.create();
			alert.show();
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
			mParentActivity.showDialog(R.id.dialog_importing_in_progress);
		}

		@Override
		protected void onPreExecute() {
			mParentActivity.showDialog(R.id.dialog_importing_in_progress);
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
						if (taskIds[i][0] == R.id.export_mov_task_complete) {
							mParentActivity.showDialog(R.id.dialog_mov_creator_in_progress); // special case for mov
						} else {
							mParentActivity.showDialog(R.id.dialog_background_runner_in_progress); // id >= 0 for dialog
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

	public interface BackgroundRunnable extends Runnable {
		/**
		 * @return Zero or a positive taskId for tasks that should show a non-cancellable progress dialog; a negative
		 *         taskId when a dialog should not be shown
		 */
		public abstract int getTaskId();
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

				// check whether to rename the media library items too, or we'll end up overwriting them
				SharedPreferences mediaPhoneSettings = PreferenceManager
						.getDefaultSharedPreferences(MediaPhoneActivity.this);
				File renameFile = null;
				switch (currentMediaItem.getType()) {
					case MediaPhoneProvider.TYPE_IMAGE_BACK:
					case MediaPhoneProvider.TYPE_IMAGE_FRONT:
						if (mediaPhoneSettings.getBoolean(getString(R.string.key_pictures_to_media), getResources()
								.getBoolean(R.bool.default_pictures_to_media))) {
							renameFile = new File(
									Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
									tempMediaFile.getName());
							renameFile.mkdirs();
						}
						break;
					case MediaPhoneProvider.TYPE_AUDIO:
						if (mediaPhoneSettings.getBoolean(getString(R.string.key_audio_to_media), getResources()
								.getBoolean(R.bool.default_audio_to_media))) {
							renameFile = new File(
									Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
									tempMediaFile.getName());
							renameFile.mkdirs();
						}
						break;
				}

				currentMediaItem.setParentId(newFrameInternalId);
				MediaManager.updateMedia(contentResolver, currentMediaItem);

				MediaManager.changeMediaId(contentResolver, currentMediaItemInternalId, newMediaItemId);
				File newMediaFile = MediaItem.getFile(newFrameInternalId, newMediaItemId,
						currentMediaItem.getFileExtension());
				tempMediaFile.renameTo(newMediaFile);

				MediaManager.addMedia(contentResolver, new MediaItem(currentMediaItemInternalId, parentFrameInternalId,
						currentMediaItem.getFileExtension(), currentMediaItem.getType()));

				// rename the media library items and re-scan to update
				if (IOUtilities.externalStorageIsWritable()) {
					if (renameFile != null && renameFile.exists()) {
						File newFile = new File(renameFile.getParent(), newMediaFile.getName());
						if (renameFile.renameTo(newFile)) {
							MediaScannerConnection.scanFile(MediaPhoneActivity.this,
									new String[] { newFile.getAbsolutePath() }, null,
									new MediaScannerConnection.OnScanCompletedListener() {
										public void onScanCompleted(String path, Uri uri) {
											if (MediaPhone.DEBUG)
												Log.d(DebugUtilities.getLogTag(this), "MediaScanner imported " + path);
										}
									});
						}
					}
				}

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
					try {
						IOUtilities.copyFile(new File(MediaPhone.DIRECTORY_THUMBS, frame.getCacheId()), new File(
								MediaPhone.DIRECTORY_THUMBS, newFrame.getCacheId()));
					} catch (IOException e) {
						// TODO: error
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
							// TODO: add an empty file stub so that if they open the media item before copying completes
							// it won't get deleted...
							fromFiles.add(media.getFile().getAbsolutePath());
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
						File outputFile = new File(outputDirectory, mediaFile.getName());
						IOUtilities.copyFile(mediaFile, outputFile);
						MediaScannerConnection.scanFile(MediaPhoneActivity.this,
								new String[] { outputFile.getAbsolutePath() }, null,
								new MediaScannerConnection.OnScanCompletedListener() {
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
