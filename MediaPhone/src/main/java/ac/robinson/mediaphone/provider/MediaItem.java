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

import java.io.File;
import java.util.Locale;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.util.BitmapUtilities;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.text.TextUtils;

public class MediaItem implements BaseColumns {

	public static final Uri CONTENT_URI = Uri.parse(MediaPhoneProvider.URI_PREFIX + MediaPhoneProvider.URI_AUTHORITY
			+ MediaPhoneProvider.URI_SEPARATOR + MediaPhoneProvider.MEDIA_LOCATION);

	public static final Uri CONTENT_URI_LINK = Uri.parse(MediaPhoneProvider.URI_PREFIX
			+ MediaPhoneProvider.URI_AUTHORITY + MediaPhoneProvider.URI_SEPARATOR
			+ MediaPhoneProvider.MEDIA_LINKS_LOCATION);

	public static final String INTERNAL_ID = "internal_id";
	public static final String PARENT_ID = "parent_id";
	public static final String DATE_CREATED = "date_created";
	public static final String FILE_EXTENSION = "file_name"; // incorrect name for legacy compatibility
	public static final String DURATION = "duration";
	public static final String TYPE = "type";
	public static final String SPAN_FRAMES = "span_frames";
	public static final String DELETED = "deleted";

	public static final String[] PROJECTION_ALL = new String[] { MediaItem._ID, INTERNAL_ID, PARENT_ID, DATE_CREATED,
			FILE_EXTENSION, DURATION, TYPE, SPAN_FRAMES, DELETED };

	public static final String[] PROJECTION_INTERNAL_ID = new String[] { INTERNAL_ID };

	public static final String[] PROJECTION_PARENT_ID = new String[] { PARENT_ID };

	public static final String DEFAULT_SORT_ORDER = TYPE + " ASC, " + DATE_CREATED + " ASC";

	private String mInternalId;
	private String mParentId;
	private long mCreationDate;
	private String mFileExtension;
	private int mDuration;
	private int mType;
	private int mSpanFrames;
	private int mDeleted;

	public MediaItem(String internalId, String parentId, String fileExtension, int type) {
		mInternalId = internalId;
		mParentId = parentId;
		mCreationDate = System.currentTimeMillis();
		setFileExtension(fileExtension);
		mDuration = -1;
		mType = type;
		mSpanFrames = 0;
		mDeleted = 0;
	}

	public MediaItem(String parentId, String fileExtension, int type) {
		this(MediaPhoneProvider.getNewInternalId(), parentId, fileExtension, type);
	}

	public MediaItem() {
		this(null, null, -1);
	}

	public String getInternalId() {
		return mInternalId;
	}

	public String getParentId() {
		return mParentId;
	}

	public void setParentId(String parentId) {
		mParentId = parentId;
	}

	public long getCreationDate() {
		return mCreationDate;
	}

	public String getFileExtension() {
		return mFileExtension;
	}

	public void setFileExtension(String fileExtension) {
		mFileExtension = (fileExtension != null ? fileExtension.toLowerCase(Locale.ENGLISH) : null);
	}

	public int getType() {
		return mType;
	}

	/**
	 * Currently only used for changing the type of an image between front/back camera.
	 * 
	 * @param type
	 */
	public void setType(int type) {
		mType = type;
	}

	public File getFile() {
		return getFile(mParentId, mInternalId, mFileExtension);
	}

	public static File getFile(String mediaParentId, String mediaInternalId, String mediaFileExtension) {
		final File filePath = new File(FrameItem.getStorageDirectory(mediaParentId), mediaInternalId + "."
				+ mediaFileExtension);
		return filePath;
	}

	/**
	 * Set the duration of this media item.
	 * 
	 * @param duration The duration to set, in milliseconds.
	 */
	public void setDurationMilliseconds(int duration) {
		mDuration = duration;
	}

	/**
	 * Returns the duration of this media item, in milliseconds.
	 */
	public int getDurationMilliseconds() {
		return mDuration;
	}

	/**
	 * Whether this media item is set to span multiple frames
	 * 
	 * @return
	 */
	public boolean getSpanFrames() {
		return mSpanFrames == 0 ? false : true;
	}

	/**
	 * Set whether this media item should span multiple frames
	 * 
	 * @param spanFrames
	 */
	public void setSpanFrames(boolean spanFrames) {
		mSpanFrames = spanFrames ? 1 : 0;
	}

	public boolean getDeleted() {
		return mDeleted == 0 ? false : true;
	}

	public void setDeleted(boolean deleted) {
		mDeleted = deleted ? 1 : 0;
	}

	/**
	 * 
	 * @return The bitmap representing this media item, or null if it is not an image or video
	 */
	public Bitmap loadIcon(int width, int height) {

		Bitmap mediaBitmap = null;

		// only image/video; audio and text are generated in frame
		switch (mType) {
			case MediaPhoneProvider.TYPE_IMAGE_BACK:
			case MediaPhoneProvider.TYPE_IMAGE_FRONT:
				mediaBitmap = BitmapUtilities.loadAndCreateScaledBitmap(getFile().getAbsolutePath(), width, height,
						BitmapUtilities.ScalingLogic.CROP, true);
				break;

			case MediaPhoneProvider.TYPE_VIDEO:
				// MINI_KIND: 512 x 384; MICRO_KIND: 96 x 96
				mediaBitmap = BitmapUtilities.scaleBitmap(ThumbnailUtils.createVideoThumbnail(getFile()
						.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND), width, height,
						BitmapUtilities.ScalingLogic.CROP);
				break;
		}

		return mediaBitmap;
	}

	/**
	 * Get the duration of a text string (number of words/lines * word duration in settings), or 0 if empty
	 * 
	 * @param textString
	 * @return
	 */
	public static int getTextDurationMilliseconds(String textString) {
		int frameDuration = 0;
		if (!TextUtils.isEmpty(textString)) {
			String[] stringLines = textString.split("[ \\n]+");
			frameDuration = MediaPhone.PLAYBACK_EXPORT_WORD_DURATION * stringLines.length;
		}
		return frameDuration;
	}

	public ContentValues getContentValues() {
		final ContentValues values = new ContentValues();
		values.put(INTERNAL_ID, mInternalId);
		values.put(PARENT_ID, mParentId);
		values.put(DATE_CREATED, mCreationDate);
		values.put(FILE_EXTENSION, mFileExtension);
		values.put(DURATION, mDuration);
		values.put(TYPE, mType);
		values.put(SPAN_FRAMES, mSpanFrames);
		values.put(DELETED, mDeleted);
		return values;
	}

	public static ContentValues getLinkContentValues(String frameId, String mediaId) {
		final ContentValues values = new ContentValues();
		values.put(INTERNAL_ID, mediaId);
		values.put(PARENT_ID, frameId);
		values.put(DELETED, 0);
		return values;
	}

	public static MediaItem fromExisting(MediaItem existing, String newInternalId, String newParentId,
			long newCreationDate) {
		final MediaItem media = new MediaItem();
		media.mInternalId = newInternalId;
		media.mParentId = newParentId;
		media.mFileExtension = existing.mFileExtension;
		media.mCreationDate = newCreationDate;
		media.mDuration = existing.mDuration;
		media.mType = existing.mType;
		media.mSpanFrames = existing.mSpanFrames;
		media.mDeleted = existing.mDeleted;
		return media;
	}

	public static MediaItem fromCursor(Cursor c) {
		final MediaItem media = new MediaItem();
		media.mInternalId = c.getString(c.getColumnIndexOrThrow(INTERNAL_ID));
		media.mParentId = c.getString(c.getColumnIndexOrThrow(PARENT_ID));
		media.mFileExtension = c.getString(c.getColumnIndexOrThrow(FILE_EXTENSION));
		media.mCreationDate = c.getLong(c.getColumnIndexOrThrow(DATE_CREATED));
		media.mDuration = c.getInt(c.getColumnIndex(DURATION));
		media.mType = c.getInt(c.getColumnIndexOrThrow(TYPE));
		media.mSpanFrames = c.getInt(c.getColumnIndexOrThrow(SPAN_FRAMES));
		media.mDeleted = c.getInt(c.getColumnIndexOrThrow(DELETED));
		return media;
	}

	@Override
	public String toString() {
		return this.getClass().getName() + "[" + mInternalId + "," + mParentId + "," + mCreationDate + ","
				+ mFileExtension + "," + mDuration + "," + mType + "," + mSpanFrames + "," + mDeleted + "]";
	}
}
