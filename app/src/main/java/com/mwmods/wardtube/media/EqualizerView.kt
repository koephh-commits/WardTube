// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.mwmods.wardtube.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three little bars that rise and fall while something plays (the mark of the item that is playing in the queue sheet) and
 * stand still while it is paused. The animation only runs while [playing] is true and the view is on the screen.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.queueAccent)
    }
    private val rect = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** True while the sound is playing: the bars move. False: they stand at rest. */
    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncAnimation()
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun syncAnimation() {
        if (playing && isAttachedToWindow) startAnimation() else stopAnimation()
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * GAP_FRACTION
        val barWidth = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT
        val radius = barWidth / 2f
        for (i in 0 until BAR_COUNT) {
            // Whole numbers of waves per cycle: the loop has no jump where it starts again.
            val wave = 0.5f + 0.5f * sin(2.0 * PI * (phase * FREQUENCY[i] + OFFSET[i])).toFloat()
            val level = if (playing) MIN_LEVEL + (1f - MIN_LEVEL) * wave else REST[i]
            val barHeight = h * level
            val left = i * (barWidth + gap)
            rect.set(left, h - barHeight, left + barWidth, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private companion object {
        const val BAR_COUNT = 3
        const val GAP_FRACTION = 0.14f
        const val MIN_LEVEL = 0.3f
        const val CYCLE_MS = 1200L
        val FREQUENCY = floatArrayOf(2f, 3f, 2f)
        val OFFSET = floatArrayOf(0f, 0.35f, 0.7f)
        val REST = floatArrayOf(0.5f, 0.9f, 0.65f)
    }
}
