// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import android.net.Uri
import java.net.URL
import java.util.Locale

object UrlUtils {

    /** True for a YouTube watch page: the path is exactly `/watch` (or below it), on one of YouTube's hosts. */
    fun isWatchPage(url: String?): Boolean = isYouTubeUrl(url) && pathIs(url, "/watch")

    /** True for a Shorts page (the vertical feed of short videos), on one of YouTube's hosts. */
    fun isShortsPage(url: String?): Boolean = isYouTubeUrl(url) && pathIs(url, "/shorts")

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

    /**
     * YouTube's own domains. A host counts when it is exactly one of these or a subdomain of one
     * (so `m.youtube.com` matches, `youtube.com.evil.net` and `youtube.xyz` do not).
     * Add a country domain here only when it is really needed.
     */
    private val YOUTUBE_DOMAINS = setOf("youtube.com", "youtube.co.uk", "youtube.com.tr", "youtu.be")

    /** Google sign-in hosts, matched exactly (no prefix matching: `accounts.google.evil.com` must not pass). */
    private val GOOGLE_ACCOUNTS_HOSTS = setOf(
        "accounts.google.com",
        "accounts.google.co.uk",
        "accounts.google.com.tr",
    )

    /** True for YouTube's own hosts (see [YOUTUBE_DOMAINS]) and Google's sign-in hosts (see [GOOGLE_ACCOUNTS_HOSTS]). */
    fun isInternalHost(host: String): Boolean {
        val h = normalizeHost(host)
        if (h in GOOGLE_ACCOUNTS_HOSTS) return true
        return YOUTUBE_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /** True for a http(s) link to one of YouTube's hosts. */
    fun isYouTubeUrl(url: String?): Boolean {
        if (url == null || hasAmbiguousChars(url)) return false
        val uri = Uri.parse(url)
        if (!uri.isHierarchical) return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        // A real YouTube link never carries credentials; one that does is trying to look like something else.
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return isInternalHost(host)
    }

    /** True for a link to a video: a YouTube watch page or a youtu.be short link. */
    fun isVideoUrl(url: String?): Boolean {
        if (url == null || !isYouTubeUrl(url)) return false
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (isShortLinkHost(host)) {
            return uri.pathSegments.firstOrNull()?.let { ID_PATTERN.matches(it) } == true
        }
        return isWatchPage(url)
    }

    /** The video id of a watch page or youtu.be link, or null. Only YouTube's own hosts are accepted. */
    fun videoId(url: String?): String? {
        if (url == null || !isYouTubeUrl(url)) return null
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val id = if (isShortLinkHost(host)) uri.pathSegments.firstOrNull() else uri.getQueryParameter("v")
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

    /**
     * True for a plain `http://` address to a site on the internet: nothing in it is encrypted. Addresses on the phone
     * itself or on a home network (localhost, 127.x, 10.x, 192.168.x, 172.16-31.x, *.local) are not counted: plain http
     * is how those are normally served, and a warning there would only be noise.
     *
     * When Android's parser cannot be trusted to read the address the way the WebView will (odd characters, or no
     * host at all), the address is never treated as a local one, so the warning is shown rather than hidden.
     */
    fun isInsecureWeb(url: String?): Boolean {
        if (url == null || !url.startsWith("http://", ignoreCase = true)) return false
        if (hasAmbiguousChars(url)) return true
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return true
        return !isLocalHost(host)
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        return when {
            n[0] == 10 || n[0] == 127 -> true
            n[0] == 192 && n[1] == 168 -> true
            n[0] == 172 && n[1] in 16..31 -> true
            else -> false
        }
    }

    /** The same address over https (port 80 becomes the default port); null when [url] is not a plain http address. */
    fun toHttps(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true)) return null
        if (hasAmbiguousChars(url)) return null
        return try {
            val plain = URL(url)
            if (plain.host.isNullOrEmpty()) return null
            val secure = URL("https", plain.host, if (plain.port == 80) -1 else plain.port, plain.file).toString()
            if (plain.ref.isNullOrEmpty()) secure else "$secure#${plain.ref}"
        } catch (e: Exception) {
            null
        }
    }

    private fun pathOf(url: String?): String? {
        if (url == null) return null
        val uri = Uri.parse(url)
        return if (uri.isHierarchical) uri.path else null
    }

    /** True when the path is exactly [base] or sits below it (`/watch` and `/watch/x`, but not `/watch-next`). */
    private fun pathIs(url: String?, base: String): Boolean {
        val path = pathOf(url) ?: return false
        return path == base || path.startsWith("$base/")
    }

    private fun isShortLinkHost(host: String): Boolean {
        val h = normalizeHost(host)
        return h == "youtu.be" || h.endsWith(".youtu.be")
    }

    /** Lower case, without the trailing dot of a fully qualified name (`youtube.com.` is `youtube.com`). */
    private fun normalizeHost(host: String): String = host.lowercase(Locale.ROOT).trimEnd('.')

    /**
     * Android's [Uri] and Chromium (the WebView) do not read a few odd addresses the same way. For example,
     * `https://evil.com\@youtube.com/` has the host youtube.com for [Uri] but goes to evil.com in the WebView.
     * A backslash or a control character has no place in a normal address here, so such a URL is never trusted.
     */
    private fun hasAmbiguousChars(url: String): Boolean =
        url.any { it == '\\' || it < ' ' || it == '\u007f' }
}
