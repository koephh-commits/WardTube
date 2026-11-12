// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mwmods.wardtube.util.UrlUtils

/**
 * A "Download video" row in the menu YouTube opens from the three dots beside a video.
 *
 * The page script (`assets/wardtube/menu-download.js`) finds which video the tapped button belongs to, adds the row to the
 * menu and, when the row is tapped, calls [Bridge.download] with the video's id. The app builds the address itself
 * from that id (a page cannot hand it any other address), and [onDownload] starts the usual download flow.
 */
class MenuDownload(
    private val webView: WebView,
    private val onDownload: (url: String, title: String?) -> Unit,
) {
    /** True when the script runs by itself at the start of every YouTube page; false when [injectNow] must be used. */
    var documentStart = false
        private set

    private val script: String? = runCatching {
        webView.context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()

    /** Call once, before the first page is loaded. */
    fun install() {
        val js = script ?: return
        webView.addJavascriptInterface(Bridge(), JS_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, js, ORIGINS)
                documentStart = true
            }
        }
    }

    /** For a WebView too old to run the script at the start of a page: put it in the page that is showing now. */
    fun injectNow() {
        if (documentStart) return
        val js = script ?: return
        if (!UrlUtils.isYouTubeUrl(webView.url)) return
        webView.evaluateJavascript(js, null)
    }

    inner class Bridge {
        private val main = Handler(Looper.getMainLooper())

        /** [kind] is "video" or "shorts"; [id] is YouTube's 11-character video id. */
        @JavascriptInterface
        fun download(kind: String?, id: String?, title: String?) {
            val videoId = id?.takeIf { ID.matches(it) } ?: return
            val url = if (kind == "shorts") {
                "https://m.youtube.com/shorts/$videoId"
            } else {
                "https://m.youtube.com/watch?v=$videoId"
            }
            val name = title?.trim()?.take(200)?.takeUnless { it.isEmpty() }
            main.post { onDownload(url, name) }
        }
    }

    private companion object {
        const val ASSET = "wardtube/menu-download.js"
        const val JS_NAME = "WardTubeMenuDl"
        val ID = Regex("[A-Za-z0-9_-]{11}")
        val ORIGINS = setOf("https://*.youtube.com", "https://youtube.com") // the same origins as PageScripts
    }
}
