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

import ac.robinson.mediaphone.BrowserActivity;
import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.view.FrameViewHolder;
import ac.robinson.mediaphone.view.HorizontalListView;
import ac.robinson.mediaphone.view.NarrativeViewHolder;
import ac.robinson.mediaphone.view.PressableRelativeLayout;
import ac.robinson.util.ImageCacheUtilities;
import ac.robinson.view.CrossFadeDrawable;
import ac.robinson.view.FastBitmapDrawable;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.CursorAdapter;
import android.widget.Filter;
import android.widget.FilterQueryProvider;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.ProgressBar;

public class FrameAdapter extends CursorAdapter implements FilterQueryProvider {

	// TODO: switch to Loader like in NarrativeAdapter (and load these at query time)
	private final int mInternalIdIndex;
	private final int mParentIdIndex;

	private final BrowserActivity mActivity;
	private final LayoutInflater mInflater;
	private NarrativeViewHolder mParentHolder;

	private final Bitmap mDefaultIconBitmap;
	private final FastBitmapDrawable mDefaultIcon;
	private FastBitmapDrawable mLoadingIcon;

	private final Filter mFilter;
	private final String mSelectionParentId;
	private final String mSelectionKeyFrameStart;
	private final String mSelectionKeyFrameEnd;
	private String mSelection;

	private String mParentFilter = null;

	private final String[] mFilterArguments0 = new String[0];
	private final String[] mFilterArguments1 = new String[1];

	private int mHorizontalPosition = 0;
	private boolean mShowKeyFrames = true;
	private boolean mSelectAllFramesAsOne = false;

	public FrameAdapter(BrowserActivity activity, String parentId) {
		super(activity, activity.managedQuery(FrameItem.CONTENT_URI, FrameItem.PROJECTION_ALL, "1=?",
				new String[] { "0" }, null), true); // hack to show no data initially

		mActivity = activity;
		mInflater = LayoutInflater.from(activity);
		mFilter = ((Filterable) this).getFilter();

		final Cursor c = getCursor();
		mInternalIdIndex = c.getColumnIndexOrThrow(FrameItem.INTERNAL_ID);
		mParentIdIndex = c.getColumnIndexOrThrow(FrameItem.PARENT_ID);

		// alternative (without frame): Bitmap.createBitmap(1, 1,
		// ImageCacheUtilities.mBitmapFactoryOptions.inPreferredConfig);
		mDefaultIconBitmap = FrameItem.loadTemporaryIcon(activity.getResources(), false);
		mDefaultIcon = new FastBitmapDrawable(mDefaultIconBitmap);

		final StringBuilder selection = new StringBuilder();
		selection.append(FrameItem.INTERNAL_ID);
		selection.append("='" + FrameItem.KEY_FRAME_ID_START + "' OR ");
		mSelectionKeyFrameStart = selection.toString();

		selection.setLength(0);
		selection.append(FrameItem.INTERNAL_ID);
		selection.append("='" + FrameItem.KEY_FRAME_ID_END + "' OR ");
		mSelectionKeyFrameEnd = selection.toString();

		selection.setLength(0);
		selection.append("(");
		selection.append(FrameItem.DELETED);
		selection.append("=0 AND ");
		selection.append(FrameItem.PARENT_ID);
		selection.append("=?");
		selection.append(")");
		mSelectionParentId = selection.toString();

		setFilterQueryProvider(this);

		mParentFilter = parentId;
		reFilter();
	}

	public FastBitmapDrawable getDefaultIcon() {
		return mDefaultIcon;
	}

	public FastBitmapDrawable getLoadingIcon() {
		if (mLoadingIcon == null) {
			if (mActivity != null) {
				mLoadingIcon = new FastBitmapDrawable(FrameItem.loadTemporaryIcon(mActivity.getResources(), true));
			} else {
				mLoadingIcon = mDefaultIcon;
			}
		}
		return mLoadingIcon;
	}

	private void reFilter() {
		mFilter.filter(null);
	}

	public void setParentHolder(NarrativeViewHolder parentHolder) {
		mParentHolder = parentHolder;
	}

	public NarrativeViewHolder getParentHolder() {
		return mParentHolder;
	}

	public void setShowKeyFrames(boolean showKeyFrames) {
		if (showKeyFrames != mShowKeyFrames) {
			mShowKeyFrames = showKeyFrames;
			reFilter();
		}
	}

	public boolean getShowKeyFrames() {
		return mShowKeyFrames;
	}

	public void setSelectAllFramesAsOne(boolean selectAllFramesAsOne) {
		mSelectAllFramesAsOne = selectAllFramesAsOne;
	}

	public boolean getSelectAllFramesAsOne() {
		return mSelectAllFramesAsOne;
	}

	public String getParentFilter() {
		return mParentFilter;
	}

	public void setHorizontalPosition(int horizontalPosition) {
		mHorizontalPosition = horizontalPosition;
	}

	public int getHorizontalPosition() {
		return mHorizontalPosition;
	}

	public View newView(Context context, Cursor cursor, ViewGroup parent) {
		final View view = mInflater.inflate(R.layout.frame_item, parent, false);

		FrameViewHolder holder = new FrameViewHolder();
		holder.display = (ImageView) view.findViewById(R.id.frame_item_image);
		holder.loader = (ProgressBar) view.findViewById(R.id.frame_item_load_progress);
		view.setTag(holder);

		final CrossFadeDrawable transition = new CrossFadeDrawable(mDefaultIconBitmap, null);
		transition.setCallback(view);
		transition.setCrossFadeEnabled(false);
		holder.transition = transition;

		return view;
	}

	public void bindView(View view, Context context, Cursor cursor) {
		final FrameViewHolder holder = (FrameViewHolder) view.getTag();

		holder.frameInternalId = cursor.getString(mInternalIdIndex);
		holder.frameParentId = cursor.getString(mParentIdIndex);

		String mediaCacheId = FrameItem.getCacheId(holder.frameInternalId);

		if (FrameItem.KEY_FRAME_ID_START.equals(holder.frameInternalId)
				|| FrameItem.KEY_FRAME_ID_END.equals(holder.frameInternalId)) {
			holder.display.setImageResource(R.drawable.ic_narratives_add);
			// holder.display.setBackgroundResource(R.drawable.button_white_small);
			holder.loader.setVisibility(View.GONE);
			holder.queryIcon = false;

		} else {
			final HorizontalListView list = mParentHolder.frameList;
			// holder.display.setBackgroundResource(R.drawable.frame_item);
			if (list.getScrollState() == AbsListView.OnScrollListener.SCROLL_STATE_FLING || list.isPendingIconsUpdate()) {
				holder.loader.setVisibility(View.VISIBLE);
				holder.display.setImageDrawable(mDefaultIcon);
				holder.queryIcon = true;
			} else {
				// if the icon has gone missing (recently imported or cache deletion), regenerate it
				// this will happen on every new frame, but we check for media before generation, so not too bad
				FastBitmapDrawable cachedIcon = ImageCacheUtilities.getCachedIcon(MediaPhone.DIRECTORY_THUMBS,
						mediaCacheId, ImageCacheUtilities.NULL_DRAWABLE);
				if (ImageCacheUtilities.LOADING_DRAWABLE.equals(cachedIcon)) {
					holder.loader.setVisibility(View.VISIBLE);
					holder.display.setImageDrawable(getLoadingIcon());
					holder.queryIcon = true;
					return; // this icon hasn't yet been updated
				} else if (ImageCacheUtilities.NULL_DRAWABLE.equals(cachedIcon)) {
					FramesManager.reloadFrameIcon(mActivity.getResources(), mActivity.getContentResolver(),
							holder.frameInternalId);
					cachedIcon = ImageCacheUtilities.getCachedIcon(MediaPhone.DIRECTORY_THUMBS, mediaCacheId,
							mDefaultIcon);
				}
				holder.display.setImageDrawable(cachedIcon);
				holder.loader.setVisibility(View.GONE);
				holder.queryIcon = false;
			}
		}
	}

	public View getEmptyView(ViewGroup parent) {
		final View emptyView;
		if (mActivity.getFrameAdapterEmptyView() == null) {
			emptyView = newView(parent.getContext(), null, parent);
			mActivity.setFrameAdapterEmptyView(emptyView);
		} else {
			emptyView = mActivity.getFrameAdapterEmptyView();
		}
		final FrameViewHolder viewHolder = (FrameViewHolder) emptyView.getTag();
		viewHolder.frameInternalId = FrameItem.LOADING_FRAME_ID; // so we can detect presses
		viewHolder.display.setVisibility(View.GONE);
		viewHolder.loader.setVisibility(View.VISIBLE);
		emptyView.setPressed(false);
		((PressableRelativeLayout) emptyView).setHighlightOnPress(false);
		return emptyView;
	}

	@Override
	public void changeCursor(Cursor cursor) {
		final Cursor oldCursor = getCursor();
		if (oldCursor != null)
			mActivity.stopManagingCursor(oldCursor);
		super.changeCursor(cursor);
	}

	public Cursor runQuery(CharSequence constraint) {
		final StringBuilder buffer = new StringBuilder();
		final String[] filterArguments;
		if (mShowKeyFrames) {
			buffer.append(mSelectionKeyFrameStart);
			buffer.append(mSelectionKeyFrameEnd);
		}
		if (mParentFilter != null) {
			buffer.append(mSelectionParentId);
			filterArguments = mFilterArguments1;
			filterArguments[0] = mParentFilter;
		} else {
			filterArguments = mFilterArguments0;
		}

		// TODO: sort out projection to only return necessary columns
		mSelection = buffer.toString();
		return mActivity.managedQuery(FrameItem.CONTENT_URI, FrameItem.PROJECTION_ALL, mSelection, filterArguments,
				FrameItem.DEFAULT_SORT_ORDER);
	}
}
