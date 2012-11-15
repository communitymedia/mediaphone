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

import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.activity.AudioActivity.PathAndStateSavingMediaRecorder;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Picture;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.larvalabs.svgandroid.SVG;
import com.larvalabs.svgandroid.SVGParser;

public class VUMeter extends View {
	static final float PIVOT_RADIUS = 8f;
	static final float PIVOT_Y_OFFSET = 1f;
	static final float SHADOW_RADIUS = PIVOT_RADIUS * 5 / 4;
	static final float SHADOW_OFFSET = 0f;
	static final float DROPOFF_STEP = 0.18f;
	static final float SURGE_STEP = 0.35f;
	static final long ANIMATION_INTERVAL = 40;
	static final float WIDTH_HEIGHT_RATIO = 0.8f;

	final float mMinAngle = (float) Math.PI * 0.295f;
	final float mMaxAngle = (float) Math.PI * 0.705f;

	Paint mPaint, mShadow;
	float mCurrentAngle;
	Picture mBackgroundDrawable;
	Rect mDrawRect = new Rect();
	boolean mRecordingStarted;

	PathAndStateSavingMediaRecorder mRecorder;
	RecordingStartedListener mRecordingStartedCallback;

	public VUMeter(Context context) {
		super(context);
		init(context);
	}

	public VUMeter(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	void init(Context context) {
		// using SVG so that we don't need resolution-specific icons
		mBackgroundDrawable = null;
		if (!isInEditMode()) { // so the Eclipse visual editor can load this component
			SVG vumeterSVG = SVGParser.getSVGFromResource(context.getResources(), R.raw.vumeter_background);
			mBackgroundDrawable = vumeterSVG.getPicture();
			vumeterSVG = null;
		}

		mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		mPaint.setColor(Color.WHITE);
		mPaint.setStrokeWidth(PIVOT_RADIUS * 4 / 5);
		mPaint.setStrokeCap(Cap.ROUND);
		mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
		mShadow.setColor(Color.argb(255, 200, 200, 200));
		mShadow.setStrokeWidth(SHADOW_RADIUS);
		mShadow.setStrokeCap(Cap.ROUND);

		mRecorder = null;
		mRecordingStarted = false;

		mCurrentAngle = 0;
	}

	public void setRecorder(PathAndStateSavingMediaRecorder recorder, RecordingStartedListener recordingStartedCallback) {
		mRecorder = recorder;
		mRecordingStartedCallback = recordingStartedCallback;
		mRecordingStarted = false;
		invalidate();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		canvas.drawColor(Color.BLACK);

		float angle = mMinAngle;
		if (mRecorder != null) {
			angle += (float) (mMaxAngle - mMinAngle) * mRecorder.getMaxAmplitude() / 16384f;
		}

		if (angle > mCurrentAngle || mRecorder == null) {
			mCurrentAngle = angle;
		} else {
			mCurrentAngle = Math.max(angle, mCurrentAngle - DROPOFF_STEP);
		}

		mCurrentAngle = Math.min(mMaxAngle, mCurrentAngle);

		if (mCurrentAngle > mMinAngle && !mRecordingStarted) {
			mRecordingStarted = true;
			if (mRecordingStartedCallback != null) {
				mRecordingStartedCallback.recordingStarted();
			}
		}

		float w = getWidth();
		float h = getHeight();
		float pivotX = w / 2;
		float pivotY = h - PIVOT_RADIUS - PIVOT_Y_OFFSET;
		float l = h * 0.89f;
		float sin = (float)Math.sin(mCurrentAngle);
		float cos = (float)Math.cos(mCurrentAngle);
		float x0 = pivotX - l * cos;
		float y0 = pivotY - l * sin;
		mDrawRect.set(0, 0, (int) w, (int) h);
		if (mBackgroundDrawable != null) {
			canvas.drawPicture(mBackgroundDrawable, mDrawRect);
		}
		canvas.drawLine(x0 + SHADOW_OFFSET, y0 + SHADOW_OFFSET, pivotX + SHADOW_OFFSET, pivotY + SHADOW_OFFSET, mShadow);
		canvas.drawCircle(pivotX + SHADOW_OFFSET, pivotY + SHADOW_OFFSET, SHADOW_RADIUS, mShadow);
		canvas.drawLine(x0, y0, pivotX, pivotY, mPaint);
		canvas.drawCircle(pivotX, pivotY, PIVOT_RADIUS, mPaint);

		if (mRecorder != null && mRecorder.isRecording()) {
			postInvalidateDelayed(ANIMATION_INTERVAL);
		}
	}

	//not needed, but added here to ensure that Eclipse's visual editor can display this component properly
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int width = MeasureSpec.getSize(widthMeasureSpec);
		super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(Math.round(width * WIDTH_HEIGHT_RATIO), MeasureSpec.EXACTLY));
	}

	@Override
	public void onLayout(boolean changed, int l, int t, int r, int b) {
		int width = r - l;
		int height = Math.round(width * WIDTH_HEIGHT_RATIO);
		ViewGroup.LayoutParams params = this.getLayoutParams();
		params.height = height;
		this.setLayoutParams(params);
		this.setMeasuredDimension(width, height);
		super.onLayout(changed, l, t, r, t + height);
	}

	public abstract class RecordingStartedListener {
		abstract public void recordingStarted();
	}
}
