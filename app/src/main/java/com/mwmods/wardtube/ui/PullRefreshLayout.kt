// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * The pull-to-refresh layer around the browsing page.
 *
 * [SwipeRefreshLayout] starts a pull as soon as the finger has gone down by the touch slop, whatever it did sideways, so
 * a slightly slanted swipe along YouTube's row of chips (or any row that scrolls sideways) at the top of a page would
 * pull the page down instead. Here a drag that went further sideways than down is left to the page for the rest of the
 * touch, and the pull only starts for a mostly vertical one.
 */
class PullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    /**
     * Called with the place of the finger (in this layout's pixels) when a touch starts, before the layout decides
     * whether a pull may begin: the owner can find out there what is under the finger.
     */
    var onTouchStart: ((x: Float, y: Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var sideways = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                sideways = false
                onTouchStart?.invoke(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(ev.x - downX)
                val dy = kotlin.math.abs(ev.y - downY)
                if (dx > slop && dx > dy) sideways = true
            }
        }
        // The touch's first event (and its end) always reach the parent class, so its own state is reset as usual.
        if (sideways && ev.actionMasked == MotionEvent.ACTION_MOVE) return false
        return super.onInterceptTouchEvent(ev)
    }
}
