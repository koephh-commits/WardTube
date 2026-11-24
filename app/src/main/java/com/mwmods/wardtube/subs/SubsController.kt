// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
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
    /** A quick sideways swipe over the screen: the owner opens its side menu (when that mode is on). */
    private val openSideMenu: () -> Unit = {},
    /** The menu's "Download" row: the address of the video's watch page and its title (the owner's usual download flow). */
    private val onDownload: (url: String, title: String?) -> Unit = { _, _ -> },
    /** The menu's "Share" row: the link to share and the video's title (the owner opens the phone's share sheet). */
    private val onShare: (link: String, title: String) -> Unit = { _, _ -> },
) : SubsListener {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "wardtube-subs").apply { isDaemon = true }
    }

    /**
     * The pool of the channels' own posts (see [morePostsFromChannels]): one request per channel, several at once, so
     * they do not wait in the queue of the feed, the chips and the playlists that the pool above serves.
     */
    private val postsPool = Executors.newFixedThreadPool(5) { task ->
        Thread(task, "wardtube-posts").apply { isDaemon = true }
    }
    private val state = activity.getSharedPreferences("subs_state", Context.MODE_PRIVATE)
    private val lang: String = Locale.getDefault().language.ifEmpty { "en" }

    private var screen: SubsScreen? = null
    private var released = false
    private var onSubsPage = false

    /** True while the app's own Subscriptions screen covers the browsing WebView (a touch then does not reach the page). */
    val isCovering: Boolean get() = screen?.visibility == View.VISIBLE

    /** The address of the page the browsing WebView last reported (it may still be loading). */
    private var pageUrl: String? = null

    /** The post whose comments are being opened by [commentsStep] (null when nothing is waiting), its tries and taps so far. */
    private var commentsFor: String? = null
    private var commentsTries = 0
    private var commentsTaps = 0
    private var commentsMenus = 0

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

    /** Videos hidden with the card's menu: they are left out of every list (kept on the phone, per account). */
    private val hiddenIds = LinkedHashSet<String>()

    /** The key and the context that YouTube's own API asks for, taken from the first page read (or read on demand). */
    @Volatile
    private var apiAccess: SubsRepository.ApiAccess? = null

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

    /**
     * The Posts chip, the way YouTube does it (the same as the Shorts chip above): its own list of the posts of every
     * subscription ([postsFeed]), with its own continuation ([postsCursor]). "All content" and "Today" read it too, to
     * put the posts between the videos (see [extraPosts]).
     *  - [postsDirect] true: the chip answered with posts, the Posts chip lists [postsFeed];
     *  - false: the page has no usable Posts chip, the Posts chip lists the posts that the feed itself carries;
     *  - null: not known yet (the answer is being read, or it could not be read for a moment, offline for instance).
     */
    private var postsDirect: Boolean? = null
    private var postsFeed: List<SubsVideo> = emptyList()
    private var postsCursor: SubsPage? = null
    private var postsFeedAt = 0L
    private var postsTryAt = 0L
    private var postsLoading = false
    private var postsMoreLoading = false

    /**
     * True when YouTube's own list of posts could not be read: the Posts chip then lists the posts tab of the channels
     * themselves, a few channels at a time, put together by date (see [morePostsFromChannels]). It goes on, channel
     * after channel and page after page, for as long as the person scrolls.
     */
    private var postsByChannels = false
    private val chanPostCursor = HashMap<String, SubsPage>() // the next page of a channel's posts tab, while it has one
    private val chanPostRead = HashSet<String>() // the channels whose posts tab was read
    private var chanPostsBusy = false
    private var chanPostsDry = 0 // batches in a row that brought no new post

    /** What the Posts chip's answers held, and what the channels' posts read: shown under an empty list, so it says why. */
    private var postsNote = ""
    private var postsChanNote = ""

    /** True when [postsFeed] is from before the feed's last refresh (or the chip was tapped after a while): it is read again. */
    private var postsStale = false

    /** How many pages of the Posts chip in a row came with nothing new (the next one is still tried a few times). */
    private var postsEmpty = 0

    /** Changes with every new first page of the Posts chip (and every new account): answers of an older one are dropped. */
    private var postsGen = 0

    /** Batches of channels read since the posts-of-channels list began ("All content" stops reading on its own at [CHAN_POSTS_BG_BATCHES]). */
    private var chanBatches = 0

    /** When YouTube's own Posts chip last answered with no posts: it is not asked again for [POSTS_CHIP_RETRY_MS]. */
    private var postsChipBadAt = 0L

    /** How many more pages of YouTube's own posts are read on their own to reach as far back as the feed does. */
    private var postsAuto = 0

    /**
     * What was learned about which channels have a posts tab at all (most have none, and asking each of them costs a
     * whole page): kept between runs for [POSTS_MEMORY_MS]. The ones with posts are read first, the ones without are
     * skipped. A "no" is trusted only once some channel in this run has shown that posts can be read (see [learnPosts]).
     */
    private val chanHasPosts = HashSet<String>()
    private val chanNoPosts = HashSet<String>()
    private val pendingNoPosts = HashSet<String>()
    private var sessionSawPosts = false
    private var postsMemoryRead = false

    private var postsRefreshQueued = false
    private val postsRefresh = Runnable {
        postsRefreshQueued = false
        val view = screen
        if (released || view == null || !onSubsPage || selected != null) return@Runnable
        if (postsFromChannels() || postsFromChannelsBg()) applyVideos(view, toTop = false)
    }

    /** The videos of one channel, read when it is chosen; its Shorts, posts and live streams when those chips are chosen. */
    private val channelVideos = HashMap<String, List<SubsVideo>>()
    private val channelShorts = HashMap<String, List<SubsVideo>>()
    private val channelStreams = HashMap<String, List<SubsVideo>>()
    private val channelPosts = HashMap<String, List<SubsVideo>>()
    private val channelLoadedAt = HashMap<String, Long>() // key: "<channel id>|<tab>"

    private var channelsAt = 0L
    private var feedAt = 0L
    private var lastTryAt = 0L
    private var channelsLoading = false
    private var feedLoading = false
    private var moreLoading = false

    /** The last read of the feed failed (offline, or nothing could be read): the empty screen then offers "Try again". */
    private var feedFailed = false

    /** The tabs ("<channel id>|<tab>") whose last read failed: the same, for the screen of one channel. */
    private val channelFailed = HashSet<String>()
    private var channelBusy: String? = null
    private var generation = 0

    private var topMargin = dp(56)
    private var bottomMargin = dp(64)

    init {
        state.getString(KEY_SEEN_IDS, "").orEmpty().split(',').filter { it.length == 11 }.forEach { seenIds.add(it) }
        // A hidden post's id is longer than a video's 11 characters.
        state.getString(KEY_HIDDEN_IDS, "").orEmpty().split(',').filter { it.length >= 11 }.forEach { hiddenIds.add(it) }
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
            screen?.closeChannels()
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
            hiddenIds.clear()
            apiAccess = null
            state.edit().remove(KEY_SEEN_IDS).remove(KEY_HIDDEN_IDS).apply()
        }
        snapshotRead = true
        account = key
        setChannelList(emptyList())
        feed = emptyList()
        cursor = null
        cursorMissing = false
        resetShorts()
        resetPosts()
        channelVideos.clear()
        channelShorts.clear()
        channelStreams.clear()
        channelPosts.clear()
        channelLoadedAt.clear()
        channelFailed.clear()
        feedFailed = false
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
        ensurePosts() // the same for the Posts chip, and the posts that "All content" puts between the videos
        val videos = visible()
        view.setVideos(videos, avatars, toTop)
        showStatus(view)
        val bare = videos.isEmpty() && (filter == SubsFilter.ALL || filter == SubsFilter.POSTS)
        val note = if (filter == SubsFilter.POSTS) {
            listOf(postsNote, postsChanNote).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { SubsRepository.diag }
        } else {
            SubsRepository.diag
        }
        view.setDiagnostic(if (bare) note else "")
        fillFiltered(videos.size)
        // The list never ends on its own: if its end is already in view (a short page, or a page that finished
        // loading while the person sat at the bottom) no scroll comes to ask for more, so the screen asks. The Shorts
        // chip does too (its own list goes on for as long as YouTube has Shorts; the feed's Shorts are few per page, so
        // their end is often in view, until pages keep coming with no Short in them, [shortsDry]).
        if (selected == null && (SubsFilter.listsVideos(filter) || shortsChipReadsOn() || postsFromChip() || postsFromChannels())) {
            view.checkEnd()
        }
    }

    /**
     * Tells the screen two things about what is happening behind the list: whether the list is empty because a read
     * failed (it then offers "Try again" instead of "no videos"), and whether older videos are being read (a small
     * spinner then shows at the end of the list).
     */
    private fun showStatus(view: SubsScreen) {
        val chosen = selected
        val failed = if (chosen != null) {
            tabsFor(filter).any { chosen.id + "|" + it in channelFailed }
        } else {
            feedFailed && feed.isEmpty()
        }
        view.setError(failed)
        view.setLoadingMore(moreBusy())
    }

    /** The same, for the spinner alone: called where a flag of a "read more" goes up or down. */
    private fun updateMore() {
        screen?.setLoadingMore(moreBusy())
    }

    /** True while the list the chip shows is being read further (older pages of the feed, or of the chip's own list). */
    private fun moreBusy(): Boolean =
        selected == null && when {
            filter == SubsFilter.SHORTS && shortsFromChip() -> shortsMoreLoading
            filter == SubsFilter.POSTS && postsFromChip() -> postsMoreLoading
            filter == SubsFilter.POSTS && postsFromChannels() -> chanPostsBusy
            else -> moreLoading
        }

    /** The Shorts chip is chosen and there is still more for it to read by itself (see [shortsFromChip]). */
    private fun shortsChipReadsOn(): Boolean =
        filter == SubsFilter.SHORTS && (shortsFromChip() || shortsDry < MAX_DRY_PAGES)

    /** True when the Shorts chip, with no channel chosen, lists YouTube's own list of Shorts ([shortsFeed]). */
    private fun shortsFromChip(): Boolean = selected == null && filter == SubsFilter.SHORTS && shortsDirect == true

    /** True when the Posts chip, with no channel chosen, lists YouTube's own list of posts ([postsFeed]). */
    private fun postsFromChip(): Boolean = selected == null && filter == SubsFilter.POSTS && postsDirect == true

    /** True when the Posts chip, with no channel chosen, lists the posts of the channels themselves ([postsFeed]). */
    private fun postsFromChannels(): Boolean = selected == null && filter == SubsFilter.POSTS && postsByChannels

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
            if (postsFromChannels()) return postsFeed
            if (postsFromChip()) return postsFeed
            // The Shorts (or Posts) chip is asking YouTube for its own list for the first time: nothing of the feed shows
            // meanwhile (the list would be swapped a moment later), the spinner does.
            if (filter == SubsFilter.SHORTS && shortsLoading && shortsDirect == null) return emptyList()
            if (filter == SubsFilter.POSTS && postsLoading && postsDirect == null) return emptyList()
            return when (filter) {
                SubsFilter.ALL -> feed + extraPosts() + extraShorts()
                SubsFilter.TODAY -> feed + extraPosts()
                else -> feed
            }
        }
        // "All content" of a channel is everything its page has: videos, posts and Shorts.
        return when (filter) {
            SubsFilter.SHORTS -> channelShorts[chosen.id].orEmpty()
            SubsFilter.LIVE -> channelStreams[chosen.id].orEmpty()
            SubsFilter.POSTS -> channelPosts[chosen.id].orEmpty()
            SubsFilter.ALL -> channelVideos[chosen.id].orEmpty() + channelPosts[chosen.id].orEmpty() + channelShorts[chosen.id].orEmpty()
            SubsFilter.TODAY -> channelVideos[chosen.id].orEmpty() + channelPosts[chosen.id].orEmpty()
            else -> channelVideos[chosen.id].orEmpty()
        }
    }

    /**
     * The videos the chosen chip shows, the newest first (older pages of the feed go below). "All content" keeps the
     * Shorts: the screen puts them in rows between videos. Posts are listed by "All content", "Today" and "Posts", and
     * stand among the videos by their age.
     */
    private fun visible(): List<SubsVideo> {
        val all = baseList()
        val base = if (hiddenIds.isEmpty()) all else all.filter { it.id !in hiddenIds }
        val now = System.currentTimeMillis()
        return when (filter) {
            SubsFilter.TODAY -> SubsAge.newestFirst(base.filter { !it.short && SubsAge.isToday(it, now) }, now)
            SubsFilter.VIDEOS -> SubsAge.newestFirst(base.filter { !it.short && it.live == 0 && it.post == null }, now)
            // The red line under a video's picture is how much of it the account has watched (0: none, so "unwatched").
            SubsFilter.UNWATCHED ->
                SubsAge.newestFirst(base.filter { !it.short && it.live != 2 && it.post == null && it.watched == 0 }, now)
            SubsFilter.CONTINUE ->
                SubsAge.newestFirst(
                    base.filter { !it.short && it.live != 2 && it.post == null && it.watched in 1 until SubsFilter.FINISHED_PERCENT },
                    now,
                )
            SubsFilter.SHORTS -> base.filter { it.short }
            SubsFilter.POSTS -> SubsAge.newestFirst(base.filter { it.post != null }, now)
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

    /**
     * The tabs of a channel's page that hold what the chosen chip lists, the one that matters most first: "All content"
     * is everything the channel has (its videos, its posts and its Shorts), "Today" its videos and posts.
     */
    private fun tabsFor(chip: Int): List<String> =
        when (chip) {
            SubsFilter.ALL -> listOf("videos", "posts", "shorts")
            SubsFilter.TODAY -> listOf("videos", "posts")
            SubsFilter.SHORTS -> listOf("shorts")
            SubsFilter.LIVE -> listOf("streams")
            SubsFilter.POSTS -> listOf("posts")
            else -> listOf("videos")
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

    private fun resetPosts() {
        postsGen++
        postsDirect = null
        postsFeed = emptyList()
        postsCursor = null
        postsFeedAt = 0L
        postsTryAt = 0L
        postsLoading = false
        postsMoreLoading = false
        postsStale = false
        postsEmpty = 0
        dropChannelPosts()
        postsNote = ""
        postsChanNote = ""
    }

    /** Forgets what the posts-of-channels list read (a new feed, a new account): it is read again when it is needed. */
    private fun dropChannelPosts() {
        postsByChannels = false
        chanPostCursor.clear()
        chanPostRead.clear()
        chanPostsBusy = false
        chanPostsDry = 0
        chanBatches = 0
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
        if (selected != null || (filter != SubsFilter.SHORTS && filter != SubsFilter.ALL)) return
        if (shortsLoading || shortsDirect == false || feedLoading) return
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
        if (filter == SubsFilter.SHORTS) screen?.setLoading(true)
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
        if (onSubsPage) applyVideos(view, toTop = page != null && filter == SubsFilter.SHORTS)
        fillShortsForAll()
    }

    /** The next page of the Shorts chip's own list (older Shorts), until YouTube has no more. */
    private fun moreShorts() {
        val page = shortsCursor ?: return
        if (shortsMoreLoading || shortsLoading || page.token == null || shortsFeed.size >= MAX_SHORTS_FEED) return
        shortsMoreLoading = true
        updateMore()
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
                if (!released) updateMore()
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
                if (view != null && onSubsPage && selected == null && (shortsFromChip() || filter == SubsFilter.ALL)) {
                    applyVideos(view, toTop = false)
                }
                fillShortsForAll()
            }
        }
    }

    /**
     * The Shorts of YouTube's own Shorts list that the feed does not carry itself, for "All content": the feed holds
     * Shorts only in a few shelves, so its rows of Shorts ran out after the first screens. They are listed after the
     * feed's own Shorts and fill the rows (see [shortsLacking]).
     */
    private fun extraShorts(): List<SubsVideo> {
        if (shortsDirect != true || shortsFeed.isEmpty()) return emptyList()
        val known = feed.mapTo(HashSet()) { it.id }
        return shortsFeed.filter { it.id !in known }
    }

    /**
     * True when "All content" has fewer Shorts than its rows of Shorts will use for the videos loaded so far (a row of
     * [SHORTS_PER_SHELF] after the third video and then after every sixth, as the screen builds them) plus a row to spare.
     */
    private fun shortsLacking(): Boolean {
        val have = feed.count { it.short } + extraShorts().size
        val videos = feed.count { !it.short } + extraPosts().size
        val need = ((videos + 3) / 6 + 2) * SHORTS_PER_SHELF
        return have < need
    }

    /** Reads on YouTube's own list of Shorts while the rows of "All content" lack Shorts (and the list has more). */
    private fun fillShortsForAll() {
        if (released || selected != null || filter != SubsFilter.ALL) return
        if (shortsDirect != true || shortsCursor == null || !shortsLacking()) return
        moreShorts()
    }

    // ---- The Posts chip ----

    /** The chips that may be the Posts chip, the likeliest first: the one named like "Posts", then the one in its usual place. */
    private fun postsChipsToTry(chips: List<SubsChip>): List<SubsChip> {
        val named = chips.filter { it.token.isNotEmpty() && isPostsLabel(it.text) }
        // All, Today, Videos, Shorts, Live, Posts: the sixth.
        val usual = chips.getOrNull(USUAL_POSTS_CHIP)?.takeIf { it.token.isNotEmpty() }
        return (named + listOfNotNull(usual)).distinctBy { it.token }.take(2)
    }

    /** The word for "posts" in the languages of the app's screen (a label in another language is found by its place). */
    private fun isPostsLabel(text: String): Boolean {
        val label = text.lowercase(Locale.ROOT)
        return POSTS_WORDS.any { label.contains(it) }
    }

    /**
     * Reads YouTube's own list of posts for the Posts chip (no channel chosen), and for "All content" and "Today" (which
     * put its posts between the videos), when the page gave the chip and the list is missing or old. The chip's answer
     * counts only if it is (nearly) all posts ([SubsRepository.mostlyPosts]), so a wrong guess of which chip it is can
     * never show another list. When no chip gives posts the Posts chip lists the posts of the feed itself. A failed read
     * (offline, an error) is tried again after [RETRY_MS], or at the next tap on the chip.
     */
    private fun ensurePosts() {
        // YouTube's own list of posts could not be read (or the chip is not there): the channels' own posts are listed.
        if (selected == null && postsDirect == false &&
            (filter == SubsFilter.POSTS || filter == SubsFilter.ALL || filter == SubsFilter.TODAY)
        ) {
            if (!postsByChannels) startPostsFromChannels()
            else if (!chanPostsBusy && chanPostRead.isEmpty()) morePostsFromChannels(background = filter != SubsFilter.POSTS)
            return
        }
        if (selected != null || postsLoading || postsDirect == false || feedLoading) return
        if (filter != SubsFilter.POSTS && filter != SubsFilter.ALL && filter != SubsFilter.TODAY) return
        val head = feedHead ?: return
        if (postsDirect == true && !postsStale && postsFeed.isNotEmpty()) return
        val now = System.currentTimeMillis()
        // After a failed read the next try waits; a list that was read fine and is old now (a pull-to-refresh) goes at once.
        if (postsDirect != true && postsTryAt != 0L && now - postsTryAt < RETRY_MS) return
        val chips = postsChipsToTry(head.chips)
        if (chips.isEmpty()) {
            // The page has no chip to ask: the Posts chip lists the channels' own posts.
            postsNote = "no posts chip in the page (${head.chips.size} chips)"
            postsDirect = false
            postsChipBadAt = System.currentTimeMillis()
            startPostsFromChannels()
            return
        }
        postsLoading = true
        postsStale = false
        postsTryAt = now
        val gen = ++postsGen
        val cookie = cookie()
        if (filter == SubsFilter.POSTS) screen?.setLoading(true)
        pool.execute {
            var found: SubsPage? = null
            var unsure = false
            val notes = StringBuilder()
            for (chip in chips) {
                try {
                    val page = SubsRepository.fetchChip(head, chip, cookie)
                    notes.append("chip «").append(chip.text).append("»: ").append(page.note).append("\n")
                    if (SubsRepository.mostlyPosts(page.videos)) {
                        found = page
                        break
                    }
                } catch (e: Exception) {
                    notes.append("chip «").append(chip.text).append("»: ").append(e.message ?: e.javaClass.simpleName).append("\n")
                    unsure = true // offline, or an error answer: not a proof that the chip is not the Posts one
                    break
                }
            }
            val result = found
            val failed = unsure
            val report = notes.toString().trim()
            main.post {
                if (gen == postsGen && report.isNotEmpty()) postsNote = report
                onPostsHead(gen, result, failed)
            }
        }
    }

    private fun onPostsHead(gen: Int, page: SubsPage?, unsure: Boolean) {
        if (released || gen != postsGen) return
        postsLoading = false
        if (page != null) {
            val seen = HashSet<String>()
            postsFeed = page.videos.filter { it.post != null && seen.add(it.id) }
            postsCursor = page
            postsDirect = true
            postsFeedAt = System.currentTimeMillis()
            postsEmpty = 0
        } else if (!unsure) {
            postsDirect = false // every chip answered and none of them listed posts: the channels' own posts are listed
            postsChipBadAt = System.currentTimeMillis()
        }
        postsAuto = 0
        val view = screen ?: return
        view.setLoading(feedLoading || channelBusy != null)
        // The list goes back to its top only when it is the Posts chip's own (the posts coming in under "All content"
        // must not take the person away from what they are reading).
        if (onSubsPage) applyVideos(view, toTop = page != null && filter == SubsFilter.POSTS)
        fillPostsForFeed()
    }

    // ---- The Posts chip, the way out: the posts tab of the channels ----

    private fun startPostsFromChannels() {
        postsByChannels = true
        postsDirect = false
        // What the list holds stays until the new reading brings its own (see [mergePosts]).
        chanPostCursor.clear()
        chanPostRead.clear()
        chanPostsBusy = false
        chanPostsDry = 0
        chanBatches = 0
        morePostsFromChannels(background = filter != SubsFilter.POSTS)
    }

    /** True when "All content" or "Today" (no channel chosen) lists, among its videos, the posts of the channels themselves. */
    private fun postsFromChannelsBg(): Boolean =
        selected == null && postsByChannels && (filter == SubsFilter.ALL || filter == SubsFilter.TODAY)

    /**
     * The channels to read for posts: the ones known to have a posts tab first, then the unknown ones; the ones known to
     * have none are left out. Each one is read once (its first page), so the list is never behind a slow or empty channel.
     */
    private fun postsOrder(): List<SubsChannel> {
        val open = strip.ifEmpty { channels }.filter { it.id !in chanNoPosts }
        val known = open.filter { it.id in chanHasPosts }
        return known + open.filter { it.id !in chanHasPosts }
    }

    private fun loadPostsMemory() {
        if (postsMemoryRead) return
        postsMemoryRead = true
        val now = System.currentTimeMillis()
        val at = state.getLong(KEY_POSTS_MEMORY_AT, 0L)
        if (at == 0L || now - at > POSTS_MEMORY_MS) {
            // Old: learn again (a channel may have started posting).
            state.edit().remove(KEY_POSTS_NO).remove(KEY_POSTS_YES).putLong(KEY_POSTS_MEMORY_AT, now).apply()
            return
        }
        chanNoPosts.addAll(state.getStringSet(KEY_POSTS_NO, emptySet()) ?: emptySet())
        chanHasPosts.addAll(state.getStringSet(KEY_POSTS_YES, emptySet()) ?: emptySet())
    }

    private fun savePostsMemory() {
        if (!sessionSawPosts) return
        state.edit()
            .putStringSet(KEY_POSTS_NO, HashSet(chanNoPosts))
            .putStringSet(KEY_POSTS_YES, HashSet(chanHasPosts))
            .apply()
    }

    /**
     * A channel's posts tab was read ([has]: it gave posts). A "no" counts only once some channel has given posts in this
     * run: when none did, the reading itself may be what failed (a page of another shape), not the channels.
     */
    private fun learnPosts(id: String, has: Boolean) {
        if (has) {
            chanHasPosts.add(id)
            chanNoPosts.remove(id)
            pendingNoPosts.remove(id)
            if (!sessionSawPosts) {
                sessionSawPosts = true
                chanNoPosts.addAll(pendingNoPosts)
                pendingNoPosts.clear()
            }
        } else {
            chanHasPosts.remove(id)
            if (sessionSawPosts) chanNoPosts.add(id) else pendingNoPosts.add(id)
        }
    }

    /** Puts [fresh] posts (of one channel) in [postsFeed]: a post that is already there is replaced by its new reading. */
    private fun mergePosts(fresh: List<SubsVideo>) {
        val ids = fresh.mapTo(HashSet()) { it.id }
        postsFeed = (postsFeed.filter { it.id !in ids } + fresh).take(MAX_POSTS_FEED)
    }

    /** The list is redrawn at most once in a while as the channels answer one by one (a redraw per channel would flicker). */
    private fun schedulePostsRefresh() {
        if (postsRefreshQueued) return
        postsRefreshQueued = true
        main.postDelayed(postsRefresh, POSTS_REFRESH_MS)
    }

    /**
     * Reads the next [CHAN_POSTS_BATCH] channels' posts tabs (see [postsOrder]); when every channel was read, the next
     * page of the channels whose last post read is the newest, so the list goes back in time by date (not when
     * [background]: "All content" and "Today" need only the newest posts). The channels are asked at once on a pool of
     * their own, and each answer goes into the list as it comes, without waiting for the slowest channel of the batch.
     */
    private fun morePostsFromChannels(background: Boolean = false) {
        if (!postsByChannels || chanPostsBusy || released) return
        if (background && chanBatches >= CHAN_POSTS_BG_MAX_BATCHES) return
        loadPostsMemory()
        val order = postsOrder()
        val now = System.currentTimeMillis()
        val jobs = ArrayList<Pair<SubsChannel, SubsPage?>>()
        for (channel in order) {
            if (channel.id !in chanPostRead) jobs.add(Pair(channel, null))
            if (jobs.size >= CHAN_POSTS_BATCH) break
        }
        if (jobs.isEmpty() && !background) {
            // Every channel was read once: the next pages, of the channels that still have one.
            val ageOf = HashMap<String, Double>()
            for (post in postsFeed) {
                val age = SubsAge.ageHours(post, now) ?: continue
                val known = ageOf[post.channelId]
                if (known == null || age > known) ageOf[post.channelId] = age // the age of the oldest post read so far
            }
            val again = order.filter { chanPostCursor[it.id]?.token != null }.sortedBy { ageOf[it.id] ?: 0.0 }
            for (channel in again.take(CHAN_POSTS_BATCH)) jobs.add(Pair(channel, chanPostCursor[channel.id]))
        }
        if (jobs.isEmpty() || chanPostsDry >= CHAN_POSTS_MAX_DRY) return
        chanPostsBusy = true
        updateMore()
        val gen = postsGen
        val cookie = cookie()
        if (!background) screen?.setLoading(true)
        var left = jobs.size
        var gotAny = false
        for ((channel, page) in jobs) {
            postsPool.execute {
                var result: SubsPage? = null
                try {
                    result = if (page == null) {
                        SubsRepository.fetchChannelTab(cookie, lang, channel, "posts")
                    } else {
                        SubsRepository.fetchMore(page, cookie)
                    }
                } catch (e: Exception) {
                    // The channel counts as read: a failing one must not be asked again and again (a new feed reads it again).
                }
                val answer = result
                main.post {
                    if (released || gen != postsGen) return@post
                    chanPostRead.add(channel.id)
                    val mine = answer?.videos?.filter { it.post != null }
                        ?.map { it.copy(channelId = channel.id, channelName = channel.name) }.orEmpty()
                    if (answer != null && page == null) learnPosts(channel.id, mine.isNotEmpty())
                    if (mine.isNotEmpty()) {
                        mergePosts(mine)
                        gotAny = true
                        schedulePostsRefresh()
                    }
                    if (answer != null && answer.token != null && mine.isNotEmpty()) {
                        chanPostCursor[channel.id] = answer
                    } else {
                        chanPostCursor.remove(channel.id)
                    }
                    left--
                    if (left == 0) onChannelPostsBatch(gen, order.size, gotAny)
                }
            }
        }
    }

    private fun onChannelPostsBatch(gen: Int, total: Int, gotAny: Boolean) {
        if (released || gen != postsGen) return
        chanPostsBusy = false
        updateMore()
        chanBatches++
        chanPostsDry = if (gotAny) 0 else chanPostsDry + 1
        postsChanNote = "channels: ${chanPostRead.size} of $total read, ${postsFeed.size} posts"
        savePostsMemory()
        val view = screen
        if (view != null) {
            view.setLoading(feedLoading || channelBusy != null)
            if (onSubsPage && (postsFromChannels() || postsFromChannelsBg())) applyVideos(view, toTop = false)
        }
        // On by itself while the list is short, so the screen is not left empty; after that, as the person scrolls.
        if (postsFromChannels() && postsFeed.size < CHAN_POSTS_FILL) {
            morePostsFromChannels()
        } else if (postsFromChannelsBg() && chanBatches < CHAN_POSTS_BG_BATCHES) {
            morePostsFromChannels(background = true)
        }
    }

    /** The next page of the Posts chip's own list (older posts), until YouTube has no more. */
    private fun morePosts() {
        val page = postsCursor ?: return
        if (postsMoreLoading || postsLoading || page.token == null || postsFeed.size >= MAX_POSTS_FEED) return
        postsMoreLoading = true
        updateMore()
        val gen = postsGen
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
                postsMoreLoading = false
                if (!released) updateMore()
                if (released || gen != postsGen || result == null) return@post
                val known = postsFeed.mapTo(HashSet()) { it.id }
                val fresh = result.videos.filter { it.post != null && it.id !in known }
                if (fresh.isEmpty()) {
                    // Nothing new on this page: the end, unless it points to yet another page (tried a few times).
                    if (result.token != null && result.token != page.token && postsEmpty < MAX_POSTS_EMPTY_PAGES) {
                        postsEmpty++
                        postsCursor = result
                        morePosts()
                    } else {
                        postsCursor = null
                    }
                    return@post
                }
                postsEmpty = 0
                postsFeed = postsFeed + fresh
                postsCursor = result
                val view = screen
                val listsPosts = filter == SubsFilter.POSTS || filter == SubsFilter.ALL || filter == SubsFilter.TODAY
                if (view != null && onSubsPage && selected == null && listsPosts) applyVideos(view, toTop = false)
                fillPostsForFeed()
            }
        }
    }

    /**
     * "All content" and "Today" show a post only as far back as the videos loaded reach; when YouTube's own list of posts
     * has not gone that far, its next pages are read on their own (a few), instead of waiting for the end of the list.
     */
    private fun fillPostsForFeed() {
        if (released || selected != null || postsDirect != true || postsCursor == null) return
        if (filter != SubsFilter.ALL && filter != SubsFilter.TODAY) return
        if (postsAuto >= POSTS_AUTO_PAGES || !postsBehindFeed()) return
        postsAuto++
        morePosts()
    }

    /**
     * The posts of YouTube's own Posts list that the feed does not carry itself, for "All content" and "Today": a feed
     * page may come with no posts in it. They stand among the videos by their age, but only as far back as the videos
     * loaded so far reach: a post older than the last video loaded may belong between videos that are not loaded yet.
     */
    private fun extraPosts(): List<SubsVideo> {
        if (postsFeed.isEmpty()) return emptyList()
        val known = feed.mapTo(HashSet()) { it.id }
        val now = System.currentTimeMillis()
        val more = cursor?.token != null
        val limit = if (more) feed.lastOrNull { !it.short }?.let { SubsAge.ageHours(it, now) } else null
        return postsFeed.filter { post ->
            post.id !in known && (limit == null || (SubsAge.ageHours(post, now) ?: 0.0) <= limit)
        }
    }

    /** True when YouTube's own list of posts has not gone back as far as the feed has: more of it is needed to keep the order. */
    private fun postsBehindFeed(): Boolean {
        val oldestPost = postsFeed.lastOrNull() ?: return false
        val oldestVideo = feed.lastOrNull { !it.short } ?: return false
        val now = System.currentTimeMillis()
        val postAge = SubsAge.ageHours(oldestPost, now) ?: return false
        val videoAge = SubsAge.ageHours(oldestVideo, now) ?: return false
        return postAge < videoAge
    }

    /** A chip that matches only a few videos of the feed: the next pages are fetched (a few times) to fill the list. */
    private fun fillFiltered(count: Int) {
        // The Shorts chip's own list fills itself: the screen asks for its next page while its end is in view.
        if (shortsFromChip() || postsFromChip() || postsFromChannels()) return
        // The Posts chip is still asking YouTube for its own list: the feed's pages would be read for nothing.
        if (filter == SubsFilter.POSTS && postsLoading) return
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
            if (video.short || video.post != null) continue
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
            if (video.short || video.post != null || video.live == 2 || video.watched > 0 || video.id in seenIds) continue
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
        remember(feed.filter { !it.short && it.post == null && belongs(it, channel) }.map { it.id })
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
            channelPosts.keys.retainAll(keep)
            channelsAt = System.currentTimeMillis()
            save()
            if (onSubsPage) show()
            if ((changed || feed.isEmpty()) && !feedLoading) refreshFeed()
        } else if (failure is SubsRepository.Failure) {
            // The page answered but holds no list (signed out, or YouTube changed it): show YouTube's page again.
            setChannelList(emptyList())
            feed = emptyList()
            resetShorts()
            resetPosts()
            channelVideos.clear()
            channelShorts.clear()
            channelStreams.clear()
            channelPosts.clear()
            SubsRepository.clear(activity.applicationContext)
            screen?.closeChannels()
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
            feedFailed = false
            feed = page.videos
            cursor = page
            cursorMissing = false
            feedHead = page
            SubsRepository.accessOf(page)?.let { apiAccess = it }
            shortsStale = true // a new first page: the Shorts chip's own list is read again too (when it is the one on view)
            postsStale = true // and the Posts chip's (the posts of "All content" and "Today" come from it too)
            if (postsByChannels) {
                // The channels' own posts are read again (the ones on view stay until the new reading brings its own), and
                // YouTube's own list gets another chance only when its last answer is old.
                postsGen++
                postsLoading = false
                postsDirect = if (System.currentTimeMillis() - postsChipBadAt < POSTS_CHIP_RETRY_MS) false else null
                postsTryAt = 0L
                dropChannelPosts()
            }
            finishFeed()
        } else if (failure is IOException) {
            // Offline: keep what is there (with nothing there, the screen says so and offers "Try again").
            feedLoading = false
            feedFailed = true
            screen?.setLoading(channelBusy != null)
            screen?.setRefreshing(false)
            screen?.let { showStatus(it) }
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
                        feedFailed = feed.isEmpty()
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

    /** A sideways swipe over the screen: the side menu of the app opens (see MainActivity.openSideMenuBySwipe). */
    override fun onSideSwipe() {
        if (!released) openSideMenu()
    }

    /** The end of the feed was reached: the next page (older videos), if YouTube has one. */
    override fun onEnd() {
        if (postsFromChannels()) {
            morePostsFromChannels() // the channels' own posts: the next channels, and the next pages of the ones read
            return
        }
        if (shortsFromChip()) {
            moreShorts() // the Shorts chip has a list of its own, with its own next page
            return
        }
        if (postsFromChip()) {
            morePosts() // so has the Posts chip
            return
        }
        // "All content" and "Today" have posts of YouTube's own list among their videos: it is read on as the feed goes deeper.
        if (selected == null && (filter == SubsFilter.ALL || filter == SubsFilter.TODAY) && postsDirect == true && postsBehindFeed()) {
            morePosts()
        }
        // The same when YouTube's own list gave none: the next channels' own posts are read as the feed goes deeper.
        if (postsFromChannelsBg()) morePostsFromChannels(background = true)
        // And the rows of Shorts of "All content" are filled from YouTube's own list of Shorts.
        fillShortsForAll()
        val page = cursor ?: return
        if (selected != null || moreLoading || feedLoading || page.token == null || feedFull()) return
        if (filter == SubsFilter.TODAY && passedToday()) return
        moreLoading = true
        updateMore()
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
                if (!released) updateMore()
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
            "posts" -> channelPosts
            else -> channelVideos
        }

    /**
     * Reads the tabs of the channel's page that the chosen chip lists ([tabsFor]), one after the other: the first is what
     * the person waits for, the others fill in behind it. [force] reads them even if they were read a moment ago.
     */
    private fun loadChannelTabs(channel: SubsChannel, force: Boolean = false) {
        val tabs = tabsFor(filter)
        if (force) {
            for (tab in tabs) channelLoadedAt.remove(channel.id + "|" + tab)
        }
        loadChannel(channel, tabs, 0)
    }

    /** Reads the tab at [index] of [tabs] ("videos", "shorts", "posts" or "streams") unless it was read a moment ago, then the next. */
    private fun loadChannel(channel: SubsChannel, tabs: List<String>, index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        val key = channel.id + "|" + tab
        val loadedAt = channelLoadedAt[key] ?: 0L
        // On to the next tab, unless the person chose another channel or chip meanwhile (that choice read its own tabs).
        val goOn: () -> Unit = {
            if (selected?.id == channel.id && tabsFor(filter) == tabs) loadChannel(channel, tabs, index + 1)
        }
        if (cacheOf(tab)[channel.id] != null && now - loadedAt < CHANNEL_STALE_MS) {
            goOn()
            return
        }
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
                // No videos on the Videos tab is a failure (the screen then offers "Try again"). The other tabs are not judged
                // so: a channel that has no Shorts, posts or streams can answer with nothing, or with an error page.
                if (tab == "videos" && got.isNullOrEmpty()) channelFailed.add(key) else channelFailed.remove(key)
                // An empty answer is kept for Shorts, posts and streams (many channels have none), not for videos (a failure).
                if (got != null && (got.isNotEmpty() || tab != "videos")) {
                    cacheOf(tab)[channel.id] = got
                    channelLoadedAt[key] = System.currentTimeMillis()
                }
                val view = screen
                if (view != null && onSubsPage && selected?.id == channel.id && tab in tabsFor(filter)) {
                    view.setLoading(false)
                    applyVideos(view, toTop = false)
                }
                goOn()
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
            loadChannelTabs(channel)
        } else {
            view.setLoading(feedLoading)
        }
    }

    override fun onFilter(filter: Int) {
        this.filter = filter
        if (filter == SubsFilter.POSTS) chanPostsDry = 0
        autoMoreLeft = AUTO_MORE_PAGES
        shortsDry = 0
        if (filter == SubsFilter.SHORTS) {
            // A tap on the chip is a fresh start for it: a failed read is tried again at once, an old list is read again.
            shortsTryAt = 0L
            if (System.currentTimeMillis() - shortsFeedAt > FEED_STALE_MS) shortsStale = true
        }
        if (filter == SubsFilter.POSTS) {
            // The same for the Posts chip.
            postsTryAt = 0L
            if (System.currentTimeMillis() - postsFeedAt > FEED_STALE_MS) postsStale = true
        }
        val view = screen ?: return
        val chosen = selected
        view.setLoading(if (chosen != null) channelBusy != null else feedLoading)
        applyVideos(view, toTop = true)
        if (chosen != null) loadChannelTabs(chosen)
    }

    override fun onVideo(video: SubsVideo) {
        commentsFor = null // another place was chosen: no comments list to open any more
        if (video.post != null) {
            // A post opens as YouTube's own page of it (its comments, all its pictures); the screen stays over the old page
            // until that page is on view, as it does for a channel's page.
            startHold()
            openUrl("${SubsRepository.MOBILE_SITE}/post/${video.id}")
            return
        }
        // Opened, so seen: its channel's blue dot goes (if it has no other new video).
        remember(listOf(video.id))
        screen?.setChannels(strip, newIds())
        openUrl(
            if (video.short) "https://m.youtube.com/shorts/${video.id}" else "https://m.youtube.com/watch?v=${video.id}"
        )
    }

    /**
     * The comments button of a post: the post opens on YouTube's own page (the screen stays over the old page until it is
     * on view, as for a post), then [commentsStep] opens the comments list there and taps its text box, so the keyboard
     * comes up by itself. The taps are real touches sent to the page (a script's own focus() does not bring the keyboard
     * up: the phone wants a touch). If the page's comments are not found in time the person is simply on the post's page.
     */
    override fun onPostComments(video: SubsVideo) {
        if (released) return
        commentsFor = video.id
        commentsTries = 0
        commentsTaps = 0
        commentsMenus = 0
        main.removeCallbacks(commentsStep)
        startHold()
        openUrl("${SubsRepository.MOBILE_SITE}/post/${video.id}")
        main.postDelayed(commentsStep, COMMENTS_FIRST_MS)
    }

    /** One look at the post's page (see [COMMENTS_JS]) and the touch it asks for; repeats until the text box is tapped. */
    private val commentsStep = object : Runnable {
        override fun run() {
            val id = commentsFor ?: return
            if (released) return
            if (commentsTries++ >= COMMENTS_MAX_TRIES) {
                commentsFor = null
                return
            }
            val url = pageUrl.orEmpty()
            // Not on the post's page yet (the old page is still the WebView's): look again soon.
            if (!url.contains(id) && !url.contains("/post/") && !url.contains("/community")) {
                main.postDelayed(this, COMMENTS_STEP_MS)
                return
            }
            val web = browse()
            web.evaluateJavascript("($COMMENTS_JS)(${org.json.JSONObject.quote(id)},$commentsTaps)") { raw ->
                if (released || commentsFor != id) return@evaluateJavascript
                val parts = raw?.trim('"')?.split('|').orEmpty()
                when (parts.firstOrNull()) {
                    "done" -> {
                        // The comment text box is open and has the focus: nothing more is touched (a further touch, made while the
                        // keyboard moves the page, could land on a comment and open its menu). Only the keyboard is asked for.
                        commentsFor = null
                        main.postDelayed({
                            if (released) return@postDelayed
                            web.requestFocus()
                            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(web, 0)
                        }, 200)
                    }
                    "sheet" -> {
                        // A menu (reply, like, dislike, report ...) opened after a touch of ours: the touch hit a comment. The menu
                        // is closed and the comments are left alone, there is no other touch on that place.
                        if (commentsMenus++ >= COMMENTS_MAX_MENUS) {
                            commentsFor = null
                        } else {
                            web.evaluateJavascript(CLOSE_SHEET_JS, null)
                            main.postDelayed(this, COMMENTS_AFTER_TAP_MS)
                        }
                    }
                    "input", "entry" -> {
                        // The place was the same at two looks one after the other and a touch there reaches the box (see [COMMENTS_JS]).
                        if (commentsTaps++ >= COMMENTS_MAX_TAPS) {
                            commentsFor = null
                        } else {
                            tapPage(web, parts)
                            main.postDelayed(this, COMMENTS_AFTER_TAP_MS)
                        }
                    }
                    else -> main.postDelayed(this, COMMENTS_STEP_MS)
                }
            }
        }
    }

    /** A touch (down, then up) on the page at the place [parts] gives: "kind|x|y|ratio", x and y in the page's own pixels. */
    private fun tapPage(web: WebView, parts: List<String>) {
        if (parts.size < 4) return
        val ratio = parts[3].toFloatOrNull() ?: activity.resources.displayMetrics.density
        val x = (parts[1].toFloatOrNull() ?: return) * ratio
        val y = (parts[2].toFloatOrNull() ?: return) * ratio
        val down = SystemClock.uptimeMillis()
        val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        web.dispatchTouchEvent(press)
        press.recycle()
        main.postDelayed({
            if (released) return@postDelayed
            val release = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            web.dispatchTouchEvent(release)
            release.recycle()
        }, 60)
    }

    /** A link inside a post's text, or the video attached to a post: a video plays, a YouTube page opens, another site goes to the phone's browser. */
    override fun onLink(url: String) {
        if (released) return
        commentsFor = null
        if (!UrlUtils.isYouTubeUrl(url)) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                // No browser installed: nothing to open.
            }
            return
        }
        if (!UrlUtils.isVideoUrl(url)) startHold() // a page: the screen stays over the old one until it is on view
        openUrl(url)
    }

    /** A vote or a like made on a post's card: YouTube's command for it is run with the account's cookies, here, with no page opened. */
    override fun onPostCommand(command: String, done: (String?) -> Unit) {
        if (released) return
        val cookie = cookie()
        val known = apiAccess
        pool.execute {
            // Unlike the video menu's actions, the reason is kept: a vote or a like that YouTube refuses is told with it.
            var reason: String? = null
            try {
                val access = known ?: SubsRepository.fetchApiAccess(cookie)
                if (known == null) apiAccess = access
                SubsRepository.runCommand(access, command, cookie)
            } catch (e: Exception) {
                reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            val answer = reason
            main.post { if (!released) done(answer) }
        }
    }

    /** The three dots of a card: the menu of what can be done with the video (or the post), like the YouTube app's. */
    override fun onVideoMenu(video: SubsVideo, anchor: View) {
        if (released) return
        val items = ArrayList<SubsVideoMenu.Item>()
        val isPost = video.post != null
        if (!isPost) {
            items.add(SubsVideoMenu.Item(R.drawable.ic_watch_later_24dp, activity.getString(R.string.subsMenuWatchLater)) {
                saveToWatchLater(video)
            })
            items.add(SubsVideoMenu.Item(R.drawable.ic_bookmark_24dp, activity.getString(R.string.subsMenuPlaylist)) {
                choosePlaylist(video)
            })
            items.add(SubsVideoMenu.Item(R.drawable.ic_download_24dp, activity.getString(R.string.subsMenuDownload)) {
                onDownload("https://m.youtube.com/watch?v=${video.id}", video.title)
            })
        }
        items.add(SubsVideoMenu.Item(R.drawable.ic_share_24dp, activity.getString(R.string.subsMenuShare)) {
            if (isPost) onShare("https://www.youtube.com/post/${video.id}", video.channelName) else onShare("https://youtu.be/${video.id}", video.title)
        })
        val channel = channels.firstOrNull { belongs(video, it) }
        if (channel != null) {
            items.add(SubsVideoMenu.Item(R.drawable.ic_person_remove_24dp, activity.getString(R.string.subsMenuUnsubscribe)) {
                confirmUnsubscribe(channel)
            })
        }
        items.add(SubsVideoMenu.Item(R.drawable.ic_block_24dp, activity.getString(R.string.subsMenuHide)) {
            hide(video)
        })
        SubsVideoMenu(activity, SubsPalette(night)).show(anchor, items)
    }

    private fun toast(message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Runs [work] on a background thread with the key and context of YouTube's API (read from the page when none was
     * kept) and the account's cookies; [done] is told the result on the main thread, or null when it failed.
     */
    private fun <T> withApi(work: (SubsRepository.ApiAccess, String) -> T, done: (T?) -> Unit) {
        val cookie = cookie()
        val known = apiAccess
        pool.execute {
            var result: T? = null
            try {
                val access = known ?: SubsRepository.fetchApiAccess(cookie)
                if (known == null) apiAccess = access
                result = work(access, cookie)
            } catch (e: Exception) {
                // The result stays null: the person is told it did not work.
            }
            val answer = result
            main.post { if (!released) done(answer) }
        }
    }

    private fun saveToWatchLater(video: SubsVideo) {
        withApi({ access, cookie -> SubsRepository.editPlaylist(access, WATCH_LATER, video.id, true, cookie) }) { ok ->
            toast(if (ok == true) R.string.subsWatchLaterAdded else R.string.subsMenuFailed)
        }
    }

    /** "Save to playlist": the account's playlists with a tick on those that hold the video; a tap adds or takes out at once. */
    private fun choosePlaylist(video: SubsVideo) {
        toast(R.string.subsMenuWorking)
        withApi({ access, cookie -> SubsRepository.fetchPlaylists(access, video.id, cookie) }) { list ->
            if (list == null) toast(R.string.subsMenuFailed) else showPlaylists(video, list)
        }
    }

    /** The playlists sheet (YouTube's own look, with "New playlist"); what its taps do on YouTube is decided here. */
    private fun showPlaylists(video: SubsVideo, options: List<SubsRepository.PlaylistOption>) {
        if (activity.isFinishing || activity.isDestroyed) return
        val host = object : SubsPlaylistSheet.Host {
            override fun onToggle(option: SubsRepository.PlaylistOption, add: Boolean, onFailed: () -> Unit) {
                togglePlaylist(video, option, add, onFailed)
            }

            override fun onCreate(title: String, privacy: String, done: (Boolean) -> Unit) {
                createPlaylist(video, title, privacy, done)
            }

            override fun onThumb(option: SubsRepository.PlaylistOption, show: (String) -> Unit) {
                loadPlaylistThumb(option, show)
            }
        }
        SubsPlaylistSheet(activity, SubsPalette(night), host).show(options)
    }

    /** The picture of a playlist that came without one (its first video's), kept until the app closes; "" is kept too. */
    private val playlistThumbs = HashMap<String, String>()

    /** When a playlist's page gave no picture (an empty playlist, or an answer that was not read): not asked again for a while. */
    private val playlistThumbsEmptyAt = HashMap<String, Long>()

    private fun loadPlaylistThumb(option: SubsRepository.PlaylistOption, show: (String) -> Unit, retry: Boolean = true) {
        val known = playlistThumbs[option.id]
        if (!known.isNullOrEmpty()) {
            show(known)
            return
        }
        // An empty answer is kept only for a short while (it used to be kept until the app closed: one answer that was
        // not read left that playlist without a picture for good).
        val emptyAt = playlistThumbsEmptyAt[option.id]
        if (emptyAt != null && System.currentTimeMillis() - emptyAt < PLAYLIST_THUMB_EMPTY_MS) return
        withApi({ access, cookie -> SubsRepository.fetchPlaylistThumb(access, option.id, cookie) }) { url ->
            if (url == null) {
                // The request failed (the network, a busy moment): once more a moment later.
                if (retry && !released) main.postDelayed({ loadPlaylistThumb(option, show, retry = false) }, 1500)
            } else if (url.isNotEmpty()) {
                playlistThumbs[option.id] = url
                playlistThumbsEmptyAt.remove(option.id)
                show(url)
            } else {
                playlistThumbsEmptyAt[option.id] = System.currentTimeMillis()
            }
        }
    }

    /** "Create" of the sheet: a new playlist with the video in it; [done] is told whether it worked. */
    private fun createPlaylist(video: SubsVideo, title: String, privacy: String, done: (Boolean) -> Unit) {
        toast(R.string.subsMenuWorking)
        withApi({ access, cookie -> SubsRepository.createPlaylist(access, title, privacy, video.id, cookie) }) { id ->
            if (id.isNullOrEmpty()) {
                toast(R.string.subsMenuFailed)
                done(false)
            } else {
                Toast.makeText(activity, activity.getString(R.string.subsPlaylistCreated, title), Toast.LENGTH_SHORT).show()
                done(true)
            }
        }
    }

    private fun togglePlaylist(video: SubsVideo, option: SubsRepository.PlaylistOption, add: Boolean, onFailed: () -> Unit) {
        withApi({ access, cookie -> SubsRepository.editPlaylist(access, option.id, video.id, add, cookie) }) { ok ->
            if (ok == true) {
                val message = activity.getString(if (add) R.string.subsPlaylistAdded else R.string.subsPlaylistRemoved, option.title)
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            } else {
                onFailed()
                toast(R.string.subsMenuFailed)
            }
        }
    }

    private fun confirmUnsubscribe(channel: SubsChannel) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.subsUnsubTitle, channel.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.subsMenuUnsubscribe) { _, _ -> unsubscribe(channel) }
            .show()
    }

    private fun unsubscribe(channel: SubsChannel) {
        withApi({ access, cookie ->
            SubsRepository.unsubscribe(access, channel.id, cookie)
            true
        }) { ok ->
            if (ok == true) onUnsubscribed(channel) else toast(R.string.subsMenuFailed)
        }
    }

    /** YouTube took the subscription away: the channel leaves the strip and its videos leave the lists. */
    private fun onUnsubscribed(channel: SubsChannel) {
        Toast.makeText(activity, activity.getString(R.string.subsUnsubDone, channel.name), Toast.LENGTH_SHORT).show()
        val wasChosen = selected?.id == channel.id
        feed = feed.filter { !belongs(it, channel) }
        shortsFeed = shortsFeed.filter { !belongs(it, channel) }
        postsFeed = postsFeed.filter { !belongs(it, channel) }
        channelVideos.remove(channel.id)
        channelShorts.remove(channel.id)
        channelStreams.remove(channel.id)
        channelPosts.remove(channel.id)
        setChannelList(channels.filter { it.id != channel.id }) // also drops the choice when it was this channel
        save()
        val view = screen ?: return
        if (!onSubsPage) return
        view.setChannels(strip, newIds())
        view.setSelected(selected)
        applyVideos(view, toTop = wasChosen)
        view.setLoading(feedLoading)
    }

    /**
     * "Hide": the video leaves every list at once, and stays out (it is remembered on the phone, for this account) until
     * "Undo" is tapped. YouTube is not told: the video stays in YouTube's own page and app.
     */
    private fun hide(video: SubsVideo) {
        if (hiddenIds.add(video.id)) saveHidden()
        refreshList()
        val view = screen ?: return
        Snackbar.make(view, if (video.post != null) R.string.subsPostHidden else R.string.subsHidden, Snackbar.LENGTH_LONG)
            .setAction(R.string.subsUndo) {
                if (hiddenIds.remove(video.id)) saveHidden()
                refreshList()
            }
            .show()
    }

    private fun refreshList() {
        val view = screen ?: return
        if (onSubsPage) applyVideos(view, toTop = false)
    }

    private fun saveHidden() {
        while (hiddenIds.size > MAX_HIDDEN) {
            val oldest = hiddenIds.iterator()
            oldest.next()
            oldest.remove()
        }
        state.edit().putString(KEY_HIDDEN_IDS, hiddenIds.joinToString(",")).apply()
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

    /** "Try again" on the empty screen of a failed read: the same as a pull to refresh, with the spinner of the skeleton. */
    override fun onRetry() {
        val chosen = selected
        if (chosen != null) {
            screen?.setLoading(true)
            loadChannelTabs(chosen, force = true)
        } else if (!feedLoading) {
            reorderOnFeed = true
            if (channels.isEmpty() && !channelsLoading) refreshChannels(cookie()) else refreshFeed()
        }
    }

    /**
     * The back button, asked before the page's own history: it closes the list of every subscription when that is open.
     * Returns true when it did (the press is used up).
     */
    fun onBack(): Boolean {
        val view = screen ?: return false
        if (view.visibility != View.VISIBLE) return false
        return view.onBack()
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
            loadChannelTabs(chosen, force = true)
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
        postsPool.shutdownNow()
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

        /**
         * The Posts chip's own list has no limit but YouTube's end of it; this is only a stop for a list that would never
         * end. A post is bigger than a Short's tile (its pictures are not kept, only their addresses), so it is lower.
         */
        const val MAX_POSTS_FEED = 3000

        /** Pages of the Posts chip's own list in a row with nothing new that are skipped over before the list counts as ended. */
        const val MAX_POSTS_EMPTY_PAGES = 5

        /** Where the Posts chip usually stands in the chips of the Subscriptions page (All, Today, Videos, Shorts, Live, Posts). */
        const val USUAL_POSTS_CHIP = 5

        /** The posts-of-channels list reads this many channels (or next pages) at a time. */
        const val CHAN_POSTS_BATCH = 8

        /** It reads on by itself while the list holds fewer posts than this (so the screen is not left empty). */
        const val CHAN_POSTS_FILL = 12

        /** Batches in a row with no new post after which it waits for the next tap on the chip (about 120 channels). */
        const val CHAN_POSTS_MAX_DRY = 15

        /** "All content" and "Today" read this many batches of channels on their own, then go on at the end of the list. */
        const val CHAN_POSTS_BG_BATCHES = 4

        /** The most batches of channels "All content" and "Today" read between two feed pages that bring a new reading. */
        const val CHAN_POSTS_BG_MAX_BATCHES = 12

        /** After an answer with no posts, YouTube's own Posts chip is not asked again for this long (the channels are read at once). */
        const val POSTS_CHIP_RETRY_MS = 30 * 60 * 1000L

        /** How long what was learned about which channels have posts is kept. */
        const val POSTS_MEMORY_MS = 3 * 24 * 60 * 60 * 1000L
        const val KEY_POSTS_NO = "posts_no"
        const val KEY_POSTS_YES = "posts_yes"
        const val KEY_POSTS_MEMORY_AT = "posts_memory_at"

        /** The list is redrawn at most this often while channels answer one by one. */
        const val POSTS_REFRESH_MS = 600L

        /** Pages of YouTube's own posts read on their own to reach as far back as the feed does. */
        const val POSTS_AUTO_PAGES = 3

        /** How many Shorts the screen puts in one row between the videos (the same number as in SubsScreen). */
        const val SHORTS_PER_SHELF = 8

        /** What a chip's label holds when it is the Posts chip: English, Arabic, French, Spanish, Portuguese, Italian, German, Turkish, Russian, Japanese, Korean. */
        val POSTS_WORDS = listOf("post", "منشور", "publica", "beitr", "gönder", "публикац", "投稿", "게시물")

        /** Only the newest videos are kept on the phone (the screen opens with them, then reads the feed again). */
        const val SAVED_VIDEOS = 120

        /** Pages with nothing new that are skipped over before the feed counts as ended. */
        const val MAX_EMPTY_PAGES = 3
        const val KEY_SEEN_IDS = "seen_ids"
        const val MAX_SEEN = 1500

        /** Videos hidden with the card's menu are remembered up to this many (the oldest are forgotten first). */
        const val KEY_HIDDEN_IDS = "hidden_ids"
        const val MAX_HIDDEN = 1000

        /** The id of the "Watch later" playlist. */
        const val WATCH_LATER = "WL"

        /** How long a playlist whose page gave no picture is left alone before its picture is asked for again. */
        const val PLAYLIST_THUMB_EMPTY_MS = 2 * 60 * 1000L

        /** A video is "new" (blue dot) for a week; one of unknown age counts while it is among the feed's first 40. */
        const val NEW_WINDOW_HOURS = 7 * 24.0
        const val NEW_FALLBACK_COUNT = 40

        /** A chip that lists fewer videos than this asks for more pages of the feed, up to [AUTO_MORE_PAGES] times. */
        const val MIN_FILTERED = 6
        const val AUTO_MORE_PAGES = 3

        /** Shown once per run: why YouTube's own page is shown instead of the app's screen. */
        @Volatile
        var warned = false

        const val COMMENTS_FIRST_MS = 900L
        const val COMMENTS_STEP_MS = 400L
        const val COMMENTS_AFTER_TAP_MS = 700L
        const val COMMENTS_MAX_TRIES = 40
        const val COMMENTS_MAX_TAPS = 3
        const val COMMENTS_MAX_MENUS = 2

        /**
         * What to tap on a post's page to write a comment, found by what the elements are and say, as YouTube's names
         * for them change. [taps] is how many touches were already sent. The answer is one of:
         *  - "done": a comment text box is open and has the focus (nothing more is touched);
         *  - "sheet": a menu with rows is open on view after a touch of ours (a comment was hit): it is to be closed;
         *  - "input|x|y|ratio": a comment text box is open on view; "entry|x|y|ratio": the comments row / "Add a comment" box
         *    to touch first (x and y are in the page's own pixels, ratio is the pixels per one of them). These two are given only
         *    when the place was the same at the last look too (a page that is still moving is not touched) and a touch there
         *    reaches that element and not something else (never a comment: a touch on a comment opens its menu);
         *  - "wait": nothing yet (the page is still loading or was scrolled toward the comments).
         * An answer "input" or "entry" also tells menu-download.js (`__wtNoDl`) that the menu that may follow is not a video's.
         */
        const val COMMENTS_JS = """function(id,taps){try{
var p=location.pathname;
if(location.href.indexOf(id)<0&&p.indexOf('/post/')<0&&p.indexOf('/community')<0)return 'wait';
var W=window.innerWidth,H=window.innerHeight,D=window.devicePixelRatio||1;
function box(e){if(!e)return null;var r=e.getBoundingClientRect();if(r.width<8||r.height<8)return null;
var c=getComputedStyle(e);if(c.visibility==='hidden'||c.display==='none')return null;return r;}
function inView(r){return r.bottom>4&&r.top<H-4&&r.right>0&&r.left<W;}
function out(k,r){return k+'|'+Math.round(r.left+r.width/2)+'|'+Math.round(r.top+r.height/2)+'|'+D;}
var SEARCH=/search|بحث/i;
var NOEDIT='ytm-mobile-topbar-renderer,ytm-searchbox,form[role="search"]';
var ITEM='ytm-comment-thread-renderer,ytm-comment-renderer,ytm-comment-replies-renderer';
var AREA='ytm-comment-simplebox-renderer,[class*="simplebox"],ytm-comments-entry-point-header-renderer,ytm-comments-entry-point-teaser-renderer';
function editable(b){
if(!b||b.nodeType!==1||!b.closest||b.closest(NOEDIT)||b.name==='search_query')return false;
if(SEARCH.test((b.getAttribute('placeholder')||'')+(b.getAttribute('aria-label')||'')))return false;
var ce=b.getAttribute('contenteditable');
return b.tagName==='TEXTAREA'||(b.tagName==='INPUT'&&(!b.type||b.type==='text'))||(ce!==null&&ce!=='false');}
function still(k,el,r){
var s=out(k,r),now=Date.now(),prev=window.__wtCsS;
window.__wtCsS={s:s,t:now};
if(!prev||prev.s!==s||now-prev.t>2500)return 'wait';
var hit=document.elementFromPoint(Math.round(r.left+r.width/2),Math.round(r.top+r.height/2));
var area=el.closest(AREA)||el;
if(!hit||!area.contains(hit)||(hit.closest&&hit.closest(ITEM)))return 'wait';
window.__wtNoDl=now+3000;
return s;}
if(taps>0){
var rows=document.querySelectorAll('ytm-menu-service-item-renderer,ytm-menu-navigation-item-renderer,yt-list-item-view-model,[role="menuitem"]');
for(var m=0;m<rows.length;m++){var row=rows[m];
var rr=row.getBoundingClientRect();
if((!rr.width||!rr.height)&&row.firstElementChild)rr=row.firstElementChild.getBoundingClientRect();
if(rr.width<W*0.5||rr.height<20||!inView(rr))continue;
var tr=false;for(var q=row;q&&q!==document.body;q=q.parentElement){if(getComputedStyle(q).opacity==='0'){tr=true;break;}}
if(tr)continue;
var h=document.elementFromPoint(Math.round(rr.left+rr.width/2),Math.round(rr.top+rr.height/2));
if(h&&row.contains(h)&&!row.closest('[data-wt-dl]'))return 'sheet';}}
if(editable(document.activeElement))return 'done';
var boxes=document.querySelectorAll('textarea,input[type="text"],[contenteditable]');
for(var i=0;i<boxes.length;i++){var b=boxes[i];
if(!editable(b)||b.closest(ITEM))continue;
var r=box(b);if(r&&inView(r))return still('input',b,r);}
var sel=['ytm-comment-simplebox-renderer','[class*="simplebox"]','ytm-comments-entry-point-header-renderer','ytm-comments-entry-point-teaser-renderer'];
var t=null;
for(var j=0;j<sel.length&&!t;j++){var f=document.querySelector(sel[j]);if(f&&box(f)&&!f.closest(ITEM))t=f;}
if(!t){var bs=document.querySelectorAll('button,[role="button"]');
for(var k=0;k<bs.length;k++){var a=(bs[k].getAttribute('aria-label')||'');
if(a.length&&a.length<60&&/comment|تعليق/i.test(a)&&box(bs[k])&&!bs[k].closest(ITEM)){t=bs[k];break;}}}
if(t){var r2=box(t);
if(!inView(r2)||r2.top<H*0.15||r2.bottom>H*0.9){t.scrollIntoView({block:'center'});return 'wait';}
return still('entry',t,r2);}
window.__wtCs=(window.__wtCs||0)+1;if(window.__wtCs<=6)window.scrollBy(0,Math.round(H*0.7));
return 'wait';}catch(e){return 'wait';}}"""

        /** Closes the menu that is open on the page the way a finger would: a touch on the dimmed page above it, then Escape. */
        const val CLOSE_SHEET_JS = """(function(){try{
var el=document.elementFromPoint(Math.round(window.innerWidth/2),8);
if(el&&!el.closest('a,button,input,[role="button"],[role="menuitem"]'))el.click();
}catch(e){}
try{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}));}catch(e){}
return 'ok';})()"""

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
