// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import org.json.JSONObject

enum class MediaKind { VIDEO, AUDIO, SUBS }

enum class VideoContainer(val ext: String, val label: String) {
    MP4("mp4", "MP4"),
    MKV("mkv", "MKV"),
    WEBM("webm", "WEBM"),
}

/**
 * [ytName] is yt-dlp's `--audio-format` value; [ext] the file extension it produces; [cover] says the format can carry
 * an embedded cover picture (a raw AAC stream and WAV cannot).
 */
enum class AudioCodec(val ytName: String, val ext: String, val label: String, val lossy: Boolean, val cover: Boolean) {
    MP3("mp3", "mp3", "MP3", true, true),
    M4A("m4a", "m4a", "M4A", true, true),
    AAC("aac", "aac", "AAC", true, false),
    OPUS("opus", "opus", "Opus", true, true),
    OGG("vorbis", "ogg", "OGG", true, true),
    FLAC("flac", "flac", "FLAC", false, true),
    WAV("wav", "wav", "WAV", false, false),
}

/**
 * Everything the user chose in the "Download" dialog, and the yt-dlp options each choice maps to.
 * Stored as JSON (queue broadcast, retry, remembered choices), so unknown / missing keys must fall back to defaults.
 */
data class DownloadOptions(
    val kind: MediaKind = MediaKind.VIDEO,
    /** Highest video height wanted; 0 = best available. */
    val maxHeight: Int = 720,
    val container: VideoContainer = VideoContainer.MP4,
    /** H.264 + AAC first (plays everywhere) instead of the best VP9 / AV1 / Opus streams. */
    val compatibleCodec: Boolean = true,
    val audioCodec: AudioCodec = AudioCodec.M4A,
    /** Audio bitrate in kbps for lossy formats; 0 = best. */
    val audioKbps: Int = 0,
    /** "srt" or "lrc". */
    val subFormat: String = "srt",
    /** Also save subtitle files next to a video / audio download. */
    val withSubs: Boolean = false,
    /** Subtitle language codes picked in the second step, comma-separated. */
    val langs: String = "",
) {
    val isAudio: Boolean get() = kind == MediaKind.AUDIO
    val isSubsOnly: Boolean get() = kind == MediaKind.SUBS
    val wantsSubs: Boolean get() = kind == MediaKind.SUBS || withSubs

    /** Value for yt-dlp's `-f`. */
    val format: String
        get() = when (kind) {
            MediaKind.AUDIO -> when (audioCodec) {
                AudioCodec.M4A -> "bestaudio[ext=m4a]/bestaudio/best"
                AudioCodec.OPUS -> "bestaudio[acodec=opus]/bestaudio/best"
                else -> "bestaudio/best"
            }
            MediaKind.VIDEO -> videoFormat()
            MediaKind.SUBS -> "best" // never used: subtitle jobs skip the media download
        }

    private fun videoFormat(): String {
        val h = if (maxHeight > 0) "[height<=$maxHeight]" else ""
        if (container == VideoContainer.WEBM) {
            // WebM only holds VP9 / AV1 + Opus / Vorbis.
            return "bv*${h}[ext=webm]+ba[ext=webm]/bv*$h+ba/b$h/b"
        }
        if (!compatibleCodec) return "bv*$h+ba/b$h/b"
        // H.264 (avc1) + AAC (mp4a) first; only if YouTube offers none for this height fall back to other codecs.
        return "bv*${h}[vcodec^=avc1]+ba[acodec^=mp4a]" +
            "/b${h}[vcodec^=avc1][acodec^=mp4a]" +
            "/bv*${h}[ext=mp4]+ba[ext=m4a]" +
            "/b${h}[ext=mp4]" +
            "/bv*$h+ba/b$h/b"
    }

    /** `--audio-quality` value: 0 is the best VBR quality, "192K" a fixed bitrate. */
    val audioQualityArg: String
        get() = if (audioKbps > 0) "${audioKbps}K" else "0"

    /** Short, language-independent tag for the Library cards, e.g. "MP4 1080p", "MP3 192k", "SRT". */
    val shortLabel: String
        get() = when (kind) {
            MediaKind.VIDEO -> {
                val quality = if (maxHeight > 0) "${maxHeight}p" else "Best"
                val base = "${container.label} $quality"
                if (withSubs) "$base + ${subFormat.uppercase()}" else base
            }
            MediaKind.AUDIO -> {
                val quality = if (audioCodec.lossy && audioKbps > 0) " ${audioKbps}k" else ""
                val base = audioCodec.label + quality
                if (withSubs) "$base + ${subFormat.uppercase()}" else base
            }
            MediaKind.SUBS -> subFormat.uppercase()
        }

    fun toJson(): String = JSONObject()
        .put("kind", kind.name)
        .put("maxHeight", maxHeight)
        .put("container", container.name)
        .put("compatibleCodec", compatibleCodec)
        .put("audioCodec", audioCodec.name)
        .put("audioKbps", audioKbps)
        .put("subFormat", subFormat)
        .put("withSubs", withSubs)
        .put("langs", langs)
        .toString()

    companion object {
        fun fromJson(json: String?): DownloadOptions {
            if (json.isNullOrBlank()) return DownloadOptions()
            return try {
                val o = JSONObject(json)
                DownloadOptions(
                    kind = runCatching { MediaKind.valueOf(o.optString("kind")) }.getOrDefault(MediaKind.VIDEO),
                    maxHeight = o.optInt("maxHeight", 720),
                    container = runCatching { VideoContainer.valueOf(o.optString("container")) }
                        .getOrDefault(VideoContainer.MP4),
                    compatibleCodec = o.optBoolean("compatibleCodec", true),
                    audioCodec = runCatching { AudioCodec.valueOf(o.optString("audioCodec")) }
                        .getOrDefault(AudioCodec.M4A),
                    audioKbps = o.optInt("audioKbps", 0),
                    subFormat = o.optString("subFormat", "srt").takeIf { it == "srt" || it == "lrc" } ?: "srt",
                    withSubs = o.optBoolean("withSubs", false),
                    langs = o.optString("langs", ""),
                )
            } catch (e: Exception) {
                DownloadOptions()
            }
        }
    }
}
