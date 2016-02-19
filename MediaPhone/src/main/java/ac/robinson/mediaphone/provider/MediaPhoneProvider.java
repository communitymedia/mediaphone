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
import java.util.UUID;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.util.DebugUtilities;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

public class MediaPhoneProvider extends ContentProvider {

	public static final String URI_AUTHORITY = MediaPhone.APPLICATION_NAME;
	private static final String DATABASE_NAME = URI_AUTHORITY + ".db";
	private static final int DATABASE_VERSION = 2;

	public static final String URI_PREFIX = "content://";
	public static final String URI_SEPARATOR = File.separator;
	private final String URI_PACKAGE = this.getClass().getPackage().getName();

	public static final String NARRATIVES_LOCATION = "narratives";
	public static final String FRAMES_LOCATION = "frames";
	public static final String MEDIA_LOCATION = "media";
	public static final String MEDIA_LINKS_LOCATION = "media_links";
	public static final String TEMPLATES_LOCATION = "templates";

	// NOTE: these are *not* the same as the MediaTablet type classifiers
	public static final int TYPE_IMAGE_BACK = 1; // normal (rear) camera
	public static final int TYPE_IMAGE_FRONT = 2; // front camera
	public static final int TYPE_VIDEO = 3;
	public static final int TYPE_AUDIO = 4;
	public static final int TYPE_TEXT = 5;

	private static final UriMatcher URI_MATCHER;
	static {
		URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
		URI_MATCHER.addURI(URI_AUTHORITY, NARRATIVES_LOCATION, R.id.uri_narratives);
		URI_MATCHER.addURI(URI_AUTHORITY, FRAMES_LOCATION, R.id.uri_frames);
		URI_MATCHER.addURI(URI_AUTHORITY, MEDIA_LOCATION, R.id.uri_media);
		URI_MATCHER.addURI(URI_AUTHORITY, MEDIA_LINKS_LOCATION, R.id.uri_media_links);
		URI_MATCHER.addURI(URI_AUTHORITY, TEMPLATES_LOCATION, R.id.uri_templates);
	}

	private SQLiteOpenHelper mOpenHelper;

	@Override
	public boolean onCreate() {
		mOpenHelper = new DatabaseHelper(getContext());
		return true;
	}

	public static String getNewInternalId() {
		return UUID.randomUUID().toString();
	}

	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {

		SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

		switch (URI_MATCHER.match(uri)) {
			case R.id.uri_narratives:
				qb.setTables(NARRATIVES_LOCATION);
				break;
			case R.id.uri_frames:
				qb.setTables(FRAMES_LOCATION);
				break;
			case R.id.uri_media:
				qb.setTables(MEDIA_LOCATION);
				break;
			case R.id.uri_media_links:
				qb.setTables(MEDIA_LINKS_LOCATION);
				break;
			case R.id.uri_templates:
				qb.setTables(TEMPLATES_LOCATION);
				break;
			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}

		// if no sort order is specified use none
		String orderBy;
		if (TextUtils.isEmpty(sortOrder)) {
			orderBy = null;
		} else {
			orderBy = sortOrder;
		}

		SQLiteDatabase db = mOpenHelper.getReadableDatabase();
		Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy);
		c.setNotificationUri(getContext().getContentResolver(), uri);

		return c;
	}

	public String getType(Uri uri) {
		switch (URI_MATCHER.match(uri)) {
			case R.id.uri_narratives:
			case R.id.uri_frames:
			case R.id.uri_media:
			case R.id.uri_media_links:
			case R.id.uri_templates:
				return "vnd.android.cursor.dir/vnd." + URI_PACKAGE; // do these need to be unique?
			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}
	}

	public Uri insert(Uri uri, ContentValues initialValues) {

		ContentValues values;
		if (initialValues != null) {
			values = new ContentValues(initialValues);
		} else {
			throw new IllegalArgumentException("No content values passed");
		}

		getType(uri); // so we don't get the database unless necessary
		SQLiteDatabase db = mOpenHelper.getWritableDatabase();

		long rowId = 0;
		Uri contentUri = null;
		switch (URI_MATCHER.match(uri)) {
			case R.id.uri_narratives:
				rowId = db.insert(NARRATIVES_LOCATION, null, values);
				contentUri = NarrativeItem.NARRATIVE_CONTENT_URI;
				break;
			case R.id.uri_frames:
				rowId = db.insert(FRAMES_LOCATION, null, values);
				contentUri = FrameItem.CONTENT_URI;
				break;
			case R.id.uri_media:
				rowId = db.insert(MEDIA_LOCATION, null, values);
				contentUri = MediaItem.CONTENT_URI;
				break;
			case R.id.uri_media_links:
				rowId = db.insert(MEDIA_LINKS_LOCATION, null, values);
				contentUri = MediaItem.CONTENT_URI_LINK;
				break;
			case R.id.uri_templates:
				rowId = db.insert(TEMPLATES_LOCATION, null, values);
				contentUri = NarrativeItem.TEMPLATE_CONTENT_URI;
				break;
		}

		if (rowId > 0) {
			Uri insertUri = ContentUris.withAppendedId(contentUri, rowId);
			getContext().getContentResolver().notifyChange(uri, null);
			return insertUri;
		}
		throw new SQLException("Failed to insert row into " + uri);
	}

	public int delete(Uri uri, String selectionClause, String[] selectionArgs) {
		getType(uri); // so we don't get the database unless necessary
		SQLiteDatabase db = mOpenHelper.getWritableDatabase();

		int count;
		switch (URI_MATCHER.match(uri)) {
			case R.id.uri_narratives:
				count = db.delete(NARRATIVES_LOCATION, selectionClause, selectionArgs);
				break;
			case R.id.uri_frames:
				count = db.delete(FRAMES_LOCATION, selectionClause, selectionArgs);
				break;
			case R.id.uri_media:
				count = db.delete(MEDIA_LOCATION, selectionClause, selectionArgs);
				break;
			case R.id.uri_media_links:
				count = db.delete(MEDIA_LINKS_LOCATION, selectionClause, selectionArgs);
				break;
			case R.id.uri_templates:
				count = db.delete(TEMPLATES_LOCATION, selectionClause, selectionArgs);
				break;
			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}

		if (count > 0) {
			getContext().getContentResolver().notifyChange(uri, null);
		}
		return count;
	}

	public int update(Uri uri, ContentValues initialValues, String selectionClause, String[] selectionArgs) {

		ContentValues values;
		if (initialValues != null) {
			values = new ContentValues(initialValues);
		} else {
			throw new IllegalArgumentException("No content values passed");
		}

		getType(uri); // so we don't get the database unless necessary
		SQLiteDatabase db = mOpenHelper.getWritableDatabase();

		int rowsAffected = 0;
		switch (URI_MATCHER.match(uri)) {
			case R.id.uri_narratives:
				rowsAffected = db.update(NARRATIVES_LOCATION, values, selectionClause, selectionArgs);
				break;
			case R.id.uri_frames:
				rowsAffected = db.update(FRAMES_LOCATION, values, selectionClause, selectionArgs);
				break;
			case R.id.uri_media:
				rowsAffected = db.update(MEDIA_LOCATION, values, selectionClause, selectionArgs);
				break;
			case R.id.uri_media_links:
				rowsAffected = db.update(MEDIA_LINKS_LOCATION, values, selectionClause, selectionArgs);
				break;
			case R.id.uri_templates:
				rowsAffected = db.update(TEMPLATES_LOCATION, values, selectionClause, selectionArgs);
				break;
		}

		if (rowsAffected > 0) {
			getContext().getContentResolver().notifyChange(uri, null);
		}
		return rowsAffected;
	}

	private static class DatabaseHelper extends SQLiteOpenHelper {
		public DatabaseHelper(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {
			db.execSQL("DROP TABLE IF EXISTS " + NARRATIVES_LOCATION + ";");
			db.execSQL("DROP TABLE IF EXISTS " + FRAMES_LOCATION + ";");
			db.execSQL("DROP TABLE IF EXISTS " + MEDIA_LOCATION + ";");
			db.execSQL("DROP TABLE IF EXISTS " + TEMPLATES_LOCATION + ";");

			db.execSQL("CREATE TABLE " + NARRATIVES_LOCATION + " (" //
					+ NarrativeItem._ID + " INTEGER PRIMARY KEY, " // required for Android Adapters
					+ NarrativeItem.INTERNAL_ID + " TEXT, " // the GUID of this narrative item
					+ NarrativeItem.SEQUENCE_ID + " INTEGER, " // the displayed ID of this narrative item
					+ NarrativeItem.DATE_CREATED + " INTEGER, " // the timestamp when this narrative was created
					+ NarrativeItem.DELETED + " INTEGER);"); // whether this narrative has been deleted
			db.execSQL("CREATE INDEX " + NARRATIVES_LOCATION + "Index" + NarrativeItem.INTERNAL_ID + " ON "
					+ NARRATIVES_LOCATION + "(" + NarrativeItem.INTERNAL_ID + ");");

			db.execSQL("CREATE TABLE " + FRAMES_LOCATION + " (" //
					+ FrameItem._ID + " INTEGER PRIMARY KEY, " // required for Android Adapters
					+ FrameItem.INTERNAL_ID + " TEXT, " // the GUID of this frame item
					+ FrameItem.PARENT_ID + " TEXT, " // the GUID of the parent of this frame item
					+ FrameItem.SEQUENCE_ID + " INTEGER, " // the position of this frame in the narrative
					+ FrameItem.DATE_CREATED + " INTEGER, " // the timestamp when this frame was created
					+ FrameItem.DELETED + " INTEGER);"); // whether this frame has been deleted
			db.execSQL("CREATE INDEX " + FRAMES_LOCATION + "Index" + FrameItem.INTERNAL_ID + " ON " + FRAMES_LOCATION
					+ "(" + FrameItem.INTERNAL_ID + ");");
			db.execSQL("CREATE INDEX " + FRAMES_LOCATION + "Index" + FrameItem.PARENT_ID + " ON " + FRAMES_LOCATION
					+ "(" + FrameItem.PARENT_ID + ");");

			// add the new item before and after frames
			db.execSQL("INSERT INTO " + FRAMES_LOCATION + " (" + FrameItem.INTERNAL_ID + ", " + FrameItem.PARENT_ID
					+ ", " + FrameItem.SEQUENCE_ID + ", " + FrameItem.DATE_CREATED + ") VALUES ('"
					+ FrameItem.KEY_FRAME_ID_START + "', null, " + Integer.MIN_VALUE + ", " + 0 + ");");
			db.execSQL("INSERT INTO " + FRAMES_LOCATION + " (" + FrameItem.INTERNAL_ID + ", " + FrameItem.PARENT_ID
					+ ", " + FrameItem.SEQUENCE_ID + ", " + FrameItem.DATE_CREATED + ") VALUES ('"
					+ FrameItem.KEY_FRAME_ID_END + "', null, " + Integer.MAX_VALUE + ", " + Integer.MAX_VALUE + ");");

			db.execSQL("CREATE TABLE " + MEDIA_LOCATION + " (" //
					+ MediaItem._ID + " INTEGER PRIMARY KEY, " // required for Android Adapters
					+ MediaItem.INTERNAL_ID + " TEXT, " // the GUID of this media item
					+ MediaItem.PARENT_ID + " TEXT, " // the GUID of the parent of this media item
					+ MediaItem.TYPE + " INTEGER, " // the type of this media (this.TYPE_<x>)
					+ MediaItem.FILE_EXTENSION + " TEXT, " // the file extension of this media item
					+ MediaItem.DURATION + " INTEGER, " // the duration of this media item
					+ MediaItem.DATE_CREATED + " INTEGER, " // the timestamp when this media item was created
					+ MediaItem.SPAN_FRAMES + " INTEGER, " // whether this media item spans multiple frames
					+ MediaItem.DELETED + " INTEGER);"); // whether this media item has been deleted
			db.execSQL("CREATE INDEX " + MEDIA_LOCATION + "Index" + MediaItem.INTERNAL_ID + " ON " + MEDIA_LOCATION
					+ "(" + MediaItem.INTERNAL_ID + ");");
			db.execSQL("CREATE INDEX " + MEDIA_LOCATION + "Index" + MediaItem.PARENT_ID + " ON " + MEDIA_LOCATION + "("
					+ MediaItem.PARENT_ID + ");");

			createMediaLinksTable(db);

			db.execSQL("CREATE TABLE " + TEMPLATES_LOCATION + " (" //
					+ NarrativeItem._ID + " INTEGER PRIMARY KEY, " // required for Android Adapters
					+ NarrativeItem.INTERNAL_ID + " TEXT, " // the GUID of this template item
					+ NarrativeItem.SEQUENCE_ID + " INTEGER, " // the displayed ID of this template item
					+ NarrativeItem.DATE_CREATED + " INTEGER, " // the timestamp when this template was created
					+ NarrativeItem.DELETED + " INTEGER);"); // whether this template has been deleted
			db.execSQL("CREATE INDEX " + TEMPLATES_LOCATION + "Index" + NarrativeItem.INTERNAL_ID + " ON "
					+ TEMPLATES_LOCATION + "(" + NarrativeItem.INTERNAL_ID + ");");
		}

		// in a separate function as it's used in both upgrade and creation
		private void createMediaLinksTable(SQLiteDatabase db) {
			db.execSQL("CREATE TABLE IF NOT EXISTS " + MEDIA_LINKS_LOCATION + " (" //
					+ MediaItem._ID + " INTEGER PRIMARY KEY, " // required for Android Adapters
					+ MediaItem.INTERNAL_ID + " TEXT, " // the GUID of the linked media item
					+ MediaItem.PARENT_ID + " TEXT, " // the GUID of the parent this media item is linked to
					+ MediaItem.DELETED + " INTEGER);"); // whether this link has been deleted
			db.execSQL("CREATE INDEX IF NOT EXISTS " + MEDIA_LINKS_LOCATION + "Index" + MediaItem.INTERNAL_ID + " ON "
					+ MEDIA_LINKS_LOCATION + "(" + MediaItem.INTERNAL_ID + ");");
			db.execSQL("CREATE INDEX IF NOT EXISTS " + MEDIA_LINKS_LOCATION + "Index" + MediaItem.PARENT_ID + " ON "
					+ MEDIA_LINKS_LOCATION + "(" + MediaItem.PARENT_ID + ");");
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			Log.i(DebugUtilities.getLogTag(this), "Database upgrade requested from version " + oldVersion + " to "
					+ newVersion);

			// TODO: backup database if necessary (also: check for read only database?)

			// must always check whether the items we're upgrading already exist, just in case a downgrade has occurred
			switch (newVersion) {
				case 2:
					Cursor c = null;
					try {
						c = db.rawQuery("SELECT * FROM " + MEDIA_LOCATION + " LIMIT 0,1", null);
						if (c.getColumnIndex(MediaItem.SPAN_FRAMES) < 0) {
							db.execSQL("ALTER TABLE " + MEDIA_LOCATION + " ADD COLUMN " + MediaItem.SPAN_FRAMES
									+ " INTEGER;");
						}
					} finally {
						if (c != null) {
							c.close();
						}
					}
					createMediaLinksTable(db);
					break;
			}
		}

		@Override
		public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			Log.i(DebugUtilities.getLogTag(this), "Database downgrade requested from version " + oldVersion + " to "
					+ newVersion + " - ignoring.");
		}
	}
}
