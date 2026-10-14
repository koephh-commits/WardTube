// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import java.io.File
import java.util.Locale

/**
 * YouTube's automatic captions are "rolling": every cue repeats the previous line and adds a new one, and between
 * cues there are 10 ms filler cues. yt-dlp converts them as they are, so the SRT / LRC ends up with every line two or
 * three times, blank cues and [music] tags. This rewrites the file with each line once, timed to when it first appears.
 */
object SubtitleCleaner {

    private class Cue(val start: Long, val end: Long, val lines: List<String>)

    private val TIME_RE = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val DIGITS_RE = Regex("""\d+""")
    private val LRC_RE = Regex("""^\[(\d+):(\d{2})(?:[.:](\d{1,3}))?](.*)$""")
    private val TAG_RE = Regex("""^\s*[\[(].*[])]\s*$""") // [موسيقى], [Music], (applause)
    private val TAG_PREFIX_RE = Regex("""^\s*[\[(][^\])]*[])]\s*""")
    private val TAG_ONLY_PREFIX_RE = Regex("""^\s*[♪♫]+\s*|\s*[♪♫]+\s*$""")

    fun clean(file: File) {
        runCatching {
            val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            val out = when (file.extension.lowercase()) {
                "srt" -> toSrt(dedupe(parseSrt(text)))
                "lrc" -> toLrc(dedupe(parseLrc(text)))
                else -> return
            }
            // Never replace a file with an empty one.
            if (out.isNotBlank()) file.writeText(out, Charsets.UTF_8)
        }
    }

    // ---- parsing -------------------------------------------------------------------------------------------------

    private fun ms(h: String, m: String, s: String, frac: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + frac.padEnd(3, '0').take(3).toLong()

    /**
     * Reads the file line by line instead of splitting it on blank lines. YouTube's rolling captions give the cue that
     * introduces the first line of a block an empty (or space-only) first row; splitting on blank lines cut that cue in
     * two and threw the text half away, so the first line of every block was lost (and showed up late, from the 10 ms
     * filler cue that repeats it).
     */
    private fun parseSrt(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        var start = 0L
        var end = 0L
        var inCue = false
        var body = ArrayList<String>()
        fun flush() {
            if (inCue) cues += Cue(start, end, body.map { it.trim() }.filter { it.isNotEmpty() })
        }
        for (row in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val m = TIME_RE.find(row)
            if (m != null) {
                // The number right above a timing line is the next cue's index, not text.
                if (body.lastOrNull()?.trim()?.matches(DIGITS_RE) == true) body.removeAt(body.lastIndex)
                flush()
                val g = m.groupValues
                start = ms(g[1], g[2], g[3], g[4])
                end = ms(g[5], g[6], g[7], g[8])
                inCue = true
                body = ArrayList()
            } else if (inCue) {
                body.add(row)
            }
        }
        flush()
        return cues
    }

    private fun parseLrc(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (row in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val m = LRC_RE.find(row.trim()) ?: continue
            val g = m.groupValues
            val start = ms("0", g[1], "0", "").let { it + g[2].toLong() * 1000 + (g[3].ifEmpty { "0" }.padEnd(3, '0').take(3).toLong()) }
            cues += Cue(start, start, listOf(g[4].trim()).filter { it.isNotEmpty() })
        }
        return cues
    }

    // ---- cleaning ------------------------------------------------------------------------------------------------

    private fun tidy(line: String): String? {
        var s = line.replace(TAG_ONLY_PREFIX_RE, "").trim()
        if (TAG_RE.matches(s)) return null
        s = s.replace(TAG_PREFIX_RE, "").trim()
        return s.ifEmpty { null }
    }

    /** One entry per distinct line, in order, timed from the cue where it first showed up. */
    private fun dedupe(cues: List<Cue>): List<Cue> {
        val result = ArrayList<Cue>()
        var last: String? = null
        for (cue in cues.sortedBy { it.start }) {
            for (raw in cue.lines) {
                val line = tidy(raw) ?: continue
                if (line == last) continue
                result += Cue(cue.start, cue.end, listOf(line))
                last = line
            }
        }
        // A line stays on screen until the next one begins (but never longer than 8 s).
        return result.mapIndexed { i, c ->
            val next = result.getOrNull(i + 1)?.start
            val end = when {
                next != null -> minOf(maxOf(next, c.start + 300), c.start + 8000)
                else -> maxOf(c.end, c.start + 2000)
            }
            Cue(c.start, end, c.lines)
        }
    }

    // ---- writing -------------------------------------------------------------------------------------------------

    // Locale.ROOT on purpose: with the phone set to Arabic, format() would write Arabic-Indic digits (٠٠:٤١,٧٥٠), which
    // no player reads as a timestamp.
    private fun srtTime(t: Long): String =
        "%02d:%02d:%02d,%03d".format(Locale.ROOT, t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)

    private fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(srtTime(c.start)).append(" --> ").append(srtTime(c.end)).append('\n')
            append(c.lines.joinToString("\n")).append("\n\n")
        }
    }

    private fun toLrc(cues: List<Cue>): String = buildString {
        for (c in cues) {
            append("[%02d:%02d.%02d]".format(Locale.ROOT, c.start / 60_000, c.start / 1000 % 60, c.start % 1000 / 10))
            append(c.lines.joinToString(" ")).append('\n')
        }
    }
}
