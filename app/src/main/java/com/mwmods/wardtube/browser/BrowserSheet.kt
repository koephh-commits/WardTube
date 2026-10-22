// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mwmods.wardtube.R

/** What the browser screen's bottom sheets (search engines, detected videos and files) have in common. */
internal object BrowserSheet {

    /** How much of the screen's height the list of a sheet may take before it scrolls. */
    private const val LIST_HEIGHT_FRACTION = 0.6f

    /** A dark-green sheet (like the browser screen) that opens fully, with no half-open step. */
    fun create(context: Context, content: View): BottomSheetDialog =
        BottomSheetDialog(context, R.style.BrowserSheetDialog).apply {
            setContentView(content)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

    fun listMaxHeight(context: Context): Int =
        (context.resources.displayMetrics.heightPixels * LIST_HEIGHT_FRACTION).toInt()

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp)
            setColor(color)
        }
}

/** Draws the engine's colored round badge (its letters on its brand color) in [view]. */
internal fun SearchEngine.paintBadge(view: TextView) {
    view.text = badge
    view.background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
