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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import ac.robinson.mediaphone.BrowserActivity;
import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneApplication;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameAdapter;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.NarrativeAdapter;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediaphone.provider.UpgradeManager;
import ac.robinson.mediaphone.view.FrameViewHolder;
import ac.robinson.mediaphone.view.HorizontalListView;
import ac.robinson.mediaphone.view.NarrativeViewHolder;
import ac.robinson.mediaphone.view.NarrativesListView;
import ac.robinson.mediautilities.MediaUtilities;
import ac.robinson.util.ImageCacheUtilities;
import ac.robinson.util.UIUtilities;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
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
import android.widget.PopupWindow;
import android.widget.TextView;

public class NarrativeBrowserActivity extends BrowserActivity {

	private NarrativesListView mNarratives;
	private NarrativeAdapter mNarrativeAdapter;

	private Bundle mPreviousSavedState;
	private String mCurrentSelectedNarrativeId;
	// private int mScrollNarrativesToEnd;

	private final AdapterView.OnItemClickListener mFrameClickListener = new FrameClickListener();
	private final AdapterView.OnItemLongClickListener mFrameLongClickListener = new FrameLongClickListener();

	private final Handler mScrollHandler = new ScrollHandler();
	private int mScrollState = ScrollManager.SCROLL_STATE_IDLE;
	private boolean mPendingIconsUpdate;
	private boolean mFingerUp = true;
	private PopupWindow mPopup;
	private boolean mPopupWillShow;
	private View mPopupPosition;
	private TextView mPopupText;

	private View mFrameAdapterEmptyView = null;

	private boolean mScanningForNarratives;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		UIUtilities.configureActionBar(this, false, true, R.string.narrative_list_header, 0);
		setContentView(R.layout.narrative_browser);

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mPreviousSavedState = savedInstanceState; // for horizontal scroll position loading (NarrativeAdapter)
			mCurrentSelectedNarrativeId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			// mScrollNarrativesToEnd = savedInstanceState.getInt(getString(R.string.extra_start_scrolled_to_end), 0);
		} else {
			// initialise preferences on first run, and perform an upgrade if applicable
			PreferenceManager.setDefaultValues(this, R.xml.preferences, true);
			UpgradeManager.upgradeApplication(NarrativeBrowserActivity.this);
		}

		initialiseNarrativesView();
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putString(getString(R.string.extra_internal_id), mCurrentSelectedNarrativeId);
		if (mNarrativeAdapter != null) {
			// save horizontal scroll positions
			HashMap<String, Integer> scrollPositions = mNarrativeAdapter.getAdapterScrollPositions();
			if (scrollPositions != null) {
				for (String narrativeId : scrollPositions.keySet()) {
					savedInstanceState.putInt(narrativeId, scrollPositions.get(narrativeId));
				}
			}
		}
		// savedInstanceState.putInt(getString(R.string.extra_start_scrolled_to_end), mScrollNarrativesToEnd);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	protected void onResume() {
		super.onResume();
		// reload previous scroll position
		SharedPreferences rotationSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		mNarratives.setSelectionFromTop(rotationSettings.getInt(getString(R.string.key_narrative_list_top), 0),
				rotationSettings.getInt(getString(R.string.key_narrative_list_position), 0));

		// scroll to the last edited frame (but make sure we can't do it again to stop annoyance)
		String frameId = loadLastEditedFrame();
		if (mCurrentSelectedNarrativeId != null && frameId != null) {
			postDelayedScrollToSelectedFrame(mCurrentSelectedNarrativeId, frameId); // delayed so list has time to load
		}
		mCurrentSelectedNarrativeId = null;

		postDelayedUpdateNarrativeIcons(); // in case any icons were in the process of loading when we rotated
	}

	@Override
	protected void onPause() {
		super.onPause();
		hidePopup();
		mScrollHandler.removeMessages(R.id.msg_update_narrative_icons);

		// need to do this manually because the default implementation resets when we change the adapters
		int listTop = mNarratives.getFirstVisiblePosition();
		int listPosition = 0;
		View firstView = mNarratives.getChildAt(0);
		if (firstView != null) {
			listPosition = firstView.getTop();
		}
		updateListPositions(listTop, listPosition);
	}

	@Override
	protected void onDestroy() {
		if (isFinishing()) {
			updateListPositions(0, 0);
			runQueuedBackgroundTask(getMediaCleanupRunnable()); // delete old media on exit
		}
		ImageCacheUtilities.cleanupCache();
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		super.onBackPressed();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.narrative_browser, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				return true;
			case R.id.menu_add_narrative:
				addNarrative();
				return true;
			case R.id.menu_scan_imports:
				importNarratives();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// Resources res = getResources();
		//
		// whether to start scrolled to the end of the list
		// boolean scrollToEnd = res.getBoolean(R.bool.default_start_scrolled_to_end);
		// try {
		// scrollToEnd = mediaPhoneSettings.getBoolean(getString(R.string.key_start_scrolled_to_end), scrollToEnd);
		// } catch (Exception e) {
		// scrollToEnd = res.getBoolean(R.bool.default_start_scrolled_to_end);
		// }
		//
		// if (scrollToEnd && mScrollNarrativesToEnd == 0) {
		// mScrollNarrativesToEnd = 1; // 0 = unknown/not yet loaded; 1 = should scroll; -1 = has scrolled
		// }
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		// no interface preferences apply to this activity
	}

	private void initialiseNarrativesView() {
		mScanningForNarratives = false;
		mNarratives = (NarrativesListView) findViewById(R.id.list_narratives);

		// for API 11 and above, buttons are in the action bar
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
			LayoutInflater layoutInflater = getLayoutInflater();
			View headerRow = layoutInflater.inflate(R.layout.narratives_header, null, false);
			mNarratives.addHeaderView(headerRow, null, false); // false = not selectable
			View emptyView = layoutInflater.inflate(R.layout.narratives_empty, null, false);
			((ViewGroup) mNarratives.getParent()).addView(emptyView);
			mNarratives.setEmptyView(emptyView); // must add separately as the header isn't shown when empty

		} else {
			// initial empty list placeholder - add manually as the < v11 version includes the header row
			TextView emptyView = new TextView(NarrativeBrowserActivity.this);
			emptyView.setGravity(Gravity.CENTER | Gravity.TOP);
			emptyView.setPadding(10, getResources()
					.getDimensionPixelSize(R.dimen.narrative_list_empty_hint_top_padding), 10, 10); // temporary
			emptyView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			emptyView.setText(getString(R.string.narrative_list_empty));
			((ViewGroup) mNarratives.getParent()).addView(emptyView);
			mNarratives.setEmptyView(emptyView);
		}

		// originally used to fix selection highlights when using hardware button to select
		// now done by overriding isEnabled in NarrativeAdapter
		// mNarratives.setFocusable(false);
		// mNarratives.setFocusableInTouchMode(false);

		mNarrativeAdapter = new NarrativeAdapter(this, true, false);
		mNarratives.setAdapter(mNarrativeAdapter);
		getSupportLoaderManager().initLoader(R.id.loader_narratives_completed, null, this);
		mNarratives.setOnScrollListener(new ScrollManager());
		mNarratives.setOnTouchListener(new FingerTracker());
		mNarratives.setOnItemSelectedListener(new SelectionTracker());
		mNarratives.setOnItemClickListener(new NarrativeViewer());

		mPopupPosition = getLayoutInflater().inflate(R.layout.popup_position, null);
		mPopupText = (TextView) mPopupPosition.findViewById(R.id.popup_text);
	}

	private void updateListPositions(int listTop, int listPosition) {
		SharedPreferences rotationSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor prefsEditor = rotationSettings.edit();
		prefsEditor.putInt(getString(R.string.key_narrative_list_top), listTop);
		prefsEditor.putInt(getString(R.string.key_narrative_list_position), listPosition);
		prefsEditor.commit(); // apply() is better, but only in SDK >= 9
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
		return new CursorLoader(NarrativeBrowserActivity.this, NarrativeItem.NARRATIVE_CONTENT_URI,
				NarrativeItem.PROJECTION_ALL, NarrativeItem.SELECTION_NOT_DELETED, null,
				NarrativeItem.DEFAULT_SORT_ORDER);
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		switch (loader.getId()) {
			case R.id.loader_narratives_completed:
				mNarrativeAdapter.swapCursor(cursor);
				break;
		}
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		mNarrativeAdapter.swapCursor(null); // data now unavailable for some reason - remove cursor
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
		if (mPreviousSavedState != null) {
			final int scrollPosition = mPreviousSavedState.getInt(narrativeId, -1);
			mPreviousSavedState.remove(narrativeId);
			return scrollPosition; // for NarrativeAdapter purposes
		}
		return -1; // for NarrativeAdapter purposes
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
		private String mPreviousPrefix;

		private final Runnable mHidePopup = new Runnable() {
			public void run() {
				hidePopup();
			}
		};

		public void onScrollStateChanged(AbsListView view, int scrollState) {
			if (mScrollState == ScrollManager.SCROLL_STATE_FLING && scrollState != ScrollManager.SCROLL_STATE_FLING) {
				mPendingIconsUpdate = true;
				mScrollHandler.removeMessages(R.id.msg_update_narrative_icons);
				final Message message = mScrollHandler.obtainMessage(R.id.msg_update_narrative_icons,
						NarrativeBrowserActivity.this);
				mScrollHandler.sendMessageDelayed(message, mFingerUp ? 0 : MediaPhone.ANIMATION_ICON_SHOW_DELAY);
			} else if (scrollState == ScrollManager.SCROLL_STATE_FLING) {
				mPendingIconsUpdate = false;
				mScrollHandler.removeMessages(R.id.msg_update_narrative_icons);
			}
			mScrollState = scrollState;
		}

		// show an overlay to highlight which narratives are currently visible
		public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
			if (mScrollState != SCROLL_STATE_FLING) {
				return;
			}

			final int centreChild = (view.getChildCount() - 1) / 2;
			if (centreChild <= 0) {
				return;
			}

			final String prefix;
			Object viewTag = mNarratives.getChildAt(centreChild).getTag();
			if (viewTag instanceof NarrativeViewHolder) {
				prefix = Integer.toString(((NarrativeViewHolder) viewTag).narrativeSequenceId);
			} else {
				return;
			}

			if (!mPopupWillShow && (mPopup == null || !mPopup.isShowing()) && !prefix.equals(mPreviousPrefix)) {
				mPopupWillShow = true;
				mScrollHandler.removeCallbacks(mShowPopup);
				mScrollHandler.postDelayed(mShowPopup, MediaPhone.ANIMATION_POPUP_SHOW_DELAY);
			}
			mPopupText.setText(prefix);
			mPreviousPrefix = prefix;

			mScrollHandler.removeCallbacks(mHidePopup);
			mScrollHandler.postDelayed(mHidePopup, MediaPhone.ANIMATION_POPUP_HIDE_DELAY);
			return;
		}
	}

	private final Runnable mShowPopup = new Runnable() {
		public void run() {
			showPopup();
		}
	};

	private void hidePopup() {
		mScrollHandler.removeCallbacks(mShowPopup);
		if (mPopup != null) {
			mPopup.dismiss();
		}
		mPopupWillShow = false;
	}

	private void showPopup() {
		if (mScrollState != AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
			return;
		}

		if (mPopup == null) {
			PopupWindow p = new PopupWindow(this);
			p.setFocusable(false);
			p.setContentView(mPopupPosition);
			p.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
			p.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
			p.setBackgroundDrawable(null);
			p.setAnimationStyle(android.R.style.Animation_Dialog);
			mPopup = p;
		}

		if (mNarratives.getWindowVisibility() == View.VISIBLE) {
			mPopup.showAtLocation(mNarratives, Gravity.CENTER, 0, 0);
		}
	}

	private void updateNarrativeIcons() {
		mPendingIconsUpdate = false;
		boolean messageSent = false;
		// boolean scrollToEnd = mScrollNarrativesToEnd == 1;
		// if (scrollToEnd) {
		// mScrollNarrativesToEnd = -1; // 0 = unknown/not yet loaded; 1 = should scroll; -1 = has scrolled
		// }
		for (int i = 0, n = mNarratives.getChildCount(); i < n; i++) {
			final Object viewTag = mNarratives.getChildAt(i).getTag();
			if (viewTag instanceof NarrativeViewHolder) {
				final NarrativeViewHolder holder = (NarrativeViewHolder) viewTag;
				// if (scrollToEnd) {
				// disabled as it only scrolls visible narratives; need a better approach (in HorizontalListView)
				// holder.frameList.scrollTo(holder.frameList.getMaxFlingX(false), 0);
				// }
				if (holder.queryIcons) {
					mNarrativeAdapter.attachAdapter(holder);
					holder.queryIcons = false;
				}
				if (!holder.frameList.isIconLoadingComplete()) {
					holder.frameList.postUpdateFrameIcons();
					if (!messageSent) {
						postDelayedUpdateNarrativeIcons();
						messageSent = true;
					}
				}
			}
		}
	}

	private void scrollToSelectedFrame(Bundle messageData) {
		String narrativeId = messageData.getString(getString(R.string.extra_parent_id));
		String frameId = messageData.getString(getString(R.string.extra_internal_id));
		if (narrativeId != null && frameId != null) {
			for (int i = 0, n = mNarratives.getChildCount(); i < n; i++) {
				final Object viewTag = mNarratives.getChildAt(i).getTag();
				if (viewTag instanceof NarrativeViewHolder) {
					final NarrativeViewHolder holder = (NarrativeViewHolder) viewTag;
					if (narrativeId.equals(holder.narrativeInternalId)) {
						holder.frameList.scrollTo(frameId);
						break;
					}
				}
			}
		}
	}

	private static class ScrollHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_update_narrative_icons:
					((NarrativeBrowserActivity) msg.obj).updateNarrativeIcons();
					break;
				case R.id.msg_scroll_to_selected_frame:
					((NarrativeBrowserActivity) msg.obj).scrollToSelectedFrame(msg.getData());
					break;
			}
		}
	}

	private void postUpdateNarrativeIcons() {
		mPendingIconsUpdate = true;
		mScrollHandler.removeMessages(R.id.msg_update_narrative_icons);
		Message message = mScrollHandler.obtainMessage(R.id.msg_update_narrative_icons, NarrativeBrowserActivity.this);
		mScrollHandler.sendMessage(message);
	}

	private void postDelayedUpdateNarrativeIcons() {
		// mPendingIconsUpdate = true; // intentionally not doing this so we load icons immediately when rotating
		mScrollHandler.removeMessages(R.id.msg_update_narrative_icons);
		Message message = mScrollHandler.obtainMessage(R.id.msg_update_narrative_icons, NarrativeBrowserActivity.this);
		mScrollHandler.sendMessageDelayed(message, MediaPhone.ANIMATION_ICON_REFRESH_DELAY);
	}

	private void postScrollToSelectedFrame(String narrativeId, String frameId) {
		mScrollHandler.removeMessages(R.id.msg_scroll_to_selected_frame);
		Message message = mScrollHandler
				.obtainMessage(R.id.msg_scroll_to_selected_frame, NarrativeBrowserActivity.this);
		Bundle messageData = message.getData();
		messageData.putString(getString(R.string.extra_parent_id), narrativeId);
		messageData.putString(getString(R.string.extra_internal_id), frameId);
		mScrollHandler.sendMessage(message);
	}

	private void postDelayedScrollToSelectedFrame(String narrativeId, String frameId) {
		// intentionally not removing, so non-delayed call from onActivityResult is handled first (onResume = rotation)
		// mScrollHandler.removeMessages(R.id.msg_scroll_to_selected_frame);
		Message message = mScrollHandler
				.obtainMessage(R.id.msg_scroll_to_selected_frame, NarrativeBrowserActivity.this);
		Bundle messageData = message.getData();
		messageData.putString(getString(R.string.extra_parent_id), narrativeId);
		messageData.putString(getString(R.string.extra_internal_id), frameId);
		mScrollHandler.sendMessageDelayed(message, MediaPhone.ANIMATION_ICON_REFRESH_DELAY);
	}

	private class FingerTracker implements View.OnTouchListener {
		// multitouch events after API v11 were handled here, but now managed via android:splitMotionEvents="false"
		// in layout - see: http://stackoverflow.com/questions/5938970/
		public boolean onTouch(View view, MotionEvent event) {
			final int action = event.getAction();
			mFingerUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
			if (mFingerUp && mScrollState != ScrollManager.SCROLL_STATE_FLING) {
				postUpdateNarrativeIcons();
			}
			return false;
		}
	}

	private class SelectionTracker implements AdapterView.OnItemSelectedListener {
		public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
			if (mScrollState != ScrollManager.SCROLL_STATE_IDLE) {
				mScrollState = ScrollManager.SCROLL_STATE_IDLE;
				postUpdateNarrativeIcons();
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

	private void getAndSaveNarrativeId(AdapterView<?> parent) {
		if (parent instanceof HorizontalListView) {
			mCurrentSelectedNarrativeId = ((FrameAdapter) ((HorizontalListView) parent).getAdapter()).getParentFilter();
			return;
		}
		mCurrentSelectedNarrativeId = null;
	}

	private class FrameClickListener implements AdapterView.OnItemClickListener {
		public void onItemClick(AdapterView<?> parent, View view, int position, long insertNewFrameAfter) {
			if (view != null && parent != null) {
				getAndSaveNarrativeId(parent);
				final FrameViewHolder holder = (FrameViewHolder) view.getTag();
				if (FrameItem.LOADING_FRAME_ID.equals(holder.frameInternalId)) {
					return; // don't allow clicking on the loading frame
				} else if (FrameItem.KEY_FRAME_ID_START.equals(holder.frameInternalId)
						|| FrameItem.KEY_FRAME_ID_END.equals(holder.frameInternalId)) {
					if (FrameItem.KEY_FRAME_ID_START.equals(holder.frameInternalId)) {
						insertFrameAfter(mCurrentSelectedNarrativeId, FrameItem.KEY_FRAME_ID_START);
					} else {
						insertFrameAfter(mCurrentSelectedNarrativeId, null); // null = insert at end
					}
				} else if (insertNewFrameAfter != 0) {
					insertFrameAfter(mCurrentSelectedNarrativeId, holder.frameInternalId);
				} else {
					editFrame(holder.frameInternalId);
				}
			}
		}
	}

	private class FrameLongClickListener implements AdapterView.OnItemLongClickListener {
		@Override
		// this is a hack to allow long pressing one or two items via the same listener
		public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long insertNewFrameAfter) {
			if (view != null && parent != null) {
				getAndSaveNarrativeId(parent);
				final FrameViewHolder holder = (FrameViewHolder) view.getTag();
				if (insertNewFrameAfter != 0) {
					// used to be just on single press, but that made it confusing when a long double press did nothing
					insertFrameAfter(mCurrentSelectedNarrativeId, holder.frameInternalId);
				} else {
					playNarrative(holder.frameInternalId);
				}
			}
			return true;
		}
	}

	private void insertFrameAfter(String parentId, String insertAfterId) {
		final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this, FrameEditorActivity.class);
		frameEditorIntent.putExtra(getString(R.string.extra_parent_id), parentId);
		frameEditorIntent.putExtra(getString(R.string.extra_insert_after_id), insertAfterId);
		startActivityForResult(frameEditorIntent, MediaPhone.R_id_intent_frame_editor);
	}

	private void editFrame(String frameId) {
		final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this, FrameEditorActivity.class);
		frameEditorIntent.putExtra(getString(R.string.extra_internal_id), frameId);
		startActivityForResult(frameEditorIntent, MediaPhone.R_id_intent_frame_editor);
	}

	private void playNarrative(String startFrameId) {
		final Intent framePlayerIntent = new Intent(NarrativeBrowserActivity.this, PlaybackActivity.class);
		framePlayerIntent.putExtra(getString(R.string.extra_internal_id), startFrameId);
		startActivityForResult(framePlayerIntent, MediaPhone.R_id_intent_narrative_player);
	}

	private void addNarrative() {
		// add a narrative by not passing a parent id - new narrative is created in FrameEditorActivity
		if (NarrativesManager.getTemplatesCount(getContentResolver()) > 0) {
			final CharSequence[] items = { getString(R.string.add_blank), getString(R.string.add_template) };
			AlertDialog.Builder builder = new AlertDialog.Builder(NarrativeBrowserActivity.this);
			builder.setTitle(R.string.title_add);
			builder.setIcon(android.R.drawable.ic_dialog_info);
			builder.setNegativeButton(R.string.button_cancel, null);
			builder.setItems(items, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int item) {
					mNarratives.setSelectionFromTop(0, 0); // so that the new narrative is visible
					switch (item) {
						case 0:
							final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this,
									FrameEditorActivity.class);
							startActivityForResult(frameEditorIntent, MediaPhone.R_id_intent_frame_editor);
							break;
						case 1:
							final Intent templateBrowserIntent = new Intent(NarrativeBrowserActivity.this,
									TemplateBrowserActivity.class);
							startActivityForResult(templateBrowserIntent, MediaPhone.R_id_intent_template_browser);
							break;
					}
					dialog.dismiss();
				}
			});
			AlertDialog alert = builder.create();
			alert.show();
		} else {
			final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this, FrameEditorActivity.class);
			startActivityForResult(frameEditorIntent, MediaPhone.R_id_intent_frame_editor);
		}
	}

	private void searchRecursivelyForNarratives(File[] importedFiles, ArrayList<String> processedFiles) {
		if (importedFiles != null) {
			// depth-first so we can delete directories on completion
			for (File newFile : importedFiles) {
				if (newFile.isDirectory()) {
					searchRecursivelyForNarratives(newFile.listFiles(), processedFiles);
				}
			}
			for (File newFile : importedFiles) {
				String rootName = newFile.getName();
				if (!newFile.isDirectory()
						&& (rootName.endsWith(MediaUtilities.SMIL_FILE_EXTENSION) || rootName
								.endsWith(MediaUtilities.SYNC_FILE_EXTENSION))) {
					rootName = rootName.replaceAll(MediaUtilities.SYNC_FILE_EXTENSION, "");
					rootName = rootName.replaceAll(MediaUtilities.SMIL_FILE_EXTENSION, "");
					if (!processedFiles.contains(rootName)) {
						((MediaPhoneApplication) getApplication()).sendBluetoothFileHint(newFile.getAbsolutePath()
								.replace(MediaPhone.IMPORT_DIRECTORY, "")); // TODO: can we catch import errors?
						processedFiles.add(rootName);
					}
				}
			}
		}
	}

	@Override
	protected void onBluetoothServiceRegistered() {
		if (mScanningForNarratives) {
			mScanningForNarratives = false; // so we don't repeat this process

			File[] importedFiles = new File(MediaPhone.IMPORT_DIRECTORY).listFiles();
			if (importedFiles == null) {
				UIUtilities.showToast(NarrativeBrowserActivity.this, R.string.narrative_folder_not_found, true);
			} else {
				ArrayList<String> processedFiles = new ArrayList<String>();
				searchRecursivelyForNarratives(importedFiles, processedFiles);
				if (processedFiles.size() <= 0) {
					UIUtilities.showFormattedToast(NarrativeBrowserActivity.this, R.string.narrative_import_not_found,
							MediaPhone.IMPORT_DIRECTORY.replace("/mnt/", "").replace("/data/", ""));
				} else {
					UIUtilities.showToast(NarrativeBrowserActivity.this, R.string.import_starting);
				}
			}

			// re-enable/disable bluetooth watcher, if applicable
			SharedPreferences mediaPhoneSettings = PreferenceManager
					.getDefaultSharedPreferences(NarrativeBrowserActivity.this);
			configureBluetoothObserver(mediaPhoneSettings, getResources());
		}
	}

	private void importNarratives() {
		mScanningForNarratives = true;

		// temporarily, so that even if the observer is disabled, we can watch files; see onBluetoothServiceRegistered
		// to detect writes in bluetooth dir, allow non-bt scanning (and clear saved file lists)
		if (!((MediaPhoneApplication) getApplication()).startWatchingBluetooth(true)) {
			mScanningForNarratives = false;
			UIUtilities.showToast(NarrativeBrowserActivity.this, R.string.narrative_folder_not_found, true);
		}
	}

	public void handleButtonClicks(View currentButton) {
		if (!verifyButtonClick(currentButton)) {
			return;
		}

		switch (currentButton.getId()) {
			case R.id.header_add_narrative:
				addNarrative();
				break;
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case MediaPhone.R_id_intent_frame_editor:
				// mNarrativeAdapter.notifyDataSetChanged(); // don't use this method - forgets scroll position
				postDelayedUpdateNarrativeIcons(); // will repeat until all icons are loaded; intentionally pass through
			case MediaPhone.R_id_intent_narrative_player:
				// scroll to the edited/played frame (note: the frame (or the narrative) could have been deleted)
				// we don't use the activity result because if they've done next/prev we will only get the original id
				String frameId = loadLastEditedFrame();
				if (mCurrentSelectedNarrativeId != null && frameId != null) {
					postScrollToSelectedFrame(mCurrentSelectedNarrativeId, frameId);
				}
				break;

			case MediaPhone.R_id_intent_template_browser:
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}
