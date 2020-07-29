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

package ac.robinson.mediaphone.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.provider.BaseColumns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediautilities.FrameMediaContainer;
import ac.robinson.util.IOUtilities;
import androidx.annotation.NonNull;

public class NarrativeItem implements BaseColumns {

	public static final Uri NARRATIVE_CONTENT_URI = Uri.parse(
			MediaPhoneProvider.URI_PREFIX + MediaPhoneProvider.URI_AUTHORITY + MediaPhoneProvider.URI_SEPARATOR +
					MediaPhoneProvider.NARRATIVES_LOCATION);

	public static final Uri TEMPLATE_CONTENT_URI = Uri.parse(
			MediaPhoneProvider.URI_PREFIX + MediaPhoneProvider.URI_AUTHORITY + MediaPhoneProvider.URI_SEPARATOR +
					MediaPhoneProvider.TEMPLATES_LOCATION);

	public static final String[] PROJECTION_ALL = new String[]{
			NarrativeItem._ID,
			NarrativeItem.INTERNAL_ID,
			NarrativeItem.DATE_CREATED,
			NarrativeItem.SEQUENCE_ID,
			NarrativeItem.DELETED
	};

	public static final String[] PROJECTION_INTERNAL_ID = new String[]{ NarrativeItem.INTERNAL_ID };

	public static final String MAX_ID = "max_id";
	public static final String[] PROJECTION_NEXT_EXTERNAL_ID = new String[]{
			"MAX(" + NarrativeItem.SEQUENCE_ID + ") as " + MAX_ID
	};

	// for keeping track of the helper narratives (so we don't add multiple copies later)
	public static final String HELPER_NARRATIVE_ID = "936df7b0-72b9-11e2-bcfd-0800200c9a66"; // *DO NOT CHANGE*
	public static final String TIMING_EDITOR_NARRATIVE_ID = "a56c33a4-8ada-41b0-ae6d-592cd5606f96"; // *DO NOT CHANGE*

	public static final String INTERNAL_ID = "internal_id";
	public static final String DATE_CREATED = "date_created";
	public static final String SEQUENCE_ID = "sequence_id";
	public static final String DELETED = "deleted";

	public static final String SELECTION_NOT_DELETED = DELETED + "=0";

	public static final String DEFAULT_SORT_ORDER = SEQUENCE_ID + " DESC"; // seq id, not date, in case date is wrong

	private String mInternalId;
	private long mCreationDate;
	private int mSequenceId;
	private int mDeleted;

	public NarrativeItem(String internalId, int externalId) {
		mInternalId = internalId;
		mCreationDate = System.currentTimeMillis();
		mSequenceId = externalId;
		mDeleted = 0;
	}

	public NarrativeItem(int externalId) {
		this(MediaPhoneProvider.getNewInternalId(), externalId);
	}

	public NarrativeItem() {
		this(0);
	}

	public String getInternalId() {
		return mInternalId;
	}

	public long getCreationDate() {
		return mCreationDate;
	}

	public int getSequenceId() {
		return mSequenceId;
	}

	public void setSequenceId(int sequenceId) {
		mSequenceId = sequenceId;
	}

	public boolean getDeleted() {
		return mDeleted != 0;
	}

	public void setDeleted(boolean deleted) {
		mDeleted = deleted ? 1 : 0;
	}

	/**
	 * Parses this narrative's content, returning a compacted version of each frame that is optimised for export, and includes
	 * only the elements required for that purpose, such as the media path and the duration it should be displayed for. Unlike
	 * {@link #getPlaybackContent(ContentResolver, String, PlaybackNarrativeDescriptor)} there is no attempt to specify where
	 * individual media items begin or end, and this is done only on a frame level. Except for audio items (which are spread
	 * evenly over all the frames they apply to, taking into account user-set media item durations), items that span more than
	 * one frame are simply repeated on the subsequent frames.
	 */
	public ArrayList<FrameMediaContainer> getContentList(ContentResolver contentResolver) {

		ArrayList<FrameMediaContainer> exportedContent = new ArrayList<>();
		HashMap<String, Point> spanningAudioFrames = new HashMap<>(); // so we can adjust durations

		ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver, mInternalId);
		for (FrameItem frame : narrativeFrames) {
			final String frameId = frame.getInternalId();
			ArrayList<MediaItem> frameComponents = MediaManager.findMediaByParentId(contentResolver, frameId);

			final FrameMediaContainer currentContainer = new FrameMediaContainer(frameId, frame.getNarrativeSequenceId());

			currentContainer.mParentId = frame.getParentId();

			String spanningAudioPath = null;
			for (MediaItem media : frameComponents) {
				final String mediaPath = media.getFile().getAbsolutePath();
				final int mediaType = media.getType();
				final int mediaDuration = media.getDurationMilliseconds();

				switch (mediaType) {
					case MediaPhoneProvider.TYPE_IMAGE_FRONT:
						currentContainer.mImageIsFrontCamera = true; // NOTE: intentionally fall through
					case MediaPhoneProvider.TYPE_IMAGE_BACK:
					case MediaPhoneProvider.TYPE_VIDEO:
						currentContainer.mImagePath = mediaPath;
						if (mediaDuration > 0) {
							// retain user-set durations (note: *not* per-media; always displayed for max length of whole frame)
							currentContainer.updateFrameMaxDuration(mediaDuration);
						}
						break;

					case MediaPhoneProvider.TYPE_TEXT:
						currentContainer.mTextContent = IOUtilities.getFileContents(mediaPath);
						if (mediaDuration > 0) {
							// retain user-set durations (note: *not* per-media; always displayed for max length of whole frame)
							currentContainer.updateFrameMaxDuration(mediaDuration);
						}
						break;

					case MediaPhoneProvider.TYPE_AUDIO:
						int insertedIndex = currentContainer.addAudioFile(mediaPath, mediaDuration);
						if (insertedIndex >= 0) {
							if (media.getSpanFrames()) {
								if (frameId.equals(media.getParentId())) {
									currentContainer.mSpanningAudioRoot = true; // this is the actual parent frame
								}
								spanningAudioPath = mediaPath;
								currentContainer.mSpanningAudioIndex = insertedIndex; // only one spanning item per frame
							} else {
								// for non-spanning items we just use the normal audio duration
								currentContainer.updateFrameMaxDuration(mediaDuration);
							}
						}
						break;

					default:
						break;
				}
			}

			// frame spanning images and text can just be repeated; audio needs to be split between frames
			// here we count the number of frames to split between so we can equalise later
			if (spanningAudioPath != null) {
				Point spanningAudioAttribute = spanningAudioFrames.get(spanningAudioPath);
				if (spanningAudioAttribute == null) {
					// x = number of frames we need to divide over; y = remaining time available for this audio item to span
					spanningAudioAttribute = new Point(0,
							currentContainer.mAudioDurations.get(currentContainer.mSpanningAudioIndex));
				}

				if (currentContainer.mFrameMaxDuration > 0) {
					// this frame has taken up some of the spanning audio's allowance
					spanningAudioAttribute.y -= currentContainer.mFrameMaxDuration;
				} else {
					// or, it needs to be allocated a share of the remainder afterwards
					spanningAudioAttribute.x += 1;
				}

				spanningAudioFrames.put(spanningAudioPath, spanningAudioAttribute);
			}

			exportedContent.add(currentContainer);
		}

		// now check all long-running audio tracks to split the audio's duration between all spanned frames
		for (FrameMediaContainer container : exportedContent) {
			boolean durationSet = container.mFrameMaxDuration > 0;

			// if no duration has been applied but a spanning audio item applies, divide its length over the frames it spans,
			// taking into account allocations already given to other frames (note: could end up with zero length here, but that
			// is intentional if the user sets timings in that way)
			if (!durationSet && container.mSpanningAudioIndex >= 0) {
				final String spanningAudioPath = container.mAudioPaths.get(container.mSpanningAudioIndex);
				final Point audioItemAttribute = spanningAudioFrames.get(spanningAudioPath);
				if (audioItemAttribute != null && audioItemAttribute.x > 0) { // should always be > 0 but just in case...
					int availableDuration = (int) Math.max(0, Math.ceil(audioItemAttribute.y / (float) audioItemAttribute.x));
					container.updateFrameMaxDuration(availableDuration);
					durationSet = true; // even if there was no available time and we set to 0, we've still finished here

					// update the remaining time (done this way so we don't miscount due to rounding errors)
					audioItemAttribute.x -= 1;
					audioItemAttribute.y -= availableDuration;
					spanningAudioFrames.put(spanningAudioPath, audioItemAttribute);
				}
			}

			// don't allow non-spanned frames to be shorter than the minimum duration (unless user-requested)
			if (!durationSet && container.mFrameMaxDuration <= 0) {
				container.updateFrameMaxDuration(MediaPhone.PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION);
			}
		}
		return exportedContent;
	}

	/**
	 * Parse this narrative's content for in-app playback, returning a list of media items and the times at which they should
	 * start and end. Unlike {@link #getContentList(ContentResolver)}, this method properly handles spanning media as individual
	 * items, rather than duplicating to simulate this. As a result, the returned media list has no frame structure, and is just
	 * a set of media at specific times and durations.
	 */
	public ArrayList<PlaybackMediaHolder> getPlaybackContent(ContentResolver contentResolver, String startingFrame,
															 PlaybackNarrativeDescriptor narrativeDescriptor) {

		final ArrayList<PlaybackMediaHolder> narrativeContent = new ArrayList<>();
		final LinkedHashMap<Integer, String> mTimeToFrameMap = narrativeDescriptor.mTimeToFrameMap; // to track frames (for skip)

		int narrativeTime = 0;
		int narrativeDuration = 0;
		boolean frameFound = startingFrame == null;

		PlaybackMediaHolder previousFrameImage = null;
		PlaybackMediaHolder previousFrameText = null;
		PlaybackMediaHolder lastAudioItem = null; // the last audio item in the narrative

		// use the exported content list as a source for frame durations
		// this is inefficient (we retrieve the frames and their media from the database twice), but it means that there is no
		// risk of calculating frame durations incorrectly (or differently) here as we rely on one method only
		ArrayList<FrameMediaContainer> timedFrameMedia = getContentList(contentResolver);

		ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver, mInternalId);
		int currentFrame = 0;
		int lastFrame = narrativeFrames.size() - 1;
		for (FrameItem frame : narrativeFrames) {
			final String frameId = frame.getInternalId();
			mTimeToFrameMap.put(narrativeTime, frameId); // store the frame's start time
			ArrayList<MediaItem> frameComponents = MediaManager.findMediaByParentId(contentResolver, frameId);

			FrameMediaContainer currentFrameTimedMedia = timedFrameMedia.get(currentFrame);
			final int frameDuration = currentFrameTimedMedia.mFrameMaxDuration; // we rely totally on the duration calculated
			// above
			final int mediaEndTime = narrativeTime + frameDuration;
			final boolean lastFrameAdjustments = currentFrame == lastFrame && narrativeDescriptor.mNarrativeImageAdjustment > 0;

			// save the start time of the requested starting frame
			if (!frameFound) {
				if (frameId.equals(startingFrame)) {
					narrativeDescriptor.mNarrativeStartTime = narrativeTime;
					frameFound = true;
				}
			}

			// we deal with each type of media separately as there are links / dependencies between them when displaying/playing
			// - first audio as it is far more complex to than images/text (because it can span multiple frames, but we can't
			// adjust its length); as noted above, we rely wholly on getContentList durations for the calculated media durations
			for (MediaItem media : frameComponents) {
				if (media.getType() == MediaPhoneProvider.TYPE_AUDIO) {
					final String mediaId = media.getInternalId();
					final String mediaPath = media.getFile().getAbsolutePath();
					final int audioEndTime = narrativeTime + media.getDurationMilliseconds();
					PlaybackMediaHolder audioItem = null;

					// spanning audio needs to be evenly distributed over frames, but *also* take into account user-set durations
					if (media.getSpanFrames()) {
						// this is the actual parent frame of a long-running item - count how many items link here
						// (+1 to count this frame as well), then add to the playback list
						// note: inherited items don't need to be edited; they are already in the list with the correct duration
						if (frameId.equals(media.getParentId())) {
							ArrayList<String> linkedMedia = MediaManager.findLinkedParentIdsByMediaId(contentResolver, mediaId);
							audioItem = new PlaybackMediaHolder(frameId, mediaId, mediaPath, MediaPhoneProvider.TYPE_AUDIO,
									narrativeTime, audioEndTime, 0, 0, linkedMedia);
							narrativeContent.add(audioItem);
						}
					} else {
						// a normal non-spanning audio item - just add to playback
						audioItem = new PlaybackMediaHolder(frameId, mediaId, mediaPath, MediaPhoneProvider.TYPE_AUDIO,
								narrativeTime, audioEndTime, 0, 0);
						narrativeContent.add(audioItem);
					}

					// store the last audio item for displaying it at the end of playback when no other items are present
					if (audioItem != null) {
						if (lastAudioItem == null) {
							lastAudioItem = audioItem;
						} else if (audioEndTime > lastAudioItem.getEndTime(false)) {
							lastAudioItem = audioItem;
						}
					}
				}
			}

			// now deal with images (only one item of this type per frame)
			// note that we adjust image timings so that their crossfade ends when the next image starts - this requires
			// shifting images earlier in the playback queue so that they can be set up in time
			// if this is the last item we add 1ms to the end of the item so it stays visible after playback completes
			// TODO: spread the crossfade over the beginning and the end of the image so that timings are more accurate?
			PlaybackMediaHolder frameImage = null;
			int imageEndAdjustment = lastFrameAdjustments ? -1 : narrativeDescriptor.mNarrativeImageAdjustment;
			for (MediaItem media : frameComponents) {
				final int mediaType = media.getType();
				if (mediaType == MediaPhoneProvider.TYPE_IMAGE_FRONT || mediaType == MediaPhoneProvider.TYPE_IMAGE_BACK ||
						mediaType == MediaPhoneProvider.TYPE_VIDEO) {
					final String mediaId = media.getInternalId();

					// check whether this is a duplicate of the previous item - if so, just extend that item's duration
					if (media.getSpanFrames() && previousFrameImage != null && mediaId.equals(previousFrameImage.mMediaItemId)) {
						int replacementPosition = narrativeContent.indexOf(previousFrameImage);
						frameImage = new PlaybackMediaHolder(previousFrameImage, frameId, mediaEndTime, imageEndAdjustment);
						narrativeContent.set(replacementPosition, frameImage);
						previousFrameImage = frameImage; // we've replaced the old item (need for comparison later in text item)

					} else {
						frameImage = new PlaybackMediaHolder(frameId, mediaId, media.getFile().getAbsolutePath(), mediaType,
								narrativeTime, mediaEndTime, narrativeDescriptor.mNarrativeImageAdjustment, imageEndAdjustment);
						narrativeContent.add(frameImage);
					}

					// if we're coming to an image-only frame from a text-only one, tweak its start time to align (avoid
					// unsightly flash of text at the start of the image display)
					if (narrativeDescriptor.mNarrativeImageAdjustment > 0 && previousFrameImage == null &&
							previousFrameText != null) {
						int replacementPosition = narrativeContent.indexOf(frameImage);
						frameImage = new PlaybackMediaHolder(frameImage, null, frameImage.getEndTime(false), 0,
								imageEndAdjustment);
						narrativeContent.set(replacementPosition, frameImage);
					}
					break;
				}
			}

			// finally, add the text (only one item of this type per frame)
			// if this is the last item we add 1ms to the end of the item so it stays visible after playback completes
			PlaybackMediaHolder frameText = null;
			int textEndAdjustment = lastFrameAdjustments ? -1 : 0;
			for (MediaItem media : frameComponents) {
				if (media.getType() == MediaPhoneProvider.TYPE_TEXT) {
					final String mediaId = media.getInternalId();

					// check whether this is a duplicate of the previous item - if so, just extend that item's duration
					if (media.getSpanFrames() && previousFrameText != null && mediaId.equals(previousFrameText.mMediaItemId)) {
						int replacementPosition = narrativeContent.indexOf(previousFrameText);
						frameText = new PlaybackMediaHolder(previousFrameText, frameId, mediaEndTime, textEndAdjustment);
						narrativeContent.set(replacementPosition, frameText);

					} else {
						frameText = new PlaybackMediaHolder(frameId, mediaId, media.getFile().getAbsolutePath(),
								MediaPhoneProvider.TYPE_TEXT, narrativeTime, mediaEndTime, 0, textEndAdjustment);
						narrativeContent.add(frameText);
					}

					// if we're coming to a text-only frame from an image, we tweak its end time to align with the text
					if (narrativeDescriptor.mNarrativeImageAdjustment > 0 && frameImage == null && previousFrameImage != null) {
						// no need to deal with lastFrameAdjustments as the image we are editing will never be the last item
						int replacementPosition = narrativeContent.indexOf(previousFrameImage);
						previousFrameImage = new PlaybackMediaHolder(previousFrameImage, null,
								previousFrameImage.getEndTime(false), narrativeDescriptor.mNarrativeImageAdjustment, 0);
						narrativeContent.set(replacementPosition, previousFrameImage);
					}
					break;
				}
			}

			// if we've got just audio on a frame and we're the last frame, we need to adjust the last audio item to add
			// an extra 1ms so that it (i.e., the audio icon) remains in view when playback stops
			if (lastFrameAdjustments && frameImage == null && frameText == null && lastAudioItem != null) {
				int replacementPosition = narrativeContent.indexOf(lastAudioItem);
				lastAudioItem = new PlaybackMediaHolder(lastAudioItem, null, lastAudioItem.getEndTime(false), 0, -1);
				narrativeContent.set(replacementPosition, lastAudioItem);
			}

			narrativeTime += frameDuration;
			narrativeDuration = Math.max(narrativeDuration, mediaEndTime);
			currentFrame += 1;
			previousFrameImage = frameImage;
			previousFrameText = frameText;
		}

		narrativeDescriptor.mNarrativeDuration = narrativeDuration;
		return narrativeContent;
	}

	public ContentValues getContentValues() {
		final ContentValues values = new ContentValues();
		values.put(INTERNAL_ID, mInternalId);
		values.put(DATE_CREATED, mCreationDate);
		values.put(SEQUENCE_ID, mSequenceId);
		values.put(DELETED, mDeleted);
		return values;
	}

	public static NarrativeItem fromCursor(Cursor c) {
		final NarrativeItem narrative = new NarrativeItem();
		narrative.mInternalId = c.getString(c.getColumnIndexOrThrow(INTERNAL_ID));
		narrative.mCreationDate = c.getLong(c.getColumnIndexOrThrow(DATE_CREATED));
		narrative.mSequenceId = c.getInt(c.getColumnIndexOrThrow(SEQUENCE_ID));
		narrative.mDeleted = c.getInt(c.getColumnIndexOrThrow(DELETED));
		return narrative;
	}

	@NonNull
	@Override
	public String toString() {
		return this.getClass().getName() + "[" + mInternalId + "," + mCreationDate + "," + mSequenceId + "," + mDeleted + "]";
	}
}
