// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.net.Uri
import org.json.JSONObject

/**
 * What the open page told the server when it asked for a media file (the page as referer, the WebView's user agent and
 * the cookies of the file's own host), plus the name the saved file should get. It is carried inside the address
 * itself, after `#__youtubedl_smuggle=`, so it travels with the job: through the queue, a retry from the Library, and
 * into yt-dlp, with no change to the queue's data.
 *
 * yt-dlp already reads a `referer` smuggled there (and removes the whole part before using the link). The rest is our
 * own keys in the same JSON, which yt-dlp ignores: [DownloadService] turns [headersOf] into `--add-header` options and
 * [nameOf] into the saved file's name.
 *
 * Why: the video servers of many sites answer only a request that looks like the page's own one. The system's download
 * manager used to be sent exactly these three headers for a file found in the browser; the same headers now go to
 * yt-dlp, so the file is downloaded by the app's own queue (progress, waiting list, retry) and still gets answered.
 */
object RequestHeaders {

    private const val MARK = "#__youtubedl_smuggle="
    private const val KEY_REFERER = "referer"
    private const val KEY_HEADERS = "wt_headers"
    private const val KEY_NAME = "wt_name"
    private const val KEY_INSECURE = "wt_insecure"

    private const val MAX_NAME_LENGTH = 80

    /** The only headers that are ever passed on, whatever the address carries. */
    private val ALLOWED = listOf("Referer", "User-Agent", "Cookie")

    private val BAD_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val CONTROL_CHARS = Regex("\\p{Cntrl}")

    /**
     * [url] with the page's identity attached. [referer] is the open page, [userAgent] the WebView's, [cookie] the
     * cookie line of [url]'s host, [name] the page's title (the file is named after it instead of after the server's
     * file name, which is often just "480p.mp4" for every episode). Anything blank is left out; an address with
     * nothing to attach is returned as it is. [allowInsecure] records that the person accepted the warning about a plain
     * http link whose site has no https version: only then does [FileDownloader] connect over http.
     */
    fun attach(
        url: String,
        referer: String?,
        userAgent: String?,
        cookie: String?,
        name: String?,
        allowInsecure: Boolean = false,
    ): String {
        val base = url.substringBefore('#')
        val data = JSONObject()
        val headers = JSONObject()

        val page = referer?.substringBefore('#')?.takeIf { isWeb(it) }
        if (page != null) {
            // The first key is the one yt-dlp's own extractors read; the header below is the one the download sends.
            data.put(KEY_REFERER, page)
            headers.put("Referer", page)
        }
        clean(userAgent)?.let { headers.put("User-Agent", it) }
        clean(cookie)?.let { headers.put("Cookie", it) }
        if (headers.length() > 0) data.put(KEY_HEADERS, headers)

        name?.replace(BAD_NAME_CHARS, "_")?.trim()?.take(MAX_NAME_LENGTH)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { data.put(KEY_NAME, it) }

        if (allowInsecure) data.put(KEY_INSECURE, true)

        if (data.length() == 0) return base
        return base + MARK + Uri.encode(data.toString())
    }

    /** The headers [attach] put in [url], by name; empty for an address without any. */
    fun headersOf(url: String): Map<String, String> {
        val headers = read(url)?.optJSONObject(KEY_HEADERS) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (name in ALLOWED) {
            clean(headers.optString(name))?.let { result[name] = it }
        }
        return result
    }

    /** The file name (without extension) [attach] asked for, or null. */
    fun nameOf(url: String): String? = read(url)?.optString(KEY_NAME)?.takeIf { it.isNotBlank() }

    /** True when the person accepted the warning and allowed this link to be downloaded over plain http. */
    fun allowsInsecure(url: String): Boolean = read(url)?.optBoolean(KEY_INSECURE, false) == true

    /** [url] as a person should see it: without the part that only carries headers. */
    fun display(url: String): String = url.substringBefore(MARK)

    private fun read(url: String): JSONObject? {
        val encoded = url.substringAfter(MARK, "")
        if (encoded.isEmpty()) return null
        return try {
            JSONObject(Uri.decode(encoded))
        } catch (e: Exception) {
            null
        }
    }

    /** A header value on one line, or null when nothing is left. A line break could start another header. */
    private fun clean(value: String?): String? =
        value?.replace(CONTROL_CHARS, "")?.trim()?.takeIf { it.isNotEmpty() }

    private fun isWeb(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}
