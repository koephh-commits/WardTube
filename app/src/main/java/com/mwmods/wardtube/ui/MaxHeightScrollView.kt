// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView
import kotlin.math.min

/**
 * A scroll view that never grows taller than [maxHeightPx]: a short list is as tall as its content, a long one scrolls
 * inside the bottom sheet instead of pushing the sheet over the whole screen.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val capped = if (mode == View.MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
        val spec = View.MeasureSpec.makeMeasureSpec(
            capped,
            if (mode == View.MeasureSpec.EXACTLY) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST,
        )
        super.onMeasure(widthMeasureSpec, spec)
    }
}
