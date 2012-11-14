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
import java.util.ArrayList;

import ac.robinson.mediautilities.R;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
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
public class SaveNarrativeActivity extends Activity {

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

	private void displayFileNameDialog(int errorMessage) {
		AlertDialog.Builder nameDialog = new AlertDialog.Builder(this);
		nameDialog.setTitle(R.string.send_narrative_name);
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

		nameDialog.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int button) {
				File outputDirectory = new File(Environment
						.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
						getString(R.string.export_local_directory));

				String chosenName = input.getText().toString();
				if (!TextUtils.isEmpty(chosenName)) {
					chosenName = chosenName.replaceAll("[^a-zA-Z0-9 ]+", ""); // only valid filenames
					renameFiles(outputDirectory, chosenName); // not yet detected duplicate html/mp4 names; may return
				} else {
					dialog.dismiss();
					displayFileNameDialog(R.string.send_narrative_name_blank); // error - enter a name
				}
			}
		});

		nameDialog.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int button) {
				dialog.dismiss();
				finish();
			}
		});

		AlertDialog createdDialog = nameDialog.create();
		createdDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		createdDialog.show();
	}

	private void renameFiles(File outputDirectory, String chosenName) {
		if (mFileUris == null) {
			failureMessage();
			return;
		}
		int uriCount = mFileUris.size();
		if (uriCount > 1) {
			outputDirectory = new File(outputDirectory, chosenName);
			if (outputDirectory.exists()) {
				displayFileNameDialog(R.string.send_narrative_name_exists);
				return;
			}
		}
		outputDirectory.mkdirs();
		if (!outputDirectory.exists()) {
			failureMessage();
			return;
		}

		// movies are a special case - their uri is in the media database
		if (uriCount == 1) {
			Uri singleUri = mFileUris.get(0);
			if ("content".equals(singleUri.getScheme())) {
				ContentResolver contentResolver = getContentResolver();
				Cursor movieCursor = contentResolver.query(singleUri, new String[] { MediaStore.Video.Media.DATA },
						null, null, null);
				if (movieCursor.moveToFirst()) {
					File movieFile = new File(movieCursor.getString(movieCursor
							.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)));
					File newMovieFile = new File(outputDirectory, chosenName + "."
							+ IOUtilities.getFileExtension(movieFile.getName()));
					if (newMovieFile.exists()) {
						displayFileNameDialog(R.string.send_narrative_name_exists);
						return;
					}
					if (movieFile.renameTo(newMovieFile)) {
						getContentResolver().delete(singleUri, null, null); // no longer here, so delete
						successMessage();
					} else {
						failureMessage();
					}
				} else {
					failureMessage();
				}
			} else {
				File mediaFile = new File(singleUri.getPath());
				File newMediaFile = new File(outputDirectory, chosenName + "."
						+ IOUtilities.getFileExtension(mediaFile.getName()));
				if (newMediaFile.exists()) {
					displayFileNameDialog(R.string.send_narrative_name_exists);
					return;
				}
				if (mediaFile.renameTo(newMediaFile)) {
					successMessage();
				} else {
					failureMessage();
				}
			}
		} else {
			boolean renamed = true;
			for (Uri mediaUri : mFileUris) {
				File mediaFile = new File(mediaUri.getPath());
				if (!mediaFile.renameTo(new File(outputDirectory, mediaFile.getName()))) {
					renamed = false;
				}
			}
			if (renamed) {
				successMessage();
			} else {
				failureMessage();
			}
		}
	}

	private void successMessage() {
		UIUtilities.showFormattedToast(SaveNarrativeActivity.this, R.string.send_narrative_saved, Environment
				.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getName());
		finish();
	}

	private void failureMessage() {
		UIUtilities.showToast(SaveNarrativeActivity.this, R.string.send_narrative_failed);
		finish();
	}
}
