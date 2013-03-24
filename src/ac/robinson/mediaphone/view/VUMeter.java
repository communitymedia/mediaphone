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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.larvalabs.svgandroid.SVG;
import com.larvalabs.svgandroid.SVGParser;

public class VUMeter extends View {
	static final float PIVOT_RADIUS = 8f;
	static final float LINE_WIDTH = PIVOT_RADIUS * 4 / 5;
	static final float SHADOW_RADIUS = PIVOT_RADIUS * 5 / 4;
	static final float SHADOW_OFFSET = 0f;
	static final float DROPOFF_STEP = 0.18f;
	static final float SURGE_STEP = 0.35f;
	static final long ANIMATION_INTERVAL = 40;
	static final float WIDTH_HEIGHT_RATIO = 0.8f;
	static final float LENGTH_HEIGHT_RATIO = 0.89f;

	final float mMinAngle = (float) Math.PI * 0.295f;
	final float mMaxAngle = (float) Math.PI * 0.705f;

	Paint mPaint, mBackgroundPaint, mShadow;
	float mPivotX, mPivotY, mLineLength, mCurrentAngle;
	Bitmap mBackgroundBitmap;
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
		mBackgroundBitmap = null;
		mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

		mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		mPaint.setColor(Color.WHITE);
		mPaint.setStrokeWidth(LINE_WIDTH);
		// mPaint.setStrokeCap(Cap.ROUND); // setStrokeCap doesn't work with hardware acceleration
		mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
		mShadow.setColor(Color.argb(255, 200, 200, 200));
		mShadow.setStrokeWidth(SHADOW_RADIUS);
		// mShadow.setStrokeCap(Cap.ROUND); // setStrokeCap doesn't work with hardware acceleration

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
		if (mBackgroundBitmap != null) {
			canvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);
		}

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

		float sin = (float) Math.sin(mCurrentAngle);
		float cos = (float) Math.cos(mCurrentAngle);
		float x0 = mPivotX - mLineLength * cos;
		float y0 = mPivotY - mLineLength * sin;

		canvas.drawCircle(x0, y0, SHADOW_RADIUS / 2f, mShadow); // setStrokeCap doesn't work with hardware acceleration
		canvas.drawLine(x0 + SHADOW_OFFSET, y0 + SHADOW_OFFSET, mPivotX + SHADOW_OFFSET, mPivotY + SHADOW_OFFSET,
				mShadow);
		canvas.drawCircle(mPivotX + SHADOW_OFFSET, mPivotY + SHADOW_OFFSET, SHADOW_RADIUS, mShadow);

		canvas.drawCircle(x0, y0, LINE_WIDTH / 2f, mPaint); // setStrokeCap doesn't work with hardware acceleration
		canvas.drawLine(x0, y0, mPivotX, mPivotY, mPaint);
		canvas.drawCircle(mPivotX, mPivotY, PIVOT_RADIUS, mPaint);

		if (mRecorder != null && mRecorder.isRecording()) {
			postInvalidateDelayed(ANIMATION_INTERVAL);
		}
	}

	// not needed, but added here to ensure that Eclipse's visual editor can display this component properly
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

		mPivotX = width / 2;
		mPivotY = height - Math.max(PIVOT_RADIUS, SHADOW_RADIUS);
		mLineLength = height * LENGTH_HEIGHT_RATIO;

		// using SVG so that we don't need a resolution-specific arc icon
		if (changed && !isInEditMode()) { // isInEditMode so the Eclipse visual editor can load this component
			SVG vumeterSVG = SVGParser.getSVGFromResource(getResources(), R.raw.vumeter_background);
			mBackgroundBitmap = vumeterSVG.getBitmap(width, height);
			vumeterSVG = null;
		}

		super.onLayout(changed, l, t, r, t + height);
	}

	public interface RecordingStartedListener {
		void recordingStarted();
	}
}
