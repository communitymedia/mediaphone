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

import ac.robinson.util.BitmapUtilities.CacheTypeContainer;
import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.util.ImageCacheUtilities;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;

public class FramesManager {

	private static String[] mArguments1 = new String[1];
	private static String[] mArguments2 = new String[2];

	private static String mFrameInternalIdSelection;
	private static String mFrameParentIdSelection;
	private static String mFrameParentIdSelectionWithExtra;
	static {
		StringBuilder selection = new StringBuilder();
		selection.append(FrameItem.INTERNAL_ID);
		selection.append("=?");
		mFrameInternalIdSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append("(");
		selection.append(FrameItem.DELETED);
		selection.append("=0 AND ");
		selection.append(FrameItem.PARENT_ID);
		selection.append("=?");
		selection.append(")");
		mFrameParentIdSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append("((");
		selection.append(FrameItem.DELETED);
		selection.append("=0 AND ");
		selection.append(FrameItem.PARENT_ID);
		selection.append("=?");
		selection.append(") OR ");
		selection.append(FrameItem.INTERNAL_ID);
		selection.append("=?");
		selection.append(")");
		mFrameParentIdSelectionWithExtra = selection.toString();
	}

	public static void reloadFrameIcon(Resources resources, ContentResolver contentResolver, FrameItem frame,
			boolean frameIsInDatabase) {
		// use the best type for photo/text/icon
		CacheTypeContainer cacheTypeContainer = new CacheTypeContainer(MediaPhone.ICON_CACHE_TYPE);
		Bitmap frameIcon = frame.loadIcon(resources, contentResolver, cacheTypeContainer, frameIsInDatabase);

		ImageCacheUtilities.addIconToCache(MediaPhone.DIRECTORY_THUMBS, frame.getCacheId(), frameIcon,
				cacheTypeContainer.type, MediaPhone.ICON_CACHE_QUALITY);
	}

	public static void reloadFrameIcon(Resources resources, ContentResolver contentResolver, String frameId) {
		reloadFrameIcon(resources, contentResolver, findFrameByInternalId(contentResolver, frameId), true);
	}

	public static FrameItem addFrameAndPreloadIcon(Resources resources, ContentResolver contentResolver, FrameItem frame) {
		// when importing, reloading the icon first means we don't load it twice (import process and in the adapter)
		reloadFrameIcon(resources, contentResolver, frame, false);
		final Uri uri = contentResolver.insert(FrameItem.CONTENT_URI, frame.getContentValues());
		if (uri != null) {
			return frame;
		}
		return null;
	}

	public static void loadTemporaryFrameIcon(Resources resources, FrameItem frame, boolean addBorder) {
		ImageCacheUtilities.addIconToCache(MediaPhone.DIRECTORY_THUMBS, frame.getCacheId(),
				FrameItem.loadTemporaryIcon(resources, addBorder), MediaPhone.ICON_CACHE_TYPE,
				MediaPhone.ICON_CACHE_QUALITY);
	}

	public static FrameItem addFrame(Resources resources, ContentResolver contentResolver, FrameItem frame,
			boolean loadIcon) {
		final Uri uri = contentResolver.insert(FrameItem.CONTENT_URI, frame.getContentValues());
		if (uri != null) {
			if (loadIcon) {
				reloadFrameIcon(resources, contentResolver, frame, true); // is this necessary (don't have any media)?
			} else {
				// loadTemporaryFrameIcon(resources, frame);
			}
			return frame;
		}
		return null;
	}

	/** 
	 * Set deleted instead; do this onDestroy
	 */
	@Deprecated
	public static boolean deleteFrame(ContentResolver contentResolver, String frameId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = frameId;
		int count = contentResolver.delete(FrameItem.CONTENT_URI, mFrameInternalIdSelection, mArguments1);
		ImageCacheUtilities.deleteCachedIcon(FrameItem.getCacheId(frameId));
		// delete cached icon and media file
		return count > 0;
	}

	public static boolean updateFrame(ContentResolver contentResolver, FrameItem frame) {
		return updateFrame(null, contentResolver, frame, false);
	}

	public static boolean updateFrame(Resources resources, ContentResolver contentResolver, FrameItem frame,
			boolean reloadIcon) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = frame.getInternalId();
		int count = contentResolver.update(FrameItem.CONTENT_URI, frame.getContentValues(), mFrameInternalIdSelection,
				arguments1);
		if (count == 1) {
			if (reloadIcon) {
				ImageCacheUtilities.deleteCachedIcon(frame.getCacheId());
				reloadFrameIcon(resources, contentResolver, frame, true);
			}
			return true;
		}
		return false;
	}

	public static FrameItem findFrameByInternalId(ContentResolver contentResolver, String internalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = internalId;
		return findFrame(contentResolver, mFrameInternalIdSelection, arguments1);
	}

	private static FrameItem findFrame(ContentResolver contentResolver, String clause, String[] arguments) {
		Cursor c = null;
		try {
			// could add sort order here, but we assume no duplicates...
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_ALL, clause, arguments, null);
			if (c.moveToFirst()) {
				return FrameItem.fromCursor(c);
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return null;
	}

	public static ArrayList<FrameItem> findFramesByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		ArrayList<FrameItem> frames = new ArrayList<FrameItem>();
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_ALL, mFrameParentIdSelection,
					arguments1, FrameItem.DEFAULT_SORT_ORDER);
			if (c.getCount() > 0) {
				while (c.moveToNext()) {
					frames.add(FrameItem.fromCursor(c));
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}

		return frames;
	}

	public static ArrayList<String> findFrameIdsByParentId(ContentResolver contentResolver, String parentId,
			String includeExtra) {
		final String[] arguments;
		if (includeExtra != null) {
			arguments = mArguments2;
			arguments[1] = includeExtra;
		} else {
			arguments = mArguments1;
		}
		arguments[0] = parentId;
		ArrayList<String> frameIds = new ArrayList<String>();
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_INTERNAL_ID,
					(includeExtra != null ? mFrameParentIdSelectionWithExtra : mFrameParentIdSelection), arguments,
					FrameItem.DEFAULT_SORT_ORDER);
			if (c.getCount() > 0) {
				while (c.moveToNext()) {
					frameIds.add(c.getString(c.getColumnIndexOrThrow(FrameItem.INTERNAL_ID)));
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}

		return frameIds;
	}

	public static FrameItem findFirstFrameByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_ALL, mFrameParentIdSelection,
					arguments1, FrameItem.DEFAULT_SORT_ORDER);
			if (c.moveToFirst()) {
				return FrameItem.fromCursor(c);
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return null;
	}

	public static int findLastFrameNarrativeSequenceId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_SEQEUENCE_ID,
					mFrameParentIdSelection, arguments1, FrameItem.DEFAULT_SORT_ORDER);
			if (c.moveToLast()) {
				// getColumnIndex is slow, so we don't get the whole FrameItem
				return c.getInt(c.getColumnIndexOrThrow(FrameItem.SEQUENCE_ID));
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return -1; // no existing frames (but should not happen)
	}

	public static int countFramesByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		Cursor c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_SEQEUENCE_ID, // doesn't matter
				mFrameParentIdSelection, arguments1, FrameItem.DEFAULT_SORT_ORDER);
		return c.getCount();
	}
}
