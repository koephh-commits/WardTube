// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The area of the Library that shows the list, where a sideways swipe moves between the filters (All / Video / Audio /
 * Lyrics).
 *
 * The [page] (the list with its empty-state text and spinner) follows the finger while it is dragged, and it fades a little
 * the further it goes. On letting go:
 * - far enough (a quarter of the width) or flicked: the page carries on out of the way, the screen puts the new filter's
 *   files in ([Host.onPageSwap]) and the new page comes in from the other side, slowing down into place;
 * - not far enough: the page settles back where it was;
 * - at either end of the filters (nothing further to go to): the page only gives a little, as if it were held by a rubber
 *   band, and returns.
 *
 * Only a clearly sideways drag takes the touch (the same test as a ViewPager): the rows below keep their taps, their long
 * press (which starts the range selection) and the vertical scrolling. A drag that starts after the long-press time never
 * counts, so moving the finger after a long press does not turn into a swipe.
 */
class FilterSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** What the screen decides; [fingerRight] is the direction the finger moves, whatever the layout direction. */
    interface Host {
        /** True when there is a filter to go to when the finger moves that way. */
        fun hasPage(fingerRight: Boolean): Boolean

        /** The swipe was accepted and the page is on its way out: the chips can follow already. */
        fun onPageChosen(fingerRight: Boolean)

        /** The old page is out of sight: put the new filter's files in. */
        fun onPageSwap()
    }

    var host: Host? = null

    /** The view that moves with the finger (set by the screen; nothing is swiped while it is null). */
    var page: View? = null

    private enum class State { IDLE, DRAGGING, LEAVING, ENTERING, RETURNING }

    private var state = State.IDLE

    private val density = context.resources.displayMetrics.density
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val maxVelocity = configuration.scaledMaximumFlingVelocity.toFloat()
    private val flingVelocity = FLING_VELOCITY_DP * density
    private val flingDistance = FLING_DISTANCE_DP * density
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    // The touch in progress.
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var rejected = false
    private var dragOriginX = 0f
    private var dragOffset = 0f
    private var tracker: VelocityTracker? = null

    /** Each transition gets a number: the end of an animation that was cut short must not start the next step. */
    private var transition = 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(ev)
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                return state == State.DRAGGING || tryStartDrag(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return false
    }

    // When a row takes the touch, the events only get here once a drag has been taken over (the row gets a cancel then, so its
    // pressed state goes away); when nothing below takes it, all of them come here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (page == null) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true // startGesture() has already run in onInterceptTouchEvent
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (state == State.DRAGGING) dragTo(ev.x) else tryStartDrag(ev)
            }
            MotionEvent.ACTION_UP -> {
                track(ev)
                if (state == State.DRAGGING) release(cancelled = false)
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (state == State.DRAGGING) release(cancelled = true)
                endGesture()
            }
            // A second finger: the position would jump to the other one, so the drag ends here.
            MotionEvent.ACTION_POINTER_UP -> if (state == State.DRAGGING) {
                release(cancelled = false)
                rejected = true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        // Leaving the screen: nothing is left to finish, the page just must not stay half way.
        transition++
        page?.animate()?.cancel()
        resetPage()
        state = State.IDLE
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    // ---- The touch ----

    private fun startGesture(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        downTime = ev.eventTime
        rejected = false
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        track(ev)
        // A new touch while the last swipe is still moving: it is over at once (the new files are in), then this one starts.
        finishTransition()
    }

    private fun endGesture() {
        tracker?.recycle()
        tracker = null
    }

    private fun track(ev: MotionEvent) {
        (tracker ?: VelocityTracker.obtain().also { tracker = it }).addMovement(ev)
    }

    /**
     * Whether the finger has gone far enough, and clearly enough sideways, to start dragging the page. A touch that went
     * further up or down first belongs to the list's scrolling for good.
     */
    private fun tryStartDrag(ev: MotionEvent): Boolean {
        if (rejected || page == null || width == 0 || state != State.IDLE) return false
        val dx = abs(ev.x - downX)
        val dy = abs(ev.y - downY)
        if (dx > touchSlop && dx > dy * DOMINANCE) {
            if (ev.eventTime - downTime > longPressMs) {
                rejected = true // a finger that stayed put this long is a long press, not the start of a swipe
                return false
            }
            state = State.DRAGGING
            dragOriginX = ev.x
            dragOffset = 0f
            page?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (dy > touchSlop) rejected = true
        return false
    }

    // ---- Following the finger ----

    private fun dragTo(x: Float) {
        val view = page ?: return
        dragOffset = x - dragOriginX
        if (host?.hasPage(dragOffset > 0f) == true) {
            view.translationX = dragOffset
            view.alpha = 1f - (1f - DRAG_MIN_ALPHA) * (abs(dragOffset) / width).coerceAtMost(1f)
        } else {
            // Nothing further this way: the page gives only a little, and does not fade.
            view.translationX = dragOffset * EDGE_RESISTANCE
            view.alpha = 1f
        }
    }

    private fun release(cancelled: Boolean) {
        var velocity = 0f
        if (!cancelled) {
            tracker?.let {
                it.computeCurrentVelocity(1000, maxVelocity)
                velocity = it.xVelocity
            }
        }
        val fingerRight = dragOffset > 0f
        val far = abs(dragOffset) > width * COMMIT_FRACTION
        val flicked = abs(velocity) > flingVelocity && velocity * dragOffset > 0f && abs(dragOffset) > flingDistance
        if (!cancelled && (far || flicked) && host?.hasPage(fingerRight) == true) {
            leave(fingerRight)
        } else {
            returnToRest()
        }
    }

    // ---- The animations ----

    /** The page carries on the way the finger moved while it fades out; then the new page comes in. */
    private fun leave(fingerRight: Boolean) {
        val view = page ?: return
        val sign = if (fingerRight) 1f else -1f
        val mine = ++transition
        state = State.LEAVING
        host?.onPageChosen(fingerRight)
        view.animate()
            .translationX(sign * maxOf(abs(view.translationX), width * LEAVE_FRACTION))
            .alpha(0f)
            .setStartDelay(0)
            .setDuration(LEAVE_MS)
            .setInterpolator(LinearInterpolator())
            .withEndAction { if (mine == transition) enter(-sign) }
            .start()
    }

    /** The new page starts [fromSign] to the side (the side the old one did not leave to) and slows down into place. */
    private fun enter(fromSign: Float) {
        val view = page ?: return
        val mine = ++transition
        state = State.ENTERING
        host?.onPageSwap()
        view.translationX = fromSign * width * ENTER_FRACTION
        view.alpha = 0f
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setStartDelay(0)
            .setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction { if (mine == transition) settled() }
            .start()
    }

    private fun returnToRest() {
        val view = page ?: return
        val mine = ++transition
        state = State.RETURNING
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setStartDelay(0)
            .setDuration(RETURN_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { if (mine == transition) settled() }
            .start()
    }

    private fun settled() {
        resetPage()
        state = State.IDLE
    }

    /** Cuts whatever is moving short and leaves the page at rest; a swipe that was chosen still puts its files in. */
    private fun finishTransition() {
        if (state == State.IDLE) return
        val swapPending = state == State.LEAVING
        transition++
        page?.animate()?.cancel()
        resetPage()
        state = State.IDLE
        if (swapPending) host?.onPageSwap()
    }

    private fun resetPage() {
        page?.apply {
            translationX = 0f
            alpha = 1f
            setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private companion object {
        /** Like a ViewPager: the drag must be at least twice as wide as it is tall. */
        const val DOMINANCE = 2f

        /** Let go after dragging this far (of the width), or flick: at least this quick, and at least this far. */
        const val COMMIT_FRACTION = 0.25f
        const val FLING_VELOCITY_DP = 300f
        const val FLING_DISTANCE_DP = 28f

        /** While dragging, the page fades down to this at a full width of travel. */
        const val DRAG_MIN_ALPHA = 0.35f

        /** At either end the page follows the finger only this much. */
        const val EDGE_RESISTANCE = 0.2f

        /** The old page leaves at least this far (of the width) in [LEAVE_MS]; the new one starts [ENTER_FRACTION] away. */
        const val LEAVE_FRACTION = 0.6f
        const val ENTER_FRACTION = 0.3f
        const val LEAVE_MS = 110L
        const val ENTER_MS = 240L
        const val RETURN_MS = 220L
    }
}
