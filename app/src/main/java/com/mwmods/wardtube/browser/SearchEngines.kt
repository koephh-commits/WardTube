// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.net.Uri

/** A search engine the browser screen can search with (see [SearchEngines]). */
class SearchEngine(
    /** Stored in the preferences: never change the id of an engine that was released. */
    val id: String,
    val name: String,
    /** The site's host, shown under the name in the picker. */
    val host: String,
    /** One or two letters drawn in the engine's colored badge. */
    val badge: String,
    /** The badge's color (ARGB). */
    val color: Int,
    val group: Group,
    /** The address a search is sent to, up to the query. */
    private val searchUrl: String,
) {
    /** Declaration order is the order of the sections in the picker. */
    enum class Group { WEB, VIDEO }

    fun urlFor(query: String): String = searchUrl + Uri.encode(query)
}

/**
 * The engines offered by the browser screen's picker. The first one is the default (and the fallback for an id that
 * is not known any more); the order here is the order the person sees.
 */
object SearchEngines {

    val all: List<SearchEngine> = listOf(
        SearchEngine(
            "google", "Google", "google.com", "G", 0xFF4285F4.toInt(),
            SearchEngine.Group.WEB, "https://www.google.com/search?q=",
        ),
        SearchEngine(
            "bing", "Bing", "bing.com", "B", 0xFF008373.toInt(),
            SearchEngine.Group.WEB, "https://www.bing.com/search?q=",
        ),
        SearchEngine(
            "duckduckgo", "DuckDuckGo", "duckduckgo.com", "D", 0xFFDE5833.toInt(),
            SearchEngine.Group.WEB, "https://duckduckgo.com/?q=",
        ),
        SearchEngine(
            "brave", "Brave Search", "search.brave.com", "Br", 0xFFFB542B.toInt(),
            SearchEngine.Group.WEB, "https://search.brave.com/search?q=",
        ),
        SearchEngine(
            "yahoo", "Yahoo", "search.yahoo.com", "Y!", 0xFF6001D2.toInt(),
            SearchEngine.Group.WEB, "https://search.yahoo.com/search?p=",
        ),
        SearchEngine(
            "yandex", "Yandex", "yandex.com", "Ya", 0xFFE53935.toInt(),
            SearchEngine.Group.WEB, "https://yandex.com/search/?text=",
        ),
        SearchEngine(
            "ecosia", "Ecosia", "ecosia.org", "E", 0xFF2E8B57.toInt(),
            SearchEngine.Group.WEB, "https://www.ecosia.org/search?q=",
        ),
        SearchEngine(
            "startpage", "Startpage", "startpage.com", "S", 0xFF5266D9.toInt(),
            SearchEngine.Group.WEB, "https://www.startpage.com/sp/search?query=",
        ),
        SearchEngine(
            "qwant", "Qwant", "qwant.com", "Q", 0xFF3B6FE0.toInt(),
            SearchEngine.Group.WEB, "https://www.qwant.com/?q=",
        ),
        SearchEngine(
            "youtube", "YouTube", "youtube.com", "YT", 0xFFE62118.toInt(),
            SearchEngine.Group.VIDEO, "https://m.youtube.com/results?search_query=",
        ),
        SearchEngine(
            "dailymotion", "Dailymotion", "dailymotion.com", "DM", 0xFF0A6DD8.toInt(),
            SearchEngine.Group.VIDEO, "https://www.dailymotion.com/search/",
        ),
    )

    /** The engine saved under [id]; Google when nothing was chosen yet or the id is unknown. */
    fun byId(id: String?): SearchEngine = all.firstOrNull { it.id == id } ?: all.first()
}
