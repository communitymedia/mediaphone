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

import java.util.ArrayList;

import androidx.annotation.NonNull;

public class PlaybackMediaHolder {
	public final String mParentFrameId;
	public final ArrayList<String> mSpanningFrameIds;
	public final String mMediaItemId;

	public final String mMediaPath;
	public final int mMediaType;

	private int mMediaStartTime;
	private int mMediaEndTime;

	private int mPlaybackOffsetStart;
	private int mPlaybackOffsetEnd;

	private int mOriginalDuration;

	public PlaybackMediaHolder(String parentId, String mediaId, String mediaPath, int mediaType, int startTime, int endTime,
							   int playbackOffsetStart, int playbackOffsetEnd, ArrayList<String> linkedFrameIds) {
		mParentFrameId = parentId;
		mMediaItemId = mediaId;
		mMediaPath = mediaPath;
		mMediaType = mediaType;
		mMediaStartTime = startTime;
		mMediaEndTime = endTime;
		mPlaybackOffsetStart = playbackOffsetStart;
		mPlaybackOffsetEnd = playbackOffsetEnd;

		mSpanningFrameIds = linkedFrameIds;
		mSpanningFrameIds.add(0, parentId);

		mOriginalDuration = getDuration();
	}

	public PlaybackMediaHolder(String parentId, String mediaId, String mediaPath, int mediaType, int startTime, int endTime,
							   int playbackOffsetStart, int playbackOffsetEnd) {
		this(parentId, mediaId, mediaPath, mediaType, startTime, endTime, playbackOffsetStart, playbackOffsetEnd,
				new ArrayList<>());
	}

	public PlaybackMediaHolder(PlaybackMediaHolder source, String newSpannedFrameId, int newEndTime, int newPlaybackOffsetEnd) {
		this(source, newSpannedFrameId, newEndTime, source.mPlaybackOffsetStart, newPlaybackOffsetEnd);
	}

	public PlaybackMediaHolder(PlaybackMediaHolder source, String newSpannedFrameId, int newEndTime, int newPlaybackOffsetStart,
							   int newPlaybackOffsetEnd) {
		mParentFrameId = source.mParentFrameId;
		mMediaItemId = source.mMediaItemId;
		mMediaPath = source.mMediaPath;
		mMediaType = source.mMediaType;
		mMediaStartTime = source.mMediaStartTime;

		mMediaEndTime = newEndTime;
		mPlaybackOffsetStart = newPlaybackOffsetStart;
		mPlaybackOffsetEnd = newPlaybackOffsetEnd;

		mSpanningFrameIds = source.mSpanningFrameIds;
		if (newSpannedFrameId != null) {
			mSpanningFrameIds.add(newSpannedFrameId);
		}
	}

	public void setStartTime(int startTime) {
		mMediaStartTime = startTime;
	}

	public int getStartTime(boolean includePlaybackOffset) {
		return includePlaybackOffset ? mMediaStartTime - mPlaybackOffsetStart : mMediaStartTime;
	}

	public void setEndTime(int endTime) {
		mMediaEndTime = endTime;
	}

	public int getEndTime(boolean includePlaybackOffset) {
		return includePlaybackOffset ? mMediaEndTime - mPlaybackOffsetEnd : mMediaEndTime;
	}

	// removes the offsets/overlaps that are used to smoothen on-device playback (leaving the 1ms tweaks for visual appearance)
	public void removePlaybackOffsets() {
		mPlaybackOffsetStart = Math.abs(mPlaybackOffsetStart) == 1 ? mPlaybackOffsetStart : 0;
		mPlaybackOffsetEnd = Math.abs(mPlaybackOffsetEnd) == 1 ? mPlaybackOffsetEnd : 0;
	}

	public int getDuration() {
		return mMediaEndTime - mMediaStartTime;
	}

	public boolean hasChangedDuration() {
		return !(getDuration() == mOriginalDuration);
	}

	@NonNull
	@Override
	public String toString() {
		String type;
		switch (mMediaType) {
			case MediaPhoneProvider.TYPE_IMAGE_BACK:
			case MediaPhoneProvider.TYPE_IMAGE_FRONT:
				type = "image";
				break;
			case MediaPhoneProvider.TYPE_AUDIO:
				type = "audio";
				break;
			case MediaPhoneProvider.TYPE_TEXT:
				type = "text";
				break;
			case MediaPhoneProvider.TYPE_VIDEO:
			default:
				type = "invalid";
				break;
		}
		StringBuilder allSpannedFrames = new StringBuilder();
		for (String frameId : mSpanningFrameIds) {
			allSpannedFrames.append(frameId);
			allSpannedFrames.append((','));
		}
		allSpannedFrames.setLength(allSpannedFrames.length() - 1);
		return "PlaybackMediaHolder{" + "mMediaStartTime=" + mMediaStartTime + ", mMediaEndTime=" + mMediaEndTime +
				", mMediaType=" + type + " (" + mMediaType + "), mParentFrameId='" + mParentFrameId + '\'' +
				", mSpanningFrameIds=[" + allSpannedFrames + "], mMediaItemId='" + mMediaItemId + '\'' + ", mMediaPath='" +
				mMediaPath + '\'' + ", mPlaybackOffsetStart=" + mPlaybackOffsetStart + ", mPlaybackOffsetEnd=" +
				mPlaybackOffsetEnd + '}';
	}
}
