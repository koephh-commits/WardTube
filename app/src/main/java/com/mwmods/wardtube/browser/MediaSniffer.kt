// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import java.util.Locale

/** One thing the open page offers for download. */
class Detected(
    /** The file's address, or the page's own address for [Kind.PAGE]. */
    val url: String,
    val kind: Kind,
    val name: String,
    /** Guessed from the extension; empty when unknown. */
    val mimeType: String,
) {
    /** Declaration order is the order of the list the person sees. */
    enum class Kind {
        /** A video that lives inside the page (no file address to see): yt-dlp is given the page. */
        PAGE,

        /**
         * A player that lives in a frame (iframe) of the page. The address is the frame's own page; yt-dlp is given it
         * with the open page as referer, because such players often refuse to answer without it.
         */
        FRAME,

        /** A video file (mp4, webm...). */
        VIDEO,

        /** A playlist of a streamed video (m3u8, mpd): yt-dlp downloads and joins the pieces. */
        STREAM,

        /** An audio file. */
        AUDIO,

        /** Anything else the page links to (zip, pdf, apk...). */
        FILE,
    }
}

/**
 * Finds what the open page offers for download, from two sides:
 *
 *  - The network: every request the WebView makes is looked at by its address ([inspect], on a WebView thread), so a
 *    video file or a streaming playlist is seen even when it is inside an iframe.
 *  - The page: a small script ([SCRIPT]) looks at `<video>` / `<audio>` elements, links to files and the page's
 *    video meta tags, and reports through [bridge].
 *
 * Whatever is found goes to [onChanged] (on the main thread) with the whole current list. The list belongs to one page:
 * the screen calls [clear] when another page starts.
 *
 * Only addresses are looked at here, no request is made and no data is read, so it costs almost nothing.
 */
class MediaSniffer(
    /** The address of the open page, asked on the main thread when a video that lives inside the page is reported. */
    private val pageUrl: () -> String?,
    private val onChanged: (List<Detected>) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. Keyed by address, in the order found. */
    private val items = LinkedHashMap<String, Detected>()

    /** What the page's script may call. Any frame of any page can reach it, so everything is checked again. */
    val bridge = Bridge()

    /** What was found, in the order shown to the person: the page's video first, files last. */
    fun snapshot(): List<Detected> = offered().sortedBy { it.kind.ordinal }

    /**
     * What is offered to the person. When the page has loaded a video file or a stream, its player's frame is left
     * out: yt-dlp cannot read most players (it answers "Unsupported URL"), and the file the player loaded is already
     * in the list. The frame stays when nothing else was found, because for some players (YouTube's embed, for one)
     * the frame is exactly what yt-dlp understands.
     */
    private fun offered(): List<Detected> {
        val all = items.values.toList()
        val hasFile = all.any { it.kind == Detected.Kind.VIDEO || it.kind == Detected.Kind.STREAM }
        return if (hasFile) all.filter { it.kind != Detected.Kind.FRAME } else all
    }

    /** Main thread. A new page started: what the old one offered is gone. */
    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        onChanged(emptyList())
    }

    /** Main thread. The page's own address can be a media file (a video opened directly). */
    fun inspectPage(url: String) {
        fromAddress(url, allowFiles = true)?.let { add(it) }
    }

    /**
     * What [url] is when the address itself is a file, a video / audio file or a streamed video, by its extension;
     * null for a page. Looks only at the address, nothing is added to the list.
     */
    fun directFile(url: String): Detected? = fromAddress(url, allowFiles = true)

    /** Any thread: called for each request of the page. Pieces of a stream and ordinary resources are skipped. */
    fun inspect(request: WebResourceRequest) {
        // The page's own address is handled by inspectPage (on the main thread, in order with the page start).
        if (request.isForMainFrame) return
        if (!request.method.equals("GET", ignoreCase = true)) return
        val found = fromAddress(request.url.toString(), allowFiles = false) ?: return
        main.post { add(found) }
    }

    private fun add(item: Detected) {
        if (items.containsKey(item.url)) return
        // Full: the oldest goes. What plays now must never be turned away (a music site keeps adding songs).
        if (items.size >= MAX_ITEMS) items.remove(items.keys.first())
        items[item.url] = item
        onChanged(offered())
    }

    // ---- Reading an address ----

    /**
     * What an address is, by its extension. [allowFiles] is false for the page's own requests: a zip or a pdf asked for
     * by a page is not something the person is looking at, while a link in the page to one is.
     */
    private fun fromAddress(url: String, allowFiles: Boolean): Detected? {
        if (url.length > MAX_URL_LENGTH || !isWeb(url)) return null
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val segment = withoutQuery.substringAfterLast('/')
        val extension = segment.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val kind = when {
            extension.isEmpty() || extension.length > 5 -> return embeddedStream(url, withoutQuery)
            extension in VIDEO_EXTENSIONS -> Detected.Kind.VIDEO
            extension in AUDIO_EXTENSIONS -> Detected.Kind.AUDIO
            extension in STREAM_EXTENSIONS -> Detected.Kind.STREAM
            allowFiles && extension in FILE_EXTENSIONS -> Detected.Kind.FILE
            else -> return embeddedStream(url, withoutQuery)
        }
        if (looksLikePiece(withoutQuery)) return null
        return build(url, kind, "", extension)
    }

    /**
     * A streaming playlist named inside the address's query, where the address itself has no usable extension: a proxy
     * or a player's own endpoint (`/play?src=https%3A%2F%2Fcdn.site%2Fmaster.m3u8`). Only playlists are taken this way:
     * the query of an ordinary request can mention any file, but `.m3u8` / `.mpd` in it is a stream almost every time.
     */
    private fun embeddedStream(url: String, withoutQuery: String): Detected? {
        val query = url.substringBefore('#').substringAfter('?', "")
        // Most requests carry a query and none of them names a playlist: a cheap look first, the decoding only if needed.
        if (!query.contains("m3u8", ignoreCase = true) && !query.contains("mpd", ignoreCase = true)) return null
        if (looksLikePiece(withoutQuery)) return null
        val found = EMBEDDED_STREAM.find(Uri.decode(query)) ?: return null
        return build(url, Detected.Kind.STREAM, "", found.groupValues[1].lowercase(Locale.ROOT))
    }

    /** One small piece of a longer stream (not a file the person wants on its own). */
    private fun looksLikePiece(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return PIECE_MARKERS.any { it in lower }
    }

    private fun build(url: String, kind: Detected.Kind, label: String, extension: String): Detected {
        val segment = Uri.decode(url.substringBefore('?').substringBefore('#').substringAfterLast('/'))
        var name = label.trim().ifEmpty { segment }
        if (name.isEmpty()) name = Uri.parse(url).host.orEmpty().ifEmpty { "download" }
        name = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        var ext = extension
        // A link the page vouches for as a video / audio, but whose address has no extension: a guess keeps the file
        // in the right Library folder and playable.
        if (ext.isEmpty() && name.substringAfterLast('.', "").length !in 1..5) {
            when (kind) {
                Detected.Kind.VIDEO -> ext = "mp4"
                Detected.Kind.AUDIO -> ext = "mp3"
                else -> {}
            }
            if (ext.isNotEmpty()) name = "$name.$ext"
        }
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext.ifEmpty { name.substringAfterLast('.', "").lowercase(Locale.ROOT) })
            .orEmpty()
        return Detected(url, kind, name.take(MAX_NAME_LENGTH), mime)
    }

    private fun isWeb(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    // ---- The page's script ----

    inner class Bridge {
        /**
         * [kind]: "page" (a video that lives inside the page, [url] empty), "frame" (a player in an iframe, [url] is
         * the frame's address), "video", "audio" or "file". [name] is the link's own file name when it has one.
         */
        @JavascriptInterface
        fun report(kind: String?, url: String?, name: String?) {
            if (kind == "page") {
                main.post {
                    val page = pageUrl()?.substringBefore('#')
                    if (page != null && isWeb(page)) add(Detected(page, Detected.Kind.PAGE, page, ""))
                }
                return
            }
            if (kind == "frame") {
                val frame = url?.trim().orEmpty()
                if (frame.length > MAX_URL_LENGTH || !isWeb(frame)) return
                // A frame whose address is itself a media file is that file, not a player.
                val asFile = fromAddress(frame, allowFiles = false)
                val uri = Uri.parse(frame)
                val label = (uri.host.orEmpty() + uri.path.orEmpty()).ifEmpty { frame }
                main.post { add(asFile ?: Detected(frame, Detected.Kind.FRAME, label.take(MAX_NAME_LENGTH), "")) }
                return
            }
            val reported = when (kind) {
                "video" -> Detected.Kind.VIDEO
                "audio" -> Detected.Kind.AUDIO
                "file" -> Detected.Kind.FILE
                else -> return
            }
            val link = url?.trim().orEmpty()
            if (link.length > MAX_URL_LENGTH || !isWeb(link)) return
            val label = name.orEmpty().take(MAX_NAME_LENGTH)
            // The address's own extension tells more than the tag it was found in (a link to an mp4, a video whose
            // source is an m3u8 playlist).
            val byAddress = fromAddress(link, allowFiles = true)
            val item = if (byAddress != null) {
                if (label.isEmpty()) byAddress else build(link, byAddress.kind, label, extensionOf(link))
            } else {
                build(link, reported, label, extensionOf(link))
            }
            main.post { add(item) }
        }

        /**
         * From the network watcher ([NET_SCRIPT]): what the server answered for [url] says it is a stream playlist
         * ("stream"), a video or an audio file, whatever the address looks like. This is how a player whose playlist has
         * no `.m3u8` in its address (a `/play?id=12`, say) is found. Pieces of a stream and the video servers of sites
         * the app already handles in its own way are left out.
         */
        @JavascriptInterface
        fun reportNet(kind: String?, url: String?) {
            val link = url?.trim().orEmpty()
            if (link.length > MAX_URL_LENGTH || !isWeb(link)) return
            val reported = when (kind) {
                "stream" -> Detected.Kind.STREAM
                "video" -> Detected.Kind.VIDEO
                "audio" -> Detected.Kind.AUDIO
                else -> return
            }
            val path = link.substringBefore('?').substringBefore('#')
            if (looksLikePiece(path) || extensionOf(link) in PIECE_EXTENSIONS || isEngineHost(link)) return
            val item = fromAddress(link, allowFiles = false) ?: build(link, reported, "", extensionOf(link))
            main.post { add(item) }
        }
    }

    /** The video servers of YouTube: their addresses are ranges of one stream, only yt-dlp can use them. */
    private fun isEngineHost(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT).orEmpty()
        return host.endsWith("googlevideo.com")
    }

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase(Locale.ROOT).takeIf { it.length in 1..5 }.orEmpty()

    companion object {
        /** The name the script reaches [Bridge] by. */
        const val BRIDGE_NAME = "WardTubeSniff"

        /** Runs again for the same page: the script ignores a second run, apart from a fresh look at the page. */
        const val RESET_CALL = "window.__wtSniffReset&&window.__wtSniffReset();window.__wtNetReset&&window.__wtNetReset()"

        private const val MAX_ITEMS = 40
        private const val MAX_URL_LENGTH = 4096
        private const val MAX_NAME_LENGTH = 120

        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp", "wmv", "ogv")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "wma")
        private val STREAM_EXTENSIONS = setOf("m3u8", "mpd")
        private val FILE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "apk", "xapk", "apks",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "mobi", "iso",
        )
        private val PIECE_MARKERS = listOf("segment", "/seg-", "/seg_", "chunk", "fragment", "/frag", "/init.")

        /** Extensions of the small pieces a stream is cut into (never a download of their own). */
        private val PIECE_EXTENSIONS = setOf("m4s", "ts", "m4f", "mts", "cmfv", "cmfa")

        /** `.m3u8` / `.mpd` inside an address's (decoded) query: the playlist a proxy or player endpoint points to. */
        private val EMBEDDED_STREAM = Regex("""\.(m3u8|mpd)(?=$|[?&#/])""", RegexOption.IGNORE_CASE)

        /**
         * Injected when a page has loaded. Looks at the page now, then again (a moment after) whenever the page changes
         * or a media element starts. Reports each address once. Plain JavaScript in a Kotlin raw string: the only
         * template in it is the bridge's name (the `$` at the end of a regular expression is a plain character).
         */
        val SCRIPT = """
(function () {
  if (window.__wtSniffReset) { window.__wtSniffReset(); return; }
  var B_NAME = '$BRIDGE_NAME';
  // The same script also runs inside every iframe (see FRAME_SCRIPT), where it reports through the same bridge.
  var TOP = true;
  try { TOP = window === window.top; } catch (e) { TOP = false; }
  var FILE = /\.(zip|rar|7z|tar|gz|tgz|apk|xapk|apks|pdf|docx?|xlsx?|pptx?|epub|mobi|iso)$/i;
  var MEDIA = /\.(mp4|m4v|webm|mkv|mov|avi|flv|3gp|wmv|ogv|mp3|m4a|aac|ogg|oga|opus|wav|flac|wma|m3u8|mpd)$/i;
  var NOT_PLAYER = /(doubleclick|googlesyndication|googleadservices|adservice|recaptcha|disqus\.com|facebook\.com\/plugins|platform\.twitter|twitter\.com\/widgets|addthis|sharethis|google\.com\/maps)/i;
  var PLAYER = /(player|embed|video|stream|vid|play)/i;
  var AUDIO_PLAYER = /(music|audio|song|track|sound|podcast|radio|mp3)/i;
  var seen = {};
  var anchors = -1;
  var timer = 0;
  function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return ''; } }
  function pathOf(u) { try { return new URL(u).pathname; } catch (e) { return ''; } }
  function send(kind, url, name) {
    var key = kind + '|' + url;
    if (seen[key]) return;
    // The bridge can be missing for a moment (a frame's first milliseconds): not marked as seen, so the next look tries again.
    var B = window[B_NAME];
    if (!B || !B.report) return;
    seen[key] = 1;
    try { B.report(kind, url, name || ''); } catch (e) {}
  }
  function scanMedia() {
    var media = document.querySelectorAll('video,audio');
    for (var i = 0; i < media.length && i < 30; i++) {
      var el = media[i];
      var isVideo = el.tagName === 'VIDEO';
      // A silent looping clip without controls is decoration, not something to download.
      if (isVideo && el.loop && el.muted && !el.controls) continue;
      var active = !el.paused || el.currentTime > 0;
      // Inside a frame only what is playing (or has played) counts, and not a short clip: that keeps ad frames and
      // interface sounds out. The page itself keeps reporting a video as soon as it is there.
      if (!TOP) {
        if (!active || NOT_PLAYER.test(location.href)) continue;
        if (isFinite(el.duration) && el.duration < 20) continue;
      }
      var srcs = [];
      if (el.currentSrc) srcs.push(el.currentSrc);
      if (el.getAttribute('src')) srcs.push(el.getAttribute('src'));
      var sources = el.querySelectorAll('source[src]');
      for (var j = 0; j < sources.length && j < 8; j++) srcs.push(sources[j].getAttribute('src'));
      var blob = false, direct = false;
      for (var k = 0; k < srcs.length; k++) {
        var s = srcs[k] || '';
        if (/^blob:/i.test(s)) { blob = true; continue; }
        var a = abs(s);
        if (/^https?:/i.test(a)) { direct = true; send(isVideo ? 'video' : 'audio', a, ''); }
      }
      // No address to give (a stream built in memory): the page, or the frame it plays in, is what yt-dlp is given.
      var hidden = !direct && (blob || el.readyState > 0 || el.videoWidth > 0);
      if (hidden && (isVideo || (blob && active && (!isFinite(el.duration) || el.duration > 30)))) {
        if (TOP) send('page', '', ''); else send('frame', location.href, '');
      }
    }
    // A player in an iframe (the site's own player page): its video cannot be reached from here, but the frame can be
    // named. Only frames that look like a player count: a big enough box that allows full screen, or one shaped like a
    // video whose address says player / embed / video. Ads, comment boxes and share widgets are skipped.
    var frames = document.querySelectorAll('iframe');
    for (var f = 0; f < frames.length && f < 12; f++) {
      var fr = frames[f];
      var fsrc = fr.getAttribute('src') || fr.getAttribute('data-src') || '';
      if (!fsrc || /^(about|javascript|data|blob):/i.test(fsrc)) continue;
      var fu = abs(fsrc);
      if (!/^https?:/i.test(fu) || NOT_PLAYER.test(fu)) continue;
      var fw = fr.offsetWidth, fh = fr.offsetHeight;
      // A music widget is a bar, not a box: it passes when its address says player + music.
      var audioish = PLAYER.test(fu) && AUDIO_PLAYER.test(fu);
      if (fw < 200 || fh < 110) {
        if (!(audioish && fw >= 200 && fh >= 40)) continue;
      }
      var full = fr.hasAttribute('allowfullscreen') || /fullscreen/i.test(fr.getAttribute('allow') || '');
      var ratio = fw / fh;
      if (full || audioish || (ratio >= 1.2 && ratio <= 2.6 && fw >= 240 && PLAYER.test(fu))) send('frame', fu, '');
    }
  }
  function scanPage() {
    var metas = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]');
    for (var m = 0; m < metas.length && m < 6; m++) {
      var c = abs(metas[m].getAttribute('content') || '');
      if (/^https?:/i.test(c) && MEDIA.test(pathOf(c))) send('video', c, '');
    }
    var type = document.querySelector('meta[property="og:type"]');
    if (type && /^video/i.test(type.getAttribute('content') || '')) send('page', '', '');
    var links = document.querySelectorAll('a[href]');
    if (links.length !== anchors) {
      anchors = links.length;
      for (var n = 0; n < links.length && n < 2500; n++) {
        var h = links[n].getAttribute('href');
        if (!h || h.charAt(0) === '#' || /^(javascript|mailto|tel|blob|data):/i.test(h)) continue;
        var u = abs(h);
        if (!/^https?:/i.test(u)) continue;
        var p = pathOf(u);
        var d = links[n].hasAttribute('download');
        if (FILE.test(p) || MEDIA.test(p) || d) send('file', u, links[n].getAttribute('download') || '');
      }
    }
  }
  function scan() { scanMedia(); if (TOP) scanPage(); }
  function later() {
    if (timer) return;
    timer = setTimeout(function () { timer = 0; scan(); }, 700);
  }
  window.__wtSniffReset = function () { seen = {}; anchors = -1; later(); };
  function boot() {
    try {
      new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'data-src'] });
    } catch (e) {}
    scan();
  }
  ['play', 'playing', 'loadedmetadata'].forEach(function (t) { document.addEventListener(t, later, true); });
  // Players start late (a click, a lazy frame, a box that opens) and a media event of a frame never reaches this
  // document: a cheap look at the media and the frames every few seconds finds what nothing announced.
  setInterval(function () {
    if (document.visibilityState === 'hidden' || !document.documentElement) return;
    try { scanMedia(); } catch (e) {}
  }, 2500);
  if (document.documentElement) boot();
  else {
    var wait = setInterval(function () {
      if (document.documentElement) { clearInterval(wait); boot(); }
    }, 50);
  }
})();
"""

        /**
         * [SCRIPT] for the frames of a page: injected before the page's own scripts, in every frame, and it does nothing
         * in the page itself (that one is looked at by [SCRIPT] once it is shown). The `<video>` / `<audio>` of an iframe
         * cannot be reached from the page, so each frame looks at its own and reports through the same bridge.
         */
        val FRAME_SCRIPT = SCRIPT.replaceFirst(
            "(function () {",
            "(function () {\n  var inFrame = true;\n  try { inFrame = window !== window.top; } catch (e) {}\n  if (!inFrame) return;",
        )

        /**
         * Injected before the page's own scripts, in every frame (document-start script). Watches what the page's code
         * downloads with fetch / XMLHttpRequest and reads what the SERVER says about each answer: a playlist type
         * (`mpegurl`, `dash+xml`), or a generic type whose first bytes are `#EXTM3U` / `<MPD`. So a stream is found
         * even when its address has no `.m3u8` in it, without any request of its own: only what the page already
         * downloads is looked at, and the page always gets its answer whole. Reports through [Bridge.reportNet].
         */
        val NET_SCRIPT = """
(function () {
  if (window.__wtNet) return;
  window.__wtNet = 1;
  var B_NAME = '$BRIDGE_NAME';
  var seen = {};
  var tested = {};
  var testedCount = 0;
  var queue = [];
  var sent = 0;
  var media = 0;
  var tries = 0;
  var waiting = false;
  var manifestSeen = false;
  var recent = [];
  // A type that says nothing about the content: only the first bytes of the answer can tell a playlist from the rest.
  var AMBIGUOUS = /^$|octet-stream|text\/plain|xml|binary/;

  function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return ''; } }

  // The app's bridge may not exist yet when the first answers arrive: what was found waits for it, a few seconds at most.
  function flush() {
    var B = window[B_NAME];
    if (B && B.reportNet) {
      while (queue.length) {
        var q = queue.shift();
        try { B.reportNet(q[0], q[1]); } catch (e) {}
      }
      return;
    }
    if (!waiting && tries < 20) {
      waiting = true;
      tries++;
      setTimeout(function () { waiting = false; flush(); }, 500);
    }
  }

  function send(kind, url) {
    if (!url || !/^https?:/i.test(url) || seen[url] || sent >= 60) return;
    seen[url] = 1;
    sent++;
    queue.push([kind, url]);
    recent.push([kind, url]);
    if (recent.length > 2) recent.shift();
    flush();
  }

  // Looks at what the server said about [url]. Returns true when only the first bytes of the body can tell.
  function inspect(url, type, length, range) {
    if (!url || seen[url]) return false;
    var t = (type || '').toLowerCase();
    if (/mpegurl|dash\+xml/.test(t)) {
      manifestSeen = true;
      send('stream', url);
      return false;
    }
    var m = /^(video|audio)\//.exec(t);
    if (m) {
      if (/mp2t|iso\.segment/.test(t)) return false;
      var total = /\/(\d+)\s*$/.exec(range || '');
      var size = total ? +total[1] : length;
      // A file of its own: big, and not part of a stream whose playlist this page already loaded.
      // An answer that hides its size is let through (a cross-site audio file often does).
      if (!manifestSeen && (size < 0 || size >= 1048576) && media < 12) {
        media++;
        send(m[1], url);
      }
      return false;
    }
    if (!AMBIGUOUS.test(t) || tested[url] || ++testedCount > 200) return false;
    tested[url] = 1;
    return true;
  }

  function body(url, head) {
    if (!head || seen[url]) return;
    if (/^\s*#EXTM3U/.test(head) || /<MPD[\s>]/.test(head)) {
      manifestSeen = true;
      send('stream', url);
    }
  }

  // The first chunk of a copy of the answer, then the copy is dropped: the page still gets its own answer whole.
  function peek(res, url) {
    try {
      var reader = res.clone().body.getReader();
      reader.read().then(function (r) {
        try { reader.cancel().catch(function () {}); } catch (e) {}
        if (r && r.value) {
          var bytes = r.value.length > 2000 ? r.value.subarray(0, 2000) : r.value;
          body(url, new TextDecoder().decode(bytes));
        }
      }, function () {});
    } catch (e) {}
  }

  function lengthOf(text) {
    var n = parseInt(text, 10);
    return isNaN(n) ? -1 : n;
  }

  // A page that changes without loading again (a single-page site): what was found for the old one is forgotten here too.
  window.__wtNetReset = function () {
    seen = {};
    sent = 0;
    media = 0;
    manifestSeen = false;
    // The app drops its list when the address changes, but a player that keeps playing through that change has
    // loaded its file or playlist already and will not ask again: the latest finds are given back while it plays.
    var again = recent.slice();
    recent = [];
    var playing = false;
    var all = document.querySelectorAll('video,audio');
    for (var i = 0; i < all.length && i < 10; i++) {
      if ((!all[i].paused || all[i].currentTime > 0) && !all[i].ended) playing = true;
    }
    if (!playing || !again.length) return;
    setTimeout(function () { for (var k = 0; k < again.length; k++) send(again[k][0], again[k][1]); }, 400);
  };

  var nativeFetch = window.fetch;
  if (typeof nativeFetch === 'function') {
    window.fetch = function (input) {
      var promise = nativeFetch.apply(this, arguments);
      try {
        var asked = typeof input === 'string' ? input : ((input && input.url) || String(input || ''));
        promise.then(function (res) {
          try {
            var h = res.headers;
            var url = abs(res.url || asked);
            if (inspect(url, h.get('content-type'), lengthOf(h.get('content-length')), h.get('content-range'))) peek(res, url);
          } catch (e) {}
        }, function () {});
      } catch (e) {}
      return promise;
    };
  }

  var XHR = window.XMLHttpRequest;
  if (XHR && XHR.prototype && XHR.prototype.send) {
    var nativeSend = XHR.prototype.send;
    XHR.prototype.send = function () {
      try {
        var x = this;
        var sniff = false;
        var url = '';
        x.addEventListener('readystatechange', function () {
          if (x.readyState !== 2) return;
          try {
            url = abs(x.responseURL);
            var length = lengthOf(x.getResponseHeader('content-length'));
            // Cross-site answers hide their length: unknown is allowed, a big known size is not.
            sniff = inspect(url, x.getResponseHeader('content-type'), length, x.getResponseHeader('content-range')) &&
              (length < 0 || length <= 1000000);
          } catch (e) {}
        });
        x.addEventListener('load', function () {
          if (!sniff) return;
          try {
            if (x.responseType === '' || x.responseType === 'text') body(url, (x.responseText || '').slice(0, 2000));
          } catch (e) {}
        });
      } catch (e) {}
      return nativeSend.apply(this, arguments);
    };
  }
})();
"""
    }
}
