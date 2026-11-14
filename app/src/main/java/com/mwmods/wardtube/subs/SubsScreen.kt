// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.CompactDecimalFormat
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.mwmods.wardtube.R
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val ROW_CARD = 0
private const val ROW_SHELF = 1
private const val ROW_TILE = 2
private const val ROW_POST = 3

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

/** What the screen asks of whoever owns it. */
interface SubsListener {
    /** A channel was chosen in the strip (null: none, the videos of every channel again). */
    fun onChannel(channel: SubsChannel?)

    fun onVideo(video: SubsVideo)

    /** The three dots of a video's card were tapped: [anchor] is the button, the menu opens beside it. */
    fun onVideoMenu(video: SubsVideo, anchor: View)

    /** "Go to channel": the channel's own page. */
    fun onOpenChannel(channel: SubsChannel)

    /** The "All" button beside the strip: YouTube's page that lists every subscription. */
    fun onOpenAllChannels()

    /** A link inside a post's text, or the video attached to a post, was tapped. */
    fun onLink(url: String)

    /** A chip was chosen (one of [SubsFilter]). */
    fun onFilter(filter: Int)

    /** The list was pulled down to refresh. */
    fun onRefresh()

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
 * channel) with an "All" button fixed at the end of its row (it opens the list of every subscription), and the videos
 * below as big cards.
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
    private val screenWidth = resources.displayMetrics.widthPixels
    private val thumbHeight = (screenWidth * 9f / 16f).toInt()
    private val compact: CompactDecimalFormat =
        CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)

    private var channels: List<SubsChannel> = emptyList()
    private var newIds: Set<String> = emptySet()
    private var selectedChannel: SubsChannel? = null
    private var filter = SubsFilter.ALL
    private var loading = false
    private var diagnostic = ""

    private val strip = RecyclerView(context)

    /** True from the moment the screen opens until the person touches the strip: it is held at its first channel. */
    private var stripPinned = false
    private var pinCorrections = 0
    private val stripAdapter = StripAdapter()
    private val allButton = TextView(context)
    private val goToChannel = TextView(context)
    private val chips = RecyclerView(context)
    private val chipAdapter = ChipAdapter()
    private val chipLabels = intArrayOf(
        R.string.subsChipAll, R.string.subsChipToday, R.string.subsChipVideos, R.string.subsChipShorts, R.string.subsChipLive,
        R.string.subsChipPosts,
    )
    private val refresh = SwipeRefreshLayout(context)
    private val list = RecyclerView(context)
    private val listAdapter = VideoAdapter()
    private val spinner = ProgressBar(context)
    private val emptyText = TextView(context)

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

    /** A Short's tile in the two columns of the Shorts chip: 9:16 picture plus the padding around it. */
    private val tileHeight = ((screenWidth / 2 - dp(8)) * 16f / 9f).toInt() + dp(8)

    init {
        // Everything touched here belongs to this screen: nothing may fall through to YouTube's page underneath.
        isClickable = true
        setBackgroundColor(palette.background)

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

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
        allButton.contentDescription = allButton.text
        allButton.textSize = 14f
        allButton.typeface = Typeface.DEFAULT_BOLD
        allButton.setTextColor(palette.accent)
        allButton.gravity = Gravity.CENTER
        allButton.setOnClickListener { listener.onOpenAllChannels() }
        // As high as a channel's picture with its ring (56dp + 3dp on each side) and level with it (the strip's top padding).
        val allParams = FrameLayout.LayoutParams(MATCH, dp(62))
        allParams.topMargin = dp(8)
        allHolder.addView(allButton, allParams)
        stripRow.addView(allHolder, FrameLayout.LayoutParams(dp(ALL_WIDTH_DP), MATCH, Gravity.END))
        column.addView(stripRow, LinearLayout.LayoutParams(MATCH, WRAP))

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
        column.addView(goToChannel, goParams)

        chips.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        chips.adapter = chipAdapter
        chips.overScrollMode = View.OVER_SCROLL_NEVER
        chips.clipToPadding = false
        chips.setPadding(dp(8), dp(2), dp(8), dp(8))
        column.addView(chips, LinearLayout.LayoutParams(MATCH, WRAP))

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = listAdapter
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && manager.findLastVisibleItemPosition() >= listAdapter.itemCount - prefetchAhead(manager)) listener.onEnd()
            }
        })
        refresh.addView(list, ViewGroup.LayoutParams(MATCH, MATCH))
        refresh.setColorSchemeColors(palette.accent)
        refresh.setProgressBackgroundColorSchemeColor(palette.chip)
        refresh.setOnRefreshListener { listener.onRefresh() }

        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(palette.accent)
        spinner.visibility = View.GONE
        emptyText.textSize = 15f
        emptyText.setTextColor(palette.secondary)
        emptyText.gravity = Gravity.CENTER
        emptyText.setPadding(dp(24), 0, dp(24), 0)
        emptyText.visibility = View.GONE

        val body = FrameLayout(context)
        body.addView(refresh, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(spinner, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        body.addView(emptyText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        column.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        addView(column, LayoutParams(MATCH, MATCH))
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

    /** The chosen chip. The Shorts chip lists the Shorts in two columns, the others in one. */
    fun setFilter(value: Int) {
        if (value == filter) return
        filter = value
        list.layoutManager = if (value == SubsFilter.SHORTS) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        chipAdapter.notifyDataSetChanged()
    }

    /** [videos] are already filtered by the chip; this puts them in rows (videos, rows of Shorts, or Shorts tiles). */
    fun setVideos(videos: List<SubsVideo>, avatars: Map<String, String>, toTop: Boolean) {
        listAdapter.submit(buildRows(videos), avatars)
        if (toTop) list.scrollToPosition(0)
        updateEmptyState()
    }

    /** Videos are still being fetched: a spinner shows while there is nothing to list yet. */
    fun setLoading(on: Boolean) {
        loading = on
        updateEmptyState()
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
        spinner.visibility = if (empty && loading) View.VISIBLE else View.GONE
        emptyText.visibility = if (empty && !loading) View.VISIBLE else View.GONE
        val base = context.getString(
            when (filter) {
                SubsFilter.LIVE -> R.string.subsEmptyLive
                SubsFilter.POSTS -> R.string.subsEmptyPosts
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

    private sealed class Row {
        class Card(val video: SubsVideo) : Row()
        class Shelf(val shorts: List<SubsVideo>) : Row()
        class Tile(val video: SubsVideo) : Row()
        class Post(val video: SubsVideo) : Row()
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
        override fun getItemCount(): Int = chipLabels.size

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
            val chosen = position == filter
            holder.label.text = context.getString(chipLabels[position])
            holder.label.setTextColor(if (chosen) palette.background else palette.text)
            holder.label.background = rounded(if (chosen) palette.text else palette.chip, dp(8).toFloat())
            holder.itemView.setOnClickListener { chooseFilter(position) }
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
        holder.label.text = channel.name
        holder.label.setTextColor(if (chosen) palette.text else palette.secondary)
        holder.ring?.background = if (chosen) ring() else null
        holder.dot?.visibility = if (!chosen && channel.id in newIds) View.VISIBLE else View.GONE
        holder.avatar?.let { SubsImages.load(it, channel.avatar, null, dp(72)) }
        // Tapping the chosen channel again goes back to every channel.
        holder.itemView.setOnClickListener { choose(if (chosen) null else channel) }
    }

    // ---- The videos, the Shorts ----

    private class CardHolder(
        root: View,
        val thumb: ImageView,
        val duration: TextView,
        val progress: View,
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

    private inner class VideoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        private var avatars: Map<String, String> = emptyMap()

        fun submit(newRows: List<Row>, avatarOf: Map<String, String>) {
            rows = newRows
            avatars = avatarOf
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            when (rows[position]) {
                is Row.Card -> ROW_CARD
                is Row.Shelf -> ROW_SHELF
                is Row.Tile -> ROW_TILE
                is Row.Post -> ROW_POST
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                ROW_SHELF -> buildShelfItem()
                ROW_TILE -> buildShortCard(MATCH, tileHeight)
                ROW_POST -> buildPostItem()
                else -> buildVideoItem()
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Card -> bindCard(holder as CardHolder, row.video, avatars)
                is Row.Shelf -> (holder as ShelfHolder).shorts.submit(row.shorts)
                is Row.Tile -> bindShort(holder as ShortHolder, row.video)
                is Row.Post -> bindPost(holder as PostHolder, row.video, avatars)
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
        // The red line under the picture: how much the account has watched.
        val line = holder.progress.layoutParams
        line.width = (screenWidth * video.watched / 100f).toInt()
        holder.progress.layoutParams = line
        holder.progress.visibility = if (video.watched > 0) View.VISIBLE else View.GONE
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

        val thumbBox = FrameLayout(context)
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
        val progress = View(context)
        progress.setBackgroundColor(0xFFFF0000.toInt())
        progress.visibility = View.GONE
        thumbBox.addView(progress, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
        card.addView(thumbBox, LinearLayout.LayoutParams(MATCH, thumbHeight))

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
        return CardHolder(card, thumb, duration, progress, avatar, title, meta, more)
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
        picture.minimumHeight = thumbHeight / 2
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
        box.addView(picture, LinearLayout.LayoutParams(MATCH, thumbHeight))
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

    /** A tall card with rounded corners: the picture of a Short with its title and views over the bottom. */
    private fun buildShortCard(width: Int, height: Int): ShortHolder {
        val root = FrameLayout(context)
        root.layoutParams = RecyclerView.LayoutParams(width, height)
        root.setPadding(dp(4), dp(4), dp(4), dp(4))

        val card = FrameLayout(context)
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

        root.addView(card, FrameLayout.LayoutParams(MATCH, MATCH))
        return ShortHolder(root, thumb, title, views, more)
    }

    /** The row of Shorts: a title with Shorts' red mark, then the Shorts side by side (scrolling sideways). */
    private fun buildShelfItem(): ShelfHolder {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        box.setPadding(0, dp(8), 0, dp(12))

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(12), dp(4), dp(12), dp(8))
        val mark = TextView(context)
        mark.text = "\u25B6"
        mark.textSize = 9f
        mark.setTextColor(Color.WHITE)
        mark.gravity = Gravity.CENTER
        mark.background = rounded(0xFFFF0000.toInt(), dp(5).toFloat())
        header.addView(mark, LinearLayout.LayoutParams(dp(26), dp(18)))
        val name = TextView(context)
        name.text = context.getString(R.string.subsShortsTitle)
        name.textSize = 20f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(palette.text)
        val nameParams = LinearLayout.LayoutParams(WRAP, WRAP)
        nameParams.marginStart = dp(8)
        header.addView(name, nameParams)
        box.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

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
        return parts.joinToString(" \u2022 ")
    }
}
