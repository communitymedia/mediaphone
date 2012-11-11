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
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.NarrativeAdapter;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediaphone.view.FrameViewHolder;
import ac.robinson.mediaphone.view.HorizontalListView;
import ac.robinson.mediaphone.view.NarrativeViewHolder;
import ac.robinson.mediaphone.view.NarrativesListView;
import ac.robinson.util.ImageCacheUtilities;
import ac.robinson.util.UIUtilities;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Gravity;
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
	private String mCurrentSelectedNarrativeId;

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

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		UIUtilities.configureActionBar(this, false, true, R.string.narrative_list_header, 0);
		setContentView(R.layout.narrative_browser);

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mCurrentSelectedNarrativeId = savedInstanceState.getString(getString(R.string.extra_internal_id));
		}

		initialiseNarrativesView();
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putString(getString(R.string.extra_internal_id), mCurrentSelectedNarrativeId);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	protected void onResume() {
		super.onResume();
		// reload previous scroll position
		SharedPreferences rotationSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		mNarratives.setSelectionFromTop(rotationSettings.getInt(getString(R.string.key_narrative_list_top), 0),
				rotationSettings.getInt(getString(R.string.key_narrative_list_position), 0));
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
			// TODO: delete deleted content
			updateListPositions(0, 0);
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
			case R.id.menu_add_narrative:
				addNarrative();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// no preferences apply to this activity
	}

	private void initialiseNarrativesView() {
		mNarratives = (NarrativesListView) findViewById(R.id.list_narratives);

		// for API 11 and above, buttons are in the action bar
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
			View headerRow = getLayoutInflater().inflate(R.layout.narratives_header, null, false);
			mNarratives.addHeaderView(headerRow, null, false); // false = not selectable
		}

		// originally used to fix selection highlights when using hardware button to select
		// now done by overriding isEnabled in NarrativeAdapter
		// mNarratives.setFocusable(false);
		// mNarratives.setFocusableInTouchMode(false);

		mNarrativeAdapter = new NarrativeAdapter(this, NarrativeItem.NARRATIVE_CONTENT_URI, true, false);
		mNarratives.setAdapter(mNarrativeAdapter);
		mNarratives.setOnScrollListener(new ScrollManager());
		mNarratives.setOnTouchListener(new FingerTracker());
		mNarratives.setOnItemSelectedListener(new SelectionTracker());
		mNarratives.setOnItemClickListener(new NarrativeViewer());

		// initial empty list placeholder
		TextView emptyView = new TextView(NarrativeBrowserActivity.this);
		emptyView.setGravity(Gravity.CENTER | Gravity.TOP);
		emptyView.setPadding(0, 80, 0, 0); // temporary
		emptyView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		emptyView.setText(getString(R.string.narrative_list_empty));
		emptyView.setVisibility(View.GONE);
		((ViewGroup) mNarratives.getParent()).addView(emptyView);
		mNarratives.setEmptyView(emptyView);

		mPopupPosition = getLayoutInflater().inflate(R.layout.popup_position, null);
		mPopupText = (TextView) mPopupPosition.findViewById(R.id.popup_text);
	}

	private void updateListPositions(int listTop, int listPosition) {
		SharedPreferences rotationSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor prefsEditor = rotationSettings.edit();
		prefsEditor.putInt(getString(R.string.key_narrative_list_top), listTop);
		prefsEditor.putInt(getString(R.string.key_narrative_list_position), listPosition);
		prefsEditor.commit(); // apply is better, but only in API > 8
	}

	public int getScrollState() {
		return mScrollState; // for NarrativeAdapter purposes
	}

	public boolean isPendingIconsUpdate() {
		return mPendingIconsUpdate; // for NarrativeAdapter purposes
	}

	public View getFrameAdapterEmptyView() {
		return mFrameAdapterEmptyView; // for FrameAdapter purposes
	}

	public void setFrameAdapterEmptyView(View view) {
		mFrameAdapterEmptyView = view; // for FrameAdapter purposes
	}

	public AdapterView.OnItemClickListener getFrameClickListener() {
		return mFrameClickListener; // for NarrativeAdapter purposes
	}

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
				final Handler handler = mScrollHandler;
				handler.removeMessages(R.id.msg_update_narrative_icons);
				final Message message = handler.obtainMessage(R.id.msg_update_narrative_icons,
						NarrativeBrowserActivity.this);
				handler.sendMessageDelayed(message, mFingerUp ? 0 : MediaPhone.ANIMATION_ICON_SHOW_DELAY);
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
		for (int i = 0, n = mNarratives.getChildCount(); i < n; i++) {
			final Object viewTag = mNarratives.getChildAt(i).getTag();
			if (viewTag instanceof NarrativeViewHolder) {
				final NarrativeViewHolder holder = (NarrativeViewHolder) viewTag;
				if (holder.queryIcons) {
					mNarrativeAdapter.attachAdapter(holder);
					holder.queryIcons = false;
				}
			}
		}
	}

	private void scrollToSelectedFrame(String narrativeId, String frameId) {
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

	private class ScrollHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_update_narrative_icons:
					((NarrativeBrowserActivity) msg.obj).updateNarrativeIcons();
					break;
				case R.id.msg_scroll_to_selected_frame:
					Bundle messageData = msg.getData();
					String narrativeId = messageData.getString(getString(R.string.extra_parent_id));
					String frameId = messageData.getString(getString(R.string.extra_internal_id));
					if (narrativeId != null && frameId != null) {
						((NarrativeBrowserActivity) msg.obj).scrollToSelectedFrame(narrativeId, frameId);
					}
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

	private void postScrollToSelectedFrame(String narrativeId, String frameId) {
		mScrollHandler.removeMessages(R.id.msg_scroll_to_selected_frame);
		Message message = mScrollHandler
				.obtainMessage(R.id.msg_scroll_to_selected_frame, NarrativeBrowserActivity.this);
		Bundle messageData = message.getData();
		messageData.putString(getString(R.string.extra_parent_id), narrativeId);
		messageData.putString(getString(R.string.extra_internal_id), frameId);
		// mScrollHandler.sendMessage(message);
		mScrollHandler.sendMessageDelayed(message, 200); // delayed so we have chance to layout first TODO: unreliable
	}

	private class FingerTracker implements View.OnTouchListener {
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
		public void onItemClick(AdapterView<?> parent, View view, int position, long insertNewFrameBefore) {
			if (view != null && parent != null) {
				getAndSaveNarrativeId(parent);
				final FrameViewHolder holder = (FrameViewHolder) view.getTag();
				if (FrameItem.KEY_FRAME_ID_START.equals(holder.frameInternalId)
						|| FrameItem.KEY_FRAME_ID_END.equals(holder.frameInternalId)) {
					if (FrameItem.KEY_FRAME_ID_START.equals(holder.frameInternalId)) {
						insertFrame(mCurrentSelectedNarrativeId, FrameItem.KEY_FRAME_ID_START);
					} else {
						insertFrame(mCurrentSelectedNarrativeId, null);
					}
				} else if (insertNewFrameBefore != 0) {
					insertFrame(mCurrentSelectedNarrativeId, holder.frameInternalId);
				} else {
					editFrame(holder.frameInternalId);
				}
			}
		}
	}

	private class FrameLongClickListener implements AdapterView.OnItemLongClickListener {
		@Override
		// this is a hack to allow long pressing one or two items via the same listener
		public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long insertNewFrameBefore) {
			if (view != null && parent != null) {
				getAndSaveNarrativeId(parent);
				final FrameViewHolder holder = (FrameViewHolder) view.getTag();
				if (insertNewFrameBefore != 0) {
					// insertFrame(mCurrentSelectedNarrativeId, holder.frameInternalId);
				} else {
					playNarrative(holder.frameInternalId);
				}
			}
			return true;
		}
	}

	private void insertFrame(String parentId, String insertBeforeId) {
		final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this, FrameEditorActivity.class);
		frameEditorIntent.putExtra(getString(R.string.extra_parent_id), parentId);
		frameEditorIntent.putExtra(getString(R.string.extra_insert_before_id), insertBeforeId);
		startActivityForResult(frameEditorIntent, R.id.intent_frame_editor);
	}

	private void editFrame(String frameId) {
		final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this, FrameEditorActivity.class);
		frameEditorIntent.putExtra(getString(R.string.extra_internal_id), frameId);
		startActivityForResult(frameEditorIntent, R.id.intent_frame_editor);
	}

	private void playNarrative(String startFrameId) {
		final Intent framePlayerIntent = new Intent(NarrativeBrowserActivity.this, NarrativePlayerActivity.class);
		framePlayerIntent.putExtra(getString(R.string.extra_internal_id), startFrameId);
		startActivityForResult(framePlayerIntent, R.id.intent_narrative_player);
	}

	private void addNarrative() {
		// add a narrative by not passing a parent id - new narrative is created in FrameEditorActivity
		if (NarrativesManager.getTemplatesCount(getContentResolver()) > 0) {
			final CharSequence[] items = { getString(R.string.add_blank), getString(R.string.add_template) };
			AlertDialog.Builder builder = new AlertDialog.Builder(NarrativeBrowserActivity.this);
			builder.setTitle(R.string.title_add);
			builder.setIcon(android.R.drawable.ic_dialog_info);
			builder.setNegativeButton(android.R.string.cancel, null);
			builder.setItems(items, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int item) {
					mNarratives.setSelectionFromTop(0, 0); // so that the new narrative is visible
					switch (item) {
						case 0:
							final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this,
									FrameEditorActivity.class);
							startActivityForResult(frameEditorIntent, R.id.intent_frame_editor);
							break;
						case 1:
							final Intent templateBrowserIntent = new Intent(NarrativeBrowserActivity.this,
									TemplateBrowserActivity.class);
							startActivityForResult(templateBrowserIntent, R.id.intent_template_browser);
							break;
					}
					dialog.dismiss();
				}
			});
			AlertDialog alert = builder.create();
			alert.show();
		} else {
			final Intent frameEditorIntent = new Intent(NarrativeBrowserActivity.this, FrameEditorActivity.class);
			startActivityForResult(frameEditorIntent, R.id.intent_frame_editor);
		}
	}

	public void handleButtonClicks(View currentButton) {
		switch (currentButton.getId()) {
			case R.id.header_add_narrative:
				addNarrative();
				break;
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case R.id.intent_frame_editor:
				// mNarrativeAdapter.notifyDataSetChanged();
			case R.id.intent_narrative_player:

				// scroll to the edited/played frame (note: the frame (or the narrative) could have been deleted)
				// we don't use the activity result because if they've done next/prev we will only get the original id
				// if (resultIntent != null) {
				// String frameId = resultIntent.getStringExtra(getString(R.string.extra_internal_id));
				// })
				SharedPreferences frameIdSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME,
						Context.MODE_PRIVATE);
				String frameId = frameIdSettings.getString(getString(R.string.key_last_edited_frame), null);
				if (mCurrentSelectedNarrativeId != null && frameId != null) {
					postScrollToSelectedFrame(mCurrentSelectedNarrativeId, frameId);
				}
				mCurrentSelectedNarrativeId = null;
				break;

			case R.id.intent_template_browser:
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}