// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** One visited page: its address, its title (empty until the page tells it) and when it was last visited. */
data class HistoryEntry(val url: String, val title: String, val time: Long)

/**
 * The pages opened in the browser screen, newest first, one entry per address (visiting a page again moves it to the
 * top). Kept in a small SharedPreferences file of its own as a JSON list, at most [MAX_ENTRIES] long.
 *
 * One instance for the whole app ([get]): the browser screen records into it and the history screen reads and
 * deletes from it.
 */
class BrowserHistory private constructor(context: Context) {

    private val sp: SharedPreferences = context.applicationContext.let {
        it.getSharedPreferences("${it.packageName}_browser_history", Context.MODE_PRIVATE)
    }

    private val entries: MutableList<HistoryEntry> = load()

    /** A copy of the list, newest first. */
    @Synchronized
    fun all(): List<HistoryEntry> = entries.toList()

    /** A page was opened: it goes to the top of the list, with [title] when it is known (else its old title is kept). */
    @Synchronized
    fun record(url: String, title: String? = null, now: Long = System.currentTimeMillis()) {
        // The same address reported twice in a moment (redirects, single-page sites): nothing to change.
        val first = entries.firstOrNull()
        if (first != null && first.url == url && now - first.time < SAME_VISIT_MS) return
        val old = entries.indexOfFirst { it.url == url }
        val oldTitle = if (old >= 0) entries.removeAt(old).title else ""
        val newTitle = title?.trim().orEmpty().ifEmpty { oldTitle }
        entries.add(0, HistoryEntry(url, newTitle, now))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save()
    }

    /** The page told its title: the entry of [url], if there is one, takes it. */
    @Synchronized
    fun setTitle(url: String?, title: String?) {
        val clean = title?.trim().orEmpty()
        if (url == null || clean.isEmpty()) return
        val index = entries.indexOfFirst { it.url == url }
        if (index < 0 || entries[index].title == clean) return
        entries[index] = entries[index].copy(title = clean)
        save()
    }

    /** Deletes the entries with these addresses. */
    @Synchronized
    fun remove(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val gone = urls.toHashSet()
        if (entries.removeAll { it.url in gone }) save()
    }

    @Synchronized
    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        save()
    }

    private fun load(): MutableList<HistoryEntry> {
        val raw = sp.getString(KEY, null) ?: return ArrayList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<HistoryEntry>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val url = o.optString("u")
                if (url.isNotEmpty()) list.add(HistoryEntry(url, o.optString("t"), o.optLong("d")))
            }
            list
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun save() {
        val array = JSONArray()
        for (e in entries) array.put(JSONObject().put("u", e.url).put("t", e.title).put("d", e.time))
        sp.edit { putString(KEY, array.toString()) }
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 500
        private const val SAME_VISIT_MS = 2000L

        @Volatile
        private var instance: BrowserHistory? = null

        fun get(context: Context): BrowserHistory =
            instance ?: synchronized(this) {
                instance ?: BrowserHistory(context.applicationContext).also { instance = it }
            }
    }
}
