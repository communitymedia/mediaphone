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

package ac.robinson.mediaphone.activity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * This Activity is a bit of a hack to allow saving from an extra item in an Intent chooser - its only purpose is to
 * move files to a specific output folder (and will fail if they're not already on the same mount point as the SD card)
 * 
 * @author Simon Robinson
 * 
 */
public class SaveNarrativeActivity extends MediaPhoneActivity {

	private ArrayList<Uri> mFileUris;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Intent intent = getIntent();
		if (intent == null) {
			failureMessage();
			return;
		}

		String action = intent.getAction();
		String type = intent.getType();
		mFileUris = null;
		if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
			if (type.startsWith(getString(R.string.export_mime_type))) {
				ArrayList<Uri> fileUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
				if (fileUris != null && fileUris.size() > 0) {
					mFileUris = fileUris;
					displayFileNameDialog(0);
				}
			}
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// no normal preferences apply to this activity
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		// no interface preferences apply to this activity
	}

	@Override
	protected void onBackgroundTaskCompleted(int taskId) {
		switch (taskId) {
			case R.id.export_save_sd_succeeded:
				successMessage();
				break;
			case R.id.export_save_sd_failed:
				failureMessage();
				break;
			case R.id.export_save_sd_file_exists:
				displayFileNameDialog(R.string.export_narrative_name_exists);
				break;
		}
	}

	private void displayFileNameDialog(int errorMessage) {
		AlertDialog.Builder nameDialog = new AlertDialog.Builder(this);
		nameDialog.setTitle(R.string.export_narrative_name);
		nameDialog.setIcon(android.R.drawable.ic_dialog_info);
		if (errorMessage != 0) {
			nameDialog.setMessage(errorMessage);
		}

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setGravity(Gravity.CENTER_HORIZONTAL);
		final EditText input = new EditText(this);
		layout.setPadding(10, 0, 10, 0);
		layout.addView(input);
		nameDialog.setView(layout);

		nameDialog.setPositiveButton(R.string.button_save, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int button) {
				File outputDirectory = new File(Environment
						.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
						getString(R.string.export_local_directory));

				String chosenName = input.getText().toString();
				if (!TextUtils.isEmpty(chosenName)) {
					chosenName = chosenName.replaceAll("[^a-zA-Z0-9 ]+", ""); // only valid filenames
					saveFilesToSD(outputDirectory, chosenName); // not yet detected duplicate html/mov names; may return
				} else {
					dialog.dismiss();
					displayFileNameDialog(R.string.export_narrative_name_blank); // error - enter a name
				}
			}
		});

		nameDialog.setNegativeButton(R.string.button_cancel, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int button) {
				dialog.dismiss();
				finish();
			}
		});

		AlertDialog createdDialog = nameDialog.create();
		createdDialog.setCanceledOnTouchOutside(false); // so we don't leave the activity in the background by mistake
		createdDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		createdDialog.show();
	}

	private void saveFilesToSD(File requestedDirectory, String requestedName) {
		if (mFileUris == null) {
			failureMessage();
			return;
		}

		// if there are multiple export files, put them in a new directory
		if (mFileUris.size() > 1) {
			requestedDirectory = new File(requestedDirectory, requestedName);
			requestedName = null; // we'll use the original filenames
			if (requestedDirectory.exists()) {
				displayFileNameDialog(R.string.export_narrative_name_exists);
				return;
			}
		}
		requestedDirectory.mkdirs();
		if (!requestedDirectory.exists()) {
			failureMessage();
			return;
		}

		final File outputDirectory = requestedDirectory;
		final String chosenName = requestedName;

		runQueuedBackgroundTask(new BackgroundRunnable() {
			int mTaskResult = R.id.export_save_sd_succeeded;

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
				boolean failure = false;
				int uriCount = mFileUris.size();
				for (Uri mediaUri : mFileUris) {
					if ("content".equals(mediaUri.getScheme())) {
						// movies are a special case - their uri is in the media database
						ContentResolver contentResolver = getContentResolver();
						Cursor movieCursor = contentResolver.query(mediaUri,
								new String[] { MediaStore.Video.Media.DATA }, null, null, null);
						boolean movFailed = true;
						if (movieCursor != null) {
							if (movieCursor.moveToFirst()) {
								File movieFile = new File(movieCursor.getString(movieCursor
										.getColumnIndex(MediaStore.Video.Media.DATA)));
								File newMovieFile = new File(outputDirectory, chosenName == null ? movieFile.getName()
										: chosenName + "." + IOUtilities.getFileExtension(movieFile.getName()));
								if (uriCount == 1 && newMovieFile.exists()) { // only relevant for single file exports
									mTaskResult = R.id.export_save_sd_file_exists;
									movieCursor.close();
									return;
								}
								if (movieFile.renameTo(newMovieFile)) { // renameTo is fine as temp is always on SD card
									movFailed = false;
									contentResolver.delete(mediaUri, null, null); // no longer here, so delete
								}
							}
							movieCursor.close();
						}
						if (movFailed) {
							failure = true;
							break;
						}
					} else {
						// for other files, we can move if they're in temp; if we have the actual media path (as with
						// smil content) we must copy to ensure we don't break the narrative by removing the originals
						File mediaFile = new File(mediaUri.getPath());
						File newMediaFile = new File(outputDirectory, chosenName == null ? mediaFile.getName()
								: chosenName + "." + IOUtilities.getFileExtension(mediaFile.getName()));
						if (uriCount == 1 && newMediaFile.exists()) { // only relevant for single file exports (html)
							mTaskResult = R.id.export_save_sd_file_exists;
							return;
						}
						if (mediaFile.getAbsolutePath().startsWith(MediaPhone.DIRECTORY_TEMP.getAbsolutePath())) {
							if (!mediaFile.renameTo(newMediaFile)) { // renameTo is fine as temp is always on SD card
								failure = true;
								break;
							}
						} else {
							try {
								IOUtilities.copyFile(mediaFile, newMediaFile);
							} catch (IOException e) {
								failure = true;
								break;
							}
						}
					}
				}

				if (failure) {
					mTaskResult = R.id.export_save_sd_failed;
				}
			}
		});
	}

	private void successMessage() {
		UIUtilities.showFormattedToast(SaveNarrativeActivity.this, R.string.export_narrative_saved, Environment
				.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getName());
		finish();
	}

	private void failureMessage() {
		UIUtilities.showToast(SaveNarrativeActivity.this, R.string.export_narrative_failed);
		finish();
	}
}
