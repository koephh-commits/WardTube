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
    /** The floating "Download" button over a video's three-dots menu was pressed (see [downloadFromMenu]). [title] may be empty. Runs on the main thread. */
    private val onMenuDownload: (url: String, title: String?) -> Unit = { _, _ -> },
) {
    private val main = Handler(Looper.getMainLooper())
    private var lastEnterPipAt = 0L
    private var lastMenuDownloadAt = 0L

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
     * The person pressed the floating "Download" button that shows over the menu of a video's three dots (wardtube.js, "A floating
     * "Download" button over a video's three-dots menu"): [url] is the link of the card's video and [title] the card's heading. The
     * app answers with the sheet that a long press on the card opens ([VideoLongPress.openFromPage] decides whether it can).
     * Any frame of a page can call this, but all it can do is open that sheet for a link that VideoLinks.parse accepts (a YouTube
     * video, a Short, a live page or a youtu.be link; the title is cut to a line there), and the sheet downloads nothing by itself:
     * the person still taps "Download video". Two calls less than 0.6 s apart count as one.
     */
    @JavascriptInterface
    fun downloadFromMenu(url: String?, title: String?) {
        if (url == null || url.length > MAX_LINK_LENGTH) return
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(this) {
            if (now - lastMenuDownloadAt < MENU_DOWNLOAD_GAP_MS) return
            lastMenuDownloadAt = now
        }
        main.post { onMenuDownload(url, title) }
    }

    private companion object {
        /** A second tap on the button this soon after the first is the same tap (a double click), not another request. */
        const val ENTER_PIP_GAP_MS = 1000L

        /** The same, for the Download button of the three-dots menu. */
        const val MENU_DOWNLOAD_GAP_MS = 600L

        /** No video link of a page is longer than this; anything beyond is not one. */
        const val MAX_LINK_LENGTH = 2048

        /** No real picture is wider or higher; anything beyond is a wrong value. */
        const val MAX_VIDEO_SIDE = 16384
    }
}
