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
import android.net.Uri;

import java.util.ArrayList;

public class MediaManager {

	private static final String[] mArguments1 = new String[1];
	private static final String[] mArguments2 = new String[2];

	private static final String mMediaInternalIdSelection;
	private static final String mMediaInternalIdNotDeletedSelection;
	private static final String mMediaInternalIdAndParentIdSelection;
	private static final String mMediaParentIdSelection;
	private static final String mDeletedSelection;
	private static final String mTextTypeSelection; // currently only used for upgrade to version 38+

	static {
		StringBuilder selection = new StringBuilder();
		selection.append(MediaItem.INTERNAL_ID);
		selection.append("=?");
		mMediaInternalIdSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append('(');
		selection.append(MediaItem.DELETED);
		selection.append("=0 AND ");
		selection.append(MediaItem.INTERNAL_ID);
		selection.append("=?");
		selection.append(')');
		mMediaInternalIdNotDeletedSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append('(');
		selection.append(MediaItem.INTERNAL_ID);
		selection.append("=? AND ");
		selection.append(MediaItem.PARENT_ID);
		selection.append("=?");
		selection.append(')');
		mMediaInternalIdAndParentIdSelection = selection.toString(); // intentionally don't filter out deleted items

		selection.setLength(0); // clears
		selection.append('(');
		selection.append(MediaItem.DELETED);
		selection.append("=0 AND (");
		selection.append(MediaItem.PARENT_ID);
		selection.append("=?");
		selection.append("))"); // extra ) is to contain OR for multiple parent selections
		mMediaParentIdSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append(MediaItem.DELETED);
		selection.append("!=0");
		mDeletedSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append(MediaItem.DELETED);
		selection.append("=0 AND ");
		selection.append(MediaItem.TYPE);
		selection.append("=");
		selection.append(MediaPhoneProvider.TYPE_TEXT);
		mTextTypeSelection = selection.toString();
	}

	public static MediaItem addMedia(ContentResolver contentResolver, MediaItem media) {
		final Uri uri = contentResolver.insert(MediaItem.CONTENT_URI, media.getContentValues());
		if (uri != null) {
			return media;
		}
		return null;
	}

	/**
	 * Note: to delete a media item, do setDeleted on the item itself and then update to the database. On the next
	 * application exit, the media file will be deleted and the database entry will be cleaned up. This approach is used
	 * to speed up interaction and so that we only need to run one background thread semi-regularly for deletion
	 */
	public static boolean deleteMediaFromBackgroundTask(ContentResolver contentResolver, String internalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = internalId;
		int count = contentResolver.delete(MediaItem.CONTENT_URI, mMediaInternalIdSelection, arguments1);
		return count > 0;
	}

	public static boolean addMediaLink(ContentResolver contentResolver, String frameId, String mediaId) {
		final Uri uri = contentResolver.insert(MediaItem.CONTENT_URI_LINK, MediaItem.getLinkContentValues(frameId, mediaId));
		return uri != null;
	}

	/**
	 * For deleting all media links to an item when the entire spanning media has been removed (ie. from its first
	 * frame)
	 *
	 * @return The number of links deleted
	 */
	public static int deleteMediaLinks(ContentResolver contentResolver, String mediaId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = mediaId;
		final ContentValues contentValues = new ContentValues();
		contentValues.put(MediaItem.DELETED, 1);
		return contentResolver.update(MediaItem.CONTENT_URI_LINK, contentValues, mMediaInternalIdNotDeletedSelection, arguments1);
	}

	/**
	 * For deleting a media link when only the current media item has been removed (i.e. when replacing a long running
	 * media item with another in the current frame)
	 */
	public static boolean deleteMediaLink(ContentResolver contentResolver, String frameId, String mediaId) {
		final String[] arguments2 = mArguments2;
		arguments2[0] = mediaId;
		arguments2[1] = frameId;
		final ContentValues contentValues = new ContentValues();
		contentValues.put(MediaItem.DELETED, 1);
		int count = contentResolver.update(MediaItem.CONTENT_URI_LINK, contentValues, mMediaInternalIdAndParentIdSelection,
				arguments2);
		return count == 1;
	}

	/**
	 * Delete all links by their parent frame ID
	 */
	public static boolean deleteMediaLinksByParent(ContentResolver contentResolver, String frameId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = frameId;
		final ContentValues contentValues = new ContentValues();
		contentValues.put(MediaItem.DELETED, 1);
		int count = contentResolver.update(MediaItem.CONTENT_URI_LINK, contentValues, mMediaParentIdSelection, arguments1);
		return count > 0;
	}

	/**
	 * Removes from the database any media links whose deleted column is not 0. Note: to delete a media link, use
	 * deleteMediaLink to set deleted, rather than actually removing. On the next application exit, the link will be deleted
	 * (via this function) and the database entry will be cleaned up. This approach is used to speed up interaction and so that
	 * we only need to run one background thread semi-regularly for deletion
	 */
	public static boolean removeDeletedMediaLinks(ContentResolver contentResolver) {
		int count = contentResolver.delete(MediaItem.CONTENT_URI_LINK, mDeletedSelection, null);
		return count > 0;
	}

	public static boolean updateMedia(ContentResolver contentResolver, MediaItem media) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = media.getInternalId();
		int count = contentResolver.update(MediaItem.CONTENT_URI, media.getContentValues(), mMediaInternalIdSelection,
				arguments1);
		return count == 1;
	}

	public static MediaItem findMediaByInternalId(ContentResolver contentResolver, String internalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = internalId;
		return findMedia(contentResolver, mMediaInternalIdSelection, arguments1);
	}

	private static MediaItem findMedia(ContentResolver contentResolver, String clause, String[] arguments) {
		try (Cursor c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_ALL, clause, arguments,
				MediaItem.DEFAULT_SORT_ORDER)) {
			// could add sort order here, but we assume no duplicates...
			if (c != null && c.moveToFirst()) {
				return MediaItem.fromCursor(c);
			}
		}
		return null;
	}

	/**
	 * Add '?' placeholders to mMediaParentIdSelection to deal with linked media items
	 */
	private static String addPlaceholders(int numPlaceholders) {
		if (numPlaceholders > 0) {
			StringBuilder selection = new StringBuilder(mMediaParentIdSelection);
			selection.setLength(selection.length() - 2); // delete the ending ))
			selection.append(" OR ");
			selection.append(MediaItem.INTERNAL_ID);
			selection.append(" IN (?");
			for (int i = 1; i < numPlaceholders; i++) {
				selection.append(",?");
			}
			selection.append(")))");
			return selection.toString();
		}
		return mMediaParentIdSelection;
	}

	/**
	 * Get all frames that link to a specific media item (not including the first frame, to which the media actually belongs).
	 */
	public static ArrayList<String> findLinkedParentIdsByMediaId(ContentResolver contentResolver, String mediaId) {
		final ArrayList<String> parentIds = new ArrayList<>();
		final String[] arguments1 = mArguments1;
		arguments1[0] = mediaId;
		try (Cursor c = contentResolver.query(MediaItem.CONTENT_URI_LINK, MediaItem.PROJECTION_PARENT_ID,
				mMediaInternalIdNotDeletedSelection, arguments1, null)) {

			if (c != null && c.getCount() > 0) {
				final int columnIndex = c.getColumnIndexOrThrow(MediaItem.PARENT_ID);
				while (c.moveToNext()) {
					final String linkId = c.getString(columnIndex);
					parentIds.add(linkId);
				}
			}
		}
		return parentIds;
	}

	/**
	 * Get the number of frames that link to a specific media item.
	 */
	public static int countLinkedParentIdsByMediaId(ContentResolver contentResolver, String mediaId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = mediaId;
		try (Cursor c = contentResolver.query(MediaItem.CONTENT_URI_LINK, MediaItem.PROJECTION_PARENT_ID,
				mMediaInternalIdNotDeletedSelection, arguments1, null)) {
			if (c != null) {
				return c.getCount();
			}
		}
		return 0;
	}

	/**
	 * Get all media items that are linked to a specific frame. Note: *only* includes links; not normal items
	 */
	public static ArrayList<String> findLinkedMediaIdsByParentId(ContentResolver contentResolver, String parentId) {
		final ArrayList<String> subIds = new ArrayList<>();
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		try (Cursor c = contentResolver.query(MediaItem.CONTENT_URI_LINK, MediaItem.PROJECTION_INTERNAL_ID,
				mMediaParentIdSelection, arguments1, null)) {

			if (c != null && c.getCount() > 0) {
				final int columnIndex = c.getColumnIndexOrThrow(MediaItem.INTERNAL_ID);
				while (c.moveToNext()) {
					final String linkId = c.getString(columnIndex);
					subIds.add(linkId);
				}
			}
		}
		return subIds;
	}

	/**
	 * Gets a cursor that includes any media linked to this frame id, following the same pattern as
	 * ContentResolver.query(). Media that isn't actually owned by this frame but is included in the query will have a
	 * different parentId
	 */
	private static Cursor getLinkedParentIdMediaCursor(ContentResolver contentResolver, String[] projection, String parentId,
													   String sortOrder) {

		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;

		// first resolve links to other media items from the MediaLinks table
		ArrayList<String> subIds = findLinkedMediaIdsByParentId(contentResolver, parentId);

		// if there are links then we need to add the other media ids to the current query
		if (subIds.size() > 0) {
			subIds.add(0, parentId); // make sure we include the requested parent at the start of the WHERE clause

			// note: more than 999 placeholders is not supported in SQLite, but we shouldn't have more than 1 photo,
			// 3 audio and 1 text items linked at most
			return contentResolver.query(MediaItem.CONTENT_URI, projection, addPlaceholders(subIds.size() - 1),
					subIds.toArray(new String[0]), sortOrder);
		} else {
			// otherwise we just perform the normal query
			return contentResolver.query(MediaItem.CONTENT_URI, projection, mMediaParentIdSelection, arguments1, sortOrder);
		}
	}

	public static ArrayList<MediaItem> findMediaByParentId(ContentResolver contentResolver, String parentId) {
		return findMediaByParentId(contentResolver, parentId, true);
	}

	public static ArrayList<MediaItem> findMediaByParentId(ContentResolver contentResolver, String parentId,
														   boolean includeLinks) {
		final ArrayList<MediaItem> medias = new ArrayList<>();
		Cursor c = null;
		try {
			if (includeLinks) {
				c = getLinkedParentIdMediaCursor(contentResolver, MediaItem.PROJECTION_ALL, parentId,
						MediaItem.DEFAULT_SORT_ORDER);
			} else {
				final String[] arguments1 = mArguments1;
				arguments1[0] = parentId;
				c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_ALL, mMediaParentIdSelection, arguments1,
						MediaItem.DEFAULT_SORT_ORDER);
			}
			if (c != null && c.getCount() > 0) {
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

	public static ArrayList<String> findMediaIdsByParentId(ContentResolver contentResolver, String parentId,
														   boolean includeLinks) {
		final ArrayList<String> mediaIds = new ArrayList<>();
		Cursor c = null;
		try {
			if (includeLinks) {
				c = getLinkedParentIdMediaCursor(contentResolver, MediaItem.PROJECTION_INTERNAL_ID, parentId, null);
			} else {
				final String[] arguments1 = mArguments1;
				arguments1[0] = parentId;
				c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_INTERNAL_ID, mMediaParentIdSelection,
						arguments1, MediaItem.DEFAULT_SORT_ORDER);
			}
			if (c != null && c.getCount() > 0) {
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
		return countMediaByParentId(contentResolver, parentId, true);
	}

	public static int countMediaByParentId(ContentResolver contentResolver, String parentId, boolean includeLinks) {
		Cursor c = null;
		try {
			if (includeLinks) {
				c = getLinkedParentIdMediaCursor(contentResolver, MediaItem.PROJECTION_INTERNAL_ID, parentId, null);
			} else {
				final String[] arguments1 = mArguments1;
				arguments1[0] = parentId;
				c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_INTERNAL_ID, mMediaParentIdSelection,
						arguments1, null);
			}
			if (c != null) {
				return c.getCount();
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return 0;
	}

	public static ArrayList<String> findDeletedMedia(ContentResolver contentResolver) {
		return findDeletedMedia(contentResolver, MediaItem.CONTENT_URI);
	}

	public static ArrayList<String> findDeletedMediaLinks(ContentResolver contentResolver) {
		return findDeletedMedia(contentResolver, MediaItem.CONTENT_URI_LINK);
	}

	private static ArrayList<String> findDeletedMedia(ContentResolver contentResolver, Uri contentUri) {
		final ArrayList<String> mediaIds = new ArrayList<>();
		try (Cursor c = contentResolver.query(contentUri, MediaItem.PROJECTION_INTERNAL_ID, mDeletedSelection, null, null)) {
			if (c != null && c.getCount() > 0) {
				final int columnIndex = c.getColumnIndexOrThrow(MediaItem.INTERNAL_ID);
				while (c.moveToNext()) {
					mediaIds.add(c.getString(columnIndex));
				}
			}
		}
		return mediaIds;
	}

	// currently only used for upgrade to version 38+
	public static ArrayList<MediaItem> findAllTextMedia(ContentResolver contentResolver) {
		final ArrayList<MediaItem> medias = new ArrayList<>();
		try (Cursor c = contentResolver.query(MediaItem.CONTENT_URI, MediaItem.PROJECTION_ALL, mTextTypeSelection, null, null)) {
			if (c != null && c.getCount() > 0) {
				while (c.moveToNext()) {
					final MediaItem media = MediaItem.fromCursor(c);
					medias.add(media);
				}
			}
		}
		return medias;
	}
}
