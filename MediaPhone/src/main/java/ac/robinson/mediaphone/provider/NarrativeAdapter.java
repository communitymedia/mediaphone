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

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;

import java.util.HashMap;

import ac.robinson.mediaphone.BrowserActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.view.HorizontalListView;
import ac.robinson.mediaphone.view.NarrativeViewHolder;
import androidx.cursoradapter.widget.CursorAdapter;

public class NarrativeAdapter extends CursorAdapter {
	private static int mInternalIdIndex = -1;
	private static int mCreationDateIndex = -1;
	private static int mSequenceIdIndex = -1;

	private final boolean mShowKeyFrames;
	private final boolean mStartScrolledToEnd;
	private final boolean mIsTemplateView;

	private final BrowserActivity mActivity;
	private final LayoutInflater mInflater;

	private FrameAdapter mEmptyAdapter;

	// must *not* be static - will leak on destroy otherwise...
	private final HashMap<String, FrameAdapter> mFrameAdapters = new HashMap<>();

	public NarrativeAdapter(BrowserActivity activity, boolean showKeyFrames, boolean startScrolledToEnd,
							boolean isTemplateView) {
		super(activity, null, 0); // null cursor and no auto querying - we use a loader to manage cursors

		mActivity = activity;
		mInflater = LayoutInflater.from(activity);

		mShowKeyFrames = showKeyFrames;
		mStartScrolledToEnd = startScrolledToEnd;
		mIsTemplateView = isTemplateView;

		// bit of a hack - use "null" for an item id that isn't (probably...) in the database
		mEmptyAdapter = new FrameAdapter(mActivity, "null");
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

	public View newView(Context context, Cursor cursor, ViewGroup parent) {
		final HorizontalListView view = (HorizontalListView) mInflater.inflate(R.layout.frame_browser, parent, false);

		NarrativeViewHolder holder = new NarrativeViewHolder();
		view.setTag(holder);

		view.setOnItemClickListener(mActivity.getFrameClickListener());
		view.setOnItemLongClickListener(mActivity.getFrameLongClickListener());

		return view;
	}

	public void bindView(View view, Context context, Cursor cursor) {
		NarrativeViewHolder holder = (NarrativeViewHolder) view.getTag();

		// only load column indices once
		if (mInternalIdIndex < 0) {
			mInternalIdIndex = cursor.getColumnIndexOrThrow(NarrativeItem.INTERNAL_ID);
			mCreationDateIndex = cursor.getColumnIndexOrThrow(NarrativeItem.DATE_CREATED);
			mSequenceIdIndex = cursor.getColumnIndexOrThrow(NarrativeItem.SEQUENCE_ID);
		}

		holder.narrativeInternalId = cursor.getString(mInternalIdIndex);
		holder.narrativeDateCreated = cursor.getLong(mCreationDateIndex);
		holder.narrativeSequenceId = cursor.getInt(mSequenceIdIndex);

		final BrowserActivity activity = mActivity;
		final HorizontalListView frameList = (HorizontalListView) view;
		if (activity.getScrollState() == AbsListView.OnScrollListener.SCROLL_STATE_FLING || activity.isPendingIconsUpdate()) {
			holder.queryIcons = true;
			frameList.setAdapter(mEmptyAdapter);
		} else {
			attachAdapter(frameList, holder);
			holder.queryIcons = false;
		}

		// alternating row colours
		int cursorPosition = cursor.getPosition();
		if ((cursor.getCount() - cursorPosition) % 2 == 0) { // so the colour stays the same when adding a new narrative
			frameList.setBackgroundResource(mIsTemplateView ? R.color.template_list_dark : R.color.narrative_list_dark);
		} else {
			frameList.setBackgroundResource(mIsTemplateView ? R.color.template_list_light : R.color.narrative_list_light);
		}
	}

	public HashMap<String, FrameAdapter> getFrameAdapters() {
		return mFrameAdapters;
	}

	public FrameAdapter getEmptyAdapter() {
		return mEmptyAdapter;
	}

	public void attachAdapter(HorizontalListView frameList, NarrativeViewHolder holder) {
		// TODO: soft references to the activity? delete on destroy?
		FrameAdapter viewAdapter = mFrameAdapters.get(holder.narrativeInternalId);
		if (viewAdapter == null) {
			viewAdapter = new FrameAdapter(mActivity, holder.narrativeInternalId);
			viewAdapter.setShowKeyFrames(mShowKeyFrames);
			viewAdapter.setHasScrolledToEnd(!mStartScrolledToEnd); // to disable, we set that the scroll has already happened
			viewAdapter.setSelectAllFramesAsOne(mIsTemplateView);
			int scrollPosition = mActivity.getFrameAdapterScrollPosition(holder.narrativeInternalId);
			frameList.setAdapterFirstView(scrollPosition);
			mFrameAdapters.put(holder.narrativeInternalId, viewAdapter);
		}
		frameList.setAdapter(viewAdapter);
	}

	public HashMap<String, Integer> getAdapterScrollPositions() {
		int frameWidth = HorizontalListView.getFrameWidth();
		if (frameWidth > 0) {
			HashMap<String, Integer> scrollPositions = null;
			for (FrameAdapter adapter : mFrameAdapters.values()) {
				int horizontalPosition = adapter.getHorizontalPosition();
				if (horizontalPosition > 0 && horizontalPosition > frameWidth) {
					if (scrollPositions == null) {
						scrollPositions = new HashMap<>();
					}
					scrollPositions.put(adapter.getParentFilter(), adapter.getHorizontalPosition());
					if (scrollPositions.size() > 20) {
						break; // don't save too many positions (TODO: 20 is completely arbitrary...)
					}
				}
			}
			return scrollPositions;
		}
		return null;
	}
}
