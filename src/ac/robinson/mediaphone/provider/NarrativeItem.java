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

import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediautilities.FrameMediaContainer;
import ac.robinson.util.IOUtilities;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;

public class NarrativeItem implements BaseColumns {

	public static final Uri NARRATIVE_CONTENT_URI = Uri.parse(MediaPhoneProvider.URI_PREFIX
			+ MediaPhoneProvider.URI_AUTHORITY + MediaPhoneProvider.URI_SEPARATOR
			+ MediaPhoneProvider.NARRATIVES_LOCATION);

	public static final Uri TEMPLATE_CONTENT_URI = Uri.parse(MediaPhoneProvider.URI_PREFIX
			+ MediaPhoneProvider.URI_AUTHORITY + MediaPhoneProvider.URI_SEPARATOR
			+ MediaPhoneProvider.TEMPLATES_LOCATION);

	public static final String[] PROJECTION_ALL = new String[] { NarrativeItem._ID, NarrativeItem.INTERNAL_ID,
			NarrativeItem.DATE_CREATED, NarrativeItem.SEQUENCE_ID, NarrativeItem.DELETED };

	public static final String[] PROJECTION_INTERNAL_ID = new String[] { NarrativeItem.INTERNAL_ID };

	public static final String MAX_ID = "max_id";
	public static final String[] PROJECTION_NEXT_EXTERNAL_ID = new String[] { "MAX(" + NarrativeItem.SEQUENCE_ID
			+ ") as " + MAX_ID };

	// for keeping track of the helper narrative (so we don't add multiple copies later)
	public static final String HELPER_NARRATIVE_ID = "936df7b0-72b9-11e2-bcfd-0800200c9a66"; // *DO NOT CHANGE*

	public static final String INTERNAL_ID = "internal_id";
	public static final String DATE_CREATED = "date_created";
	public static final String SEQUENCE_ID = "sequence_id";
	public static final String DELETED = "deleted";

	public static final String DEFAULT_SORT_ORDER = DATE_CREATED + " DESC";

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
		return mDeleted == 0 ? false : true;
	}

	public void setDeleted(boolean deleted) {
		mDeleted = deleted ? 1 : 0;
	}

	public ArrayList<FrameMediaContainer> getContentList(ContentResolver contentResolver) {

		ArrayList<FrameMediaContainer> exportedContent = new ArrayList<FrameMediaContainer>();

		ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver, mInternalId);
		for (FrameItem frame : narrativeFrames) {

			ArrayList<MediaItem> frameComponents = MediaManager.findMediaByParentId(contentResolver,
					frame.getInternalId());

			final FrameMediaContainer currentContainer = new FrameMediaContainer(frame.getInternalId(),
					frame.getNarrativeSequenceId());

			currentContainer.mParentId = frame.getParentId();

			for (MediaItem media : frameComponents) {

				switch (media.getType()) {
					case MediaPhoneProvider.TYPE_IMAGE_FRONT:
						currentContainer.mImageIsFrontCamera = true;
					case MediaPhoneProvider.TYPE_IMAGE_BACK:
					case MediaPhoneProvider.TYPE_VIDEO:
						currentContainer.mImagePath = media.getFile().getAbsolutePath();
						break;

					case MediaPhoneProvider.TYPE_TEXT:
						currentContainer.mTextContent = IOUtilities.getFileContents(media.getFile().getAbsolutePath());
						if (!TextUtils.isEmpty(currentContainer.mTextContent)) {
							currentContainer.updateFrameMaxDuration(MediaItem
									.getTextDurationMilliseconds(currentContainer.mTextContent));
						} else {
							currentContainer.mTextContent = null;
						}
						break;

					case MediaPhoneProvider.TYPE_AUDIO:
						currentContainer.addAudioFile(media.getFile().getAbsolutePath(),
								media.getDurationMilliseconds());
						break;
				}

				currentContainer.updateFrameMaxDuration(media.getDurationMilliseconds());
			}

			// don't allow frames to be shorter than the minimum duration
			if (currentContainer.mFrameMaxDuration <= 0) {
				currentContainer.updateFrameMaxDuration(MediaPhone.PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION);
			}

			exportedContent.add(currentContainer);
		}

		return exportedContent;
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

	@Override
	public String toString() {
		return this.getClass().getName() + "[" + mInternalId + "," + mCreationDate + "," + mSequenceId + "," + mDeleted
				+ "]";
	}
}
