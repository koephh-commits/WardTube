// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The touch gestures of the built-in player ([PlayerActivity]), the ones a video app has:
 *
 * - **Double tap**: the right half of the screen moves 10 seconds forward, the left half 10 seconds back. It works for a
 *   video and for a song alike. Right is forward on every phone language: it is the side of the screen, not the reading
 *   direction.
 * - **Quick taps after a double tap** keep adding 10 seconds in the same direction (20, 30, ...) as long as each one comes
 *   less than [CHAIN_MS] after the last. A tap on the other half starts again from 10 in that direction.
 * - **A sideways swipe on a picture** moves the position: the finger's distance becomes time ([spanFor]) and the new place is
 *   shown while the finger is down; the video jumps there when the finger leaves (one seek, not one per move, so a slow
 *   phone is not flooded).
 *
 * Where the gestures are read: the player view gives them the touches that no control took (a button or the time bar that is
 * touched keeps its own touch, so scrubbing the bar or pressing next twice quickly never seeks), and the lyrics list gives
 * them the second tap of a double tap ([lyricsListener]) while its own scrolling and line taps stay as they were.
 *
 * A single tap is not given to the player view at once: it waits [doubleTapTimeout] to see if a second tap follows, and then
 * [Host.onSingleTap] shows or hides the controls. Without this wait the controls would flicker at every double tap. A tap on a
 * line of the lyrics waits the same way ([afterTapWindow]).
 *
 * Nothing here moves the player itself: the owner does it through [Host].
 */
internal class PlayerGestures(context: Context, private val host: Host) {

    interface Host {
        /** The width of the touch surface in pixels (the screen's half decides the direction of a double tap). */
        fun surfaceWidth(): Int

        /** The file can be moved to another place (it is loaded, its length is known). False: taps and swipes only do what they did before. */
        fun canSeek(): Boolean

        /** A picture (not only a song) is on the screen: the only thing a sideways swipe moves. */
        fun canSwipeSeek(): Boolean

        /** Where the file is now, in milliseconds. */
        fun positionMs(): Long

        /** The length of the file in milliseconds; 0 or less when it is not known. */
        fun durationMs(): Long

        /** A double tap, or a quick tap after one: move by [deltaMs] (negative = back). [totalSeconds] is what the run of taps has moved so far, for the label. */
        fun onTapSeek(deltaMs: Long, forward: Boolean, totalSeconds: Int)

        /** A swipe began: show the place it is going to. */
        fun onSwipeStart()

        /** The finger moved: [targetMs] is where the file would go, [deltaMs] how far that is from where it was. */
        fun onSwipeMove(targetMs: Long, deltaMs: Long)

        /** The finger left ([commit] true: go to [targetMs]) or the touch was taken away ([commit] false: stay). */
        fun onSwipeEnd(targetMs: Long, deltaMs: Long, commit: Boolean)

        /** A single tap that is not half of a double tap: show or hide the controls. */
        fun onSingleTap()
    }

    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop
    private val doubleTapSlop = config.scaledDoubleTapSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())

    // The last tap that ended (uptime of its release; 0 = none), to know if the next one is its pair.
    private var lastTapTime = 0L
    private var lastTapRawX = 0f
    private var lastTapRawY = 0f

    // The run of quick taps: while the clock is before chainUntil every tap moves again.
    private var chainUntil = 0L
    private var chainForward = true
    private var chainSeconds = 0

    // The touch being followed on the player view.
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var swiping = false
    private var swipeStartMs = 0L
    private var swipeTargetMs = 0L

    /** This touch was a double tap (or a quick tap): it did its work when it went down, the rest of it is ignored. */
    private var tapSeek = false

    private val singleTap = Runnable { host.onSingleTap() }
    private var pendingAfterWindow: Runnable? = null

    // ---- The player view ----

    /** Gets every touch of the player view that no control took. Always true: the whole touch is wanted, down to its end. */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                moved = false
                swiping = false
                tapSeek = false
                if (isSecondTap(ev)) {
                    tapSeek = true
                    step(ev.x, ev.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!tapSeek) onMove(ev)
            MotionEvent.ACTION_UP -> when {
                tapSeek -> Unit
                swiping -> endSwipe(commit = true)
                !moved -> onTap(ev)
            }
            MotionEvent.ACTION_CANCEL -> if (swiping) endSwipe(commit = false)
        }
        return true
    }

    private fun onMove(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            moved = true
            // Only a sideways drag on a picture is a swipe; any other drag does nothing (it is not a tap either).
            if (abs(dx) > abs(dy) && host.canSwipeSeek() && host.durationMs() > 0L) {
                swiping = true
                swipeStartMs = host.positionMs()
                swipeTargetMs = swipeStartMs
                // The swipe takes the place of a tap that was waiting to be told apart from a double tap.
                handler.removeCallbacks(singleTap)
                lastTapTime = 0L
                host.onSwipeStart()
            }
        }
        if (swiping) {
            val duration = host.durationMs()
            if (duration <= 0L) return
            val width = host.surfaceWidth().coerceAtLeast(1)
            val delta = (dx / width * spanFor(duration)).toLong()
            swipeTargetMs = (swipeStartMs + delta).coerceIn(0L, duration)
            host.onSwipeMove(swipeTargetMs, swipeTargetMs - swipeStartMs)
        }
    }

    private fun endSwipe(commit: Boolean) {
        swiping = false
        host.onSwipeEnd(swipeTargetMs, swipeTargetMs - swipeStartMs, commit)
    }

    /** What a touch that went up without moving does: a held press is a plain tap, a short one waits to see if another follows. */
    private fun onTap(ev: MotionEvent) {
        if (ev.eventTime - downTime > longPressTimeout || !host.canSeek()) {
            // Not half of a double tap (held long, or nothing to move): the controls answer at once, as they always did.
            lastTapTime = 0L
            handler.removeCallbacks(singleTap)
            host.onSingleTap()
            return
        }
        lastTapTime = ev.eventTime
        lastTapRawX = ev.rawX
        lastTapRawY = ev.rawY
        handler.removeCallbacks(singleTap)
        handler.postDelayed(singleTap, doubleTapTimeout)
    }

    // ---- The lyrics list ----

    /**
     * For the lyrics list of a song: it keeps scrolling and line taps as they were, and only the second tap of a double tap (or a
     * quick tap after one) is taken from it, to move the song. The first tap of a pair is seen, not taken: a line tap that comes
     * from it waits for the second one ([afterTapWindow]).
     */
    fun lyricsListener(): RecyclerView.OnItemTouchListener = object : RecyclerView.OnItemTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startTime = 0L
        private var lyricsMoved = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    startTime = e.eventTime
                    lyricsMoved = false
                    if (isSecondTap(e)) {
                        step(e.x, e.eventTime)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE ->
                    if (abs(e.x - startX) > touchSlop || abs(e.y - startY) > touchSlop) lyricsMoved = true
                MotionEvent.ACTION_UP -> if (!lyricsMoved && e.eventTime - startTime <= longPressTimeout) {
                    lastTapTime = e.eventTime
                    lastTapRawX = e.rawX
                    lastTapRawY = e.rawY
                }
            }
            return false
        }

        // The second tap did all its work on the way down: nothing is left to do with the rest of it.
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    /**
     * Runs [action] once it is clear that the tap was not the first of a double tap: now when the file cannot be moved (there
     * is no double tap then), otherwise after the double-tap time. A double tap in between drops it.
     */
    fun afterTapWindow(action: () -> Unit) {
        if (!host.canSeek()) {
            action()
            return
        }
        pendingAfterWindow?.let { handler.removeCallbacks(it) }
        val run = Runnable {
            pendingAfterWindow = null
            action()
        }
        pendingAfterWindow = run
        handler.postDelayed(run, doubleTapTimeout)
    }

    // ---- Shared ----

    /** The touch that just went down is the second tap of a double tap, or a quick tap of a run that is going on. */
    private fun isSecondTap(ev: MotionEvent): Boolean {
        if (!host.canSeek()) return false
        if (ev.eventTime < chainUntil) return true
        return lastTapTime != 0L &&
            ev.eventTime - lastTapTime <= doubleTapTimeout &&
            hypot(ev.rawX - lastTapRawX, ev.rawY - lastTapRawY) <= doubleTapSlop
    }

    /** One 10-second step to the side of the screen that was tapped; also what a tap that was waiting for its pair is dropped for. */
    private fun step(x: Float, now: Long) {
        handler.removeCallbacks(singleTap)
        pendingAfterWindow?.let { handler.removeCallbacks(it) }
        pendingAfterWindow = null
        lastTapTime = 0L
        val forward = x >= host.surfaceWidth() / 2f
        chainSeconds = if (now < chainUntil && forward == chainForward) chainSeconds + STEP_SECONDS else STEP_SECONDS
        chainForward = forward
        chainUntil = now + CHAIN_MS
        host.onTapSeek(if (forward) STEP_MS else -STEP_MS, forward, chainSeconds)
    }

    /** How much of the file a swipe over the whole width of the screen moves: an eighth of it, never less than a minute nor more than five (or the whole file if it is shorter). */
    private fun spanFor(durationMs: Long): Long =
        (durationMs / 8L).coerceIn(MIN_SPAN_MS, MAX_SPAN_MS).coerceAtMost(durationMs)

    /** Drops everything that is waiting (the screen is left, or goes into the little window). */
    fun cancel() {
        handler.removeCallbacks(singleTap)
        pendingAfterWindow?.let { handler.removeCallbacks(it) }
        pendingAfterWindow = null
        lastTapTime = 0L
        chainUntil = 0L
        if (swiping) endSwipe(commit = false)
    }

    private companion object {
        const val STEP_SECONDS = 10
        const val STEP_MS = STEP_SECONDS * 1000L

        /** A tap this soon after the last step moves again, without a new double tap. */
        const val CHAIN_MS = 700L

        const val MIN_SPAN_MS = 60_000L
        const val MAX_SPAN_MS = 300_000L
    }
}
