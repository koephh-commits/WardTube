// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.CompactDecimalFormat
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.mwmods.wardtube.R
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val ROW_CARD = 0
private const val ROW_SHELF = 1
private const val ROW_TILE = 2
private const val ROW_POST = 3
private const val ROW_FOOTER = 4

/** A row of Shorts goes after the third video, then after every sixth one; a row holds this many Shorts. */
private const val SHELF_SIZE = 8

/** How far (dp) the finger must travel in one direction before [SubsListener.onDrag] is told. */
private const val DRAG_THRESHOLD_DP = 24f

/** The width of the "All" button at the end of the strip of channels. */
private const val ALL_WIDTH_DP = 64

/** A post's words show this many lines at most (the rest is on the post's own page, one tap away). */
private const val POST_MAX_LINES = 12

/** How many times the strip may be put back at its first channel by the layout listener (a guard against a loop). */
private const val MAX_PIN_CORRECTIONS = 8

/** How long the strip and the chips take to settle (slide away or back) once the list stops moving, in milliseconds. */
private const val HEADER_SETTLE_MS = 180L

/**
 * A list with more rows than this is put on view again as a whole when it changes: finding what changed in it would take
 * longer than drawing it again (the Shorts chip's own list can hold thousands of tiles).
 */
private const val DIFF_MAX_ROWS = 1500

/** What the screen asks of whoever owns it. */
interface SubsListener {
    /** A channel was chosen in the strip (null: none, the videos of every channel again). */
    fun onChannel(channel: SubsChannel?)

    fun onVideo(video: SubsVideo)

    /** The three dots of a video's card were tapped: [anchor] is the button, the menu opens beside it. */
    fun onVideoMenu(video: SubsVideo, anchor: View)

    /** "Go to channel": the channel's own page. */
    fun onOpenChannel(channel: SubsChannel)

    /** "Manage" in the screen's own list of every subscription: YouTube's page that lists them (to unsubscribe, to set bells). */
    fun onOpenAllChannels()

    /** A link inside a post's text, or the video attached to a post, was tapped. */
    fun onLink(url: String)

    /** A chip was chosen (one of [SubsFilter]). */
    fun onFilter(filter: Int)

    /** The list was pulled down to refresh. */
    fun onRefresh()

    /** "Try again" was tapped on the empty screen that says a read failed. */
    fun onRetry()

    /** The list was scrolled close to its end: more videos can be loaded. */
    fun onEnd()

    /**
     * The finger is dragging over the screen: [down] is true when it moves up (the list scrolls down), false when it
     * moves down. The owner hides or shows the app's bottom bar by it, as it does for YouTube's own pages.
     */
    fun onDrag(down: Boolean)
}

/** The colours of the screen: YouTube's own dark and light greys. */
class SubsPalette(night: Boolean) {
    val background: Int = if (night) 0xFF0F0F0F.toInt() else 0xFFFFFFFF.toInt()
    val text: Int = if (night) 0xFFF1F1F1.toInt() else 0xFF0F0F0F.toInt()
    val secondary: Int = if (night) 0xFFAAAAAA.toInt() else 0xFF606060.toInt()
    val chip: Int = if (night) 0xFF272727.toInt() else 0xFFF2F2F2.toInt()
    val placeholder: Int = if (night) 0xFF212121.toInt() else 0xFFE5E5E5.toInt()
    val accent: Int = if (night) 0xFF3EA6FF.toInt() else 0xFF065FD4.toInt()

    /** The thin border of a poll's choice. */
    val outline: Int = if (night) 0xFF3F3F3F.toInt() else 0xFFCCCCCC.toInt()

    /** The card of the three-dots menu (YouTube's own: a dark grey card at night, white by day). */
    val menu: Int = if (night) 0xFF212121.toInt() else 0xFFFFFFFF.toInt()

    /** The pill button on the card of the playlists sheet ("New playlist"): a step lighter than the card. */
    val button: Int = if (night) 0xFF3F3F3F.toInt() else 0xFFF2F2F2.toInt()
}

/**
 * The Subscriptions screen, drawn by the app instead of YouTube's page: a strip of channel pictures on top (a tap
 * shows only that channel's videos, in place, with the strip staying where it is; a second tap goes back to every
 * channel) with an "All" button fixed at the end of its row (it opens the screen's own list of every subscription, with
 * a search box), the filter chips under it, and the videos below as big cards.
 *
 * The strip and the chips lie over the list and slide away while the list is scrolled down (and come back as soon as it
 * is scrolled up), so the videos get the room. While nothing is to be listed the screen says why: a skeleton while it
 * loads, "Try again" when a read failed, a short note when there is simply nothing.
 *
 * It is built in code (no layout file) and only shows what it is given: the data and the decisions are in
 * [SubsController].
 */
@SuppressLint("ViewConstructor")
class SubsScreen(
    context: Context,
    private val palette: SubsPalette,
    private val listener: SubsListener,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    /** The screen's width when it was made: only a hint for the size pictures are decoded at (the layout itself follows the real width). */
    private val screenWidth = resources.displayMetrics.widthPixels
    private val compact: CompactDecimalFormat =
        CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)

    private var channels: List<SubsChannel> = emptyList()
    private var newIds: Set<String> = emptySet()
    private var selectedChannel: SubsChannel? = null
    private var filter = SubsFilter.ALL
    private var loading = false
    private var failed = false
    private var loadingMore = false
    private var diagnostic = ""

    /** The rows the videos were built into (without the spinner row at the end), and the pictures of the channels they use. */
    private var builtRows: List<Row> = emptyList()
    private var avatarMap: Map<String, String> = emptyMap()

    /** The strip of channels, "Go to channel" and the chips: one block that lies over the list and slides away. */
    private val header = LinearLayout(context)
    private var headerHeight = 0

    private val strip = RecyclerView(context)

    /** True from the moment the screen opens until the person touches the strip: it is held at its first channel. */
    private var stripPinned = false
    private var pinCorrections = 0
    private val stripAdapter = StripAdapter()
    private val allButton = TextView(context)
    private val goToChannel = TextView(context)
    private val chips = RecyclerView(context)
    private val chipAdapter = ChipAdapter()

    /** The chips in the order they are shown, and what each one is (a [SubsFilter] value; its label is at the same place). */
    private val chipValues = intArrayOf(
        SubsFilter.ALL, SubsFilter.TODAY, SubsFilter.CONTINUE, SubsFilter.UNWATCHED, SubsFilter.VIDEOS, SubsFilter.SHORTS,
        SubsFilter.LIVE, SubsFilter.POSTS,
    )
    private val chipLabels = intArrayOf(
        R.string.subsChipAll, R.string.subsChipToday, R.string.subsChipContinue, R.string.subsChipUnwatched,
        R.string.subsChipVideos, R.string.subsChipShorts, R.string.subsChipLive, R.string.subsChipPosts,
    )
    private val refresh = SwipeRefreshLayout(context)
    private val list = RecyclerView(context)
    private val listAdapter = VideoAdapter()

    /** What stands where the list is empty: a skeleton while it loads, a note when there is nothing, "Try again" when a read failed. */
    private val stateBox = FrameLayout(context)
    private val skeleton = LinearLayout(context)
    private var skeletonPulse: ObjectAnimator? = null
    private val emptyText = TextView(context)
    private val errorBox = LinearLayout(context)
    private val errorNote = TextView(context)

    /** The screen's own list of every subscription (the "All" button), with its search box. */
    private val panel = FrameLayout(context)
    private val panelSearch = EditText(context)

    /** The header's two faces: the title row with the lens button under it, and the search row that replaces them while searching. */
    private val panelBar = LinearLayout(context)
    private val panelLensRow = LinearLayout(context)
    private val panelSearchRow = LinearLayout(context)
    private val panelSearchClear = ImageView(context)
    private lateinit var panelColumn: LinearLayout
    private val panelList = RecyclerView(context)
    private val panelEmpty = TextView(context)
    private val panelAdapter = PanelAdapter()
    private var panelQuery = ""

    private val circle = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private val roundedCorners = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
        }
    }

    init {
        // Everything touched here belongs to this screen: nothing may fall through to YouTube's page underneath.
        isClickable = true
        setBackgroundColor(palette.background)

        // The header is opaque and lies over the list (the list starts below it, see onHeaderLaidOut).
        header.orientation = LinearLayout.VERTICAL
        header.setBackgroundColor(palette.background)

        strip.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        strip.adapter = stripAdapter
        strip.overScrollMode = View.OVER_SCROLL_NEVER
        // A rebind (a blue dot appearing, a picture arriving) must not fade or move the pictures.
        strip.itemAnimator = null
        strip.clipToPadding = false
        // The end of the strip is left free for the "All" button that covers it (see stripRow below): the last channel
        // can be scrolled out from under the button. (Relative: the end is the left in Arabic.)
        strip.setPaddingRelative(dp(8), dp(8), dp(ALL_WIDTH_DP + 8), dp(4))
        // The strip opens at its first channel and stays there, whatever the layout passes of the first moments do,
        // until the person touches it.
        strip.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) stripPinned = false
                return false
            }
        })
        strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val manager = strip.layoutManager as? LinearLayoutManager
            if (stripPinned && manager != null && channels.isNotEmpty() && pinCorrections < MAX_PIN_CORRECTIONS &&
                manager.findFirstCompletelyVisibleItemPosition() != 0
            ) {
                pinCorrections++
                strip.scrollToPosition(0)
            }
        }

        // The row of the strip: the channels scroll across the whole width and "All" stays at the end of the row (right in
        // a left-to-right language, left in Arabic) like YouTube's, drawn over the strip with the screen's own colour, so
        // the channels that scroll under it are hidden. (The strip is not weighted beside the button in a LinearLayout
        // any more: that made the list measure twice with two different widths when the screen opened, and the strip did
        // not start from its first channel.)
        val stripRow = FrameLayout(context)
        stripRow.addView(strip, FrameLayout.LayoutParams(MATCH, WRAP))
        val allHolder = FrameLayout(context)
        allHolder.setBackgroundColor(palette.background)
        allHolder.isClickable = true // touches on it never reach the channels under it
        allButton.text = context.getString(R.string.subsAll)
        allButton.contentDescription = context.getString(R.string.subsAllTitle)
        allButton.textSize = 14f
        allButton.typeface = Typeface.DEFAULT_BOLD
        allButton.setTextColor(palette.accent)
        allButton.gravity = Gravity.CENTER
        allButton.setOnClickListener { openChannels() }
        // As high as a channel's picture with its ring (56dp + 3dp on each side) and level with it (the strip's top padding).
        val allParams = FrameLayout.LayoutParams(MATCH, dp(62))
        allParams.topMargin = dp(8)
        allHolder.addView(allButton, allParams)
        stripRow.addView(allHolder, FrameLayout.LayoutParams(dp(ALL_WIDTH_DP), MATCH, Gravity.END))
        header.addView(stripRow, LinearLayout.LayoutParams(MATCH, WRAP))

        goToChannel.text = context.getString(R.string.subsGoToChannel)
        goToChannel.textSize = 14f
        goToChannel.typeface = Typeface.DEFAULT_BOLD
        goToChannel.setTextColor(palette.text)
        goToChannel.gravity = Gravity.CENTER
        goToChannel.setPadding(dp(16), dp(9), dp(16), dp(9))
        goToChannel.background = rounded(palette.chip, dp(18).toFloat())
        goToChannel.visibility = View.GONE
        goToChannel.setOnClickListener { selectedChannel?.let { listener.onOpenChannel(it) } }
        val goParams = LinearLayout.LayoutParams(WRAP, WRAP)
        goParams.setMargins(dp(12), dp(4), dp(12), dp(8))
        goParams.gravity = Gravity.START
        header.addView(goToChannel, goParams)

        chips.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        chips.adapter = chipAdapter
        chips.overScrollMode = View.OVER_SCROLL_NEVER
        chips.clipToPadding = false
        chips.setPadding(dp(8), dp(2), dp(8), dp(8))
        header.addView(chips, LinearLayout.LayoutParams(MATCH, WRAP))

        // The list starts below the header once its height is known; it is read after the layout pass, not during it.
        header.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top != headerHeight) header.post { onHeaderLaidOut(header.height) }
        }

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = listAdapter
        list.overScrollMode = View.OVER_SCROLL_NEVER
        // Rows pass under the header (and into the room it leaves when it has slid away): no clipping to the padding.
        list.clipToPadding = false
        // Rows that change in place are redrawn at once (a post's card cross-fading as its count changes looks like a flicker).
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) header.animate().cancel()
                else if (newState == RecyclerView.SCROLL_STATE_IDLE) settleHeader()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                followScroll(dy)
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && manager.findLastVisibleItemPosition() >= listAdapter.itemCount - prefetchAhead(manager)) listener.onEnd()
            }
        })
        refresh.addView(list, ViewGroup.LayoutParams(MATCH, MATCH))
        refresh.setColorSchemeColors(palette.accent)
        refresh.setProgressBackgroundColorSchemeColor(palette.chip)
        refresh.setOnRefreshListener { listener.onRefresh() }

        val body = FrameLayout(context)
        body.addView(refresh, FrameLayout.LayoutParams(MATCH, MATCH))
        buildStates()
        body.addView(stateBox, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(header, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        addView(body, LayoutParams(MATCH, MATCH))

        buildPanel()
        addView(panel, LayoutParams(MATCH, MATCH))
    }

    // ---- The header (the strip and the chips) slides away with the list ----

    /**
     * The header's height is known (or changed: a channel was chosen and "Go to channel" appeared): the list starts below
     * it, the empty-state notes stand below it, and the pull-to-refresh spinner comes out from under it.
     */
    private fun onHeaderLaidOut(height: Int) {
        if (height <= 0 || height == headerHeight) return
        headerHeight = height
        list.setPadding(0, height, 0, 0)
        stateBox.setPadding(0, height, 0, 0)
        refresh.setProgressViewOffset(false, height - dp(40), height + dp(16))
        if (-header.translationY > height) header.translationY = -height.toFloat()
    }

    /**
     * The header follows the list: it goes up as the rows go up and comes back as soon as they come down (so it is never
     * far from the finger), and it is whole whenever the list is at its top.
     */
    private fun followScroll(dy: Int) {
        if (dy == 0 || headerHeight == 0) return
        val offset = if (list.canScrollVertically(-1)) (-header.translationY + dy).coerceIn(0f, headerHeight.toFloat()) else 0f
        if (offset != -header.translationY) {
            header.animate().cancel()
            header.translationY = -offset
        }
    }

    /**
     * The list stopped with the header half way: it goes the rest of the way, to whichever end is nearer. Near the top of
     * the list the header leaves together with the first rows and is left alone (moving it there would leave a gap).
     */
    private fun settleHeader() {
        if (headerHeight == 0) return
        val offset = -header.translationY
        if (offset <= 0f || offset >= headerHeight) return
        if (list.computeVerticalScrollOffset() < headerHeight) return
        val target = if (offset > headerHeight / 2f) -headerHeight.toFloat() else 0f
        header.animate().cancel()
        header.animate().translationY(target).setDuration(HEADER_SETTLE_MS).start()
    }

    /** The header whole again (another list was put on view). */
    private fun resetHeader() {
        header.animate().cancel()
        header.translationY = 0f
    }

    // ---- What stands where the list is empty ----

    private fun buildStates() {
        // A skeleton: three cards as grey blocks, breathing a little, while the first videos are read.
        skeleton.orientation = LinearLayout.VERTICAL
        for (i in 0 until 3) skeleton.addView(skeletonCard(), LinearLayout.LayoutParams(MATCH, WRAP))
        skeleton.visibility = View.GONE
        stateBox.addView(skeleton, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        emptyText.textSize = 15f
        emptyText.setTextColor(palette.secondary)
        emptyText.gravity = Gravity.CENTER
        emptyText.setPadding(dp(24), 0, dp(24), 0)
        emptyText.visibility = View.GONE
        stateBox.addView(emptyText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // A read failed and there is nothing to show: what happened, and a button to try again.
        errorBox.orientation = LinearLayout.VERTICAL
        errorBox.gravity = Gravity.CENTER_HORIZONTAL
        errorBox.setPadding(dp(32), 0, dp(32), 0)
        val icon = ImageView(context)
        icon.setImageResource(R.drawable.ic_error_24dp)
        icon.imageTintList = ColorStateList.valueOf(palette.secondary)
        errorBox.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = TextView(context)
        title.text = context.getString(R.string.subsLoadError)
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(palette.text)
        title.gravity = Gravity.CENTER
        val titleParams = LinearLayout.LayoutParams(WRAP, WRAP)
        titleParams.topMargin = dp(8)
        errorBox.addView(title, titleParams)
        errorNote.textSize = 12f
        errorNote.setTextColor(palette.secondary)
        errorNote.gravity = Gravity.CENTER
        errorNote.maxLines = 4
        errorNote.ellipsize = TextUtils.TruncateAt.END
        errorNote.visibility = View.GONE
        val noteParams = LinearLayout.LayoutParams(WRAP, WRAP)
        noteParams.topMargin = dp(6)
        errorBox.addView(errorNote, noteParams)
        val retry = TextView(context)
        retry.text = context.getString(R.string.subsRetry)
        retry.textSize = 14f
        retry.typeface = Typeface.DEFAULT_BOLD
        // The accent is light at night and dark by day: the screen's own colour is what reads on it in both.
        retry.setTextColor(palette.background)
        retry.gravity = Gravity.CENTER
        retry.setPadding(dp(24), dp(10), dp(24), dp(10))
        retry.background = rounded(palette.accent, dp(20).toFloat())
        retry.setOnClickListener { listener.onRetry() }
        val retryParams = LinearLayout.LayoutParams(WRAP, WRAP)
        retryParams.topMargin = dp(20)
        errorBox.addView(retry, retryParams)
        errorBox.visibility = View.GONE
        stateBox.addView(errorBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    /** A video's card as grey blocks: the picture, the channel's round picture and two lines. */
    private fun skeletonCard(): View {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        val thumb = RatioFrame(context, 9f / 16f)
        thumb.setBackgroundColor(palette.placeholder)
        card.addView(thumb, LinearLayout.LayoutParams(MATCH, WRAP))
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(12), dp(12), dp(12), dp(16))
        val avatar = View(context)
        avatar.background = oval(palette.placeholder, Color.TRANSPARENT)
        row.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))
        val lines = LinearLayout(context)
        lines.orientation = LinearLayout.VERTICAL
        val first = View(context)
        first.background = rounded(palette.placeholder, dp(4).toFloat())
        lines.addView(first, LinearLayout.LayoutParams(MATCH, dp(14)))
        val second = View(context)
        second.background = rounded(palette.placeholder, dp(4).toFloat())
        val secondParams = LinearLayout.LayoutParams(dp(160), dp(12))
        secondParams.topMargin = dp(8)
        lines.addView(second, secondParams)
        val linesParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        linesParams.marginStart = dp(12)
        row.addView(lines, linesParams)
        card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return card
    }

    /** The skeleton breathes (its opacity goes down and up) while it is on view, and only then. */
    private fun setPulse(on: Boolean) {
        if (on) {
            var pulse = skeletonPulse
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 1f, 0.45f)
                pulse.duration = 800L
                pulse.repeatMode = ValueAnimator.REVERSE
                pulse.repeatCount = ValueAnimator.INFINITE
                skeletonPulse = pulse
            }
            if (!pulse.isStarted) pulse.start()
        } else {
            skeletonPulse?.cancel()
            skeleton.alpha = 1f
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // A screen that is not on view must not keep an animation running.
        if (changedView === this && visibility != View.VISIBLE) skeletonPulse?.cancel()
    }

    override fun onDetachedFromWindow() {
        skeletonPulse?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- Watching the finger (the bottom bar hides while scrolling) ----

    private var dragLastX = 0f
    private var dragLastY = 0f
    private var dragTravelled = 0f
    private var dragResync = false
    private val dragThreshold = DRAG_THRESHOLD_DP * density

    /**
     * Follows the finger over the whole screen, never taking a touch from the views below: the same rule the browsing
     * page uses (MainActivity.watchScrollDirection), because this screen covers the page and none of its touches reach
     * the WebView. It sits here and not on the list so that a pull-down at the top of the list (taken by the
     * pull-to-refresh layout) and a drag that starts on the strip or the chips are seen too.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragLastX = event.x
                dragLastY = event.y
                dragTravelled = 0f
                dragResync = false
            }
            // A finger was added or lifted: measure again from where the one that is left is.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> dragResync = true
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                if (dragResync) {
                    dragLastX = event.x
                    dragLastY = event.y
                    dragTravelled = 0f
                    dragResync = false
                } else {
                    val dx = event.x - dragLastX
                    val dy = event.y - dragLastY
                    dragLastX = event.x
                    dragLastY = event.y
                    if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        // Distance in one direction; it starts over when the finger turns around.
                        dragTravelled = if (dragTravelled * dy < 0f) dy else dragTravelled + dy
                        if (dragTravelled <= -dragThreshold) listener.onDrag(down = true)
                        else if (dragTravelled >= dragThreshold) listener.onDrag(down = false)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // ---- What the owner tells the screen ----

    fun setChannels(list: List<SubsChannel>, fresh: Set<String>) {
        val sameOrder = list.size == channels.size && list.indices.all { list[it].id == channels[it].id }
        channels = list
        newIds = fresh
        refreshStrip(sameOrder)
        if (panel.visibility == View.VISIBLE) panelAdapter.refilter()
    }

    fun setSelected(channel: SubsChannel?) {
        selectedChannel = channel
        goToChannel.visibility = if (channel != null) View.VISIBLE else View.GONE
        refreshStrip(true)
    }

    /**
     * Redraws the strip. When the channels are in the same order only the pictures and dots are rebound: the strip
     * keeps its place and a fling in progress is not disturbed (a full reset of the adapter did both).
     */
    private fun refreshStrip(sameOrder: Boolean) {
        if (sameOrder) stripAdapter.notifyItemRangeChanged(0, channels.size) else stripAdapter.notifyDataSetChanged()
    }

    /** The strip of channels goes back to its first channel (the right end in Arabic). */
    fun scrollStripToStart() {
        stripPinned = true
        pinCorrections = 0
        strip.stopScroll()
        strip.scrollToPosition(0)
    }

    /** The chosen chip. The Shorts chip lists the Shorts in columns of tiles, the others in one column. */
    fun setFilter(value: Int) {
        if (value == filter) return
        val wasShorts = filter == SubsFilter.SHORTS
        filter = value
        if (wasShorts != (value == SubsFilter.SHORTS)) list.layoutManager = layoutFor(value)
        resetHeader()
        chipAdapter.notifyDataSetChanged()
    }

    /** One column of cards, or (the Shorts chip) tiles side by side: two on a phone, more on a wide screen. */
    private fun layoutFor(value: Int): RecyclerView.LayoutManager {
        if (value != SubsFilter.SHORTS) return LinearLayoutManager(context)
        val columns = ((resources.displayMetrics.widthPixels / density) / 170f).toInt().coerceIn(2, 5)
        val manager = GridLayoutManager(context, columns)
        // The spinner row at the end of the list is as wide as the list.
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (listAdapter.isFooter(position)) columns else 1
        }
        return manager
    }

    /**
     * [videos] are already filtered by the chip; this puts them in rows (videos, rows of Shorts, or Shorts tiles).
     * [toTop]: it is another list (a chip or a channel was chosen): it starts from its top with the header whole.
     */
    fun setVideos(videos: List<SubsVideo>, avatars: Map<String, String>, toTop: Boolean) {
        avatarMap = avatars
        builtRows = buildRows(videos)
        pushRows(toTop)
        if (toTop) {
            list.scrollToPosition(0)
            resetHeader()
        }
    }

    /** Puts the built rows on view, with the spinner row after them while older videos are being read. */
    private fun pushRows(replace: Boolean) {
        val rows = if (loadingMore && builtRows.isNotEmpty()) builtRows + Row.Footer else builtRows
        listAdapter.submit(rows, avatarMap, replace)
        updateEmptyState()
    }

    /** Videos are still being fetched: a skeleton shows while there is nothing to list yet. */
    fun setLoading(on: Boolean) {
        loading = on
        updateEmptyState()
    }

    /** The last read failed: when there is nothing to list, the screen says so and offers "Try again". */
    fun setError(on: Boolean) {
        if (failed == on) return
        failed = on
        updateEmptyState()
    }

    /** Older videos are being read: a small spinner shows at the end of the list. */
    fun setLoadingMore(on: Boolean) {
        if (loadingMore == on) return
        loadingMore = on
        pushRows(false)
    }

    /** What to show under "no videos" (why there are none), or "". */
    fun setDiagnostic(text: String) {
        diagnostic = text
        updateEmptyState()
    }

    private var endCheckPending = false

    /**
     * Asks the owner for more videos if the end of the list is already in view. A scroll does that by itself, but a
     * list that is short, or that finished loading while the person sat at the bottom, gets no scroll. The check runs
     * once the list has been laid out (so what is in view is known).
     */
    fun checkEnd() {
        if (endCheckPending) return
        endCheckPending = true
        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                endCheckPending = false
                val observer = list.viewTreeObserver
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                val manager = list.layoutManager as? LinearLayoutManager
                val count = listAdapter.itemCount
                if (manager != null && count > 0 && manager.findLastVisibleItemPosition() >= count - prefetchAhead(manager)) listener.onEnd()
                return true
            }
        })
    }

    /**
     * How many items before the end of the list the next page is asked for. The Shorts grid asks earlier (its tiles are
     * tall, two to a row: 12 of them are about three screens): the list then goes on under the finger, with no wait at
     * its end, like YouTube's.
     */
    private fun prefetchAhead(manager: LinearLayoutManager): Int = if (manager is GridLayoutManager) 12 else 4

    /** Ends (or shows) the pull-to-refresh spinner. */
    fun setRefreshing(on: Boolean) {
        if (refresh.isRefreshing != on) refresh.isRefreshing = on
    }

    private fun updateEmptyState() {
        val empty = listAdapter.itemCount == 0
        val skeletonOn = empty && loading
        val errorOn = empty && !loading && failed
        val emptyOn = empty && !loading && !failed
        skeleton.visibility = if (skeletonOn) View.VISIBLE else View.GONE
        setPulse(skeletonOn)
        errorBox.visibility = if (errorOn) View.VISIBLE else View.GONE
        errorNote.text = diagnostic
        errorNote.visibility = if (diagnostic.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (emptyOn) View.VISIBLE else View.GONE
        val base = context.getString(
            when (filter) {
                SubsFilter.LIVE -> R.string.subsEmptyLive
                SubsFilter.POSTS -> R.string.subsEmptyPosts
                SubsFilter.UNWATCHED -> R.string.subsEmptyUnwatched
                SubsFilter.CONTINUE -> R.string.subsEmptyContinue
                else -> R.string.subsEmpty
            }
        )
        emptyText.text = if (diagnostic.isEmpty()) base else base + "\n\n" + diagnostic
    }

    private fun choose(channel: SubsChannel?) {
        setSelected(channel)
        listener.onChannel(channel)
    }

    private fun chooseFilter(value: Int) {
        if (value == filter) return
        setFilter(value)
        listener.onFilter(value)
    }

    // ---- The list of every subscription (the "All" button) ----

    /**
     * A page over the screen with every channel the account is subscribed to (the ones with something new first, then by
     * name) and a search box. A tap on a channel shows its videos, as a tap in the strip does. "Manage" goes to YouTube's
     * own page of the subscriptions (to unsubscribe or set the bells).
     */
    private fun buildPanel() {
        panel.visibility = View.GONE
        panel.isClickable = true
        panel.setBackgroundColor(palette.background)

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        panelColumn = column
        val bar = panelBar
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(4), dp(4), dp(8), dp(4))
        val back = ImageView(context)
        back.setImageResource(R.drawable.ic_arrow_back_white_24dp)
        back.imageTintList = ColorStateList.valueOf(palette.text)
        back.contentDescription = context.getString(R.string.browserBack)
        back.scaleType = ImageView.ScaleType.CENTER
        back.isClickable = true
        back.background = rippleBackground()
        back.setOnClickListener { closeChannels() }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = TextView(context)
        title.text = context.getString(R.string.subsAllTitle)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(palette.text)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val titleParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        titleParams.marginStart = dp(4)
        bar.addView(title, titleParams)
        val manage = TextView(context)
        manage.text = context.getString(R.string.subsManage)
        manage.contentDescription = context.getString(R.string.subsManageHint)
        manage.textSize = 14f
        manage.typeface = Typeface.DEFAULT_BOLD
        manage.setTextColor(palette.accent)
        manage.gravity = Gravity.CENTER
        manage.setPadding(dp(12), dp(10), dp(12), dp(10))
        manage.setOnClickListener {
            closeChannels()
            listener.onOpenAllChannels()
        }
        bar.addView(manage, LinearLayout.LayoutParams(WRAP, WRAP))
        column.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        // The lens button, under the title row: the lens with its words beside it. The search box is not always on the
        // screen any more; this button opens it (see openPanelSearch).
        val lens = LinearLayout(context)
        lens.orientation = LinearLayout.HORIZONTAL
        lens.gravity = Gravity.CENTER_VERTICAL
        lens.isClickable = true
        lens.isFocusable = true
        lens.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x33808080),
            rounded(palette.chip, dp(20).toFloat()),
            rounded(Color.WHITE, dp(20).toFloat()),
        )
        lens.setPadding(dp(14), 0, dp(18), 0)
        lens.contentDescription = context.getString(R.string.subsSearchButton)
        val lensIcon = ImageView(context)
        lensIcon.setImageResource(R.drawable.ic_search_24dp)
        lensIcon.imageTintList = ColorStateList.valueOf(palette.text)
        lensIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        lens.addView(lensIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        val lensLabel = TextView(context)
        lensLabel.text = context.getString(R.string.subsSearchButton)
        lensLabel.textSize = 14f
        lensLabel.maxLines = 1
        lensLabel.ellipsize = TextUtils.TruncateAt.END
        lensLabel.setTextColor(palette.text)
        lensLabel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val lensLabelParams = LinearLayout.LayoutParams(WRAP, WRAP)
        lensLabelParams.marginStart = dp(10)
        lens.addView(lensLabel, lensLabelParams)
        lens.setOnClickListener { openPanelSearch() }
        panelLensRow.orientation = LinearLayout.HORIZONTAL
        panelLensRow.setPadding(dp(12), dp(2), dp(12), dp(10))
        panelLensRow.addView(lens, LinearLayout.LayoutParams(WRAP, dp(40)))
        column.addView(panelLensRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // The search row: back arrow (closes the search and brings the title row back), then a pill with the glass, the
        // text and an x that empties it. It is hidden until the lens is tapped (see openPanelSearch).
        val row = panelSearchRow
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(4), dp(12), dp(4))
        row.visibility = View.GONE
        val closeSearch = ImageView(context)
        closeSearch.setImageResource(R.drawable.ic_arrow_back_white_24dp)
        closeSearch.imageTintList = ColorStateList.valueOf(palette.text)
        closeSearch.contentDescription = context.getString(R.string.browserBack)
        closeSearch.scaleType = ImageView.ScaleType.CENTER
        closeSearch.isClickable = true
        closeSearch.background = rippleBackground()
        closeSearch.setOnClickListener { closePanelSearch() }
        row.addView(closeSearch, LinearLayout.LayoutParams(dp(48), dp(48)))
        val pill = LinearLayout(context)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = rounded(palette.chip, dp(22).toFloat())
        val glass = ImageView(context)
        glass.setImageResource(R.drawable.ic_search_24dp)
        glass.imageTintList = ColorStateList.valueOf(palette.secondary)
        glass.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glassParams = LinearLayout.LayoutParams(dp(20), dp(20))
        glassParams.marginStart = dp(14)
        pill.addView(glass, glassParams)
        panelSearch.hint = context.getString(R.string.subsSearchHint)
        panelSearch.setHintTextColor(palette.secondary)
        panelSearch.setTextColor(palette.text)
        panelSearch.textSize = 15f
        panelSearch.setSingleLine()
        panelSearch.imeOptions = EditorInfo.IME_ACTION_SEARCH
        panelSearch.background = null
        panelSearch.setPadding(dp(10), 0, dp(4), 0)
        panelSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        panelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                panelQuery = s?.toString().orEmpty().trim()
                panelSearchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                panelAdapter.refilter()
            }
        })
        pill.addView(panelSearch, LinearLayout.LayoutParams(0, MATCH, 1f))
        panelSearchClear.setImageResource(R.drawable.ic_close_24dp)
        panelSearchClear.imageTintList = ColorStateList.valueOf(palette.secondary)
        panelSearchClear.contentDescription = context.getString(R.string.browserClear)
        panelSearchClear.scaleType = ImageView.ScaleType.CENTER
        panelSearchClear.isClickable = true
        panelSearchClear.background = rippleBackground()
        panelSearchClear.visibility = View.GONE
        panelSearchClear.setOnClickListener { panelSearch.setText("") }
        pill.addView(panelSearchClear, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(pill, LinearLayout.LayoutParams(0, dp(44), 1f))
        column.addView(row, LinearLayout.LayoutParams(MATCH, dp(56)))

        panelList.layoutManager = LinearLayoutManager(context)
        panelList.adapter = panelAdapter
        panelList.overScrollMode = View.OVER_SCROLL_NEVER
        panelList.itemAnimator = null
        panelList.setPadding(0, 0, 0, dp(16))
        panelList.clipToPadding = false
        panelEmpty.text = context.getString(R.string.subsNoChannelMatch)
        panelEmpty.textSize = 15f
        panelEmpty.setTextColor(palette.secondary)
        panelEmpty.gravity = Gravity.CENTER
        panelEmpty.visibility = View.GONE
        val area = FrameLayout(context)
        area.addView(panelList, FrameLayout.LayoutParams(MATCH, MATCH))
        area.addView(panelEmpty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        column.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))

        panel.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** The lens button was tapped: the title row and the button give way to the search row, and the keyboard comes up. */
    private fun openPanelSearch() {
        if (panelSearchRow.visibility == View.VISIBLE) return
        android.transition.TransitionManager.beginDelayedTransition(
            panelColumn,
            android.transition.AutoTransition().setDuration(160).addTarget(panelBar).addTarget(panelLensRow).addTarget(panelSearchRow),
        )
        panelBar.visibility = View.GONE
        panelLensRow.visibility = View.GONE
        panelSearchRow.visibility = View.VISIBLE
        panelSearch.requestFocus()
        panelSearch.post {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(panelSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Closes the search row (the words are cleared, so the list is whole again) and shows the title row and the button. */
    private fun closePanelSearch(animate: Boolean = true) {
        if (panelSearchRow.visibility != View.VISIBLE) return
        hideKeyboard()
        if (animate) {
            android.transition.TransitionManager.beginDelayedTransition(
                panelColumn,
                android.transition.AutoTransition().setDuration(160).addTarget(panelBar).addTarget(panelLensRow).addTarget(panelSearchRow),
            )
        }
        panelSearch.setText("")
        panelSearchRow.visibility = View.GONE
        panelBar.visibility = View.VISIBLE
        panelLensRow.visibility = View.VISIBLE
    }

    private fun openChannels() {
        if (panel.visibility == View.VISIBLE) return
        closePanelSearch(animate = false)
        panelSearch.setText("") // also builds the list again, from the newest channels
        panelAdapter.refilter()
        panel.visibility = View.VISIBLE
        panelList.scrollToPosition(0)
    }

    /** Closes the list of every subscription (also when the screen goes away, so it is not found open when it returns). */
    fun closeChannels() {
        if (panel.visibility != View.VISIBLE) return
        closePanelSearch(animate = false)
        hideKeyboard()
        panel.visibility = View.GONE
    }

    /** The back button: closes the list of every subscription if it is open. Returns true when it did (the press is used up). */
    fun onBack(): Boolean {
        if (panel.visibility != View.VISIBLE) return false
        // The search row first (back from it shows the title row again), then the list itself.
        if (panelSearchRow.visibility == View.VISIBLE) {
            closePanelSearch()
            return true
        }
        closeChannels()
        return true
    }

    private fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(windowToken, 0)
        panelSearch.clearFocus()
    }

    /** A channel was tapped in the list of every subscription: its videos show, and the strip scrolls to its picture. */
    private fun pickChannel(channel: SubsChannel) {
        closeChannels()
        if (selectedChannel?.id == channel.id) return
        stripPinned = false
        choose(channel)
        val index = channels.indexOfFirst { it.id == channel.id }
        (strip.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(maxOf(index, 0), dp(4))
    }

    private class PanelHolder(root: View, val avatar: ImageView, val name: TextView, val dot: View) : RecyclerView.ViewHolder(root)

    private inner class PanelAdapter : RecyclerView.Adapter<PanelHolder>() {
        private var shown: List<SubsChannel> = emptyList()

        /** Builds the list again from the channels and the words in the search box: the ones with something new first, then by name. */
        fun refilter() {
            val words = panelQuery.lowercase()
            val ordered = channels.sortedWith(compareByDescending<SubsChannel> { it.id in newIds }.thenBy { it.name.lowercase() })
            shown = if (words.isEmpty()) ordered else ordered.filter { it.name.lowercase().contains(words) }
            notifyDataSetChanged()
            panelEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun getItemCount(): Int = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PanelHolder {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(8), dp(16), dp(8))
            row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            row.background = selectableBackground()
            val avatar = roundPicture()
            row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))
            val name = TextView(context)
            name.textSize = 15f
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            name.setTextColor(palette.text)
            val nameParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            nameParams.marginStart = dp(16)
            row.addView(name, nameParams)
            val dot = View(context)
            dot.background = oval(palette.accent, Color.TRANSPARENT)
            val dotParams = LinearLayout.LayoutParams(dp(10), dp(10))
            dotParams.marginStart = dp(12)
            row.addView(dot, dotParams)
            return PanelHolder(row, avatar, name, dot)
        }

        override fun onBindViewHolder(holder: PanelHolder, position: Int) {
            val channel = shown[position]
            val chosen = selectedChannel?.id == channel.id
            holder.name.text = channel.name
            holder.name.typeface = if (chosen) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.dot.visibility = if (channel.id in newIds) View.VISIBLE else View.GONE
            SubsImages.load(holder.avatar, channel.avatar, null, dp(88))
            holder.itemView.setOnClickListener { pickChannel(channel) }
        }
    }

    // ---- The rows of the list ----

    /**
     * What a row of the list is. Each has a [key] (what it stands for: the same video is the same row wherever it moves)
     * and compares by what it holds, so that [RowDiff] can tell which rows changed when a page arrives or a video is hidden.
     */
    private sealed class Row(val key: String) {
        data class Card(val video: SubsVideo) : Row("v:" + video.id)
        data class Shelf(val shorts: List<SubsVideo>) : Row("s:" + shorts.firstOrNull()?.id.orEmpty())
        data class Tile(val video: SubsVideo) : Row("t:" + video.id)
        data class Post(val video: SubsVideo) : Row("p:" + video.id)

        /** The spinner at the end of the list while older videos are being read. */
        data object Footer : Row("footer")
    }

    private class RowDiff(private val before: List<Row>, private val after: List<Row>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = before.size

        override fun getNewListSize(): Int = after.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            before[oldItemPosition].key == after[newItemPosition].key

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            before[oldItemPosition] == after[newItemPosition]
    }

    /** A post is drawn as a post's card, any other video as a video's card. */
    private fun cardOf(video: SubsVideo): Row = if (video.post != null) Row.Post(video) else Row.Card(video)

    /** True when a row of Shorts goes after the video at [index] (counting from 0). */
    private fun shelfAfter(index: Int): Boolean = index == 2 || (index > 2 && (index - 2) % 6 == 0)

    private fun buildRows(videos: List<SubsVideo>): List<Row> {
        if (filter == SubsFilter.SHORTS) return videos.filter { it.short }.map { Row.Tile(it) }
        val normal = videos.filter { !it.short }
        if (filter != SubsFilter.ALL) return normal.map { cardOf(it) }
        val shorts = videos.filter { it.short }
        val rows = ArrayList<Row>(normal.size + 4)
        var next = 0
        for ((index, video) in normal.withIndex()) {
            rows.add(cardOf(video))
            if (next < shorts.size && shelfAfter(index)) {
                rows.add(Row.Shelf(shorts.subList(next, minOf(next + SHELF_SIZE, shorts.size)).toList()))
                next += SHELF_SIZE
            }
        }
        // Too few videos to reach the first row of Shorts: show it at the end rather than not at all.
        if (next == 0 && shorts.isNotEmpty()) rows.add(Row.Shelf(shorts.subList(0, minOf(SHELF_SIZE, shorts.size)).toList()))
        return rows
    }

    // ---- Small helpers ----

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = radius
        return shape
    }

    /** The round touch feedback of the phone's theme (a plain background where the theme has none). */
    private fun rippleBackground(): android.graphics.drawable.Drawable? {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        try {
            return attrs.getDrawable(0)
        } finally {
            attrs.recycle()
        }
    }

    /** The touch feedback of the phone's theme for a whole row (it stays inside the row). */
    private fun selectableBackground(): android.graphics.drawable.Drawable? {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            return attrs.getDrawable(0)
        } finally {
            attrs.recycle()
        }
    }

    private fun oval(color: Int, stroke: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(color)
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(2), stroke)
        return shape
    }

    /** The ring around the chosen channel's picture. */
    private fun ring(): GradientDrawable {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(Color.TRANSPARENT)
        shape.setStroke(dp(2), palette.text)
        return shape
    }

    private fun roundPicture(): ImageView {
        val picture = ImageView(context)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        picture.setBackgroundColor(palette.placeholder)
        picture.outlineProvider = circle
        picture.clipToOutline = true
        return picture
    }

    // ---- The chips ----

    private class ChipHolder(root: View, val label: TextView) : RecyclerView.ViewHolder(root)

    private inner class ChipAdapter : RecyclerView.Adapter<ChipHolder>() {
        override fun getItemCount(): Int = chipValues.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipHolder {
            // Equal padding on both sides of the chip (not a margin), so nothing depends on the layout direction.
            val box = FrameLayout(context)
            box.layoutParams = RecyclerView.LayoutParams(WRAP, WRAP)
            box.setPadding(dp(4), 0, dp(4), 0)
            val label = TextView(context)
            label.textSize = 14f
            label.typeface = Typeface.DEFAULT_BOLD
            label.gravity = Gravity.CENTER
            label.setPadding(dp(12), dp(7), dp(12), dp(7))
            box.addView(label, FrameLayout.LayoutParams(WRAP, WRAP))
            return ChipHolder(box, label)
        }

        override fun onBindViewHolder(holder: ChipHolder, position: Int) {
            val value = chipValues[position]
            val chosen = value == filter
            holder.label.text = context.getString(chipLabels[position])
            holder.label.setTextColor(if (chosen) palette.background else palette.text)
            holder.label.background = rounded(if (chosen) palette.text else palette.chip, dp(8).toFloat())
            holder.itemView.isSelected = chosen
            holder.itemView.setOnClickListener { chooseFilter(value) }
        }
    }

    // ---- The strip of channels ----

    private class StripHolder(
        root: View,
        val ring: View?,
        val avatar: ImageView?,
        val dot: View?,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    private inner class StripAdapter : RecyclerView.Adapter<StripHolder>() {
        override fun getItemCount(): Int = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripHolder = buildChannelItem()

        override fun onBindViewHolder(holder: StripHolder, position: Int) {
            bindChannel(holder, channels[position])
        }
    }

    private fun buildChannelItem(): StripHolder {
        val item = LinearLayout(context)
        item.orientation = LinearLayout.VERTICAL
        item.gravity = Gravity.CENTER_HORIZONTAL
        item.layoutParams = RecyclerView.LayoutParams(dp(80), WRAP)

        val ring = FrameLayout(context)
        ring.setPadding(dp(3), dp(3), dp(3), dp(3))
        val avatar = roundPicture()
        ring.addView(avatar, FrameLayout.LayoutParams(dp(56), dp(56)))
        val dot = View(context)
        dot.background = oval(palette.accent, palette.background)
        dot.visibility = View.GONE
        ring.addView(dot, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END))
        item.addView(ring, LinearLayout.LayoutParams(WRAP, WRAP))

        val label = TextView(context)
        label.textSize = 12f
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        val labelParams = LinearLayout.LayoutParams(dp(72), WRAP)
        labelParams.topMargin = dp(4)
        item.addView(label, labelParams)
        return StripHolder(item, ring, avatar, dot, label)
    }

    private fun bindChannel(holder: StripHolder, channel: SubsChannel) {
        val chosen = selectedChannel?.id == channel.id
        val fresh = !chosen && channel.id in newIds
        holder.label.text = channel.name
        holder.label.setTextColor(if (chosen) palette.text else palette.secondary)
        holder.ring?.background = if (chosen) ring() else null
        holder.dot?.visibility = if (fresh) View.VISIBLE else View.GONE
        holder.avatar?.let { SubsImages.load(it, channel.avatar, null, dp(72)) }
        // What a screen reader says: the name, and that the channel has something new.
        holder.itemView.contentDescription = if (fresh) context.getString(R.string.subsChannelNew, channel.name) else channel.name
        holder.itemView.isSelected = chosen
        // Tapping the chosen channel again goes back to every channel.
        holder.itemView.setOnClickListener { choose(if (chosen) null else channel) }
    }

    // ---- The videos, the Shorts ----

    private class CardHolder(
        root: View,
        val thumb: ImageView,
        val duration: TextView,
        /** The red line under the picture (how much was watched) and its two parts: the watched part and the rest. */
        val progress: View,
        val done: View,
        val rest: View,
        val avatar: ImageView,
        val title: TextView,
        val meta: TextView,
        val more: ImageView,
    ) : RecyclerView.ViewHolder(root)

    private class ShortHolder(
        root: View,
        val thumb: ImageView,
        val title: TextView,
        val views: TextView,
        val more: ImageView,
    ) : RecyclerView.ViewHolder(root)

    /** A row of Shorts in the list: its own sideways list of Shorts under a title. */
    private class ShelfHolder(root: View, val shorts: ShelfAdapter) : RecyclerView.ViewHolder(root)

    /** The spinner row at the end of the list. */
    private class FooterHolder(root: View) : RecyclerView.ViewHolder(root)

    private inner class VideoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        private var avatars: Map<String, String> = emptyMap()

        /**
         * Puts [newRows] on view. Only the rows that changed are touched ([RowDiff]): the list keeps its place, the rows
         * that stay are not drawn again, and a hidden video leaves with an animation. A different list ([replace]: a chip
         * or a channel was chosen), new channel pictures, an empty list on either side or a very long one are put on
         * view whole.
         */
        fun submit(newRows: List<Row>, avatarOf: Map<String, String>, replace: Boolean) {
            val old = rows
            val sameAvatars = avatarOf === avatars || avatarOf == avatars
            rows = newRows
            avatars = avatarOf
            if (replace || !sameAvatars || old.isEmpty() || newRows.isEmpty() || old.size > DIFF_MAX_ROWS || newRows.size > DIFF_MAX_ROWS) {
                notifyDataSetChanged()
            } else {
                DiffUtil.calculateDiff(RowDiff(old, newRows), false).dispatchUpdatesTo(this)
            }
        }

        fun isFooter(position: Int): Boolean = rows.getOrNull(position) is Row.Footer

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            when (rows[position]) {
                is Row.Card -> ROW_CARD
                is Row.Shelf -> ROW_SHELF
                is Row.Tile -> ROW_TILE
                is Row.Post -> ROW_POST
                is Row.Footer -> ROW_FOOTER
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                ROW_SHELF -> buildShelfItem()
                ROW_TILE -> buildShortCard(MATCH, 0)
                ROW_POST -> buildPostItem()
                ROW_FOOTER -> buildFooterItem()
                else -> buildVideoItem()
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Card -> bindCard(holder as CardHolder, row.video, avatars)
                is Row.Shelf -> (holder as ShelfHolder).shorts.submit(row.shorts)
                is Row.Tile -> bindShort(holder as ShortHolder, row.video)
                is Row.Post -> bindPost(holder as PostHolder, row.video, avatars)
                is Row.Footer -> Unit
            }
        }
    }

    private inner class ShelfAdapter : RecyclerView.Adapter<ShortHolder>() {
        private var items: List<SubsVideo> = emptyList()

        fun submit(videos: List<SubsVideo>) {
            if (items == videos) return
            items = videos
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortHolder = buildShortCard(dp(150), dp(264))

        override fun onBindViewHolder(holder: ShortHolder, position: Int) = bindShort(holder, items[position])
    }

    private fun buildFooterItem(): FooterHolder {
        val box = FrameLayout(context)
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        box.setPadding(0, dp(16), 0, dp(24))
        val bar = ProgressBar(context)
        bar.isIndeterminate = true
        bar.indeterminateTintList = ColorStateList.valueOf(palette.accent)
        box.addView(bar, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_HORIZONTAL))
        return FooterHolder(box)
    }

    private fun bindCard(holder: CardHolder, video: SubsVideo, avatars: Map<String, String>) {
        holder.title.text = video.title
        holder.meta.text = metaLine(video)
        // The corner badge: "LIVE" (red) for a stream on now, "Upcoming" for a scheduled one, else the length.
        val badge = when (video.live) {
            1 -> context.getString(R.string.subsLiveBadge)
            2 -> context.getString(R.string.subsUpcomingBadge)
            else -> video.duration
        }
        holder.duration.text = badge
        holder.duration.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        holder.duration.background = rounded(if (video.live == 1) 0xE6CC0000.toInt() else 0xCC000000.toInt(), dp(4).toFloat())
        // The red line under the picture: how much the account has watched, as a share of the picture's width.
        val watched = video.watched.coerceIn(0, 100)
        (holder.done.layoutParams as LinearLayout.LayoutParams).weight = watched.toFloat()
        (holder.rest.layoutParams as LinearLayout.LayoutParams).weight = (100 - watched).toFloat()
        holder.progress.visibility = if (watched > 0) View.VISIBLE else View.GONE
        holder.progress.requestLayout()
        SubsImages.load(
            holder.thumb,
            "https://i.ytimg.com/vi/${video.id}/hq720.jpg",
            "https://i.ytimg.com/vi/${video.id}/mqdefault.jpg",
            (screenWidth * 0.8f).toInt(),
        )
        val avatar = avatars[video.channelId] ?: avatars[video.channelName] ?: ""
        SubsImages.load(holder.avatar, avatar, null, dp(48))
        holder.itemView.setOnClickListener { listener.onVideo(video) }
        holder.more.setOnClickListener { listener.onVideoMenu(video, it) }
    }

    private fun bindShort(holder: ShortHolder, video: SubsVideo) {
        holder.title.text = video.title
        holder.title.visibility = if (video.title.isEmpty()) View.GONE else View.VISIBLE
        holder.views.text = video.views
        holder.views.visibility = if (video.views.isEmpty()) View.GONE else View.VISIBLE
        // YouTube keeps a tall picture for a Short; the usual one is the way out.
        SubsImages.load(
            holder.thumb,
            "https://i.ytimg.com/vi/${video.id}/oardefault.jpg",
            "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg",
            screenWidth / 2,
        )
        holder.itemView.setOnClickListener { listener.onVideo(video) }
        holder.more.setOnClickListener { listener.onVideoMenu(video, it) }
    }

    private fun buildVideoItem(): CardHolder {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)

        // The picture is 16:9 at whatever width the card has (the phone may be turned sideways after the card was made).
        val thumbBox = RatioFrame(context, 9f / 16f)
        val thumb = ImageView(context)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(palette.placeholder)
        thumbBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
        val duration = TextView(context)
        duration.textSize = 12f
        duration.typeface = Typeface.DEFAULT_BOLD
        duration.setTextColor(Color.WHITE)
        duration.setPadding(dp(5), dp(2), dp(5), dp(2))
        duration.background = rounded(0xCC000000.toInt(), dp(4).toFloat())
        duration.visibility = View.GONE
        val durationParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
        durationParams.setMargins(dp(8), dp(8), dp(8), dp(8))
        thumbBox.addView(duration, durationParams)
        // The red line: two parts side by side whose weights are the percent watched and the rest.
        val progress = LinearLayout(context)
        progress.orientation = LinearLayout.HORIZONTAL
        progress.visibility = View.GONE
        val done = View(context)
        done.setBackgroundColor(0xFFFF0000.toInt())
        val rest = View(context)
        progress.addView(done, LinearLayout.LayoutParams(0, MATCH, 0f))
        progress.addView(rest, LinearLayout.LayoutParams(0, MATCH, 0f))
        thumbBox.addView(progress, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.BOTTOM))
        card.addView(thumbBox, LinearLayout.LayoutParams(MATCH, WRAP))

        val info = LinearLayout(context)
        info.orientation = LinearLayout.HORIZONTAL
        info.setPadding(dp(12), dp(12), dp(12), dp(16))
        val avatar = roundPicture()
        info.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))

        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        val title = TextView(context)
        title.textSize = 15f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(palette.text)
        val meta = TextView(context)
        meta.textSize = 12f
        meta.maxLines = 2
        meta.ellipsize = TextUtils.TruncateAt.END
        meta.setTextColor(palette.secondary)
        texts.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        val metaParams = LinearLayout.LayoutParams(MATCH, WRAP)
        metaParams.topMargin = dp(3)
        texts.addView(meta, metaParams)
        val textParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        textParams.marginStart = dp(12)
        info.addView(texts, textParams)

        // The three dots, at the end of the row (the left in Arabic) like YouTube's. The button is bigger than its
        // picture so a finger finds it, and it pulls into the row's padding so the dots sit at the screen's edge.
        val more = ImageView(context)
        more.setImageResource(R.drawable.ic_more_vert_24dp)
        more.imageTintList = ColorStateList.valueOf(palette.text)
        more.contentDescription = context.getString(R.string.subsMenuMore)
        more.scaleType = ImageView.ScaleType.CENTER
        more.isClickable = true
        more.background = rippleBackground()
        val moreParams = LinearLayout.LayoutParams(dp(40), dp(40))
        moreParams.marginStart = dp(4)
        moreParams.marginEnd = -dp(8)
        moreParams.topMargin = -dp(8)
        info.addView(more, moreParams)

        card.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
        return CardHolder(card, thumb, duration, progress, done, rest, avatar, title, meta, more)
    }

    // ---- The posts ----

    /** A count under a post (likes, comments): its small picture and its text. */
    private class CountView(val group: LinearLayout, val label: TextView)

    private class PostHolder(
        root: View,
        val avatar: ImageView,
        val name: TextView,
        val time: TextView,
        val more: ImageView,
        val text: TextView,
        /** What is attached to the post (pictures, a poll, a video): filled again for every post the card shows. */
        val media: LinearLayout,
        val actions: View,
        val likes: CountView,
        val comments: CountView,
    ) : RecyclerView.ViewHolder(root)

    /**
     * A post's card, like YouTube's: the channel's picture with its name and the age of the post (and the three dots),
     * the words (links in the accent colour), what is attached to them, then the likes and the comments. A tap on it
     * opens the post's page; a tap on a link in its words opens that link.
     */
    private fun buildPostItem(): PostHolder {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(12), dp(12), dp(12), dp(8))
        val avatar = roundPicture()
        head.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))

        val names = LinearLayout(context)
        names.orientation = LinearLayout.VERTICAL
        val name = TextView(context)
        name.textSize = 15f
        name.typeface = Typeface.DEFAULT_BOLD
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.setTextColor(palette.text)
        val time = TextView(context)
        time.textSize = 12f
        time.maxLines = 1
        time.setTextColor(palette.secondary)
        names.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        names.addView(time, LinearLayout.LayoutParams(MATCH, WRAP))
        val namesParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        namesParams.marginStart = dp(12)
        head.addView(names, namesParams)

        // The three dots, at the end of the row, as on a video's card.
        val more = ImageView(context)
        more.setImageResource(R.drawable.ic_more_vert_24dp)
        more.imageTintList = ColorStateList.valueOf(palette.text)
        more.contentDescription = context.getString(R.string.subsMenuMore)
        more.scaleType = ImageView.ScaleType.CENTER
        more.isClickable = true
        more.background = rippleBackground()
        val moreParams = LinearLayout.LayoutParams(dp(40), dp(40))
        moreParams.marginStart = dp(4)
        moreParams.marginEnd = -dp(8)
        head.addView(more, moreParams)
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val text = TextView(context)
        text.textSize = 15f
        text.maxLines = POST_MAX_LINES
        text.ellipsize = TextUtils.TruncateAt.END
        text.setTextColor(palette.text)
        text.setLineSpacing(0f, 1.1f)
        text.setPadding(dp(16), dp(4), dp(16), dp(12))
        // The words take the touch themselves to tell a tap on a link from a tap beside it (which opens the post).
        text.setOnTouchListener { view, event -> postTextTouch(view as TextView, event, card) }
        card.addView(text, LinearLayout.LayoutParams(MATCH, WRAP))

        val media = LinearLayout(context)
        media.orientation = LinearLayout.VERTICAL
        card.addView(media, LinearLayout.LayoutParams(MATCH, WRAP))

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.setPadding(dp(16), dp(10), dp(16), dp(12))
        val likes = countView(R.drawable.ic_thumb_up_24dp)
        val comments = countView(R.drawable.ic_comment_24dp)
        actions.addView(likes.group, LinearLayout.LayoutParams(WRAP, WRAP))
        val commentsParams = LinearLayout.LayoutParams(WRAP, WRAP)
        commentsParams.marginStart = dp(28)
        actions.addView(comments.group, commentsParams)
        card.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))

        // A band between one post and the next.
        val band = View(context)
        band.setBackgroundColor(palette.chip)
        card.addView(band, LinearLayout.LayoutParams(MATCH, dp(8)))
        return PostHolder(card, avatar, name, time, more, text, media, actions, likes, comments)
    }

    private fun countView(icon: Int): CountView {
        val group = LinearLayout(context)
        group.orientation = LinearLayout.HORIZONTAL
        group.gravity = Gravity.CENTER_VERTICAL
        val picture = ImageView(context)
        picture.setImageResource(icon)
        picture.imageTintList = ColorStateList.valueOf(palette.text)
        group.addView(picture, LinearLayout.LayoutParams(dp(24), dp(24)))
        val label = TextView(context)
        label.textSize = 14f
        label.setTextColor(palette.text)
        val labelParams = LinearLayout.LayoutParams(WRAP, WRAP)
        labelParams.marginStart = dp(8)
        group.addView(label, labelParams)
        return CountView(group, label)
    }

    private fun bindPost(holder: PostHolder, video: SubsVideo, avatars: Map<String, String>) {
        val post = video.post ?: return
        holder.name.text = video.channelName
        holder.time.text = video.published
        holder.time.visibility = if (video.published.isEmpty()) View.GONE else View.VISIBLE
        val picture = post.avatar.ifEmpty { avatars[video.channelId] ?: avatars[video.channelName] ?: "" }
        SubsImages.load(holder.avatar, picture, null, dp(48))

        holder.text.visibility = if (post.text.isEmpty()) View.GONE else View.VISIBLE
        holder.text.text = linkedText(post)
        holder.media.removeAllViews()
        addPostMedia(holder.media, post)

        holder.likes.label.text = post.likes
        holder.likes.group.visibility = if (post.likes.isEmpty()) View.GONE else View.VISIBLE
        holder.comments.label.text = post.comments
        holder.comments.group.visibility = if (post.comments.isEmpty()) View.GONE else View.VISIBLE
        holder.actions.visibility = if (post.likes.isEmpty() && post.comments.isEmpty()) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { listener.onVideo(video) }
        holder.more.setOnClickListener { listener.onVideoMenu(video, it) }
    }

    /** The words of a post, its links coloured and tappable. */
    private fun linkedText(post: SubsPost): CharSequence {
        if (post.links.isEmpty()) return post.text
        val spannable = SpannableString(post.text)
        for (link in post.links) {
            if (link.start < 0 || link.start >= link.end || link.end > post.text.length) continue
            spannable.setSpan(LinkSpan(link.url), link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private inner class LinkSpan(private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            listener.onLink(url)
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = palette.accent
            ds.isUnderlineText = false
        }
    }

    /** A tap that ends on a link of the words follows the link; a tap anywhere else in them is a tap on the card. */
    private fun postTextTouch(view: TextView, event: MotionEvent, card: View): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val link = linkAt(view, event)
                if (link != null) link.onClick(view) else card.performClick()
                return true
            }
        }
        return false
    }

    /** The link of [view]'s words under the finger, or null. */
    private fun linkAt(view: TextView, event: MotionEvent): ClickableSpan? {
        val words = view.text as? Spanned ?: return null
        val layout = view.layout ?: return null
        val x = event.x - view.totalPaddingLeft + view.scrollX
        val y = event.y - view.totalPaddingTop + view.scrollY
        if (y < 0f || y > layout.height) return null
        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return words.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }

    /** What is attached to a post, in the order YouTube shows it: pictures, a poll, a video. */
    private fun addPostMedia(box: LinearLayout, post: SubsPost) {
        if (post.images.isNotEmpty()) box.addView(postPicture(post.images), LinearLayout.LayoutParams(MATCH, WRAP))
        if (post.choices.isNotEmpty()) addPoll(box, post)
        if (post.videoId.isNotEmpty()) box.addView(attachedVideo(post), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** The picture of a post, as wide as the screen and as high as it is (up to a limit); "+2" when the post has more. */
    private fun postPicture(images: List<String>): View {
        val frame = FrameLayout(context)
        val picture = ImageView(context)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        picture.adjustViewBounds = true
        picture.maxHeight = screenWidth * 5 / 4
        picture.minimumHeight = dp(120)
        picture.setBackgroundColor(palette.placeholder)
        frame.addView(picture, FrameLayout.LayoutParams(MATCH, WRAP))
        SubsImages.load(picture, images[0], null, screenWidth)
        if (images.size > 1) {
            val more = TextView(context)
            more.text = "+" + (images.size - 1)
            more.textSize = 13f
            more.typeface = Typeface.DEFAULT_BOLD
            more.setTextColor(Color.WHITE)
            more.setPadding(dp(8), dp(3), dp(8), dp(3))
            more.background = rounded(0xCC000000.toInt(), dp(6).toFloat())
            val params = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
            params.setMargins(dp(8), dp(8), dp(8), dp(8))
            frame.addView(more, params)
        }
        return frame
    }

    /** A poll: a bordered row for each choice (its picture first, then its words), and the votes under them. */
    private fun addPoll(box: LinearLayout, post: SubsPost) {
        for (choice in post.choices) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val border = GradientDrawable()
            border.setColor(Color.TRANSPARENT)
            border.cornerRadius = dp(12).toFloat()
            border.setStroke(dp(2), palette.outline)
            row.background = border
            row.outlineProvider = roundedCorners
            row.clipToOutline = true
            if (choice.image.isNotEmpty()) {
                val picture = ImageView(context)
                picture.scaleType = ImageView.ScaleType.CENTER_CROP
                picture.setBackgroundColor(palette.placeholder)
                row.addView(picture, LinearLayout.LayoutParams(dp(84), dp(84)))
                SubsImages.load(picture, choice.image, null, dp(168))
            }
            val words = TextView(context)
            words.text = choice.text
            words.textSize = 15f
            words.setTextColor(palette.text)
            words.setPadding(dp(16), dp(12), dp(16), dp(12))
            row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
            val params = LinearLayout.LayoutParams(MATCH, WRAP)
            params.setMargins(dp(16), dp(4), dp(16), dp(4))
            box.addView(row, params)
        }
        if (post.votes.isNotEmpty()) {
            val votes = TextView(context)
            votes.text = post.votes
            votes.textSize = 14f
            votes.setTextColor(palette.secondary)
            votes.setPadding(dp(16), dp(6), dp(16), 0)
            box.addView(votes, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** A video attached to a post: its picture and title; a tap plays it. */
    private fun attachedVideo(post: SubsPost): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        val picture = ImageView(context)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        picture.setBackgroundColor(palette.placeholder)
        // 16:9 at whatever width the post has.
        val frame = RatioFrame(context, 9f / 16f)
        frame.addView(picture, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(frame, LinearLayout.LayoutParams(MATCH, WRAP))
        SubsImages.load(
            picture,
            "https://i.ytimg.com/vi/${post.videoId}/hq720.jpg",
            "https://i.ytimg.com/vi/${post.videoId}/mqdefault.jpg",
            (screenWidth * 0.8f).toInt(),
        )
        if (post.videoTitle.isNotEmpty()) {
            val title = TextView(context)
            title.text = post.videoTitle
            title.textSize = 14f
            title.typeface = Typeface.DEFAULT_BOLD
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            title.setTextColor(palette.text)
            title.setPadding(dp(16), dp(8), dp(16), dp(4))
            box.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        box.setOnClickListener { listener.onLink("${SubsRepository.MOBILE_SITE}/watch?v=${post.videoId}") }
        return box
    }

    /**
     * A tall card with rounded corners: the picture of a Short with its title and views over the bottom. With a [height]
     * the card has that size (a Short in a row of Shorts); with 0 it is as tall as its width asks for, 9:16 (a tile of
     * the Shorts chip: its width is the grid's, and follows the screen when it is turned).
     */
    private fun buildShortCard(width: Int, height: Int): ShortHolder {
        val fixed = height > 0
        val root = FrameLayout(context)
        root.layoutParams = RecyclerView.LayoutParams(width, if (fixed) height else WRAP)
        root.setPadding(dp(4), dp(4), dp(4), dp(4))

        val card: FrameLayout = if (fixed) FrameLayout(context) else RatioFrame(context, 16f / 9f)
        card.outlineProvider = roundedCorners
        card.clipToOutline = true
        card.setBackgroundColor(palette.placeholder)
        val thumb = ImageView(context)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))

        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(8), dp(28), dp(8), dp(8))
        texts.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xCC000000.toInt()))
        val title = TextView(context)
        title.textSize = 13f
        title.typeface = Typeface.DEFAULT_BOLD
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(Color.WHITE)
        val views = TextView(context)
        views.textSize = 12f
        views.maxLines = 1
        views.setTextColor(0xE6FFFFFF.toInt())
        texts.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        texts.addView(views, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(texts, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // A light shade at the top so the white dots show over a bright picture too.
        val shade = View(context)
        shade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0x00000000))
        card.addView(shade, FrameLayout.LayoutParams(MATCH, dp(48), Gravity.TOP))

        // The three dots, at the top corner of the card on the end side (the left in Arabic) like YouTube's Shorts.
        val more = ImageView(context)
        more.setImageResource(R.drawable.ic_more_vert_24dp)
        more.imageTintList = ColorStateList.valueOf(Color.WHITE)
        more.contentDescription = context.getString(R.string.subsMenuMore)
        more.scaleType = ImageView.ScaleType.CENTER
        more.isClickable = true
        more.background = rippleBackground()
        card.addView(more, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))

        root.addView(card, FrameLayout.LayoutParams(MATCH, if (fixed) MATCH else WRAP))
        return ShortHolder(root, thumb, title, views, more)
    }

    /** The row of Shorts: a title with Shorts' red mark, then the Shorts side by side (scrolling sideways). */
    private fun buildShelfItem(): ShelfHolder {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        box.setPadding(0, dp(8), 0, dp(12))

        val titleRow = LinearLayout(context)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.setPadding(dp(12), dp(4), dp(12), dp(8))
        val mark = TextView(context)
        mark.text = "▶"
        mark.textSize = 9f
        mark.setTextColor(Color.WHITE)
        mark.gravity = Gravity.CENTER
        mark.background = rounded(0xFFFF0000.toInt(), dp(5).toFloat())
        titleRow.addView(mark, LinearLayout.LayoutParams(dp(26), dp(18)))
        val name = TextView(context)
        name.text = context.getString(R.string.subsShortsTitle)
        name.textSize = 20f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(palette.text)
        val nameParams = LinearLayout.LayoutParams(WRAP, WRAP)
        nameParams.marginStart = dp(8)
        titleRow.addView(name, nameParams)
        box.addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = RecyclerView(context)
        val adapter = ShelfAdapter()
        row.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        row.adapter = adapter
        row.overScrollMode = View.OVER_SCROLL_NEVER
        row.clipToPadding = false
        row.setPadding(dp(8), 0, dp(8), 0)
        box.addView(row, LinearLayout.LayoutParams(MATCH, dp(264)))
        return ShelfHolder(box, adapter)
    }

    /** "Channel • 38K views • 5 hours ago": YouTube's own texts, or made from the numbers of an RSS feed. */
    private fun metaLine(video: SubsVideo): String {
        val parts = ArrayList<String>(3)
        if (video.channelName.isNotEmpty()) parts.add(video.channelName)
        if (video.views.isNotEmpty()) {
            parts.add(video.views)
        } else if (video.viewCount >= 0) {
            parts.add(context.getString(R.string.subsViews, compact.format(video.viewCount)))
        }
        if (video.published.isNotEmpty()) {
            parts.add(video.published)
        } else if (video.publishedMs > 0) {
            parts.add(
                DateUtils.getRelativeTimeSpanString(
                    video.publishedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
            )
        }
        return parts.joinToString(" • ")
    }
}

/**
 * A frame whose height follows its width ([ratio] is height over width): a 16:9 picture is 0.5625 of its width high at
 * any width, so it stays right when the phone is turned (a height worked out once, in pixels, did not).
 */
private class RatioFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((width * ratio).toInt(), View.MeasureSpec.EXACTLY),
        )
    }
}
