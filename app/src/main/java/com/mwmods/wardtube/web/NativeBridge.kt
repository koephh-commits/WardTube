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
) {
    private val main = Handler(Looper.getMainLooper())

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
}
