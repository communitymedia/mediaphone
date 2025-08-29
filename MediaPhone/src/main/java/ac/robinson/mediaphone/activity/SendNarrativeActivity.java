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

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;

public class SendNarrativeActivity extends MediaPhoneActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// this activity exists solely to allow sending/saving of files created in a background task
		final Intent intent = getIntent();
		if (intent != null) {
			Object sendExtra = intent.getSerializableExtra(getString(R.string.extra_exported_content));
			if (sendExtra instanceof ArrayList<?> sendExtraFiles) {
                ArrayList<Uri> filesToSend = new ArrayList<>();
				for (Object file : sendExtraFiles) {
					if (file instanceof Uri) {
						filesToSend.add((Uri) file);
					}
				}
				sendFiles(filesToSend);
			}
		}

		finish();
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// nothing to do
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		// nothing to do
	}
}
