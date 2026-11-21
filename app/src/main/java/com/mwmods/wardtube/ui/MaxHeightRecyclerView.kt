// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/**
 * A list that never grows taller than [maxHeightPx]: a short list is as tall as its rows, a long one scrolls inside the
 * bottom sheet instead of pushing the sheet over the whole screen (the same idea as [MaxHeightScrollView], for a
 * RecyclerView).
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val mode = View.MeasureSpec.getMode(heightSpec)
        val size = View.MeasureSpec.getSize(heightSpec)
        val capped = if (mode == View.MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
        val spec = View.MeasureSpec.makeMeasureSpec(
            capped,
            if (mode == View.MeasureSpec.EXACTLY) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST,
        )
        super.onMeasure(widthSpec, spec)
    }
}
