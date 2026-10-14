// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import com.mwmods.wardtube.R
import com.mwmods.wardtube.util.UrlUtils
import com.mwmods.wardtube.web.BackgroundWebView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The video player: a WebView of its own, apart from the one that browses YouTube, so a video keeps playing
 * while you look at the home page or your subscriptions (like the YouTube app's mini player).
 *
 * The WebView is created when the first video opens and destroyed when the player is closed (a second page is
 * heavy on small phones). It is in one of three modes:
 *  - [Mode.NONE]: no video.
 *  - [Mode.EXPANDED]: covers the browsing area.
 *  - [Mode.MINI]: a small floating box in the corner showing only the video, with play/pause and close buttons.
 *
 * The mini player can be dragged; on release it slides to the nearer side of the screen. A tap on it brings the
 * video back full screen; a double tap enlarges it to nearly the screen's width (and a second double tap shrinks it
 * back), like the YouTube app's floating player. The dark discs behind its two buttons fade away after a few seconds
 * without a touch (the icons stay) and come back as soon as the box is touched.
 *
 * Dragged past the left or right edge of the screen (by about a third of its width), the box slides out of sight, the
 * video pauses and only a small tab with an arrow stays at the edge. A tap on the tab brings the box back and the
 * video goes on (if it was playing).
 *
 * In the mini player the WebView keeps its full size (so the page does not reflow and the video does not
 * stutter) and is scaled down and shifted inside a small, clipping container so that only the video shows.
 */
class PlayerHost(
    private val area: FrameLayout,
    private val container: FrameLayout,
    private val overlay: View,
    private val playPauseButton: ImageButton,
    private val closeButton: ImageButton,
    private val createWebView: () -> BackgroundWebView,
    private val releaseWebView: (BackgroundWebView) -> Unit,
    private val onModeChanged: (Mode) -> Unit,
) {

    enum class Mode { NONE, EXPANDED, MINI }

    var mode: Mode = Mode.NONE
        private set

    var webView: BackgroundWebView? = null
        private set

    val isActive: Boolean get() = mode != Mode.NONE

    /**
     * True while a Short is on screen: the mini player stays out of its way. The video is not closed, it waits
     * (paused) inside the hidden box and the box comes back when the Short is left. Full screen is not affected.
     */
    var miniHidden: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (mode == Mode.MINI) {
                applyMini()
                if (docked) placeDockTab(horizontal = false)
                if (!value) {
                    showControls()
                    scheduleFade()
                }
            }
        }

    /** The page the player shows (the video being played), or null when there is no player. */
    val currentUrl: String? get() = webView?.url ?: lastUrl

    /** The last page the player is known to show (the video being played). */
    private var lastUrl: String? = null

    /** The video's box inside the player page, in WebView pixels. Null: assume 16:9 at the top of the page. */
    private var miniRect: RectF? = null

    private var shownPlaying: Boolean? = null

    private val remeasure = Runnable { if (mode == Mode.MINI) measureVideoBox() }

    // Where the user dragged the mini player to, as a shift from its default corner, and the drag in progress.
    private var offsetX = 0f
    private var offsetY = 0f
    private var startOffsetX = 0f
    private var startOffsetY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(area.context).scaledTouchSlop

    // Size and margin of the box as last placed, needed to keep a drag inside the screen.
    private var miniBoxWidth = 0
    private var miniBoxHeight = 0
    private var miniMargin = 0

    private val density: Float get() = area.resources.displayMetrics.density

    // Enlarged by a double tap: the box is nearly as wide as the screen. [smallOnRight] remembers which side the
    // normal-size box was on, so it goes back there.
    private var enlarged = false
    private var smallOnRight = true
    private var resizeAnimator: ValueAnimator? = null

    // The dark discs behind the play/pause and close buttons. They fade out after [FADE_DELAY_MS] without a touch;
    // the icons themselves stay.
    private val discs: List<Drawable> = listOfNotNull(playPauseButton.background?.mutate(), closeButton.background?.mutate())
    private var discAlpha = 255
    private var discsShown = true
    private var discAnimator: ValueAnimator? = null
    private val fadeDiscs = Runnable { if (mode == Mode.MINI) setDiscsShown(false) }

    // A single tap (no second tap within the double-tap time) brings the video back; a double tap resizes the box.
    private val gestures = GestureDetector(area.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (mode == Mode.MINI) expand()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleEnlarged()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    // Docked: the box was dragged out past the left or right edge. The video is paused and only a tab with an arrow
    // stays at the edge; a tap on it brings the box back.
    private var docked = false
    private var dockedOnRight = false
    private var dockCentre = 0.5f // the tab's vertical centre, as a share of the area's height
    private var resumeOnUndock = false
    private val tabWidthPx: Int get() = (TAB_WIDTH_DP * density).roundToInt()
    private val tabHeightPx: Int get() = (TAB_HEIGHT_DP * density).roundToInt()
    private val dockArrow = ImageView(area.context).apply {
        setImageResource(R.drawable.ic_chevron_24dp)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val dockTab = FrameLayout(area.context).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR // the arrow's direction depends on the side, so it is never mirrored
        visibility = View.GONE
        isClickable = true
        contentDescription = area.context.getString(R.string.miniPlayerShow)
        elevation = 10f * density
    }

    init {
        // The tab sits above everything else in the area; it is moved with translations, so showing it never lays the
        // area out again.
        dockTab.addView(dockArrow, FrameLayout.LayoutParams((20f * density).roundToInt(), (20f * density).roundToInt(), Gravity.CENTER))
        area.addView(dockTab, FrameLayout.LayoutParams(tabWidthPx, tabHeightPx, Gravity.TOP or Gravity.LEFT))
        dockTab.setOnClickListener { undock() }

        // Kept for accessibility services (TalkBack's double tap activates the box); touches are handled below.
        overlay.setOnClickListener { expand() }
        // A drag moves the box; taps are told apart by [gestures].
        overlay.setOnTouchListener { v, event ->
            val handled = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showControls()
                    stopMotion()
                    offsetX = container.translationX
                    offsetY = container.translationY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startOffsetX = offsetX
                    startOffsetY = offsetY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        stopMotion()
                        startOffsetX = container.translationX - dx
                        startOffsetY = container.translationY - dy
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        offsetX = startOffsetX + dx
                        offsetY = startOffsetY + dy
                        applyOffset()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        if (shouldDock()) dock() else snapToEdge()
                    }
                    scheduleFade()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        snapToEdge() // it may be partly out of the screen: pull it back in
                    }
                    scheduleFade()
                    true
                }
                else -> false
            }
            // After the box's own handling: a double tap starts its animation here, and the "down" of that same
            // touch must not cut it short.
            gestures.onTouchEvent(event)
            handled
        }
        // Touching a button counts as touching the box: the discs stay while it is pressed. The click still goes through.
        val keepControls = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> showControls()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleFade()
            }
            false
        }
        playPauseButton.setOnTouchListener(keepControls)
        closeButton.setOnTouchListener(keepControls)
        closeButton.setOnClickListener { close() }
        playPauseButton.setOnClickListener {
            PlayerController.toggle()
            setPlaying(shownPlaying != true)
        }
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 10f * density)
            }
        }
        // Rotation or a layout change while the mini player shows: place it again for the new size.
        area.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = right - left != oldRight - oldLeft
            val heightChanged = bottom - top != oldBottom - oldTop
            if (docked) area.post { placeDockTab(horizontal = widthChanged) }
            if (mode == Mode.MINI) {
                if (widthChanged) {
                    area.post {
                        if (mode != Mode.MINI) return@post
                        miniRect = null
                        stopMotion()
                        offsetX = 0f
                        offsetY = 0f
                        applyMini()
                        remeasureSoon()
                    }
                } else if (heightChanged) {
                    // Only the height changed (the bottom bar slid away or came back while scrolling): the box
                    // stays where the user left it instead of going back to its corner.
                    keepPositionOnHeightChange(oldBottom - oldTop, bottom - top)
                }
            }
        }
    }

    /** Plays [url] and shows the player full screen. The same video that is already open is just shown again. */
    fun play(url: String) {
        val current = webView
        if (current != null && mode != Mode.NONE) {
            val id = UrlUtils.videoId(url)
            val sameVideo = id != null && id == UrlUtils.videoId(lastUrl) && !hasStartTime(url)
            if (!sameVideo) {
                lastUrl = url
                current.loadUrl(url)
                setPlaying(true)
            }
            expand()
            return
        }
        val view = createWebView()
        webView = view
        container.addView(view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        PlayerController.attach(view)
        lastUrl = url
        view.loadUrl(url)
        setPlaying(true)
        expand()
    }

    /** Measures the video's box again in a moment (the page has just changed or finished loading). */
    fun remeasureSoon() {
        if (mode != Mode.MINI) return
        area.removeCallbacks(remeasure)
        area.postDelayed(remeasure, REMEASURE_DELAY_MS)
    }

    /** Remembers the page the player shows (called when its address changes). */
    fun noteUrl(url: String?) {
        if (url != null) lastUrl = url
    }

    /** Shows the player over the whole browsing area. */
    fun expand() {
        val view = webView ?: return
        leaveDock(resume = true)
        mode = Mode.EXPANDED
        view.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        area.removeCallbacks(fadeDiscs)
        stopMotion()
        container.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        container.translationX = 0f
        container.translationY = 0f
        container.clipToOutline = false
        container.elevation = 0f
        container.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        onModeChanged(mode)
    }

    /** Shrinks the player to the floating box; the video keeps playing. */
    fun minimize() {
        if (webView == null || mode == Mode.MINI) return
        mode = Mode.MINI
        miniRect = null
        applyMini()
        measureVideoBox()
        showControls()
        scheduleFade()
        onModeChanged(mode)
    }

    /** Stops the video and removes the player. */
    fun close() {
        val view = webView ?: return
        PlayerController.detach(view)
        container.removeView(view)
        webView = null
        lastUrl = null
        mode = Mode.NONE
        container.visibility = View.GONE
        overlay.visibility = View.GONE
        shownPlaying = null
        enlarged = false
        leaveDock(resume = false)
        area.removeCallbacks(fadeDiscs)
        stopMotion()
        offsetX = 0f
        offsetY = 0f
        container.translationX = 0f
        container.translationY = 0f
        releaseWebView(view)
        onModeChanged(mode)
    }

    /** Keeps the mini player's play/pause button in step with the video. */
    fun setPlaying(playing: Boolean) {
        if (shownPlaying == playing) return
        shownPlaying = playing
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_white_24dp)
    }

    // ---- Mini player geometry ----

    /** Asks the page where its video is, then places the box again with the real size. */
    private fun measureVideoBox() {
        val view = webView ?: return
        view.evaluateJavascript(MEASURE_SCRIPT) { value ->
            if (mode != Mode.MINI || webView !== view) return@evaluateJavascript
            val parts = value?.trim('"')?.split('|') ?: return@evaluateJavascript
            if (parts.size < 6) return@evaluateJavascript
            val n = FloatArray(parts.size)
            for (i in parts.indices) n[i] = parts[i].toFloatOrNull() ?: return@evaluateJavascript
            val innerWidth = n[4]
            val w = area.width
            val h = area.height
            if (innerWidth <= 0f || w <= 0 || h <= 0) return@evaluateJavascript
            val k = w / innerWidth // CSS pixels to screen pixels
            val rect = RectF(n[0] * k, n[1] * k, (n[0] + n[2]) * k, (n[1] + n[3]) * k)
            val plausible = rect.width() >= w * 0.4f && rect.height() > 0f && rect.top >= -1f && rect.bottom <= h * 1.05f
            miniRect = if (plausible) rect else null
            applyMini()
        }
    }

    private fun applyMini() {
        val view = webView ?: return
        val w = area.width
        val h = area.height
        if (w <= 0 || h <= 0) {
            // Not laid out yet (the activity is still starting): the layout listener places the box later.
            container.visibility = View.INVISIBLE
            return
        }
        val rect = miniRect ?: RectF(0f, 0f, w.toFloat(), w * 9f / 16f)

        val margin = (12f * density).roundToInt()
        // Normal size: half the screen's width (150-240dp). Enlarged: nearly the whole width, like the YouTube app.
        val targetWidth = if (enlarged) (w - 2 * margin).toFloat().coerceAtLeast(1f)
        else (w * 0.5f).coerceIn(150f * density, 240f * density)
        var scale = targetWidth / rect.width()
        val maxHeight = h * (if (enlarged) 0.6f else 0.4f)
        if (rect.height() * scale > maxHeight) scale = maxHeight / rect.height()
        val boxWidth = (rect.width() * scale).roundToInt().coerceAtLeast(1)
        val boxHeight = (rect.height() * scale).roundToInt().coerceAtLeast(1)

        // The WebView keeps its full-page size; only its drawing is scaled and moved so the video fills the box.
        // LEFT, not START: in a right-to-left layout START would put the wider WebView against the box's right edge
        // and the box would show the wrong part of the page (black).
        view.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT)
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = -rect.left * scale
        view.translationY = -rect.top * scale

        container.layoutParams = FrameLayout.LayoutParams(boxWidth, boxHeight, Gravity.BOTTOM or Gravity.END).apply {
            marginStart = margin
            marginEnd = margin
            bottomMargin = margin
        }
        container.clipToOutline = true
        container.elevation = 8f * density
        container.visibility = if (miniHidden || docked) View.GONE else View.VISIBLE
        overlay.visibility = View.VISIBLE

        miniBoxWidth = boxWidth
        miniBoxHeight = boxHeight
        miniMargin = margin
        applyOffset()
    }

    /**
     * Double tap: the box grows to nearly the screen's width, or goes back to its normal size. It stays where it was
     * (a box in the lower half keeps its bottom edge, one in the upper half keeps its top edge) and on the same side.
     */
    private fun toggleEnlarged() {
        if (mode != Mode.MINI || miniBoxWidth <= 0) return
        val w = area.width
        val h = area.height
        if (w <= 0 || h <= 0) return
        val rtl = area.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val oldLeft = (if (rtl) miniMargin else w - miniMargin - miniBoxWidth) + offsetX
        val oldTop = h - miniMargin - miniBoxHeight + offsetY
        val oldBottom = oldTop + miniBoxHeight
        val inLowerHalf = oldTop + miniBoxHeight / 2f >= h / 2f
        // A box nearly as wide as the screen has no side of its own: it keeps the one remembered.
        if (miniBoxWidth < w * 0.8f) smallOnRight = oldLeft + miniBoxWidth / 2f >= w / 2f

        val oldW = miniBoxWidth
        val oldH = miniBoxHeight
        enlarged = !enlarged
        stopMotion()
        applyMini()

        val newDefaultLeft = if (rtl) miniMargin else w - miniMargin - miniBoxWidth
        val newDefaultTop = h - miniMargin - miniBoxHeight
        val newLeft = if (smallOnRight) w - miniMargin - miniBoxWidth else miniMargin
        val newTop = if (inLowerHalf) oldBottom - miniBoxHeight else oldTop
        offsetX = newLeft - newDefaultLeft.toFloat()
        offsetY = newTop - newDefaultTop
        applyOffset()
        animateResize(oldLeft, oldTop, oldW, oldH)
    }

    /**
     * The new size and place are already in effect; the box starts out looking like the old one (same size, same
     * spot) and grows or shrinks into them. It is only a transform on the box, so the video stays sharp throughout.
     */
    private fun animateResize(fromLeft: Float, fromTop: Float, fromW: Int, fromH: Int) {
        if (miniBoxWidth <= 0 || miniBoxHeight <= 0 || fromW <= 0 || fromH <= 0) return
        val sx0 = fromW.toFloat() / miniBoxWidth
        val sy0 = fromH.toFloat() / miniBoxHeight
        // Where the box's top-left corner would be with no translation, in its new size.
        val tx0 = fromLeft - defaultLeft()
        val ty0 = fromTop - (area.height - miniMargin - miniBoxHeight)
        val tx1 = offsetX
        val ty1 = offsetY
        container.pivotX = 0f
        container.pivotY = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RESIZE_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                container.scaleX = sx0 + (1f - sx0) * t
                container.scaleY = sy0 + (1f - sy0) * t
                container.translationX = tx0 + (tx1 - tx0) * t
                container.translationY = ty0 + (ty1 - ty0) * t
            }
            // Runs when it finishes and also when it is cancelled: the box ends up in its final size and place.
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (resizeAnimator === animation) resizeAnimator = null
                    container.scaleX = 1f
                    container.scaleY = 1f
                    applyOffset()
                }
            })
        }
        resizeAnimator = animator
        container.scaleX = sx0
        container.scaleY = sy0
        container.translationX = tx0
        container.translationY = ty0
        animator.start()
    }

    /** Ends the box's running animations: a resize jumps to its final size and place, a slide stays where it is. */
    private fun stopMotion() {
        resizeAnimator?.cancel()
        container.animate().cancel()
    }

    // ---- Button discs ----

    /** The box was touched: the discs behind the buttons come back and stay while the touch lasts. */
    private fun showControls() {
        area.removeCallbacks(fadeDiscs)
        setDiscsShown(true)
    }

    /** The touch ended: the discs fade away after a few seconds without another one. */
    private fun scheduleFade() {
        area.removeCallbacks(fadeDiscs)
        if (mode == Mode.MINI && !docked) area.postDelayed(fadeDiscs, FADE_DELAY_MS)
    }

    private fun setDiscsShown(shown: Boolean) {
        if (discsShown == shown) return
        discsShown = shown
        discAnimator?.cancel()
        discAnimator = ValueAnimator.ofInt(discAlpha, if (shown) 255 else 0).apply {
            duration = if (shown) DISC_SHOW_MS else DISC_HIDE_MS
            addUpdateListener {
                discAlpha = it.animatedValue as Int
                for (d in discs) d.alpha = discAlpha
            }
            start()
        }
    }

    // ---- Docking at the screen's edge ----

    /** Where the box's left edge is when it sits in its default corner (bottom-end), in the area's pixels. */
    private fun defaultLeft(): Int {
        val rtl = area.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (rtl) miniMargin else area.width - miniMargin - miniBoxWidth
    }

    /** True when the box was let go far enough past the left or right edge to be docked. */
    private fun shouldDock(): Boolean {
        val w = area.width
        if (miniBoxWidth <= 0 || w <= 0) return false
        val left = defaultLeft() + offsetX
        val outside = maxOf(-left, left + miniBoxWidth - w, 0f)
        return outside >= miniBoxWidth * DOCK_FRACTION
    }

    /** The box slides out of the screen, the video pauses and a tab with an arrow slides in from the same edge. */
    private fun dock() {
        val w = area.width
        val h = area.height
        if (miniBoxWidth <= 0 || w <= 0 || h <= 0) {
            snapToEdge()
            return
        }
        val dl = defaultLeft()
        val left = dl + offsetX
        val top = h - miniMargin - miniBoxHeight + offsetY
        dockedOnRight = left + miniBoxWidth / 2f >= w / 2f
        dockCentre = ((top + miniBoxHeight / 2f) / h).coerceIn(0f, 1f)
        // A video that was already paused stays paused when the box comes back.
        resumeOnUndock = shownPlaying == true
        docked = true
        area.removeCallbacks(fadeDiscs)
        PlayerController.pause()
        setPlaying(false)

        val outLeft = if (dockedOnRight) w else -miniBoxWidth
        offsetX = (outLeft - dl).toFloat()
        stopMotion()
        container.animate().translationX(offsetX).setDuration(SNAP_MS).withEndAction {
            if (docked) container.visibility = View.GONE
        }.start()

        dockArrow.scaleX = if (dockedOnRight) -1f else 1f
        dockTab.background = dockBackground(dockedOnRight)
        dockTab.animate().cancel()
        placeDockTab(horizontal = false)
        dockTab.translationX = if (dockedOnRight) w.toFloat() else -tabWidthPx.toFloat()
        dockTab.animate().translationX(tabRestX(w)).setDuration(SNAP_MS).start()
    }

    /** The tab was tapped: it slides away, the box comes back from the edge at the same height and the video resumes. */
    private fun undock() {
        if (!docked) return
        val w = area.width
        if (w <= 0) return
        val onRight = dockedOnRight
        docked = false

        dockTab.animate().cancel()
        dockTab.animate().translationX(if (onRight) w.toFloat() else -tabWidthPx.toFloat()).setDuration(SNAP_MS).withEndAction {
            if (!docked) dockTab.visibility = View.GONE
        }.start()

        val dl = defaultLeft()
        val restLeft = if (onRight) w - miniMargin - miniBoxWidth else miniMargin
        val outLeft = if (onRight) w else -miniBoxWidth
        offsetX = (restLeft - dl).toFloat()
        stopMotion()
        container.translationX = (outLeft - dl).toFloat()
        container.visibility = if (miniHidden) View.GONE else View.VISIBLE
        container.animate().translationX(offsetX).setDuration(SNAP_MS).start()

        if (resumeOnUndock) {
            PlayerController.play()
            setPlaying(true)
        }
        showControls()
        scheduleFade()
    }

    /** Drops the docked state at once (the video is shown full screen, or closed). */
    private fun leaveDock(resume: Boolean) {
        if (!docked) return
        docked = false
        dockTab.animate().cancel()
        dockTab.visibility = View.GONE
        if (resume && resumeOnUndock) {
            PlayerController.play()
            setPlaying(true)
        }
    }

    /** Puts the tab at the height the box left from; with [horizontal] also at its edge (after a rotation). */
    private fun placeDockTab(horizontal: Boolean) {
        val w = area.width
        val h = area.height
        if (!docked || w <= 0 || h <= 0) return
        val top = (dockCentre * h - tabHeightPx / 2f).coerceIn(0f, (h - tabHeightPx).coerceAtLeast(0).toFloat())
        dockTab.translationY = top
        if (horizontal) {
            dockTab.animate().cancel()
            dockTab.translationX = tabRestX(w)
        }
        dockTab.visibility = if (miniHidden) View.GONE else View.VISIBLE
    }

    private fun tabRestX(w: Int): Float = if (dockedOnRight) (w - tabWidthPx).toFloat() else 0f

    /** The tab's dark rounded shape: rounded on the side facing the screen, flat against the edge. */
    private fun dockBackground(onRight: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(0xF2212121.toInt())
        setStroke((1f * density).roundToInt().coerceAtLeast(1), 0x66FFFFFF)
        val r = 12f * density
        // top-left, top-right, bottom-right, bottom-left (an x and a y radius each)
        setCornerRadii(if (onRight) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f))
    }

    /** After a drag the box slides to the nearer side of the screen, keeping its height. */
    private fun snapToEdge() {
        val w = area.width
        if (miniBoxWidth <= 0 || w <= 0) return
        val rtl = area.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val defaultLeft = if (rtl) miniMargin else w - miniMargin - miniBoxWidth
        val centre = defaultLeft + offsetX + miniBoxWidth / 2f
        val targetLeft = if (centre < w / 2f) miniMargin else w - miniMargin - miniBoxWidth
        val target = (targetLeft - defaultLeft).toFloat()
        offsetX = target
        container.animate().translationX(target).setDuration(SNAP_MS).start()
    }

    /**
     * The area became taller or shorter from [oldHeight] to [newHeight]. The box is placed from the bottom edge, so
     * its saved shift is adjusted: a box in the upper half keeps its place on screen (it was left near the top and
     * must not slide down), a box in the lower half keeps its distance from the bottom edge.
     */
    private fun keepPositionOnHeightChange(oldHeight: Int, newHeight: Int) {
        if (miniBoxHeight <= 0 || oldHeight <= 0 || newHeight <= 0) return
        val oldTop = oldHeight - miniMargin - miniBoxHeight + offsetY
        val inUpperHalf = oldTop + miniBoxHeight / 2f < oldHeight / 2f
        if (inUpperHalf) offsetY -= (newHeight - oldHeight).toFloat()
        applyOffset()
    }

    /** Moves the box by the dragged distance, keeping all of it on screen (the default corner is bottom-end). */
    private fun applyOffset() {
        val w = area.width
        val h = area.height
        if (miniBoxWidth <= 0 || w <= 0 || h <= 0) return
        val rtl = area.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val defaultLeft = if (rtl) miniMargin else w - miniMargin - miniBoxWidth
        val defaultTop = h - miniMargin - miniBoxHeight
        // While dragging, the box may leave the screen sideways (that is how it is docked); otherwise it stays inside.
        val minX = (if (dragging) -miniBoxWidth - defaultLeft else miniMargin - defaultLeft).toFloat()
        val maxX = (if (dragging) w - defaultLeft else w - miniMargin - miniBoxWidth - defaultLeft).toFloat()
        val minY = (miniMargin - defaultTop).toFloat()
        offsetX = if (minX <= maxX) offsetX.coerceIn(minX, maxX) else 0f
        offsetY = if (minY <= 0f) offsetY.coerceIn(minY, 0f) else 0f
        container.translationX = offsetX
        container.translationY = offsetY
    }

    private fun hasStartTime(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.isHierarchical && uri.getQueryParameter("t") != null
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val REMEASURE_DELAY_MS = 500L
        const val SNAP_MS = 180L
        const val FADE_DELAY_MS = 3000L
        const val RESIZE_MS = 500L
        const val DISC_SHOW_MS = 120L
        const val DOCK_FRACTION = 0.35f // how much of the box must be out of the screen to dock it
        const val TAB_WIDTH_DP = 22f
        const val TAB_HEIGHT_DP = 92f
        const val DISC_HIDE_MS = 350L

        // "<left>|<top>|<width>|<height>|<viewport width>|<viewport height>" of the video's box, in CSS pixels.
        val MEASURE_SCRIPT = """
            (function () {
              window.scrollTo(0, 0);
              var el = document.querySelector('#player-container-id') || document.querySelector('#movie_player') ||
                       document.querySelector('.html5-video-player') || document.querySelector('video');
              if (!el) return '';
              var r = el.getBoundingClientRect();
              return [r.left, r.top, r.width, r.height, window.innerWidth, window.innerHeight].join('|');
            })();
        """.trimIndent()
    }
}
