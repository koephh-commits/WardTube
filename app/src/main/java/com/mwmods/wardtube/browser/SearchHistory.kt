// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray

/**
 * The words searched in the browser screen's link field, newest first, one entry per text (searching the same words
 * again moves them to the top), at most [MAX_ENTRIES] long. They are what the link field's suggestions panel lists as
 * "recent searches" (see [OmniboxPanel]). Kept in a small SharedPreferences file of its own, on the phone only.
 *
 * One instance for the whole app ([get]).
 */
class SearchHistory private constructor(context: Context) {

    private val sp: SharedPreferences = context.applicationContext.let {
        it.getSharedPreferences("${it.packageName}_search_history", Context.MODE_PRIVATE)
    }

    private val entries: MutableList<String> = load()

    /** A copy of the list, newest first. */
    @Synchronized
    fun all(): List<String> = entries.toList()

    /** [text] was searched: it goes to the top of the list. */
    @Synchronized
    fun record(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        entries.removeAll { it.equals(clean, ignoreCase = true) }
        entries.add(0, clean)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save()
    }

    /** The searches that contain every word of [query] (case ignored), newest first, at most [limit]. */
    @Synchronized
    fun matching(query: String, limit: Int): List<String> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return entries.take(limit)
        return entries.filter { entry ->
            val low = entry.lowercase()
            words.all { it in low } && !low.equals(query.trim().lowercase())
        }.take(limit)
    }

    @Synchronized
    fun remove(text: String) {
        if (entries.removeAll { it == text }) save()
    }

    @Synchronized
    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        save()
    }

    private fun load(): MutableList<String> {
        val raw = sp.getString(KEY, null) ?: return ArrayList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val text = array.optString(i)
                if (text.isNotEmpty()) list.add(text)
            }
            list
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun save() {
        val array = JSONArray()
        for (e in entries) array.put(e)
        sp.edit { putString(KEY, array.toString()) }
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 60

        @Volatile
        private var instance: SearchHistory? = null

        fun get(context: Context): SearchHistory =
            instance ?: synchronized(this) {
                instance ?: SearchHistory(context.applicationContext).also { instance = it }
            }
    }
}
