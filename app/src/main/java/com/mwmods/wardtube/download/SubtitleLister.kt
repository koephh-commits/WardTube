// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.util.Locale

/** One subtitle track the video offers. [code] is what yt-dlp's `--sub-langs` expects. */
data class SubtitleOption(val code: String, val kind: Kind) {
    enum class Kind { MANUAL, AUTO, AUTO_TRANSLATED }

    /** "العربية" / "English", from the code's language part ("en-orig" -> "en"). */
    val languageName: String
        get() {
            val tag = code.removeSuffix("-orig").replace('_', '-')
            val locale = Locale.forLanguageTag(tag)
            val name = locale.getDisplayName(Locale.getDefault())
            return if (name.isNullOrBlank() || name.equals(tag, ignoreCase = true)) code else name
        }
}

/** Asks yt-dlp which subtitles a video has, without downloading anything. Blocking: call from a background thread. */
object SubtitleLister {

    /** Throws if the engine or the network fails. An empty list means the video has no subtitles at all. */
    fun fetch(context: Context, url: String): List<SubtitleOption> {
        check(YtDlpEngine.ensureReady(context)) { "engine" }
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--dump-single-json")
            addOption("--no-warnings")
        }
        val out = YoutubeDL.getInstance().execute(request).out
        val json = JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0)))

        val result = LinkedHashMap<String, SubtitleOption>()

        // Manual subtitles: every language the uploader provided.
        json.optJSONObject("subtitles")?.keys()?.forEach { code ->
            if (code != "live_chat") result[code] = SubtitleOption(code, SubtitleOption.Kind.MANUAL)
        }

        // Automatic captions come in ~150 machine translations; offer only the spoken language ("-orig")
        // and the translations into Arabic / English.
        json.optJSONObject("automatic_captions")?.let { auto ->
            val keys = auto.keys().asSequence().toList()
            for (code in keys) {
                if (code.endsWith("-orig")) {
                    result.putIfAbsent(code, SubtitleOption(code, SubtitleOption.Kind.AUTO))
                }
            }
            for (code in listOf("ar", "en")) {
                if (code in keys && code !in result) {
                    result[code] = SubtitleOption(code, SubtitleOption.Kind.AUTO_TRANSLATED)
                }
            }
        }
        return result.values.toList()
    }
}
