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

import ac.robinson.view.CrossFadeDrawable;
import android.widget.ImageView;
import android.widget.ProgressBar;

public class FrameViewHolder {
	public String frameInternalId;
	public String frameParentId;
	public ImageView display; // the icon to show
	public ProgressBar loader; // the progress bar to show when loading
	public CrossFadeDrawable transition; // redrawing when coming back into view
	public boolean queryIcon; // if we're currently loading this item's icon
}
