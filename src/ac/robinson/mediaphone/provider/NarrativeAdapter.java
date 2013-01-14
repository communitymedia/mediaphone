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

import java.util.HashMap;

import ac.robinson.mediaphone.BrowserActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.view.HorizontalListView;
import ac.robinson.mediaphone.view.NarrativeViewHolder;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.CursorAdapter;
import android.widget.FilterQueryProvider;
import android.widget.RelativeLayout;

public class NarrativeAdapter extends CursorAdapter implements FilterQueryProvider {
	private final Uri mContentUri;
	private final boolean mShowKeyFrames;
	private final boolean mIsTemplateView;

	private final int mInternalIdIndex;
	private final int mCreationDateIndex;
	private final int mSequenceIdIndex;

	private static String mNarrativeNotDeletedSelection = NarrativeItem.DELETED + "=0";

	private final BrowserActivity mActivity;
	private final LayoutInflater mInflater;

	private FrameAdapter mEmptyAdapter;

	// must *not* be static - will leak on destroy otherwise...
	private final HashMap<String, FrameAdapter> mFrameAdapters = new HashMap<String, FrameAdapter>();

	public NarrativeAdapter(BrowserActivity activity, Uri contentUri, boolean showKeyFrames, boolean isTemplateView) {
		super(activity, activity.managedQuery(contentUri, NarrativeItem.PROJECTION_ALL, mNarrativeNotDeletedSelection,
				null, NarrativeItem.DEFAULT_SORT_ORDER), true);

		mActivity = activity;
		mInflater = LayoutInflater.from(activity);

		mContentUri = contentUri;
		mShowKeyFrames = showKeyFrames;
		mIsTemplateView = isTemplateView;

		final Cursor c = getCursor();
		mInternalIdIndex = c.getColumnIndexOrThrow(NarrativeItem.INTERNAL_ID);
		mCreationDateIndex = c.getColumnIndexOrThrow(NarrativeItem.DATE_CREATED);
		mSequenceIdIndex = c.getColumnIndexOrThrow(NarrativeItem.SEQUENCE_ID);

		// bit of a hack - just a use random id for an item that isn't (probably...) in the database
		mEmptyAdapter = new FrameAdapter(mActivity, MediaPhoneProvider.getNewInternalId());
		mEmptyAdapter.setShowKeyFrames(false);
		mEmptyAdapter.setSelectAllFramesAsOne(false);
	}

	@Override
	public boolean areAllItemsEnabled() {
		return false; // so that individual frames (rather than the whole narratives row) are selectable
	}

	@Override
	public boolean isEnabled(int position) {
		return false; // so that individual frames (rather than the whole narratives row) are selectable
	}

	public HashMap<String, FrameAdapter> getFrameAdapters() {
		return mFrameAdapters;
	}

	public FrameAdapter getEmptyAdapter() {
		return mEmptyAdapter;
	}

	public View newView(Context context, Cursor cursor, ViewGroup parent) {
		final RelativeLayout view = (RelativeLayout) mInflater.inflate(R.layout.frame_browser, parent, false);

		NarrativeViewHolder holder = new NarrativeViewHolder();
		// TODO: soft references to the list view? delete on destroy?
		holder.frameList = (HorizontalListView) view.findViewById(R.id.narrative_list_view);
		view.setTag(holder);

		holder.frameList.setOnItemClickListener(mActivity.getFrameClickListener());
		holder.frameList.setOnItemLongClickListener(mActivity.getFrameLongClickListener());

		return view;
	}

	public void attachAdapter(NarrativeViewHolder holder) {
		// TODO: soft references to the activity? delete on destroy?
		FrameAdapter viewAdapter = mFrameAdapters.get(holder.narrativeInternalId);
		if (viewAdapter == null) {
			viewAdapter = new FrameAdapter(mActivity, holder.narrativeInternalId);
			viewAdapter.setParentHolder(holder);
			viewAdapter.setShowKeyFrames(mShowKeyFrames);
			viewAdapter.setSelectAllFramesAsOne(mIsTemplateView);
			holder.frameList.setAdapterFirstView();
			mFrameAdapters.put(holder.narrativeInternalId, viewAdapter);
		}
		holder.frameList.setAdapter(viewAdapter);
	}

	public void bindView(View view, Context context, Cursor cursor) {
		NarrativeViewHolder holder = (NarrativeViewHolder) view.getTag();

		holder.narrativeInternalId = cursor.getString(mInternalIdIndex);
		holder.narrativeDateCreated = cursor.getLong(mCreationDateIndex);
		holder.narrativeSequenceId = cursor.getInt(mSequenceIdIndex);

		final BrowserActivity activity = mActivity;
		if (activity.getScrollState() == AbsListView.OnScrollListener.SCROLL_STATE_FLING
				|| activity.isPendingIconsUpdate()) {
			holder.queryIcons = true;
			holder.frameList.setAdapter(mEmptyAdapter);
		} else {
			attachAdapter(holder);
			holder.queryIcons = false;
		}

		// alternating row colours
		int cursorPosition = cursor.getPosition();
		if ((cursor.getCount() - cursorPosition) % 2 == 0) { // so the colour stays the same when adding a new narrative
			holder.frameList.setBackgroundResource(mIsTemplateView ? R.color.template_list_dark
					: R.color.narrative_list_dark);
		} else {
			holder.frameList.setBackgroundResource(mIsTemplateView ? R.color.template_list_light
					: R.color.narrative_list_light);
		}
	}

	@Override
	public void changeCursor(Cursor cursor) {
		final Cursor oldCursor = getCursor();
		if (oldCursor != null)
			mActivity.stopManagingCursor(oldCursor);
		super.changeCursor(cursor);
	}

	public Cursor runQuery(CharSequence constraint) {
		// TODO: sort out projection to only return necessary columns
		return mActivity.managedQuery(mContentUri, NarrativeItem.PROJECTION_ALL, mNarrativeNotDeletedSelection, null,
				NarrativeItem.DEFAULT_SORT_ORDER);
	}
}
