/*
 *  Copyright (C) 2026 Simon Robinson
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

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;

/**
 * Holds the (non-cancellable) progress dialogs shown while background tasks are running, replacing the previous
 * Activity.showDialog() / onCreateDialog() approach (which is now deprecated). This newer approach means we can also
 * use the FragmentManager to restore dialogs after configuration changes. See {@link MediaPhoneActivity#createProgressDialog(int)}
 * for dialog configurations.
 */
public class ProgressDialogFragment extends AppCompatDialogFragment {

	private static final String ARG_DIALOG_ID = "dialog_id";

	public ProgressDialogFragment() {
		// required for recreation by FragmentManager
	}

	static ProgressDialogFragment newInstance(int dialogId) {
		ProgressDialogFragment fragment = new ProgressDialogFragment();
		Bundle arguments = new Bundle();
		arguments.putInt(ARG_DIALOG_ID, dialogId);
		fragment.setArguments(arguments);
		return fragment;
	}

	static String tagFor(int dialogId) {
		return "progress_dialog_" + dialogId;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setCancelable(false);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		return ((MediaPhoneActivity) requireActivity()).createProgressDialog(requireArguments().getInt(ARG_DIALOG_ID));
	}
}
