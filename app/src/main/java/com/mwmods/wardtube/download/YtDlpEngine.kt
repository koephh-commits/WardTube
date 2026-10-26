// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** Starts and updates the bundled yt-dlp + ffmpeg. Everything runs on the device; no third-party website is used. */
object YtDlpEngine {

    @Volatile
    private var ready = false

    /**
     * True once an update was tried in this process, so a failing link does not trigger one update after another
     * (only matters when automatic updating is on: otherwise the engine is only updated from the menu).
     */
    @Volatile
    var updateAttempted = false
        private set

    /** Unpacks the engine on first use (a few seconds). Blocking: call from a background thread. */
    @Synchronized
    fun ensureReady(context: Context): Boolean {
        if (ready) return true
        return try {
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Pulls the latest yt-dlp. YouTube changes often, so an outdated engine is the usual cause of failures.
     * Nothing calls this by itself: it runs when the person taps "Update download engine", and after a failure only
     * when they turned on automatic updating ([com.mwmods.wardtube.Prefs.autoUpdateEngine], off by default).
     */
    @Synchronized
    fun update(context: Context): Boolean {
        if (!ensureReady(context)) return false
        updateAttempted = true
        return try {
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
