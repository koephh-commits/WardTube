// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import java.util.Locale
import java.util.TreeMap

/**
 * Media3 reads SubRip (.srt) and WebVTT but not LRC, and LRC is what the "Lyrics / subtitles LRC" download makes for a song.
 * This turns the text of an LRC file into SubRip so the built-in player can show it like any other subtitle.
 *
 * LRC is one timed line per row: `[01:23.45]the words`. Handled: the minutes / seconds / fraction forms `[mm:ss]`,
 * `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]` and `[mm:ss:xx]`; several time stamps in front of one line
 * (`[00:10.00][01:30.00]chorus`); the `[offset:+/-ms]` tag; word-by-word stamps (`<00:12.34>`), which are dropped;
 * information tags (`[ar:...]`, `[ti:...]`), which are not timed and so are skipped.
 * A line stays on screen until the next stamp; an empty line only ends the one before it.
 */
object LrcToSrt {

    internal val STAMP_RE = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    internal val INLINE_RE = Regex("""<\d+:\d{1,2}(?:[.:]\d{1,3})?>""")
    internal val OFFSET_RE = Regex("""^\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    /** How long the very last line stays, there being no stamp after it. */
    private const val LAST_LINE_MS = 5_000L

    /** The SubRip text, or null when the file has no timed line at all (it is not an LRC, or it is empty). */
    fun convert(lrc: String): String? {
        // Time -> the lines that start then (two rows with one stamp, say a line and its translation, share a cue).
        val timed = TreeMap<Long, MutableList<String>>()
        var offset = 0L
        for (raw in lrc.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            var row = raw.trim()
            val tag = OFFSET_RE.find(row)
            if (tag != null) {
                offset = tag.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val stamps = ArrayList<Long>()
            while (true) {
                val m = STAMP_RE.find(row)
                if (m == null || m.range.first != 0) break
                val minutes = m.groupValues[1].toLongOrNull() ?: break
                val seconds = m.groupValues[2].toLong()
                val millis = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                stamps.add(minutes * 60_000L + seconds * 1_000L + millis)
                row = row.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) continue
            val text = LyricsText.tidy(row.replace(INLINE_RE, "")) // notes like [موسيقى] are not words
            for (stamp in stamps) {
                // The offset tag: a positive value makes the lines come sooner.
                val lines = timed.getOrPut((stamp - offset).coerceAtLeast(0L)) { ArrayList() }
                if (text.isNotEmpty()) lines.add(text)
            }
        }

        val starts = timed.keys.toList()
        val out = StringBuilder()
        var count = 0
        for ((index, start) in starts.withIndex()) {
            val lines = timed[start].orEmpty()
            if (lines.isEmpty()) continue // an empty line: nothing to show, it only ends the line before it
            val end = if (index + 1 < starts.size) starts[index + 1] else start + LAST_LINE_MS
            count++
            out.append(count).append('\n')
            out.append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
            for (line in lines) out.append(line).append('\n')
            out.append('\n')
        }
        return if (count == 0) null else out.toString()
    }

    /** `HH:MM:SS,mmm`. Always with the plain digits 0-9: a phone set to Arabic would otherwise write Arabic-Indic ones. */
    private fun srtTime(ms: Long): String = String.format(
        Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000L, ms / 60_000L % 60L, ms / 1_000L % 60L, ms % 1_000L,
    )
}
