// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mwmods.wardtube.R

/**
 * The browsing history: the pages opened in the browser screen, newest first (see [BrowserHistory]).
 *
 * - Tap a page: the browser screen opens it (the address goes back in [EXTRA_URL]).
 * - Long press a page, or "Select multiple" in the menu: several pages can be ticked; the bar at the bottom deletes
 *   them. Selecting works like the Library's: a tap ticks / unticks a page, and a long press ticks every page between
 *   the last ticked one and the pressed one (see [HistoryAdapter.selectRangeTo]). "Select all" ticks every page.
 * - "Clear all history" in the menu deletes everything.
 * - Back ends the ticking mode first, and only then leaves the screen.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var history: BrowserHistory
    private lateinit var toolbar: MaterialToolbar
    private lateinit var emptyState: View
    private lateinit var selectionBar: View
    private lateinit var deleteButton: MaterialButton

    private val adapter = HistoryAdapter(
        onOpen = { openPage(it) },
        onLongPress = { startSelecting(it) },
        onChanged = { updateUi() },
    )

    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setSelecting(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        history = BrowserHistory.get(this)

        val root = findViewById<View>(R.id.historyRoot)
        toolbar = findViewById(R.id.historyToolbar)
        emptyState = findViewById(R.id.historyEmpty)
        selectionBar = findViewById(R.id.historySelectionBar)
        deleteButton = findViewById(R.id.historyDelete)
        findViewById<RecyclerView>(R.id.historyList).adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.menu_history)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select -> {
                    setSelecting(!adapter.selecting)
                    true
                }
                R.id.action_select_all -> {
                    adapter.selectAll()
                    true
                }
                R.id.action_clear -> {
                    confirmClear()
                    true
                }
                else -> false
            }
        }
        onBackPressedDispatcher.addCallback(this, selectionBackCallback)
        deleteButton.setOnClickListener { confirmDeleteSelected() }

        adapter.submit(history.all())
        updateUi()
    }

    /** A tap on a page outside the ticking mode: the browser screen opens it. */
    private fun openPage(entry: HistoryEntry) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, entry.url))
        finish()
    }

    private fun startSelecting(entry: HistoryEntry) {
        setSelecting(true)
        adapter.toggle(entry)
    }

    private fun setSelecting(enabled: Boolean) {
        adapter.setSelecting(enabled)
        selectionBackCallback.isEnabled = enabled
        updateUi()
    }

    /** After every change: the title, the menu, the delete bar and the empty message follow the list and the ticks. */
    private fun updateUi() {
        val selecting = adapter.selecting
        val count = adapter.selectedCount()
        toolbar.title = when {
            !selecting -> getString(R.string.historyTitle)
            count == 0 -> getString(R.string.historySelectItems)
            else -> resources.getQuantityString(R.plurals.historySelectedCount, count, count)
        }
        toolbar.subtitle = if (selecting) getString(R.string.libraryRangeHint) else null
        toolbar.menu.findItem(R.id.action_select)
            ?.setTitle(if (selecting) R.string.libraryMultiSelectOff else R.string.libraryMultiSelectOn)
        toolbar.menu.findItem(R.id.action_select_all)?.isVisible = selecting
        toolbar.menu.findItem(R.id.action_select)?.isVisible = adapter.itemCount > 0
        toolbar.menu.findItem(R.id.action_clear)?.isVisible = adapter.itemCount > 0
        emptyState.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        val showBar = selecting && count > 0
        selectionBar.visibility = if (showBar) View.VISIBLE else View.GONE
        if (showBar) deleteButton.text = getString(R.string.librarySelectionDelete, count)
    }

    private fun confirmDeleteSelected() {
        val urls = adapter.selectedUrls()
        if (urls.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.historyDeleteTitle)
            .setMessage(resources.getQuantityString(R.plurals.historyDeleteMessage, urls.size, urls.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                history.remove(urls)
                setSelecting(false)
                adapter.submit(history.all())
                updateUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.historyClearTitle)
            .setMessage(R.string.historyClearMessage)
            .setPositiveButton(R.string.delete) { _, _ ->
                history.clear()
                setSelecting(false)
                adapter.submit(history.all())
                updateUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** The address of the page the person tapped, in the result of this screen. */
        const val EXTRA_URL = "url"
    }
}

private class HistoryHolder(view: View) : RecyclerView.ViewHolder(view) {
    val icon: ImageView = view.findViewById(R.id.historyIcon)
    val check: MaterialCheckBox = view.findViewById(R.id.historyCheck)
    val title: TextView = view.findViewById(R.id.historyTitleText)
    val meta: TextView = view.findViewById(R.id.historyMeta)
}

/** The rows of the history, with the ticks of the selection mode (kept by address). */
private class HistoryAdapter(
    private val onOpen: (HistoryEntry) -> Unit,
    private val onLongPress: (HistoryEntry) -> Unit,
    private val onChanged: () -> Unit,
) : RecyclerView.Adapter<HistoryHolder>() {

    private var items: List<HistoryEntry> = emptyList()
    private val selected = LinkedHashSet<String>()

    /** The last page ticked: the next long press ticks everything between it and the pressed page. */
    private var anchor: String? = null

    var selecting = false
        private set

    fun submit(newItems: List<HistoryEntry>) {
        items = newItems
        val urls = newItems.map { it.url }.toSet()
        selected.retainAll(urls)
        if (anchor !in urls) anchor = null
        notifyDataSetChanged()
    }

    fun setSelecting(enabled: Boolean) {
        selecting = enabled
        if (!enabled) {
            selected.clear()
            anchor = null
        }
        notifyDataSetChanged()
    }

    fun selectedCount(): Int = selected.size

    fun selectedUrls(): List<String> = selected.toList()

    fun selectAll() {
        selected.addAll(items.map { it.url })
        notifyDataSetChanged()
        onChanged()
    }

    /** Ticks or unticks one page; a page that becomes ticked becomes the anchor of the next range. */
    fun toggle(entry: HistoryEntry) {
        if (selected.remove(entry.url)) {
            if (anchor == entry.url) anchor = null
        } else {
            selected.add(entry.url)
            anchor = entry.url
        }
        notifyDataSetChanged()
        onChanged()
    }

    /**
     * Ticks every page between the anchor and [target] (both included), without unticking anything; [target] then
     * becomes the anchor, so ranges can follow one another. With no anchor only [target] is ticked.
     */
    fun selectRangeTo(target: HistoryEntry) {
        val targetIndex = items.indexOfFirst { it.url == target.url }
        if (targetIndex < 0) return
        val anchorUrl = anchor
        val anchorIndex = if (anchorUrl == null) -1 else items.indexOfFirst { it.url == anchorUrl }
        if (anchorIndex < 0) {
            selected.add(target.url)
        } else {
            for (i in minOf(anchorIndex, targetIndex)..maxOf(anchorIndex, targetIndex)) selected.add(items[i].url)
        }
        anchor = target.url
        notifyDataSetChanged()
        onChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder =
        HistoryHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context
        holder.title.text = entry.title.ifBlank { hostOf(entry.url) }
        holder.meta.text = "${timeOf(context, entry.time)} • ${shortUrl(entry.url)}"

        val ticked = entry.url in selected
        holder.itemView.isActivated = selecting && ticked
        holder.icon.visibility = if (selecting) View.GONE else View.VISIBLE
        holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
        holder.check.isChecked = ticked

        holder.itemView.setOnClickListener {
            if (selecting) toggle(entry) else onOpen(entry)
        }
        holder.itemView.setOnLongClickListener {
            if (!selecting) onLongPress(entry) else selectRangeTo(entry)
            true
        }
    }

    private fun hostOf(url: String): String = Uri.parse(url).host ?: url

    private fun shortUrl(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")

    private fun timeOf(context: Context, time: Long): String = DateUtils.formatDateTime(
        context,
        time,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
}
