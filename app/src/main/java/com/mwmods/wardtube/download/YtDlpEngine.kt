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

    /** Pulls the latest yt-dlp. YouTube changes often, so an outdated engine is the usual cause of failures. */
    @Synchronized
    fun update(context: Context): Boolean {
        if (!ensureReady(context)) return false
        return try {
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
