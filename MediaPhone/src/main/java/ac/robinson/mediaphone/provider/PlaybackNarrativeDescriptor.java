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

package ac.robinson.mediaphone.provider;

import java.util.LinkedHashMap;

public class PlaybackNarrativeDescriptor {
	public final int mNarrativeImageAdjustment;
	public int mNarrativeStartTime = 0;
	public int mNarrativeDuration = 0;
	public final LinkedHashMap<Integer, String> mTimeToFrameMap = new LinkedHashMap<>(); 

	/**
	 * 
	 * @param imageAdjustment an adjustment, in milliseconds to subtract from the start time of images in order to make
	 *            sure they appear at the right time during playback (due to crossfade). Must be either zero or
	 *            positive.
	 */
	public PlaybackNarrativeDescriptor(int imageAdjustment) {
		mNarrativeImageAdjustment = imageAdjustment;
	}
}
