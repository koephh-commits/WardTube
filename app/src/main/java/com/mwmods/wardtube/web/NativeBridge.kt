// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
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
}
