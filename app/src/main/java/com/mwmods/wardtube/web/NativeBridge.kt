// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.mwmods.wardtube.media.MediaPlayerService
import com.mwmods.wardtube.util.UrlUtils

/**
 * What the page script (assets/wardtube/wardtube.js) can ask the app to do. Everything is checked here again,
 * because any frame of a page can call it: only YouTube links are accepted.
 */
class NativeBridge(
    private val onOpenVideo: (String) -> Unit,
    private val onOpenPage: (String) -> Unit,
    /** True while the player is shown as the small floating box. Called on a WebView thread, so it must be thread-safe. */
    private val isMiniNow: () -> Boolean = { false },
    /** The shape (width / height) of the system's picture-in-picture window while the player is that window, else 0. Thread-safe too. */
    private val pipAspectNow: () -> Float = { 0f },
    /** What the player's page says is playing (see [setPipVideo]): the picture's size, 0 x 0 when nothing that could be shown. Runs on the main thread. */
    private val onPipVideo: (Int, Int) -> Unit = { _, _ -> },
    /** May the page start its video again after a pause nobody asked for (see [canAutoResume])? Called on a WebView thread. */
    private val canAutoResumeNow: () -> Boolean = { true },
    /** The person pressed the picture-in-picture button on the player's page (see [enterPip]). Runs on the main thread. */
    private val onEnterPip: () -> Unit = {},
    /** Does this phone have picture in picture (Android 8+, the feature not switched off)? Called on a WebView thread. */
    private val pipSupportedNow: () -> Boolean = { false },
    /** The "Download video" row that menu-download.js put in the menu of a card's three dots was tapped (see [menuDownload]). [title] may be null. Runs on the main thread. */
    private val onMenuRowDownload: (url: String, title: String?) -> Unit = { _, _ -> },
    /** The same row (or the button beside the buttons under the video) of a watch page, for the video that page shows (see [menuDownloadCurrent]). Runs on the main thread. */
    private val onMenuRowDownloadCurrent: (url: String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var lastEnterPipAt = 0L
    private var lastMenuRowAt = 0L

    /** A video link was tapped on a browsing page: play it in the player instead of in the page. */
    @JavascriptInterface
    fun openVideo(url: String?) {
        if (url != null && UrlUtils.isVideoUrl(url)) main.post { onOpenVideo(url) }
    }

    /** A link to another YouTube page (channel, hashtag, search...) was tapped in the player: show it in the browser WebView. */
    @JavascriptInterface
    fun openPage(url: String?) {
        if (url != null && UrlUtils.isYouTubeUrl(url) && !UrlUtils.isVideoUrl(url)) main.post { onOpenPage(url) }
    }

    /**
     * Is the player a small box right now? The player page asks on every tick (and when its script starts), so a page
     * that finished loading after the user left the video never misses the app's "now it is a box": the answer does
     * not depend on when the app's message happened to arrive.
     */
    @JavascriptInterface
    fun isMini(): Boolean = isMiniNow()

    /**
     * The shape (width / height) the player's page has to give its player while the app is the picture-in-picture window,
     * 0 when it is not. The app decides it and the page fits itself to it, so nothing has to be measured in the page (see
     * PlayerHost.enterPip and the "Picture in picture" part of wardtube.js). Asked on every tick and when the script starts,
     * for the same reason as [isMini].
     */
    @JavascriptInterface
    fun pipAspect(): Float = pipAspectNow()

    /**
     * A video of the page started to play with sound: the built-in player of the Library (a video or a song that plays from there)
     * pauses instead of playing over it. A frame of the page can call this, but all it does is pause that player.
     */
    @JavascriptInterface
    fun mediaPlaying() {
        MediaPlayerService.pauseForOtherMedia()
    }

    /**
     * The player's page says what plays, at once, on every play / pause / end / resize of its video (the way NouTube's page
     * arms its picture-in-picture window): the picture's size in pixels while a video that the little window could show is
     * playing, 0 x 0 when none is. The app arms the window from it, so leaving the app right after a video starts (or
     * right after it was paused) is handled correctly instead of waiting for the next poll.
     */
    @JavascriptInterface
    fun setPipVideo(width: Int, height: Int) {
        val w = width.coerceIn(0, MAX_VIDEO_SIDE)
        val h = height.coerceIn(0, MAX_VIDEO_SIDE)
        main.post { onPipVideo(w, h) }
    }

    /**
     * Asked by the picture-in-picture guard of the page (wardtube.js, pipGuardTick) before it starts a video again that
     * YouTube paused by itself (the way NouTube's background guard asks NouTubeI.canAutoResume): false while a call is on
     * or ringing, because that pause is the system's and the video must stay paused until the person plays it.
     */
    @JavascriptInterface
    fun canAutoResume(): Boolean = canAutoResumeNow()

    /**
     * Has this phone picture in picture? The player's page asks once before it puts its picture-in-picture button on the
     * player (wardtube.js, "Picture in picture: a button in the player"), so a phone without the feature has no dead button.
     */
    @JavascriptInterface
    fun pipSupported(): Boolean = pipSupportedNow()

    /**
     * The person pressed the picture-in-picture button on the player: the app shrinks into the system's little window now, the
     * same window that opens by itself when the person leaves with Home (MainActivity.enterPipFromButton decides whether it can).
     * Any frame of a page can call this, but all it can do is open that window for a video that is already open in the player
     * (nothing happens without one), and two calls less than a second apart count as one.
     */
    @JavascriptInterface
    fun enterPip() {
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(this) {
            if (now - lastEnterPipAt < ENTER_PIP_GAP_MS) return
            lastEnterPipAt = now
        }
        main.post { onEnterPip() }
    }

    /**
     * The person tapped the "Download video" row that the page script adds to YouTube's menu of a video's three dots
     * (assets/wardtube/menu-download.js). [kind] is "video" or "shorts" and [id] the 11-character video id: the app builds the
     * address itself from them (a page cannot hand it any other address), and the usual download flow starts (MainActivity.startMenuDownload).
     * Any frame of a page can call this, but all it can do is open that flow, which still asks the person what to download,
     * for a YouTube video id; two calls less than 0.6 s apart count as one.
     */
    @JavascriptInterface
    fun menuDownload(kind: String?, id: String?, title: String?) {
        val url = videoUrl(kind, id) ?: return
        if (!menuRowGapPassed()) return
        val name = title?.trim()?.take(MAX_TITLE_LENGTH)?.takeUnless { it.isEmpty() }
        main.post { onMenuRowDownload(url, name) }
    }

    /**
     * The same, for the video the watch page itself shows: the row of its menu, or the "Download" button that the page script
     * puts beside the like / share buttons under the video when that row has no three dots (MainActivity.startDownloadFlow: it
     * asks the player whether the video is live first). Same checks as [menuDownload].
     */
    @JavascriptInterface
    fun menuDownloadCurrent(kind: String?, id: String?) {
        val url = videoUrl(kind, id) ?: return
        if (!menuRowGapPassed()) return
        main.post { onMenuRowDownloadCurrent(url) }
    }

    /** The address of the video whose id the page gave; null when [id] is not a YouTube video id. */
    private fun videoUrl(kind: String?, id: String?): String? {
        val videoId = id?.takeIf { VIDEO_ID.matches(it) } ?: return null
        return if (kind == "shorts") "https://m.youtube.com/shorts/$videoId" else "https://m.youtube.com/watch?v=$videoId"
    }

    /** False when the row was tapped less than [MENU_DOWNLOAD_GAP_MS] ago (a double click); otherwise notes this tap. */
    private fun menuRowGapPassed(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(this) {
            if (now - lastMenuRowAt < MENU_DOWNLOAD_GAP_MS) return false
            lastMenuRowAt = now
        }
        return true
    }

    private companion object {
        /** A second tap on the button this soon after the first is the same tap (a double click), not another request. */
        const val ENTER_PIP_GAP_MS = 1000L

        /** Two taps on the "Download video" row of the three-dots menu this close count as one. */
        const val MENU_DOWNLOAD_GAP_MS = 600L

        /** A card's title is cut to this many characters (the page script cuts it too). */
        const val MAX_TITLE_LENGTH = 200

        /** YouTube's video id. */
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** No real picture is wider or higher; anything beyond is a wrong value. */
        const val MAX_VIDEO_SIDE = 16384
    }
}
