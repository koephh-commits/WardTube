// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import java.util.Locale

/**
 * How old a video is, worked out from the text YouTube wrote ("3 hours ago", "قبل ٣ ساعات", "il y a 2 jours") or from
 * the date of an RSS entry. Used by the "Today" chip and by the blue dot (only recent videos count as new).
 *
 * Words are compared by their beginning (so "hours", "hour" and "ساعات" all match), in English, Arabic, French,
 * Spanish, Portuguese and German. A text in any other language gives null: the video is then simply not "today".
 */
object SubsAge {

    private const val SECOND = 1.0 / 3600.0
    private const val MINUTE = 1.0 / 60.0
    private const val HOUR = 1.0
    private const val DAY = 24.0
    private const val WEEK = 168.0
    private const val MONTH = 720.0
    private const val YEAR = 8760.0

    /** The words that start each unit's names, the smallest unit first. */
    private val UNITS: List<Pair<Double, List<String>>> = listOf(
        SECOND to listOf("second", "segundo", "sekunde", "ثان", "ثوان"),
        MINUTE to listOf("minut", "دقيق", "دقائق"),
        HOUR to listOf("hour", "heure", "hora", "stunde", "ساع"),
        DAY to listOf("day", "jour", "dia", "d\u00eda", "tag", "يوم", "أيام", "ايام"),
        WEEK to listOf("week", "semaine", "semana", "woche", "أسبوع", "اسبوع", "أسابيع", "اسابيع"),
        MONTH to listOf("month", "mois", "mes", "m\u00eas", "monat", "شهر", "أشهر", "اشهر", "شهور"),
        YEAR to listOf("year", "a\u00f1o", "ano", "jahr", "سن", "عام", "أعوام", "اعوام"),
    )

    /** Arabic dual forms say the number inside the word ("ساعتين" is two hours). */
    private val DUAL: Map<String, Double> = mapOf(
        "ثانيتين" to SECOND,
        "دقيقتين" to MINUTE,
        "ساعتين" to HOUR,
        "يومين" to DAY,
        "أسبوعين" to WEEK,
        "اسبوعين" to WEEK,
        "شهرين" to MONTH,
        "سنتين" to YEAR,
        "عامين" to YEAR,
    )

    /** The age in hours of a written age such as "5 hours ago", or null when the text cannot be understood. */
    fun hours(text: String): Double? {
        if (text.isEmpty()) return null
        var number = -1L
        var i = 0
        while (i < text.length && number < 0) {
            if (Character.digit(text[i], 10) >= 0) {
                var value = 0L
                while (i < text.length && Character.digit(text[i], 10) >= 0 && value < 100_000L) {
                    value = value * 10 + Character.digit(text[i], 10)
                    i++
                }
                number = value
            } else {
                i++
            }
        }

        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in text.lowercase(Locale.ROOT)) {
            if (Character.isLetter(c)) {
                word.append(c)
            } else if (word.isNotEmpty()) {
                words.add(word.toString())
                word.setLength(0)
            }
        }
        if (word.isNotEmpty()) words.add(word.toString())

        for (w in words) {
            val dual = DUAL[w]
            if (dual != null) return dual * 2
            for ((size, stems) in UNITS) {
                if (stems.any { w.startsWith(it) }) return size * (if (number >= 0) number else 1L)
            }
        }
        return null
    }

    /** The age of [video] in hours, or null when it is not known. */
    fun ageHours(video: SubsVideo, now: Long): Double? =
        if (video.publishedMs > 0L) ((now - video.publishedMs) / 3_600_000.0).coerceAtLeast(0.0) else hours(video.published)

    /**
     * [videos] with the newest first. They are ordered by age, and videos of the same age keep the order they came in
     * (YouTube's own). A video whose age is not known (a scheduled stream, a language not understood) takes the age of
     * the one before it, so it stays beside it; a stream that is on now counts as the newest.
     */
    fun newestFirst(videos: List<SubsVideo>, now: Long): List<SubsVideo> {
        if (videos.size < 2) return videos
        val keys = DoubleArray(videos.size)
        var last = 0.0
        for ((index, video) in videos.withIndex()) {
            val age = if (video.live == 1) 0.0 else ageHours(video, now)
            if (age != null) last = age
            keys[index] = last
        }
        return videos.indices.sortedBy { keys[it] }.map { videos[it] }
    }

    /** True when the video is a day old or more (false when its age is not known). */
    fun isOlderThanDay(video: SubsVideo, now: Long): Boolean {
        val age = ageHours(video, now) ?: return false
        return age >= DAY
    }

    /** True when the video went up in the last 24 hours (a live stream that is on now counts). */
    fun isToday(video: SubsVideo, now: Long): Boolean {
        if (video.live == 1) return true
        val age = ageHours(video, now) ?: return false
        return age < DAY
    }
}
