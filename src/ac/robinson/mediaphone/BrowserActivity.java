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

package ac.robinson.mediaphone;

import android.database.Cursor;
import android.support.v4.app.LoaderManager;
import android.view.View;
import android.widget.AdapterView;

public abstract class BrowserActivity extends MediaPhoneActivity implements LoaderManager.LoaderCallbacks<Cursor> {
	// for NarrativeAdapter purposes
	abstract public int getScrollState();

	// for NarrativeAdapter purposes
	abstract public boolean isPendingIconsUpdate();

	// for NarrativeAdapter purposes
	abstract public int getFrameAdapterScrollPosition(String narrativeId);

	// for FrameAdapter purposes
	abstract public View getFrameAdapterEmptyView();

	// for FrameAdapter purposes
	abstract public void setFrameAdapterEmptyView(View view);

	// for NarrativeAdapter purposes
	abstract public AdapterView.OnItemClickListener getFrameClickListener();

	// for NarrativeAdapter purposes
	abstract public AdapterView.OnItemLongClickListener getFrameLongClickListener();
}
