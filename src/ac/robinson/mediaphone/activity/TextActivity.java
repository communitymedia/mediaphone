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

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

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
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.RelativeLayout;

public class TextActivity extends MediaPhoneActivity {

	private String mMediaItemInternalId;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			setTheme(R.style.default_light_theme); // light looks *much* better beyond honeycomb
		}
		UIUtilities.configureActionBar(this, true, true, R.string.title_frame_editor, R.string.title_text);
		setContentView(R.layout.text_view);

		mMediaItemInternalId = null;

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mMediaItemInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putString(getString(R.string.extra_internal_id), mMediaItemInternalId);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			// do this here so that we know the layout's size for loading icons
			loadMediaContainer();
		}
	}

	@Override
	public void onBackPressed() {
		// managed to press back before loading the media - wait
		if (mMediaItemInternalId == null) {
			return;
		}

		// force hide the soft keyboard so that the layout refreshes next time we launch TODO: refresh layout?
		try {
			InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			inputMethodManager.hideSoftInputFromWindow(((EditText) findViewById(R.id.text_view)).getWindowToken(), 0);
		} catch (Throwable t) {
			// on some phones, and with custom keyboards, this fails, and crashes - catch instead
		}

		MediaItem textMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		saveCurrentText(textMediaItem);

		// always return ok, as it's not worth checking two text files are equal just to save reloading the icon
		saveLastEditedFrame(textMediaItem != null ? textMediaItem.getParentId() : null);
		setResult(Activity.RESULT_OK);
		finish();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
			onBackPressed();
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.next_previous_frame, menu);
		inflater.inflate(R.menu.add_frame, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		switch (itemId) {
			case R.id.menu_add_frame:
				if (saveCurrentText(MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId))) {
					runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
					((EditText) findViewById(R.id.text_view)).setText(""); // otherwise we copy to the new frame
				} else {
					UIUtilities.showToast(TextActivity.this, R.string.split_text_add_content);
				}
				return true;

			case R.id.menu_previous_frame:
			case R.id.menu_next_frame:
				performSwitchFrames(itemId, true);
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// the soft back button (necessary in some circumstances)
		int newVisibility = View.VISIBLE;
		int newAlignment = RelativeLayout.CENTER_HORIZONTAL;
		if (!mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
				getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
			newAlignment = RelativeLayout.ALIGN_PARENT_LEFT;
		}
		RelativeLayout.LayoutParams newLayoutParams = new RelativeLayout.LayoutParams(0, 0);
		newLayoutParams.addRule(newAlignment, -1);
		findViewById(R.id.button_finished_text).setVisibility(newVisibility);
		findViewById(R.id.text_view_nav_strut).setLayoutParams(newLayoutParams);
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
				if (intent.getBooleanExtra(getString(R.string.extra_show_options_menu), false)) {
					openOptionsMenu();
				}
			}
			if (parentInternalId == null) {
				UIUtilities.showToast(TextActivity.this, R.string.error_loading_text_editor);
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
		MediaItem textMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (textMediaItem != null) {
			EditText textBox = (EditText) findViewById(R.id.text_view);
			if (TextUtils.isEmpty(textBox.getText().toString())) { // don't delete existing content
				textBox.setText(IOUtilities.getFileContents(textMediaItem.getFile().getAbsolutePath()).toString());
			}
			// show the keyboard as a further hint (below Honeycomb it is automatic)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				try {
					InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
					manager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
				} catch (Throwable t) {
					// on some phones this causes problems
				}
			} else {
				textBox.requestFocus();
			}
		} else {
			UIUtilities.showToast(TextActivity.this, R.string.error_loading_text_editor);
			onBackPressed();
			return;
		}

		registerForSwipeEvents(); // here to avoid crashing due to double-swiping
	}

	private boolean saveCurrentText(MediaItem textMediaItem) {
		String mediaText = ((EditText) findViewById(R.id.text_view)).getText().toString();
		if (!TextUtils.isEmpty(mediaText) && textMediaItem != null) {
			FileOutputStream fileOutputStream = null;
			try {
				fileOutputStream = new FileOutputStream(textMediaItem.getFile());
				fileOutputStream.write(mediaText.getBytes());
			} catch (FileNotFoundException e) {
				return false;
			} catch (IOException e) {
				return false;
			} finally {
				IOUtilities.closeStream(fileOutputStream);
			}
			return true;
		} else {
			// so we don't leave an empty stub
			ContentResolver contentResolver = getContentResolver();
			MediaItem textToDelete = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
			textToDelete.setDeleted(true);
			MediaManager.updateMedia(contentResolver, textToDelete);
			return false;
		}
	}

	@Override
	protected void onBackgroundTaskProgressUpdate(int taskId) {
		if (taskId == Math.abs(R.id.split_frame_task_complete)) {
		}
		super.onBackgroundTaskProgressUpdate(taskId); // *must* be after other tasks
	}

	private boolean performSwitchFrames(int itemId, boolean showOptionsMenu) {
		MediaItem textMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		return switchFrames(textMediaItem.getParentId(), itemId, R.string.extra_parent_id, R.id.intent_text_editor,
				showOptionsMenu, TextActivity.class);
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
				AlertDialog.Builder builder = new AlertDialog.Builder(TextActivity.this);
				builder.setTitle(R.string.delete_text_confirmation);
				builder.setMessage(R.string.delete_text_hint);
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						MediaItem textToDelete = MediaManager.findMediaByInternalId(contentResolver,
								mMediaItemInternalId);
						textToDelete.setDeleted(true);
						MediaManager.updateMedia(contentResolver, textToDelete);
						UIUtilities.showToast(TextActivity.this, R.string.delete_text_succeeded);
						onBackPressed();
					}
				});
				AlertDialog alert = builder.create();
				alert.show();
				break;
		}
	}
}
