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
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;

import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.util.BitmapUtilities.CacheTypeContainer;
import ac.robinson.util.ImageCacheUtilities;

public class FramesManager {

	private static String[] mArguments1 = new String[1];

	private static String mFrameInternalIdSelection;
	private static String mFrameParentIdSelection;
	private static String mDeletedSelection;
	static {
		StringBuilder selection = new StringBuilder();
		selection.append(FrameItem.INTERNAL_ID);
		selection.append("=?");
		mFrameInternalIdSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append('(');
		selection.append(FrameItem.DELETED);
		selection.append("=0 AND ");
		selection.append(FrameItem.PARENT_ID);
		selection.append("=?");
		selection.append(')');
		mFrameParentIdSelection = selection.toString();

		selection.setLength(0);
		selection.append(FrameItem.DELETED);
		selection.append("!=0");
		mDeletedSelection = selection.toString();
	}

	/**
	 * Update a list of frame icons, removing all icons from the cache first to ensure the old version is not displayed
	 * 
	 * @param frameIds
	 */
	public static void reloadFrameIcons(Resources resources, ContentResolver contentResolver, ArrayList<String> frameIds) {
		for (String frameId : frameIds) {
			ImageCacheUtilities.setLoadingIcon(FrameItem.getCacheId(frameId));
		}
		for (String frameId : frameIds) {
			reloadFrameIcon(resources, contentResolver, frameId);
		}
	}

	public static void reloadFrameIcon(Resources resources, ContentResolver contentResolver, FrameItem frame,
			boolean frameIsInDatabase) {
		if (frame == null) {
			return; // if run from switchFrames then the existing frame could have been deleted - ignore
		}

		final String frameCacheId = frame.getCacheId();
		ImageCacheUtilities.setLoadingIcon(frameCacheId);

		// use the best type for photo/text/icon
		CacheTypeContainer cacheTypeContainer = new CacheTypeContainer(MediaPhone.ICON_CACHE_TYPE);
		Bitmap frameIcon = frame.loadIcon(resources, contentResolver, cacheTypeContainer, frameIsInDatabase);

		ImageCacheUtilities.deleteCachedIcon(frameCacheId); // just in case adding encounters an error (we'll repeat)
		ImageCacheUtilities.addIconToCache(MediaPhone.DIRECTORY_THUMBS, frameCacheId, frameIcon,
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
	 * Note: to delete a frame item, do setDeleted on the item itself and then update to the database. On the next
	 * application exit, the frame's media files will be deleted and the database entry will be cleaned up. This
	 * approach speeds up interaction and means that we only need one background thread semi-regularly for deletion
	 */
	public static boolean deleteFrameFromBackgroundTask(ContentResolver contentResolver, String frameId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = frameId;
		int count = contentResolver.delete(FrameItem.CONTENT_URI, mFrameInternalIdSelection, mArguments1);
		ImageCacheUtilities.deleteCachedIcon(FrameItem.getCacheId(frameId));
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
				final FrameItem frame = FrameItem.fromCursor(c);
				return frame;
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
		final ArrayList<FrameItem> frames = new ArrayList<>();
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_ALL, mFrameParentIdSelection,
					arguments1, FrameItem.DEFAULT_SORT_ORDER);
			if (c.getCount() > 0) {
				while (c.moveToNext()) {
					final FrameItem frame = FrameItem.fromCursor(c);
					frames.add(frame);
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}

		return frames;
	}

	public static ArrayList<String> findFrameIdsByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments;
		arguments = mArguments1;
		arguments[0] = parentId;
		final ArrayList<String> frameIds = new ArrayList<>();
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_INTERNAL_ID, mFrameParentIdSelection,
					arguments, FrameItem.DEFAULT_SORT_ORDER);
			if (c.getCount() > 0) {
				final int columnIndex = c.getColumnIndexOrThrow(FrameItem.INTERNAL_ID);
				while (c.moveToNext()) {
					final String index = c.getString(columnIndex);
					frameIds.add(index);
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
				final FrameItem frame = FrameItem.fromCursor(c);
				return frame;
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return null;
	}

	public static String findLastFrameByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_INTERNAL_ID, mFrameParentIdSelection,
					arguments1, FrameItem.DEFAULT_SORT_ORDER);
			if (c.moveToLast()) {
				// for speed, don't get the whole FrameItem
				final String lastId = c.getString(c.getColumnIndexOrThrow(FrameItem.INTERNAL_ID));
				return lastId;
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return null; // no existing frames (but should not happen)
	}

	public static int countFramesByParentId(ContentResolver contentResolver, String parentId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = parentId;
		Cursor c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_INTERNAL_ID,
				mFrameParentIdSelection, arguments1, FrameItem.DEFAULT_SORT_ORDER);
		final int count = c.getCount();
		c.close();
		return count;
	}

	public static ArrayList<String> findDeletedFrames(ContentResolver contentResolver) {
		final ArrayList<String> frameIds = new ArrayList<>();
		Cursor c = null;
		try {
			c = contentResolver.query(FrameItem.CONTENT_URI, FrameItem.PROJECTION_INTERNAL_ID, mDeletedSelection, null,
					null);
			if (c.getCount() > 0) {
				final int columnIndex = c.getColumnIndexOrThrow(FrameItem.INTERNAL_ID);
				while (c.moveToNext()) {
					frameIds.add(c.getString(columnIndex));
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return frameIds;
	}

	/**
	 * Returns a list of the frame ids following the given frame id. If includePrevious is set then the frame before the
	 * given frame will also be included. If not then the list will start from one after the current frame. If
	 * includePrevious is true, the first element of the returned list may be null (as the start frame could be the
	 * first frame of the narrative).
	 * 
	 * @param frameId
	 * @param includeCurrentAndPrevious Whether to include the previous frame in the list as well
	 */
	public static ArrayList<String> getFollowingFrameIds(ContentResolver contentResolver, String frameId,
			boolean includeCurrentAndPrevious) {
		if (frameId == null) {
			return null;
		}
		FrameItem parentFrame = findFrameByInternalId(contentResolver, frameId);
		return getFollowingFrameIds(contentResolver, parentFrame, includeCurrentAndPrevious);
	}

	public static ArrayList<String> getFollowingFrameIds(ContentResolver contentResolver, FrameItem parentFrame,
			boolean includePrevious) {
		if (parentFrame == null) {
			return null;
		}

		final String parentFrameId = parentFrame.getInternalId();
		ArrayList<String> narrativeFrameIds = findFrameIdsByParentId(contentResolver, parentFrame.getParentId());
		ArrayList<String> idsToRemove = new ArrayList<>();

		// used to use an iterator here, but it turns out that remove() can fail silently (!)
		String previousFrameId = null;
		for (final String frameId : narrativeFrameIds) {
			idsToRemove.add(frameId);
			if (parentFrameId.equals(frameId)) {
				break;
			}
			previousFrameId = frameId;
		}

		// remove irrelevant frames; preserve previous if necessary
		if (includePrevious) {
			idsToRemove.remove(previousFrameId);
		}
		narrativeFrameIds.removeAll(idsToRemove);
		if (includePrevious && previousFrameId == null) {
			narrativeFrameIds.add(0, null); // need this null to show that no previous frame is present
		}
		return narrativeFrameIds;
	}

	/**
	 * Used for inserting a new frame - given the narrative id and the desired before or after frame id (not both) this
	 * function will adjust existing frames and return the new frame sequence id, which must be updated into the frame
	 * by the caller. Note: the new frame must have already been added to the database.
	 *
	 * @param narrativeId
	 * @param insertAfterId
	 * @return The sequence id that should be used for the new frame
	 */
	public static int adjustNarrativeSequenceIds(Resources res, ContentResolver contentResolver, String narrativeId, String
			insertAfterId) {
		// note: not a background task any more, because it causes concurrency problems with deleting after back press
		int narrativeSequenceIdIncrement = res.getInteger(R.integer.frame_narrative_sequence_increment);
		int narrativeSequenceId = 0;

		// insert new frame - increment necessary frames after the new frame's position
		boolean insertAtStart = FrameItem.KEY_FRAME_ID_START.equals(insertAfterId);
		ArrayList<FrameItem> narrativeFrames = FramesManager.findFramesByParentId(contentResolver, narrativeId);
		narrativeFrames.remove(0); // don't edit the newly inserted frame yet

		int previousNarrativeSequenceId = -1;
		boolean frameFound = false;
		for (FrameItem frame : narrativeFrames) {
			if (!frameFound && insertAtStart) {
				frameFound = true;
				narrativeSequenceId = frame.getNarrativeSequenceId();
			}
			if (frameFound) {
				int currentNarrativeSequenceId = frame.getNarrativeSequenceId();
				if (currentNarrativeSequenceId <= narrativeSequenceId || currentNarrativeSequenceId <=
						previousNarrativeSequenceId) {

					frame.setNarrativeSequenceId(currentNarrativeSequenceId + Math.max(narrativeSequenceId -
							currentNarrativeSequenceId, previousNarrativeSequenceId - currentNarrativeSequenceId) + 1);
					if (insertAtStart) {
						FramesManager.updateFrame(res, contentResolver, frame, true); // TODO: background task?
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

		return narrativeSequenceId;
	}
}
