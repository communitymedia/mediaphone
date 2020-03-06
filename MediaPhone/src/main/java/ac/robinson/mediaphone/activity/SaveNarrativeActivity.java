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

import android.Manifest;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * This Activity is a bit of a hack to allow saving from an extra item in an Intent chooser - its only purpose is to
 * move files to a specific output folder (and will fail if they're not already on the same mount point as the SD card)
 *
 * @author Simon Robinson
 */
public class SaveNarrativeActivity extends MediaPhoneActivity {

	private static final int PERMISSION_SD_STORAGE = 104;

	private File mOutputDirectory;
	private boolean mUsingDefaultOutputDirectory;
	private ArrayList<Uri> mFileUris;
	private String mSelectedFileName = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Intent intent = getIntent();
		if (intent == null) {
			failureMessage();
			return;
		}

		// TODO: this is a hack to try to hide the activity title bar below the keyboard
		getWindow().setGravity(Gravity.BOTTOM);

		String action = intent.getAction();
		String type = intent.getType();
		mFileUris = null;
		if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
			if (type.startsWith(getString(R.string.export_mime_type))) {
				ArrayList<Uri> fileUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
				if (fileUris != null && fileUris.size() > 0) {
					mFileUris = fileUris;

					Uri singleFile = mFileUris.get(0);
					String fileScheme = singleFile.getScheme();
					if ("file".equals(fileScheme)) {
						mSelectedFileName = new File(singleFile.toString()).getName();
					} else if ("content".equals(fileScheme)) {
						try {
							Cursor cursor = getContentResolver().query(singleFile, null, null, null, null);
							int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
							cursor.moveToFirst();
							mSelectedFileName = cursor.getString(nameIndex);
							cursor.close();
						} catch (Exception ignored) {
						}
					}
					if (!TextUtils.isEmpty(mSelectedFileName)) {
						mSelectedFileName = IOUtilities.removeExtension(mSelectedFileName);
					}

					displayFileNameDialog(0);

					if (ContextCompat.checkSelfPermission(SaveNarrativeActivity.this,
							Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
							PackageManager.PERMISSION_GRANTED) {
						if (ActivityCompat.shouldShowRequestPermissionRationale(SaveNarrativeActivity.this,
								Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
							UIUtilities.showFormattedToast(SaveNarrativeActivity.this, R.string.permission_storage_rationale,
									getString(R.string.app_name));
						}
						ActivityCompat.requestPermissions(SaveNarrativeActivity.this, new String[]{
								Manifest.permission.WRITE_EXTERNAL_STORAGE
						}, PERMISSION_SD_STORAGE);
					}
				}
			}
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		mOutputDirectory = null;
		String selectedOutputDirectory = mediaPhoneSettings.getString(getString(R.string.key_export_directory), null);
		if (!TextUtils.isEmpty(selectedOutputDirectory)) {
			File outputFile = new File(selectedOutputDirectory);
			if (outputFile.exists()) {
				mOutputDirectory = outputFile;
				mUsingDefaultOutputDirectory = false;
			}
		}
		if (mOutputDirectory == null) {
			mOutputDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
					getString(R.string.export_local_directory));
			mUsingDefaultOutputDirectory = true;
		}
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
			default:
				break;
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		switch (requestCode) {
			case PERMISSION_SD_STORAGE:
				if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
					UIUtilities.showFormattedToast(SaveNarrativeActivity.this, R.string.permission_storage_error,
							getString(R.string.app_name));
					finish();
				}
				break;
			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}
	}

	private void displayFileNameDialog(int errorMessage) {
		AlertDialog.Builder nameDialog = new AlertDialog.Builder(SaveNarrativeActivity.this,
				R.style.Theme_MediaPhone_AlertDialog);
		nameDialog.setTitle(R.string.export_narrative_name);
		if (errorMessage != 0) {
			nameDialog.setMessage(errorMessage);
		}

		Resources resources = getResources();
		int dialogPadding = resources.getDimensionPixelSize(R.dimen.save_narrative_dialog_padding);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setGravity(Gravity.CENTER_HORIZONTAL);
		layout.setPadding(dialogPadding, 0, dialogPadding, 0);

		final EditText fileInput = new EditText(SaveNarrativeActivity.this);
		if (!TextUtils.isEmpty(mSelectedFileName)) {
			fileInput.setText(mSelectedFileName);
		}
		fileInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
		fileInput.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		fileInput.setMaxLines(1);
		fileInput.setSelectAllOnFocus(true);
		fileInput.setFilters(new InputFilter[]{ mFileNameFilter });
		layout.addView(fileInput);
		nameDialog.setView(layout);

		nameDialog.setPositiveButton(R.string.button_save, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int button) {
				dialog.dismiss();
				handleSaveClick(fileInput);
			}
		});

		nameDialog.setNegativeButton(R.string.button_cancel, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int button) {
				dialog.dismiss();
				finish();
			}
		});

		final AlertDialog createdDialog = nameDialog.create();
		createdDialog.setCanceledOnTouchOutside(false); // so we don't leave the activity in the background by mistake
		createdDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

		fileInput.setOnEditorActionListener(new EditText.OnEditorActionListener() {
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_ACTION_DONE) {
					if (createdDialog.isShowing()) {
						createdDialog.dismiss();
					}
					handleSaveClick(v);
					return true;
				}
				return false;
			}
		});

		createdDialog.show();
		fileInput.requestFocus();
	}

	InputFilter mFileNameFilter = new InputFilter() {
		public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
			if (source.length() < 1) {
				return null;
			}
			char last = source.charAt(source.length() - 1);
			String reservedChars = "?:\"*|/\\<>";
			if (reservedChars.indexOf(last) > -1) {
				return source.subSequence(0, source.length() - 1);
			}
			return null;
		}
	};

	private void handleSaveClick(TextView fileInput) {
		String chosenName = fileInput.getText().toString();
		if (!TextUtils.isEmpty(chosenName)) {
			// chosenName = chosenName.replaceAll("[^a-zA-Z0-9 ]+", ""); // only valid filenames (now replaced by filter, above)
			saveFilesToSD(mOutputDirectory, chosenName); // not yet detected duplicate html/mov names; may return
		} else {
			displayFileNameDialog(R.string.export_narrative_name_blank); // error - enter a name
		}
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
						Cursor movieCursor = contentResolver.query(mediaUri, new String[]{ MediaStore.Video.Media.DATA }, null,
								null, null);
						if (movieCursor != null) {
							if (movieCursor.moveToFirst()) {
								File movieFile =
										new File(movieCursor.getString(movieCursor.getColumnIndex(MediaStore.Video.Media.DATA)));
								File newMovieFile = new File(outputDirectory, chosenName == null ? movieFile.getName() :
										chosenName + "." + IOUtilities.getFileExtension(movieFile.getName()));
								if (uriCount == 1 && newMovieFile.exists()) { // only relevant for single file exports
									mTaskResult = R.id.export_save_sd_file_exists;
									movieCursor.close();
									return;
								}
								if (IOUtilities.moveFile(movieFile, newMovieFile)) {
									contentResolver.delete(mediaUri, null, null); // no longer here, so delete
								} else {
									failure = true;
								}
							}
							movieCursor.close();
						}
					} else {
						// for other files, we can move if they're in temp; if we have the actual media path (as with
						// smil content) we must copy to ensure we don't break the narrative by removing the originals
						File mediaFile = new File(mediaUri.getPath());
						File newMediaFile = new File(outputDirectory, chosenName == null ? mediaFile.getName() :
								chosenName + "" + "." + IOUtilities.getFileExtension(mediaFile.getName()));
						if (uriCount == 1 && newMediaFile.exists()) { // only relevant for single file exports (html)
							mTaskResult = R.id.export_save_sd_file_exists;
							return;
						}
						if (mediaFile.getAbsolutePath().startsWith(MediaPhone.DIRECTORY_TEMP.getAbsolutePath())) {
							if (!IOUtilities.moveFile(mediaFile, newMediaFile)) {
								failure = true;
							}
						} else {
							try {
								IOUtilities.copyFile(mediaFile, newMediaFile);
							} catch (IOException e) {
								failure = true;
							}
						}
					}
					if (failure) {
						break;
					}
				}

				if (failure) {
					mTaskResult = R.id.export_save_sd_failed;
				}
			}
		});
	}

	private void successMessage() {
		// TODO: we probably don't need to echo the directory name here (and it is a little confusing for the root folder)
		File outputDirectoryToDisplay = mUsingDefaultOutputDirectory ?
				Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) : mOutputDirectory;
		String directoryName = outputDirectoryToDisplay.getName();
		if (outputDirectoryToDisplay.equals(Environment.getExternalStorageDirectory())) {
			directoryName = "/";
		}
		UIUtilities.showFormattedToast(SaveNarrativeActivity.this, R.string.export_narrative_saved, directoryName);
		finish();
	}

	private void failureMessage() {
		UIUtilities.showToast(SaveNarrativeActivity.this, R.string.export_narrative_failed);
		finish();
	}
}
