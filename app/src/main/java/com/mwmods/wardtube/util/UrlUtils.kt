// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import android.net.Uri
import java.util.Locale

object UrlUtils {

    fun isWatchPage(url: String?): Boolean = pathOf(url)?.startsWith("/watch") == true

    /** True for a Shorts page (the vertical feed of short videos). */
    fun isShortsPage(url: String?): Boolean = pathOf(url)?.startsWith("/shorts") == true

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

    /** True for YouTube's own hosts (youtube.com, m.youtube.com, youtube.co.uk, youtu.be, accounts.google.*). */
    fun isInternalHost(host: String): Boolean {
        if (host == "youtu.be" || host.endsWith(".youtu.be")) return true
        if (host.startsWith("accounts.google.")) return true
        val labels = host.split('.')
        val index = labels.indexOf("youtube")
        if (index < 0) return false
        return when (labels.size - index) {
            2 -> true // youtube.com, m.youtube.com
            3 -> labels[index + 1] == "co" || labels[index + 1] == "com" // youtube.co.uk, youtube.com.tr
            else -> false
        }
    }

    /** True for a http(s) link to one of YouTube's hosts. */
    fun isYouTubeUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = Uri.parse(url)
        if (!uri.isHierarchical) return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return isInternalHost(host)
    }

    /** True for a link to a video: a YouTube watch page or a youtu.be short link. */
    fun isVideoUrl(url: String?): Boolean {
        if (!isYouTubeUrl(url)) return false
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            return uri.pathSegments.firstOrNull()?.let { ID_PATTERN.matches(it) } == true
        }
        return isWatchPage(url)
    }

    /** The video id of a watch page or youtu.be link, or null. */
    fun videoId(url: String?): String? {
        if (url == null) return null
        val uri = Uri.parse(url)
        if (!uri.isHierarchical) return null
        val host = uri.host?.lowercase(Locale.ROOT)
        val id = if (host == "youtu.be") uri.pathSegments.firstOrNull() else uri.getQueryParameter("v")
        return if (id != null && ID_PATTERN.matches(id)) id else null
    }

    /** Removes the `t` (start time) parameter. */
    fun withoutTime(url: String): String {
        val uri = Uri.parse(url)
        if (!uri.isHierarchical || uri.getQueryParameter("t") == null) return url
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == "t") continue
            for (value in uri.getQueryParameters(name)) builder.appendQueryParameter(name, value)
        }
        return builder.build().toString()
    }

    private fun pathOf(url: String?): String? {
        if (url == null) return null
        val uri = Uri.parse(url)
        return if (uri.isHierarchical) uri.path else null
    }
}
