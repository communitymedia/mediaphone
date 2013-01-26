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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

public class MediaManager {

	private static String[] mArguments1 = new String[1];

	private static String mMediaInternalIdSelection;
	private static String mMediaParentIdSelection;
	private static String mDeletedSelection;
	static {
		StringBuilder selection = new StringBuilder();
		selection.append(MediaItem.INTERNAL_ID);
		selection.append("=?");
		mMediaInternalIdSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append("(");
		selection.append(MediaItem.DELETED);
		selection.append("=0 AND ");
		selection.append(MediaItem.PARENT_ID);
		selection.append("=?");
		selection.append(")");
		mMediaParentIdSelection = selection.toString();

		selection.setLength(0);
		selection.append(MediaItem.DELETED);
		selection.append("!=0");
		mDeletedSelection = selection.toString();
	}

	public static MediaItem addMedia(ContentResolver contentResolver, MediaItem media) {
		final Uri uri = contentResolver.insert(MediaItem.CONTENT_URI, media.getContentValues());
		if (uri != null) {
			return media;
		}
		return null;
	}

	/**
	 * Note: to delete a media item, do setDeleted the item itself and then update to the database. On the next
	 * application launch, the media file will be deleted and the database entry will be cleaned up. This approach is
	 * used to speed up interaction and so that we only need to run one background thread semi-regularly for deletion
	 */
	public static boolean deleteMediaFromBackgroundTask(ContentResolver contentResolver, String internalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = internalId;
		int count = contentResolver.delete(MediaItem.CONTENT_URI, mMediaInternalIdSelection, arguments1);
		return count > 0;
	}

	public static boolean updateMedia(ContentResolver contentResolver, MediaItem media) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = media.getInternalId();
		int count = contentResolver.update(MediaItem.CONTENT_URI, media.getContentValues(), mMediaInternalIdSelection,
				arguments1);
		return count == 1;
	}

	public static boolean changeMediaId(ContentResolver contentResolver, String oldMediaItemInternalId,
			String newMediaItemInternalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = oldMediaItemInternalId;
		final ContentValues contentValues = new ContentValues();
		contentValues.put(MediaItem.INTERNAL_ID, newMediaItemInternalId);
		int count = contentResolver.update(MediaItem.CONTENT_URI, contentValues, mMediaInternalIdSelection, arguments1);
		return count == 1;
	}

	public static MediaItem findMediaByInternalId(ContentResolver contentResolver, String internalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = internalId;
		return findMedia(contentResolver, mMediaInternalIdSelection, arguments1);
	}

	private static MediaItem findMedia(ContentResolver contentResolver, String clause, String[] arguments) {
		Cursor c = null;
		try {
			// could add sort order here, but we assume no duplicates...
			c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_ALL, clause, arguments,
					MediaItem.DEFAULT_SORT_ORDER);
			if (c.moveToFirst()) {
				final MediaItem media = MediaItem.fromCursor(c);
				return media;
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return null;
	}

	public static ArrayList<MediaItem> findMediaByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		final ArrayList<MediaItem> medias = new ArrayList<MediaItem>();
		Cursor c = null;
		try {
			c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_ALL, mMediaParentIdSelection,
					arguments1, MediaItem.DEFAULT_SORT_ORDER);
			if (c.getCount() > 0) {
				while (c.moveToNext()) {
					final MediaItem media = MediaItem.fromCursor(c);
					medias.add(media);
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return medias;
	}

	public static ArrayList<String> findMediaIdsByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments;
		arguments = mArguments1;
		arguments[0] = parentId;
		final ArrayList<String> mediaIds = new ArrayList<String>();
		Cursor c = null;
		try {
			c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_INTERNAL_ID, mMediaParentIdSelection,
					arguments, null);
			if (c.getCount() > 0) {
				final int columnIndex = c.getColumnIndexOrThrow(MediaItem.INTERNAL_ID);
				while (c.moveToNext()) {
					final String index = c.getString(columnIndex);
					mediaIds.add(index);
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}

		return mediaIds;
	}

	public static int countMediaByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		Cursor c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_INTERNAL_ID,
				mMediaParentIdSelection, arguments1, MediaItem.DEFAULT_SORT_ORDER);
		final int count = c.getCount();
		c.close();
		return count;
	}

	public static ArrayList<String> findDeletedMedia(ContentResolver contentResolver) {
		final ArrayList<String> mediaIds = new ArrayList<String>();
		Cursor c = null;
		try {
			c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_INTERNAL_ID, mDeletedSelection, null,
					null);
			if (c.getCount() > 0) {
				final int columnIndex = c.getColumnIndexOrThrow(MediaItem.INTERNAL_ID);
				while (c.moveToNext()) {
					mediaIds.add(c.getString(columnIndex));
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return mediaIds;
	}
}
