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
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import java.io.File;
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
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class FrameEditorActivity extends MediaPhoneActivity {

	private static final int PERMISSION_CAMERA = 101;
	private static final int PERMISSION_AUDIO = 102;
	private String mPermissionsItemClicked = null; // temporarily store params during permission request (but not on rotation)
	private int mPermissionsSelectedAudioIndex;
	private boolean mPermissionsPreventSpanning;

	private String mFrameInternalId;
	private boolean mHasEditedMedia = false;
	private boolean mAddNewFrame = false;
	private String mReloadImagePath = null;
	private boolean mDeleteFrameOnExit = false;

	private final LinkedHashMap<String, Integer> mFrameAudioItems = new LinkedHashMap<>();

	// the ids of inherited (spanned) media items from previous frames
	private String mImageInherited;
	private int mImageLinkingDrawable;
	private String mAudioInherited;
	private int mAudioLinkingIndex; // we only allow one inherited audio item, but need to know which one it is
	private int mAudioLinkingDrawable;
	private String mTextInherited;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.frame_editor);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		// load previous id on screen rotation
		mFrameInternalId = null;
		if (savedInstanceState != null) {
			mFrameInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			mHasEditedMedia = savedInstanceState.getBoolean(getString(R.string.extra_media_edited));
			if (mHasEditedMedia) {
				setBackButtonIcons(FrameEditorActivity.this, R.id.button_finished_editing, 0, true);
			}
		}

		// load the frame elements themselves
		loadFrameElements();
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putString(getString(R.string.extra_internal_id), mFrameInternalId);
		savedInstanceState.putBoolean(getString(R.string.extra_media_edited), mHasEditedMedia);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			if (mAddNewFrame) {
				mAddNewFrame = false;
				addNewFrame();
				supportInvalidateOptionsMenu(); // disable previous/next buttons if needed
				loadFrameElements();
			} else {
				// change the frame that is displayed, if applicable
				changeFrames(loadLastEditedFrame());
			}

			// do image loading here so that we know the layout's size for sizing the image
			if (mReloadImagePath != null) {
				reloadFrameImage(mReloadImagePath);
				mReloadImagePath = null;
			}

			registerForSwipeEvents(); // here to avoid crashing due to double-swiping
		}
	}

	@Override
	public void onBackPressed() {
		// managed to press back before loading the frame - wait
		if (mFrameInternalId == null) {
			return;
		}

		// make sure to always scroll to the correct frame even if we've done next/prev
		saveLastEditedFrame(mFrameInternalId);

		cleanupFrameMedia();

		setResult(Activity.RESULT_OK);
		try {
			// if they've managed to swipe and open another activity between, this will crash as result can't be sent
			super.onBackPressed();
		} catch (RuntimeException ignored) {
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.previous_frame, menu);
		inflater.inflate(R.menu.next_frame, menu);
		inflater.inflate(R.menu.copy_media, menu);
		inflater.inflate(R.menu.paste_media, menu);

		inflater.inflate(R.menu.finished_editing, menu);
		inflater.inflate(R.menu.add_frame, menu);

		inflater.inflate(R.menu.play_narrative, menu); // note: if we couldn't open a temp directory then exporting won't work
		inflater.inflate(R.menu.make_template, menu);
		inflater.inflate(R.menu.delete_narrative, menu); // no space pre action bar
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (mFrameInternalId != null) {
			ContentResolver contentResolver = getContentResolver();
			FrameItem.NavigationMode navigationAllowed = FrameItem.getNavigationAllowed(contentResolver, mFrameInternalId);
			if (navigationAllowed == FrameItem.NavigationMode.PREVIOUS || navigationAllowed == FrameItem.NavigationMode.NONE) {
				MenuItem menuItem = menu.findItem(R.id.menu_next_frame);
				Drawable drawable = menuItem.getIcon();
				if (drawable != null) {
					drawable.mutate(); // only affect this instance of the drawable
					drawable.setColorFilter(getResources().getColor(R.color.next_frame_disabled), PorterDuff.Mode.SRC_ATOP);
				}
				menuItem.setEnabled(false);
			}
			if (navigationAllowed == FrameItem.NavigationMode.NEXT || navigationAllowed == FrameItem.NavigationMode.NONE) {
				MenuItem menuItem = menu.findItem(R.id.menu_previous_frame);
				Drawable drawable = menuItem.getIcon();
				if (drawable != null) {
					drawable.mutate(); // only affect this instance of the drawable
					drawable.setColorFilter(getResources().getColor(R.color.next_frame_disabled), PorterDuff.Mode.SRC_ATOP);
				}
				menuItem.setEnabled(false);
			}

			// check whether media exists to copy/paste and set button visibility accordingly
			menu.findItem(R.id.menu_copy_media)
					.setVisible(MediaManager.countMediaByParentId(contentResolver, mFrameInternalId, true) > 0);
			SharedPreferences copyFrameSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
			String copiedFrameId = copyFrameSettings.getString(getString(R.string.key_copied_frame), null);
			menu.findItem(R.id.menu_paste_media).setVisible(!TextUtils.isEmpty(copiedFrameId));
		}

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		if (itemId == R.id.menu_previous_frame || itemId == R.id.menu_next_frame) {
			switchFrames(mFrameInternalId, itemId, null, true);
			return true;
		} else if (itemId == R.id.menu_copy_media) {
			if (MediaManager.countMediaByParentId(getContentResolver(), mFrameInternalId, false) > 0) {
				SharedPreferences copyFrameSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
				SharedPreferences.Editor prefsEditor = copyFrameSettings.edit();
				prefsEditor.putString(getString(R.string.key_copied_frame), mFrameInternalId);
				prefsEditor.apply();
				UIUtilities.showToast(FrameEditorActivity.this, R.string.copy_media_succeeded);
			}
			return true;

		} else if (itemId == R.id.menu_paste_media) {
			SharedPreferences copyFrameSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
			String copiedFrameId = copyFrameSettings.getString(getString(R.string.key_copied_frame), null);
			if (!TextUtils.isEmpty(copiedFrameId)) {
				runQueuedBackgroundTask(getMediaCopyRunnable(copiedFrameId, mFrameInternalId));
			}
			return true;

		} else if (itemId == R.id.menu_export_narrative) {
			// note: not currently possible (menu item removed for consistency), but left for possible future use
			FrameItem exportFrame = FramesManager.findFrameByInternalId(getContentResolver(), mFrameInternalId);
			if (exportFrame != null) {
				exportContent(exportFrame.getParentId(), false);
			}
			return true;

		} else if (itemId == R.id.menu_play_narrative) {
			if (MediaManager.countMediaByParentId(getContentResolver(), mFrameInternalId) > 0) {
				final Intent framePlayerIntent = new Intent(FrameEditorActivity.this, PlaybackActivity.class);
				framePlayerIntent.putExtra(getString(R.string.extra_internal_id), mFrameInternalId);
				startActivityForResult(framePlayerIntent, MediaPhone.R_id_intent_narrative_player);
			} else {
				UIUtilities.showToast(FrameEditorActivity.this, R.string.play_narrative_add_content);
			}
			return true;

		} else if (itemId == R.id.menu_add_frame) {
			ContentResolver contentResolver = getContentResolver();
			if (MediaManager.countMediaByParentId(contentResolver, mFrameInternalId) > 0) {
				FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, mFrameInternalId);
				if (currentFrame != null) {
					final Intent frameEditorIntent = new Intent(FrameEditorActivity.this, FrameEditorActivity.class);
					frameEditorIntent.putExtra(getString(R.string.extra_parent_id), currentFrame.getParentId());
					frameEditorIntent.putExtra(getString(R.string.extra_insert_after_id), mFrameInternalId);
					startActivity(frameEditorIntent); // no result so that the original exits TODO: does it?
					onBackPressed();
				}
			} else {
				UIUtilities.showToast(FrameEditorActivity.this, R.string.split_frame_add_content);
			}
			return true;

		} else if (itemId == R.id.menu_make_template) {
			ContentResolver resolver = getContentResolver();
			if (MediaManager.countMediaByParentId(resolver, mFrameInternalId) > 0) {
				FrameItem templateFrame = FramesManager.findFrameByInternalId(resolver, mFrameInternalId);
				runQueuedBackgroundTask(getNarrativeTemplateRunnable(templateFrame.getParentId(), true));
			} else {
				UIUtilities.showToast(FrameEditorActivity.this, R.string.make_template_add_content);
			}
			return true;

		} else if (itemId == R.id.menu_delete_narrative) {
			FrameItem deleteNarrativeFrame = FramesManager.findFrameByInternalId(getContentResolver(), mFrameInternalId);
			if (deleteNarrativeFrame != null) {
				deleteNarrativeDialog(deleteNarrativeFrame.getParentId());
			}
			return true;

		} else if (itemId == R.id.menu_back_without_editing || itemId == R.id.menu_finished_editing) {
			onBackPressed();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// no normal preferences apply to this activity
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		// the soft done/back button
		// TODO: remove this to fit with new styling (Toolbar etc)
		int newVisibility = View.VISIBLE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB ||
				!mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
						getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
		}
		findViewById(R.id.button_finished_editing).setVisibility(newVisibility);

		CenteredImageTextButton textButton = findViewById(R.id.button_add_text);
		if (mediaPhoneSettings.getBoolean(getString(R.string.key_custom_font), false)) {
			File customFontFile = new File(MediaPhone.DIRECTORY_THUMBS, getString(R.string.key_custom_font));
			if (customFontFile.exists()) {
				Typeface textTypeface = Typeface.createFromFile(customFontFile);
				textButton.setTypeface(textTypeface);
			}
		} else {
			textButton.setTypeface(Typeface.DEFAULT);
		}
	}

	private void cleanupFrameMedia() {
		// delete frame/narrative if required; don't allow frames with just links and no actual media
		ContentResolver contentResolver = getContentResolver();
		final FrameItem editedFrame = FramesManager.findFrameByInternalId(contentResolver, mFrameInternalId);
		String nextFrameId = null;
		if (editedFrame != null &&
				(MediaManager.countMediaByParentId(contentResolver, mFrameInternalId, false) <= 0 || mDeleteFrameOnExit)) {
			// need the next frame id for scrolling (but before we update it to be deleted)
			ArrayList<String> frameIds = FramesManager.findFrameIdsByParentId(contentResolver, editedFrame.getParentId());

			// save the next frame's id for updating icons/media; also deal with scrolling - if we're the first/last
			// frame scroll to ensure frames don't show off screen TODO: do this in the horizontal list view instead
			// (otherwise, there's no need to scroll - better to leave items in place than scroll to previous or next)
			int numFrames = frameIds.size();
			if (numFrames > 1) {
				int currentFrameIndex = frameIds.indexOf(mFrameInternalId);
				if (currentFrameIndex == 0) {
					nextFrameId = frameIds.get(1);
					saveLastEditedFrame(nextFrameId); // scroll to new first frame after exiting
				} else if (currentFrameIndex > numFrames - 2) {
					saveLastEditedFrame(frameIds.get(numFrames - 2)); // scroll to new last frame after exiting
				} else {
					nextFrameId = frameIds.get(currentFrameIndex + 1); // just store the frame to start updates from
				}
			}

			// delete this frame and any links from it to media items
			editedFrame.setDeleted(true);
			FramesManager.updateFrame(contentResolver, editedFrame);
			MediaManager.deleteMediaLinksByParent(contentResolver, mFrameInternalId);

			// if there's no narrative content remaining after we've been deleted, delete the narrative first for a better
			// interface experience (doing this means we don't have to wait for the frame icon to disappear)
			if (numFrames == 1) {
				NarrativeItem narrativeToDelete = NarrativesManager.findNarrativeByInternalId(contentResolver,
						editedFrame.getParentId());
				narrativeToDelete.setDeleted(true);
				NarrativesManager.updateNarrative(contentResolver, narrativeToDelete);

			} else if (numFrames > 1 && nextFrameId != null) {
				// otherwise we need to delete our media from subsequent frames; always update the next frame's icon
				ArrayList<String> frameComponents = MediaManager.findMediaIdsByParentId(contentResolver, mFrameInternalId,
						false);
				inheritMediaAndDeleteItemLinks(nextFrameId, null, frameComponents);
			}
		}
	}

	private void loadFrameElements() {
		if (mFrameInternalId == null) {
			// editing an existing frame
			final Intent intent = getIntent();
			if (intent != null) {
				mFrameInternalId = intent.getStringExtra(getString(R.string.extra_internal_id));
			}

			// adding a new frame
			if (mFrameInternalId == null) {
				// we add the new frame and load elements in onWindowFocusChanged for a better UI experience
				// (avoids slower initial activity load and brief flash of new frame in narrative browser)
				mAddNewFrame = true;
				return;
			}
		}

		// reset interface and media inheritance
		mReloadImagePath = null;
		mFrameAudioItems.clear();
		CenteredImageTextButton imageButton = findViewById(R.id.button_take_picture_video);
		imageButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_frame_image, 0, 0);
		// (audio buttons are loaded/reset after audio files are loaded)
		CenteredImageTextButton textButton = findViewById(R.id.button_add_text);
		textButton.setText("");
		textButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_frame_text, 0, 0);

		// reset media inheritance
		mImageInherited = null;
		mImageLinkingDrawable = 0;
		mAudioInherited = null;
		mAudioLinkingIndex = -1;
		mAudioLinkingDrawable = 0;
		mTextInherited = null;

		// load existing content into buttons (no need to do any of this on new frames)
		ArrayList<MediaItem> frameComponents = MediaManager.findMediaByParentId(getContentResolver(), mFrameInternalId);
		boolean imageLoaded = false;
		boolean audioLoaded = false;
		boolean textLoaded = false;
		for (MediaItem currentItem : frameComponents) {
			final int currentType = currentItem.getType();
			final boolean spanFrames = currentItem.getSpanFrames();
			final boolean inheritedMedia = !currentItem.getParentId().equals(mFrameInternalId);
			if (!imageLoaded &&
					(currentType == MediaPhoneProvider.TYPE_IMAGE_BACK || currentType == MediaPhoneProvider.TYPE_IMAGE_FRONT ||
							currentType == MediaPhoneProvider.TYPE_VIDEO)) {
				mReloadImagePath = currentItem.getFile().getAbsolutePath();
				if (spanFrames) {
					// this was originally going to be done in onDraw of CenteredImageTextButton, but there's a
					// bizarre bug that causes the canvas to be translated just over 8000 pixels to the left before
					// it's given to our onDraw method - instead we now use a layer drawable when loading
					if (inheritedMedia) {
						mImageInherited = currentItem.getInternalId();
						mImageLinkingDrawable = R.drawable.ic_frame_image_locked;
					} else {
						mImageLinkingDrawable = R.drawable.ic_frame_image_spanning;
					}
				}
				imageLoaded = true;

			} else if (!audioLoaded && currentType == MediaPhoneProvider.TYPE_AUDIO) {
				// we could inherit multiple audio items, but this becomes too complex, so we currently only allow one
				mFrameAudioItems.put(currentItem.getInternalId(), currentItem.getDurationMilliseconds());
				if (spanFrames) {
					mAudioLinkingIndex = mFrameAudioItems.size() - 1;
					if (inheritedMedia) {
						mAudioInherited = currentItem.getInternalId();
						mAudioLinkingDrawable = R.drawable.ic_frame_audio_locked;
					} else {
						mAudioLinkingDrawable = R.drawable.ic_frame_audio_spanning;
					}
				}
				if (mFrameAudioItems.size() >= MediaPhone.MAX_AUDIO_ITEMS) {
					audioLoaded = true;
				}

			} else if (!textLoaded && currentType == MediaPhoneProvider.TYPE_TEXT) {
				String textSnippet = IOUtilities.getFileContentSnippet(currentItem.getFile().getAbsolutePath(),
						getResources().getInteger(R.integer.text_snippet_length));
				textButton.setText(textSnippet);
				if (spanFrames) {
					if (inheritedMedia) {
						mTextInherited = currentItem.getInternalId();
						textButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_frame_text_locked, 0, 0);
					} else {
						textButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_frame_text_spanning, 0, 0);
					}
				}
				textLoaded = true;
			}
		}

		// this is now the last edited frame - save our position
		saveLastEditedFrame(mFrameInternalId);

		// update the interface (image is loaded in onWindowFocusChanged so we know the button's size)
		reloadAudioButtons();
	}

	private void addNewFrame() {
		final Intent intent = getIntent();
		if (intent == null) {
			UIUtilities.showToast(FrameEditorActivity.this, R.string.error_loading_frame_editor);
			mFrameInternalId = "-1"; // so we exit
			onBackPressed();
			return;
		}

		Resources resources = getResources();
		ContentResolver contentResolver = getContentResolver();
		String intentNarrativeId = intent.getStringExtra(getString(R.string.extra_parent_id));
		final boolean insertNewNarrative = intentNarrativeId == null;
		final String narrativeId = insertNewNarrative ? MediaPhoneProvider.getNewInternalId() : intentNarrativeId;

		// default to inserting at the end if no before/after id is given
		final String afterId = intent.getStringExtra(getString(R.string.extra_insert_after_id));
		final String insertAfterId =
				afterId == null ? FramesManager.findLastFrameByParentId(contentResolver, narrativeId) : afterId;

		// don't load the frame's icon yet - it will be loaded (or deleted) when we return
		FrameItem newFrame = new FrameItem(narrativeId, -1);
		FramesManager.addFrame(resources, contentResolver, newFrame, false);
		mFrameInternalId = newFrame.getInternalId();

		int narrativeSequenceId = 0;
		if (insertNewNarrative) {
			// new narrative required
			NarrativeItem newNarrative = new NarrativeItem(narrativeId,
					NarrativesManager.getNextNarrativeExternalId(contentResolver));
			NarrativesManager.addNarrative(contentResolver, newNarrative);

		} else {
			narrativeSequenceId = FramesManager.adjustNarrativeSequenceIds(resources, contentResolver, narrativeId,
					insertAfterId);

			if (insertAfterId != null && !FrameItem.KEY_FRAME_ID_START.equals(insertAfterId)) {
				// get and update any inherited media
				ArrayList<MediaItem> inheritedMedia = MediaManager.findMediaByParentId(contentResolver, insertAfterId);
				for (final MediaItem media : inheritedMedia) {
					if (media.getSpanFrames()) {
						MediaManager.addMediaLink(contentResolver, mFrameInternalId, media.getInternalId());
					}
				}
			}
		}

		newFrame.setNarrativeSequenceId(narrativeSequenceId);
		FramesManager.updateFrame(contentResolver, newFrame);
	}

	private void reloadAudioButtons() {
		CenteredImageTextButton[] audioButtons = {
				findViewById(R.id.button_record_audio_1),
				findViewById(R.id.button_record_audio_2),
				findViewById(R.id.button_record_audio_3)
		};
		audioButtons[2].setText("");

		// reset locked (inherited) media
		for (CenteredImageTextButton button : audioButtons) {
			button.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_frame_audio, 0, 0);
		}

		// load the audio content
		int audioIndex = 0;
		for (Entry<String, Integer> audioMedia : mFrameAudioItems.entrySet()) {
			audioButtons[audioIndex].setText(StringUtilities.millisecondsToTimeString(audioMedia.getValue(), false));
			if (audioIndex == mAudioLinkingIndex) {
				audioButtons[audioIndex].setCompoundDrawablesWithIntrinsicBounds(0, mAudioLinkingDrawable, 0, 0);
			}
			audioIndex += 1;
		}

		// hide unnecessary buttons
		if (audioIndex < 2) {
			audioButtons[2].setVisibility(View.GONE);
			audioButtons[1].setText("");
			if (audioIndex < 1) {
				audioButtons[1].setVisibility(View.GONE);
				audioButtons[0].setText("");
			} else {
				audioButtons[1].setVisibility(View.VISIBLE);
			}
		} else {
			audioButtons[1].setVisibility(View.VISIBLE);
			audioButtons[2].setVisibility(View.VISIBLE);
		}
	}

	private void reloadFrameImage(String imagePath) {
		CenteredImageTextButton cameraButton = findViewById(R.id.button_take_picture_video);
		Resources resources = getResources();
		TypedValue resourceValue = new TypedValue();
		resources.getValue(R.dimen.image_button_fill_percentage, resourceValue, true);
		int pictureSize = (int) (
				(resources.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? cameraButton.getWidth() :
						cameraButton.getHeight()) * resourceValue.getFloat());
		BitmapDrawable cachedIcon = new BitmapDrawable(resources,
				BitmapUtilities.loadAndCreateScaledBitmap(imagePath, pictureSize, pictureSize, BitmapUtilities.ScalingLogic.CROP,
						true));
		if (mImageLinkingDrawable != 0) {
			Drawable[] layers = new Drawable[2];
			layers[0] = cachedIcon;
			layers[1] = ContextCompat.getDrawable(FrameEditorActivity.this, mImageLinkingDrawable);
			LayerDrawable layerDrawable = new LayerDrawable(layers);
			layerDrawable.setLayerInset(1, pictureSize - layers[1].getIntrinsicHeight(),
					pictureSize - layers[1].getIntrinsicWidth(), 0, 0);
			cameraButton.setCompoundDrawablesWithIntrinsicBounds(null, layerDrawable, null, null);
		} else {
			cameraButton.setCompoundDrawablesWithIntrinsicBounds(null, cachedIcon, null, null);
		}
	}

	private void changeFrames(String newFrameId) {
		if (newFrameId != null && !newFrameId.equals(mFrameInternalId)) {
			mFrameInternalId = newFrameId;
			String extraKey = getString(R.string.extra_internal_id);
			final Intent launchingIntent = getIntent();
			if (launchingIntent != null) {
				launchingIntent.removeExtra(extraKey);
				launchingIntent.putExtra(extraKey, mFrameInternalId);
				setIntent(launchingIntent);
			}

			// assume they've edited (maybe not, but we can't get the result, so don't know) - refreshes action bar too
			mHasEditedMedia = true;
			setBackButtonIcons(FrameEditorActivity.this, R.id.button_finished_editing, 0, true);

			// load the new frame elements
			loadFrameElements();
		}
	}

	private void setAndUpdateEditedMedia() {
		loadFrameElements();
		mHasEditedMedia = true;
		setBackButtonIcons(FrameEditorActivity.this, R.id.button_finished_editing, 0, true);
	}

	@Override
	protected void onBackgroundTaskCompleted(int taskId) {
		if (taskId == R.id.copy_paste_media_task_empty) {
			UIUtilities.showToast(FrameEditorActivity.this, R.string.paste_media_empty, true);
		} else if (taskId == R.id.copy_paste_media_task_failed) {
			UIUtilities.showToast(FrameEditorActivity.this, R.string.paste_media_failed, true);
		} else if (taskId == R.id.copy_paste_media_task_partial) {
			UIUtilities.showToast(FrameEditorActivity.this, R.string.paste_media_partial, true);
			setAndUpdateEditedMedia();
		} else if (taskId == R.id.copy_paste_media_task_complete) {
			setAndUpdateEditedMedia();
		}
	}

	@Override
	protected boolean swipeNext() {
		return switchFrames(mFrameInternalId, R.id.menu_next_frame, null, false);
	}

	@Override
	protected boolean swipePrevious() {
		return switchFrames(mFrameInternalId, R.id.menu_previous_frame, null, false);
	}

	private int getAudioIndex(int buttonId) {
		if (buttonId == R.id.button_record_audio_1) {
			return 0;
		} else if (buttonId == R.id.button_record_audio_2) {
			return 1;
		} else if (buttonId == R.id.button_record_audio_3) {
			return 2;
		}
		return -1;
	}

	private void editImage(String parentId) {
		// TODO: note that checking permissions here makes little sense on devices with no camera, but it greatly simplifies
		//  permission management (and - realistically - devices without cameras are not supported particularly well anyway)
		if (ContextCompat.checkSelfPermission(FrameEditorActivity.this, Manifest.permission.CAMERA) ==
				PackageManager.PERMISSION_GRANTED) {
			final Intent takePictureIntent = new Intent(FrameEditorActivity.this, CameraActivity.class);
			takePictureIntent.putExtra(getString(R.string.extra_parent_id), parentId);
			startActivityForResult(takePictureIntent, MediaPhone.R_id_intent_picture_editor);
		} else {
			mPermissionsItemClicked = parentId;
			if (ActivityCompat.shouldShowRequestPermissionRationale(FrameEditorActivity.this, Manifest.permission.CAMERA)) {
				UIUtilities.showFormattedToast(FrameEditorActivity.this, R.string.permission_camera_rationale,
						getString(R.string.app_name));
			}
			ActivityCompat.requestPermissions(FrameEditorActivity.this, new String[]{ Manifest.permission.CAMERA },
					PERMISSION_CAMERA);
		}
	}

	private void editAudio(String parentId, int selectedAudioIndex, boolean preventSpanning) {
		// TODO: note that checking permissions here makes little sense on devices with no microphone, but it greatly simplifies
		//  permission management (and - realistically - devices without mics are not supported particularly well anyway)
		if (ContextCompat.checkSelfPermission(FrameEditorActivity.this, Manifest.permission.RECORD_AUDIO) ==
				PackageManager.PERMISSION_GRANTED) {
			final Intent recordAudioIntent = new Intent(FrameEditorActivity.this, AudioActivity.class);
			recordAudioIntent.putExtra(getString(R.string.extra_parent_id), parentId);

			// index of -1 means don't edit existing
			if (selectedAudioIndex >= 0) {
				int currentIndex = 0;
				for (String audioMediaId : mFrameAudioItems.keySet()) {
					if (currentIndex == selectedAudioIndex) {
						recordAudioIntent.putExtra(getString(R.string.extra_internal_id), audioMediaId);
						break;
					}
					currentIndex += 1;
				}
			}

			// unless we're editing (or replacing) inherited audio, or editing a single item we need to stop frame spanning
			if (preventSpanning) {
				recordAudioIntent.putExtra(getString(R.string.extra_prevent_frame_spanning), true);
			}

			startActivityForResult(recordAudioIntent, MediaPhone.R_id_intent_audio_editor);
		} else {
			mPermissionsItemClicked = parentId;
			mPermissionsSelectedAudioIndex = selectedAudioIndex;
			mPermissionsPreventSpanning = preventSpanning;
			if (ActivityCompat.shouldShowRequestPermissionRationale(FrameEditorActivity.this,
					Manifest.permission.RECORD_AUDIO)) {
				UIUtilities.showFormattedToast(FrameEditorActivity.this, R.string.permission_audio_rationale,
						getString(R.string.app_name));
			}
			ActivityCompat.requestPermissions(FrameEditorActivity.this, new String[]{ Manifest.permission.RECORD_AUDIO },
					PERMISSION_AUDIO);
		}
	}

	private void editText(String parentId) {
		final Intent addTextIntent = new Intent(FrameEditorActivity.this, TextActivity.class);
		addTextIntent.putExtra(getString(R.string.extra_parent_id), parentId);
		startActivityForResult(addTextIntent, MediaPhone.R_id_intent_text_editor);
	}

	public void handleButtonClicks(View currentButton) {
		if (!verifyButtonClick(currentButton)) {
			return;
		}

		final int buttonId = currentButton.getId();
		if (buttonId == R.id.button_finished_editing) {
			onBackPressed();

		} else if (buttonId == R.id.button_take_picture_video) {
			if (mImageInherited != null) {
				AlertDialog.Builder builder = new AlertDialog.Builder(FrameEditorActivity.this);
				builder.setTitle(R.string.span_media_edit_image_title);
				builder.setMessage(R.string.span_media_edit_image);
				builder.setNegativeButton(R.string.span_media_edit_original, (dialog, which) -> {
					// find the parent frame of the media item we want to edit, switch to it, then edit
					MediaItem inheritedImage = MediaManager.findMediaByInternalId(getContentResolver(), mImageInherited);
					if (inheritedImage != null) {
						cleanupFrameMedia(); // this frame may now be obsolete if no other media was added
						final String newFrameId = inheritedImage.getParentId();
						saveLastEditedFrame(newFrameId);
						editImage(newFrameId);
					}
				});
				builder.setPositiveButton(R.string.span_media_add_new, (dialog, whichButton) -> {
					endLinkedMediaItem(mImageInherited, mFrameInternalId); // remove the existing media link
					editImage(mFrameInternalId);
				});
				AlertDialog alert = builder.create();
				alert.show();
			} else {
				editImage(mFrameInternalId);
			}

		} else if (buttonId == R.id.button_record_audio_1 || buttonId == R.id.button_record_audio_2 ||
				buttonId == R.id.button_record_audio_3) {
			final int selectedAudioIndex = getAudioIndex(buttonId);
			if (mAudioInherited != null && selectedAudioIndex == mAudioLinkingIndex) {
				AlertDialog.Builder builder = new AlertDialog.Builder(FrameEditorActivity.this);
				builder.setTitle(R.string.span_media_edit_audio_title);
				builder.setMessage(R.string.span_media_edit_audio);
				builder.setNegativeButton(R.string.span_media_edit_original, (dialog, which) -> {
					// find the parent frame of the media item we want to edit, switch to it, then edit
					MediaItem inheritedAudio = MediaManager.findMediaByInternalId(getContentResolver(), mAudioInherited);
					if (inheritedAudio != null) {
						cleanupFrameMedia(); // this frame may now be obsolete if no other media was added
						final String newFrameId = inheritedAudio.getParentId();
						saveLastEditedFrame(newFrameId);
						editAudio(mFrameInternalId, selectedAudioIndex, false);
					}
				});
				builder.setPositiveButton(R.string.span_media_add_new, (dialog, whichButton) -> {
					// remove the existing media links and edit a new audio item
					endLinkedMediaItem(mAudioInherited, mFrameInternalId);
					editAudio(mFrameInternalId, -1, false); // spanning is now allowed
				});
				AlertDialog alert = builder.create();
				alert.show();
			} else {
				boolean preventSpanning = mAudioLinkingIndex >= 0 && selectedAudioIndex != mAudioLinkingIndex;
				editAudio(mFrameInternalId, selectedAudioIndex, preventSpanning); // no spanning if not already
			}

		} else if (buttonId == R.id.button_add_text) {
			if (mTextInherited != null) {
				AlertDialog.Builder builder = new AlertDialog.Builder(FrameEditorActivity.this);
				builder.setTitle(R.string.span_media_edit_text_title);
				builder.setMessage(R.string.span_media_edit_text);
				builder.setNegativeButton(R.string.span_media_edit_original, (dialog, which) -> {
					// find the parent frame of the media item we want to edit, switch to it, then edit
					MediaItem inheritedText = MediaManager.findMediaByInternalId(getContentResolver(), mTextInherited);
					if (inheritedText != null) {
						cleanupFrameMedia(); // this frame may now be obsolete if no other media was added
						final String newFrameId = inheritedText.getParentId();
						saveLastEditedFrame(newFrameId);
						editText(newFrameId);
					}
				});
				builder.setPositiveButton(R.string.span_media_add_new, (dialog, whichButton) -> {
					// remove the existing media links and edit a new text item
					endLinkedMediaItem(mTextInherited, mFrameInternalId);
					editText(mFrameInternalId);
				});
				AlertDialog alert = builder.create();
				alert.show();
			} else {
				editText(mFrameInternalId);
			}

		} else if (buttonId == R.id.button_delete_frame) {
			AlertDialog.Builder builder = new AlertDialog.Builder(FrameEditorActivity.this);
			builder.setTitle(R.string.delete_frame_confirmation);
			builder.setMessage(R.string.delete_frame_hint);
			builder.setNegativeButton(R.string.button_cancel, null);
			builder.setPositiveButton(R.string.button_delete, (dialog, whichButton) -> {
				mDeleteFrameOnExit = true;
				onBackPressed();
			});
			AlertDialog alert = builder.create();
			alert.show();
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case MediaPhone.R_id_intent_picture_editor:
			case MediaPhone.R_id_intent_audio_editor:
			case MediaPhone.R_id_intent_text_editor:
			case MediaPhone.R_id_intent_narrative_player:
				// if we get RESULT_OK then a media component has been edited - reload our content
				if (resultCode == Activity.RESULT_OK) {
					// only load our existing frame here; changes are handled in onWindowFocusChanged
					String newInternalId = loadLastEditedFrame();
					if (newInternalId != null && newInternalId.equals(mFrameInternalId)) {
						setAndUpdateEditedMedia();
					}

				} else if (resultCode == R.id.result_narrative_deleted_exit) {
					// no point reloading if we're going to exit
					onBackPressed();
				}
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		switch (requestCode) {
			case PERMISSION_CAMERA:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					if (mPermissionsItemClicked != null) {
						editImage(mPermissionsItemClicked);
					}
				} else {
					UIUtilities.showFormattedToast(FrameEditorActivity.this, R.string.permission_camera_error,
							getString(R.string.app_name));
				}
				break;

			case PERMISSION_AUDIO:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					if (mPermissionsItemClicked != null) {
						editAudio(mPermissionsItemClicked, mPermissionsSelectedAudioIndex, mPermissionsPreventSpanning);
					}
				} else {
					UIUtilities.showFormattedToast(FrameEditorActivity.this, R.string.permission_audio_error,
							getString(R.string.app_name));
				}
				break;

			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}
	}
}
