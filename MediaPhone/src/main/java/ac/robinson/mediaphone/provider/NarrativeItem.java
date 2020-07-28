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
	 * Parses this narrative's content, returning a compacted version of each frame. Used for exporting a narrative's
	 * content. Items that span more than one frame are simply repeated; except for audio, which is spread evenly over
	 * all the frames it applies to.
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
					// x = number of frames we need to divide over; y = amount of time already "used up" by other fixed-duration
					// frames that this item spans
					spanningAudioAttribute = new Point(0, 0);
				}

				if (currentContainer.mFrameMaxDuration > 0) {
					spanningAudioAttribute.y += currentContainer.mFrameMaxDuration;
				} else {
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
				for (int i = 0, n = container.mAudioPaths.size(); i < n; i++) { // should only be one item, but just in case...
					final Point audioItemAttribute = spanningAudioFrames.get(container.mAudioPaths.get(i));
					if (audioItemAttribute != null && audioItemAttribute.x > 0) { // should always be > 0 but just in case...
						int availableDuration = container.mAudioDurations.get(i) - audioItemAttribute.y;
						int thisFrameDuration = (int) Math.max(0, Math.ceil(availableDuration / (float) audioItemAttribute.x));
						container.updateFrameMaxDuration(thisFrameDuration);
						durationSet = true; // even if there was no available time and we set to 0, we've still finished here
					}
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
	 * Parse this narrative's content, returning a list of timed media items. The given PlaybackNarrativeDescriptor
	 * contains options for parsing, and is returned with key initialisation values (start time, for example).
	 * <p>
	 * Note: start time could easily be calculated from the narrative descriptor's mTimeToFrameMap, but as we're looping
	 * through every frame anyway it's more efficient to calculate it here.
	 */
	public ArrayList<PlaybackMediaHolder> getPlaybackContent(ContentResolver contentResolver, String startingFrame,
															 PlaybackNarrativeDescriptor narrativeDescriptor) {

		final ArrayList<PlaybackMediaHolder> narrativeContent = new ArrayList<>();
		final HashMap<String, Integer> longRunningAudioCounts = new HashMap<>(); // to adjust durations
		final LinkedHashMap<Integer, String> mTimeToFrameMap = narrativeDescriptor.mTimeToFrameMap; // to track frames

		int narrativeTime = 0;
		int narrativeDuration = 0;
		boolean frameFound = startingFrame == null;
		boolean imageAdjustment = narrativeDescriptor.mNarrativeImageAdjustment > 0;

		PlaybackMediaHolder previousFrameImage = null;
		PlaybackMediaHolder previousFrameText = null;
		PlaybackMediaHolder lastAudioItem = null; // the last audio item in the narrative

		ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver, mInternalId);
		int currentFrame = 0;
		int lastFrame = narrativeFrames.size() - 1;
		for (FrameItem frame : narrativeFrames) {
			final String frameId = frame.getInternalId();
			mTimeToFrameMap.put(narrativeTime, frameId); // store the frame's start time
			ArrayList<MediaItem> frameComponents = MediaManager.findMediaByParentId(contentResolver, frameId);

			int frameDuration = 0;
			int textDuration = 0;

			// return the start time of the requested starting frame
			if (!frameFound) {
				if (frameId.equals(startingFrame)) {
					narrativeDescriptor.mNarrativeStartTime = narrativeTime;
					frameFound = true;
				}
			}

			// first we need to deal with durations; specifically audio - it's far more complex to play than images/text
			// because we try to fit the items it spans nicely over the duration of its playback
			// for other media items we just naively take the default or user-requested duration TODO: improve this!
			// NOTE: other aspects (e.g., timing editing) rely on audio being first in the list of frame media components
			boolean hasSpanningAudio = false;
			int audioItemsAdded = 0;
			for (MediaItem media : frameComponents) {
				final int mediaType = media.getType();
				final int mediaDuration = media.getDurationMilliseconds();
				if (mediaType == MediaPhoneProvider.TYPE_AUDIO) {
					final String mediaPath = media.getFile().getAbsolutePath();
					final int audioEndTime = narrativeTime + mediaDuration;
					PlaybackMediaHolder audioItem = null;
					if (media.getSpanFrames()) {
						hasSpanningAudio = true;
						if (frameId.equals(media.getParentId())) {
							// this is the actual parent frame of a long-running item - count how many items link here
							// (+1 to count this frame as well)
							ArrayList<String> linkedMedia = MediaManager.findLinkedParentIdsByMediaId(contentResolver,
									media.getInternalId());
							final int linkedItemCount = linkedMedia.size() + 1;
							longRunningAudioCounts.put(mediaPath, linkedItemCount);
							frameDuration = Math.max((int) Math.ceil(mediaDuration / (float) linkedItemCount), frameDuration);

							// add this item to the playback list
							audioItem = new PlaybackMediaHolder(frameId, media.getInternalId(), mediaPath, mediaType,
									narrativeTime, audioEndTime, 0, 0, linkedMedia);
							narrativeContent.add(audioItem);
							audioItemsAdded += 1;

							// because of rounding errors, we could be wrong in the narrative duration here - correct
							narrativeDuration = Math.max(narrativeDuration, audioEndTime);
						} else {
							// if we've inherited this audio then no need to add to playback, just calculate duration
							// TODO: very naive currently - should we split more evenly to account for other lengths?
							// NOTE: if changing behaviour, be sure to account for the similar version in getContentList
							frameDuration = Math.max(
									(int) Math.ceil(mediaDuration / (float) longRunningAudioCounts.get(mediaPath)),
									frameDuration);
						}
					} else {
						// a normal non-spanning audio item - update duration and add
						frameDuration = Math.max(mediaDuration, frameDuration);
						audioItem = new PlaybackMediaHolder(frameId, media.getInternalId(), mediaPath, mediaType, narrativeTime,
								audioEndTime, 0, 0);
						narrativeContent.add(audioItem);
						audioItemsAdded += 1;
					}

					// store the last audio item for displaying it at the end of playback when no other items are present
					if (audioItem != null) {
						if (lastAudioItem == null) {
							lastAudioItem = audioItem;
						} else if (audioEndTime > lastAudioItem.getEndTime(false)) {
							lastAudioItem = audioItem;
						}
					}
				} else {
					// another type of media - just update frame duration from user-set media duration
					// TODO: allow for, e.g., text spanning multiple images but with user-set durations
					frameDuration = Math.max(mediaDuration, frameDuration);

					// note that calculated text durations are stored as negative numbers so we can keep track of what's
					// generated and what's user-selected; we only use the calculated value if it's greater than the
					// minimum duration and if we're not fitting content to spanning audio
					if (mediaType == MediaPhoneProvider.TYPE_TEXT) {
						textDuration = MediaPhone.PLAYBACK_EXPORT_WORD_DURATION * media.getExtra();
					}
				}
			}

			// set the minimum frame duration if not inherited from audio or user-requested
			if (!hasSpanningAudio && frameDuration <= 0) {
				frameDuration = Math.max(MediaPhone.PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION,
						Math.max(frameDuration, textDuration)); // TODO: scale text proportionally when spanning audio?
			}

			// now deal with images (only one item of this type per frame)
			// note that we adjust image timings so that their crossfade ends when the next image starts - this requires
			// shifting images earlier in the playback queue so that they can be set up in time
			// if this is the last item we add 1ms to the end of the item so it stays visible after playback completes
			// TODO: spread the crossfade over the beginning and the end of the image so that timings are more accurate?
			final int mediaEndTime = narrativeTime + frameDuration;
			boolean lastFrameAdjustments = currentFrame == lastFrame && imageAdjustment;
			narrativeDuration = Math.max(narrativeDuration, mediaEndTime);
			PlaybackMediaHolder frameImage = null;
			for (MediaItem media : frameComponents) {
				final int mediaType = media.getType();
				if (mediaType == MediaPhoneProvider.TYPE_IMAGE_FRONT || mediaType == MediaPhoneProvider.TYPE_IMAGE_BACK ||
						mediaType == MediaPhoneProvider.TYPE_VIDEO) {

					// check whether this is a duplicate of the previous item - if so, just extend that item's duration
					// TODO: load spanning items at start (like we do for audio?)
					int imageAdjustmentValue = imageAdjustment ? narrativeDescriptor.mNarrativeImageAdjustment : 0;
					int lastFrameAdjustmentValue =
							lastFrameAdjustments ? -1 : (imageAdjustment ? narrativeDescriptor.mNarrativeImageAdjustment : 0);
					if (media.getSpanFrames() && previousFrameImage != null &&
							media.getInternalId().equals(previousFrameImage.mMediaItemId)) {
						int replacementPosition = narrativeContent.indexOf(previousFrameImage);
						frameImage = new PlaybackMediaHolder(previousFrameImage, frameId, mediaEndTime,
								lastFrameAdjustmentValue);
						narrativeContent.set(replacementPosition, frameImage);
						previousFrameImage = frameImage; // we've replaced the old item (need for comparison later in text item)

					} else {
						frameImage = new PlaybackMediaHolder(frameId, media.getInternalId(), media.getFile().getAbsolutePath(),
								mediaType, narrativeTime, mediaEndTime, imageAdjustmentValue, lastFrameAdjustmentValue);
						narrativeContent.add(narrativeContent.size() - audioItemsAdded, frameImage);
					}

					// if we're coming to an image-only frame from a text-only one, tweak its start time to align (avoid
					// unsightly flash of text at the start of the image display)
					if (imageAdjustment && previousFrameImage == null && previousFrameText != null) {
						int replacementPosition = narrativeContent.indexOf(frameImage);
						frameImage = new PlaybackMediaHolder(frameImage, null, frameImage.getEndTime(false), 0,
								lastFrameAdjustmentValue);
						narrativeContent.set(replacementPosition, frameImage);
					}
					break;
				}
			}

			// finally, add the text (only one item of this type per frame)
			// if this is the last item we add 1ms to the end of the item so it stays visible after playback completes
			PlaybackMediaHolder frameText = null;
			for (MediaItem media : frameComponents) {
				if (media.getType() == MediaPhoneProvider.TYPE_TEXT) {

					// check whether this is a duplicate of the previous item - if so, just extend that item's duration
					// TODO: load spanning items at start (like we do for audio?)
					if (media.getSpanFrames() && previousFrameText != null &&
							media.getInternalId().equals(previousFrameText.mMediaItemId)) {

						int replacementPosition = narrativeContent.indexOf(previousFrameText);
						frameText = new PlaybackMediaHolder(previousFrameText, frameId, mediaEndTime,
								lastFrameAdjustments ? -1 : 0);
						narrativeContent.set(replacementPosition, frameText);

					} else {
						frameText = new PlaybackMediaHolder(frameId, media.getInternalId(), media.getFile().getAbsolutePath(),
								MediaPhoneProvider.TYPE_TEXT, narrativeTime, mediaEndTime, 0, lastFrameAdjustments ? -1 : 0);
						narrativeContent.add(frameText);
					}

					// if we're coming to a text-only frame from an image, we tweak its end time to align with text
					if (imageAdjustment && frameImage == null && previousFrameImage != null) {
						// no need to deal with lastFrameAdjustments as the image we are editing will never be the last item
						int replacementPosition = narrativeContent.indexOf(previousFrameImage);
						previousFrameImage = new PlaybackMediaHolder(previousFrameImage, null,
								previousFrameImage.getEndTime(false), narrativeDescriptor.mNarrativeImageAdjustment, 0);
						narrativeContent.set(replacementPosition, previousFrameImage);
					}
					break;
				}
			}

			// if we've got just audio on a frame and we're the last frame we need to adjust the last audio item to add
			// an extra 1ms so that it remains in view when playback stops
			if (lastFrameAdjustments && frameImage == null && frameText == null && lastAudioItem != null) {
				int replacementPosition = narrativeContent.indexOf(lastAudioItem);
				lastAudioItem = new PlaybackMediaHolder(lastAudioItem, null, lastAudioItem.getEndTime(false), 0, -1);
				narrativeContent.set(replacementPosition, lastAudioItem);

			}

			narrativeTime += frameDuration;
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
