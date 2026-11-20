// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import android.content.Intent
import com.mwmods.wardtube.Prefs

/**
 * The two download settings of the Library: how many downloads run at the same time ([parallel]), and the speed limit of
 * each one ([speedLimitKb], KiB per second, 0 = no limit).
 *
 * The download service runs in its own process, so it cannot read the app's settings as they change. The values travel
 * to it with every start request ([putInto]) and, while it is running, in a package-restricted broadcast ([announce])
 * whenever the person changes them. A service that is not running is not started for that: the next start request
 * carries the values.
 */
data class DownloadLimits(val parallel: Int, val speedLimitKb: Int) {

    /** The speed limit in bytes per second; 0 = no limit. */
    val bytesPerSecond: Long
        get() = speedLimitKb.coerceAtLeast(0) * 1024L

    fun putInto(intent: Intent): Intent =
        intent.putExtra(EXTRA_PARALLEL, parallel).putExtra(EXTRA_SPEED_KB, speedLimitKb)

    /** Tells a running download service about these settings. Nothing happens when it is not running. */
    fun announce(context: Context) {
        runCatching {
            context.sendBroadcast(putInto(Intent(ACTION_CHANGED).setPackage(context.packageName)))
        }
    }

    companion object {
        const val ACTION_CHANGED = "com.mwmods.wardtube.DOWNLOAD_LIMITS"
        private const val EXTRA_PARALLEL = "maxParallel"
        private const val EXTRA_SPEED_KB = "speedLimitKb"

        fun of(prefs: Prefs) = DownloadLimits(prefs.maxParallelDownloads, prefs.speedLimitKb)

        /** The settings an intent carries, or null when it carries none (a pause / cancel / resume request). */
        fun from(intent: Intent?): DownloadLimits? {
            if (intent == null || !intent.hasExtra(EXTRA_PARALLEL)) return null
            return DownloadLimits(
                parallel = intent.getIntExtra(EXTRA_PARALLEL, Prefs.DEFAULT_PARALLEL_DOWNLOADS)
                    .coerceIn(1, Prefs.MAX_PARALLEL_DOWNLOADS),
                speedLimitKb = intent.getIntExtra(EXTRA_SPEED_KB, 0).coerceAtLeast(0),
            )
        }
    }
}
