// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

/**
 * Keeps the words and leaves out what is not sung. Captions (above all YouTube's automatic ones) mix the words with notes
 * for the reader: `[موسيقى]`, `(Music)`, `[Applause]`, `[Chorus]`, a `>>` where the speaker changes, music marks (♪).
 * They are not lyrics, so the lyrics view and the lyrics files the app saves do not show them.
 *
 * Only a bracket that names a sound or a part of the song is removed (`(ooh ooh)` is sung and stays), wherever it stands in
 * the line: at the end of a line of words, as in `راح صفي المالهم داعي حياتي [موسيقى]`, as well as alone.
 */
object LyricsText {

    private val BRACKET_RE = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^\}]*\}|［[^］]*］|（[^）]*）|【[^】]*】""")

    /** What a bracket must mention to be a note and not a sung word (English and Arabic). */
    private val NOTE_RE = Regex(
        "music|instrumental|applause|laugh|cheer|clap|singing|humming|intro|outro|chorus|verse|bridge|silence|noise|" +
            "inaudible|\\bbgm\\b|\\bsfx\\b|" +
            "موسيق|تصفيق|ضحك|غناء|غِناء|يغني|تغني|مقدمة|خاتمة|لازمة|كورس|صمت|هتاف|ترنم|ترنيم|إنشاد|انشاد|إيقاع|ايقاع|مؤثرات",
        RegexOption.IGNORE_CASE,
    )

    private val SYMBOLS_RE = Regex("[♪♫♬♩🎵🎶]")

    /** The mark of a change of speaker (`>>`, `»`), and a dialogue dash, at the start of a line. */
    private val SPEAKER_RE = Regex("""^(?:\s*(?:[>»›]+|[-–—]+(?=\s)))+\s*""")
    private val SPACES_RE = Regex("""\s+""")

    /** [line] with the notes taken out; empty when nothing sung is left (the line was only a note, or a music mark). */
    fun tidy(line: String): String {
        var s = BRACKET_RE.replace(line) { if (NOTE_RE.containsMatchIn(it.value)) "" else it.value }
        s = SYMBOLS_RE.replace(s, " ")
        s = SPACES_RE.replace(s, " ").trim()
        return SPEAKER_RE.replace(s, "").trim()
    }
}
