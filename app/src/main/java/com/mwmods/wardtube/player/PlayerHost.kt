// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
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
 * video goes on (if it was playing). If the video is started from outside the app while the box is tucked away (the
 * notification's Play button, a headset or Bluetooth key, Next / Previous), the box comes back too, so the video never
 * plays out of sight (see [onPlaybackState]).
 *
 * Full screen, a swipe down that starts on the video follows the finger (the whole player slides down over the
 * browsing page) and, let go far enough or flicked, shrinks into the mini player's corner; let go early, it slides
 * back. See [onPlayerTouch]. The same as the minimize arrow, like the YouTube app's swipe down.
 *
 * In the mini player the WebView keeps its full size (so the page does not reflow and the video does not
 * stutter) and is scaled down and shifted inside a small, clipping container so that only the video shows. The
 * video's box is measured again every few seconds, because YouTube moves things around when it opens a new video, and
 * the page script keeps the page scrolled to the top meanwhile (see [MEASURE_SCRIPT]).
 *
 * Picture in picture ([enterPip]): when the whole app window becomes the system's small floating window, the player
 * is shown the same way as the mini player (the page hides everything but the video, the WebView keeps a screen-sized
 * layout and is scaled down), only the "box" is the whole window and has no buttons of its own (the system draws
 * its own). [mode] stays what it was (full screen or mini) so [exitPip] can put it back. Everything that places the
 * WebView there uses LEFT / top-left, never START, and the container is forced left-to-right: in a right-to-left
 * layout START puts the wider WebView against the window's right edge and the window would show the wrong part
 * of the page (black).
 * After full screen the video may be left shorter than the player's box: the picture's own box is fitted then and the page is
 * nudged to size the video again (see [nudgePage]).
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
     * True from the moment the player becomes the small box until it is shown full screen or closed. The player page
     * asks for it (see NativeBridge.isMini) on a WebView thread, hence @Volatile, and it is set BEFORE the page is
     * told, so the answer is never behind the message.
     */
    @Volatile
    var miniWanted: Boolean = false
        private set

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

    /**
     * True while the app window is the system's picture-in-picture window and this player fills it (see [enterPip]).
     * [mode] is still the one from before (full screen or mini).
     */
    var pip: Boolean = false
        private set

    /** The size the WebView is laid out at while in picture in picture (the screen's), in pixels. */
    private var pipPageWidth = 0
    private var pipPageHeight = 0

    /**
     * Right after the window came from the system's full screen (see [enterPip]) and until this uptime, a measure of the
     * video's box is only believed when the box is where the page script pins it (the top-left corner, as wide as the
     * page): before that the page is still leaving full screen and its player is in some in-between shape.
     */
    private var pipStrictUntil = 0L

    // After full screen the page can be left with its video sized for an in-between moment (a band of black under the
    // picture, the picture not filling the player's box). Synthetic resize events do not always make YouTube size it
    // again, a real change of the page's width does: for a split second the WebView is laid out a few pixels narrower and
    // then given its size back (see [nudgePage]). Nothing is measured while that lasts.
    private var pipNudging = false
    private var pipNudgesLeft = 0
    private var pipNudgedAt = 0L

    // The first seconds of the window: the page is told it is the box, laid out again (after full screen) and the video's
    // box is measured and fitted again several times, so the picture does not stay in the shape of the in-between moment.
    private var pipSettleStep = 0
    private var pipSettleRelayout = false
    private val pipSettle = object : Runnable {
        override fun run() {
            if (!pip) return
            tellPageMini()
            if (pipSettleRelayout) relayoutPage()
            applyPip() // the window's size may have changed since the last fit
            if (pipSettleRelayout && pipSettleStep == PIP_NUDGE_STEP) nudgePage()
            measureVideoBox()
            pipSettleStep++
            if (pipSettleStep < PIP_SETTLE_MS.size) area.postDelayed(this, PIP_SETTLE_MS[pipSettleStep])
        }
    }

    /** True when there is a video to show in a picture-in-picture window: not closed, not hidden for a Short, not tucked away. */
    val canPip: Boolean get() = webView != null && mode != Mode.NONE && !miniHidden && !docked

    /** The page the player shows (the video being played), or null when there is no player. */
    val currentUrl: String? get() = webView?.url ?: lastUrl

    /** The last page the player is known to show (the video being played). */
    private var lastUrl: String? = null

    /** The video's box inside the player page, in WebView pixels. Null: assume 16:9 at the top of the page. */
    private var miniRect: RectF? = null

    private var shownPlaying: Boolean? = null

    // While the mini player shows, the video's box is measured again every few seconds. YouTube moves things around
    // after a new video opens (the next video of a list, a page that finished loading late), and a box measured only
    // once would then show the wrong part of the page (the suggestions below the video, for example).
    private val remeasure = object : Runnable {
        override fun run() {
            if (mode != Mode.MINI && !pip) return
            if (!docked && !miniHidden) measureVideoBox()
            area.postDelayed(this, REMEASURE_PERIOD_MS)
        }
    }

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

    // Swipe down on the full-screen video to minimize it (see [onPlayerTouch]).
    // WATCHING: the finger is down and the page still has the touch (it may be a tap or a scroll). DRAGGING: the player
    // follows the finger. SWALLOW: the rest of a touch that was cut short is kept from the page.
    private enum class Swipe { IDLE, WATCHING, DRAGGING, SWALLOW }

    private var swipe = Swipe.IDLE
    private var swipeId = 0 // counts touches, so a late answer about the video's box is never used for the next touch
    private var swipeDownRawX = 0f
    private var swipeDownRawY = 0f
    private var swipeDownY = 0f // in the player's own pixels, to compare with the video's box
    private var swipeAnchorRawY = 0f // where the finger was when the drag took over
    private var swipeBase = 0f // how far down the player already was then (it may still have been sliding back)
    private var swipeBox: RectF? = null // the video's box in the page, in the player's pixels; null: none found
    private var swipeBoxRead = false // the page has answered (swipeBox is null if it found no video)
    private var swipeTracker: VelocityTracker? = null

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
    private var dockedAt = 0L // SystemClock.uptimeMillis() of the last [dock]
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
            if (pip) {
                // The little window came out another size (the system lets the person make it bigger or smaller): the
                // video's box is fitted into it again.
                if (widthChanged || heightChanged) area.post { if (pip) applyPip() }
                return@addOnLayoutChangeListener
            }
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
    @SuppressLint("ClickableViewAccessibility")
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
        view.setOnTouchListener { v, event -> onPlayerTouch(v, event) }
        container.addView(view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        PlayerController.attach(view)
        lastUrl = url
        view.loadUrl(url)
        setPlaying(true)
        expand()
    }

    /** Measures the video's box again in a moment (the page has just changed or finished loading). */
    fun remeasureSoon() {
        if (mode != Mode.MINI && !pip) return
        tellPageMini() // a page that has just (re)loaded starts out knowing nothing about the box
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
        endPip()
        leaveDock(resume = true)
        miniWanted = false // before the page is told (see below)
        mode = Mode.EXPANDED
        view.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        area.removeCallbacks(fadeDiscs)
        area.removeCallbacks(remeasure)
        view.evaluateJavascript("window.__wardtube&&window.__wardtube.setMini(false)", null)
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
    fun minimize() = enterMini(null)

    /** [box]: where the video will be in the box (known from the swipe), or null to assume 16:9 until it is measured. */
    private fun enterMini(box: RectF?) {
        if (webView == null || mode == Mode.MINI) return
        miniWanted = true // before the page is told: the page also asks for it by itself (NativeBridge.isMini)
        mode = Mode.MINI
        miniRect = box
        applyMini()
        tellPageMini() // at once: with the page still loading, the first measure below may find nothing to talk to
        measureVideoBox()
        remeasureSoon() // and again every few seconds from then on
        showControls()
        scheduleFade()
        onModeChanged(mode)
    }

    /** Stops the video and removes the player. */
    fun close() {
        val view = webView ?: return
        endPip()
        PlayerController.detach(view)
        container.removeView(view)
        webView = null
        lastUrl = null
        miniWanted = false
        mode = Mode.NONE
        container.visibility = View.GONE
        overlay.visibility = View.GONE
        shownPlaying = null
        enlarged = false
        leaveDock(resume = false)
        area.removeCallbacks(fadeDiscs)
        area.removeCallbacks(remeasure)
        stopMotion()
        endSwipe()
        offsetX = 0f
        offsetY = 0f
        container.translationX = 0f
        container.translationY = 0f
        releaseWebView(view)
        onModeChanged(mode)
    }

    /**
     * What the page's video is doing right now, as read by the state poll. Keeps the play/pause button in step with it.
     *
     * A video that plays while the box is docked was started from outside the app (the notification, a headset or
     * Bluetooth key): the box would stay hidden while the video plays, so it comes back. The first moments after docking
     * are skipped, because a state read that began before the video was paused can still arrive and say "playing".
     */
    fun onPlaybackState(playing: Boolean) {
        if (playing && docked && SystemClock.uptimeMillis() - dockedAt > DOCK_SETTLE_MS) {
            val resume = resumeOnUndock
            resumeOnUndock = false // it plays already: undock() must not start it a second time
            undock()
            if (docked) resumeOnUndock = resume // the box could not come back yet (not laid out): leave the tab as it was
        }
        setPlaying(playing)
    }

    /** Keeps the mini player's play/pause button in step with the video. */
    fun setPlaying(playing: Boolean) {
        if (shownPlaying == playing) return
        shownPlaying = playing
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_white_24dp)
    }

    // ---- Mini player geometry ----

    /** Tells the player page that it is shown as the small box (it hides everything but the video and stays at the top). */
    private fun tellPageMini() {
        webView?.evaluateJavascript("window.__wardtube&&window.__wardtube.setMini(true)", null)
    }

    /** Asks the page where its video is, then places the box again with the real size. */
    private fun measureVideoBox() {
        val view = webView ?: return
        if (pipNudging) return
        view.evaluateJavascript(MEASURE_SCRIPT) { value ->
            if ((mode != Mode.MINI && !pip) || webView !== view || pipNudging) return@evaluateJavascript
            val parts = value?.trim('"')?.split('|') ?: return@evaluateJavascript
            if (parts.size < 6) return@evaluateJavascript
            val n = FloatArray(parts.size)
            for (i in parts.indices) n[i] = parts[i].toFloatOrNull() ?: return@evaluateJavascript
            val innerWidth = n[4]
            // The size the WebView itself is laid out at: the area's, or in picture in picture the screen's (the area is
            // then the little window, and the WebView is bigger than it and scaled down).
            val w = if (pip) pipPageWidth else area.width
            val h = if (pip) pipPageHeight else area.height
            if (innerWidth <= 0f || w <= 0 || h <= 0) return@evaluateJavascript
            val k = w / innerWidth // CSS pixels to screen pixels
            val rect = RectF(n[0] * k, n[1] * k, (n[0] + n[2]) * k, (n[1] + n[3]) * k)
            // The video sits at the top of the page: a box far down is something else (a suggestion's picture).
            val plausible = rect.width() >= w * 0.4f && rect.height() > 0f && rect.top >= -1f &&
                rect.top <= h * 0.35f && rect.bottom <= h * 1.05f
            val next = if (plausible) rect else null
            // The window has just come from the system's full screen: the page is still leaving it, and a box that is not
            // pinned at the top-left corner at the page's width, or that is taller than wide, is the page between two
            // layouts. It is not used (the last box, or 16:9 at the top, stays) and the next look decides.
            if (pip && next != null && SystemClock.uptimeMillis() < pipStrictUntil) {
                val pinned = abs(n[0]) <= 2f && abs(n[1]) <= 2f && abs(n[2] - innerWidth) <= maxOf(2f, innerWidth * 0.02f)
                if (!pinned || next.height() > next.width()) return@evaluateJavascript
            }
            // Picture in picture only: the player's box can be taller than the picture it holds (YouTube sized the video for an
            // in-between moment of the page when the player left full screen: black under the picture, which then sits at the
            // top of the window instead of filling it). The picture's own box is shown then, and the page is nudged to size
            // the video again. A picture much smaller than its box (a wide film's bars, a 4:3 video's sides) is left alone.
            var box = next
            if (pip && next != null && parts.size >= 10) {
                val v = RectF(n[6] * k, n[7] * k, (n[6] + n[8]) * k, (n[7] + n[9]) * k)
                val insideBox = v.left >= next.left - 2f && v.top >= next.top - 2f &&
                    v.right <= next.right + 2f && v.bottom <= next.bottom + 2f
                val almostFillsBox = v.width() >= next.width() * 0.9f && v.height() >= next.height() * 0.85f
                val shorterThanBox = v.height() < next.height() * 0.98f
                if (insideBox && almostFillsBox && shorterThanBox) {
                    box = v
                    if (pipNudgesLeft > 0 && SystemClock.uptimeMillis() - pipNudgedAt > PIP_NUDGE_GAP_MS) {
                        pipNudgesLeft--
                        nudgePage()
                    }
                }
            }
            // Nothing moved (the usual case on the repeated look), or the box is in the middle of its resize animation.
            if (sameBox(miniRect, box) || resizeAnimator != null) return@evaluateJavascript
            miniRect = box
            applyMini()
        }
    }

    /**
     * Lays the page out a few pixels narrower for a split second and then gives it its size back, so that YouTube gets a
     * real change of width (its resize handlers run, the player sizes the video to its box again) and not only the events
     * [relayoutPage] sends. The window is not affected: the WebView is only a few pixels narrower and scaled the same.
     */
    private fun nudgePage() {
        val view = webView ?: return
        if (!pip || pipNudging || pipPageWidth <= PIP_NUDGE_PX * 4) return
        pipNudging = true
        pipNudgedAt = SystemClock.uptimeMillis()
        view.layoutParams = FrameLayout.LayoutParams(pipPageWidth - PIP_NUDGE_PX, pipPageHeight, Gravity.TOP or Gravity.LEFT)
        area.postDelayed({
            pipNudging = false
            if (!pip) return@postDelayed
            applyPip() // the size is not the page's own any more: it is set again here
            measureVideoBox()
        }, PIP_NUDGE_MS)
    }

    private fun sameBox(a: RectF?, b: RectF?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return abs(a.left - b.left) < 1.5f && abs(a.top - b.top) < 1.5f &&
            abs(a.width() - b.width()) < 1.5f && abs(a.height() - b.height()) < 1.5f
    }

    private fun applyMini() {
        if (pip) {
            applyPip()
            return
        }
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

    // ---- Picture in picture ----

    /**
     * The app window has become the system's picture-in-picture window (the activity has hidden its bars, so the area is
     * the whole window): the video fills it. [pageWidth] x [pageHeight] is the screen's size. The WebView is laid out at
     * that size, as in the mini player, and only its drawing is scaled down, so the page does not reflow when the window
     * shrinks and the video does not stutter. The page itself hides everything but the player (see [miniWanted]).
     *
     * [afterFullscreen]: the window came from the system's full screen and the activity has just taken the video out of it.
     * The page is then in the middle of leaving full screen (its viewport changed from the sideways full screen to the
     * window's size and on to the upright layout, and YouTube's player sizes itself at each step), so what it reports at
     * this moment is not trusted (see [pipStrictUntil]), the box measured before is dropped, and the page is told to lay
     * itself out again while the window settles ([pipSettle]).
     */
    fun enterPip(pageWidth: Int, pageHeight: Int, afterFullscreen: Boolean = false) {
        val view = webView ?: return
        if (mode == Mode.NONE || pip) return
        pip = true
        pipNudging = false
        pipNudgesLeft = PIP_NUDGE_MAX
        pipNudgedAt = 0L
        if (afterFullscreen) {
            miniRect = null // measured before, in another layout (or for another video)
            pipStrictUntil = SystemClock.uptimeMillis() + PIP_STRICT_MS
        }
        // From the mini player the WebView already has a full-size layout fixed in pixels, which the window shrinking
        // does not touch: it is kept, so the page does not reflow at all. Full screen, the WebView follows the window
        // (it is already shrinking), so the screen's size is used.
        val keepSize = mode == Mode.MINI && view.width > 0 && view.height > 0
        pipPageWidth = if (keepSize) view.width else pageWidth.coerceAtLeast(1)
        pipPageHeight = if (keepSize) view.height else pageHeight.coerceAtLeast(1)
        miniWanted = true // before the page is told: the page also asks for it by itself (NativeBridge.isMini)
        stopMotion()
        area.removeCallbacks(fadeDiscs)
        overlay.animate().cancel()
        overlay.visibility = View.GONE // the system draws the window's own buttons
        dockTab.visibility = View.GONE
        // Never mirrored: whatever the phone's language is, the window is laid out from its physical left.
        container.layoutDirection = View.LAYOUT_DIRECTION_LTR
        container.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        container.scaleX = 1f
        container.scaleY = 1f
        container.translationX = 0f
        container.translationY = 0f
        container.clipToOutline = false
        container.elevation = 0f
        container.visibility = View.VISIBLE
        applyPip()
        tellPageMini() // at once, so the page does not show its other parts while the window is shrinking
        measureVideoBox()
        area.removeCallbacks(remeasure)
        area.postDelayed(remeasure, REMEASURE_DELAY_MS)
        // The first seconds: tell, fit and measure again several times (after full screen also lay the page out again).
        area.removeCallbacks(pipSettle)
        pipSettleStep = 0
        pipSettleRelayout = afterFullscreen
        area.postDelayed(pipSettle, PIP_SETTLE_MS[0])
    }

    /**
     * Asks the page to lay itself out again: YouTube sizes its player (and the picture inside it) when the page's size or
     * full-screen state changes, and after the window took the player out of full screen it can be left sized for one of
     * the in-between moments (a part of the video cut off, black under it).
     */
    private fun relayoutPage() {
        webView?.evaluateJavascript("window.__wardtube&&window.__wardtube.relayout&&window.__wardtube.relayout()", null)
    }

    /** The window is the app again: the player goes back to what it was before, full screen or the floating box. */
    fun exitPip() {
        if (!pip) return
        endPip()
        when (mode) {
            Mode.EXPANDED -> expand()
            Mode.MINI -> {
                overlay.alpha = 1f
                applyMini()
                tellPageMini()
                measureVideoBox()
                remeasureSoon()
                showControls()
                scheduleFade()
            }
            Mode.NONE -> Unit
        }
    }

    /** Drops the picture-in-picture state; the caller lays the player out again for the mode it is in. */
    private fun endPip() {
        if (!pip) return
        pip = false
        pipStrictUntil = 0L
        pipNudging = false
        area.removeCallbacks(pipSettle)
        container.layoutDirection = View.LAYOUT_DIRECTION_INHERIT
    }

    /** Fits the video's box into the window: as large as it goes, centred, with black around it if the shapes differ. */
    private fun applyPip() {
        val view = webView ?: return
        val w = area.width
        val h = area.height
        if (w <= 0 || h <= 0) return // not laid out yet: the layout listener comes back when it is
        val pageW = pipPageWidth
        val pageH = pipPageHeight
        val rect = miniRect ?: RectF(0f, 0f, pageW.toFloat(), pageW * 9f / 16f)
        val scale = minOf(w / rect.width(), h / rect.height())
        val shownW = rect.width() * scale
        val shownH = rect.height() * scale
        // LEFT, not START: see the class comment.
        val gravity = Gravity.TOP or Gravity.LEFT
        val params = view.layoutParams as? FrameLayout.LayoutParams
        if (params == null || params.width != pageW || params.height != pageH || params.gravity != gravity) {
            view.layoutParams = FrameLayout.LayoutParams(pageW, pageH, gravity)
        }
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = (w - shownW) / 2f - rect.left * scale
        view.translationY = (h - shownH) / 2f - rect.top * scale
    }

    /**
     * Where the video is in the window right now, for the system's shrinking animation; null when that cannot be said.
     * [aspect] is the picture's width over its height.
     */
    fun pipSourceRect(aspect: Float): Rect? {
        if (mode == Mode.NONE || pip || container.visibility != View.VISIBLE) return null
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0 || aspect <= 0f) return null
        val where = IntArray(2)
        container.getLocationInWindow(where)
        // Full screen the video sits at the top of the player and is as wide as it; in the floating box it is the box.
        val boxHeight = if (mode == Mode.EXPANDED) minOf(h, (w / aspect).roundToInt()) else h
        return Rect(where[0], where[1], where[0] + w, where[1] + boxHeight)
    }

    // ---- Swipe down to minimize ----

    /**
     * Sees every touch of the full-screen player before its page does, to turn a swipe down that starts on the video into
     * "minimize" (like the YouTube app). Everything else, taps, scrolls, the seek bar, goes to the page untouched.
     *
     * A touch is only followed when the phone is upright (held sideways the page is mostly the video and scrolls) and it
     * starts in the upper part of the screen, where the video is. The page is asked where its video is (a touch that
     * starts on the comments or the description is a scroll, not a swipe), and when the finger has gone down more than
     * the touch slop, mostly straight down, from a spot on the video (not on its seek bar), the player takes over: the
     * page is sent a cancel, so it does not scroll or take the end for a tap, and the whole player slides down with the
     * finger over the browsing page. Let go past [SWIPE_MINIMIZE_DP], or flicked down, it shrinks into the mini player
     * from where it is; otherwise it slides back.
     */
    private fun onPlayerTouch(view: View, event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (swipe == Swipe.DRAGGING) settleBack() // the last touch never ended properly
            endSwipe()
            if (canWatchSwipe(view, event)) startWatching(event)
            return false // the page always gets the start of a touch
        }
        if (mode != Mode.EXPANDED) {
            // The player became the box (the back key, a link) in the middle of a touch. A drag under way still owns the
            // rest of it: the page was told its touch was over, so it must not see the end of it.
            val swallow = swipe == Swipe.DRAGGING || swipe == Swipe.SWALLOW
            if (swallow && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) swipe = Swipe.SWALLOW
            else endSwipe()
            return swallow
        }
        return when (swipe) {
            Swipe.WATCHING -> watchSwipe(view, event)
            Swipe.DRAGGING -> dragSwipe(event)
            Swipe.SWALLOW -> {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endSwipe()
                true
            }
            Swipe.IDLE -> false
        }
    }

    /** Only an upright phone, one finger, and a start in the upper part of the screen (where the video is) can be a swipe. */
    private fun canWatchSwipe(view: View, event: MotionEvent): Boolean =
        mode == Mode.EXPANDED && webView === view && event.pointerCount == 1 &&
            view.width > 0 && view.width < view.height && event.y < view.height * SWIPE_ZONE_SHARE

    private fun startWatching(event: MotionEvent) {
        val web = webView ?: return
        swipe = Swipe.WATCHING
        val id = ++swipeId
        swipeDownRawX = event.rawX
        swipeDownRawY = event.rawY
        swipeDownY = event.y
        swipeBox = null
        swipeBoxRead = false
        swipeTracker?.recycle()
        swipeTracker = VelocityTracker.obtain().also { addToTracker(it, event) }
        // Where is the video? The answer comes back within a few milliseconds, while the finger is still going.
        web.evaluateJavascript(VIDEO_BOX_SCRIPT) { value ->
            if (id != swipeId || swipe == Swipe.IDLE) return@evaluateJavascript
            swipeBox = parseBox(value)
            swipeBoxRead = true
        }
    }

    /** The box a [VIDEO_BOX_SCRIPT] answer describes, in the player's pixels, or null when the page has no video to show. */
    private fun parseBox(value: String?): RectF? {
        val parts = value?.trim('"')?.split('|') ?: return null
        if (parts.size < 6) return null
        val n = FloatArray(parts.size)
        for (i in parts.indices) n[i] = parts[i].toFloatOrNull() ?: return null
        val w = area.width
        if (n[4] <= 0f || w <= 0) return null
        val k = w / n[4] // CSS pixels to screen pixels
        return RectF(n[0] * k, n[1] * k, (n[0] + n[2]) * k, (n[1] + n[3]) * k)
    }

    /** The finger is down and the page has the touch: decide whether it is going to be a swipe down on the video. */
    private fun watchSwipe(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) { // lifted, cancelled, or a second finger came down
            endSwipe()
            return false
        }
        swipeTracker?.let { addToTracker(it, event) }
        val dx = event.rawX - swipeDownRawX
        val dy = event.rawY - swipeDownRawY
        if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) return false // still a tap
        // Up, sideways or slanted: the page's own scroll (or a seek), not ours.
        if (dy <= touchSlop || dy < abs(dx) * SWIPE_VERTICAL_RATIO) {
            endSwipe()
            return false
        }
        if (!swipeBoxRead) return false // no answer about the video yet: wait, the finger is still going
        val box = swipeBox
        // Not on the video, or on its seek bar along the bottom edge: the page's.
        if (box == null || swipeDownY < box.top || swipeDownY > box.bottom - SWIPE_BOTTOM_DP * density) {
            endSwipe()
            return false
        }
        beginDrag(view, event)
        return true
    }

    private fun beginDrag(view: View, event: MotionEvent) {
        swipe = Swipe.DRAGGING
        container.animate().cancel() // a slide back that is still running: the finger takes the player from where it is
        swipeBase = container.translationY
        swipeAnchorRawY = event.rawY
        // The page is told its touch is over, so it neither scrolls nor takes the end of this one for a tap.
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        view.onTouchEvent(cancel)
        cancel.recycle()
        view.parent?.requestDisallowInterceptTouchEvent(true)
    }

    /** The player follows the finger down (never up past its place); lifting it decides between minimizing and sliding back. */
    private fun dragSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                swipeTracker?.let { addToTracker(it, event) }
                container.translationY = (swipeBase + event.rawY - swipeAnchorRawY).coerceIn(0f, area.height.toFloat())
            }
            MotionEvent.ACTION_UP -> {
                val tracker = swipeTracker
                var velocity = 0f
                if (tracker != null) {
                    addToTracker(tracker, event)
                    tracker.computeCurrentVelocity(1000)
                    velocity = tracker.yVelocity
                }
                val box = swipeBox
                endSwipe()
                finishDrag(velocity, box)
            }
            MotionEvent.ACTION_CANCEL -> {
                endSwipe()
                settleBack()
            }
            else -> { // a second finger: let go of the drag, and keep the rest of the touch from the page
                endSwipe()
                swipe = Swipe.SWALLOW
                settleBack()
            }
        }
        return true
    }

    /** The finger was lifted at [velocity] px/s (down is positive). [box] is the video's box in the page. */
    private fun finishDrag(velocity: Float, box: RectF?) {
        val dy = container.translationY
        val enough = dy >= SWIPE_MINIMIZE_DP * density
        val flungDown = velocity >= SWIPE_FLING_DP_S * density
        val flungUp = velocity <= -SWIPE_FLING_DP_S * density
        if ((enough && !flungUp) || (flungDown && dy > touchSlop)) minimizeFromSwipe(dy, box) else settleBack()
    }

    /**
     * The player becomes the mini player and the video shrinks into its corner from where the finger left it: the box
     * starts out the size and place of the video on screen ([dy] below where it sat in the page) and grows smaller into
     * the corner, the same animation as the double tap's resize.
     */
    private fun minimizeFromSwipe(dy: Float, box: RectF?) {
        val w = area.width.toFloat()
        val h = area.height.toFloat()
        val fromLeft = box?.left ?: 0f
        val fromTop = (box?.top ?: 0f) + dy
        val fromW = box?.width() ?: w
        val fromH = box?.height() ?: (w * 9f / 16f)
        // In the mini player the page pins the video to the top-left corner at full width, so that is the box to start
        // from (the first measure corrects it if the page disagrees). Without it a video that is not 16:9 would first be
        // shrunk as if it were, and only jump to its real shape when the animation is over.
        val seed = box?.takeIf { it.width() > 0f && it.height() > 0f }
            ?.let { RectF(0f, 0f, w, w * it.height() / it.width()) }
            ?.takeIf { it.height() <= h * 1.05f }
        container.animate().cancel()
        enterMini(seed)
        animateResize(fromLeft, fromTop, fromW.roundToInt(), fromH.roundToInt())
        // The play/pause and close buttons would be seen shrinking along with the box: they appear once it has landed.
        overlay.animate().cancel()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setStartDelay(RESIZE_MS * 3 / 5).setDuration(DISC_SHOW_MS).start()
    }

    /** Let go too early: the player slides back up to cover the page again. */
    private fun settleBack() {
        container.animate().cancel()
        container.animate().translationY(0f).setDuration(SNAP_MS).start()
    }

    private fun endSwipe() {
        swipe = Swipe.IDLE
        swipeTracker?.recycle()
        swipeTracker = null
    }

    /**
     * Gives the touch to the velocity tracker in screen coordinates: the player moves with the finger, so its own
     * coordinates would hardly change and the speed would read as zero.
     */
    private fun addToTracker(tracker: VelocityTracker, event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
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
        dockedAt = SystemClock.uptimeMillis()
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
        const val REMEASURE_PERIOD_MS = 2000L // how often the mini player looks at the video's box again

        // Picture in picture: how soon after the window appears the page is told, fitted and measured again (one entry per
        // look, each the wait after the one before), and how long, after full screen, a box that is not pinned is distrusted.
        val PIP_SETTLE_MS = longArrayOf(150L, 250L, 400L, 600L, 900L, 1300L)
        const val PIP_STRICT_MS = 6000L

        // The page nudge (see nudgePage): after full screen, at this look of the settle sequence (about 1.4 s in) the page is
        // laid out PIP_NUDGE_PX narrower for PIP_NUDGE_MS; and, up to PIP_NUDGE_MAX times, PIP_NUDGE_GAP_MS apart at least,
        // again whenever the picture is found shorter than the player's box.
        const val PIP_NUDGE_STEP = 3
        const val PIP_NUDGE_PX = 6
        const val PIP_NUDGE_MS = 140L
        const val PIP_NUDGE_MAX = 2
        const val PIP_NUDGE_GAP_MS = 900L
        const val SNAP_MS = 180L
        const val FADE_DELAY_MS = 3000L
        const val RESIZE_MS = 500L
        const val DISC_SHOW_MS = 120L
        const val DOCK_FRACTION = 0.35f // how much of the box must be out of the screen to dock it
        const val DOCK_SETTLE_MS = 1500L // after docking, "playing" reports are ignored for this long (see onPlaybackState)
        const val TAB_WIDTH_DP = 22f
        const val TAB_HEIGHT_DP = 92f
        const val DISC_HIDE_MS = 350L

        // Swipe down to minimize (see onPlayerTouch).
        const val SWIPE_ZONE_SHARE = 0.6f // a touch can only start in the upper 60% of the screen
        const val SWIPE_VERTICAL_RATIO = 1.2f // the finger must go down this much more than sideways
        const val SWIPE_BOTTOM_DP = 36f // the video's bottom edge (its seek bar) is left to the page
        const val SWIPE_MINIMIZE_DP = 72f // let go after sliding down this far: minimize
        const val SWIPE_FLING_DP_S = 600f // or flick down faster than this

        // The same answer as MEASURE_SCRIPT, but it changes nothing in the page (the player is full screen, the page
        // must stay where the user left it): where the video's box is right now, or '' when no video is in view.
        val VIDEO_BOX_SCRIPT = """
            (function () {
              var vw = window.innerWidth, vh = window.innerHeight;
              var picks = ['#player-container-id', '#movie_player', '.html5-video-player', 'video'];
              for (var i = 0; i < picks.length; i++) {
                var list = document.querySelectorAll(picks[i]);
                for (var j = 0; j < list.length; j++) {
                  var r = list[j].getBoundingClientRect();
                  if (r.width >= vw * 0.4 && r.height > 0 && r.bottom > 0 && r.top <= vh * 0.3) {
                    return [r.left, r.top, r.width, r.height, vw, vh].join('|');
                  }
                }
              }
              return '';
            })();
        """.trimIndent()

        // "<left>|<top>|<width>|<height>|<viewport width>|<viewport height>|<picture left>|<top>|<width>|<height>" of the
        // video's box and then of the <video> picture itself (zeros when none is found), in CSS pixels, or ''.
        // The page is put back at the top first (the box shows only the top of it). The player is the first box, in the
        // order below, that is wide enough and sits near the top: a box further down (a suggestion's picture, a copy of
        // the player left over while YouTube changes video) is not the player.
        val MEASURE_SCRIPT = """
            (function () {
              window.scrollTo(0, 0);
              var root = document.scrollingElement || document.documentElement;
              if (root) root.scrollTop = 0;
              try { if (window.__wardtube) window.__wardtube.setMini(true); } catch (e) {}
              var vw = window.innerWidth, vh = window.innerHeight;
              var pic = [0, 0, 0, 0];
              var vids = document.querySelectorAll('video');
              for (var v = 0; v < vids.length; v++) {
                var b = vids[v].getBoundingClientRect();
                if (b.width >= vw * 0.4 && b.height > 0 && b.top >= -1 && b.top <= vh * 0.3) {
                  pic = [b.left, b.top, b.width, b.height];
                  break;
                }
              }
              var picks = ['#player-container-id', '#movie_player', '.html5-video-player', 'video'];
              for (var i = 0; i < picks.length; i++) {
                var list = document.querySelectorAll(picks[i]);
                for (var j = 0; j < list.length; j++) {
                  var r = list[j].getBoundingClientRect();
                  if (r.width >= vw * 0.4 && r.height > 0 && r.top >= -1 && r.top <= vh * 0.3) {
                    return [r.left, r.top, r.width, r.height, vw, vh].concat(pic).join('|');
                  }
                }
              }
              return '';
            })();
        """.trimIndent()
    }
}
