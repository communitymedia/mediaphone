/*
 *  Copyright (C) 2013 Simon Robinson
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

package ac.robinson.mediaphone.view;

import ac.robinson.mediaphone.R;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.RelativeLayout;
import android.widget.SeekBar;

public class SeekBarRelativeLayout extends RelativeLayout {

	private float mXDistance, mYDistance, mLastX, mLastY;
	private int mTouchSlop;
	private boolean mDownSent;
	private SeekBar mSeekBar;

	public SeekBarRelativeLayout(Context context) {
		super(context);
		initialise(context);
	}

	public SeekBarRelativeLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
		initialise(context);
	}

	public SeekBarRelativeLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initialise(context);
	}

	private void initialise(Context context) {
		ViewConfiguration vc = ViewConfiguration.get(context);
		mTouchSlop = vc.getScaledTouchSlop();
	}

	private void sendDuplicateEvent(MotionEvent event, int action) {
		int originalAction = event.getAction();
		event.setAction(action);
		if (mSeekBar == null) {
			mSeekBar = (SeekBar) findViewById(R.id.preference_seek_bar);
		}
		if (mSeekBar != null) {
			mSeekBar.dispatchTouchEvent(event);
		}
		event.setAction(originalAction);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		switch (ev.getAction()) {
			case MotionEvent.ACTION_DOWN:
				mDownSent = false;
				mXDistance = mYDistance = 0;
				mLastX = ev.getX();
				mLastY = ev.getY();

				// setAction doesn't work for the initial event (more likely, it modifies the next event instead...)
				// - here we cancel the down event before it is propagated to the seek bar
				sendDuplicateEvent(ev, MotionEvent.ACTION_CANCEL);
				return false;

			case MotionEvent.ACTION_MOVE:
				mXDistance = Math.abs(ev.getX() - mLastX);
				mYDistance = Math.abs(ev.getY() - mLastY);
				if (mXDistance >= mTouchSlop || mYDistance >= mTouchSlop) {
					if (mYDistance >= mXDistance) {
						return true; // don't pass any more events to the child - we're scrolling vertically
					} else if (!mDownSent) {
						// we're scrolling horizontally - send the initial down action that we cancelled previously
						sendDuplicateEvent(ev, MotionEvent.ACTION_DOWN); // this is now the initial down action
						mDownSent = true;
					}
				} else {
					// could also send a down event here if (ev.getEventTime() - original event's time) is larger than
					// the view configuration's tap timeout, but this causes accidental changes on scroll more often
					sendDuplicateEvent(ev, MotionEvent.ACTION_CANCEL); // don't do the down action on the seek bar
				}
				break;

			case MotionEvent.ACTION_UP:
				mXDistance = Math.abs(ev.getX() - mLastX);
				mYDistance = Math.abs(ev.getY() - mLastY);
				if (!mDownSent && mXDistance < mTouchSlop && mYDistance < mTouchSlop) {
					// they tapped on the bar (note: not checking tap duration here, as we cancelled the original event)
					sendDuplicateEvent(ev, MotionEvent.ACTION_DOWN);
					mDownSent = true;
				}
		}

		return super.onInterceptTouchEvent(ev);
	}

}
