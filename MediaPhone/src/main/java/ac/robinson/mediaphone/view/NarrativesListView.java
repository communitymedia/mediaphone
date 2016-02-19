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

package ac.robinson.mediaphone.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ListView;

public class NarrativesListView extends ListView {

	private float mXDistance, mYDistance, mLastX, mLastY;

	public NarrativesListView(Context context) {
		super(context);
	}

	public NarrativesListView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public NarrativesListView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	// x/y scrolling at the same time - see: http://stackoverflow.com/questions/2646028/
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		switch (ev.getAction()) {
			case MotionEvent.ACTION_DOWN:
				mXDistance = mYDistance = 0;
				mLastX = ev.getX();
				mLastY = ev.getY();
				break;
			case MotionEvent.ACTION_MOVE:
				final float curX = ev.getX();
				final float curY = ev.getY();
				mXDistance += Math.abs(curX - mLastX);
				mYDistance += Math.abs(curY - mLastY);
				mLastX = curX;
				mLastY = curY;
				if (mXDistance > mYDistance) {
					return false;
				}
		}

		return super.onInterceptTouchEvent(ev);
	}
}
