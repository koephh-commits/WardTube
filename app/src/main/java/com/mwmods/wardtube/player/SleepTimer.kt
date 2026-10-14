// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.mwmods.wardtube.R

/**
 * Stops playback after a chosen number of minutes. It lives for as long as the app process does
 * (the playback service keeps the process alive while a video plays in the background).
 *
 * One clock only: the end time is kept in elapsedRealtime (it keeps counting while the phone sleeps),
 * and both the display and the stop use it. The timer re-checks itself every few seconds and whenever
 * the app asks through [checkExpired], so it cannot drift from what is shown on screen.
 */
object SleepTimer {

    const val MAX_MINUTES = 24 * 60

    private val handler = Handler(Looper.getMainLooper())
    private var endsAt = 0L
    private var app: Context? = null

    private val poll = object : Runnable {
        override fun run() {
            checkExpired()
            if (isActive) handler.postDelayed(this, remainingMs.coerceIn(250L, 15_000L))
        }
    }

    val isActive: Boolean get() = endsAt != 0L

    private val remainingMs: Long
        get() = if (endsAt == 0L) 0L else (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /** Whole minutes left, rounded up; 0 when no timer is running. */
    val remainingMinutes: Int get() = ((remainingMs + 59_999L) / 60_000L).toInt()

    /** Seconds left, rounded up; 0 when no timer is running. */
    val remainingSeconds: Int get() = ((remainingMs + 999L) / 1000L).toInt()

    /** Starts (or restarts) the timer for 1..[MAX_MINUTES] minutes. */
    fun start(context: Context, minutes: Int) {
        cancel()
        app = context.applicationContext
        endsAt = SystemClock.elapsedRealtime() + minutes.coerceIn(1, MAX_MINUTES) * 60_000L
        handler.postDelayed(poll, 1000L)
    }

    /** Stops playback if the time is up. Safe to call as often as needed. */
    fun checkExpired() {
        if (endsAt == 0L || SystemClock.elapsedRealtime() < endsAt) return
        val context = app
        cancel()
        // Only say "playback stopped" if something was really playing.
        PlayerController.queryState { state ->
            PlayerController.pause()
            if (context != null) {
                PlaybackService.stop(context)
                if (state?.playing == true) {
                    Toast.makeText(context, R.string.sleepTimerDone, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun cancel() {
        handler.removeCallbacks(poll)
        endsAt = 0L
    }
}
