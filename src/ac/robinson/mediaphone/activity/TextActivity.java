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

import java.io.FileOutputStream;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

public class TextActivity extends MediaPhoneActivity {

	private String mMediaItemInternalId = null;
	private boolean mHasEditedMedia = false;
	private boolean mShowOptionsMenu = false;
	private boolean mSwitchedFrames = false;

	private EditText mEditText;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			setTheme(R.style.default_light_theme); // light looks *much* better beyond honeycomb
		}
		UIUtilities.configureActionBar(this, true, true, R.string.title_frame_editor, R.string.title_text);
		setContentView(R.layout.text_view);

		mEditText = (EditText) findViewById(R.id.text_view);
		mMediaItemInternalId = null;
		mShowOptionsMenu = false;
		mSwitchedFrames = false;

		// load previous state on screen rotation
		if (savedInstanceState != null) {
			mMediaItemInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			mHasEditedMedia = savedInstanceState.getBoolean(getString(R.string.extra_media_edited));
			mSwitchedFrames = savedInstanceState.getBoolean(getString(R.string.extra_switched_frames));
			if (mHasEditedMedia) {
				setBackButtonIcons(TextActivity.this, R.id.button_finished_text, 0, true);
			}
		}

		// load the media itself
		loadMediaContainer();
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putString(getString(R.string.extra_internal_id), mMediaItemInternalId);
		savedInstanceState.putBoolean(getString(R.string.extra_media_edited), mHasEditedMedia);
		savedInstanceState.putBoolean(getString(R.string.extra_switched_frames), mSwitchedFrames);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			if (mShowOptionsMenu) {
				mShowOptionsMenu = false;
				openOptionsMenu();
			}
			registerForSwipeEvents(); // here to avoid crashing due to double-swiping
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		// we want to get notifications when the text is changed (but after adding existing text)
		mEditText.addTextChangedListener(mTextWatcher);
	}

	@Override
	protected void onPause() {
		// we don't want to get the notification that the text was removed from the window on pause or destroy
		mEditText.removeTextChangedListener(mTextWatcher);
		super.onPause();
	}

	private TextWatcher mTextWatcher = new TextWatcher() {
		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			if (!mHasEditedMedia) {
				mHasEditedMedia = true; // so the action bar refreshes to the correct icon
				setBackButtonIcons(TextActivity.this, R.id.button_finished_text, 0, true);
			}
			mHasEditedMedia = true;
		}

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void afterTextChanged(Editable s) {
			mHasEditedMedia = true; // just in case
		}
	};

	@Override
	public void onBackPressed() {
		// managed to press back before loading the media - wait
		if (mMediaItemInternalId == null) {
			return;
		}

		final MediaItem textMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		if (textMediaItem != null) {
			saveCurrentText(textMediaItem);
			saveLastEditedFrame(textMediaItem.getParentId());
		}

		// force hide the soft keyboard so that the layout refreshes next time we launch TODO: refresh layout?
		try {
			InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			inputMethodManager.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
		} catch (Throwable t) {
			// on some phones, and with custom keyboards, this fails, and crashes - catch instead
		}

		setResult(mHasEditedMedia ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
		super.onBackPressed();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
			onBackPressed(); // so we go back as well as hiding the keyboard
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		setupMenuNavigationButtonsFromMedia(inflater, menu, getContentResolver(), mMediaItemInternalId, mHasEditedMedia);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		switch (itemId) {
			case R.id.menu_previous_frame:
			case R.id.menu_next_frame:
				performSwitchFrames(itemId, true);
				return true;

			case R.id.menu_add_frame:
				final MediaItem textMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (textMediaItem != null && saveCurrentText(textMediaItem)) {
					runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
				} else {
					UIUtilities.showToast(TextActivity.this, R.string.split_text_add_content);
				}
				return true;

			case R.id.menu_back_without_editing:
			case R.id.menu_finished_editing:
				onBackPressed();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// the soft done/back button
		int newVisibility = View.VISIBLE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
				|| !mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
						getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
		}
		findViewById(R.id.button_finished_text).setVisibility(newVisibility);
	}

	private void loadMediaContainer() {

		// first launch
		ContentResolver contentResolver = getContentResolver();
		if (mMediaItemInternalId == null) {

			// editing an existing frame
			String parentInternalId = null;
			final Intent intent = getIntent();
			if (intent != null) {
				parentInternalId = intent.getStringExtra(getString(R.string.extra_parent_id));
				mShowOptionsMenu = intent.getBooleanExtra(getString(R.string.extra_show_options_menu), false);
				mSwitchedFrames = intent.getBooleanExtra(getString(R.string.extra_switched_frames), false);
			}
			if (parentInternalId == null) {
				UIUtilities.showToast(TextActivity.this, R.string.error_loading_text_editor);
				mMediaItemInternalId = "-1"; // so we exit
				onBackPressed();
				return;
			}

			// get existing content if it exists
			mMediaItemInternalId = FrameItem.getTextContentId(contentResolver, parentInternalId);

			// add a new media item if it doesn't already exist
			if (mMediaItemInternalId == null) {
				MediaItem textMediaItem = new MediaItem(parentInternalId, MediaPhone.EXTENSION_TEXT_FILE,
						MediaPhoneProvider.TYPE_TEXT);
				mMediaItemInternalId = textMediaItem.getInternalId();
				MediaManager.addMedia(contentResolver, textMediaItem);
			}
		}

		// load any existing text
		final MediaItem textMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (textMediaItem != null) {
			if (TextUtils.isEmpty(mEditText.getText().toString())) { // don't delete existing (i.e. changed) content
				mEditText.setText(IOUtilities.getFileContents(textMediaItem.getFile().getAbsolutePath()).toString());
			}
			// show the keyboard as a further hint (below Honeycomb it is automatic)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) { // TODO: improve these keyboard manipulations
				try {
					InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
					manager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
				} catch (Throwable t) {
					// on some phones this causes problems
				}
			}
			mEditText.requestFocus();
		} else {
			UIUtilities.showToast(TextActivity.this, R.string.error_loading_text_editor);
			onBackPressed();
			return;
		}
	}

	private boolean saveCurrentText(MediaItem textMediaItem) {
		String mediaText = mEditText.getText().toString();
		if (!TextUtils.isEmpty(mediaText)) {
			if (mHasEditedMedia) {
				FileOutputStream fileOutputStream = null;
				try {
					fileOutputStream = new FileOutputStream(textMediaItem.getFile());
					fileOutputStream.write(mediaText.getBytes());
					fileOutputStream.flush(); // does nothing in FileOutputStream
				} catch (Throwable t) {
					return false;
				} finally {
					IOUtilities.closeStream(fileOutputStream);
				}

				// update the icon
				runBackgroundTask(getFrameIconUpdaterRunnable(textMediaItem.getParentId()));
			}
			return true;
		} else {
			// so we don't leave an empty stub
			textMediaItem.setDeleted(true);
			MediaManager.updateMedia(getContentResolver(), textMediaItem);

			// update the icon to remove the text
			runBackgroundTask(getFrameIconUpdaterRunnable(textMediaItem.getParentId()));
			return false;
		}
	}

	@Override
	protected void onBackgroundTaskProgressUpdate(int taskId) {
		if (taskId == Math.abs(R.id.split_frame_task_complete)) {
			mEditText.setText(""); // otherwise we copy to the new frame
			mHasEditedMedia = false;
			setBackButtonIcons(TextActivity.this, R.id.button_finished_text, 0, false);
		}
		super.onBackgroundTaskProgressUpdate(taskId); // *must* be after other tasks
	}

	private boolean performSwitchFrames(int itemId, boolean showOptionsMenu) {
		if (mMediaItemInternalId != null) {
			final MediaItem textMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
					mMediaItemInternalId);
			if (textMediaItem != null) {
				return switchFrames(textMediaItem.getParentId(), itemId, R.string.extra_parent_id, showOptionsMenu,
						TextActivity.class);
			}
		}
		return false;
	}

	@Override
	protected boolean swipeNext() {
		return performSwitchFrames(R.id.menu_next_frame, false);
	}

	@Override
	protected boolean swipePrevious() {
		return performSwitchFrames(R.id.menu_previous_frame, false);
	}

	public void handleButtonClicks(View currentButton) {
		switch (currentButton.getId()) {
			case R.id.button_finished_text:
				onBackPressed();
				break;

			case R.id.button_delete_text:
				final AlertDialog.Builder builder = new AlertDialog.Builder(TextActivity.this);
				builder.setTitle(R.string.delete_text_confirmation);
				builder.setMessage(R.string.delete_text_hint);
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						mEditText.setText(""); // updated & deleted in onBackPressed
						UIUtilities.showToast(TextActivity.this, R.string.delete_text_succeeded);
						onBackPressed();
					}
				});
				final AlertDialog alert = builder.create();
				alert.show();
				break;
		}
	}
}
