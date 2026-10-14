// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.webkit.WebView

/**
 * The WebViews of the main screen. Pages you browse (home, subscriptions, search...) live in [browse]; a video
 * plays in a second WebView, the player, which is full screen or a small floating box (the mini player).
 */
interface WebTargets {
    /** The WebView that shows YouTube's pages. */
    val browse: WebView

    /** The page being looked at: the player while a video is full screen, otherwise [browse]. */
    val current: WebView

    /** The player while a video is open (full screen or in the mini player), otherwise null. */
    val video: WebView?

    /** Every WebView that exists right now. */
    val all: List<WebView>

    /** Opens a link: a video plays in the player, anything else loads in [browse]. */
    fun openUrl(url: String)
}
