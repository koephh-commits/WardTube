// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The menu of the three dots on a video's card, drawn like the YouTube app's: a rounded card with a row for each action
 * (its picture, then its text), floating beside the dots over a light dim. A tap outside it, or the back button, closes
 * it; a tap on a row closes it and runs the row's action.
 *
 * It only shows the rows it is given: what each row does is decided by [SubsController].
 */
class SubsVideoMenu(private val context: Context, private val palette: SubsPalette) {

    /** A row: its [icon] (a drawable resource, drawn in the text's colour), its [label] and what a tap does. */
    class Item(val icon: Int, val label: String, val action: () -> Unit)

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    /** Opens the menu beside [anchor] (the dots). It goes under the dots, or above them when there is no room below. */
    fun show(anchor: View, items: List<Item>) {
        if (items.isEmpty()) return
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val edge = dp(12)
        val width = minOf(screenWidth - 2 * edge, dp(400))

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(0, dp(8), 0, dp(8))
        val shape = GradientDrawable()
        shape.setColor(palette.menu)
        shape.cornerRadius = dp(16).toFloat()
        card.background = shape
        for (item in items) {
            card.addView(row(item, dialog), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        dialog.setContentView(card, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Where the dots are on the screen: the card's top goes just under them, and is moved up when it would run
        // past the bottom of the screen.
        val where = IntArray(2)
        anchor.getLocationOnScreen(where)
        val cardHeight = dp(16) + items.size * dp(52)
        var top = where[1] + anchor.height
        if (top + cardHeight > screenHeight - edge) top = where[1] - cardHeight
        top = top.coerceIn(edge, maxOf(edge, screenHeight - cardHeight - edge))

        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            window.setDimAmount(0.3f)
            val params = window.attributes
            // LEFT, not START: the position is worked out in screen coordinates, whatever the language's direction.
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.x = (screenWidth - width) / 2
            params.y = top
            params.width = width
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            window.attributes = params
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun row(item: Item, dialog: Dialog): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), 0, dp(20), 0)
        row.isClickable = true
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            row.background = attrs.getDrawable(0)
        } finally {
            attrs.recycle()
        }

        // The picture is the row's first child, so it stands at the start of the row: the right in Arabic, the left otherwise.
        val icon = ImageView(context)
        icon.setImageResource(item.icon)
        icon.imageTintList = ColorStateList.valueOf(palette.text)
        row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))

        val label = TextView(context)
        label.text = item.label
        label.textSize = 16f
        label.maxLines = 2
        label.setTextColor(palette.text)
        label.gravity = Gravity.CENTER_VERTICAL
        val labelParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        labelParams.marginStart = dp(20)
        row.addView(label, labelParams)

        row.setOnClickListener {
            dialog.dismiss()
            item.action()
        }
        return row
    }
}
