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

    /** The phone's version of YouTube's site: the pages the app opens in its browsing WebView (a post, a link of a post). */
    const val MOBILE_SITE = "https://m.youtube.com"

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

    // ---- Actions of the video menu (YouTube's own API, signed in with the WebView's cookies) ----

    /** What a request to YouTube's own API needs: the page's key and its client context (JSON text). */
    class ApiAccess(val key: String, val context: String)

    /**
     * One playlist of the account, as the "Save to" list shows it: [contains] is true when the video is already in it,
     * [privacy] is YouTube's PRIVATE, PUBLIC or UNLISTED ("" when it did not say) and [thumb] the https address of the
     * playlist's picture ("" when the answer carried none: the sheet then asks [fetchPlaylistThumb]).
     */
    class PlaylistOption(
        val id: String,
        val title: String,
        val contains: Boolean,
        val privacy: String = "",
        val thumb: String = "",
    )

    /** The access taken from a page that was already read, or null when it carried none. */
    fun accessOf(page: SubsPage?): ApiAccess? {
        val source = page ?: return null
        val key = source.apiKey ?: return null
        val context = source.context ?: return null
        return ApiAccess(key, context)
    }

    /** Reads the key and the context from YouTube's own page of subscriptions (used when no page was read yet). */
    fun fetchApiAccess(cookie: String): ApiAccess {
        val html = httpGet(CHANNELS_PAGE, cookie, "en")
        val key = API_KEY.find(html)?.groupValues?.get(1) ?: throw Failure("no key")
        val context = objectAfter(html, "\"INNERTUBE_CONTEXT\"") ?: throw Failure("no context")
        return ApiAccess(key, context)
    }

    /** Sends [extra] (the fields of the request) with the page's context to `youtubei/v1/<path>` and returns the answer. */
    private fun postApi(path: String, access: ApiAccess, extra: JSONObject, cookie: String): JSONObject {
        val body = JSONObject()
        body.put("context", JSONObject(access.context))
        val names = extra.keys()
        while (names.hasNext()) {
            val name = names.next()
            body.put(name, extra.get(name))
        }
        val connection = URL("$SITE/youtubei/v1/$path?key=${access.key}&prettyPrint=false").openConnection() as HttpURLConnection
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
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw Failure("HTTP ${connection.responseCode}")
            return JSONObject(readText(connection.inputStream))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Adds a video to a playlist of the account, or takes it out ([add] false). [playlistId] "WL" is "Watch later".
     * True when YouTube says it was done.
     */
    fun editPlaylist(access: ApiAccess, playlistId: String, videoId: String, add: Boolean, cookie: String): Boolean {
        val action = JSONObject()
        if (add) {
            action.put("action", "ACTION_ADD_VIDEO")
            action.put("addedVideoId", videoId)
        } else {
            action.put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
            action.put("removedVideoId", videoId)
        }
        val extra = JSONObject()
        extra.put("playlistId", playlistId)
        extra.put("actions", JSONArray().put(action))
        val answer = postApi("browse/edit_playlist", access, extra, cookie)
        return answer.optString("status") == "STATUS_SUCCEEDED"
    }

    /** The playlists of the account ("Watch later" too), each marked with whether it already holds [videoId]. */
    fun fetchPlaylists(access: ApiAccess, videoId: String, cookie: String): List<PlaylistOption> {
        val extra = JSONObject()
        extra.put("videoIds", JSONArray().put(videoId))
        extra.put("excludeWatchLater", false)
        val answer = postApi("playlist/get_add_to_playlist", access, extra, cookie)
        val found = ArrayList<PlaylistOption>()
        collectPlaylists(answer, found)
        if (found.isEmpty()) throw Failure("no playlists")
        return found
    }

    private fun collectPlaylists(node: Any?, out: MutableList<PlaylistOption>) {
        when (node) {
            is JSONObject -> {
                val option = node.optJSONObject("playlistAddToOptionRenderer")
                if (option != null) {
                    val id = option.optString("playlistId")
                    val title = textOf(option.opt("title"))
                    if (id.isNotEmpty() && title.isNotEmpty() && out.none { it.id == id }) {
                        out.add(
                            PlaylistOption(
                                id,
                                title,
                                option.optString("containsSelectedVideos") == "ALL",
                                option.optString("privacy"),
                                firstThumbnail(option),
                            )
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectPlaylists(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectPlaylists(node.opt(i), out)
            }
        }
    }

    /** The first picture ({"thumbnails": [...]}) found anywhere inside [node], or "" when there is none. */
    private fun firstThumbnail(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val own = bestImage(node)
                if (own.isNotEmpty()) return own
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = firstThumbnail(node.opt(keys.next()))
                    if (found.isNotEmpty()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = firstThumbnail(node.opt(i))
                    if (found.isNotEmpty()) return found
                }
            }
        }
        return ""
    }

    /** The first video id (11 characters) found anywhere inside [node], or "". */
    private fun firstVideoId(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val own = node.optString("videoId")
                if (own.length == 11) return own
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = firstVideoId(node.opt(keys.next()))
                    if (found.isNotEmpty()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = firstVideoId(node.opt(i))
                    if (found.isNotEmpty()) return found
                }
            }
        }
        return ""
    }

    /**
     * The picture of a playlist, the way YouTube shows it: the picture of its first video ("" when the playlist is empty
     * or its page gave no video). Used for the playlists whose "Save to" answer carried no picture.
     */
    fun fetchPlaylistThumb(access: ApiAccess, playlistId: String, cookie: String): String {
        val extra = JSONObject()
        extra.put("browseId", "VL$playlistId")
        val id = firstVideoId(postApi("browse", access, extra, cookie))
        return if (id.isEmpty()) "" else "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    /**
     * Makes a new playlist of the account called [title] ([privacy]: PRIVATE, PUBLIC or UNLISTED) with [videoId] in it,
     * and returns its id. Throws when YouTube does not answer with the new playlist's id.
     */
    fun createPlaylist(access: ApiAccess, title: String, privacy: String, videoId: String, cookie: String): String {
        val extra = JSONObject()
        extra.put("title", title)
        extra.put("privacyStatus", privacy)
        extra.put("videoIds", JSONArray().put(videoId))
        val id = postApi("playlist/create", access, extra, cookie).optString("playlistId")
        if (id.isEmpty()) throw Failure("playlist not created")
        return id
    }

    /** Takes the account off a channel's subscribers. Throws when YouTube does not answer with success. */
    fun unsubscribe(access: ApiAccess, channelId: String, cookie: String) {
        val extra = JSONObject()
        extra.put("channelIds", JSONArray().put(channelId))
        extra.put("params", "CgIIAhgA")
        postApi("subscription/unsubscribe", access, extra, cookie)
    }

    // ---- The videos (from YouTube's own pages) ----

    private class Stats {
        var renderers = 0
        var lockups = 0
        var shorts = 0
        var posts = 0

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
     * One tab of a channel's page: "videos", "shorts", "posts" or "streams" (its live streams, past ones too: they are
     * marked as live type 3 so the Live chip lists them). A channel with no live streams (or no posts) has no such tab;
     * the page then comes with another tab open and the answer is an empty page (see [tabOpen]). A tab lists only its
     * own kind: the posts tab only posts, the other tabs no posts (a shelf of them can stand in a channel's page).
     */
    fun fetchChannelTab(cookie: String, lang: String, channel: SubsChannel, tab: String): SubsPage {
        val expected = if (tab == "streams" || tab == "posts") tab else null
        val page = fetchPage("$SITE/channel/${channel.id}/$tab?hl=$lang", cookie, lang, channel, expectTab = expected)
        val own = if (tab == "posts") page.videos.filter { it.post != null } else page.videos.filter { it.post == null }
        val marked = if (tab == "streams") own.map { if (it.live == 0 && !it.short) it.copy(live = 3) else it } else own
        return SubsPage(marked, page.token, page.apiKey, page.context)
    }

    /**
     * Whether the tab that is open in a channel's page data is the one named [tab] ("streams" or "posts"): the
     * `tabRenderer` marked `selected`, whose address ends with the tab's name (the posts tab's address says "posts" or,
     * in the older pages, "community") or whose `params` are the streams tab's. Null when the data lists no open tab at
     * all (a shape this code does not know): the caller then takes the page as it is.
     */
    private fun tabOpen(node: Any?, tab: String): Boolean? {
        if (node is JSONArray) {
            for (i in 0 until node.length()) tabOpen(node.opt(i), tab)?.let { return it }
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
                val path = url.substringBefore('?').trimEnd('/')
                return if (tab == "posts") {
                    path.endsWith("/posts") || path.endsWith("/community")
                } else {
                    path.endsWith("/$tab") || params.startsWith(STREAMS_PARAMS)
                }
            }
            tabOpen(value, tab)?.let { return it }
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
        expectTab: String? = null,
    ): SubsPage {
        val html = httpGet(address, cookie, lang)
        val data = initialData(html)
        if (data == null) {
            diag = "page ${html.length / 1024}KB without data"
            throw Failure("no page data")
        }
        // A channel with no live streams (or no posts) has no such tab: YouTube answers the address with the channel's
        // first tab instead, full of ordinary videos, which must not be taken for streams.
        if (expectTab != null && tabOpen(data, expectTab) == false) {
            diag = "page ${html.length / 1024}KB: no $expectTab tab"
            return SubsPage(emptyList(), null, null, null)
        }
        val stats = Stats()
        val videos = ArrayList<SubsVideo>()
        val tokens = ArrayList<String>()
        collectVideos(data, videos, HashSet(), tokens, stats)
        diag = "page ${html.length / 1024}KB: videoRenderer ${stats.renderers}, lockup ${stats.lockups}, " +
            "shorts ${stats.shorts}, posts ${stats.posts}, videos ${videos.size}"
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

    /** True when [videos] are (nearly) all posts: the answer of the Posts chip, not of another one. */
    fun mostlyPosts(videos: List<SubsVideo>): Boolean = videos.isNotEmpty() && videos.count { it.post != null } * 5 >= videos.size * 4

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
        val note = "items ${videos.size}, lockup ${stats.lockups}, shorts ${stats.shorts}, posts ${stats.posts}, " +
            "post keys ${postKeys(data).joinToString(",").ifEmpty { "-" }}"
        return SubsPage(videos, tokens.lastOrNull() ?: stats.loose.lastOrNull(), apiKey, context, note = note)
    }

    /** The names (up to a few) of the keys of [data] that say "post": shows what shape YouTube gave posts when none was read. */
    private fun postKeys(data: Any?): Set<String> {
        val out = LinkedHashSet<String>()
        collectPostKeys(data, out)
        return out
    }

    private fun collectPostKeys(node: Any?, out: MutableSet<String>) {
        if (out.size >= 8) return
        if (node is JSONArray) {
            for (i in 0 until node.length()) collectPostKeys(node.opt(i), out)
            return
        }
        if (node !is JSONObject) return
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.contains("post", ignoreCase = true) && key != "postId") out.add(key)
            collectPostKeys(node.opt(key), out)
        }
    }

    /**
     * Collects the videos in the order they stand in the page, and the list's continuation tokens (the ones inside a
     * `continuationItemRenderer`; any other continuation command goes to [Stats.loose]). Two shapes of video are
     * known: the long-standing `videoRenderer` and the newer `lockupViewModel`. Shorts come in their own shapes
     * (`reelItemRenderer`, `shortsLockupViewModel`) and are kept, marked as Shorts; so are posts of channels (the
     * long-standing `backstagePostRenderer` and the newer `postRenderer`), as a [SubsVideo] that carries a [SubsPost];
     * playlists and anything else are left out.
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
                "backstagePostRenderer", "postRenderer" -> {
                    // Not searched inside: a video attached to a post is a part of the post, not a video of the feed.
                    stats.posts++
                    val video = (value as? JSONObject)?.let { postFromRenderer(it) }
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
                else -> {
                    // YouTube renames its renderers now and then: any other object that carries a post's own id under a
                    // key that says "post" is a post too (its fields are read on their own, see [postFromRenderer]).
                    if (value is JSONObject && key.contains("post", ignoreCase = true) && value.optString("postId").isNotEmpty()) {
                        stats.posts++
                        val video = postFromRenderer(value)
                        if (video != null && seen.add(video.id)) out.add(video)
                    } else {
                        collectVideos(value, out, seen, tokens, stats)
                    }
                }
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

    // ---- The posts of channels ----

    /** The most pictures and poll choices of a post that are kept (a post with more shows these). */
    private const val MAX_POST_IMAGES = 10
    private const val MAX_POST_CHOICES = 8

    /**
     * A post of a channel (`backstagePostRenderer`, or `postRenderer` in the newer feeds; both carry the same fields) as
     * a [SubsVideo] holding a [SubsPost]. Every field is read on its own and may be missing: a post is dropped only
     * when it has no id or nothing at all to show.
     */
    private fun postFromRenderer(o: JSONObject): SubsVideo? {
        val id = o.optString("postId")
        if (id.isEmpty()) return null

        val author = o.opt("authorText")
        val channelId = findText(author, "browseId") { CHANNEL_ID.matches(it) }
            ?: findText(o.opt("authorEndpoint"), "browseId") { CHANNEL_ID.matches(it) }.orEmpty()
        // `contentText` in both known shapes; a renamed field is looked for under its likeliest other names.
        val (text, links) = postText(o.opt("contentText") ?: o.opt("content") ?: o.opt("text"))

        val attachment = o.opt("backstageAttachment")
        val images = ArrayList<String>()
        collectPostImages(attachment, images)

        val choices = ArrayList<PostChoice>()
        var votes = ""
        val poll = findObject(attachment, "pollRenderer")
        if (poll != null) {
            val list = poll.optJSONArray("choices")
            if (list != null) {
                for (i in 0 until minOf(list.length(), MAX_POST_CHOICES)) {
                    val choice = list.optJSONObject(i) ?: continue
                    val words = textOf(choice.opt("text"))
                    val picture = bestImage(choice.optJSONObject("image"))
                    if (words.isNotEmpty() || picture.isNotEmpty()) {
                        choices.add(
                            PostChoice(
                                text = words,
                                image = picture,
                                select = commandText(choice.opt("selectServiceEndpoint")),
                                deselect = commandText(choice.opt("deselectServiceEndpoint")),
                                shareIfSelected = textOf(choice.opt("votePercentageIfSelected")),
                                shareIfNotSelected = textOf(choice.opt("votePercentageIfNotSelected")),
                                selected = choice.optBoolean("selected"),
                            )
                        )
                    }
                }
            }
            votes = textOf(poll.opt("totalVotes"))
        }

        val attached = findObject(attachment, "videoRenderer")
        val videoId = attached?.optString("videoId").orEmpty().takeIf { it.length == 11 }.orEmpty()
        val videoTitle = if (videoId.isEmpty()) "" else textOf(attached?.opt("title"))

        if (text.isEmpty() && images.isEmpty() && choices.isEmpty() && videoId.isEmpty()) return null

        // The counts: the post's own `voteCount`, else the like button's text; the comments are the reply button's text.
        val buttons = o.optJSONObject("actionButtons")?.optJSONObject("commentActionButtonsRenderer")
        var likes = textOf(o.opt("voteCount"))
        if (likes.isEmpty()) {
            likes = textOf(buttons?.optJSONObject("likeButton")?.optJSONObject("toggleButtonRenderer")?.opt("defaultText"))
        }
        val comments = textOf(buttons?.optJSONObject("replyButton")?.optJSONObject("buttonRenderer")?.opt("text"))
        // The like button's own commands (like, take the like back) and whether the account has liked the post already.
        val likeButton = buttons?.optJSONObject("likeButton")?.optJSONObject("toggleButtonRenderer")
        val likeCommand = commandText(likeButton?.opt("defaultServiceEndpoint"))
        val unlikeCommand = commandText(likeButton?.opt("toggledServiceEndpoint"))
        val liked = likeButton?.optBoolean("isToggled") == true

        return SubsVideo(
            id = id,
            title = text.replace('\n', ' ').take(100),
            channelId = channelId,
            channelName = textOf(author),
            published = textOf(o.opt("publishedTimeText")),
            post = SubsPost(
                text = text,
                links = links,
                avatar = bestImage(o.optJSONObject("authorThumbnail")),
                images = images,
                choices = choices,
                votes = votes,
                videoId = videoId,
                videoTitle = videoTitle,
                likes = likes,
                comments = comments,
                likeCommand = likeCommand,
                unlikeCommand = unlikeCommand,
                liked = liked,
            ),
        )
    }

    // ---- Voting and liking from a post's card (YouTube's own commands, run with the account's cookies) ----

    /**
     * A command of YouTube's page (a vote, a like) kept as JSON text, or "" when the page gave none the app can run
     * (the sign-in page that a signed-out page puts there, for one).
     */
    private fun commandText(value: Any?): String {
        val command = value as? JSONObject ?: return ""
        val leaves = ArrayList<JSONObject>()
        collectCommands(command, leaves, 0)
        return if (leaves.isEmpty()) "" else command.toString()
    }

    /** True for a command that is a call of YouTube's API: it names the address to send to, or is a like / a comment action. */
    private fun isApiCommand(node: JSONObject): Boolean {
        val api = node.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("apiUrl").orEmpty()
        return api.isNotEmpty() || node.has("likeEndpoint") || node.has("performCommentActionEndpoint")
    }

    /** Collects the API calls inside [node], which is one or a wrapper of several (YouTube nests them a few ways). */
    private fun collectCommands(node: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (node !is JSONObject || depth > 6) return
        for (wrapper in listOf("commandExecutorCommand", "serialCommand", "parallelCommand")) {
            val list = node.optJSONObject(wrapper)?.optJSONArray("commands") ?: continue
            for (i in 0 until list.length()) collectCommands(list.opt(i), out, depth + 1)
        }
        for (wrapper in listOf("innertubeCommand", "command", "performOnceCommand", "serviceEndpoint")) {
            collectCommands(node.optJSONObject(wrapper), out, depth + 1)
        }
        if (isApiCommand(node)) out.add(node)
    }

    /** The path under `youtubei/v1/` that a command is sent to, or "" when it names none the app knows. */
    private fun commandPath(command: JSONObject): String {
        val named = command.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("apiUrl").orEmpty()
        var path = named.substringAfter("/youtubei/v1/", named).trim('/')
        if (path.isEmpty()) {
            val status = command.optJSONObject("likeEndpoint")?.optString("status").orEmpty()
            path = when {
                command.has("performCommentActionEndpoint") -> "comment/perform_comment_action"
                command.has("likeEndpoint") -> when (status) {
                    "LIKE" -> "like/like"
                    "DISLIKE" -> "like/dislike"
                    else -> "like/removelike"
                }
                else -> ""
            }
        }
        // Only a plain path of YouTube's own API: never an address, never a query.
        return if (Regex("[A-Za-z0-9_/]+").matches(path)) path else ""
    }

    /**
     * The fields a command sends with the page's context, the way YouTube's own page builds them: a like sends its
     * target (and its params), a comment action its action, and any other command the fields of its own endpoint.
     */
    private fun commandFields(command: JSONObject): JSONObject {
        val fields = JSONObject()
        command.optJSONObject("performCommentActionEndpoint")?.let { endpoint ->
            fields.put("actions", JSONArray().put(endpoint.optString("action")))
            return fields
        }
        command.optJSONObject("likeEndpoint")?.let { endpoint ->
            endpoint.optJSONObject("target")?.let { fields.put("target", it) }
            for (name in listOf("likeParams", "removeLikeParams", "dislikeParams")) {
                val params = endpoint.optString(name)
                if (params.isNotEmpty()) {
                    fields.put("params", params)
                    break
                }
            }
            return fields
        }
        val key = command.keys().asSequence().firstOrNull { it.endsWith("Endpoint") }
        val endpoint = key?.let { command.optJSONObject(it) } ?: return fields
        val names = endpoint.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (name == "clickTrackingParams" || name == "nextEndpoint") continue
            fields.put(name, endpoint.get(name))
        }
        return fields
    }

    /**
     * Runs a post's vote or like: the [command] YouTube wrote on its page (see [PostChoice.select],
     * [SubsPost.likeCommand]) is sent to YouTube's API signed in as the account. True when YouTube accepted every
     * call of it; a refusal, a missing sign-in or a command with nothing to send throws [Failure].
     */
    fun runCommand(access: ApiAccess, command: String, cookie: String): Boolean {
        val leaves = ArrayList<JSONObject>()
        collectCommands(JSONObject(command), leaves, 0)
        var sent = 0
        for (leaf in leaves) {
            val path = commandPath(leaf)
            if (path.isEmpty()) continue
            val answer = postApi(path, access, commandFields(leaf), cookie)
            if (answer.has("error")) throw Failure("refused")
            sent++
        }
        if (sent == 0) throw Failure("nothing to send")
        return true
    }

    /** The words of a post as one text, and where its links stand in it (a link's own text is often cut short by YouTube). */
    private fun postText(value: Any?): Pair<String, List<PostLink>> {
        if (value is String) return Pair(value, emptyList())
        if (value !is JSONObject) return Pair("", emptyList())
        val simple = value.optString("simpleText")
        if (simple.isNotEmpty()) return Pair(simple, emptyList())
        val runs = value.optJSONArray("runs") ?: return Pair("", emptyList())
        val text = StringBuilder()
        val links = ArrayList<PostLink>()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val piece = run.optString("text")
            val url = linkOf(run.optJSONObject("navigationEndpoint"))
            if (url.isNotEmpty() && piece.isNotEmpty()) links.add(PostLink(text.length, text.length + piece.length, url))
            text.append(piece)
        }
        // Only the end is trimmed, so the places of the links (counted from the start) stay true.
        val whole = text.toString().trimEnd()
        return Pair(whole, links.filter { it.end <= whole.length })
    }

    /** Where a link of a post's text goes: an https address, or "" when it is none the app can open. */
    private fun linkOf(endpoint: JSONObject?): String {
        if (endpoint == null) return ""
        var url = endpoint.optJSONObject("urlEndpoint")?.optString("url").orEmpty().trim()
        if (url.isEmpty()) {
            url = endpoint.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty().trim()
        }
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("/")) url = MOBILE_SITE + url
        if (!url.startsWith("https://") && !url.startsWith("http://")) return ""
        // YouTube sends a link to another site through its own redirect page: the site itself is the address.
        val uri = android.net.Uri.parse(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if ((host == "youtube.com" || host.endsWith(".youtube.com")) && uri.path == "/redirect") {
            val target = uri.getQueryParameter("q").orEmpty()
            if (target.startsWith("https://") || target.startsWith("http://")) return target
        }
        return url.replaceFirst("://www.youtube.com", "://m.youtube.com")
    }

    /** The pictures attached to a post (`backstageImageRenderer`, one or several), the largest size of each. */
    private fun collectPostImages(node: Any?, out: MutableList<String>) {
        if (node is JSONArray) {
            for (i in 0 until node.length()) collectPostImages(node.opt(i), out)
            return
        }
        if (node !is JSONObject) return
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            if (key == "backstageImageRenderer" && value is JSONObject) {
                val url = bestImage(value.optJSONObject("image"))
                if (url.isNotEmpty() && url !in out && out.size < MAX_POST_IMAGES) out.add(url)
            } else {
                collectPostImages(value, out)
            }
        }
    }

    /** The first object stored under [key] anywhere inside [node], or null. */
    private fun findObject(node: Any?, key: String): JSONObject? {
        if (node is JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = node.opt(k)
                if (k == key && v is JSONObject) return v
                val found = findObject(v, key)
                if (found != null) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findObject(node.opt(i), key)
                if (found != null) return found
            }
        }
        return null
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
                        post = o.optJSONObject("po")?.let { postFromJson(it) },
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
            val entry = JSONObject().put("i", v.id).put("t", v.title).put("c", v.channelId).put("h", v.channelName)
                .put("d", v.duration).put("p", v.published).put("w", v.views)
                .put("pm", v.publishedMs).put("vc", v.viewCount)
                .put("sh", v.short).put("lv", v.live).put("wp", v.watched)
            v.post?.let { entry.put("po", postToJson(it)) }
            videoArray.put(entry)
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

    private fun postToJson(p: SubsPost): JSONObject {
        val links = JSONArray()
        for (l in p.links) links.put(JSONArray().put(l.start).put(l.end).put(l.url))
        val images = JSONArray()
        for (url in p.images) images.put(url)
        val choices = JSONArray()
        for (c in p.choices) {
            choices.put(
                JSONObject().put("t", c.text).put("i", c.image).put("se", c.select).put("de", c.deselect)
                    .put("ps", c.shareIfSelected).put("pn", c.shareIfNotSelected).put("sl", c.selected)
            )
        }
        return JSONObject().put("t", p.text).put("l", links).put("a", p.avatar).put("im", images).put("ch", choices)
            .put("v", p.votes).put("vi", p.videoId).put("vt", p.videoTitle).put("lk", p.likes).put("cm", p.comments)
            .put("lc", p.likeCommand).put("uc", p.unlikeCommand).put("ld", p.liked)
    }

    private fun postFromJson(o: JSONObject): SubsPost {
        val links = ArrayList<PostLink>()
        val linkArray = o.optJSONArray("l")
        if (linkArray != null) {
            for (i in 0 until linkArray.length()) {
                val l = linkArray.optJSONArray(i) ?: continue
                links.add(PostLink(l.optInt(0), l.optInt(1), l.optString(2)))
            }
        }
        val images = ArrayList<String>()
        val imageArray = o.optJSONArray("im")
        if (imageArray != null) {
            for (i in 0 until imageArray.length()) images.add(imageArray.optString(i))
        }
        val choices = ArrayList<PostChoice>()
        val choiceArray = o.optJSONArray("ch")
        if (choiceArray != null) {
            for (i in 0 until choiceArray.length()) {
                val c = choiceArray.optJSONObject(i) ?: continue
                choices.add(
                    PostChoice(
                        text = c.optString("t"), image = c.optString("i"),
                        select = c.optString("se"), deselect = c.optString("de"),
                        shareIfSelected = c.optString("ps"), shareIfNotSelected = c.optString("pn"),
                        selected = c.optBoolean("sl"),
                    )
                )
            }
        }
        return SubsPost(
            text = o.optString("t"), links = links, avatar = o.optString("a"), images = images, choices = choices,
            votes = o.optString("v"), videoId = o.optString("vi"), videoTitle = o.optString("vt"),
            likes = o.optString("lk"), comments = o.optString("cm"),
            likeCommand = o.optString("lc"), unlikeCommand = o.optString("uc"), liked = o.optBoolean("ld"),
        )
    }

    fun clear(context: Context) {
        cacheFile(context).delete()
    }
}
