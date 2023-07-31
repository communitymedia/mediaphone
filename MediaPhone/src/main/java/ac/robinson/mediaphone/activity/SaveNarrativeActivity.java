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
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import androidx.documentfile.provider.DocumentFile;

/**
 * This Activity is a bit of a hack to allow saving from an extra item in an Intent chooser - its only purpose is to
 * move files to a specific output folder (and will fail if they're not already on the same mount point as the SD card)
 *
 * @author Simon Robinson
 */
public class SaveNarrativeActivity extends MediaPhoneActivity {

	private static final int PERMISSION_SD_STORAGE = 104;

	private File mOutputDirectory;
	private Uri mOutputUri;
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

					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
							ContextCompat.checkSelfPermission(SaveNarrativeActivity.this,
									Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
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
		mOutputUri = null;
		String settingsKey = getString(R.string.key_export_directory);
		String selectedOutputDirectory = mediaPhoneSettings.getString(settingsKey, null);
		if (!TextUtils.isEmpty(selectedOutputDirectory)) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				DocumentFile pickedDir = DocumentFile.fromTreeUri(SaveNarrativeActivity.this,
						Uri.parse(selectedOutputDirectory));
				// note: pickedDir is not actually nullable as Q is >= 21 (see null return in DocumentFile.fromTreeUri)
				if (pickedDir.exists() && pickedDir.isDirectory() && pickedDir.canWrite()) {
					mOutputUri = pickedDir.getUri();
					mUsingDefaultOutputDirectory = false;
				} else {
					// this file doesn't exist any more; we need to reset the setting
					SharedPreferences.Editor exportEditor = mediaPhoneSettings.edit();
					exportEditor.remove(settingsKey);
					exportEditor.apply();
				}
			} else {
				File outputFile = new File(selectedOutputDirectory);
				if (outputFile.exists()) {
					mOutputDirectory = outputFile;
					mUsingDefaultOutputDirectory = false;
				}
			}
		}
		if (mOutputDirectory == null && mOutputUri == null) {
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
		if (taskId == R.id.export_save_sd_succeeded) {
			successMessage();
		} else if (taskId == R.id.export_save_sd_failed) {
			failureMessage();
		} else if (taskId == R.id.export_save_sd_file_exists) {
			displayFileNameDialog(R.string.export_narrative_name_exists);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == PERMISSION_SD_STORAGE) {
			if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
				UIUtilities.showFormattedToast(SaveNarrativeActivity.this, R.string.permission_storage_error,
						getString(R.string.app_name));
				finish();
			}
		} else {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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

		nameDialog.setPositiveButton(R.string.button_save, (dialog, button) -> {
			dialog.dismiss();
			handleSaveClick(fileInput);
		});

		nameDialog.setNegativeButton(R.string.button_cancel, (dialog, button) -> {
			dialog.dismiss();
			finish();
		});

		final AlertDialog createdDialog = nameDialog.create();
		createdDialog.setCanceledOnTouchOutside(false); // so we don't leave the activity in the background by mistake
		createdDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

		fileInput.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				if (createdDialog.isShowing()) {
					createdDialog.dismiss();
				}
				handleSaveClick(v);
				return true;
			}
			return false;
		});

		createdDialog.show();
		fileInput.requestFocus();
	}

	private final InputFilter mFileNameFilter = (source, start, end, dest, dstart, dend) -> {
		if (source.length() < 1) {
			return null;
		}
		char last = source.charAt(source.length() - 1);
		String reservedChars = "?:\"*|/\\<>";
		if (reservedChars.indexOf(last) > -1) {
			return source.subSequence(0, source.length() - 1);
		}
		return null;
	};

	private void handleSaveClick(TextView fileInput) {
		String chosenName = fileInput.getText().toString();
		if (!TextUtils.isEmpty(chosenName)) {
			// chosenName = chosenName.replaceAll("[^a-zA-Z0-9 ]+", ""); // only valid filenames (now replaced by filter, above)
			saveFilesToSD(chosenName); // not yet detected duplicate html/mov names; may return
		} else {
			displayFileNameDialog(R.string.export_narrative_name_blank); // error - enter a name
		}
	}

	private void saveFilesToSD(String requestedName) {
		if (mFileUris == null) {
			failureMessage();
			return;
		}

		// pre-Q, if there are multiple export files, put them in a new directory before creation
		File requestedDirectory = mOutputDirectory;
		DocumentFile requestedDocumentFile = null;
		int uriCount = mFileUris.size();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			if (uriCount > 1) {
				requestedDirectory = new File(requestedDirectory, requestedName);
				if (requestedDirectory.exists()) {
					displayFileNameDialog(R.string.export_narrative_name_exists);
					return;
				}
			}

			// pre-Q we were able to allow custom directories, and so had to create them ourselves
			requestedDirectory.mkdirs();
			if (!requestedDirectory.exists()) {
				failureMessage();
				return;
			}

		} else {
			if (mUsingDefaultOutputDirectory) {
				// we can't do anything to check for files already existing because we have no control over their location
				requestedDirectory = new File(requestedName);
			} else {
				// post-Q (SDK 29), all file access is a nightmare (it is slow and complex all round as a start, but the main
				// pain point is that it has zero reliable backwards compatibility, so we have to support both file-based *and*
				// SAF-based methods)
				requestedDocumentFile = DocumentFile.fromTreeUri(SaveNarrativeActivity.this, mOutputUri);
				if (uriCount > 1) {
					if (requestedDocumentFile.findFile(requestedName) != null) {
						displayFileNameDialog(R.string.export_narrative_name_exists);
						return;
					}

					requestedDocumentFile = requestedDocumentFile.createDirectory(requestedName);
					if (requestedDocumentFile == null) {
						failureMessage();
						return;
					}
				}
			}
		}

		final File outputDirectory = requestedDirectory;
		final String chosenName = uriCount > 1 ? null : requestedName;
		final DocumentFile outputDocumentFile = requestedDocumentFile;

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
						// before SDK 29 movies special cases - their uri is in the media database (to help with YouTube export)
						ContentResolver contentResolver = getContentResolver();
						Cursor movieCursor = contentResolver.query(mediaUri, new String[]{ MediaStore.Video.Media.DATA }, null,
								null, null);
						if (movieCursor != null) {
							if (movieCursor.moveToFirst()) {
								File movieFile = new File(
										movieCursor.getString(movieCursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)));
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
						File mediaFile = new File(mediaUri.getPath());
						String fileName = chosenName == null ? mediaFile.getName() :
								chosenName + "." + IOUtilities.getFileExtension(mediaFile.getName());

						// from SDK 29 we are not allowed to access files except through the slow, convoluted (and not at all
						// backwards-compatible) Storage Access Framework
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							Uri outputUri;
							ContentResolver contentResolver = getContentResolver();
							if (!mUsingDefaultOutputDirectory) {
								if (outputDocumentFile.findFile(fileName) != null) {
									mTaskResult = R.id.export_save_sd_file_exists;
									return;
								}
								DocumentFile file = outputDocumentFile.createFile(getString(R.string.export_mime_type),
										fileName);
								outputUri = file.getUri();
							} else {
								ContentValues contentValues = new ContentValues();
								contentValues.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
								contentValues.put(MediaStore.Downloads.MIME_TYPE, getString(R.string.export_mime_type));
								contentValues.put(MediaStore.Downloads.RELATIVE_PATH,
										Environment.DIRECTORY_DOWNLOADS + File.separator +
												getString(R.string.export_local_directory) +
												(uriCount > 1 ? File.separator + outputDirectory.getName() : ""));
								contentValues.put(MediaStore.Downloads.IS_PENDING, 1);
								outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
							}
							if (outputUri != null) {
								OutputStream outputStream = null;
								try {
									outputStream = contentResolver.openOutputStream(outputUri);
									IOUtilities.copyFile(mediaFile, outputStream);
									if (mUsingDefaultOutputDirectory) { // record that the file is no-longer pending
										ContentValues contentValues = new ContentValues();
										contentValues.put(MediaStore.Downloads.IS_PENDING, 0);
										contentResolver.update(outputUri, contentValues, null, null);
									}
								} catch (IOException e) {
									failure = true;
								} finally {
									IOUtilities.closeStream(outputStream);
								}
							} else {
								failure = true;
							}

						} else {
							// otherwise, we can save normally - move files if they're in temp; if we have the actual media path
							// (e.g., SMIL content) we must copy to ensure we don't break the narrative by removing the originals
							File newMediaFile = new File(outputDirectory, fileName);
							if (uriCount == 1 && newMediaFile.exists()) { // only relevant for single file exports
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
		String directoryName = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getName();
		if (!mUsingDefaultOutputDirectory) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				if (mOutputDirectory.equals(Environment.getExternalStorageDirectory())) {
					directoryName = "/";
				} else {
					directoryName = mOutputDirectory.getName();
				}
			} else {
				String outputUri = mOutputUri.toString();
				try {
					outputUri = URLDecoder.decode(outputUri, StandardCharsets.UTF_8.name());
				} catch (UnsupportedEncodingException ignored) {
				}
				directoryName = outputUri;
				String[] protocolParts = directoryName.split(":");
				if (protocolParts.length > 0) {
					directoryName = protocolParts[protocolParts.length - 1];
					String[] nameParts = directoryName.split("/");
					if (nameParts.length > 0) {
						directoryName = nameParts[nameParts.length - 1];
					}
				}
			}
		}
		UIUtilities.showFormattedToast(SaveNarrativeActivity.this, R.string.export_narrative_saved, directoryName);
		finish();
	}

	private void failureMessage() {
		UIUtilities.showToast(SaveNarrativeActivity.this, R.string.export_narrative_failed);
		finish();
	}
}
