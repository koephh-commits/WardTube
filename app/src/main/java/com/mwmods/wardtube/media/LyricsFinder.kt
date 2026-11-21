// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import com.mwmods.wardtube.util.DownloadItem
import com.mwmods.wardtube.util.DownloadStore
import com.mwmods.wardtube.util.MediaCategory
import java.util.Locale

/**
 * Finds the lyrics / subtitle files (.lrc / .srt) of a video or an audio file of the Library.
 *
 * The app saves them in the `كلمات` folder ([DownloadStore.FOLDER_LYRICS]) of Download/WardTube, away from the media
 * (`فيديوهات` / `أصوات`), under the media's exact name: `Song.mp3` in `أصوات` goes with `Song.lrc` in `كلمات`. Files saved
 * by earlier versions lie straight in Download/WardTube, so they are still found, but the one in `كلمات` wins when both
 * exist. A second language keeps its code (`Song.en.srt`).
 */
object LyricsFinder {

    private val LANGUAGE_RE = Regex("""[A-Za-z]{2,3}(-[A-Za-z0-9]{2,4})?""")

    private class Match(val item: DownloadItem, val language: String, val extension: String)

    /**
     * The lyrics files of [media] among [candidates] (the Library's .srt / .lrc files), the best first: the one with the
     * plain name before the ones with a language code, and, for music, LRC before SRT (for a video, SRT before LRC).
     * One file per language and format.
     */
    fun find(media: DownloadItem, candidates: List<DownloadItem>): List<DownloadItem> {
        val base = media.name.substringBeforeLast('.')
        val preferLrc = media.category == MediaCategory.AUDIO
        return candidates
            .mapNotNull { match(it, base) }
            // The same name in two places (an older copy outside the folder): the one in the lyrics folder is kept.
            .sortedWith(compareBy<Match>({ if (it.item.folder == DownloadStore.FOLDER_LYRICS) 0 else 1 }, { it.item.name }))
            .distinctBy { it.language + "|" + it.extension }
            .sortedWith(
                compareBy<Match>(
                    { if (it.language.isEmpty()) 0 else 1 },
                    { if ((it.extension == "lrc") == preferLrc) 0 else 1 },
                    { it.language },
                ),
            )
            .map { it.item }
    }

    /** [item] when it is a lyrics file named after [base] ("Song.lrc", "Song.ar.srt"); null for any other file. */
    private fun match(item: DownloadItem, base: String): Match? {
        if (!item.isSubtitle) return null
        val name = item.name
        if (!name.startsWith("$base.", ignoreCase = true)) return null
        val rest = name.substring(base.length + 1) // "lrc" or "ar.srt"
        val extension = rest.substringAfterLast('.').lowercase(Locale.ROOT)
        if (extension != "srt" && extension != "lrc") return null
        if (rest.length == extension.length) return Match(item, "", extension)
        // Anything between the name and the extension must be a language code: "Song.Remix.srt" is not Song's.
        val language = rest.substring(0, rest.length - extension.length - 1)
        return if (LANGUAGE_RE.matches(language)) Match(item, language.lowercase(Locale.ROOT), extension) else null
    }
}
