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

import java.math.BigDecimal;
import java.util.Locale;

import ac.robinson.mediaphone.R;
import ac.robinson.util.UIUtilities;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

public class SeekBarPreference extends Preference implements OnSeekBarChangeListener, OnClickListener {
	private float mMinValue = 0;
	private float mMaxValue = 100;
	private float mInterval = 1;
	private String mStringFormat = "%.0f";
	private float mDefaultValue = 50;
	private String mPrependUnits = null;
	private String mAppendUnits = null;

	private float mCurrentValue = mDefaultValue;
	private long mDoubleTapTime = 0;
	private float mLastTouchX, mLastTouchY; // for stopping seekbar movement on scroll
	private long mLastTouchTime;

	private SeekBar mSeekBar;
	private TextView mValueTextView;
	private TextView mPrependUnitsView;
	private TextView mAppendUnitsView;

	public SeekBarPreference(Context context) {
		super(context);
		// TODO: initialise
	}

	public SeekBarPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
		setValuesFromXml(context, attrs);
	}

	public SeekBarPreference(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		setValuesFromXml(context, attrs);
	}

	// @SuppressLint("UseValueOf") we can't use valueOf as we need the *actual* string value - valueOf doesn't do this
	@SuppressLint("UseValueOf")
	private void setValuesFromXml(Context context, AttributeSet attrs) {
		TypedArray customAttributes = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference);

		mMinValue = customAttributes.getFloat(R.styleable.SeekBarPreference_minVal, mMinValue);
		mMaxValue = customAttributes.getFloat(R.styleable.SeekBarPreference_maxVal, mMaxValue);
		if (mMaxValue < mMinValue) {
			mMaxValue = mMinValue;
		}

		String defaultValue = attrs.getAttributeValue("http://schemas.android.com/apk/res/android", "defaultValue");
		try {
			mDefaultValue = Float.valueOf(defaultValue);
		} catch (Throwable t) {
			// for some ridiculous reason, Android's inbuilt preference values don't resolve references!
			float defaultValueFloat = 0;
			if (defaultValue != null && defaultValue.length() > 1 && defaultValue.charAt(0) == '@') {
				int resourceId = 0;
				try {
					resourceId = Integer.parseInt(defaultValue.substring(1));
				} catch (Throwable t2) {
					resourceId = 0;
				}
				if (resourceId != 0) {
					// we don't know what type this reference is, so we need to try each one in turn
					boolean defaultValueFound;
					try {
						TypedValue resourceValue = new TypedValue();
						context.getResources().getValue(resourceId, resourceValue, true);
						defaultValueFloat = resourceValue.getFloat();
						defaultValueFound = true;
					} catch (Throwable t3) {
						defaultValueFound = false;
					}
					if (!defaultValueFound) {
						try {
							defaultValueFloat = context.getResources().getInteger(resourceId);
							defaultValueFound = true;
						} catch (Throwable t3) {
							defaultValueFound = false;
						}
					}
					if (!defaultValueFound) {
						// bizarrely, loading as a string seems to work as a last resort for most values
						try {
							defaultValueFloat = Float.valueOf(context.getString(resourceId));
							defaultValueFound = true;
						} catch (Throwable t3) {
							defaultValueFound = false;
						}
					}
					if (defaultValueFound) {
						mDefaultValue = defaultValueFloat;
					}
				}
			}
		}
		mDefaultValue = Math.max(mMinValue, Math.min(mMaxValue, mDefaultValue)); // clip to bounds
		mCurrentValue = mDefaultValue; // xml is loaded before current value is set (from persisted value on inflation)

		mInterval = customAttributes.getFloat(R.styleable.SeekBarPreference_interval, mInterval);
		if (mInterval > mMaxValue - mMinValue) {
			mInterval = mMaxValue - mMinValue;
		}
		BigDecimal intervalFormat = new BigDecimal(new Float(mInterval).toString()).stripTrailingZeros();
		if (intervalFormat.scale() >= 0) {
			mStringFormat = "%." + intervalFormat.scale() + "f";
		}

		mPrependUnits = customAttributes.getString(R.styleable.SeekBarPreference_prependUnits);
		if (mPrependUnits != null) {
			mPrependUnits = mPrependUnits.trim() + " ";
		}
		mAppendUnits = customAttributes.getString(R.styleable.SeekBarPreference_appendUnits);
		if (mAppendUnits != null) {
			mAppendUnits = " " + mAppendUnits.trim();
		}
		customAttributes.recycle();
	}

	private float rangeIntToFloat(int i) {
		int roundValue = (int) (1 / mInterval);
		if (roundValue < 1) {
			roundValue = 1;
		}
		return Math.round(((i * mInterval) + mMinValue) * roundValue) / (float) roundValue;
	}

	private int floatToRangeInt(float f) {
		return (int) ((f - mMinValue) / mInterval);
	}

	@Override
	protected View onCreateView(ViewGroup parent) {
		View parentView = super.onCreateView(parent);
		View summary = parentView.findViewById(android.R.id.summary);
		if (summary != null) {
			ViewParent summaryParent = summary.getParent();
			if (summaryParent instanceof ViewGroup) {
				final LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(
						Context.LAYOUT_INFLATER_SERVICE);
				ViewGroup summaryParentGroup = (ViewGroup) summaryParent;
				layoutInflater.inflate(R.layout.seek_bar_preference, summaryParentGroup);

				mSeekBar = (SeekBar) summaryParentGroup.findViewById(R.id.preference_seek_bar);
				mSeekBar.setMax(floatToRangeInt(mMaxValue));
				mSeekBar.setOnSeekBarChangeListener(this);
				mSeekBar.setOnTouchListener(new OnTouchListener() {
					@Override
					public boolean onTouch(View v, MotionEvent event) {
						switch (event.getAction()) {
							case MotionEvent.ACTION_CANCEL: // so we don't move the preference item on cancel
								mLastTouchX = event.getRawX();
								mLastTouchY = event.getRawY();
								return true;

							case MotionEvent.ACTION_UP:
								if (event.getEventTime() == mLastTouchTime) {
									break; // this is a single tap; don't cancel
								}
								// intentionally not breaking/returning so we cancel if appropriate
							case MotionEvent.ACTION_DOWN:
								mLastTouchTime = event.getEventTime();
								// intentionally not breaking/returning so we cancel if appropriate
							default:
								// because we can't change the action in the parent's onInterceptTouchEvent, we dispatch
								// the same event twice - an *identical* action after a cancel implies another cancel
								if (event.getRawX() == mLastTouchX && event.getRawY() == mLastTouchY) {
									return true;
								} else {
									mLastTouchX = -1;
									mLastTouchY = -1;
								}
						}
						return false;
					}
				});

				mValueTextView = (TextView) summaryParentGroup.findViewById(R.id.preference_seek_bar_value);
				mPrependUnitsView = (TextView) summaryParentGroup.findViewById(R.id.preference_seek_bar_prepend_units);
				mAppendUnitsView = (TextView) summaryParentGroup.findViewById(R.id.preference_seek_bar_append_units);
			}
		}

		parentView.setOnClickListener(this);
		return parentView;
	}

	@Override
	public void onBindView(View view) {
		super.onBindView(view);
		if (mValueTextView != null) {
			mValueTextView.setText(String.format((Locale) null, mStringFormat, mCurrentValue));
		}
		if (mSeekBar != null) {
			mSeekBar.setProgress(floatToRangeInt(mCurrentValue));
		}
		if (mPrependUnitsView != null && mPrependUnits != null) {
			mPrependUnitsView.setText(mPrependUnits);
		}
		if (mAppendUnitsView != null && mAppendUnits != null) {
			mAppendUnitsView.setText(mAppendUnits);
		}
	}

	@Override
	public void onClick(View v) {
		if (System.currentTimeMillis() - mDoubleTapTime < ViewConfiguration.getDoubleTapTimeout()) {
			mCurrentValue = mDefaultValue;
			if (mSeekBar != null) {
				mSeekBar.setProgress(floatToRangeInt(mCurrentValue));
			}
			if (mValueTextView != null) {
				mValueTextView.setText(String.format((Locale) null, mStringFormat, mCurrentValue));
			}
			persistFloat(mCurrentValue);
			UIUtilities.showToast(v.getContext(), R.string.preferences_reset_default);
		}
		mDoubleTapTime = System.currentTimeMillis();
	}

	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
		float newValue = rangeIntToFloat(progress);
		newValue = Math.max(mMinValue, Math.min(mMaxValue, newValue)); // clip to bounds

		// change rejected, revert to the previous value
		if (!callChangeListener(newValue)) {
			seekBar.setProgress(floatToRangeInt(mCurrentValue));
			return;
		}

		// store the new value
		mCurrentValue = newValue;
		if (mValueTextView != null) {
			mValueTextView.setText(String.format((Locale) null, mStringFormat, mCurrentValue));
		}
		persistFloat(newValue);
	}

	public void onStartTrackingTouch(SeekBar seekBar) {
	}

	public void onStopTrackingTouch(SeekBar seekBar) {
		notifyChanged();
	}

	@Override
	protected Object onGetDefaultValue(TypedArray typedArray, int index) {
		try {
			mDefaultValue = typedArray.getFloat(index, mDefaultValue);
		} catch (Throwable t) {
			try {
				mDefaultValue = Float.valueOf(typedArray.getString(index));
			} catch (Throwable t2) {
			}
		}
		return mDefaultValue;
	}

	@Override
	protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
		if (restoreValue) {
			mCurrentValue = getPersistedFloat(mCurrentValue);
			mCurrentValue = Math.max(mMinValue, Math.min(mMaxValue, mCurrentValue)); // clip to bounds
		} else {
			float tempDefault;
			try {
				tempDefault = (Float) defaultValue;
			} catch (Throwable t) {
				tempDefault = mDefaultValue;
			}
			tempDefault = Math.max(mMinValue, Math.min(mMaxValue, tempDefault)); // clip to bounds
			persistFloat(tempDefault);
			mCurrentValue = mDefaultValue = tempDefault;
		}
	}
}
