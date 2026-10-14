// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import com.mwmods.wardtube.util.DownloadItem

/**
 * The files ticked in the Library's multi-select mode, in the order they were chosen, plus the anchor of a range
 * selection.
 *
 * The [DownloadItem]s themselves are kept, not only their ids: a ticked file must stay ticked when the filter chips
 * or the search hide it for a moment.
 *
 * Nothing here touches the UI; the adapter redraws the rows.
 */
class MultiSelection {

    private val selected = LinkedHashMap<Long, DownloadItem>()

    /**
     * Anchor of a range: the last file the user ticked (by a tap, or as the end of an earlier range). The next long
     * press ticks everything between the anchor and the pressed file. Cleared when the anchor is unticked or by [clear].
     */
    private var anchorId: Long? = null

    val count: Int
        get() = selected.size

    fun isSelected(id: Long): Boolean = selected.containsKey(id)

    /** The ticked files, in the order they were ticked. */
    fun items(): List<DownloadItem> = selected.values.toList()

    fun clear() {
        selected.clear()
        anchorId = null
    }

    /** Ticks or unticks one file; a file that becomes ticked becomes the anchor of the next range. */
    fun toggle(item: DownloadItem) {
        if (selected.remove(item.id) != null) {
            if (anchorId == item.id) anchorId = null
        } else {
            selected[item.id] = item
            anchorId = item.id
        }
    }

    /**
     * Ticks every file between the anchor and [target] (both included) in [visible], the files on screen in display
     * order, without unticking anything; [target] then becomes the anchor, so ranges can follow one another.
     * With no anchor among [visible] (nothing ticked yet, or the anchor is hidden by the filter or the search) only
     * [target] is ticked, and it becomes the anchor.
     */
    fun selectRange(visible: List<DownloadItem>, target: DownloadItem) {
        val targetIndex = visible.indexOfFirst { it.id == target.id }
        if (targetIndex < 0) return

        val anchor = anchorId
        val anchorIndex = if (anchor == null) -1 else visible.indexOfFirst { it.id == anchor }

        if (anchorIndex < 0) {
            if (selected.containsKey(target.id)) anchorId = target.id else toggle(target)
            return
        }

        for (i in minOf(anchorIndex, targetIndex)..maxOf(anchorIndex, targetIndex)) {
            val item = visible[i]
            selected[item.id] = item
        }
        anchorId = target.id
    }

    /** Drops ticked files that no longer exist (deleted elsewhere). Returns true if any was dropped. */
    fun retainOnly(existing: Set<Long>): Boolean {
        val changed = selected.keys.retainAll(existing)
        val anchor = anchorId
        if (anchor != null && anchor !in existing) anchorId = null
        return changed
    }
}
