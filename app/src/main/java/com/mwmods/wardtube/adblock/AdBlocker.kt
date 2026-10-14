// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.adblock

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mwmods.wardtube.Prefs
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network-level ad blocking, in two layers:
 *
 *  1. Built-in rules for YouTube's own ad endpoints (assets/adblock/hosts.txt and url_patterns.txt),
 *     which the public lists do not cover.
 *  2. EasyList and Liste AR (see [FilterListManager]), downloaded in the background and refreshed
 *     every few days.
 *
 * Cosmetic hiding and in-player ad skipping are done by the injected page script
 * (see PageScripts and assets/wardtube/wardtube.js).
 *
 * One instance serves the whole app ([shared]): the YouTube screen and the browser screen use the same rules, so the
 * filter lists are parsed and kept in memory only once. The network rules work for every site; the cosmetic rules
 * are compiled for YouTube only (see FilterEngine).
 */
class AdBlocker(context: Context, private val prefs: Prefs) {

    private val appContext = context.applicationContext
    private val filterLists = FilterListManager(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wardtube-filters").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val counter = AtomicInteger()
    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var lastAttemptAt = 0L

    private val blockedHosts: Set<String>
    private val blockedPatterns: List<String>
    private val builtinCss: String

    @Volatile
    private var engine: FilterEngine? = null

    /** Host of the page being shown, for third-party checks (callers that do not say which WebView they serve). */
    @Volatile
    private var pageHost: String? = null

    /** Host of the page of each WebView that identifies itself (the browser screen), so two screens never mix. */
    private val pageHosts = Collections.synchronizedMap(WeakHashMap<Any, String>())

    private val started = AtomicBoolean(false)

    /** CSS that hides ad elements: the built-in rules plus the lists' rules for YouTube. */
    @Volatile
    var cosmeticCss: String = ""
        private set

    /** Requests blocked since the app started. */
    val blockedCount: Int get() = counter.get()

    private val filterListeners = CopyOnWriteArrayList<() -> Unit>()

    /** [listener] runs on the main thread whenever the filter lists have been (re)loaded. Remove it when its screen goes. */
    fun addOnFiltersChanged(listener: () -> Unit) {
        filterListeners.addIfAbsent(listener)
    }

    fun removeOnFiltersChanged(listener: () -> Unit) {
        filterListeners.remove(listener)
    }

    init {
        val assets = appContext.assets
        blockedHosts = readRules(assets.open("adblock/hosts.txt").bufferedReader().use { it.readLines() })
            .map { it.lowercase(Locale.ROOT) }.toHashSet()
        blockedPatterns = readRules(assets.open("adblock/url_patterns.txt").bufferedReader().use { it.readLines() })
        builtinCss = assets.open("adblock/cosmetic.css").bufferedReader().use { it.readText() }
        cosmeticCss = builtinCss
    }

    /** Loads the stored lists, then downloads fresh ones if they are older than four days. */
    fun start() {
        // Shared by several screens: the lists are loaded once, whichever screen asks first.
        if (!started.compareAndSet(false, true)) return
        val stale = isStale()
        if (stale) lastAttemptAt = System.currentTimeMillis()
        worker.execute {
            rebuildEngine()
            if (stale) refreshWithGuard()
        }
    }

    /**
     * Automatic update: downloads fresh lists when the stored ones are four days old or more.
     * Called when the app comes to the foreground, so it also works for an app that stays open for days.
     * A failed attempt (for example while offline) is retried at most every few hours.
     */
    fun refreshIfStale() {
        if (!isStale()) return
        if (System.currentTimeMillis() - lastAttemptAt < RETRY_INTERVAL_MS) return
        lastAttemptAt = System.currentTimeMillis()
        worker.execute { refreshWithGuard() }
    }

    private fun isStale() = System.currentTimeMillis() - prefs.filtersUpdatedAt > UPDATE_INTERVAL_MS

    private fun refreshWithGuard() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            downloadAndRebuild()
        } finally {
            refreshing.set(false)
        }
    }

    /** Downloads the lists now. [onDone] runs on the main thread with (success, rule count). */
    fun updateNow(onDone: (Boolean, Int) -> Unit) {
        worker.execute {
            val ok = downloadAndRebuild()
            val count = engine?.ruleCount ?: 0
            mainHandler.post { onDone(ok, count) }
        }
    }

    /**
     * Called on a WebView background thread for every request. [page] is the WebView the request comes from; a screen
     * that has its own WebView passes it so its page host is not mixed with another screen's (the YouTube screen
     * leaves it out).
     */
    fun shouldBlock(request: WebResourceRequest, page: Any? = null): Boolean {
        val uri = request.url ?: return false
        if (request.isForMainFrame) {
            val host = uri.host?.lowercase(Locale.ROOT)
            when {
                page == null -> pageHost = host
                host != null -> pageHosts[page] = host
                else -> pageHosts.remove(page)
            }
            return false
        }
        if (!prefs.adBlock) return false
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val url = uri.toString().lowercase(Locale.ROOT)

        val blocked = isBuiltinBlocked(host, url) || isBlockedByLists(request, uri, host, url, page)
        if (blocked) counter.incrementAndGet()
        return blocked
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    // ---- Built-in rules ----

    private fun isBuiltinBlocked(host: String, url: String): Boolean {
        var candidate = host
        while (true) {
            if (candidate in blockedHosts) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) break
            candidate = candidate.substring(dot + 1)
        }
        return blockedPatterns.any { url.contains(it) }
    }

    // ---- Filter lists ----

    private fun isBlockedByLists(request: WebResourceRequest, uri: Uri, host: String, url: String, source: Any?): Boolean {
        val engine = engine ?: return false
        if (url.length > MAX_URL_LENGTH) return false
        val page = if (source != null) pageHosts[source] else pageHost
        val thirdParty = page != null && registrableDomain(page) != registrableDomain(host)
        val type = ResourceType.infer(uri.path.orEmpty().lowercase(Locale.ROOT), header(request, "accept"))
        return engine.shouldBlock(RequestContext(url, host, page, thirdParty, type))
    }

    private fun downloadAndRebuild(): Boolean {
        val results = filterLists.lists.map { filterLists.download(it) }
        if (results.all { it }) prefs.filtersUpdatedAt = System.currentTimeMillis()
        if (results.any { it }) rebuildEngine()
        return results.all { it }
    }

    private fun rebuildEngine() {
        try {
            val builder = FilterEngine.Builder()
            var loaded = false
            for (list in filterLists.lists) {
                val reader = filterLists.open(list) ?: continue
                try {
                    reader.use { builder.addList(it) }
                    loaded = true
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Could not read ${list.name}", e)
                }
            }
            if (!loaded) return
            val built = builder.build()
            engine = built
            cosmeticCss = builtinCss + "\n" + built.cosmeticCss
            mainHandler.post { filterListeners.forEach { it() } }
        } catch (e: OutOfMemoryError) {
            // Very small devices: keep the built-in rules rather than crash.
            Log.w(TAG, "Not enough memory for the filter lists", e)
        }
    }

    private fun header(request: WebResourceRequest, name: String): String? {
        val headers = request.requestHeaders ?: return null
        for ((key, value) in headers) if (key.equals(name, ignoreCase = true)) return value
        return null
    }

    /** Good enough without a public-suffix list: the last two labels, or three for co.uk-style domains. */
    private fun registrableDomain(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val tld = labels[labels.size - 1]
        val second = labels[labels.size - 2]
        val keep = if (tld.length == 2 && second in SECOND_LEVEL) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    private fun readRules(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    companion object {
        @Volatile
        private var instance: AdBlocker? = null

        /** The app's single ad blocker, created on first use. */
        fun shared(context: Context, prefs: Prefs): AdBlocker =
            instance ?: synchronized(this) {
                instance ?: AdBlocker(context, prefs).also { instance = it }
            }

        private const val TAG = "AdBlocker"
        private const val UPDATE_INTERVAL_MS = 4L * 24 * 60 * 60 * 1000
        private const val RETRY_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val MAX_URL_LENGTH = 4096
        private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne")
    }
}
