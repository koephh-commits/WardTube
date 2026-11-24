// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.adblock.AdBlocker
import org.json.JSONObject

/**
 * Injects assets/wardtube/wardtube.js into YouTube pages, followed by assets/wardtube/menu-download.js (the "Download video"
 * row of the menu of a video's three dots, and the "Download" button beside the buttons under the video of a watch page): one
 * script, so the second one reaches every page, and every WebView that cannot run scripts at the start of a page, the same way.
 *
 * On WebViews that support document-start scripts the script runs before the page's own
 * scripts (needed to strip ads from the first player response). Older WebViews get it
 * injected when the page starts loading, which is a bit late but still handles CSS and the ad skipper.
 */
class PageScripts(
    context: Context,
    private val prefs: Prefs,
    private val adBlocker: AdBlocker,
    /** "browse": taps on video links open the player. "player": taps on other YouTube pages open the browser WebView. */
    private val route: String? = null,
) {

    private val script: String
    private val menuScript: String
    private val baseCss: String
    private var handle: ScriptHandler? = null

    private val supportsDocumentStart: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    init {
        val assets = context.applicationContext.assets
        script = assets.open("wardtube/wardtube.js").bufferedReader().use { it.readText() }
        baseCss = assets.open("wardtube/base.css").bufferedReader().use { it.readText() }
        // Without this file the page still works, it only has no download row of its own.
        menuScript = runCatching {
            assets.open("wardtube/menu-download.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    /** Registers the script for every future page load. Safe to call again after a setting changes. */
    fun install(webView: WebView) {
        if (!supportsDocumentStart) return
        try {
            handle?.remove()
        } catch (e: Exception) {
            // The WebView that registered it is already gone.
        }
        handle = null
        handle = WebViewCompat.addDocumentStartJavaScript(webView, source(), ORIGINS)
    }

    /** Forgets the registration of a WebView that is being destroyed. */
    fun forget() {
        handle = null
    }

    /** Fallback for WebViews without document-start scripts. The script ignores repeated runs. */
    fun injectFallback(webView: WebView) {
        if (!supportsDocumentStart) webView.evaluateJavascript(source(), null)
    }

    /** Applies the current toggles to the open page and to future page loads. */
    fun applySettings(webView: WebView) {
        webView.evaluateJavascript("window.__wardtube&&window.__wardtube.configure(${flags()})", null)
        install(webView)
    }

    /** Pushes the current ad-hiding CSS to the open page (after the filter lists were loaded). */
    fun refreshCss(webView: WebView) {
        val update = JSONObject().put("adCss", adBlocker.cosmeticCss)
        webView.evaluateJavascript("window.__wardtube&&window.__wardtube.configure($update)", null)
    }

    private fun flags(): JSONObject =
        JSONObject().put("adblock", prefs.adBlock).put("background", prefs.backgroundPlay).put("h264", prefs.preferH264)

    private fun source(): String {
        val config = flags().put("baseCss", baseCss).put("adCss", adBlocker.cosmeticCss)
        if (route != null) config.put("route", route)
        val main = "window.__wardtubeConfig=$config;\n$script"
        return if (menuScript.isEmpty()) main else "$main\n;\n$menuScript"
    }

    private companion object {
        val ORIGINS = setOf("https://*.youtube.com", "https://youtube.com")
    }
}
