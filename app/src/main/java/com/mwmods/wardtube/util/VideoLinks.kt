// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

/**
 * Reads which video a link names (a watch page, a Short, a live page or a youtu.be link) and builds the app's own
 * addresses for it. The address that is downloaded, copied or shared is always built here from the 11-character id, so
 * whatever else a page put into its link (a playlist, a start time, tracking data) never travels with it.
 */
object VideoLinks {

    class Video(val id: String, val short: Boolean) {
        /** The address the download flow starts from (the same one the Subscriptions screen and the player use). */
        val downloadUrl: String
            get() = if (short) "https://m.youtube.com/shorts/$id" else "https://m.youtube.com/watch?v=$id"

        /** The address that is copied or shared. */
        val shareUrl: String
            get() = if (short) "https://www.youtube.com/shorts/$id" else "https://youtu.be/$id"

        /** The video's picture, 320 x 180. */
        val thumbnailUrl: String
            get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    private val LINK = Regex(
        "^https?://(?:www\\.|m\\.|music\\.)?" +
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^\\s#]*&)?v=|shorts/|live/))" +
            "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])",
        RegexOption.IGNORE_CASE,
    )

    /** The video [link] names, or null when it is not a link to one video (a channel, a playlist, another site). */
    fun parse(link: String): Video? {
        val match = LINK.find(link.trim()) ?: return null
        return Video(match.groupValues[1], match.value.contains("/shorts/", ignoreCase = true))
    }
}
