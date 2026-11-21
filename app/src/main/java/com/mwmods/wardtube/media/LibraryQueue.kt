// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.mwmods.wardtube.R
import com.mwmods.wardtube.util.DownloadItem
import com.mwmods.wardtube.util.DownloadStore
import com.mwmods.wardtube.util.MediaCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "whole Library as the queue" mode of the player's queue sheet.
 *
 * Switched on, the queue becomes every video and every song of the Library (in the Library's own order, newest first), the
 * ones that were in the queue before it included. The item that is playing is not touched, so the sound / picture does not
 * stop or jump: the Library's items after it come right after it, and the ones before it go to the end of the queue (the queue is
 * in the order of play, see [QueueCycle]). Switched off, the queue that was there before comes back the same way around the item
 * that is playing now (a file the Library no longer has is left out).
 *
 * It owns no list: it only tells the player what to add and remove, through the [MediaController] the screen already has, and
 * the queue sheet shows whatever the player then holds. What the mode replaced is a list of file addresses, kept here for the
 * whole life of the app ([saved]) so it also survives the player screen being closed while the music goes on.
 *
 * @param controller the screen's controller (null while it is not ready or the screen is gone).
 * @param itemOf makes the player's item of a file from its address, its name and its lyrics files ([PlayerLauncher.encodeSubs]).
 *   It reads the lyrics files, so it is called off the main thread.
 * @param onChanged the mode or its busy state changed: the sheet draws its button again.
 */
class LibraryQueue(
    private val activity: AppCompatActivity,
    private val controller: () -> MediaController?,
    private val itemOf: (uri: String, title: String, encodedSubs: String) -> MediaItem,
    private val onChanged: () -> Unit,
) {

    /** True while the queue is the whole Library. */
    val isOn: Boolean get() = on

    /** True while the Library is being read and the queue rebuilt: the button waits. */
    var isBusy = false
        private set

    private var job: Job? = null

    /** Switches the mode: on when it is off, off when it is on. */
    fun toggle() {
        if (isBusy) return
        val c = controller() ?: return
        if (c.mediaItemCount == 0) return
        change(c, turnOn = !on)
    }

    /** A queue that is opened new (from the Library) is not the Library queue any more: the mode ends with the one it replaced. */
    fun reset() {
        job?.cancel()
        job = null
        val changed = on || isBusy
        on = false
        saved = null
        isBusy = false
        if (changed) onChanged()
    }

    private fun change(c: MediaController, turnOn: Boolean) {
        // What the queue is now, to bring it back when the mode is switched off.
        val before = if (turnOn) List(c.mediaItemCount) { PlayerLauncher.uriOf(c.getMediaItemAt(it))?.toString().orEmpty() } else null
        isBusy = true
        onChanged()
        job = activity.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { readItems(turnOn) }
            isBusy = false
            val player = controller()
            if (items == null || player == null || player.mediaItemCount == 0) {
                if (items == null) Toast.makeText(activity, R.string.playerQueueLibraryFailed, Toast.LENGTH_SHORT).show()
                onChanged()
                return@launch
            }
            placeAround(player, items)
            on = turnOn
            saved = before
            Toast.makeText(
                activity,
                if (turnOn) R.string.playerQueueLibraryOnToast else R.string.playerQueueLibraryOffToast,
                Toast.LENGTH_SHORT,
            ).show()
            onChanged()
        }
    }

    /**
     * The items the queue is to hold: the whole Library when switching on, the saved queue when switching off. Null when the
     * Library cannot be read (nothing is changed then).
     */
    private fun readItems(turnOn: Boolean): List<MediaItem>? {
        val files = DownloadStore.query(activity)
        val media = files.filter { it.category == MediaCategory.VIDEO || it.category == MediaCategory.AUDIO }
        if (media.isEmpty()) return null
        val lyrics = files.filter { it.isSubtitle }
        val wanted = if (turnOn) {
            media
        } else {
            val byAddress = media.associateBy { it.uri.toString() }
            saved.orEmpty().mapNotNull { byAddress[it] }
        }
        return wanted.map { f -> itemOf(f.uri.toString(), f.name.substringBeforeLast('.'), wordsOf(f, lyrics)) }
    }

    private fun wordsOf(file: DownloadItem, lyrics: List<DownloadItem>): String =
        PlayerLauncher.encodeSubs(LyricsFinder.find(file, lyrics).map { it.uri to it.name })

    /**
     * Makes the queue [items], leaving the item that plays now exactly as it is. The queue is in the order of play (the playing
     * item first, what was played at the bottom, see [QueueCycle]), so the items after it in [items] come right after it and the
     * ones before it in [items] go to the end, in their order: after the last one of [items] the first comes again. When it is not
     * among [items], it stays first and all of [items] follow it.
     */
    private fun placeAround(c: MediaController, items: List<MediaItem>) {
        val index = c.currentMediaItemIndex.coerceAtLeast(0)
        val playingId = c.currentMediaItem?.mediaId
        val at = items.indexOfFirst { it.mediaId == playingId }
        val first = if (at >= 0) items.subList(0, at) else emptyList()
        val last = if (at >= 0) items.subList(at + 1, items.size) else items
        val count = c.mediaItemCount
        if (index + 1 < count) c.removeMediaItems(index + 1, count)
        if (index > 0) c.removeMediaItems(0, index)
        if (last.isNotEmpty()) c.addMediaItems(last)
        if (first.isNotEmpty()) c.addMediaItems(first)
    }

    private companion object {
        /** Whether the queue is the whole Library now (for the life of the app, like the player that holds the queue). */
        var on = false

        /** The addresses of the queue the mode replaced, in their order; null while the mode is off. */
        var saved: List<String>? = null
    }
}
