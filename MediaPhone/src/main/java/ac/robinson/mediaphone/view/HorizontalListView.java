/*
 * HorizontalListView.java v1.5
 * see: http://www.dev-smart.com/archives/34
 *
 * The MIT License
 * Copyright (c) 2011 Paul Soucy (paul@dev-smart.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package ac.robinson.mediaphone.view;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameAdapter;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.FramesManager;
import ac.robinson.util.ImageCacheUtilities;
import ac.robinson.view.CrossFadeDrawable;
import ac.robinson.view.FastBitmapDrawable;

public class HorizontalListView extends AdapterView<ListAdapter> {

	protected FrameAdapter mAdapter = null;
	private int mLeftViewIndex = -1;
	private int mRightViewIndex = 0;
	private int mDisplayOffset = 0;
	protected int mCurrentX = 0;
	protected int mNextX = 0;
	private int mMaxX = Integer.MAX_VALUE;

	protected Scroller mScroller;
	private GestureDetector mGestureDetector;
	private HorizontalGestureListener mGestureListener = new HorizontalGestureListener();
	private Queue<View> mRemovedViewQueue = new LinkedList<>();
	private OnItemSelectedListener mOnItemSelected;
	private OnItemClickListener mOnItemClicked;
	private OnItemLongClickListener mOnItemLongClicked;

	// for icon updating
	private final Handler mScrollHandler = new ScrollHandler();
	private int mScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
	private boolean mDataChanged = false;
	private boolean mAdapterFirstView = false;
	private int mAdapterFirstViewOffset = 0;
	private static int mFrameWidth = 0; // static to fix scroll positioning bug
	private boolean mPendingIconsUpdate;
	private boolean mIconLoadingComplete;
	private boolean mFingerUp = true;
	private Runnable mLayoutUpdater = new Runnable() {
		@Override
		public void run() {
			requestLayout();
		}
	};

	public HorizontalListView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initView();
	}

	private void initView() {
		resetView();
		mScroller = new Scroller(getContext());
		mGestureDetector = new GestureDetector(getContext(), mGestureListener);
		mGestureDetector.setIsLongpressEnabled(false); // done manually as an Android bug gives the wrong view sometimes
		setOnTouchListener(new FingerTracker());
		setOnItemSelectedListener(new SelectionTracker());
	}

	private void resetView() {
		mLeftViewIndex = -1;
		mRightViewIndex = 0;
		mDisplayOffset = 0;
		mCurrentX = 0;
		mNextX = 0;
		mMaxX = Integer.MAX_VALUE;
		mIconLoadingComplete = false;
	}

	@Override
	public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener listener) {
		mOnItemSelected = listener;
	}

	@Override
	public void setOnItemClickListener(AdapterView.OnItemClickListener listener) {
		mOnItemClicked = listener;
	}

	@Override
	public void setOnItemLongClickListener(AdapterView.OnItemLongClickListener listener) {
		mOnItemLongClicked = listener;
	}

	@Override
	public ListAdapter getAdapter() {
		return mAdapter;
	}

	@Override
	public void setAdapter(ListAdapter adapter) {
		if (mAdapter != null) {
			mAdapter.unregisterDataSetObserver(mDataObserver);
		}

		mRemovedViewQueue.clear();
		mAdapter = (FrameAdapter) adapter; // TODO: check type before casting?
		mAdapter.registerDataSetObserver(mDataObserver);
		mDataObserver.onChanged();
	}

	@Override
	public void addChildrenForAccessibility(ArrayList<View> outChildren) {
		// override this method so that TalkBack can access the child items
		// TODO: improve handling of this issue - make sure that individual frames are spoken in TalkBack
		// TODO: (which would also help improve device testing by making these buttons discoverable)
		// more discussion:
		// - https://stackoverflow.com/questions/30585561/
		// - https://github.com/facebook/react-native/issues/7377
	}

	// a hack so we know when to start one frame in (to hide the add frame icon)
	public void setAdapterFirstView(int viewOffset) {
		mAdapterFirstViewOffset = viewOffset;
		mAdapterFirstView = true;
	}

	@Override
	public void setSelection(int position) {
		// TODO: implement item highlighting
	}

	@Override
	public View getSelectedView() {
		// TODO: implement retrieval of highlighted item
		return null;
	}

	private DataSetObserver mDataObserver = new DataSetObserver() {
		@Override
		public void onChanged() {
			mDataChanged = true;
			requestLayout();
		}

		@Override
		public void onInvalidated() {
			onChanged(); // TODO: do we need this?
		}
	};

	public static int getFrameWidth() {
		return mFrameWidth;
	}

	public int getHorizontalPosition() {
		return mCurrentX;
	}

	private void savePositionToAdapter(int position) {
		if (mAdapter != null) {
			mAdapter.setHorizontalPosition(position);
		}
	}

	private void updatePositionFromAdapter() {
		if (mAdapter != null) {
			mNextX = mAdapter.getHorizontalPosition();
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		if (mAdapter == null) {
			return;
		}

		if (mDataChanged) {
			resetView();
			removeAllViewsInLayout();
			updatePositionFromAdapter();
			mDataChanged = false;
		}

		if (mScroller.computeScrollOffset()) {
			mNextX = mScroller.getCurrX();
		}

		if (mNextX <= 0) {
			mNextX = 0;
			mScroller.forceFinished(true);
		}
		if (mNextX >= mMaxX) {
			mNextX = mMaxX;
			mScroller.forceFinished(true);
		}

		int dx = mCurrentX - mNextX;

		// bugfix for scrolling > 1 view at once; also ensures we don't load *all* frames between current and next x
		boolean movedOutsideBounds = false;
		if (mFrameWidth > 0) {
			int numViewsOffset = dx / mFrameWidth;
			if (Math.abs(numViewsOffset) > 1) {
				if (mLeftViewIndex - numViewsOffset < -1) {
					numViewsOffset = mLeftViewIndex - 1;
				}
				int adapterCount = mAdapter.getCount() - 1;
				if (mRightViewIndex - numViewsOffset > adapterCount) {
					numViewsOffset = mRightViewIndex - adapterCount;
				}
				mLeftViewIndex -= numViewsOffset;
				mRightViewIndex -= numViewsOffset;
				mCurrentX -= numViewsOffset * mFrameWidth;
				dx = mCurrentX - mNextX;
				movedOutsideBounds = true;
			}
		}

		removeNonVisibleItems(dx);
		fillList(dx);
		positionItems(dx);

		mCurrentX = mNextX;
		savePositionToAdapter(mCurrentX);

		if (!mScroller.isFinished()) {
			post(mLayoutUpdater);
			postUpdateFrameIcons(); // TODO: does this have adverse (memory) effects?
		} else {
			updateScrollState(AbsListView.OnScrollListener.SCROLL_STATE_IDLE);
		}

		// this is a hack because the current method ends up binding the wrong views if we move a long way
		if (movedOutsideBounds) {
			Cursor cursor = mAdapter.getCursor();
			if (mLeftViewIndex + 1 >= 0) {
				cursor.moveToPosition(mLeftViewIndex + 1);
				for (int i = 0, n = getChildCount(); i < n; i++) {
					final View view = getChildAt(i);
					mAdapter.bindView(view, this.getContext(), cursor);
					if (!cursor.moveToNext()) {
						break;
					}
				}
			}
		}
	}

	private void addAndMeasureChild(final View child, int viewPos) {
		LayoutParams params = child.getLayoutParams();
		if (params == null) {
			params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
		}

		addViewInLayout(child, viewPos, params, true);
		child.measure(MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(params.height,
				MeasureSpec.AT_MOST));
	}

	private void fillList(final int dx) {
		int edge = 0;
		View child = getChildAt(getChildCount() - 1);
		if (child != null) {
			edge = child.getRight();
		}
		fillListRight(edge, dx);

		edge = 0;
		child = getChildAt(0);
		if (child != null) {
			edge = child.getLeft();
		}
		fillListLeft(edge, dx);
	}

	private void fillListRight(int rightEdge, final int dx) {
		int viewWidth = getWidth();
		int adapterCount = mAdapter.getCount();

		// TODO: is there a better way to show the empty view?
		if (adapterCount == 0) {
			removeAllViewsInLayout(); // newer SDK versions have an extra initial view - stick to our hacky fix for now
			View child = mAdapter.getEmptyView(this);
			addAndMeasureChild(child, -1);
			rightEdge += child.getMeasuredWidth();
			if (mRightViewIndex == -1) {
				mMaxX = mCurrentX + rightEdge - viewWidth;
			}
			mRightViewIndex++;

		} else {

			while (rightEdge + dx < viewWidth && mRightViewIndex < adapterCount) {

				View child = mAdapter.getView(mRightViewIndex, mRemovedViewQueue.poll(), this);
				addAndMeasureChild(child, -1);
				rightEdge += child.getMeasuredWidth();

				if (mRightViewIndex == adapterCount - 1) {
					mMaxX = mCurrentX + rightEdge - viewWidth;
				}
				mRightViewIndex++;
			}
		}

		// fix to fill from left when not full
		if (mMaxX < 0 || (mAdapter.getShowKeyFrames() && mMaxX < mFrameWidth)) {
			mMaxX = (mAdapter.getShowKeyFrames() ? mFrameWidth : 0);
		}
	}

	private void fillListLeft(int leftEdge, final int dx) {
		int childWidth;
		while (leftEdge + dx > 0 && mLeftViewIndex >= 0) {
			View child = mAdapter.getView(mLeftViewIndex, mRemovedViewQueue.poll(), this);
			addAndMeasureChild(child, 0);
			childWidth = child.getMeasuredWidth();
			leftEdge -= childWidth;
			mLeftViewIndex--;
			mDisplayOffset -= childWidth;
		}
	}

	private void removeNonVisibleItems(final int dx) {
		View child = getChildAt(0);
		while (child != null && child.getRight() + dx <= 0) {
			mDisplayOffset += child.getMeasuredWidth();
			mRemovedViewQueue.offer(child);
			removeViewInLayout(child);
			mLeftViewIndex++;
			child = getChildAt(0);
		}

		child = getChildAt(getChildCount() - 1);
		int viewWidth = getWidth();
		while (child != null && child.getLeft() + dx >= viewWidth) {
			mRemovedViewQueue.offer(child);
			removeViewInLayout(child);
			mRightViewIndex--;
			child = getChildAt(getChildCount() - 1);
		}
	}

	private void positionItems(final int dx) {
		if (getChildCount() > 0) {
			// so that the start add frame buttons are hidden initially for a better appearance
			if (mAdapterFirstView) {
				if (mFrameWidth <= 0) {
					mFrameWidth = 0;
					View child = getChildAt(0);
					if (child != null) {
						mFrameWidth = child.getMeasuredWidth();
					}
				}
				if (mFrameWidth > 0) {
					final int adapterOffset = mAdapterFirstViewOffset;
					mAdapterFirstViewOffset = -1;
					mAdapterFirstView = false;
					if (adapterOffset >= 0) { // reload position on rotate
						mCurrentX = adapterOffset;
						mNextX = adapterOffset;
						mScroller.startScroll(mNextX, 0, 0, 0); // use the same code to check not going past the end
						post(mLayoutUpdater);
					} else if (mAdapter.getShowKeyFrames()) {
						mCurrentX = mFrameWidth;
						mNextX = mFrameWidth;
					} else {
						mCurrentX = 0;
						mNextX = 0;
					}
				}
			}

			mDisplayOffset += dx;
			int left = mDisplayOffset;
			for (int i = 0; i < getChildCount(); i++) {
				View child = getChildAt(i);
				int childWidth = child.getMeasuredWidth();
				child.layout(left, 0, left + childWidth, child.getMeasuredHeight());
				left += childWidth + child.getPaddingRight();
			}
		}
	}

	public synchronized void scrollTo(int x) {
		// could use version below, but then we'd have to hardcode the default duration
		mScroller.forceFinished(true);
		mScroller.startScroll(mNextX, 0, x - mNextX, 0);
		requestLayout();
	}

	public synchronized void scrollTo(int x, int duration) {
		mScroller.forceFinished(true);
		mScroller.startScroll(mNextX, 0, x - mNextX, 0, duration);
		requestLayout();
	}

	// scrolls to a particular frameId and puts it in the centre of the screen
	// TODO: this will not work properly when mChildWidth is 0 (i.e. when getShowKeyFrames() is false)
	public synchronized void scrollTo(String frameInternalId) {
		Cursor cursor = mAdapter.getCursor();
		if (cursor.isClosed()) {
			return; // seen a crash report due to the cursor being closed - this is a possible fix, but untested
		}
		int newPosition = 0;
		boolean foundItem = false;
		int columnIndex = cursor.getColumnIndexOrThrow(FrameItem.INTERNAL_ID);
		for (boolean hasItem = cursor.moveToFirst(); hasItem; hasItem = cursor.moveToNext()) {
			if (cursor.getString(columnIndex).equals(frameInternalId)) {
				foundItem = true;
				break;
			}
			newPosition += mFrameWidth;
		}
		if (foundItem) {
			newPosition -= Math.round((getWidth() - mFrameWidth) / 2f);
			int minX = (mAdapter.getShowKeyFrames() ? mFrameWidth : 0);
			if (newPosition < minX) {
				newPosition = minX;
			} else {
				int xMax = getMaxFlingX();
				if (newPosition > xMax) {
					// check that we don't scroll past the start if we're smaller than the width
					if ((cursor.getCount() - (mAdapter.getShowKeyFrames() ? 2 : 0)) * mFrameWidth > getWidth()) {
						newPosition = xMax;
					} else {
						newPosition = minX;
					}
				}
			}
			scrollTo(newPosition, 0);
		}
	}

	public int getMaxFlingX() {
		return getMaxFlingX(mAdapter.getShowKeyFrames());
	}

	public int getMaxFlingX(boolean hideKeyFrames) {
		int xMax = mMaxX - (hideKeyFrames ? mFrameWidth : 0);
		if (mMaxX == Integer.MAX_VALUE && mFrameWidth > 0) {
			// not yet measured properly; -1 for the add frame icon at the end (start is done separately)
			// negatives not a problem
			xMax = ((mAdapter.getCount() - (hideKeyFrames ? 1 : 0)) * mFrameWidth) - getWidth();
		}
		return xMax;
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		int action = e.getAction() & MotionEvent.ACTION_MASK;
		switch (action) {
			case MotionEvent.ACTION_DOWN:
				mGestureListener.setPrimaryPointer(e, true);
				break;

			case MotionEvent.ACTION_POINTER_DOWN:
				mGestureListener.setSecondaryPointer(e, true);
				mGestureListener.onDown(e);
				break;

			case MotionEvent.ACTION_MOVE:
				mGestureListener.setMostRecentPoint(e);
				break;

			case MotionEvent.ACTION_CANCEL:
				mGestureListener.cancelTouch(e);
				break;

			default:
				break;
		}

		boolean handled = mGestureDetector.onTouchEvent(e);

		switch (action) {
			case MotionEvent.ACTION_UP:
				if (mGestureListener.getTwoFingerPressed()) {
					mGestureListener.onSingleTapUp(e); // fake a two-finger press so that different finger ups work
				}
				mGestureListener.setPrimaryPointer(e, false);
				break;

			case MotionEvent.ACTION_POINTER_UP:
				mGestureListener.setTwoFingerPressed(true);
				mGestureListener.setSecondaryPointer(e, false);
				postDelayed(mTwoFingerPressEnder, MediaPhone.TWO_FINGER_PRESS_INTERVAL); // 2 in this time to two-press
				break;
		}

		return handled;
	}

	// a hack to deal with events getting the wrong view on long press
	private Runnable mLongPressSender = new Runnable() {
		@Override
		public void run() {
			mGestureListener.setLongPress();
		}
	};

	// a hack to deal with events getting the wrong view on two-finger press
	private Runnable mTwoFingerPressEnder = new Runnable() {
		@Override
		public void run() {
			mGestureListener.setTwoFingerPressed(false);
			mGestureListener.setSecondaryPointer(null, false);
		}
	};

	private class HorizontalGestureListener implements GestureDetector.OnGestureListener {

		// could use arrays with pointer index for these, but this is easier to understand at this stage
		private boolean mPrimaryPointerDown = false;
		private boolean mSecondaryPointerDown = false;
		private String mInitialPrimaryId = null;
		private MotionEvent mMostRecentPrimaryEvent = null;
		private String mInitialSecondaryId = null;
		private MotionEvent mMostRecentSecondaryEvent = null;
		private boolean mLongPressed = false;
		private boolean mTwoFingerPressed = false;

		private void setFrameSelectedState(View view, int resourceId, boolean selected) {
			if (view instanceof PressableRelativeLayout) {
				if (mAdapter.getSelectAllFramesAsOne()) {
					for (int i = 0, n = getChildCount(); i < n; i++) {
						View child = getChildAt(i);
						PressableRelativeLayout currentFrame = (PressableRelativeLayout) child;
						if (currentFrame != null) { // needed in case a frame is deleted while they're pressing
							currentFrame.setPressedIcon(resourceId);
							currentFrame.setPressed(selected);
						}
					}
				} else {
					PressableRelativeLayout currentFrame = (PressableRelativeLayout) view;
					if (currentFrame != null) { // needed in case a frame is deleted while they're pressing
						currentFrame.setPressedIcon(resourceId);
						currentFrame.setPressed(selected);
					}
				}
			}
		}

		public void resetPressState() {
			for (int i = 0, n = getChildCount(); i < n; i++) {
				setFrameSelectedState(getChildAt(i), -1, false); // -1 so we can use int rather than Integer
			}
			mInitialPrimaryId = null;
			if (mMostRecentPrimaryEvent != null) {
				mMostRecentPrimaryEvent.recycle();
			}
			mMostRecentPrimaryEvent = null;
			mPrimaryPointerDown = false;
			mInitialSecondaryId = null;
			if (mMostRecentSecondaryEvent != null) {
				mMostRecentSecondaryEvent.recycle();
			}
			mMostRecentSecondaryEvent = null;
			mSecondaryPointerDown = false;
		}

		public void setPrimaryPointer(MotionEvent e, boolean isDown) {
			if (isDown) {
				mLongPressed = false;
				mTwoFingerPressed = false;
				mInitialPrimaryId = getSelectedFrameInternalId(e, 0);
				mMostRecentPrimaryEvent = MotionEvent.obtain(e);
			} else {
				mInitialPrimaryId = null;
				if (mMostRecentPrimaryEvent != null) {
					mMostRecentPrimaryEvent.recycle();
				}
				mMostRecentPrimaryEvent = null;
				resetPressState();
			}
			mPrimaryPointerDown = isDown;
		}

		public void setSecondaryPointer(MotionEvent e, boolean isDown) {
			if (isDown) {
				mLongPressed = false;
				mTwoFingerPressed = true; // second pointer = starting a two-finger press, so shouldn't do normal events
				mInitialSecondaryId = getSelectedFrameInternalId(e, 1);
				mMostRecentSecondaryEvent = MotionEvent.obtain(e);
			} else if (!mTwoFingerPressed) {
				mInitialSecondaryId = null;
				if (mMostRecentSecondaryEvent != null) {
					mMostRecentSecondaryEvent.recycle();
				}
				mMostRecentSecondaryEvent = null;
				resetPressState();
			}
			mSecondaryPointerDown = isDown;
		}

		// see: http://stackoverflow.com/a/9020291
		public void setMostRecentPoint(MotionEvent e) {
			for (int p = 0, n = e.getPointerCount(); p < n; p++) {
				if (mPrimaryPointerDown && e.getPointerId(p) == 0) {
					mMostRecentPrimaryEvent = MotionEvent.obtain(e);
				}
				if (mSecondaryPointerDown && e.getPointerId(p) == 1) {
					mMostRecentSecondaryEvent = MotionEvent.obtain(e);
				}
			}
		}

		public void cancelTouch(MotionEvent e) {
			resetPressState();
		}

		public void setLongPress() {
			mLongPressed = true;
			int primaryViewId = getSelectedChildIndex(mMostRecentPrimaryEvent, 0); // so we get the view and id as well
			if (primaryViewId < 0 || FrameItem.KEY_FRAME_ID_END.equals(mInitialPrimaryId) ||
					FrameItem.KEY_FRAME_ID_START.equals(mInitialPrimaryId)) {
				return;
			}
			View primaryView = getChildAt(primaryViewId);
			String primaryId = getSelectedFrameInternalId(primaryView);

			if (mPrimaryPointerDown && mInitialPrimaryId != null && mInitialPrimaryId.equals(primaryId)) {
				if (mSecondaryPointerDown) {
					int secondaryViewId = getSelectedChildIndex(mMostRecentSecondaryEvent, 1);
					if (secondaryViewId < 0 || FrameItem.KEY_FRAME_ID_END.equals(mInitialSecondaryId) ||
							FrameItem.KEY_FRAME_ID_START.equals(mInitialSecondaryId)) {
						return;
					}
					View secondaryView = getChildAt(secondaryViewId);
					String secondaryId = getSelectedFrameInternalId(secondaryView);

					if (mInitialSecondaryId != null && mInitialSecondaryId.equals(secondaryId) &&
							Math.abs(primaryViewId - secondaryViewId) == 1 && !FrameItem.KEY_FRAME_ID_END.equals(secondaryId) &&
							!FrameItem.KEY_FRAME_ID_START.equals(secondaryId)) {

						performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); // vibrate to indicate long press
						resetPressState();

						// this is a hack to pass both ids in a standard event handler
						int minId = Math.min(primaryViewId, secondaryViewId);
						if (mOnItemLongClicked != null) {
							mOnItemLongClicked.onItemLongClick(HorizontalListView.this, (
									minId == primaryViewId ? primaryView : secondaryView), mLeftViewIndex + 1 + minId, 1);
							sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
						}
					}
				} else {
					performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); // vibrate to indicate long press
					resetPressState();
					if (mOnItemLongClicked != null) {
						mOnItemLongClicked.onItemLongClick(HorizontalListView.this, primaryView, mLeftViewIndex + 1 +
								primaryViewId, 0); // 0 for id so we can pass 1 or 2 views via a single handler
						sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
					}
				}
			}
		}

		public void setTwoFingerPressed(boolean twoFingerPressed) {
			mTwoFingerPressed = twoFingerPressed;
		}

		public boolean getTwoFingerPressed() {
			return mTwoFingerPressed;
		}

		private String getSelectedFrameInternalId(MotionEvent e, int pointerIndex) {
			int selectedView = getSelectedChildIndex(e, pointerIndex);
			if (selectedView >= 0) {
				return getSelectedFrameInternalId(getChildAt(selectedView));
			}
			return null;
		}

		private String getSelectedFrameInternalId(View view) {
			FrameViewHolder holder = getSelectedFrameViewHolder(view);
			if (holder != null) {
				return TextUtils.isEmpty(holder.frameInternalId) ? null : new String(holder.frameInternalId);
			}
			return null;
		}

		private FrameViewHolder getSelectedFrameViewHolder(View view) {
			if (view != null) {
				if (view.getTag() instanceof FrameViewHolder) {
					return (FrameViewHolder) view.getTag();
				}
			}
			return null;
		}

		private int getSelectedChildIndex(MotionEvent e, int pointerIndex) {
			if (e == null || pointerIndex < 0) {
				return -1;
			}
			Rect viewRect = new Rect();
			for (int i = 0, n = getChildCount(); i < n; i++) {
				View child = getChildAt(i);
				viewRect.set(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
				if (viewRect.contains((int) e.getX(pointerIndex), (int) e.getY(pointerIndex))) {
					return i;
				}
			}
			return -1;
		}

		@Override
		public boolean onDown(MotionEvent e) {
			mScroller.forceFinished(true);
			updateScrollState(AbsListView.OnScrollListener.SCROLL_STATE_IDLE);

			// so that we always have to hold the full length for a long press
			HorizontalListView.this.removeCallbacks(mLongPressSender);
			HorizontalListView.this.postDelayed(mLongPressSender, ViewConfiguration.getLongPressTimeout());

			// multiple frames
			if (!mAdapter.getSelectAllFramesAsOne() && e.getPointerCount() == 2) {
				int primaryChildIndex = getSelectedChildIndex(e, 0);
				int secondaryChildIndex = getSelectedChildIndex(e, 1);

				if (primaryChildIndex >= 0 && secondaryChildIndex >= 0 &&
						Math.abs(primaryChildIndex - secondaryChildIndex) == 1) {
					View primaryView = getChildAt(primaryChildIndex);
					View secondaryView = getChildAt(secondaryChildIndex);

					if (primaryView != null && secondaryView != null) {

						FrameViewHolder primaryHolder = getSelectedFrameViewHolder(primaryView);
						FrameViewHolder secondaryHolder = getSelectedFrameViewHolder(secondaryView);

						if (primaryHolder != null && secondaryHolder != null) {

							if (!FrameItem.KEY_FRAME_ID_START.equals(primaryHolder.frameInternalId) &&
									!FrameItem.KEY_FRAME_ID_END.equals(primaryHolder.frameInternalId) &&
									!FrameItem.KEY_FRAME_ID_START.equals(secondaryHolder.frameInternalId) &&
									!FrameItem.KEY_FRAME_ID_END.equals(secondaryHolder.frameInternalId)) {

								setFrameSelectedState(primaryView, (primaryChildIndex >
										secondaryChildIndex ? PressableRelativeLayout.EDIT_ICON_RIGHT :
										PressableRelativeLayout.EDIT_ICON_LEFT), true);
								setFrameSelectedState(secondaryView, (primaryChildIndex >
										secondaryChildIndex ? PressableRelativeLayout.EDIT_ICON_LEFT :
										PressableRelativeLayout.EDIT_ICON_RIGHT), true);
							}
						}
					}
				}

			} else if (e.getPointerCount() == 1) {

				// single frame
				int selectedChild = getSelectedChildIndex(e, 0); // e.getPointerId(e.getActionIndex())
				if (selectedChild >= 0) {
					View child = getChildAt(selectedChild);
					// setFrameSelectedState(child, mAdapter.getSelectAllFramesAsOne() ? 0
					// : PressableRelativeLayout.PLAY_ICON, true);
					setFrameSelectedState(child, 0, true);
					if (mOnItemSelected != null) {
						mOnItemSelected.onItemSelected(HorizontalListView.this, child,
								mLeftViewIndex + 1 + selectedChild, mAdapter.getItemId(mLeftViewIndex + 1 + selectedChild));
						sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
					}
				}
			}

			return true;
		}

		@Override
		public void onShowPress(MotionEvent e) {
		}

		@Override
		public boolean onSingleTapUp(MotionEvent e) {
			int selectedChild = getSelectedChildIndex(e, 0);
			if (selectedChild >= 0 && mOnItemClicked != null) {

				View child = getChildAt(selectedChild);
				if (!mTwoFingerPressed && !mLongPressed) {
					// 0 for multiple views in same handler - was mAdapter.getItemId(mLeftViewIndex + 1 + selectedChild)
					playSoundEffect(SoundEffectConstants.CLICK); // play the default button click (respects prefs)
					mOnItemClicked.onItemClick(HorizontalListView.this, child, mLeftViewIndex + 1 + selectedChild, 0);
					sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);

				} else if (!mAdapter.getSelectAllFramesAsOne() && mTwoFingerPressed) {
					String primaryId = getSelectedFrameInternalId(child);
					int secondaryViewId = getSelectedChildIndex(mMostRecentSecondaryEvent, 1);
					// same time tap - ids are swapped
					if (primaryId != null && primaryId.equals(mInitialSecondaryId)) {
						secondaryViewId = getSelectedChildIndex(mMostRecentPrimaryEvent, 0);
						mInitialSecondaryId = mInitialPrimaryId;
					}
					if (mInitialSecondaryId != null && !FrameItem.KEY_FRAME_ID_END.equals(primaryId) &&
							!FrameItem.KEY_FRAME_ID_START.equals(primaryId) &&
							!FrameItem.KEY_FRAME_ID_END.equals(mInitialSecondaryId) &&
							!FrameItem.KEY_FRAME_ID_START.equals(mInitialSecondaryId) &&
							Math.abs(selectedChild - secondaryViewId) == 1) {
						View secondaryView = getChildAt(secondaryViewId);

						// this is a hack to pass both ids in a standard event handler
						int minId = Math.min(selectedChild, secondaryViewId);
						playSoundEffect(SoundEffectConstants.CLICK); // play the default button click (respects prefs)
						mOnItemClicked.onItemClick(HorizontalListView.this, (minId == selectedChild ? child : secondaryView),
								mLeftViewIndex + 1 + minId, 1);
						sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
					}
				}

				// save the position
				FrameViewHolder holder = getSelectedFrameViewHolder(child);
				if (holder != null) {
					if (FrameItem.KEY_FRAME_ID_START.equals(holder.frameInternalId)) {
						synchronized (HorizontalListView.this) {
							savePositionToAdapter(mFrameWidth);
						}
					} else if (FrameItem.KEY_FRAME_ID_END.equals(holder.frameInternalId)) {
						synchronized (HorizontalListView.this) {
							savePositionToAdapter(getMaxFlingX());
						}
					} else {
						// TODO: fix this
						// holder.loader.setVisibility(View.VISIBLE);
					}
				}
			}
			resetPressState();
			mTwoFingerPressed = false;
			mLongPressed = false;
			return true;
		}

		@Override
		public void onLongPress(MotionEvent e) {
			// Android has a ridiculous long press bug where it doesn't finish the event - long press to open the edit
			// screen, then press back: it thinks you're now pressing the secondary pointer, hence why we have to do
			// this stupid workaround. It is not related to the parent filtering the touches, unfortunately.
		}

		@Override
		public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
			// this is an alternative to the x/y scroll code in NarrativesListView - probably more reliable
			// getParent().requestDisallowInterceptTouchEvent(true);
			if (!mTwoFingerPressed) {
				resetPressState();
			}
			updateScrollState(AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
			synchronized (HorizontalListView.this) {
				mNextX += (int) distanceX;
			}
			requestLayout();
			return true;
		}

		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
			resetPressState();
			updateScrollState(AbsListView.OnScrollListener.SCROLL_STATE_FLING);

			// fling, but hide the add frame icons if necessary; velocity is pixels per second
			synchronized (HorizontalListView.this) {
				int xMax = getMaxFlingX();
				boolean leftEdge = mCurrentX <= (mAdapter.getShowKeyFrames() ? mFrameWidth : 0);
				boolean rightEdge = mCurrentX >= xMax;
				if (!leftEdge && !rightEdge && Math.abs(velocityX) > getWidth() * MediaPhone.FLING_TO_END_MINIMUM_RATIO) {
					if (velocityX < 0) {
						mNextX = xMax;
					} else {
						mNextX = (mAdapter.getShowKeyFrames() ? mFrameWidth : 0);
					}
					scrollTo(mNextX);
					return true;
				} else {
					mScroller.fling(mNextX, 0, (int) -velocityX, 0, (leftEdge ? 0 : (mAdapter.getShowKeyFrames() ? mFrameWidth :
							0)), (rightEdge ? mMaxX : xMax), 0, 0);
				}
			}
			requestLayout();
			return true;
		}
	}

	public int getScrollState() {
		return mScrollState; // for FrameAdapter purposes
	}

	public boolean isPendingIconsUpdate() {
		return mPendingIconsUpdate; // for FrameAdapter purposes
	}

	public boolean isIconLoadingComplete() {
		boolean iconLoadingComplete;
		synchronized (HorizontalListView.this) {
			iconLoadingComplete = mIconLoadingComplete;
		}
		return iconLoadingComplete;
	}

	private void updateFrameIcons() {
		mPendingIconsUpdate = false;
		boolean iconLoadingComplete = true;

		Resources resources = getContext().getResources();
		ContentResolver contentResolver = getContext().getContentResolver();
		for (int i = 0, n = getChildCount(); i < n; i++) {
			final View view = getChildAt(i);
			final FrameViewHolder holder = (FrameViewHolder) view.getTag();
			if (holder.queryIcon) {
				// if the icon has gone missing due to, e.g., cache deletion, regenerate it
				FastBitmapDrawable cachedIcon = ImageCacheUtilities.getCachedIcon(MediaPhone.DIRECTORY_THUMBS,
						FrameItem.getCacheId(holder.frameInternalId), ImageCacheUtilities.NULL_DRAWABLE);
				if (ImageCacheUtilities.LOADING_DRAWABLE.equals(cachedIcon)) {
					iconLoadingComplete = false;
					holder.loader.setVisibility(View.VISIBLE);
					holder.display.setImageDrawable(mAdapter.getLoadingIcon());
					holder.queryIcon = true;
					continue; // this icon hasn't yet been updated
				} else if (ImageCacheUtilities.NULL_DRAWABLE.equals(cachedIcon)) {
					FramesManager.reloadFrameIcon(resources, contentResolver, holder.frameInternalId);
					cachedIcon = ImageCacheUtilities.getCachedIcon(MediaPhone.DIRECTORY_THUMBS,
							FrameItem.getCacheId(holder.frameInternalId), mAdapter
							.getDefaultIcon());
				}

				CrossFadeDrawable d = holder.transition;
				d.setEnd(cachedIcon.getBitmap());
				holder.display.setImageDrawable(d);
				holder.loader.setVisibility(View.GONE);
				d.startTransition(MediaPhone.ANIMATION_FADE_TRANSITION_DURATION);
				holder.queryIcon = false;
			}
		}

		synchronized (HorizontalListView.this) {
			mIconLoadingComplete = iconLoadingComplete;
		}

		invalidate();
	}

	public synchronized void postUpdateFrameIcons() {
		mPendingIconsUpdate = true;
		Handler handler = mScrollHandler;
		handler.removeMessages(R.id.msg_update_frame_icons);
		Message message = handler.obtainMessage(R.id.msg_update_frame_icons, HorizontalListView.this);
		handler.sendMessage(message);
	}

	private void updateScrollState(int scrollState) {
		if (mScrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING &&
				scrollState != AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
			mPendingIconsUpdate = true;
			final Handler handler = mScrollHandler;
			handler.removeMessages(R.id.msg_update_frame_icons);
			final Message message = handler.obtainMessage(R.id.msg_update_frame_icons, HorizontalListView.this);
			handler.sendMessageDelayed(message, mFingerUp ? 0 : MediaPhone.ANIMATION_ICON_SHOW_DELAY);
		} else if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
			mPendingIconsUpdate = false;
			mScrollHandler.removeMessages(R.id.msg_update_frame_icons);
		}
		mScrollState = scrollState;
	}

	private static class ScrollHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_update_frame_icons:
					((HorizontalListView) msg.obj).updateFrameIcons();
					break;
				default:
					break;
			}
		}
	}

	private class FingerTracker implements View.OnTouchListener {
		public boolean onTouch(View view, MotionEvent event) {
			final int action = event.getAction();
			mFingerUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
			if (mFingerUp && mScrollState != AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
				postUpdateFrameIcons();
			}
			return false;
		}
	}

	private class SelectionTracker implements AdapterView.OnItemSelectedListener {
		public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
			if (mScrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
				mScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
				postUpdateFrameIcons();
			}
		}

		public void onNothingSelected(AdapterView<?> adapterView) {
		}
	}
}
