// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/** One row of the words as the lyrics view shows it: from [startMs] until the next row starts. [isBreak] is a "♪" pause row. */
class LyricLine(val startMs: Long, val endMs: Long, val text: String, val isBreak: Boolean = false)

/**
 * Turns a lyrics file (LRC, SubRip or WebVTT) into the list of rows the lyrics view shows, all of them at once, so the
 * song's words can be read as a whole and the one being sung is highlighted ([LyricsView]) instead of one line at a time.
 *
 * A pause of six seconds or more (an intro, a solo, a gap between two verses) becomes a "♪" row, so the view does not look
 * stuck on the last sung line while the music plays on. Notes of the caption that are not words (`[موسيقى]`, `(Music)`, `>>`)
 * are taken out ([LyricsText]); a row that was only such a note counts as a pause.
 */
object LyricsParser {

    private const val BREAK_MIN_MS = 6_000L
    private const val LAST_LINE_MS = 5_000L
    private const val BREAK_TEXT = "♪"

    private val TIME_RE = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val TAG_RE = Regex("""<[^>]*>|\{\\[^\}]*\}""")

    /** A timed row before it is shaped for display: [end] is null for LRC (a row lasts until the next stamp); empty text marks a pause. */
    private class Raw(val start: Long, val end: Long?, val text: String)

    /** The rows of [text], a file called [name] (the extension tells LRC from SubRip / WebVTT). Empty when nothing is timed. */
    fun parse(name: String, text: String): List<LyricLine> {
        val raw = if (name.endsWith(".lrc", ignoreCase = true)) parseLrc(text) else parseCues(text)
        return build(raw)
    }

    private fun lines(text: String): List<String> =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** The same reading rules as [LrcToSrt] (several stamps per row, the offset tag, word stamps dropped), but empty rows are kept as pauses. */
    private fun parseLrc(lrc: String): List<Raw> {
        val timed = TreeMap<Long, MutableList<String>>()
        var offset = 0L
        for (rawRow in lines(lrc)) {
            var row = rawRow.trim()
            val tag = LrcToSrt.OFFSET_RE.find(row)
            if (tag != null) {
                offset = tag.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val stamps = ArrayList<Long>()
            while (true) {
                val m = LrcToSrt.STAMP_RE.find(row)
                if (m == null || m.range.first != 0) break
                val minutes = m.groupValues[1].toLongOrNull() ?: break
                val seconds = m.groupValues[2].toLong()
                val millis = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                stamps.add(minutes * 60_000L + seconds * 1_000L + millis)
                row = row.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) continue
            // Notes such as [موسيقى] are not words: a row with only a note is an empty row, a pause.
            val text = LyricsText.tidy(row.replace(LrcToSrt.INLINE_RE, ""))
            for (stamp in stamps) {
                val list = timed.getOrPut((stamp - offset).coerceAtLeast(0L)) { ArrayList() }
                if (text.isNotEmpty()) list.add(text)
            }
        }
        return timed.map { (start, rows) -> Raw(start, null, rows.joinToString("\n")) }
    }

    /** SubRip and WebVTT: a time row (`00:01:02,500 --> 00:01:05,000`), then the words up to the next blank row. */
    private fun parseCues(text: String): List<Raw> {
        val out = ArrayList<Raw>()
        val rows = lines(text)
        var i = 0
        while (i < rows.size) {
            val m = TIME_RE.find(rows[i])
            if (m == null) {
                i++
                continue
            }
            val start = millis(m, 1)
            val end = millis(m, 5)
            i++
            val body = ArrayList<String>()
            var noteOnly = false
            while (i < rows.size && rows[i].isNotBlank()) {
                val cleaned = clean(rows[i])
                val words = LyricsText.tidy(cleaned)
                if (words.isNotEmpty()) body.add(words) else if (cleaned.isNotEmpty()) noteOnly = true
                i++
            }
            if (body.isNotEmpty()) {
                out.add(Raw(start, maxOf(end, start), body.joinToString("\n")))
            } else if (noteOnly) {
                out.add(Raw(start, null, "")) // only [موسيقى] and the like: a pause until the next words
            }
        }
        return out.sortedBy { it.start }
    }

    private fun millis(m: MatchResult, group: Int): Long {
        val v = m.groupValues
        val hours = v[group].toLongOrNull() ?: 0L
        val minutes = v[group + 1].toLong()
        val seconds = v[group + 2].toLong()
        val fraction = v[group + 3].padEnd(3, '0').take(3).toLong()
        return ((hours * 60L + minutes) * 60L + seconds) * 1_000L + fraction
    }

    private fun clean(row: String): String = TAG_RE.replace(row, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .trim()

    private fun build(raw: List<Raw>): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for ((i, r) in raw.withIndex()) {
            val nextStart = raw.getOrNull(i + 1)?.start
            val end = r.end ?: nextStart ?: (r.start + LAST_LINE_MS)
            if (r.text.isEmpty()) {
                // A pause marker (an empty LRC row, or a row that was only a note): the first of a run speaks for the run,
                // up to the next words. Shown only when long, and never as the very first row (the intro is made below).
                if (i > 0 && raw[i - 1].text.isEmpty()) continue
                var j = i + 1
                while (j < raw.size && raw[j].text.isEmpty()) j++
                val runEnd = raw.getOrNull(j)?.start ?: (r.start + LAST_LINE_MS)
                if (out.isNotEmpty() && runEnd - r.start >= BREAK_MIN_MS) out.add(LyricLine(r.start, runEnd, BREAK_TEXT, true))
                continue
            }
            // A long wait before the first sung row (an intro).
            if (out.isEmpty() && r.start >= BREAK_MIN_MS) out.add(LyricLine(0L, r.start, BREAK_TEXT, true))
            out.add(LyricLine(r.start, end, r.text))
            // A SubRip row that ends long before the next one starts: the gap between is a pause.
            val nextIsPause = raw.getOrNull(i + 1)?.text?.isEmpty() == true
            if (r.end != null && nextStart != null && !nextIsPause && nextStart - r.end >= BREAK_MIN_MS) {
                out.add(LyricLine(r.end, nextStart, BREAK_TEXT, true))
            }
        }
        return out
    }
}

/**
 * The parsed rows of the songs in the player's queue, kept in memory for the screen: it is made again (from the notification,
 * from the Library's mini player) while the queue stays, and the rows are read once, when the queue starts.
 * Keyed by the song (its media id) and the name of the lyrics file as the subtitle track carries it.
 */
object LyricsStore {

    private val rows = ConcurrentHashMap<String, List<LyricLine>>()
    private val labels = ConcurrentHashMap<String, List<String>>()

    private fun key(mediaId: String, label: String) = "$mediaId\u0000$label"

    /** A new queue starts: the old songs' words are not needed any more. */
    fun clear() {
        rows.clear()
        labels.clear()
    }

    fun put(mediaId: String, label: String, value: List<LyricLine>) {
        // Putting the same song's same file again (the Library queue builds the items of a song that already plays) replaces
        // its rows and does not list the label twice: a doubled label would hide the song's only lyrics file from get().
        val known = rows.put(key(mediaId, label), value) != null
        if (!known) labels.merge(mediaId, listOf(label)) { a, b -> a + b }
    }

    /** The rows of the lyrics track [label] of the song [mediaId]; when the song has just one lyrics file, that one whatever the label says. */
    fun get(mediaId: String?, label: String?): List<LyricLine>? {
        if (mediaId == null) return null
        if (label != null) rows[key(mediaId, label)]?.let { return it }
        return labels[mediaId]?.singleOrNull()?.let { rows[key(mediaId, it)] }
    }
}
