// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

/**
 * The words a search engine offers while a text is being typed (the list Chrome shows under its address bar).
 *
 * The typed text is sent to **the engine the person chose** (the one shown at the end of the link field) and to no
 * other: an engine with no public suggestion service here simply gets no suggestions, instead of the text going to
 * another company. A text that looks like an address is never sent. Nothing is sent until the field has the focus and
 * has text, and the answers are not stored (only the words the person actually searches are, in [SearchHistory]).
 */
object SearchSuggest {

    /** The suggestion service of each engine (by [SearchEngine.id]), up to the typed text; the answer is OpenSearch JSON. */
    private val endpoints: Map<String, String> = mapOf(
        "google" to "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&q=",
        "youtube" to "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&ie=utf-8&oe=utf-8&q=",
        "bing" to "https://api.bing.com/osjson.aspx?query=",
        "duckduckgo" to "https://duckduckgo.com/ac/?type=list&q=",
        "brave" to "https://search.brave.com/api/suggest?q=",
        "yandex" to "https://suggest.yandex.com/suggest-ff.cgi?part=",
    )

    fun supports(engine: SearchEngine): Boolean = engine.id in endpoints

    /**
     * The suggestions for [query], best first; empty when the engine has none, the network fails or the answer is not
     * what was expected. Blocks: call it from a background thread.
     */
    fun fetch(engine: SearchEngine, query: String, language: String): List<String> {
        val base = endpoints[engine.id] ?: return emptyList()
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        var url = base + Uri.encode(text)
        if (engine.id == "google" || engine.id == "youtube") url += "&hl=" + Uri.encode(language)
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            // Read as UTF-8 whatever the header says (some engines label Arabic answers wrongly).
            val body = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            parse(body, text)
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    /** OpenSearch JSON: ["typed text", ["suggestion", ...], ...]. The typed text itself and repeats are left out. */
    internal fun parse(body: String, typed: String): List<String> {
        val root = JSONArray(body.trim())
        val array = root.optJSONArray(1) ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optString(i, "").trim()
            if (item.isEmpty() || item.equals(typed, ignoreCase = true)) continue
            if (out.none { it.equals(item, ignoreCase = true) }) out.add(item)
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    private const val TIMEOUT_MS = 3500
    private const val MAX_RESULTS = 6
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
