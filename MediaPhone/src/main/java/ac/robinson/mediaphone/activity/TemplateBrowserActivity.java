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

package ac.robinson.mediaphone.activity;

import ac.robinson.mediaphone.BrowserActivity;
import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameAdapter;
import ac.robinson.mediaphone.provider.NarrativeAdapter;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediaphone.view.HorizontalListView;
import ac.robinson.mediaphone.view.NarrativeViewHolder;
import ac.robinson.mediaphone.view.NarrativesListView;
import ac.robinson.util.ImageCacheUtilities;
import ac.robinson.util.UIUtilities;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.TextView;

public class TemplateBrowserActivity extends BrowserActivity {

	private NarrativesListView mTemplates;
	private NarrativeAdapter mTemplateAdapter;

	private final AdapterView.OnItemClickListener mFrameClickListener = new FrameClickListener();
	private AdapterView.OnItemLongClickListener mFrameLongClickListener = new FrameLongClickListener();
	private boolean mAllowTemplateDeletion = false;

	private final Handler mScrollHandler = new ScrollHandler();
	private int mScrollState = ScrollManager.SCROLL_STATE_IDLE;
	private boolean mPendingIconsUpdate;
	private boolean mFingerUp = true;

	private View mFrameAdapterEmptyView = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		UIUtilities.configureActionBar(this, false, true, R.string.template_list_header, 0);
		setContentView(R.layout.template_browser);
		initialiseTemplatesView();
		UIUtilities.showToast(TemplateBrowserActivity.this, R.string.select_template_hint);
	}

	@Override
	protected void onResume() {
		super.onResume();
		// reload previous scroll position
		SharedPreferences rotationSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		mTemplates.setSelectionFromTop(rotationSettings.getInt(getString(R.string.key_template_list_top), 0),
				rotationSettings.getInt(getString(R.string.key_template_list_position), 0));
	}

	@Override
	protected void onPause() {
		super.onPause();
		mScrollHandler.removeMessages(R.id.msg_update_template_icons);

		// need to do this manually because the default implementation resets when we change the adapters
		int listTop = mTemplates.getFirstVisiblePosition();
		int listPosition = 0;
		View firstView = mTemplates.getChildAt(0);
		if (firstView != null) {
			listPosition = firstView.getTop();
		}
		updateListPositions(listTop, listPosition);
	}

	@Override
	protected void onDestroy() {
		if (isFinishing()) {
			updateListPositions(0, 0);
		}
		ImageCacheUtilities.cleanupCache();
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		setResult(Activity.RESULT_OK);
		finish();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			getMenuInflater().inflate(R.menu.cancel, menu);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
			case R.id.menu_cancel:
				onBackPressed();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		mAllowTemplateDeletion = mediaPhoneSettings.getBoolean(getString(R.string.key_allow_deleting_templates),
				getResources().getBoolean(R.bool.default_allow_deleting_templates));
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		// the soft back button (necessary in some circumstances)
		int newVisibility = View.VISIBLE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
				|| !mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
						getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
		}
		findViewById(R.id.button_finished_templates).setVisibility(newVisibility);
	}

	private void initialiseTemplatesView() {
		mTemplates = (NarrativesListView) findViewById(R.id.list_templates);

		// for API 11 and above, buttons are in the action bar
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
			LayoutInflater layoutInflater = getLayoutInflater();
			View headerRow = layoutInflater.inflate(R.layout.templates_header, null, false);
			mTemplates.addHeaderView(headerRow, null, false); // false = not selectable
			View emptyView = layoutInflater.inflate(R.layout.templates_empty, null, false);
			((ViewGroup) mTemplates.getParent()).addView(emptyView);
			mTemplates.setEmptyView(emptyView); // must add separately as the header isn't shown when empty

		} else {
			// initial empty list placeholder - add manually as the < v11 version includes the header row
			TextView emptyView = new TextView(TemplateBrowserActivity.this);
			emptyView.setGravity(Gravity.CENTER | Gravity.TOP);
			emptyView.setPadding(10,
					getResources().getDimensionPixelSize(R.dimen.template_list_empty_hint_top_padding), 10, 10); // temporary
			emptyView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			emptyView.setText(getString(R.string.template_list_empty));
			((ViewGroup) mTemplates.getParent()).addView(emptyView);
			mTemplates.setEmptyView(emptyView);
		}

		mTemplateAdapter = new NarrativeAdapter(this, false, true);
		mTemplates.setAdapter(mTemplateAdapter);
		getSupportLoaderManager().initLoader(R.id.loader_templates_completed, null, this);
		mTemplates.setOnScrollListener(new ScrollManager());
		mTemplates.setOnTouchListener(new FingerTracker());
		mTemplates.setOnItemSelectedListener(new SelectionTracker());
		mTemplates.setOnItemClickListener(new NarrativeViewer());
	}

	private void updateListPositions(int listTop, int listPosition) {
		SharedPreferences rotationSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor prefsEditor = rotationSettings.edit();
		prefsEditor.putInt(getString(R.string.key_template_list_top), listTop);
		prefsEditor.putInt(getString(R.string.key_template_list_position), listPosition);
		prefsEditor.commit(); // apply() is better, but only in SDK >= 9
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
		return new CursorLoader(TemplateBrowserActivity.this, NarrativeItem.TEMPLATE_CONTENT_URI,
				NarrativeItem.PROJECTION_ALL, NarrativeItem.SELECTION_NOT_DELETED, null,
				NarrativeItem.DEFAULT_SORT_ORDER);
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		switch (loader.getId()) {
			case R.id.loader_templates_completed:
				mTemplateAdapter.swapCursor(cursor);
				break;
		}
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		mTemplateAdapter.swapCursor(null); // data now unavailable for some reason - remove cursor
	}

	@Override
	public int getScrollState() {
		return mScrollState; // for NarrativeAdapter purposes
	}

	@Override
	public boolean isPendingIconsUpdate() {
		return mPendingIconsUpdate; // for NarrativeAdapter purposes
	}

	@Override
	public int getFrameAdapterScrollPosition(String narrativeId) {
		return -1; // for NarrativeAdapter purposes - we're not saving rotation state here TODO: should we?
	}

	@Override
	public View getFrameAdapterEmptyView() {
		return mFrameAdapterEmptyView; // for FrameAdapter purposes
	}

	@Override
	public void setFrameAdapterEmptyView(View view) {
		mFrameAdapterEmptyView = view; // for FrameAdapter purposes
	}

	@Override
	public AdapterView.OnItemClickListener getFrameClickListener() {
		return mFrameClickListener; // for NarrativeAdapter purposes
	}

	@Override
	public AdapterView.OnItemLongClickListener getFrameLongClickListener() {
		return mFrameLongClickListener; // for NarrativeAdapter purposes
	}

	private class ScrollManager implements AbsListView.OnScrollListener {
		public void onScrollStateChanged(AbsListView view, int scrollState) {
			if (mScrollState == ScrollManager.SCROLL_STATE_FLING && scrollState != ScrollManager.SCROLL_STATE_FLING) {
				mPendingIconsUpdate = true;
				final Handler handler = mScrollHandler;
				handler.removeMessages(R.id.msg_update_template_icons);
				final Message message = handler.obtainMessage(R.id.msg_update_template_icons,
						TemplateBrowserActivity.this);
				handler.sendMessageDelayed(message, mFingerUp ? 0 : MediaPhone.ANIMATION_ICON_SHOW_DELAY);
			} else if (scrollState == ScrollManager.SCROLL_STATE_FLING) {
				mPendingIconsUpdate = false;
				mScrollHandler.removeMessages(R.id.msg_update_template_icons);
			}
			mScrollState = scrollState;
		}

		// for showing the overlay with current item information - no need at the moment
		public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
			// TODO show the template ids that are currently visible
			return;
		}
	}

	private void updateTemplateIcons() {
		mPendingIconsUpdate = false;
		for (int i = 0, n = mTemplates.getChildCount(); i < n; i++) {
			final Object viewTag = mTemplates.getChildAt(i).getTag();
			if (viewTag instanceof NarrativeViewHolder) {
				final NarrativeViewHolder holder = (NarrativeViewHolder) viewTag;
				if (holder.queryIcons) {
					mTemplateAdapter.attachAdapter(holder);
					holder.queryIcons = false;
				}
			}
		}
	}

	private static class ScrollHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_update_template_icons:
					((TemplateBrowserActivity) msg.obj).updateTemplateIcons();
					break;
			}
		}
	}

	private void postUpdateTemplateIcons() {
		mPendingIconsUpdate = true;
		Handler handler = mScrollHandler;
		handler.removeMessages(R.id.msg_update_template_icons);
		Message message = handler.obtainMessage(R.id.msg_update_template_icons, TemplateBrowserActivity.this);
		handler.sendMessage(message);
	}

	private class FingerTracker implements View.OnTouchListener {
		public boolean onTouch(View view, MotionEvent event) {
			final int action = event.getAction();
			mFingerUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
			if (mFingerUp && mScrollState != ScrollManager.SCROLL_STATE_FLING) {
				postUpdateTemplateIcons();
			}
			return false;
		}
	}

	private class SelectionTracker implements AdapterView.OnItemSelectedListener {
		public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
			if (mScrollState != ScrollManager.SCROLL_STATE_IDLE) {
				mScrollState = ScrollManager.SCROLL_STATE_IDLE;
				postUpdateTemplateIcons();
			}
		}

		public void onNothingSelected(AdapterView<?> adapterView) {
		}
	}

	private class NarrativeViewer implements AdapterView.OnItemClickListener {
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			// no need to do anything here - we don't allow clicking entire narratives
		}
	}

	@Override
	protected void onBackgroundTaskCompleted(int taskId) {
		if (taskId == R.id.make_load_template_task_complete) {
			UIUtilities.showToast(TemplateBrowserActivity.this, R.string.template_to_narrative_complete);
			onBackPressed();
		}
	}

	private class FrameClickListener implements AdapterView.OnItemClickListener {
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			// sometimes we get the event without the view (they released at the last minute?)
			if (view != null && parent != null) {
				runQueuedBackgroundTask(getNarrativeTemplateRunnable(
						((FrameAdapter) ((HorizontalListView) parent).getAdapter()).getParentFilter(), false));
			}
		}
	}

	private class FrameLongClickListener implements AdapterView.OnItemLongClickListener {
		@Override
		// this is a hack to allow long pressing one or two items via the same listener
		public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long insertNewFrameBefore) {
			// sometimes we get the event without the view (they released at the last minute?)
			if (view != null && parent != null) {
				final CharSequence[] items;
				if (mAllowTemplateDeletion) {
					items = new CharSequence[] { getString(R.string.export_template),
							getString(R.string.delete_template) };
				} else {
					items = new CharSequence[] { getString(R.string.export_template) };
				}
				final String templateId = ((FrameAdapter) ((HorizontalListView) parent).getAdapter()).getParentFilter();

				AlertDialog.Builder builder = new AlertDialog.Builder(TemplateBrowserActivity.this);
				builder.setTitle(R.string.template_actions);
				// builder.setMessage(R.string.edit_template_hint);
				builder.setIcon(android.R.drawable.ic_dialog_info);
				builder.setNegativeButton(R.string.button_cancel, null);
				builder.setItems(items, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int item) {
						switch (item) {
							case 0:
								exportContent(templateId, true);
								break;
							case 1:
								AlertDialog.Builder builder = new AlertDialog.Builder(TemplateBrowserActivity.this);
								builder.setTitle(R.string.delete_template_confirmation);
								builder.setMessage(R.string.delete_template_hint);
								builder.setIcon(android.R.drawable.ic_dialog_alert);
								builder.setNegativeButton(R.string.button_cancel, null);
								builder.setPositiveButton(R.string.button_delete,
										new DialogInterface.OnClickListener() {
											@Override
											public void onClick(DialogInterface dialog, int whichButton) {
												ContentResolver contentResolver = getContentResolver();
												NarrativeItem templateToDelete = NarrativesManager
														.findTemplateByInternalId(contentResolver, templateId);
												templateToDelete.setDeleted(true);
												NarrativesManager.updateTemplate(contentResolver, templateToDelete);
												UIUtilities.showToast(TemplateBrowserActivity.this,
														R.string.delete_template_succeeded);
											}
										});
								builder.show();
								break;
						}
						dialog.dismiss();
					}
				});
				builder.show();
			}
			return true;
		}
	}

	public void handleButtonClicks(View currentButton) {
		if (!verifyButtonClick(currentButton)) {
			return;
		}

		switch (currentButton.getId()) {
			case R.id.button_finished_templates:
				onBackPressed();
				break;
		}
	}
}
