// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.adblock

/**
 * Resource types of the filter syntax, as bit flags.
 *
 * WebView does not tell us what kind of resource a request is for, so [infer] makes an
 * educated guess from the file extension and the Accept header.
 */
internal object ResourceType {
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLESHEET = 4
    const val XHR = 8
    const val SUBDOCUMENT = 16
    const val MEDIA = 32
    const val FONT = 64
    const val OBJECT = 128
    const val PING = 256
    const val OTHER = 512
    const val ALL = 1023

    /** A request we cannot classify may be any of these. */
    const val UNKNOWN = SCRIPT or XHR or PING or OTHER

    fun fromOption(name: String): Int? = when (name) {
        "script" -> SCRIPT
        "image" -> IMAGE
        "stylesheet", "css" -> STYLESHEET
        "xmlhttprequest", "xhr" -> XHR
        "subdocument", "frame" -> SUBDOCUMENT
        "media" -> MEDIA
        "font" -> FONT
        "object" -> OBJECT
        "ping" -> PING
        "other" -> OTHER
        else -> null
    }

    fun infer(path: String, accept: String?): Int {
        val dot = path.lastIndexOf('.')
        val extension = if (dot >= 0 && path.length - dot <= 6) path.substring(dot + 1) else ""
        return when (extension) {
            "js", "mjs" -> SCRIPT
            "css" -> STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif" -> IMAGE
            "mp4", "webm", "m3u8", "mp3", "ogg", "m4a", "ts", "m4s", "mpd" -> MEDIA
            "woff", "woff2", "ttf", "otf", "eot" -> FONT
            "html", "htm" -> SUBDOCUMENT
            else -> when {
                accept == null -> UNKNOWN
                accept.startsWith("text/html") -> SUBDOCUMENT
                accept.contains("image/") -> IMAGE
                accept.contains("text/css") -> STYLESHEET
                else -> UNKNOWN
            }
        }
    }
}
