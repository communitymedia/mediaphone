/*
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
import android.view.View;
import android.widget.RelativeLayout;

public class SendToBackRelativeLayout extends RelativeLayout {
	public SendToBackRelativeLayout(Context context) {
		super(context);
	}

	public SendToBackRelativeLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public SendToBackRelativeLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	public void sendChildToBack(View child) {
		int index = indexOfChild(child);
		if (index > 0) {
			detachViewFromParent(index);
			attachViewToParent(child, 0, child.getLayoutParams());
		}
	}
}
