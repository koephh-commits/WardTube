// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import java.net.URI
import java.util.Locale

/**
 * Tells a link to one video apart from a link to a list of them (a playlist, a channel, a profile), so the download
 * flow knows whether to ask for a choice of videos.
 *
 * YouTube and TikTok are recognised by their addresses. Every other site is [Kind.UNKNOWN]: yt-dlp itself decides
 * whether the page is a list (SoundCloud sets, Vimeo albums, Dailymotion playlists, Bilibili series...), so the
 * download flow asks it instead of keeping a list of address shapes that would always be out of date.
 *
 * No Android classes are used here on purpose: this is plain string and [URI] work.
 */
object PlaylistLinks {

    enum class Kind {
        /** One video (or a direct media address): download it as it is. */
        SINGLE,
        /** A YouTube video that was opened from a playlist: the person chooses between the video and the playlist. */
        VIDEO_IN_LIST,
        /** A list of videos: a playlist, a channel, a profile. */
        LIST,
        /** Another site: may be a video or a list, only yt-dlp can tell. */
        UNKNOWN,
    }

    /**
     * [videoUrl] is the link to give yt-dlp for one video (for [Kind.LIST] it is the same as [listUrl]); [listUrl] is
     * the link of the whole list, null when there is none.
     */
    class Analysis(val kind: Kind, val videoUrl: String, val listUrl: String? = null)

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
    private val LIST_PATTERN = Regex("[A-Za-z0-9_-]{2,}")
    private val MEDIA_EXTENSIONS = setOf(
        "m3u8", "mpd", "mp4", "m4v", "mov", "webm", "mkv", "flv", "avi", "ts", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac",
    )
    private val CHANNEL_TABS = setOf("videos", "shorts", "streams")

    fun analyze(url: String): Analysis {
        val noFragment = url.substringBefore('#')
        val rawQuery = if ('?' in noFragment) noFragment.substringAfter('?') else null
        val uri = try {
            URI(noFragment.substringBefore('?'))
        } catch (e: Exception) {
            return Analysis(Kind.SINGLE, url)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return Analysis(Kind.SINGLE, url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return Analysis(Kind.SINGLE, url)
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }

        return when {
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> tikTok(url, host, segments)
            !host.startsWith("accounts.google.") && UrlUtils.isInternalHost(host) -> youTube(url, host, segments, rawQuery)
            isMediaAddress(segments) -> Analysis(Kind.SINGLE, url)
            else -> Analysis(Kind.UNKNOWN, url)
        }
    }

    /** A direct media file or a stream (m3u8 / mpd): never a list of videos, so there is nothing to look up. */
    private fun isMediaAddress(segments: List<String>): Boolean {
        val last = segments.lastOrNull() ?: return false
        val dot = last.lastIndexOf('.')
        return dot >= 0 && last.substring(dot + 1).lowercase(Locale.ROOT) in MEDIA_EXTENSIONS
    }

    // ---- YouTube ----

    private fun youTube(url: String, host: String, segments: List<String>, rawQuery: String?): Analysis {
        val list = queryValue(rawQuery, "list")?.takeIf { LIST_PATTERN.matches(it) }
        val videoId = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> queryValue(rawQuery, "v")
            segments.size >= 2 && (segments[0] == "shorts" || segments[0] == "live") -> segments[1]
            else -> null
        }?.takeIf { ID_PATTERN.matches(it) }

        if (videoId != null) {
            if (list == null || segments.firstOrNull() == "shorts" || segments.firstOrNull() == "live") {
                return Analysis(Kind.SINGLE, url)
            }
            return Analysis(Kind.VIDEO_IN_LIST, "https://www.youtube.com/watch?v=$videoId", youTubeListUrl(videoId, list))
        }
        // /playlist?list=ID, or the common mistake /watch?list=ID (no video).
        if (list != null && (segments.firstOrNull() == "playlist" || segments.firstOrNull() == "watch")) {
            val listUrl = youTubeListUrl(null, list)
            return Analysis(Kind.LIST, listUrl, listUrl)
        }
        channelPath(segments)?.let { path ->
            val channel = "https://www.youtube.com$path"
            return Analysis(Kind.LIST, channel, channel)
        }
        return Analysis(Kind.SINGLE, url)
    }

    /**
     * The address of a playlist as yt-dlp reads it best.
     *
     * A Mix (`list=RD` + the id of the video it was made from) only exists around a video: the plain playlist page of
     * a Mix is empty or redirects, so it is always read through the watch page, with the video that goes with it
     * ([videoId] when the link has one, otherwise the id inside the list id).
     */
    fun youTubeListUrl(videoId: String?, list: String): String {
        val seed = mixSeed(list)
        val id = videoId ?: seed
        return if (list.startsWith("RD") && id != null) {
            "https://www.youtube.com/watch?v=$id&list=$list"
        } else {
            "https://www.youtube.com/playlist?list=$list"
        }
    }

    /** The video id inside a Mix id ("RD" + 11 characters), else null. YouTube Music's "RDCLAK..." lists are plain playlists. */
    private fun mixSeed(list: String): String? =
        if (list.length == 13 && list.startsWith("RD")) list.substring(2).takeIf { ID_PATTERN.matches(it) } else null

    /**
     * The path of a channel's video list (`/@name/videos`, `/channel/ID/videos`...), or null when [segments] are not a
     * channel. Only the video tabs are lists of videos; any other tab (home, playlists, community) is read as Videos.
     */
    fun channelPath(segments: List<String>): String? {
        val first = segments.firstOrNull() ?: return null
        val base: List<String>
        val rest: List<String>
        when {
            first.startsWith("@") && first.length > 1 -> {
                base = listOf(first)
                rest = segments.drop(1)
            }
            (first == "channel" || first == "c" || first == "user") && segments.size >= 2 -> {
                base = segments.take(2)
                rest = segments.drop(2)
            }
            else -> return null
        }
        val tab = rest.firstOrNull()?.takeIf { it in CHANNEL_TABS } ?: "videos"
        return "/" + (base + tab).joinToString("/")
    }

    // ---- TikTok ----

    /** A profile (`/@user`) and a collection (`/@user/collection/NAME-ID`) are lists; videos and short links are not. */
    private fun tikTok(url: String, host: String, segments: List<String>): Analysis {
        val first = segments.firstOrNull().orEmpty()
        val profile = first.startsWith("@") && segments.size == 1 && !host.startsWith("vm.") && !host.startsWith("vt.")
        val collection = first.startsWith("@") && segments.size >= 3 &&
            (segments[1] == "collection" || segments[1] == "playlist")
        return if (profile || collection) Analysis(Kind.LIST, url, url) else Analysis(Kind.SINGLE, url)
    }

    private fun queryValue(rawQuery: String?, name: String): String? =
        rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
}
