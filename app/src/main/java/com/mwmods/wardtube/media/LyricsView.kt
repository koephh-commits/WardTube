// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mwmods.wardtube.R

/**
 * The words of a song the way the Wardio web player shows them: one soft, dark card at the bottom of the picture, with the
 * line being sung in the accent colour and bold, and the line that follows it right under, plain. When the song moves to the
 * next line, the new one fades in with a small lift.
 *
 * The card has no touch of its own (nothing to scroll, nothing to tap): every touch goes through to the player, so the double
 * tap, the swipe and the tap that shows the controls work over the words as they do anywhere else on the screen.
 *
 * The owner feeds it the song's position ([setPosition]) a few times a second.
 */
class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** True while only the one line the subtitle track says now is shown (the rows of the whole file are not known). */
    var isSingle = false
        private set

    private val card = LinearLayout(context)
    private val currentText = TextView(context)
    private val nextText = TextView(context)

    private var rows: List<LyricLine> = emptyList()

    /** The row the card shows now (-1 before the first one, -2 when nothing has been drawn for this song yet). */
    private var shown = NOTHING

    init {
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setBackgroundResource(R.drawable.bg_lyrics_card)
        card.setPadding(dp(18), dp(14), dp(18), dp(14))

        styleLine(currentText, sizeSp = 20f, color = ContextCompat.getColor(context, R.color.queueAccent), bold = true)
        styleLine(nextText, sizeSp = 16f, color = ContextCompat.getColor(context, R.color.queueTextDim), bold = false)

        card.addView(currentText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val nextParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nextParams.topMargin = dp(8)
        card.addView(nextText, nextParams)

        // The width is set when measuring (the screen's width less the margins, and not wider than a phone's card on a big screen).
        addView(card, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        card.visibility = View.GONE
        nextText.visibility = View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styleLine(view: TextView, sizeSp: Float, color: Int, bold: Boolean) {
        view.gravity = Gravity.CENTER
        view.textAlignment = View.TEXT_ALIGNMENT_CENTER
        view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setLineSpacing(0f, 1.25f)
        view.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        (card.layoutParams as FrameLayout.LayoutParams).width = (width - dp(28)).coerceIn(0, dp(560))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ---- What is shown ----

    /** All the rows of the song's words. */
    fun setLines(newRows: List<LyricLine>) {
        isSingle = false
        rows = newRows
        if (rows.isEmpty()) {
            shown = NOTHING
            card.visibility = View.GONE
            return
        }
        // Until the song's position arrives, the first line is there (as the web player does before the first line is reached).
        draw(-1, animate = false)
        shown = -1
    }

    /** Fallback: only the line the subtitle track says right now. */
    fun setSingleLine(text: String) {
        isSingle = true
        rows = emptyList()
        shown = NOTHING
        if (text.isBlank()) {
            currentText.text = ""
            card.visibility = View.GONE
            return
        }
        val changed = currentText.text.toString() != text || card.visibility != View.VISIBLE
        currentText.text = text
        nextText.visibility = View.GONE
        card.visibility = View.VISIBLE
        if (changed) fadeIn(animateNext = false)
    }

    fun clear() {
        isSingle = false
        rows = emptyList()
        shown = NOTHING
        currentText.text = ""
        nextText.text = ""
        card.visibility = View.GONE
    }

    /** The song was moved (a seek) or came back to the screen: the next position is drawn even if it is the same row. */
    fun resumeFollow() {
        shown = NOTHING
    }

    /** The song is at [positionMs]: the card shows the row being sung and the one after it, when the row changes. */
    fun setPosition(positionMs: Long) {
        if (isSingle || rows.isEmpty()) return
        val index = indexAt(positionMs + LEAD_MS)
        if (index == shown) return
        shown = index
        draw(index, animate = true)
    }

    private fun indexAt(positionMs: Long): Int {
        var low = 0
        var high = rows.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (rows[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Puts row [index] (the first row when it is -1) in the accent line, and the row after it, if it is words, under it. */
    private fun draw(index: Int, animate: Boolean) {
        val at = index.coerceAtLeast(0)
        val current = rows[at]
        val following = if (index < 0) null else rows.getOrNull(at + 1)?.takeIf { !it.isBreak }
        currentText.text = current.text
        nextText.text = following?.text.orEmpty()
        nextText.visibility = if (following == null) View.GONE else View.VISIBLE
        card.visibility = View.VISIBLE
        if (animate) fadeIn(animateNext = following != null)
    }

    /** The new line fades in with a small lift (the web player's own motion); it only moves and fades, so the letters are never stretched. */
    private fun fadeIn(animateNext: Boolean) {
        lift(currentText)
        if (animateNext) lift(nextText)
    }

    private fun lift(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(6).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private companion object {
        const val NOTHING = -2

        /** The line comes this much before a row's time, as music apps do: it reads better than a line that is late. */
        const val LEAD_MS = 150L
    }
}
