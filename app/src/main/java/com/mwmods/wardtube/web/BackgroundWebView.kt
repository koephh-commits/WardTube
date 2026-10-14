// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * A WebView that stays "visible" to Chromium while the app is in the background.
 *
 * Newer Android/WebView versions hide the page (and suspend or throttle its media and timers) as soon
 * as the window is no longer visible; spoofing document.visibilityState in JavaScript is not enough.
 * While [keepVisible] is true the window visibility change is not forwarded, so the video keeps playing.
 */
class BackgroundWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    var keepVisible: Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (keepVisible) View.VISIBLE else visibility)
    }
}
