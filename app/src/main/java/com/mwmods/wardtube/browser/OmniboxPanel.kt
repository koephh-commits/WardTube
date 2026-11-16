// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mwmods.wardtube.R
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The page under the browser's link field while the field is being used, like the one under Chrome's address bar.
 *
 * - Field empty (or still holding the open page's own address): the open page's card (edit / copy / share), then the
 *   recent searches ([SearchHistory]); with no searches yet, the last pages opened ([BrowserHistory]).
 * - Text typed: the text itself first (search it, or open it when it looks like an address), then the recent searches
 *   that match, what the chosen engine suggests ([SearchSuggest], arriving a moment later), and the pages of the
 *   history that match.
 *
 * A tap on a row does the search (or opens the page); the arrow at its end only puts the text in the field. A long
 * press on a recent search removes it. Dragging the list hides the keyboard so the whole list can be seen.
 *
 * [BrowserActivity] owns the field and the top row; it calls [show] / [update] / [hide] and answers [Actions].
 */
internal class OmniboxPanel(
    private val activity: AppCompatActivity,
    private val history: BrowserHistory,
    private val searches: SearchHistory,
    private val engine: () -> SearchEngine,
    private val actions: Actions,
) {

    interface Actions {
        /** A text was picked: search it (or open it, when it is an address). */
        fun pick(text: String)

        /** A page of the history was picked. */
        fun openPage(url: String)

        /** The arrow of a row: the text goes to the field, nothing is searched. */
        fun fill(text: String)

        /** The pencil (or the card): the open page's address goes into the field, the cursor at its end, the keyboard up. */
        fun editAddress(url: String)

        fun copyAddress(url: String)

        fun shareAddress(url: String)

        /** The list was dragged: the keyboard can go. */
        fun listDragged()
    }

    private enum class Kind(@DrawableRes val icon: Int) {
        TYPED(R.drawable.ic_search_24dp),
        ADDRESS(R.drawable.ic_globe_white_24dp),
        RECENT(R.drawable.ic_history_white_24dp),
        SUGGESTION(R.drawable.ic_search_24dp),
        PAGE(R.drawable.ic_globe_white_24dp),
    }

    private sealed class Item {
        data class Current(val url: String, val title: String) : Item()
        data class Header(@StringRes val text: Int) : Item()
        data class Row(val kind: Kind, val text: String, val sub: String? = null, val url: String? = null) : Item()
    }

    private val panel: View = activity.findViewById(R.id.browserOmnibox)
    private val list: RecyclerView = activity.findViewById(R.id.browserOmniboxList)
    private val adapter = Adapter()
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "omnibox-suggest").apply { isDaemon = true }
    }

    /** Set at once by [show] / [hide]: the panel stays on the screen a moment longer while it fades away. */
    private var shown = false
    val isOpen: Boolean get() = shown

    /** The same curve as the header's movement (see BrowserActivity), so the two feel like one. */
    private val interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** How far below its place the panel is while it is not shown: it rises into place as it fades in. */
    private val shift = SHIFT_DP * activity.resources.displayMetrics.density

    /** The open page (null on the main screen); the text equal to its address counts as "nothing typed yet". */
    private var currentUrl: String? = null
    private var currentTitle: String = ""

    private var typed = ""

    /** What the engine suggested for [remoteFor]; shown only while the field still holds that text. */
    private var remote: List<String> = emptyList()
    private var remoteFor = ""

    /** Counts the texts typed: an answer that arrives for an older one is dropped. */
    private var generation = 0
    private val fetchRunnable = Runnable { startFetch() }

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) actions.listDragged()
            }
        })
    }

    /** Opens the panel for the open page ([url] / [title], or null on the main screen). */
    fun show(url: String?, title: String?) {
        currentUrl = url?.takeIf { it.isNotBlank() && it != "about:blank" }
        currentTitle = title.orEmpty()
        remote = emptyList()
        remoteFor = ""
        typed = ""
        shown = true
        // It comes in after the field has left its old place (the field and the page glide up at the same time): it fades
        // in while it rises a few dp into its own place.
        panel.animate().cancel()
        panel.alpha = 0f
        panel.translationY = shift
        panel.visibility = View.VISIBLE
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(SHOW_DELAY_MS)
            .setDuration(SHOW_MS)
            .setInterpolator(interpolator)
            .start()
    }

    /** Closes the panel: it fades away quickly (the field is already coming back down), and is gone when it has. */
    fun hide() {
        shown = false
        generation++
        handler.removeCallbacks(fetchRunnable)
        panel.animate().cancel()
        if (panel.visibility != View.VISIBLE) return
        panel.animate()
            .alpha(0f)
            .translationY(shift)
            .setStartDelay(0)
            .setDuration(HIDE_MS)
            .setInterpolator(interpolator)
            .withEndAction { if (!shown) panel.visibility = View.GONE }
            .start()
    }

    fun destroy() {
        shown = false
        generation++
        handler.removeCallbacks(fetchRunnable)
        panel.animate().cancel()
        executor.shutdownNow()
    }

    /** The field's text changed: the list is built again, and the engine is asked (after a short pause) for suggestions. */
    fun update(text: String) {
        typed = text
        generation++
        handler.removeCallbacks(fetchRunnable)
        build()
        val query = text.trim()
        if (isTyping() && !looksLikeAddress(query) && SearchSuggest.supports(engine())) {
            handler.postDelayed(fetchRunnable, FETCH_DELAY_MS)
        }
    }

    /** Nothing typed yet: an empty field, or the open page's own address that the person has not changed. */
    private fun isTyping(): Boolean {
        val text = typed.trim()
        return text.isNotEmpty() && text != currentUrl
    }

    private fun startFetch() {
        val query = typed.trim()
        val mine = generation
        val chosen = engine()
        val language = Locale.getDefault().language
        executor.execute {
            val answer = SearchSuggest.fetch(chosen, query, language)
            handler.post {
                if (mine != generation || !isOpen) return@post
                remote = answer
                remoteFor = query
                build()
            }
        }
    }

    // ---- What the list holds ----

    private fun build() {
        val items = ArrayList<Item>()
        if (!isTyping()) {
            currentUrl?.let { items.add(Item.Current(it, currentTitle)) }
            val recent = searches.all().take(RECENT_COUNT)
            if (recent.isNotEmpty()) {
                items.add(Item.Header(R.string.omniboxRecentSearches))
                recent.forEach { items.add(Item.Row(Kind.RECENT, it)) }
            } else {
                val pages = history.all().take(PAGES_WHEN_NO_SEARCHES)
                if (pages.isNotEmpty()) {
                    items.add(Item.Header(R.string.browserRecent))
                    pages.forEach { items.add(pageRow(it)) }
                }
            }
        } else {
            val query = typed.trim()
            items.add(Item.Row(if (looksLikeAddress(query)) Kind.ADDRESS else Kind.TYPED, query))
            val seen = HashSet<String>()
            seen.add(query.lowercase())
            for (text in searches.matching(query, MATCHING_SEARCHES)) {
                if (seen.add(text.lowercase())) items.add(Item.Row(Kind.RECENT, text))
            }
            if (remoteFor == query) {
                for (text in remote) {
                    if (seen.add(text.lowercase())) items.add(Item.Row(Kind.SUGGESTION, text))
                }
            }
            matchingPages(query).forEach { items.add(pageRow(it)) }
        }
        adapter.submit(items)
    }

    private fun pageRow(entry: HistoryEntry): Item.Row {
        val host = Uri.parse(entry.url).host?.removePrefix("www.") ?: entry.url
        return Item.Row(Kind.PAGE, entry.title.ifBlank { host }, entry.url.removePrefix("https://").removePrefix("http://"), entry.url)
    }

    /** The pages of the history whose name or address has every word of [query]. */
    private fun matchingPages(query: String): List<HistoryEntry> {
        val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return history.all()
            .filter { entry ->
                val haystack = (entry.title + " " + entry.url).lowercase()
                words.all { it in haystack }
            }
            .take(MATCHING_PAGES)
    }

    /** A text with no spaces that starts like a link or has a dot in it: it is opened, not searched (as in the field). */
    private fun looksLikeAddress(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return false
        return t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) || '.' in t
    }

    private fun confirmRemove(text: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.omniboxRemoveTitle)
            .setMessage(text)
            .setPositiveButton(R.string.delete) { _, _ ->
                searches.remove(text)
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** [text] with the part after what was typed in bold, as Chrome does for a suggestion that continues the text. */
    private fun emphasize(text: String, query: String): CharSequence {
        val q = query.trim()
        if (q.isEmpty() || text.length <= q.length || !text.startsWith(q, ignoreCase = true)) return text
        val span = SpannableString(text)
        span.setSpan(StyleSpan(Typeface.BOLD), q.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    // ---- The adapter ----

    private class RowHolder(root: View) : RecyclerView.ViewHolder(root) {
        val icon: ImageView = root.findViewById(R.id.omniIcon)
        val text: TextView = root.findViewById(R.id.omniText)
        val sub: TextView = root.findViewById(R.id.omniSub)
        val fill: ImageButton = root.findViewById(R.id.omniFill)
    }

    private class HeaderHolder(root: View) : RecyclerView.ViewHolder(root) {
        val label: TextView = root.findViewById(R.id.omniHeader)
    }

    private class CurrentHolder(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.omniPageTitle)
        val url: TextView = root.findViewById(R.id.omniPageUrl)
        val edit: View = root.findViewById(R.id.omniPageEdit)
        val copy: View = root.findViewById(R.id.omniPageCopy)
        val share: View = root.findViewById(R.id.omniPageShare)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()

        fun submit(next: List<Item>) {
            items = next
            notifyDataSetChanged()
            list.scrollToPosition(0)
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Item.Current -> TYPE_CURRENT
            is Item.Header -> TYPE_HEADER
            is Item.Row -> TYPE_ROW
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_CURRENT -> CurrentHolder(inflater.inflate(R.layout.item_omnibox_page, parent, false))
                TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_omnibox_header, parent, false))
                else -> RowHolder(inflater.inflate(R.layout.item_omnibox_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Current -> bindCurrent(holder as CurrentHolder, item)
                is Item.Header -> (holder as HeaderHolder).label.setText(item.text)
                is Item.Row -> bindRow(holder as RowHolder, item)
            }
        }

        private fun bindCurrent(holder: CurrentHolder, item: Item.Current) {
            holder.title.text = item.title.ifBlank { Uri.parse(item.url).host?.removePrefix("www.") ?: item.url }
            holder.url.text = item.url.removePrefix("https://").removePrefix("http://")
            holder.itemView.setOnClickListener { actions.editAddress(item.url) }
            holder.edit.setOnClickListener { actions.editAddress(item.url) }
            holder.copy.setOnClickListener { actions.copyAddress(item.url) }
            holder.share.setOnClickListener { actions.shareAddress(item.url) }
        }

        private fun bindRow(holder: RowHolder, item: Item.Row) {
            holder.icon.setImageResource(item.kind.icon)
            holder.text.text = if (item.kind == Kind.SUGGESTION) emphasize(item.text, typed) else item.text
            holder.sub.visibility = if (item.sub == null) View.GONE else View.VISIBLE
            holder.sub.text = item.sub
            holder.itemView.setOnClickListener {
                val url = item.url
                if (item.kind == Kind.PAGE && url != null) actions.openPage(url) else actions.pick(item.text)
            }
            if (item.kind == Kind.RECENT) {
                holder.itemView.setOnLongClickListener {
                    confirmRemove(item.text)
                    true
                }
            } else {
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.isLongClickable = false
            }
            // A page of the history puts its address in the field; any other row, its text.
            holder.fill.setOnClickListener { actions.fill(item.url ?: item.text) }
        }
    }

    private companion object {
        const val TYPE_CURRENT = 0
        const val TYPE_HEADER = 1
        const val TYPE_ROW = 2

        const val SHIFT_DP = 12
        const val SHOW_DELAY_MS = 60L
        const val SHOW_MS = 200L
        const val HIDE_MS = 110L

        const val FETCH_DELAY_MS = 150L
        const val RECENT_COUNT = 8
        const val PAGES_WHEN_NO_SEARCHES = 6
        const val MATCHING_SEARCHES = 3
        const val MATCHING_PAGES = 3
    }
}
