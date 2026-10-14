// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.mwmods.wardtube.adblock.AdBlocker
import com.mwmods.wardtube.util.UrlUtils
import java.util.Locale

enum class ErrorKind { NETWORK_CHANGED, OFFLINE, OTHER }

/** What the WebViewClient reports back to the activity. */
interface PageEvents {
    /** A new page started loading. */
    fun onPageStarted(url: String?) {}

    /** The page or its URL changed (also fires for YouTube's in-page navigation). */
    fun onPageChanged(url: String?)

    fun onLoadError(kind: ErrorKind, description: String)

    fun onRenderProcessGone()

    /**
     * A YouTube page is about to load in the main frame of this WebView (a tapped link, a redirect...).
     * Return true to handle it elsewhere: the load is then cancelled.
     */
    fun onInternalNavigation(url: String): Boolean = false
}

class WardTubeWebViewClient(
    private val adBlocker: AdBlocker,
    private val scripts: PageScripts,
    private val events: PageEvents,
) : WebViewClient() {

    // ---- Ad blocking ----

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
        if (request != null && adBlocker.shouldBlock(request)) adBlocker.emptyResponse() else null

    // ---- Navigation: YouTube stays in the app, everything else opens in the browser ----

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        return handleNavigation(view, uri, request.isForMainFrame)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        return handleNavigation(view, Uri.parse(url), true)
    }

    private fun handleNavigation(view: WebView?, uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme in INTERNAL_SCHEMES) return false
        if (scheme != "http" && scheme != "https") return true // intent://, market:// ...: ignore

        val host = uri.host?.lowercase(Locale.ROOT)
        if (host != null && isInternalHost(host)) return mainFrame && events.onInternalNavigation(uri.toString())

        try {
            view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // No browser installed
        }
        return true
    }

    private fun isInternalHost(host: String): Boolean = UrlUtils.isInternalHost(host)

    // ---- Page lifecycle ----

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.let { scripts.injectFallback(it) }
        events.onPageStarted(url)
        events.onPageChanged(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.let { scripts.injectFallback(it) }
        events.onPageChanged(url)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        events.onPageChanged(url)
    }

    // ---- Errors ----

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) reportError(error?.description?.toString().orEmpty())
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) reportError(description.orEmpty())
    }

    private fun reportError(description: String) {
        val kind = when {
            "NETWORK_CHANGED" in description -> ErrorKind.NETWORK_CHANGED
            "NAME_NOT_RESOLVED" in description || "INTERNET_DISCONNECTED" in description -> ErrorKind.OFFLINE
            else -> ErrorKind.OTHER
        }
        events.onLoadError(kind, description)
    }

    /** The renderer was killed (usually low memory on small phones). Let the activity rebuild the WebView. */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        events.onRenderProcessGone()
        return true
    }

    private companion object {
        val INTERNAL_SCHEMES = setOf("about", "data", "blob", "javascript")
    }
}
