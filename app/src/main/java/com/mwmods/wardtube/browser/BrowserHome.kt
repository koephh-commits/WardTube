// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the browser screen shows under the link field while no page is open (see `browserHomeScroll` in the layout):
 *
 * - Quick sites: rows of four tiles. Seven sites are built in; the person adds their own with the last tile (a link,
 *   kept in [Prefs.quickSites]) and removes one of their own with a long press. A tap opens the site in the browser.
 * - Recently opened: the last [RECENT_COUNT] pages of the browsing history ([BrowserHistory]), newest first; a tap opens
 *   the page and "See all" opens the whole history. Hidden while the history is empty.
 * - A hint card about the floating download button (static, in the layout).
 *
 * [BrowserActivity] shows it with [setVisible] (true on the main screen, false while a page is open) and calls
 * [refresh] when the screen comes back, so that a page opened meanwhile shows in the list.
 */
internal class BrowserHome(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val history: BrowserHistory,
    /** Opens an address in the browser. */
    private val openPage: (String) -> Unit,
    /** Opens the browsing history screen. */
    private val openHistory: () -> Unit,
) {

    /** A quick site: [letter] is drawn in its tile, [color] tints it; [custom] ones were added by the person. */
    private class Site(val name: String, val url: String, val color: Int, val letter: String, val custom: Boolean)

    private val scroll: View = activity.findViewById(R.id.browserHomeScroll)
    private val sitesBox: LinearLayout = activity.findViewById(R.id.browserQuickSites)
    private val recentHeader: View = activity.findViewById(R.id.browserRecentHeader)
    private val recentList: LinearLayout = activity.findViewById(R.id.browserRecentList)
    private val inflater: LayoutInflater = LayoutInflater.from(activity)
    private val density = activity.resources.displayMetrics.density

    /** The colors a site (or a page of the history) can take: the ones that tell the kinds of found items apart. */
    private val palette: IntArray by lazy {
        intArrayOf(
            res(R.color.detectVideo), res(R.color.detectStream), res(R.color.detectPlayer),
            res(R.color.detectAudio), res(R.color.detectFile),
        )
    }

    init {
        activity.findViewById<View>(R.id.browserRecentAll).setOnClickListener { openHistory() }
        refresh()
    }

    /** Shows or hides the whole main screen content. */
    fun setVisible(visible: Boolean) {
        scroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refresh()
    }

    fun refresh() {
        showSites()
        showRecent()
    }

    // ---- Quick sites ----

    private fun builtIn(): List<Site> = listOf(
        builtIn(R.string.quickYoutube, "https://m.youtube.com/", R.color.detectVideo, "Y"),
        builtIn(R.string.quickTiktok, "https://www.tiktok.com/", R.color.detectStream, "T"),
        builtIn(R.string.quickFacebook, "https://m.facebook.com/", R.color.detectFile, "F"),
        builtIn(R.string.quickInstagram, "https://www.instagram.com/", R.color.detectPlayer, "I"),
        builtIn(R.string.quickSoundcloud, "https://soundcloud.com/", R.color.detectAudio, "S"),
        builtIn(R.string.quickDailymotion, "https://www.dailymotion.com/", R.color.detectStream, "D"),
        builtIn(R.string.quickVimeo, "https://vimeo.com/", R.color.detectVideo, "V"),
    )

    private fun builtIn(@StringRes name: Int, url: String, @ColorRes color: Int, letter: String) =
        Site(activity.getString(name), url, res(color), letter, custom = false)

    private fun customSite(name: String, url: String) = Site(name, url, colorFor(url), letterOf(name), custom = true)

    private fun customSites(): List<Site> {
        val raw = prefs.quickSites
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<Site>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val url = o.optString("u")
                if (url.isEmpty()) continue
                list.add(customSite(o.optString("n").ifEmpty { hostOf(url) }, url))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustom(sites: List<Site>) {
        val array = JSONArray()
        for (site in sites) array.put(JSONObject().put("n", site.name).put("u", site.url))
        prefs.quickSites = array.toString()
    }

    /** Rows of [COLUMNS] tiles: the built-in sites, the person's own, then the Add tile. */
    private fun showSites() {
        sitesBox.removeAllViews()
        val sites = builtIn() + customSites()
        val cells = sites.size + 1
        var first = 0
        while (first < cells) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0 until COLUMNS) {
                val position = first + column
                val cell: View = when {
                    position < sites.size -> siteTile(sites[position])
                    position == sites.size -> addTile()
                    else -> View(activity)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (first > 0) params.topMargin = dp(12f)
            sitesBox.addView(row, params)
            first += COLUMNS
        }
    }

    private fun siteTile(site: Site): View {
        val tile = inflater.inflate(R.layout.item_quick_site, sitesBox, false)
        tile.findViewById<View>(R.id.quickSiteIcon).background = BrowserSheet.rounded(activity, tint(site.color), 20f)
        tile.findViewById<TextView>(R.id.quickSiteLetter).apply {
            text = site.letter
            setTextColor(site.color)
        }
        tile.findViewById<TextView>(R.id.quickSiteName).text = site.name
        tile.setOnClickListener { openPage(site.url) }
        if (site.custom) {
            tile.setOnLongClickListener {
                confirmRemove(site)
                true
            }
        }
        return tile
    }

    private fun addTile(): View {
        val tile = inflater.inflate(R.layout.item_quick_site, sitesBox, false)
        tile.findViewById<View>(R.id.quickSiteIcon).setBackgroundResource(R.drawable.bg_quick_site_add)
        tile.findViewById<View>(R.id.quickSiteLetter).visibility = View.GONE
        tile.findViewById<ImageView>(R.id.quickSiteAdd).visibility = View.VISIBLE
        tile.findViewById<TextView>(R.id.quickSiteName).apply {
            setText(R.string.quickAdd)
            setTextColor(res(R.color.browserHint))
        }
        tile.setOnClickListener { askForSite() }
        return tile
    }

    /** Asks for the link of a site to add. */
    private fun askForSite() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.quickAddHint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp(24f), dp(8f), dp(24f), 0)
            addView(
                input,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.quickAddTitle)
            .setView(box)
            .setPositiveButton(R.string.quickAdd) { _, _ -> addSite(input.text?.toString().orEmpty()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun addSite(text: String) {
        val address = normalize(text)
        if (address == null) {
            Snackbar.make(scroll, R.string.quickAddInvalid, Snackbar.LENGTH_LONG).show()
            return
        }
        val list = customSites().toMutableList()
        if (list.size < MAX_CUSTOM && list.none { sameAddress(it.url, address) }) {
            list.add(customSite(hostOf(address), address))
            saveCustom(list)
        }
        refresh()
    }

    private fun confirmRemove(site: Site) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.quickRemoveTitle)
            .setMessage(site.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                saveCustom(customSites().filterNot { it.url == site.url && it.name == site.name })
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The text as an address with a scheme, or null when it cannot be a site ("site.com" becomes https://site.com). */
    private fun normalize(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val address = if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
            t
        } else {
            "https://$t"
        }
        val host = Uri.parse(address).host ?: return null
        return if ('.' in host) address else null
    }

    private fun sameAddress(a: String, b: String) = a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

    // ---- Recently opened ----

    private fun showRecent() {
        recentList.removeAllViews()
        val entries = history.all().take(RECENT_COUNT)
        val visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        recentHeader.visibility = visibility
        recentList.visibility = visibility
        val now = System.currentTimeMillis()
        entries.forEachIndexed { index, entry ->
            if (index > 0) recentList.addView(divider())
            val row = inflater.inflate(R.layout.item_browser_recent, recentList, false)
            val host = hostOf(entry.url)
            val color = colorFor(host)
            row.findViewById<TextView>(R.id.recentAvatar).apply {
                text = letterOf(host)
                setTextColor(color)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.blendARGB(res(R.color.browserSheetRow), color, 0.16f))
                }
            }
            row.findViewById<TextView>(R.id.recentTitle).text = entry.title.ifBlank { host }
            row.findViewById<TextView>(R.id.recentHost).text = host
            row.findViewById<TextView>(R.id.recentTime).text = timeAgo(entry.time, now)
            row.setOnClickListener { openPage(entry.url) }
            recentList.addView(row)
        }
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(res(R.color.browserSheetDivider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1))
    }

    private fun timeAgo(time: Long, now: Long): String {
        if (now - time < DateUtils.MINUTE_IN_MILLIS) return activity.getString(R.string.browserRecentNow)
        return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            .toString()
    }

    // ---- Small helpers ----

    private fun res(@ColorRes id: Int): Int = ContextCompat.getColor(activity, id)

    /** [color] at a low strength over the card color: the fill of a tile or an avatar. */
    private fun tint(color: Int): Int = ColorUtils.blendARGB(res(R.color.browserSheet), color, 0.14f)

    /** The same key always gets the same color of the [palette]. */
    private fun colorFor(key: String): Int = palette[(key.hashCode() and Int.MAX_VALUE) % palette.size]

    /** The first letter or digit of [name], capitalized; it is what a tile or an avatar shows instead of an icon. */
    private fun letterOf(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    private fun hostOf(url: String): String = Uri.parse(url).host?.removePrefix("www.") ?: url

    private fun dp(value: Float): Int = (value * density).toInt()

    private companion object {
        const val COLUMNS = 4
        const val RECENT_COUNT = 3

        /** More than this many sites of the person's own are not added (the list would only be a long scroll). */
        const val MAX_CUSTOM = 24
    }
}
