// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Where the Subscriptions screen gets its data, with no API key and no account API:
 *  - the list of channels is read from YouTube's own "all subscriptions" page, fetched with the cookies the WebView
 *    already holds (the person signed in there), so it is the real list of the signed-in account;
 *  - the videos are read the same way, from YouTube's own Subscriptions page (the signed-in account's feed, with
 *    durations, views and dates written by YouTube in the phone's language) and from a channel's videos page;
 *  - the channel's public RSS feed (its 15 newest videos) is the way out when those pages give nothing.
 *
 * Everything here blocks: call it from a background thread.
 */
object SubsRepository {

    /** The page answered, but nothing usable is in it (signed out, or YouTube's page changed). */
    class Failure(message: String) : Exception(message)

    const val SITE = "https://www.youtube.com"

    private const val CHANNELS_PAGE = "$SITE/feed/channels"
    private const val BROWSE_API = "$SITE/youtubei/v1/browse"
    private const val RSS = "$SITE/feeds/videos.xml?channel_id="
    private const val TIMEOUT_MS = 12_000
    private const val MAX_PAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_CONTINUATIONS = 12

    /** The desktop page has a plain, well known layout; the phone's page keeps its data in other shapes. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** What went wrong (or what was found) the last time a page was read: shown on the screen when it stays empty. */
    @Volatile
    var diag: String = ""

    private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
    private val DURATION = Regex("^\\p{Nd}{1,2}(:\\p{Nd}{2}){1,2}$")
    private val DATA_MARK = Regex("ytInitialData\"?\\]?\\s*=\\s*")
    private val API_KEY = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"")

    // ---- Who is signed in ----

    private fun cookieJar(cookie: String): Map<String, String> {
        val jar = HashMap<String, String>()
        for (part in cookie.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) jar[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        return jar
    }

    /** A short fingerprint of the signed-in account, or "" when nobody is signed in. Never the cookie itself. */
    fun accountKey(cookie: String): String {
        val jar = cookieJar(cookie)
        val sid = jar["SAPISID"] ?: jar["__Secure-3PAPISID"] ?: return ""
        return sha1(sid).take(16)
    }

    // ---- The channels ----

    /** The channels of the signed-in account. Throws [Failure] when the page has none, other exceptions when offline. */
    fun fetchChannels(cookie: String): List<SubsChannel> {
        val html = httpGet(CHANNELS_PAGE, cookie, "en")
        val data = initialData(html) ?: throw Failure("no page data")
        val found = LinkedHashMap<String, SubsChannel>()
        val tokens = ArrayList<String>()
        collect(data, found, tokens)

        // The page holds only the first part of a long list; the rest comes through continuation requests (best effort).
        val apiKey = API_KEY.find(html)?.groupValues?.get(1)
        val context = objectAfter(html, "\"INNERTUBE_CONTEXT\"")
        var token = tokens.lastOrNull()
        var rounds = 0
        while (token != null && apiKey != null && context != null && rounds < MAX_CONTINUATIONS) {
            rounds++
            val more = try {
                browseContinuation(apiKey, context, token, cookie)
            } catch (e: Exception) {
                null
            }
            if (more == null) break
            val next = ArrayList<String>()
            collect(more, found, next)
            token = next.lastOrNull()
        }
        if (found.isEmpty()) throw Failure("no channels")
        return found.values.toList()
    }

    /** Collects every channel (an object with a `channelId`, a title and a picture) and every continuation token. */
    private fun collect(node: Any?, out: MutableMap<String, SubsChannel>, tokens: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val id = node.optString("channelId")
                if (id !in out && CHANNEL_ID.matches(id)) {
                    val name = textOf(node.opt("title"))
                    if (name.isNotEmpty()) out[id] = SubsChannel(id, name, bestImage(node.optJSONObject("thumbnail")))
                }
                val token = node.optJSONObject("continuationCommand")?.optString("token").orEmpty()
                if (token.isNotEmpty()) tokens.add(token)
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out, tokens)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collect(node.opt(i), out, tokens)
            }
        }
    }

    private fun textOf(value: Any?): String {
        if (value is String) return value
        if (value !is JSONObject) return ""
        val simple = value.optString("simpleText")
        if (simple.isNotEmpty()) return simple
        val runs = value.optJSONArray("runs") ?: return ""
        val text = StringBuilder()
        for (i in 0 until runs.length()) text.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return text.toString()
    }

    /** The largest picture of a `{"thumbnails": [...]}` object, as an https address ("" when there is none). */
    private fun bestImage(holder: JSONObject?): String {
        val list = holder?.optJSONArray("thumbnails") ?: return ""
        val url = list.optJSONObject(list.length() - 1)?.optString("url").orEmpty()
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("https://") -> url
            else -> ""
        }
    }

    private fun initialData(html: String): JSONObject? {
        val mark = DATA_MARK.find(html) ?: return null
        val raw = objectAt(html, mark.range.last + 1) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    /** The JSON object text that starts at [start] (a '{'): braces are matched outside of strings. Null if it never closes. */
    private fun objectAt(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '{') return null
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else if (c == '"') {
                inString = true
            } else if (c == '{') {
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    /** The JSON object that follows `key:` in [text], or null. */
    private fun objectAfter(text: String, key: String): String? {
        val at = text.indexOf(key)
        if (at < 0) return null
        val colon = text.indexOf(':', at + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        return objectAt(text, i)
    }

    private fun browseContinuation(apiKey: String, context: String, token: String, cookie: String): JSONObject {
        val body = "{\"context\":$context,\"continuation\":${JSONObject.quote(token)}}"
        val connection = URL("$BROWSE_API?key=$apiKey&prettyPrint=false").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Origin", SITE)
            connection.setRequestProperty("X-Origin", SITE)
            connection.setRequestProperty("X-Goog-AuthUser", "0")
            authorization(cookie)?.let { connection.setRequestProperty("Authorization", it) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw Failure("HTTP ${connection.responseCode}")
            return JSONObject(readText(connection.inputStream))
        } finally {
            connection.disconnect()
        }
    }

    /** The header YouTube's own pages send with their requests: a hash of the session cookie, the time and the site. */
    private fun authorization(cookie: String): String? {
        val jar = cookieJar(cookie)
        val now = System.currentTimeMillis() / 1000
        val parts = ArrayList<String>()
        val schemes = listOf(
            "SAPISIDHASH" to "SAPISID",
            "SAPISID1PHASH" to "__Secure-1PAPISID",
            "SAPISID3PHASH" to "__Secure-3PAPISID",
        )
        for ((scheme, name) in schemes) {
            val sid = jar[name] ?: continue
            parts.add("$scheme ${now}_${sha1("$now $sid $SITE")}")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    // ---- The videos (from YouTube's own pages) ----

    private class Stats {
        var renderers = 0
        var lockups = 0
        var shorts = 0

        /**
         * Every other continuation command found in the page (filter chips, menus...). The list's own token is the one
         * in its `continuationItemRenderer`; these are used only when the page has no such renderer.
         */
        val loose = ArrayList<String>()
    }

    /** The signed-in person's feed of new videos: YouTube's Subscriptions page. [lang] is the phone's language. */
    fun fetchFeed(cookie: String, lang: String): SubsPage =
        fetchPage("$SITE/feed/subscriptions?hl=$lang", cookie, lang, null)

    /** The videos tab of one channel; its videos carry no channel on that page, so [channel] is put in them. */
    fun fetchChannelVideos(cookie: String, lang: String, channel: SubsChannel): SubsPage =
        fetchChannelTab(cookie, lang, channel, "videos")

    /**
     * One tab of a channel's page: "videos", "shorts" or "streams" (its live streams, past ones too: they are marked
     * as live type 3 so the Live chip lists them). A channel with no live streams has no such tab; the page then comes
     * with another tab open and the answer is an empty page (see [streamsTabOpen]).
     */
    fun fetchChannelTab(cookie: String, lang: String, channel: SubsChannel, tab: String): SubsPage {
        val page = fetchPage("$SITE/channel/${channel.id}/$tab?hl=$lang", cookie, lang, channel, onlyStreams = tab == "streams")
        if (tab != "streams") return page
        val marked = page.videos.map { if (it.live == 0 && !it.short) it.copy(live = 3) else it }
        return SubsPage(marked, page.token, page.apiKey, page.context)
    }

    /**
     * Whether the tab that is open in a channel's page data is its live streams tab: the `tabRenderer` marked
     * `selected`, whose address ends with "/streams" or whose `params` are the streams tab's. Null when the data lists
     * no open tab at all (a shape this code does not know): the caller then takes the page as it is.
     */
    private fun streamsTabOpen(node: Any?): Boolean? {
        if (node is JSONArray) {
            for (i in 0 until node.length()) streamsTabOpen(node.opt(i))?.let { return it }
            return null
        }
        if (node !is JSONObject) return null
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            if (key == "tabRenderer" && value is JSONObject) {
                // The tab's own content (its videos) is not searched: the answer is in its address, not in what it holds.
                if (!value.optBoolean("selected")) continue
                val endpoint = value.optJSONObject("endpoint") ?: return null
                val url = endpoint.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
                val params = endpoint.optJSONObject("browseEndpoint")?.optString("params").orEmpty()
                if (url.isEmpty() && params.isEmpty()) return null
                return url.substringBefore('?').trimEnd('/').endsWith("/streams") || params.startsWith(STREAMS_PARAMS)
            }
            streamsTabOpen(value)?.let { return it }
        }
        return null
    }

    /** The start of the `params` that YouTube gives the streams tab's address (protobuf for "streams", in base64). */
    private const val STREAMS_PARAMS = "EgdzdHJlYW1z"

    private fun fetchPage(
        address: String,
        cookie: String,
        lang: String,
        owner: SubsChannel?,
        onlyStreams: Boolean = false,
    ): SubsPage {
        val html = httpGet(address, cookie, lang)
        val data = initialData(html)
        if (data == null) {
            diag = "page ${html.length / 1024}KB without data"
            throw Failure("no page data")
        }
        // A channel with no live streams has no streams tab: YouTube answers the address with the channel's first tab
        // instead, full of ordinary videos, which must not be taken for streams.
        if (onlyStreams && streamsTabOpen(data) == false) {
            diag = "page ${html.length / 1024}KB: no streams tab"
            return SubsPage(emptyList(), null, null, null)
        }
        val stats = Stats()
        val videos = ArrayList<SubsVideo>()
        val tokens = ArrayList<String>()
        collectVideos(data, videos, HashSet(), tokens, stats)
        diag = "page ${html.length / 1024}KB: videoRenderer ${stats.renderers}, lockup ${stats.lockups}, " +
            "shorts ${stats.shorts}, videos ${videos.size}"
        val result = if (owner == null) videos else videos.map { it.copy(channelId = owner.id, channelName = owner.name) }
        // The feed's page carries the filter chips ("All", "Today", "Videos", "Shorts"...); a channel's page has none that matter.
        val chips = ArrayList<SubsChip>()
        if (owner == null) collectChips(data, chips)
        return SubsPage(
            result, tokens.lastOrNull() ?: stats.loose.lastOrNull(),
            API_KEY.find(html)?.groupValues?.get(1), objectAfter(html, "\"INNERTUBE_CONTEXT\""), chips,
        )
    }

    /**
     * The filter chips of the page in the order they stand in it (both shapes YouTube uses: the long-standing
     * `chipCloudChipRenderer` and the newer `chipViewModel`). A chip with no continuation is kept with an empty token, so
     * the position of the others stays the one on the screen.
     */
    private fun collectChips(node: Any?, out: MutableList<SubsChip>) {
        if (node is JSONArray) {
            for (i in 0 until node.length()) collectChips(node.opt(i), out)
            return
        }
        if (node !is JSONObject) return
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            if ((key == "chipCloudChipRenderer" || key == "chipViewModel") && value is JSONObject) {
                val text = textOf(value.opt("text")).ifEmpty { findText(value, "text") { it.isNotEmpty() }.orEmpty() }
                if (text.isNotEmpty()) out.add(SubsChip(text, findText(value, "token") { it.isNotEmpty() }.orEmpty()))
            } else {
                collectChips(value, out)
            }
        }
    }

    /**
     * The first page of what one filter chip lists (YouTube's own list for it: the Shorts chip lists only Shorts, and
     * goes on for as long as the account has any, through [fetchMore]). Throws [Failure] when the chip has no token.
     */
    fun fetchChip(head: SubsPage, chip: SubsChip, cookie: String): SubsPage {
        if (chip.token.isEmpty()) throw Failure("chip without continuation")
        return fetchMore(SubsPage(emptyList(), chip.token, head.apiKey, head.context), cookie)
    }

    /** True when [videos] are (nearly) all Shorts: the answer of the Shorts chip, not of another one. */
    fun mostlyShorts(videos: List<SubsVideo>): Boolean = videos.isNotEmpty() && videos.count { it.short } * 5 >= videos.size * 4

    /** The page after [page] (its videos only), through a continuation request. */
    fun fetchMore(page: SubsPage, cookie: String): SubsPage {
        val token = page.token ?: throw Failure("no continuation")
        val apiKey = page.apiKey ?: throw Failure("no key")
        val context = page.context ?: throw Failure("no context")
        val data = browseContinuation(apiKey, context, token, cookie)
        val videos = ArrayList<SubsVideo>()
        val tokens = ArrayList<String>()
        val stats = Stats()
        collectVideos(data, videos, HashSet(), tokens, stats)
        return SubsPage(videos, tokens.lastOrNull() ?: stats.loose.lastOrNull(), apiKey, context)
    }

    /**
     * Collects the videos in the order they stand in the page, and the list's continuation tokens (the ones inside a
     * `continuationItemRenderer`; any other continuation command goes to [Stats.loose]). Two shapes of video are
     * known: the long-standing `videoRenderer` and the newer `lockupViewModel`. Shorts come in their own shapes
     * (`reelItemRenderer`, `shortsLockupViewModel`) and are kept, marked as Shorts; playlists and anything else are
     * left out.
     */
    private fun collectVideos(
        node: Any?,
        out: MutableList<SubsVideo>,
        seen: MutableSet<String>,
        tokens: MutableList<String>,
        stats: Stats,
    ) {
        if (node is JSONArray) {
            for (i in 0 until node.length()) collectVideos(node.opt(i), out, seen, tokens, stats)
            return
        }
        if (node !is JSONObject) return
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            when (key) {
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer" -> {
                    stats.renderers++
                    val video = (value as? JSONObject)?.let { videoFromRenderer(it) }
                    if (video != null && seen.add(video.id)) out.add(video)
                }
                "lockupViewModel" -> {
                    stats.lockups++
                    val video = (value as? JSONObject)?.let { videoFromLockup(it) }
                    if (video != null && seen.add(video.id)) out.add(video)
                }
                "reelItemRenderer" -> {
                    stats.shorts++
                    val video = (value as? JSONObject)?.let { shortFromReel(it) }
                    if (video != null && seen.add(video.id)) out.add(video)
                }
                "shortsLockupViewModel" -> {
                    stats.shorts++
                    val video = (value as? JSONObject)?.let { shortFromLockup(it) }
                    if (video != null && seen.add(video.id)) out.add(video)
                }
                "continuationItemRenderer" -> {
                    // The list's own "load more" item, the last one of its page: its token is the next page of videos.
                    val token = findText(value, "token") { it.isNotEmpty() }
                    if (token != null) tokens.add(token)
                }
                "continuationCommand" -> {
                    // Also the filter chips and menus carry such commands, and they can stand after the list in the
                    // page: taking the last one of those would ask for something that is not the next page of videos.
                    val token = (value as? JSONObject)?.optString("token").orEmpty()
                    if (token.isNotEmpty()) stats.loose.add(token)
                }
                else -> collectVideos(value, out, seen, tokens, stats)
            }
        }
    }

    private fun videoFromRenderer(o: JSONObject): SubsVideo? {
        val id = o.optString("videoId")
        if (id.length != 11) return null
        // A Short opens in the Shorts player.
        var short = o.optJSONObject("navigationEndpoint")?.has("reelWatchEndpoint") == true
        val title = textOf(o.opt("title"))
        if (title.isEmpty()) return null

        var duration = textOf(o.opt("lengthText"))
        val overlays = o.optJSONArray("thumbnailOverlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val overlay = overlays.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
                if (overlay.optString("style") == "SHORTS") short = true
                if (duration.isEmpty()) duration = textOf(overlay.opt("text"))
            }
        }

        var live = liveOf(o.opt("badges"), overlays)
        if (live == 0 && o.has("upcomingEventData")) live = 2
        if (live != 0) duration = "" // the overlay's text is "LIVE" or "UPCOMING", not a length

        val byline = o.optJSONObject("ownerText") ?: o.optJSONObject("longBylineText") ?: o.optJSONObject("shortBylineText")
        val run = byline?.optJSONArray("runs")?.optJSONObject(0)
        val channelId = run?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        return SubsVideo(
            id = id,
            title = title,
            channelId = if (CHANNEL_ID.matches(channelId)) channelId else "",
            channelName = run?.optString("text").orEmpty(),
            duration = if (short) "" else duration,
            published = textOf(o.opt("publishedTimeText")),
            views = textOf(o.opt("viewCountText")),
            short = short,
            live = if (short) 0 else live,
            watched = watchedOf(overlays),
        )
    }

    /** A Short of a shelf (`reelItemRenderer`): its title, views and (when the shelf says it) its channel. */
    private fun shortFromReel(o: JSONObject): SubsVideo? {
        val id = o.optString("videoId")
        if (id.length != 11) return null
        return SubsVideo(
            id = id,
            title = textOf(o.opt("headline")),
            channelId = findText(o, "browseId") { CHANNEL_ID.matches(it) }.orEmpty(),
            channelName = "",
            views = textOf(o.opt("viewCountText")),
            short = true,
        )
    }

    /** A Short in the newer shape (`shortsLockupViewModel`): the title and the views are the texts on its picture. */
    private fun shortFromLockup(o: JSONObject): SubsVideo? {
        var id = o.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")
            ?.optString("videoId").orEmpty()
        if (id.length != 11) id = o.optString("entityId").removePrefix("shorts-shelf-item-")
        if (id.length != 11) id = findText(o, "videoId") { it.length == 11 }.orEmpty()
        if (id.length != 11) return null
        val overlay = o.optJSONObject("overlayMetadata")
        return SubsVideo(
            id = id,
            title = overlay?.optJSONObject("primaryText")?.optString("content").orEmpty(),
            channelId = findText(o, "browseId") { CHANNEL_ID.matches(it) }.orEmpty(),
            channelName = "",
            views = overlay?.optJSONObject("secondaryText")?.optString("content").orEmpty(),
            short = true,
        )
    }

    /** What marks a live stream in a video's badges and picture overlays (the different shapes YouTube uses). */
    private val LIVE_MARKS = listOf("LIVE_NOW", "\"style\":\"LIVE\"", "\"imageName\":\"LIVE\"", "BADGE_STYLE_LIVE")
    private val UPCOMING_MARKS = listOf("UPCOMING", "upcomingEventData")

    /** 1 when the parts say the video is live now, 2 when it is scheduled, else 0. */
    private fun liveOf(vararg parts: Any?): Int {
        var result = 0
        for (part in parts) {
            val text = part?.toString() ?: continue
            if (LIVE_MARKS.any { text.contains(it) }) return 1
            if (UPCOMING_MARKS.any { text.contains(it) }) result = 2
        }
        return result
    }

    /** The percent of the video the account has watched (the red line under a picture), or 0. */
    private fun watchedOf(node: Any?): Int = findNumber(node, "percentDurationWatched", "startPercent").coerceIn(0, 100)

    /** The first number stored under one of [keys] anywhere inside [node], or 0. */
    private fun findNumber(node: Any?, vararg keys: String): Int {
        if (node is JSONObject) {
            val iterator = node.keys()
            while (iterator.hasNext()) {
                val k = iterator.next()
                val v = node.opt(k)
                if (k in keys && v is Number) return v.toInt()
                val found = findNumber(v, *keys)
                if (found != 0) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findNumber(node.opt(i), *keys)
                if (found != 0) return found
            }
        }
        return 0
    }

    private fun videoFromLockup(o: JSONObject): SubsVideo? {
        val type = o.optString("contentType")
        val short = type.contains("SHORT")
        if (type != "LOCKUP_CONTENT_TYPE_VIDEO" && !short) return null
        val id = o.optString("contentId")
        if (id.length != 11) return null
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content").orEmpty()
        if (title.isEmpty()) return null

        // The texts of the rows under the title: [channel, views, date] (on a channel's own page: [views, date]).
        val parts = ArrayList<String>()
        val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                for (j in 0 until row.length()) {
                    val text = row.optJSONObject(j)?.optJSONObject("text")?.optString("content").orEmpty()
                    if (text.isNotEmpty()) parts.add(text)
                }
            }
        }
        val named = parts.size >= 3
        val channelId = findText(meta, "browseId") { CHANNEL_ID.matches(it) }.orEmpty()
        val image = o.opt("contentImage")
        val live = if (short) 0 else liveOf(image)
        return SubsVideo(
            id = id,
            title = title,
            channelId = channelId,
            channelName = if (named) parts[0] else "",
            duration = if (short || live != 0) "" else findText(image, "text") { DURATION.matches(it) }.orEmpty(),
            published = parts.getOrNull(if (named) 2 else 1).orEmpty(),
            views = parts.getOrNull(if (named) 1 else 0).orEmpty(),
            short = short,
            live = live,
            watched = watchedOf(image),
        )
    }

    /** The first string stored under [key] anywhere inside [node] that [accept] likes, or null. */
    private fun findText(node: Any?, key: String, accept: (String) -> Boolean): String? {
        if (node is JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = node.opt(k)
                if (k == key && v is String && accept(v)) return v
                val found = findText(v, key, accept)
                if (found != null) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findText(node.opt(i), key, accept)
                if (found != null) return found
            }
        }
        return null
    }

    // ---- The videos of a channel (RSS, the way out) ----

    /** The newest videos of one channel (Shorts left out), from its RSS feed. */
    fun fetchRss(channelId: String, channelName: String): List<SubsVideo> {
        val connection = URL(RSS + channelId).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw Failure("HTTP ${connection.responseCode}")
            return connection.inputStream.use { parseFeed(it, channelId, channelName) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFeed(input: InputStream, channelId: String, channelName: String): List<SubsVideo> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val out = ArrayList<SubsVideo>()
        var inEntry = false
        var id = ""
        var title = ""
        var published = 0L
        var views = -1L
        var short = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                if (name == "entry") {
                    inEntry = true
                    id = ""
                    title = ""
                    published = 0L
                    views = -1L
                    short = false
                } else if (inEntry && name == "yt:videoId") {
                    id = parser.nextText().trim()
                } else if (inEntry && name == "title") {
                    if (title.isEmpty()) title = parser.nextText().trim()
                } else if (inEntry && name == "published") {
                    published = parseDate(parser.nextText().trim())
                } else if (inEntry && name == "link") {
                    if (parser.getAttributeValue(null, "rel") == "alternate") {
                        short = parser.getAttributeValue(null, "href").orEmpty().contains("/shorts/")
                    }
                } else if (inEntry && name == "media:statistics") {
                    views = parser.getAttributeValue(null, "views")?.toLongOrNull() ?: -1L
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                inEntry = false
                if (id.length == 11 && title.isNotEmpty() && !short) {
                    out.add(SubsVideo(id, title, channelId, channelName, publishedMs = published, viewCount = views))
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseDate(text: String): Long =
        try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(text)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }

    // ---- HTTP ----

    private fun httpGet(address: String, cookie: String, lang: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Language", "$lang,en;q=0.5")
            connection.setRequestProperty("Cookie", cookie)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw Failure("HTTP ${connection.responseCode}")
            return readText(connection.inputStream)
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_PAGE_BYTES) throw Failure("page too large")
        }
        return out.toString("UTF-8")
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---- Kept on the phone ----

    private fun cacheFile(context: Context) = File(context.filesDir, "subscriptions.json")

    fun load(context: Context): SubsSnapshot? {
        val file = cacheFile(context)
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val channels = ArrayList<SubsChannel>()
            val channelArray = root.getJSONArray("c")
            for (i in 0 until channelArray.length()) {
                val o = channelArray.getJSONObject(i)
                channels.add(SubsChannel(o.getString("i"), o.getString("n"), o.optString("a")))
            }
            val videos = ArrayList<SubsVideo>()
            val videoArray = root.getJSONArray("v")
            for (i in 0 until videoArray.length()) {
                val o = videoArray.getJSONObject(i)
                videos.add(
                    SubsVideo(
                        o.getString("i"), o.getString("t"), o.getString("c"), o.getString("h"),
                        o.optString("d"), o.optString("p"), o.optString("w"), o.optLong("pm"), o.optLong("vc", -1L),
                        short = o.optBoolean("sh"), live = o.optInt("lv"), watched = o.optInt("wp"),
                    )
                )
            }
            SubsSnapshot(root.optString("account"), channels, videos, root.optLong("channelsAt"), root.optLong("feedAt"))
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, snapshot: SubsSnapshot) {
        val root = JSONObject()
        root.put("account", snapshot.account)
        root.put("channelsAt", snapshot.channelsAt)
        root.put("feedAt", snapshot.feedAt)
        val channelArray = JSONArray()
        for (c in snapshot.channels) {
            channelArray.put(JSONObject().put("i", c.id).put("n", c.name).put("a", c.avatar))
        }
        val videoArray = JSONArray()
        for (v in snapshot.videos) {
            videoArray.put(
                JSONObject().put("i", v.id).put("t", v.title).put("c", v.channelId).put("h", v.channelName)
                    .put("d", v.duration).put("p", v.published).put("w", v.views)
                    .put("pm", v.publishedMs).put("vc", v.viewCount)
                    .put("sh", v.short).put("lv", v.live).put("wp", v.watched)
            )
        }
        root.put("c", channelArray)
        root.put("v", videoArray)
        val file = cacheFile(context)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    fun clear(context: Context) {
        cacheFile(context).delete()
    }
}
