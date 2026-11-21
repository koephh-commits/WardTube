// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem

/** The one way into the built-in player, from a file list or from a page address. */
object PlayerLauncher {

    const val EXTRA_URIS = "wt.player.uris"
    const val EXTRA_TITLES = "wt.player.titles"
    const val EXTRA_SUBS = "wt.player.subs"
    const val EXTRA_START = "wt.player.start"

    /** Several subtitle addresses of one item are joined with this character in [EXTRA_SUBS]. */
    const val SUB_SEPARATOR = "\u0001"

    /** Inside one subtitle: its address, then this character, then its file name (the address of a library file has no name). */
    const val NAME_SEPARATOR = "\u0002"

    /** One file of a queue, with the subtitle files that belong to it. */
    class Entry(val uri: Uri, val title: String, val subtitles: List<Pair<Uri, String>> = emptyList())

    /** Plays [entries] as a queue, starting at [start]. */
    fun openFiles(context: Context, entries: List<Entry>, start: Int) {
        if (entries.isEmpty()) return
        val intent = base(context)
            .putStringArrayListExtra(EXTRA_URIS, ArrayList(entries.map { it.uri.toString() }))
            .putStringArrayListExtra(EXTRA_TITLES, ArrayList(entries.map { it.title }))
            .putStringArrayListExtra(EXTRA_SUBS, ArrayList(entries.map { encodeSubs(it.subtitles) }))
            .putExtra(EXTRA_START, start.coerceIn(0, entries.size - 1))
        context.startActivity(intent)
    }

    /** The subtitle files of one item as one text (what [EXTRA_SUBS] carries for it, and what the player's queue reads back). */
    fun encodeSubs(subtitles: List<Pair<Uri, String>>): String =
        subtitles.joinToString(SUB_SEPARATOR) { (u, n) -> u.toString() + NAME_SEPARATOR + n }

    /** Brings the player screen back with whatever is playing (the mini player of the Library): nothing new is started. */
    fun openCurrent(context: Context) {
        context.startActivity(base(context))
    }

    /** The file of a queue item: the Library gives each item its file's address as the media id. Null when it has none. */
    fun uriOf(item: MediaItem?): Uri? {
        if (item == null) return null
        item.localConfiguration?.uri?.let { return it }
        return item.mediaId.takeIf { it.isNotBlank() && it != MediaItem.DEFAULT_MEDIA_ID }?.let { Uri.parse(it) }
    }

    private fun base(context: Context) = Intent(context, PlayerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
