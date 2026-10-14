// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import android.webkit.CookieManager
import android.webkit.WebView

object CookieHelper {

    fun setAccepting(webView: WebView, accept: Boolean) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(accept)
        manager.setAcceptThirdPartyCookies(webView, accept)
    }

    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
    }
}
