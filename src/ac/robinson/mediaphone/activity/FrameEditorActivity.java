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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.FramesManager;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.StringUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.CenteredImageTextButton;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

public class FrameEditorActivity extends MediaPhoneActivity {

	// not in MediaPhone.java because it needs more than just this to add more audio items (layouts need updating too)
	private final int MAX_AUDIO_ITEMS = 3;

	private String mFrameInternalId;
	private LinkedHashMap<String, Integer> mFrameAudioItems = new LinkedHashMap<String, Integer>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			setTheme(R.style.default_light_theme); // light looks *much* better beyond honeycomb
		}
		UIUtilities.configureActionBar(this, true, true, R.string.title_frame_editor, 0);
		setContentView(R.layout.frame_editor);

		// load previous id on screen rotation
		mFrameInternalId = null;
		if (savedInstanceState != null) {
			mFrameInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putString(getString(R.string.extra_internal_id), mFrameInternalId);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			// do this here so that we know the layout's size for loading icons
			loadFrameElements();
		}
	}

	@Override
	public void onBackPressed() {
		saveLastEditedFrame(mFrameInternalId); // so we always get the id even if we've done next/prev

		// delete frame/narrative if required
		Resources resources = getResources();
		ContentResolver contentResolver = getContentResolver();
		FrameItem editedFrame = FramesManager.findFrameByInternalId(contentResolver, mFrameInternalId);
		String nextDisplayId = mFrameInternalId;
		if (MediaManager.countMediaByParentId(contentResolver, mFrameInternalId) <= 0) {
			editedFrame.setDeleted(true);
			FramesManager.updateFrame(contentResolver, editedFrame);
		}

		// delete, or added no frame content
		if (editedFrame.getDeleted()) {
			// no narrative content - delete the narrative first for a better interface experience
			// (don't have to wait for the frame to disappear)
			int numFrames = FramesManager.countFramesByParentId(contentResolver, editedFrame.getParentId());
			if (numFrames == 0) {
				NarrativeItem narrativeToDelete = NarrativesManager.findNarrativeByInternalId(contentResolver,
						editedFrame.getParentId());
				narrativeToDelete.setDeleted(true);
				NarrativesManager.updateNarrative(contentResolver, narrativeToDelete);

			} else if (numFrames > 0) {
				// if we're the first frame, update the second frame's icon to be the main icon (i.e. with overlay)
				FrameItem nextFrame = FramesManager
						.findFirstFrameByParentId(contentResolver, editedFrame.getParentId());
				if (editedFrame.getNarrativeSequenceId() < nextFrame.getNarrativeSequenceId()) {
					FramesManager.reloadFrameIcon(resources, contentResolver, nextFrame, true);
				}

				// need the next frame id for scrolling
				ArrayList<String> frameIds = FramesManager.findFrameIdsByParentId(contentResolver,
						editedFrame.getParentId(), mFrameInternalId);
				int i = 0;
				int foundId = 0;
				for (String id : frameIds) {
					if (mFrameInternalId.equals(id)) {
						foundId = i;
						break;
					}
					i += 1;
				}
				int idCount = frameIds.size() - 1;
				if (foundId >= idCount) {
					foundId = (idCount > 0 ? idCount - 1 : 0);
				} else {
					foundId += 1;
				}
				nextDisplayId = frameIds.get(foundId);
				saveLastEditedFrame(nextDisplayId); // scroll to this frame after exiting
			}
		}

		final Intent updateFrameIntent = new Intent(FrameEditorActivity.this, NarrativeBrowserActivity.class);
		// no longer used (shared preferences instead)
		updateFrameIntent.putExtra(getString(R.string.extra_internal_id), nextDisplayId);
		setResult(Activity.RESULT_OK, updateFrameIntent);
		finish();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.next_previous_frame, menu);
		getMenuInflater().inflate(R.menu.frame_editor, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		switch (itemId) {
			case R.id.menu_make_template:
				ContentResolver contentResolver = getContentResolver();
				if (MediaManager.countMediaByParentId(contentResolver, mFrameInternalId) > 0) {
					FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, mFrameInternalId);
					runBackgroundTask(getNarrativeTemplateRunnable(currentFrame.getParentId(),
							MediaPhoneProvider.getNewInternalId(), true)); // don't need the id
				} else {
					UIUtilities.showToast(FrameEditorActivity.this, R.string.make_template_add_content);
				}
				return true;

			case R.id.menu_delete_narrative:
				deleteNarrativeDialog(mFrameInternalId);
				return true;

			case R.id.menu_previous_frame:
			case R.id.menu_next_frame:
				switchFrames(mFrameInternalId, itemId, R.string.extra_internal_id, R.id.intent_frame_editor, true,
						FrameEditorActivity.class);
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// the soft back button (necessary in some circumstances)
		int newVisibility = View.VISIBLE;
		if (!mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
				getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
		}
		findViewById(R.id.button_finished_editing).setVisibility(newVisibility);
	}

	// TODO: show error toasts on failure
	private void loadFrameElements() {

		// first launch
		if (mFrameInternalId == null) {

			// editing an existing frame
			final Intent intent = getIntent();
			if (intent != null) {
				mFrameInternalId = intent.getStringExtra(getString(R.string.extra_internal_id));
				if (intent.getBooleanExtra(getString(R.string.extra_show_options_menu), false)) {
					openOptionsMenu();
				}
			}

			// adding a new frame
			if (mFrameInternalId == null) {
				String intentNarrativeId = intent.getStringExtra(getString(R.string.extra_parent_id));
				final boolean insertNewNarrative = intentNarrativeId == null;
				final String narrativeId = insertNewNarrative ? MediaPhoneProvider.getNewInternalId()
						: intentNarrativeId;
				final String insertBeforeId = intent.getStringExtra(getString(R.string.extra_insert_before_id));
				final String insertAfterId = intent.getStringExtra(getString(R.string.extra_insert_after_id));

				// don't load the icon yet - will be loaded (or deleted) when we return
				Resources res = getResources();
				ContentResolver contentResolver = getContentResolver();
				FrameItem newFrame = new FrameItem(narrativeId, -1);
				FramesManager.addFrame(res, contentResolver, newFrame, false);
				mFrameInternalId = newFrame.getInternalId();

				// not a background task any more, because it causes concurrency problems with deleting after back press
				int narrativeSequenceIdIncrement = res.getInteger(R.integer.frame_narrative_sequence_increment);
				int narrativeSequenceId = 0;

				if (insertNewNarrative) {
					// new narrative required
					NarrativeItem newNarrative = new NarrativeItem(narrativeId,
							NarrativesManager.getNextNarrativeExternalId(contentResolver));
					NarrativesManager.addNarrative(contentResolver, newNarrative);

				} else {
					// default to inserting at the end if no before/after id is given
					if (insertBeforeId == null && insertAfterId == null) {
						narrativeSequenceId = FramesManager.findLastFrameNarrativeSequenceId(contentResolver,
								narrativeId) + narrativeSequenceIdIncrement;

					} else {
						// insert new frame - increment necessary frames after the new frame's position
						boolean insertAtStart = FrameItem.KEY_FRAME_ID_START.equals(insertBeforeId);
						ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver,
								narrativeId);
						narrativeFrames.remove(0); // don't edit the newly inserted frame yet

						int previousNarrativeSequenceId = 0;
						boolean frameFound = false;
						for (FrameItem frame : narrativeFrames) {
							if (!frameFound && (insertAtStart || frame.getInternalId().equals(insertBeforeId))) {
								frameFound = true;
								narrativeSequenceId = frame.getNarrativeSequenceId();
							}
							if (frameFound) {
								int currentNarrativeSequenceId = frame.getNarrativeSequenceId();
								if (currentNarrativeSequenceId <= narrativeSequenceId
										|| currentNarrativeSequenceId <= previousNarrativeSequenceId) {

									frame.setNarrativeSequenceId(currentNarrativeSequenceId + 1);
									if (insertAtStart) {
										FramesManager.updateFrame(res, contentResolver, frame, true);
										insertAtStart = false;
									} else {
										FramesManager.updateFrame(contentResolver, frame);
									}
									previousNarrativeSequenceId = frame.getNarrativeSequenceId();
								} else {
									break;
								}
							}
							if (!frameFound && frame.getInternalId().equals(insertAfterId)) {
								frameFound = true;
								narrativeSequenceId = frame.getNarrativeSequenceId() + narrativeSequenceIdIncrement;
							}
						}
					}
				}

				FrameItem thisFrame = FramesManager.findFrameByInternalId(contentResolver, mFrameInternalId);
				thisFrame.setNarrativeSequenceId(narrativeSequenceId);
				FramesManager.updateFrame(contentResolver, thisFrame);
			}
		}

		// load existing content into buttons
		ArrayList<MediaItem> frameComponents = MediaManager.findMediaByParentId(getContentResolver(), mFrameInternalId);
		boolean imageLoaded = false;
		boolean audioLoaded = false;
		mFrameAudioItems.clear();
		boolean textLoaded = false;

		((CenteredImageTextButton) findViewById(R.id.button_take_picture_video))
				.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.ic_menu_camera, 0, 0);
		// audio buttons are loaded/reset after audio files are loaded
		((CenteredImageTextButton) findViewById(R.id.button_add_text)).setText("");

		Resources resources = getResources();
		for (MediaItem currentItem : frameComponents) {
			int currentType = currentItem.getType();

			if (!imageLoaded
					&& (currentType == MediaPhoneProvider.TYPE_IMAGE_BACK
							|| currentType == MediaPhoneProvider.TYPE_IMAGE_FRONT || currentType == MediaPhoneProvider.TYPE_VIDEO)) {

				CenteredImageTextButton cameraButton = (CenteredImageTextButton) findViewById(R.id.button_take_picture_video);
				TypedValue resourceValue = new TypedValue();
				resources.getValue(R.attr.image_button_fill_percentage, resourceValue, true);
				int pictureSize = (int) ((resources.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? cameraButton
						.getWidth() : cameraButton.getHeight()) * resourceValue.getFloat());
				BitmapDrawable cachedIcon = new BitmapDrawable(resources, BitmapUtilities.loadAndCreateScaledBitmap(
						currentItem.getFile().getAbsolutePath(), pictureSize, pictureSize,
						BitmapUtilities.ScalingLogic.CROP, true));
				cameraButton.setCompoundDrawablesWithIntrinsicBounds(null, cachedIcon, null, null);
				imageLoaded = true;

			} else if (!audioLoaded && currentType == MediaPhoneProvider.TYPE_AUDIO) {
				mFrameAudioItems.put(currentItem.getInternalId(), currentItem.getDurationMilliseconds());
				if (mFrameAudioItems.size() >= MAX_AUDIO_ITEMS) {
					audioLoaded = true;
				}

			} else if (!textLoaded && currentType == MediaPhoneProvider.TYPE_TEXT) {
				String textSnippet = IOUtilities.getFileContentSnippet(currentItem.getFile().getAbsolutePath(),
						resources.getInteger(R.integer.text_snippet_length));
				((CenteredImageTextButton) findViewById(R.id.button_add_text)).setText(textSnippet);
				textLoaded = true;
			}
		}

		resetAudioButtons();
		registerForSwipeEvents(); // here to avoid crashing due to double-swiping
	}

	private int getAudioButtonId(int audioIndex) {
		switch (audioIndex) {
			case 0:
				return R.id.button_record_audio_1;
			case 1:
				return R.id.button_record_audio_2;
			case 2:
				return R.id.button_record_audio_3;
		}
		return -1;
	}

	private int getAudioIndex(int buttonId) {
		switch (buttonId) {
			case R.id.button_record_audio_1:
				return 0;
			case R.id.button_record_audio_2:
				return 1;
			case R.id.button_record_audio_3:
				return 2;
		}
		return -1;
	}

	private void resetAudioButtons() {
		// reset existing buttons
		for (int i = 0; i < MAX_AUDIO_ITEMS; i++) {
			final CenteredImageTextButton audioButton = (CenteredImageTextButton) findViewById(getAudioButtonId(i));
			audioButton.setText("");
			audioButton.setVisibility(View.VISIBLE);
		}

		// load the audio content
		int audioIndex = 0;
		for (Entry<String, Integer> audioMedia : mFrameAudioItems.entrySet()) {
			CenteredImageTextButton audioButton = (CenteredImageTextButton) findViewById(getAudioButtonId(audioIndex));
			audioButton.setText(StringUtilities.millisecondsToTimeString(audioMedia.getValue(), false));
			audioIndex += 1;
		}

		// hide unnecessary buttons
		if (audioIndex < 2) {
			((CenteredImageTextButton) findViewById(getAudioButtonId(2))).setVisibility(View.GONE);
			if (audioIndex < 1) {
				((CenteredImageTextButton) findViewById(getAudioButtonId(1))).setVisibility(View.GONE);
			}
		}
	}

	@Override
	protected boolean swipeNext() {
		return switchFrames(mFrameInternalId, R.id.menu_next_frame, R.string.extra_internal_id,
				R.id.intent_frame_editor, false, FrameEditorActivity.class);
	}

	@Override
	protected boolean swipePrevious() {
		return switchFrames(mFrameInternalId, R.id.menu_previous_frame, R.string.extra_internal_id,
				R.id.intent_frame_editor, false, FrameEditorActivity.class);
	}

	public void handleButtonClicks(View currentButton) {

		final int buttonId = currentButton.getId();
		switch (buttonId) {
			case R.id.button_finished_editing:
				onBackPressed();
				break;

			case R.id.button_take_picture_video:
				final Intent takePictureIntent = new Intent(FrameEditorActivity.this, CameraActivity.class);
				takePictureIntent.putExtra(getString(R.string.extra_parent_id), mFrameInternalId);
				startActivityForResult(takePictureIntent, R.id.intent_picture_editor);
				break;

			case R.id.button_record_audio_1:
			case R.id.button_record_audio_2:
			case R.id.button_record_audio_3:
				final Intent recordAudioIntent = new Intent(FrameEditorActivity.this, AudioActivity.class);
				recordAudioIntent.putExtra(getString(R.string.extra_parent_id), mFrameInternalId);
				int selectedAudioIndex = getAudioIndex(buttonId);
				int currentIndex = 0;
				for (String audioMediaId : mFrameAudioItems.keySet()) {
					if (currentIndex == selectedAudioIndex) {
						recordAudioIntent.putExtra(getString(R.string.extra_internal_id), audioMediaId);
						break;
					}
					currentIndex += 1;
				}
				startActivityForResult(recordAudioIntent, R.id.intent_audio_editor);
				break;

			case R.id.button_add_text:
				final Intent addTextIntent = new Intent(FrameEditorActivity.this, TextActivity.class);
				addTextIntent.putExtra(getString(R.string.extra_parent_id), mFrameInternalId);
				startActivityForResult(addTextIntent, R.id.intent_text_editor);
				break;

			case R.id.button_delete_frame:
				AlertDialog.Builder builder = new AlertDialog.Builder(FrameEditorActivity.this);
				builder.setTitle(R.string.delete_frame_confirmation);
				builder.setMessage(R.string.delete_frame_hint);
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						FrameItem frameToDelete = FramesManager
								.findFrameByInternalId(contentResolver, mFrameInternalId);
						frameToDelete.setDeleted(true);
						FramesManager.updateFrame(contentResolver, frameToDelete);
						showDialog(R.id.dialog_background_runner_in_progress); // so they can't press any buttons
						onBackPressed();
					}
				});
				AlertDialog alert = builder.create();
				alert.show();
				break;

			case R.id.button_add_frame_editor:
				ContentResolver contentResolver = getContentResolver();
				if (MediaManager.countMediaByParentId(contentResolver, mFrameInternalId) > 0) {
					showDialog(R.id.dialog_background_runner_in_progress);
					currentButton.setEnabled(false); // don't let them press twice
					final Intent frameEditorIntent = new Intent(FrameEditorActivity.this, FrameEditorActivity.class);
					FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, mFrameInternalId);
					frameEditorIntent.putExtra(getString(R.string.extra_parent_id), currentFrame.getParentId());
					frameEditorIntent.putExtra(getString(R.string.extra_insert_after_id), mFrameInternalId);
					startActivity(frameEditorIntent); // no result so that the original exits
					onBackPressed();
				} else {
					UIUtilities.showToast(FrameEditorActivity.this, R.string.split_frame_add_content);
				}
				break;
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case R.id.intent_picture_editor:
			case R.id.intent_audio_editor:
			case R.id.intent_text_editor:
				if (resultCode == Activity.RESULT_OK) {

					// change the frame that is displayed, if applicable
					SharedPreferences frameIdSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME,
							Context.MODE_PRIVATE);
					String newInternalId = frameIdSettings.getString(getString(R.string.key_last_edited_frame), null);
					if (newInternalId != null && !newInternalId.equals(mFrameInternalId)) {
						mFrameInternalId = newInternalId;
						String extraKey = getString(R.string.extra_internal_id);
						final Intent launchingIntent = getIntent();
						if (launchingIntent != null) {
							launchingIntent.removeExtra(extraKey);
							launchingIntent.putExtra(extraKey, mFrameInternalId);
						}
						setIntent(launchingIntent);
					}

					// update the icon, regardless
					runBackgroundTask(getFrameIconUpdaterRunnable(mFrameInternalId));
				}
				break;

			case R.id.intent_frame_editor:
				// ids are passed back through a saved preference object
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}