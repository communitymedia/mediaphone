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

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replacement for the older AsyncTask version: shared background thread pools, and a way to post results to UI threads
 */
public final class BackgroundTasks {

	private static final Handler MAIN_THREAD_HANDLER = new Handler(Looper.getMainLooper());

	// for general (potentially long-running) work, such as queued tasks, imports and exports - these loop over their
	// own queues, so we use cached thread pool to stop them blocking each other (idle threads discarded after 60s)
	private static final ExecutorService GENERAL_EXECUTOR = Executors.newCachedThreadPool(
			new BackgroundThreadFactory("MediaPhone-background"));

	// for loading screen-sized images - limited intentionally because we don't want to run out of memory
	private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(
			Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() - 1, 4)),
			new BackgroundThreadFactory("MediaPhone-image"));

	private BackgroundTasks() {
	}

	public static void execute(Runnable task) {
		GENERAL_EXECUTOR.execute(task);
	}

	public static Future<?> submitImageTask(Runnable task) {
		return IMAGE_EXECUTOR.submit(task);
	}

	public static void runOnMainThread(Runnable task) {
		MAIN_THREAD_HANDLER.post(task);
	}

	private static class BackgroundThreadFactory implements ThreadFactory {
		private final String mNamePrefix;
		private final AtomicInteger mCount = new AtomicInteger(1);

		private BackgroundThreadFactory(String namePrefix) {
			mNamePrefix = namePrefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			// match AsyncTask's behaviour of running at background priority so we don't compete with the UI thread
			return new Thread(() -> {
				Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
				r.run();
			}, mNamePrefix + "-" + mCount.getAndIncrement());
		}
	}
}
