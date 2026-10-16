// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/** One video of a list. [url] is what yt-dlp is given for it; [item] is explained in [DownloadTarget]. */
data class PlaylistEntry(val url: String, val title: String, val durationSec: Int = 0, val item: Int = 0)

/** A list of videos as yt-dlp reads it. [truncated] means the list has more videos than [PlaylistLister.MAX_ENTRIES]. */
class PlaylistInfo(val title: String, val entries: List<PlaylistEntry>, val truncated: Boolean)

/** What a link turned out to be. */
sealed interface ProbeResult {
    /** A list of videos. */
    class Playlist(val info: PlaylistInfo) : ProbeResult
    /** One video: download the link as it is. */
    object Single : ProbeResult
    /** The list could not be read. [detail] is yt-dlp's own message (may be empty). */
    class Failed(val detail: String) : ProbeResult
}

/**
 * Asks yt-dlp what is behind a link, without downloading anything: one video or a list of them. This works for every
 * site yt-dlp knows (YouTube playlists / Mixes / channels, SoundCloud sets, Vimeo albums, Bilibili series...), because
 * the answer comes from the extractor of that site and not from a guess about the address.
 */
object PlaylistLister {

    /** A list is read up to this many videos: every video becomes a job of the queue, which the Library redraws often. */
    const val MAX_ENTRIES = 200

    /** Throws if the engine or the network fails. Blocking: call from a background thread. */
    fun fetch(context: Context, url: String, processId: String): ProbeResult {
        check(YtDlpEngine.ensureReady(context)) { "engine" }
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            // One more than the limit shows that the list is longer, and a Mix (which never ends) is cut short.
            addOption("--playlist-end", (MAX_ENTRIES + 1).toString())
        }
        val out = YoutubeDL.getInstance().execute(request, processId).out
        return parse(out, url)
    }

    /** yt-dlp prints the whole answer as one line of JSON; anything printed before it (a warning) is skipped. */
    internal fun parse(output: String, pageUrl: String): ProbeResult {
        val line = output.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: throw IllegalStateException("no answer")
        val json = JSONObject(line.trim())
        val entries = json.optJSONArray("entries")
        val type = text(json, "_type")
        if (entries == null && type != "playlist" && type != "multi_video") return ProbeResult.Single

        val found = ArrayList<PlaylistEntry>()
        val nested = entries != null && collect(entries, found)
        // A page whose videos all share the page's own address (a post with several videos) is told apart by position.
        val byItem = !nested && found.size > 1 && found.map { it.url }.toSet().size == 1
        val unique = LinkedHashMap<String, PlaylistEntry>()
        for (entry in found) {
            val fixed = if (byItem) entry.copy(url = pageUrl) else entry.copy(item = 0)
            unique.putIfAbsent(fixed.url + "#" + fixed.item, fixed)
        }
        val list = unique.values.toList()
        // Nothing to choose from: let the download job try the link itself and report what is wrong with it.
        if (list.isEmpty()) return ProbeResult.Single
        // A "list" of one video that is not a real playlist is just that video (a post with a single video).
        if (list.size == 1 && !hasListShape(json)) return ProbeResult.Single

        val truncated = list.size > MAX_ENTRIES
        return ProbeResult.Playlist(PlaylistInfo(text(json, "title"), list.take(MAX_ENTRIES), truncated))
    }

    /** A list that really is a list (a playlist id or a title of its own) is still shown when it has one video only. */
    private fun hasListShape(json: JSONObject): Boolean = text(json, "_type") == "playlist" && text(json, "id").isNotEmpty()

    /**
     * Adds the videos of [entries] to [out], going into lists inside the list (a channel made of tabs). Returns true
     * when it met such a nested list, because then the position of a video no longer says anything about the page.
     * Stops after one video more than [MAX_ENTRIES].
     */
    private fun collect(entries: JSONArray, out: MutableList<PlaylistEntry>): Boolean {
        var nested = false
        for (i in 0 until entries.length()) {
            if (out.size > MAX_ENTRIES) break
            val entry = entries.optJSONObject(i) ?: continue
            val inner = entry.optJSONArray("entries")
            if (inner != null) {
                nested = true
                collect(inner, out)
                continue
            }
            entryOf(entry, i + 1)?.let { out += it }
        }
        return nested
    }

    private fun entryOf(e: JSONObject, position: Int): PlaylistEntry? {
        val key = text(e, "ie_key")
        // A reference to another list (a channel's tabs, a playlist inside a playlist) is not a video.
        if (key == "YoutubeTab" || key == "YoutubePlaylist" || text(e, "_type") == "playlist") return null
        val title = text(e, "title")
        // YouTube lists the videos it hides too; there is nothing to download for them.
        if (title == "[Private video]" || title == "[Deleted video]") return null

        val id = text(e, "id")
        // A reference carries its own address in `url`. A video that was read in full has the media file as `url` and
        // its page as `webpage_url`: the page is what can be downloaded again.
        val reference = key.isNotEmpty() || text(e, "_type") == "url"
        val direct = httpOrNull(text(e, "url"))
        val page = httpOrNull(text(e, "webpage_url"))
        val url = (if (reference) direct else page ?: direct)
            ?: page
            ?: if (key == "Youtube" && id.length == 11) "https://www.youtube.com/watch?v=$id" else null
        if (url == null) return null
        val duration = if (e.isNull("duration")) 0 else e.optDouble("duration", 0.0).toInt().coerceAtLeast(0)
        return PlaylistEntry(url, title, duration, item = position)
    }

    private fun httpOrNull(value: String): String? =
        value.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** org.json turns a JSON null into the text "null"; that must read as no text. */
    private fun text(o: JSONObject, name: String): String = if (o.isNull(name)) "" else o.optString(name).trim()
}

/**
 * Reads a link with [PlaylistLister] in the background and hands the answer to whoever asks, on the main thread. It is
 * started early (while the "Download as" window is still open) so the answer is usually ready when the person taps Download.
 *
 * [mustBeList] is true for links known to be lists: a failure is then reported. For any other site a failure, or an
 * answer that takes longer than [UNKNOWN_SITE_TIMEOUT_MS], only means yt-dlp cannot tell, so the link is treated as one
 * video and the download job reports the real problem itself.
 */
class ListProbe(context: Context, private val url: String, private val mustBeList: Boolean) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val processId = UUID.randomUUID().toString()

    @Volatile private var cancelled = false
    // Both are touched on the main thread only.
    private var result: ProbeResult? = null
    private var listener: ((ProbeResult) -> Unit)? = null

    /** The answer if it is already there, else null. */
    val ready: ProbeResult? get() = result

    fun start(): ListProbe {
        thread(name = "wardtube-list-probe") {
            val answer = read()
            main.post { deliver(answer) }
        }
        if (!mustBeList) {
            main.postDelayed({
                if (result == null && !cancelled) {
                    deliver(ProbeResult.Single)
                    runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                }
            }, UNKNOWN_SITE_TIMEOUT_MS)
        }
        return this
    }

    /** Main thread. The first answer wins: a late one (after the timeout) is ignored. */
    private fun deliver(answer: ProbeResult) {
        if (cancelled || result != null) return
        result = answer
        val callback = listener
        listener = null
        callback?.invoke(answer)
    }

    /** Calls [callback] on the main thread with the answer: at once when it is ready, else when it arrives. */
    fun whenReady(callback: (ProbeResult) -> Unit) {
        val answer = result
        if (answer != null) callback(answer) else listener = callback
    }

    fun cancel() {
        cancelled = true
        listener = null
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    private companion object {
        const val UNKNOWN_SITE_TIMEOUT_MS = 25_000L
    }

    private fun read(): ProbeResult {
        var detail = ""
        // An outdated yt-dlp is the usual reason a list cannot be read: update it once and try again (known lists only).
        for (attempt in 0..1) {
            try {
                return PlaylistLister.fetch(app, url, processId)
            } catch (e: Throwable) {
                if (cancelled) return ProbeResult.Failed("")
                detail = (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
                if (!mustBeList) return ProbeResult.Single
                if (attempt == 0 && !YtDlpEngine.updateAttempted) {
                    if (YtDlpEngine.update(app)) continue
                }
                break
            }
        }
        return ProbeResult.Failed(detail)
    }
}
