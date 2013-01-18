package ac.robinson.mediaphone.view;

import java.math.BigDecimal;

import ac.robinson.mediaphone.R;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

public class SeekBarPreference extends Preference implements OnSeekBarChangeListener {
	private float mMinValue = 0;
	private float mMaxValue = 100;
	private float mInterval = 1;
	private String mStringFormat = "%.0f";
	private float mDefaultValue = 50;
	private String mPrependUnits = null;
	private String mAppendUnits = null;

	private float mCurrentValue = mDefaultValue;

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

	// @SuppressLint("UseValueOf") we can't use valueOf as we need the actual string value - valueOf doesn't do this
	@SuppressLint("UseValueOf")
	private void setValuesFromXml(Context context, AttributeSet attrs) {
		TypedArray customAttributes = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference);

		mMinValue = customAttributes.getFloat(R.styleable.SeekBarPreference_minVal, mMinValue);
		mMaxValue = customAttributes.getFloat(R.styleable.SeekBarPreference_maxVal, mMaxValue);
		if (mMaxValue < mMinValue) {
			mMaxValue = mMinValue;
		}

		mDefaultValue = customAttributes.getFloat(R.styleable.SeekBarPreference_defaultVal, mDefaultValue);
		if (mDefaultValue > mMaxValue) {
			mDefaultValue = mMaxValue;
		} else if (mDefaultValue < mMinValue) {
			mDefaultValue = mMinValue;
		}
		mCurrentValue = mDefaultValue;

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
		return rangeIntToFloat(i, mMinValue, mInterval);
	}

	public static float rangeIntToFloat(int i, float minValue, float interval) {
		int roundValue = (int) (1 / interval);
		if (roundValue < 1) {
			roundValue = 1;
		}
		return Math.round(((i * interval) + minValue) * roundValue) / (float) roundValue;
	}

	private int floatToRangeInt(float f) {
		return floatToRangeInt(f, mMinValue, mInterval);
	}

	public static int floatToRangeInt(float f, float minValue, float interval) {
		return (int) ((f - minValue) / interval);
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

				mValueTextView = (TextView) summaryParentGroup.findViewById(R.id.preference_seek_bar_value);
				mPrependUnitsView = (TextView) summaryParentGroup.findViewById(R.id.preference_seek_bar_prepend_units);
				mAppendUnitsView = (TextView) summaryParentGroup.findViewById(R.id.preference_seek_bar_append_units);
			}
		}

		return parentView;
	}

	@Override
	public void onBindView(View view) {
		super.onBindView(view);
		if (mValueTextView != null) {
			mValueTextView.setText(String.format(mStringFormat, mCurrentValue));
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

	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
		// clip to bounds
		float newValue = rangeIntToFloat(progress);
		if (newValue > mMaxValue) {
			newValue = mMaxValue;
		} else if (newValue < mMinValue) {
			newValue = mMinValue;
		}

		// change rejected, revert to the previous value
		if (!callChangeListener(newValue)) {
			seekBar.setProgress(floatToRangeInt(mCurrentValue));
			return;
		}

		// store the new value
		mCurrentValue = newValue;
		if (mValueTextView != null) {
			mValueTextView.setText(String.format(mStringFormat, mCurrentValue));
		}
		persistInt(floatToRangeInt(newValue));
	}

	public void onStartTrackingTouch(SeekBar seekBar) {
	}

	public void onStopTrackingTouch(SeekBar seekBar) {
		notifyChanged();
	}

	@Override
	protected Object onGetDefaultValue(TypedArray typedArray, int index) {
		// int defaultValue = typedArray.getInt(index, floatToRangeInt(mDefaultValue)); // not using Android version
		return floatToRangeInt(mDefaultValue);
	}

	@Override
	protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
		if (restoreValue) {
			mCurrentValue = rangeIntToFloat(getPersistedInt(floatToRangeInt(mCurrentValue)));
		} else {
			// although our default value uses a float, we have to store as an integer due to the seek bar...
			persistInt(floatToRangeInt(mDefaultValue));
			mCurrentValue = mDefaultValue;
		}
	}
}
