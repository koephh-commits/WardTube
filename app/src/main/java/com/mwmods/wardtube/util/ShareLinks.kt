// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import java.net.URI
import java.util.Locale

/**
 * Finds the video link in the text another app shares with WardTube (the YouTube app, TikTok, Facebook, a browser...).
 *
 * Shared text is rarely a bare link: TikTok sends "Check out this video! #tag https://vm.tiktok.com/ZM.../" and
 * YouTube adds tracking parameters (`?si=...`). The link is cut out of the text first.
 *
 * Any site is accepted and yt-dlp, which knows well over a thousand of them, decides whether it can download the page.
 * Only YouTube and TikTok get more attention: their links are rewritten to a plain form (so the same video always gives
 * the same URL, which the download queue uses to spot a duplicate), and their pages that are not a video (channels,
 * playlists, profiles, photo posts) are turned down, because yt-dlp would take them for a whole list of videos.
 *
 * No Android classes are used here on purpose: this is plain string and [URI] work.
 */
object ShareLinks {

    enum class Site { YOUTUBE, TIKTOK, OTHER }

    /** A link to download: [url] is the cleaned link yt-dlp is given. */
    class Link(val url: String, val site: Site)

    /** What was found in a shared text. */
    sealed interface Result {
        /** A link to give to yt-dlp. */
        class Found(val link: Link) : Result
        /** Links are there, but none can be a video: a YouTube / TikTok page that is not one, or a site's bare home page. */
        object NotVideo : Result
        /** No link at all. */
        object NoLink : Result
    }

    // ASCII only, so a link written right before Arabic text does not swallow the first Arabic letters.
    private val URL_PATTERN = Regex("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&()*+,;=%]+", RegexOption.IGNORE_CASE)
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
    private val DIGITS = Regex("[0-9]+")
    private const val TRAILING_PUNCTUATION = ".,;:!?)]"

    /**
     * The link to download in [text], else says whether there was any link at all. A YouTube / TikTok video wins over
     * a link to another site, wherever they are in the text; otherwise the first link of another site is taken.
     */
    fun find(text: String?): Result {
        var sawLink = false
        var other: Link? = null
        for (match in URL_PATTERN.findAll(text.orEmpty())) {
            sawLink = true
            val link = classify(match.value.trimEnd { it in TRAILING_PUNCTUATION }) ?: continue
            if (link.site != Site.OTHER) return Result.Found(link)
            if (other == null) other = link
        }
        return when {
            other != null -> Result.Found(other)
            sawLink -> Result.NotVideo
            else -> Result.NoLink
        }
    }

    /** The first http(s) address written in [text], as typed (trailing punctuation cut off), or null when there is none. */
    fun firstUrl(text: String?): String? =
        URL_PATTERN.find(text.orEmpty())?.value?.trimEnd { it in TRAILING_PUNCTUATION }?.takeIf { it.isNotEmpty() }

    /** The cleaned [Link] for one URL, or null when it cannot be a video (see the class comment). */
    fun classify(url: String): Link? {
        // The query is split off first: it is only read for YouTube's `v=`, and odd characters in tracking
        // parameters must not make the whole link unreadable for URI.
        val noFragment = url.substringBefore('#')
        val rawQuery = if ('?' in noFragment) noFragment.substringAfter('?') else null
        val uri = try {
            URI(noFragment.substringBefore('?'))
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return when {
            isTikTokHost(host) -> tikTok(host, segments)
            isYouTubeHost(host) -> youTube(host, segments, rawQuery)
            // Any other site: left as it is (Facebook's `?v=`, Twitter's status ids... all live in the address).
            // Only a bare home page is turned down.
            segments.isEmpty() -> null
            else -> Link(noFragment, Site.OTHER)
        }
    }

    // ---- YouTube ----

    private fun isYouTubeHost(host: String): Boolean =
        !host.startsWith("accounts.google.") && UrlUtils.isInternalHost(host)

    /** youtu.be/ID, youtube.com/watch?v=ID, /shorts/ID and /live/ID. Channels, playlists and the home page are not videos. */
    private fun youTube(host: String, segments: List<String>, rawQuery: String?): Link? {
        val id = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> queryValue(rawQuery, "v")
            segments.size >= 2 && (segments[0] == "shorts" || segments[0] == "live") -> segments[1]
            else -> null
        }
        if (id == null || !ID_PATTERN.matches(id)) return null
        // One form for every kind of link: it also drops `si`, `t`, `list`... which a download does not need.
        return Link("https://www.youtube.com/watch?v=$id", Site.YOUTUBE)
    }

    private fun queryValue(rawQuery: String?, name: String): String? =
        rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)

    // ---- TikTok ----

    private fun isTikTokHost(host: String): Boolean = host == "tiktok.com" || host.endsWith(".tiktok.com")

    /**
     * Short links (vm. / vt.tiktok.com/CODE, tiktok.com/t/CODE), full links (tiktok.com/@user/video/ID) and the old
     * mobile form (m.tiktok.com/v/ID.html). Photo posts (/@user/photo/ID) are not videos. The query (`?_t=...&_r=1`) is
     * only tracking and is dropped.
     */
    private fun tikTok(host: String, segments: List<String>): Link? {
        val path = when {
            (host.startsWith("vm.") || host.startsWith("vt.")) && segments.isNotEmpty() ->
                "/" + segments[0] + "/"
            segments.size >= 3 && segments[0].startsWith("@") && segments[1] == "video" && DIGITS.matches(segments[2]) ->
                "/" + segments.take(3).joinToString("/")
            segments.size >= 2 && segments[0] == "t" -> "/t/" + segments[1] + "/"
            segments.size >= 2 && segments[0] == "v" -> "/v/" + segments[1]
            else -> return null
        }
        return Link("https://$host$path", Site.TIKTOK)
    }
}
