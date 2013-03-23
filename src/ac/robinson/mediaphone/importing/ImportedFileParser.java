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

package ac.robinson.mediaphone.importing;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.FramesManager;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediautilities.FrameMediaContainer;
import ac.robinson.mediautilities.HTMLUtilities;
import ac.robinson.mediautilities.MediaUtilities;
import ac.robinson.mediautilities.SMILUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;

public class ImportedFileParser {

	public static ArrayList<FrameMediaContainer> importHTMLNarrative(ContentResolver contentResolver, File htmlFile,
			int sequenceIncrement) {
		ArrayList<FrameMediaContainer> htmlFrames = HTMLUtilities.getHTMLFrameList(htmlFile, sequenceIncrement);
		htmlFrames = importNarrativeAndFormatFrames(contentResolver, htmlFrames);
		if (MediaPhone.IMPORT_DELETE_AFTER_IMPORTING) {
			htmlFile.delete();
		}
		return htmlFrames;
	}

	public static ArrayList<FrameMediaContainer> importMOVNarrative(File movFile) {
		// not doing this at the moment
		// if we do, must always delete rather than copy/move because bluetooth files renamed by the system are not
		// deletable by our process, so will leave 0kb stub remaining
		return null;
	}

	public static ArrayList<FrameMediaContainer> importSMILNarrative(ContentResolver contentResolver, File smilFile,
			int sequenceIncrement) {
		ArrayList<FrameMediaContainer> smilFrames = SMILUtilities.getSMILFrameList(smilFile, sequenceIncrement,
				MediaPhone.IMPORT_DELETE_AFTER_IMPORTING);
		smilFrames = importNarrativeAndFormatFrames(contentResolver, smilFrames);
		if (MediaPhone.IMPORT_DELETE_AFTER_IMPORTING) {
			// delete the temporary files that could be remaining (sync file will be deleted automatically)
			new File(smilFile.getParent(), smilFile.getName().replace(MediaUtilities.SYNC_FILE_EXTENSION, "")
					+ MediaUtilities.SMIL_FILE_EXTENSION).delete();
			smilFile.delete();

			// delete the directory if we're importing from a sub-directory of the import location
			// (will fail if media still exists, but needed here if we're importing a text-only narrative)
			File parentFile = new File(smilFile.getParent());
			if (parentFile.getAbsolutePath().startsWith(MediaPhone.IMPORT_DIRECTORY)) {
				if (!parentFile.getAbsolutePath().equals(new File(MediaPhone.IMPORT_DIRECTORY).getAbsolutePath())) {
					parentFile.delete();
				}
			}
		}
		return smilFrames;
	}

	private static ArrayList<FrameMediaContainer> importNarrativeAndFormatFrames(ContentResolver contentResolver,
			ArrayList<FrameMediaContainer> frames) {
		if (frames != null && frames.size() > 0) {

			int narrativeExternalId = NarrativesManager.getNextNarrativeExternalId(contentResolver);
			NarrativeItem newNarrative = new NarrativeItem(narrativeExternalId);
			NarrativesManager.addNarrative(contentResolver, newNarrative);

			// add the narrative's id to each frame
			String narrativeInternalId = newNarrative.getInternalId();
			for (FrameMediaContainer frame : frames) {
				frame.mParentId = narrativeInternalId;
			}

		}
		return frames;
	}

	public static void importNarrativeFrame(Resources res, ContentResolver contentResolver, FrameMediaContainer frame) {
		if (MediaPhone.DEBUG)
			Log.d(DebugUtilities.getLogTag(frame), "Importing narrative frame " + frame.mFrameId);

		// directory is automatically created here
		FrameItem newFrame = new FrameItem(frame.mParentId, frame.mFrameSequenceId);
		File parentDirectory = null;

		if (!TextUtils.isEmpty(frame.mTextContent)) {
			String textUUID = MediaPhoneProvider.getNewInternalId();
			File textContentFile = MediaItem
					.getFile(newFrame.getInternalId(), textUUID, MediaPhone.EXTENSION_TEXT_FILE);

			if (textContentFile != null) {
				try {
					FileWriter fileWriter = new FileWriter(textContentFile);
					BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
					bufferedWriter.write(frame.mTextContent);
					bufferedWriter.close();
				} catch (Exception e) {
				}

				MediaItem textMediaItem = new MediaItem(textUUID, newFrame.getInternalId(),
						MediaPhone.EXTENSION_TEXT_FILE, MediaPhoneProvider.TYPE_TEXT);
				MediaManager.addMedia(contentResolver, textMediaItem);
			}
		}

		if (frame.mImagePath != null) {
			String imageUUID = MediaPhoneProvider.getNewInternalId();
			// preserve the original file extension so we know if we can edit this item later on
			// (earlier versions of the application used different file formats for some items)
			String existingFileExtension = IOUtilities.getFileExtension(frame.mImagePath);
			File imageContentFile = MediaItem.getFile(newFrame.getInternalId(), imageUUID, existingFileExtension);

			File sourceImageFile = new File(frame.mImagePath);
			try {
				IOUtilities.copyFile(sourceImageFile, imageContentFile);
				if (MediaPhone.IMPORT_DELETE_AFTER_IMPORTING) {
					parentDirectory = sourceImageFile.getParentFile();
					sourceImageFile.delete();
				}
			} catch (IOException e) {
				// error copying (we copy rather than rename because it could be on a different mount point...)
			}
			if (imageContentFile.exists()) {
				MediaItem imageMediaItem = new MediaItem(imageUUID, newFrame.getInternalId(), existingFileExtension,
						(frame.mImageIsFrontCamera ? MediaPhoneProvider.TYPE_IMAGE_FRONT
								: MediaPhoneProvider.TYPE_IMAGE_BACK));
				MediaManager.addMedia(contentResolver, imageMediaItem);
				// TODO: add to media library?
			}
		}

		if (frame.mAudioPaths.size() > 0) {
			int audioId = 0;
			for (String audioPath : frame.mAudioPaths) {
				String audioUUID = MediaPhoneProvider.getNewInternalId();
				String existingFileExtension = IOUtilities.getFileExtension(audioPath);
				File audioContentFile = MediaItem.getFile(newFrame.getInternalId(), audioUUID, existingFileExtension);

				File sourceAudioFile = new File(audioPath);
				try {
					IOUtilities.copyFile(sourceAudioFile, audioContentFile);
					if (MediaPhone.IMPORT_DELETE_AFTER_IMPORTING) {
						parentDirectory = sourceAudioFile.getParentFile();
						sourceAudioFile.delete();
					}
				} catch (IOException e) {
					// error copying (we copy rather than rename because it could be on a different mount point...)
				}
				if (audioContentFile.exists()) {
					MediaItem audioMediaItem = new MediaItem(audioUUID, newFrame.getInternalId(),
							existingFileExtension, MediaPhoneProvider.TYPE_AUDIO);
					audioMediaItem.setDurationMilliseconds(frame.mAudioDurations.get(audioId));
					MediaManager.addMedia(contentResolver, audioMediaItem);
					// TODO: add to media library?
				}
				audioId += 1;
			}
		}

		// if importing from a subdirectory, delete the directory (will only delete if empty)
		if (MediaPhone.IMPORT_DELETE_AFTER_IMPORTING && parentDirectory != null) {
			if (parentDirectory.getAbsolutePath().startsWith(MediaPhone.IMPORT_DIRECTORY)) {
				if (!parentDirectory.getAbsolutePath().equals(new File(MediaPhone.IMPORT_DIRECTORY).getAbsolutePath())) {
					parentDirectory.delete();
				}
			}
		}

		FramesManager.addFrameAndPreloadIcon(res, contentResolver, newFrame);
	}
}
