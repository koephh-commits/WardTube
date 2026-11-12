// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mwmods.wardtube.R
import com.mwmods.wardtube.util.UrlUtils
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Puts the app's own Subscriptions screen ([SubsScreen]) over YouTube's page whenever the browsing WebView is on the
 * Subscriptions tab, and takes it away again on any other page.
 *
 * The WebView keeps running underneath, so YouTube's own header (search) and bottom tabs stay where they are: the screen
 * covers only the space between them, measured from the page. If anything is missing (nobody signed in, the list of
 * channels could not be read) the screen is simply not shown, and the person sees YouTube's page as before.
 *
 * Data: [SubsRepository] (the channels and the feed from YouTube's own pages with the WebView's cookies; a channel's RSS
 * feed when those give nothing), kept on the phone so the screen opens at once and refreshes in the background.
 */
class SubsController(
    private val activity: AppCompatActivity,
    /** The FrameLayout that holds the browsing WebView; the screen goes right above the WebView, below the player. */
    private val area: FrameLayout,
    private val browse: () -> WebView,
    private val night: Boolean,
    private val enabled: () -> Boolean,
    /** Opens a link the way the app does: a video in the player, any other page in the browsing WebView. */
    private val openUrl: (String) -> Unit,
    /** The finger drags over the screen ([down] = the list scrolls down): the owner hides or shows its bottom bar. */
    private val onScrollDirection: (down: Boolean) -> Unit = {},
) : SubsListener {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "wardtube-subs").apply { isDaemon = true }
    }
    private val state = activity.getSharedPreferences("subs_state", Context.MODE_PRIVATE)
    private val lang: String = Locale.getDefault().language.ifEmpty { "en" }

    private var screen: SubsScreen? = null
    private var released = false
    private var onSubsPage = false

    /** The address of the page the browsing WebView last reported (it may still be loading). */
    private var pageUrl: String? = null

    /**
     * True from the tap on "Go to channel" until the channel's page has painted its first content ([onPageShown]), or
     * [HOLD_MAX_MS] have passed. The WebView keeps drawing the page it had (YouTube's own Subscriptions list) until the
     * new page paints, so the screen must not be taken away before that: it would uncover that list for the whole load.
     */
    private var holding = false
    private val holdTimeout = Runnable { endHold() }

    private var account = ""
    private var snapshotRead = false
    private var channels: List<SubsChannel> = emptyList()
    private var avatars: Map<String, String> = emptyMap()
    private var strip: List<SubsChannel> = emptyList()

    /**
     * True when [strip] was put in order by a feed of videos. From then on its order is kept while the Subscriptions
     * screen is on view (see [orderStrip]); before that the channels are in YouTube's own order and may move once.
     */
    private var stripFromFeed = false

    /** Set by a pull-to-refresh: the order of the feed that comes back is applied at once (the person asked for it). */
    private var reorderOnFeed = false
    private var selected: SubsChannel? = null
    private var filter = SubsFilter.ALL

    /** Videos the person opened or saw (from the app's list or by tapping their channel): they carry no blue dot. */
    private val seenIds = LinkedHashSet<String>()

    /** How many more pages may be fetched on their own to fill a short filtered list (reset by every chip tap). */
    private var autoMoreLeft = 0

    /** The feed of every channel, in the order YouTube gives it; [cursor] asks for its next page. */
    private var feed: List<SubsVideo> = emptyList()
    private var cursor: SubsPage? = null

    /** True while the feed comes from the copy kept on the phone: it has no way to ask for older videos until it is read again. */
    private var cursorMissing = false

    /** How many pages in a row came with nothing new (the next one is still tried a few times, then the feed ends). */
    private var emptyPages = 0

    /** How many pages in a row came with no new Short (the mixed feed's Shorts stop being asked for after [MAX_DRY_PAGES]). */
    private var shortsDry = 0

    /**
     * The first page of the feed: it holds the filter chips of YouTube's Subscriptions page, and the key and context
     * that every later request needs (the pages after it come with neither).
     */
    private var feedHead: SubsPage? = null

    /**
     * The Shorts chip, the way YouTube does it: its own list of the Shorts of every subscription ([shortsFeed]), with
     * its own continuation ([shortsCursor]) that never runs out until YouTube has no older Short to give. The feed above
     * holds Shorts only in a few shelves, so listing from it ends soon.
     *  - [shortsDirect] true: the chip answered with Shorts, the Shorts chip lists [shortsFeed];
     *  - false: the page has no usable Shorts chip, the Shorts chip lists the Shorts of the feed (the old way);
     *  - null: not known yet (the answer is being read, or it could not be read for a moment, offline for instance).
     */
    private var shortsDirect: Boolean? = null
    private var shortsFeed: List<SubsVideo> = emptyList()
    private var shortsCursor: SubsPage? = null
    private var shortsFeedAt = 0L
    private var shortsTryAt = 0L
    private var shortsLoading = false
    private var shortsMoreLoading = false

    /** True when [shortsFeed] is from before the feed's last refresh (or the chip was tapped after a while): it is read again. */
    private var shortsStale = false

    /** How many pages of the Shorts chip in a row came with nothing new (the next one is still tried a few times). */
    private var shortsEmpty = 0

    /** Changes with every new first page of the Shorts chip (and every new account): answers of an older one are dropped. */
    private var shortsGen = 0

    /** The videos of one channel, read when it is chosen; its Shorts and live streams when those chips are chosen. */
    private val channelVideos = HashMap<String, List<SubsVideo>>()
    private val channelShorts = HashMap<String, List<SubsVideo>>()
    private val channelStreams = HashMap<String, List<SubsVideo>>()
    private val channelLoadedAt = HashMap<String, Long>() // key: "<channel id>|<tab>"

    private var channelsAt = 0L
    private var feedAt = 0L
    private var lastTryAt = 0L
    private var channelsLoading = false
    private var feedLoading = false
    private var moreLoading = false
    private var channelBusy: String? = null
    private var generation = 0

    private var topMargin = dp(56)
    private var bottomMargin = dp(64)

    init {
        state.getString(KEY_SEEN_IDS, "").orEmpty().split(',').filter { it.length == 11 }.forEach { seenIds.add(it) }
    }

    // ---- Following the page ----

    /** Called whenever the browsing WebView changes page (also for pages inside YouTube that load no new document). */
    fun onPage(url: String?) {
        if (released) return
        pageUrl = url
        if (!enabled() || !isSubscriptionsPage(url)) {
            // Leaving for a channel's page: the screen stays over the old page until the new one is on view.
            if (holding && enabled() && screen?.visibility == View.VISIBLE) return
            onSubsPage = false
            screen?.visibility = View.GONE
            return
        }
        onSubsPage = true
        if (screen?.visibility == View.VISIBLE) {
            measure()
            return
        }
        ensureData()
    }

    /**
     * The browsing WebView painted the first content of a new page: nothing of the page before it is drawn any more, so
     * the screen can go if that page is not the Subscriptions one.
     */
    fun onPageShown(url: String?) {
        if (released || !holding || isSubscriptionsPage(url)) return
        endHold()
    }

    private fun startHold() {
        holding = true
        screen?.setRefreshing(true) // the pull-to-refresh spinner tells the person the channel is loading
        main.removeCallbacks(holdTimeout)
        main.postDelayed(holdTimeout, HOLD_MAX_MS)
    }

    private fun endHold() {
        if (!holding) return
        holding = false
        main.removeCallbacks(holdTimeout)
        screen?.setRefreshing(false)
        onPage(pageUrl) // takes the screen away unless the WebView is back on the Subscriptions page
    }

    private fun isSubscriptionsPage(url: String?): Boolean {
        if (url == null || !UrlUtils.isYouTubeUrl(url)) return false
        val uri = Uri.parse(url)
        // A fragment is YouTube's own search box (m.youtube.com/...#searching): it must stay usable.
        if (uri.fragment != null) return false
        return uri.path?.trimEnd('/') == "/feed/subscriptions"
    }

    private fun cookie(): String = CookieManager.getInstance().getCookie(SubsRepository.SITE).orEmpty()

    private fun ensureData() {
        val cookie = cookie()
        val key = SubsRepository.accountKey(cookie)
        if (key.isEmpty()) return // nobody signed in: YouTube's page shows its own sign-in prompt
        if (!snapshotRead || key != account) readSnapshot(key)
        val now = System.currentTimeMillis()
        if (channels.isNotEmpty()) {
            show()
            val stale = if (feed.isEmpty() || cursorMissing) now - lastTryAt > RETRY_MS else now - feedAt > FEED_STALE_MS
            if (!feedLoading && stale) refreshFeed()
            if (!channelsLoading && now - channelsAt > CHANNELS_STALE_MS) refreshChannels(cookie)
        } else if (!channelsLoading) {
            refreshChannels(cookie)
        }
    }

    private fun readSnapshot(key: String) {
        if (account.isNotEmpty() && account != key) {
            // Another account signed in: what the previous one saw means nothing here.
            seenIds.clear()
            state.edit().remove(KEY_SEEN_IDS).apply()
        }
        snapshotRead = true
        account = key
        setChannelList(emptyList())
        feed = emptyList()
        cursor = null
        cursorMissing = false
        resetShorts()
        channelVideos.clear()
        channelShorts.clear()
        channelStreams.clear()
        channelLoadedAt.clear()
        channelsAt = 0L
        feedAt = 0L
        selected = null
        val app = activity.applicationContext
        val snapshot = SubsRepository.load(app) ?: return
        if (snapshot.account != key) {
            SubsRepository.clear(app) // the previous account's list must not show for this one
            return
        }
        setChannelList(snapshot.channels)
        feed = snapshot.videos
        cursorMissing = feed.isNotEmpty()
        channelsAt = snapshot.channelsAt
        feedAt = snapshot.feedAt
        orderStrip(force = true)
    }

    private fun setChannelList(list: List<SubsChannel>) {
        channels = list
        avatars = list.associate { it.id to it.avatar } + list.associate { it.name to it.avatar }
        selected = selected?.let { chosen -> list.firstOrNull { it.id == chosen.id } }
        orderStrip()
    }

    // ---- Showing ----

    private fun create(): SubsScreen {
        val view = SubsScreen(activity, SubsPalette(night), this)
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        params.topMargin = topMargin
        params.bottomMargin = bottomMargin
        view.visibility = View.GONE
        // Right above the WebView (index 0) and below the player and the mini player's controls.
        area.addView(view, minOf(1, area.childCount), params)
        screen = view
        return view
    }

    private fun show() {
        if (!onSubsPage || channels.isEmpty()) return
        val view = screen ?: create()
        // Coming back to the screen: the newest order, and the strip of channels starts from its first channel again.
        val opening = view.visibility != View.VISIBLE
        if (opening) {
            reorderOnFeed = false
            orderStrip(force = true)
        }
        view.setChannels(strip, newIds())
        view.setSelected(selected)
        if (opening && selected == null) view.scrollStripToStart()
        view.setFilter(filter)
        applyVideos(view, toTop = false)
        view.setLoading(feedLoading || channelBusy != null)
        view.visibility = View.VISIBLE
        measure()
    }

    private fun applyVideos(view: SubsScreen, toTop: Boolean) {
        ensureShorts() // before the list is built: it decides what the Shorts chip lists
        val videos = visible()
        view.setVideos(videos, avatars, toTop)
        view.setDiagnostic(if (videos.isEmpty() && filter == SubsFilter.ALL) SubsRepository.diag else "")
        fillFiltered(videos.size)
        // The list never ends on its own: if its end is already in view (a short page, or a page that finished
        // loading while the person sat at the bottom) no scroll comes to ask for more, so the screen asks. The Shorts
        // chip does too (its own list goes on for as long as YouTube has Shorts; the feed's Shorts are few per page, so
        // their end is often in view, until pages keep coming with no Short in them, [shortsDry]).
        if (selected == null && (filter == SubsFilter.ALL || filter == SubsFilter.VIDEOS || shortsChipReadsOn())) view.checkEnd()
    }

    /** The Shorts chip is chosen and there is still more for it to read by itself (see [shortsFromChip]). */
    private fun shortsChipReadsOn(): Boolean =
        filter == SubsFilter.SHORTS && (shortsFromChip() || shortsDry < MAX_DRY_PAGES)

    /** True when the Shorts chip, with no channel chosen, lists YouTube's own list of Shorts ([shortsFeed]). */
    private fun shortsFromChip(): Boolean = selected == null && filter == SubsFilter.SHORTS && shortsDirect == true

    /**
     * True when the feed has all it may hold for the chosen chip: [MAX_FEED] videos, or, for the Shorts chip when it
     * lists the feed's own Shorts (a Short is a small share of the feed, so 200 videos hold only a few of them),
     * [MAX_SHORTS] Shorts.
     */
    private fun feedFull(): Boolean =
        if (filter == SubsFilter.SHORTS) feed.count { it.short } >= MAX_SHORTS else feed.size >= MAX_FEED

    /** What the chosen chip lists, before it is cut by the chip: the feed, or the chosen channel's own tab. */
    private fun baseList(): List<SubsVideo> {
        val chosen = selected
        if (chosen == null) {
            if (shortsFromChip()) return shortsFeed
            // The Shorts chip is asking YouTube for its own list for the first time: nothing of the feed shows meanwhile
            // (the list would be swapped a moment later), the spinner does.
            if (filter == SubsFilter.SHORTS && shortsLoading && shortsDirect == null) return emptyList()
            return feed
        }
        return when (filter) {
            SubsFilter.SHORTS -> channelShorts[chosen.id].orEmpty()
            SubsFilter.LIVE -> channelStreams[chosen.id].orEmpty()
            else -> channelVideos[chosen.id].orEmpty()
        }
    }

    /**
     * The videos the chosen chip shows, the newest first (older pages of the feed go below). "All content" keeps the
     * Shorts: the screen puts them in rows between videos.
     */
    private fun visible(): List<SubsVideo> {
        val base = baseList()
        val now = System.currentTimeMillis()
        return when (filter) {
            SubsFilter.TODAY -> SubsAge.newestFirst(base.filter { !it.short && SubsAge.isToday(it, now) }, now)
            SubsFilter.VIDEOS -> SubsAge.newestFirst(base.filter { !it.short && it.live == 0 }, now)
            SubsFilter.SHORTS -> base.filter { it.short }
            SubsFilter.LIVE ->
                SubsAge.newestFirst(base.filter { !it.short && it.live != 0 }, now).sortedBy { liveGroup(it) }
            else -> SubsAge.newestFirst(base.filter { !it.short }, now) + base.filter { it.short }
        }
    }

    /** In the Live chip: streams on now first, then scheduled ones, then past ones (the sort keeps the order inside each). */
    private fun liveGroup(video: SubsVideo): Int =
        when (video.live) {
            1 -> 0
            2 -> 1
            else -> 2
        }

    /**
     * True once the oldest video loaded so far is a day old or more: the feed goes from the newest to the oldest, so
     * no later page can hold anything of "today".
     */
    private fun passedToday(): Boolean {
        val now = System.currentTimeMillis()
        val oldest = feed.lastOrNull { !it.short && SubsAge.ageHours(it, now) != null } ?: return false
        return SubsAge.isOlderThanDay(oldest, now)
    }

    /** The tab of a channel's page that holds what the chosen chip lists. */
    private fun tabFor(chip: Int): String =
        when (chip) {
            SubsFilter.SHORTS -> "shorts"
            SubsFilter.LIVE -> "streams"
            else -> "videos"
        }

    private fun resetShorts() {
        shortsGen++
        feedHead = null
        shortsDirect = null
        shortsFeed = emptyList()
        shortsCursor = null
        shortsFeedAt = 0L
        shortsTryAt = 0L
        shortsLoading = false
        shortsMoreLoading = false
        shortsStale = false
        shortsEmpty = 0
    }

    /** The chips that may be the Shorts chip, the likeliest first: the one named "Shorts", then the one in its usual place. */
    private fun shortsChipsToTry(chips: List<SubsChip>): List<SubsChip> {
        val named = chips.filter { it.token.isNotEmpty() && isShortsLabel(it.text) }
        // All, Today, Videos, Shorts, Live...: the fourth, in a bar long enough to be the Subscriptions page's.
        val usual = chips.getOrNull(USUAL_SHORTS_CHIP)?.takeIf { it.token.isNotEmpty() && chips.size > USUAL_SHORTS_CHIP + 1 }
        return (named + listOfNotNull(usual)).distinctBy { it.token }.take(2)
    }

    private fun isShortsLabel(text: String): Boolean {
        val label = text.lowercase(Locale.ROOT)
        return label.contains("short") || label.contains("شورت") || label.contains("قصير")
    }

    /**
     * The Shorts chip is chosen (no channel): reads YouTube's own list of Shorts for it, when the page gave the chip and
     * the list is missing or old. A chip's answer counts only if it is (nearly) all Shorts ([SubsRepository.mostlyShorts]),
     * so a wrong guess of which chip it is can never show another list. When no chip gives Shorts the chip lists the
     * feed's own Shorts, as before. A failed read (offline, an error) is tried again after [RETRY_MS], or at the next
     * tap on the chip.
     */
    private fun ensureShorts() {
        if (selected != null || filter != SubsFilter.SHORTS || shortsLoading || shortsDirect == false || feedLoading) return
        val head = feedHead ?: return
        if (shortsDirect == true && !shortsStale && shortsFeed.isNotEmpty()) return
        val now = System.currentTimeMillis()
        // After a failed read the next try waits; a list that was read fine and is old now (a pull-to-refresh) goes at once.
        if (shortsDirect != true && shortsTryAt != 0L && now - shortsTryAt < RETRY_MS) return
        val chips = shortsChipsToTry(head.chips)
        if (chips.isEmpty()) return // the page has no chip to ask: the feed's own Shorts are listed
        shortsLoading = true
        shortsStale = false
        shortsTryAt = now
        val gen = ++shortsGen
        val cookie = cookie()
        screen?.setLoading(true)
        pool.execute {
            var found: SubsPage? = null
            var unsure = false
            for (chip in chips) {
                try {
                    val page = SubsRepository.fetchChip(head, chip, cookie)
                    if (SubsRepository.mostlyShorts(page.videos)) {
                        found = page
                        break
                    }
                } catch (e: Exception) {
                    unsure = true // offline, or an error answer: not a proof that the chip is not the Shorts one
                    break
                }
            }
            val result = found
            val failed = unsure
            main.post { onShortsHead(gen, result, failed) }
        }
    }

    private fun onShortsHead(gen: Int, page: SubsPage?, unsure: Boolean) {
        if (released || gen != shortsGen) return
        shortsLoading = false
        if (page != null) {
            val seen = HashSet<String>()
            shortsFeed = page.videos.filter { it.short && seen.add(it.id) }
            shortsCursor = page
            shortsDirect = true
            shortsFeedAt = System.currentTimeMillis()
            shortsEmpty = 0
        } else if (!unsure) {
            shortsDirect = false // every chip answered and none of them listed Shorts: the feed's own Shorts are listed
        }
        val view = screen ?: return
        view.setLoading(feedLoading || channelBusy != null)
        if (onSubsPage) applyVideos(view, toTop = page != null)
    }

    /** The next page of the Shorts chip's own list (older Shorts), until YouTube has no more. */
    private fun moreShorts() {
        val page = shortsCursor ?: return
        if (shortsMoreLoading || shortsLoading || page.token == null || shortsFeed.size >= MAX_SHORTS_FEED) return
        shortsMoreLoading = true
        val gen = shortsGen
        val cookie = cookie()
        pool.execute {
            var next: SubsPage? = null
            try {
                next = SubsRepository.fetchMore(page, cookie)
            } catch (e: Exception) {
                // Tried again at the next scroll.
            }
            val result = next
            main.post {
                shortsMoreLoading = false
                if (released || gen != shortsGen || result == null) return@post
                val known = shortsFeed.mapTo(HashSet()) { it.id }
                val fresh = result.videos.filter { it.short && it.id !in known }
                if (fresh.isEmpty()) {
                    // Nothing new on this page: the end, unless it points to yet another page (tried a few times).
                    if (result.token != null && result.token != page.token && shortsEmpty < MAX_SHORTS_EMPTY_PAGES) {
                        shortsEmpty++
                        shortsCursor = result
                        moreShorts()
                    } else {
                        shortsCursor = null
                    }
                    return@post
                }
                shortsEmpty = 0
                shortsFeed = shortsFeed + fresh
                shortsCursor = result
                val view = screen
                if (view != null && onSubsPage && shortsFromChip()) applyVideos(view, toTop = false)
            }
        }
    }

    /** A chip that matches only a few videos of the feed: the next pages are fetched (a few times) to fill the list. */
    private fun fillFiltered(count: Int) {
        // The Shorts chip's own list fills itself: the screen asks for its next page while its end is in view.
        if (shortsFromChip()) return
        if (selected != null || filter == SubsFilter.ALL || count >= MIN_FILTERED || autoMoreLeft <= 0) return
        if (filter == SubsFilter.TODAY && passedToday()) return
        val page = cursor ?: return
        if (page.token == null || moreLoading || feedLoading) return
        autoMoreLeft--
        onEnd()
    }

    private fun belongs(video: SubsVideo, channel: SubsChannel): Boolean =
        video.channelId == channel.id || (video.channelId.isEmpty() && video.channelName == channel.name)

    /** The channels, the ones with the newest video in the feed first (the order of the app's own strip). */
    private fun ordered(): List<SubsChannel> {
        if (feed.isEmpty()) return channels
        val rank = HashMap<String, Int>()
        for ((index, video) in feed.withIndex()) {
            if (video.short) continue
            val channel = channels.firstOrNull { belongs(video, it) } ?: continue
            if (channel.id !in rank) rank[channel.id] = index
        }
        return channels.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    /**
     * Sets [strip]. While the screen is on view and its strip was already ordered by a feed, the order the person is
     * looking at stays: a feed (or a list of channels) that arrives a moment after the screen opened must not shuffle
     * the pictures under their finger. Channels that are gone leave, new ones join at the end, and the others take the
     * newest data. The newest-video-first order is applied when the screen is opened again, after a pull-to-refresh
     * ([force]), and the first time a feed is read (nothing was in a meaningful order before it).
     *
     * @return true when a new order was applied and it differs from the one before (the strip should go back to its start).
     */
    private fun orderStrip(force: Boolean = false): Boolean {
        val ideal = ordered()
        if (!force && screen?.visibility == View.VISIBLE && stripFromFeed && strip.isNotEmpty()) {
            val byId = ideal.associateBy { it.id }
            val kept = strip.mapNotNull { byId[it.id] }
            val keptIds = kept.mapTo(HashSet()) { it.id }
            strip = kept + ideal.filter { it.id !in keptIds }
            return false
        }
        val changed = ideal.map { it.id } != strip.map { it.id }
        strip = ideal
        stripFromFeed = feed.isNotEmpty()
        return changed
    }

    /**
     * Channels with a blue dot, like YouTube's: a channel that has a recent video the person has not seen. A video
     * counts as seen when the account has watched part of it (the red line YouTube draws under its picture), when it
     * was opened from this screen, or when its channel was tapped in the strip. Only the last [NEW_WINDOW_HOURS]
     * count (a video of unknown age counts while it is among the first [NEW_FALLBACK_COUNT] of the feed); Shorts and
     * streams that have not started do not.
     */
    private fun newIds(): Set<String> {
        if (feed.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        val fresh = HashSet<String>()
        for ((index, video) in feed.withIndex()) {
            if (video.short || video.live == 2 || video.watched > 0 || video.id in seenIds) continue
            val age = SubsAge.ageHours(video, now)
            if (age != null) {
                if (age > NEW_WINDOW_HOURS) continue
            } else if (index >= NEW_FALLBACK_COUNT) {
                continue
            }
            val id = if (video.channelId.isNotEmpty()) {
                video.channelId
            } else {
                channels.firstOrNull { it.name == video.channelName }?.id ?: continue
            }
            fresh.add(id)
        }
        return fresh
    }

    /** Remembers these videos as seen (the oldest are forgotten after [MAX_SEEN]). */
    private fun remember(ids: Collection<String>) {
        var changed = false
        for (id in ids) {
            if (seenIds.add(id)) changed = true
        }
        if (!changed) return
        while (seenIds.size > MAX_SEEN) {
            val oldest = seenIds.iterator()
            oldest.next()
            oldest.remove()
        }
        state.edit().putString(KEY_SEEN_IDS, seenIds.joinToString(",")).apply()
    }

    /** The channel was tapped in the strip: everything it has in the feed counts as seen. */
    private fun markSeen(channel: SubsChannel) {
        remember(feed.filter { !it.short && belongs(it, channel) }.map { it.id })
    }

    /**
     * YouTube's page keeps its header and bottom tabs: the screen covers only what is between them. Their sizes are
     * read from the page (in device pixels); if that fails the last known (or a usual) size is kept.
     */
    private fun measure() {
        val view = screen ?: return
        browse().evaluateJavascript(MEASURE_JS) { value ->
            if (released) return@evaluateJavascript
            val parts = value?.trim('"')?.split(',')
            val top = parts?.getOrNull(0)?.toIntOrNull() ?: 0
            val bottom = parts?.getOrNull(1)?.toIntOrNull() ?: 0
            if (top in dp(24)..dp(140)) topMargin = top
            if (bottom in dp(32)..dp(140)) bottomMargin = bottom
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@evaluateJavascript
            if (params.topMargin != topMargin || params.bottomMargin != bottomMargin) {
                params.topMargin = topMargin
                params.bottomMargin = bottomMargin
                view.layoutParams = params
            }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    // ---- Loading the channels ----

    private fun refreshChannels(cookie: String) {
        channelsLoading = true
        pool.execute {
            var list: List<SubsChannel>? = null
            var error: Exception? = null
            try {
                list = SubsRepository.fetchChannels(cookie)
            } catch (e: Exception) {
                error = e
            }
            val found = list
            val failure = error
            main.post { onChannels(found, failure) }
        }
    }

    private fun onChannels(found: List<SubsChannel>?, failure: Exception?) {
        channelsLoading = false
        if (released) return
        if (found != null) {
            val changed = found.map { it.id } != channels.map { it.id }
            setChannelList(found)
            val keep = found.map { it.id }.toHashSet()
            channelVideos.keys.retainAll(keep)
            channelShorts.keys.retainAll(keep)
            channelStreams.keys.retainAll(keep)
            channelsAt = System.currentTimeMillis()
            save()
            if (onSubsPage) show()
            if ((changed || feed.isEmpty()) && !feedLoading) refreshFeed()
        } else if (failure is SubsRepository.Failure) {
            // The page answered but holds no list (signed out, or YouTube changed it): show YouTube's page again.
            setChannelList(emptyList())
            feed = emptyList()
            resetShorts()
            channelVideos.clear()
            channelShorts.clear()
            channelStreams.clear()
            SubsRepository.clear(activity.applicationContext)
            screen?.visibility = View.GONE
            warn(failure.message ?: "?")
        }
        // Otherwise the phone is offline: what is shown stays.
        if (found == null) screen?.setRefreshing(false)
    }

    // ---- Loading the feed ----

    private fun refreshFeed() {
        if (channels.isEmpty()) return
        val cookie = cookie()
        val token = ++generation
        feedLoading = true
        emptyPages = 0
        shortsDry = 0
        lastTryAt = System.currentTimeMillis()
        screen?.setLoading(true)
        pool.execute {
            var page: SubsPage? = null
            var error: Exception? = null
            try {
                page = SubsRepository.fetchFeed(cookie, lang)
            } catch (e: Exception) {
                error = e
            }
            val result = page
            val failure = error
            main.post { onFeedPage(token, result, failure) }
        }
    }

    private fun onFeedPage(token: Int, page: SubsPage?, failure: Exception?) {
        if (released || token != generation) return
        if (page != null && page.videos.isNotEmpty()) {
            feed = page.videos
            cursor = page
            cursorMissing = false
            feedHead = page
            shortsStale = true // a new first page: the Shorts chip's own list is read again too (when it is the one on view)
            finishFeed()
        } else if (failure is IOException) {
            // Offline: keep what is there.
            feedLoading = false
            screen?.setLoading(false)
            screen?.setRefreshing(false)
        } else {
            // The page held no videos that could be read: ask the channels' RSS feeds instead.
            refreshFeedRss(token)
        }
    }

    /** The way out: the newest videos of every channel from its RSS feed, put together by date. */
    private fun refreshFeedRss(token: Int) {
        val list = channels
        val collected = HashMap<String, List<SubsVideo>>()
        var done = 0
        var failed = 0
        var lastError = ""
        for (channel in list) {
            pool.execute {
                var videos: List<SubsVideo>? = null
                var message = ""
                try {
                    videos = SubsRepository.fetchRss(channel.id, channel.name)
                } catch (e: Exception) {
                    message = e.message ?: e.javaClass.simpleName
                }
                val got = videos
                val why = message
                main.post {
                    if (released || token != generation) return@post
                    done++
                    if (got != null) collected[channel.id] = got else {
                        failed++
                        lastError = why
                    }
                    if (done == list.size) {
                        channelVideos.putAll(collected)
                        feed = collected.values.flatten().sortedByDescending { it.publishedMs }.take(MAX_FEED)
                        cursor = null
                        cursorMissing = false
                        if (feed.isEmpty()) {
                            SubsRepository.diag = SubsRepository.diag + "; rss: ${list.size - failed} ok, $failed failed ($lastError)"
                        }
                        finishFeed()
                    }
                }
            }
        }
    }

    private fun finishFeed() {
        feedLoading = false
        if (feed.isNotEmpty()) feedAt = System.currentTimeMillis()
        val reordered = orderStrip(force = reorderOnFeed)
        reorderOnFeed = false
        save()
        val view = screen ?: return
        view.setRefreshing(false)
        if (onSubsPage) {
            view.setChannels(strip, newIds())
            if (reordered) view.scrollStripToStart()
            applyVideos(view, toTop = false)
            view.setLoading(channelBusy != null)
        }
    }

    /** The finger is dragging over the screen: the app's bottom bar follows it (see MainActivity.onPageScrolled). */
    override fun onDrag(down: Boolean) {
        if (!released) onScrollDirection(down)
    }

    /** The end of the feed was reached: the next page (older videos), if YouTube has one. */
    override fun onEnd() {
        if (shortsFromChip()) {
            moreShorts() // the Shorts chip has a list of its own, with its own next page
            return
        }
        val page = cursor ?: return
        if (selected != null || moreLoading || feedLoading || page.token == null || feedFull()) return
        if (filter == SubsFilter.TODAY && passedToday()) return
        moreLoading = true
        val cookie = cookie()
        val token = generation
        pool.execute {
            var next: SubsPage? = null
            try {
                next = SubsRepository.fetchMore(page, cookie)
            } catch (e: Exception) {
                // Tried again at the next scroll.
            }
            val result = next
            main.post {
                moreLoading = false
                if (released || token != generation || result == null) return@post
                val known = feed.map { it.id }.toHashSet()
                val fresh = result.videos.filter { it.id !in known }
                if (fresh.isEmpty()) {
                    // Nothing new on this page: the end, unless it points to yet another page (tried a few times).
                    if (result.token != null && result.token != page.token && emptyPages < MAX_EMPTY_PAGES) {
                        emptyPages++
                        cursor = result
                        onEnd()
                    } else {
                        cursor = null
                    }
                    return@post
                }
                emptyPages = 0
                // The feed holds [MAX_FEED] videos at most, but the Shorts chip reads on past that (see [feedFull]):
                // beyond that size only the Shorts of a page are kept, so the other chips keep their size and speed.
                val kept = if (feed.size >= MAX_FEED) fresh.filter { it.short } else fresh
                shortsDry = if (kept.any { it.short }) 0 else shortsDry + 1
                feed = feed + kept
                cursor = result
                val view = screen
                if (view != null && onSubsPage && selected == null) {
                    if (kept.isNotEmpty()) applyVideos(view, toTop = false)
                    else if (shortsChipReadsOn()) view.checkEnd() // nothing to show, but the end is still in view
                }
            }
        }
    }

    // ---- Loading one channel ----

    private fun cacheOf(tab: String): HashMap<String, List<SubsVideo>> =
        when (tab) {
            "shorts" -> channelShorts
            "streams" -> channelStreams
            else -> channelVideos
        }

    /** Reads one tab of the channel's page ("videos", "shorts" or "streams") unless it was read a moment ago. */
    private fun loadChannel(channel: SubsChannel, tab: String) {
        val now = System.currentTimeMillis()
        val key = channel.id + "|" + tab
        val loadedAt = channelLoadedAt[key] ?: 0L
        if (cacheOf(tab)[channel.id] != null && now - loadedAt < CHANNEL_STALE_MS) return
        channelBusy = key
        screen?.setLoading(true)
        val cookie = cookie()
        pool.execute {
            var videos: List<SubsVideo>? = null
            try {
                videos = SubsRepository.fetchChannelTab(cookie, lang, channel, tab).videos
            } catch (e: Exception) {
                // Below: the channel's RSS feed (videos only).
            }
            if (tab == "videos" && videos.isNullOrEmpty()) {
                val pageDiag = SubsRepository.diag
                try {
                    videos = SubsRepository.fetchRss(channel.id, channel.name)
                } catch (e: Exception) {
                    SubsRepository.diag = pageDiag + "; rss: " + (e.message ?: e.javaClass.simpleName)
                }
            }
            val got = videos
            main.post {
                if (channelBusy == key) channelBusy = null
                if (released) return@post
                // An empty answer is kept for Shorts and streams (many channels have none), not for videos (a failure).
                if (got != null && (got.isNotEmpty() || tab != "videos")) {
                    cacheOf(tab)[channel.id] = got
                    channelLoadedAt[key] = System.currentTimeMillis()
                }
                val view = screen
                if (view != null && onSubsPage && selected?.id == channel.id && tabFor(filter) == tab) {
                    view.setLoading(false)
                    applyVideos(view, toTop = false)
                }
            }
        }
    }

    // ---- Keeping and telling ----

    private fun save() {
        val snapshot = SubsSnapshot(account, channels, feed.take(SAVED_VIDEOS), channelsAt, feedAt)
        val app = activity.applicationContext
        pool.execute { runCatching { SubsRepository.save(app, snapshot) } }
    }

    private fun warn(reason: String) {
        if (warned) return
        warned = true
        Toast.makeText(activity, activity.getString(R.string.subsLoadFailed, reason), Toast.LENGTH_LONG).show()
    }

    // ---- What the screen asks ----

    override fun onChannel(channel: SubsChannel?) {
        selected = channel
        if (channel != null) markSeen(channel)
        val view = screen ?: return
        view.setChannels(strip, newIds())
        applyVideos(view, toTop = true)
        if (channel != null) {
            loadChannel(channel, tabFor(filter))
        } else {
            view.setLoading(feedLoading)
        }
    }

    override fun onFilter(filter: Int) {
        this.filter = filter
        autoMoreLeft = AUTO_MORE_PAGES
        shortsDry = 0
        if (filter == SubsFilter.SHORTS) {
            // A tap on the chip is a fresh start for it: a failed read is tried again at once, an old list is read again.
            shortsTryAt = 0L
            if (System.currentTimeMillis() - shortsFeedAt > FEED_STALE_MS) shortsStale = true
        }
        val view = screen ?: return
        val chosen = selected
        view.setLoading(if (chosen != null) channelBusy != null else feedLoading)
        applyVideos(view, toTop = true)
        if (chosen != null) loadChannel(chosen, tabFor(filter))
    }

    override fun onVideo(video: SubsVideo) {
        // Opened, so seen: its channel's blue dot goes (if it has no other new video).
        remember(listOf(video.id))
        screen?.setChannels(strip, newIds())
        openUrl(
            if (video.short) "https://m.youtube.com/shorts/${video.id}" else "https://m.youtube.com/watch?v=${video.id}"
        )
    }

    override fun onOpenChannel(channel: SubsChannel) {
        startHold()
        openUrl("https://m.youtube.com/channel/${channel.id}")
    }

    /** The "All" button: YouTube's own list of every subscription (the screen stays over the page until it is on view). */
    override fun onOpenAllChannels() {
        startHold()
        openUrl("https://m.youtube.com/feed/channels")
    }

    override fun onRefresh() {
        val view = screen
        if (channels.isEmpty()) {
            view?.setRefreshing(false)
            return
        }
        if (!channelsLoading) refreshChannels(cookie())
        val chosen = selected
        if (chosen != null) {
            channelLoadedAt.remove(chosen.id + "|" + tabFor(filter))
            loadChannel(chosen, tabFor(filter))
            view?.setRefreshing(false)
        } else if (feedLoading) {
            view?.setRefreshing(false)
        } else {
            reorderOnFeed = true
            refreshFeed()
        }
    }

    /** The activity is going away: stop the work in the background and take the screen off. */
    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        screen?.let { area.removeView(it) }
        screen = null
    }

    private companion object {
        const val FEED_STALE_MS = 5 * 60 * 1000L
        const val CHANNELS_STALE_MS = 60 * 60 * 1000L
        const val CHANNEL_STALE_MS = 10 * 60 * 1000L
        const val RETRY_MS = 60 * 1000L

        /** The screen stays over a channel's page that is still loading for this long at most (see [holding]). */
        const val HOLD_MAX_MS = 12_000L

        /**
         * The feed stops growing here: every page that arrives rebuilds the whole list on the main thread, so a very
         * long feed slows the screen down (most of all on a phone with little memory).
         */
        const val MAX_FEED = 200

        /**
         * When the Shorts chip lists the feed's own Shorts (YouTube gave no Shorts chip of its own), it goes on past
         * [MAX_FEED] until the feed holds this many Shorts (only the Shorts of the later pages are kept then: the tiles
         * are cheap to list, and the other chips stay as they are).
         */
        const val MAX_SHORTS = 2000

        /** The feed's own Shorts are asked for by themselves until this many pages in a row came with no new Short. */
        const val MAX_DRY_PAGES = 20

        /**
         * The Shorts chip's own list has no limit but YouTube's end of it; this is only a stop for a list that would
         * never end (a tile is a few hundred bytes, so this is a few MB at most).
         */
        const val MAX_SHORTS_FEED = 10_000

        /** Pages of the Shorts chip's own list in a row with nothing new that are skipped over before the list counts as ended. */
        const val MAX_SHORTS_EMPTY_PAGES = 5

        /** Where the Shorts chip usually stands in the chips of the Subscriptions page (All, Today, Videos, Shorts, Live...). */
        const val USUAL_SHORTS_CHIP = 3

        /** Only the newest videos are kept on the phone (the screen opens with them, then reads the feed again). */
        const val SAVED_VIDEOS = 120

        /** Pages with nothing new that are skipped over before the feed counts as ended. */
        const val MAX_EMPTY_PAGES = 3
        const val KEY_SEEN_IDS = "seen_ids"
        const val MAX_SEEN = 1500

        /** A video is "new" (blue dot) for a week; one of unknown age counts while it is among the feed's first 40. */
        const val NEW_WINDOW_HOURS = 7 * 24.0
        const val NEW_FALLBACK_COUNT = 40

        /** A chip that lists fewer videos than this asks for more pages of the feed, up to [AUTO_MORE_PAGES] times. */
        const val MIN_FILTERED = 6
        const val AUTO_MORE_PAGES = 3

        /** Shown once per run: why YouTube's own page is shown instead of the app's screen. */
        @Volatile
        var warned = false

        /** "top,bottom": where YouTube's header ends and its bottom tabs start, in device pixels. */
        const val MEASURE_JS =
            "(function(){try{var d=window.devicePixelRatio||1;" +
                "var h=document.querySelector('ytm-mobile-topbar-renderer');" +
                "var p=document.querySelector('ytm-pivot-bar-renderer');" +
                "var t=h?Math.round(h.getBoundingClientRect().bottom*d):0;" +
                "var b=p?Math.round((window.innerHeight-p.getBoundingClientRect().top)*d):0;" +
                "return t+','+b;}catch(e){return '0,0';}})()"
    }
}
