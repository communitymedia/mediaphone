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
import android.database.Cursor;
import android.net.Uri;

public class NarrativesManager {

	private static String[] mArguments1 = new String[1];

	private static String mInternalIdSelection;
	private static String mNotDeletedSelection;
	static {
		StringBuilder selection = new StringBuilder();
		selection.append(NarrativeItem.INTERNAL_ID);
		selection.append("=?");
		mInternalIdSelection = selection.toString();

		selection.setLength(0); // clears
		selection.append(NarrativeItem.DELETED);
		selection.append("=0");
		mNotDeletedSelection = selection.toString();
	}

	public static NarrativeItem addTemplate(ContentResolver contentResolver, NarrativeItem narrative) {
		return addItem(NarrativeItem.TEMPLATE_CONTENT_URI, contentResolver, narrative);
	}

	public static NarrativeItem addNarrative(ContentResolver contentResolver, NarrativeItem narrative) {
		return addItem(NarrativeItem.NARRATIVE_CONTENT_URI, contentResolver, narrative);
	}

	public static NarrativeItem addItem(Uri contentType, ContentResolver contentResolver, NarrativeItem narrative) {
		final Uri uri = contentResolver.insert(contentType, narrative.getContentValues());
		if (uri != null) {
			return narrative;
		}
		return null;
	}

	@Deprecated
	public static boolean deleteTemplate(ContentResolver contentResolver, String internalId) {
		return deleteItem(NarrativeItem.TEMPLATE_CONTENT_URI, contentResolver, internalId);
	}

	@Deprecated
	public static boolean deleteNarrative(ContentResolver contentResolver, String internalId) {
		return deleteItem(NarrativeItem.NARRATIVE_CONTENT_URI, contentResolver, internalId);
	}

	/**
	 * Set deleted instead; do this onDestroy
	 */
	@Deprecated
	public static boolean deleteItem(Uri contentType, ContentResolver contentResolver, String internalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = internalId;
		int count = contentResolver.delete(contentType, mInternalIdSelection, arguments1);
		// delete cached icon, frames and media elements
		return count > 0;
	}

	public static boolean updateTemplate(ContentResolver contentResolver, NarrativeItem narrative) {
		return updateItem(NarrativeItem.TEMPLATE_CONTENT_URI, contentResolver, narrative);
	}

	public static boolean updateNarrative(ContentResolver contentResolver, NarrativeItem narrative) {
		return updateItem(NarrativeItem.NARRATIVE_CONTENT_URI, contentResolver, narrative);
	}

	private static boolean updateItem(Uri contentType, ContentResolver contentResolver, NarrativeItem narrative) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = narrative.getInternalId();
		int count = contentResolver.update(contentType, narrative.getContentValues(), mInternalIdSelection, arguments1);
		return count == 1;
	}

	public static NarrativeItem findTemplateByInternalId(ContentResolver contentResolver, String internalId) {
		return findItemByInternalId(NarrativeItem.TEMPLATE_CONTENT_URI, contentResolver, internalId);
	}

	public static NarrativeItem findNarrativeByInternalId(ContentResolver contentResolver, String internalId) {
		return findItemByInternalId(NarrativeItem.NARRATIVE_CONTENT_URI, contentResolver, internalId);
	}

	private static NarrativeItem findItemByInternalId(Uri contentType, ContentResolver contentResolver,
			String internalId) {
		final String[] arguments1 = mArguments1;
		arguments1[0] = internalId;
		return findItem(contentType, contentResolver, mInternalIdSelection, arguments1);
	}

	private static NarrativeItem findItem(Uri contentType, ContentResolver contentResolver, String clause,
			String[] arguments) {
		Cursor c = null;
		try {
			// could add sort order here, but we assume no duplicates...
			c = contentResolver.query(contentType, NarrativeItem.PROJECTION_ALL, clause, arguments, null);
			if (c.moveToFirst()) {
				final NarrativeItem narrative = NarrativeItem.fromCursor(c);
				return narrative;
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return null;
	}

	public static int getTemplatesCount(ContentResolver contentResolver) {
		return getCount(NarrativeItem.TEMPLATE_CONTENT_URI, contentResolver);
	}

	public static int getNarrativesCount(ContentResolver contentResolver) {
		return getCount(NarrativeItem.NARRATIVE_CONTENT_URI, contentResolver);
	}

	private static int getCount(Uri contentType, ContentResolver contentResolver) {
		Cursor c = contentResolver.query(contentType, NarrativeItem.PROJECTION_INTERNAL_ID, mNotDeletedSelection, null,
				null);
		final int count = c.getCount();
		c.close();
		return count;
	}

	public static int getNextTemplateExternalId(ContentResolver contentResolver) {
		return getNextExternalId(NarrativeItem.TEMPLATE_CONTENT_URI, contentResolver);
	}

	public static int getNextNarrativeExternalId(ContentResolver contentResolver) {
		return getNextExternalId(NarrativeItem.NARRATIVE_CONTENT_URI, contentResolver);
	}

	private static int getNextExternalId(Uri contentType, ContentResolver contentResolver) {
		Cursor c = null;
		try {
			c = contentResolver.query(contentType, NarrativeItem.PROJECTION_NEXT_EXTERNAL_ID, mNotDeletedSelection,
					null, null);
			if (c.moveToFirst()) {
				final int newId = c.getInt(c.getColumnIndexOrThrow(NarrativeItem.MAX_ID)) + 1;
				return newId;
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return 0;
	}
}
