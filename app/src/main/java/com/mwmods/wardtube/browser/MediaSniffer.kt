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
    fun snapshot(): List<Detected> = items.values.sortedBy { it.kind.ordinal }

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
        if (items.size >= MAX_ITEMS || items.containsKey(item.url)) return
        items[item.url] = item
        onChanged(items.values.toList())
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
        if (extension.isEmpty() || extension.length > 5) return null
        val kind = when {
            extension in VIDEO_EXTENSIONS -> Detected.Kind.VIDEO
            extension in AUDIO_EXTENSIONS -> Detected.Kind.AUDIO
            extension in STREAM_EXTENSIONS -> Detected.Kind.STREAM
            allowFiles && extension in FILE_EXTENSIONS -> Detected.Kind.FILE
            else -> return null
        }
        if (looksLikePiece(withoutQuery)) return null
        return build(url, kind, "", extension)
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
         * [kind]: "page" (a video that lives inside the page, [url] empty), "video", "audio" or "file". [name] is the
         * link's own file name when it has one.
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
    }

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase(Locale.ROOT).takeIf { it.length in 1..5 }.orEmpty()

    companion object {
        /** The name the script reaches [Bridge] by. */
        const val BRIDGE_NAME = "WardTubeSniff"

        /** Runs again for the same page: the script ignores a second run, apart from a fresh look at the page. */
        const val RESET_CALL = "window.__wtSniffReset&&window.__wtSniffReset()"

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

        /**
         * Injected when a page has loaded. Looks at the page now, then again (a moment after) whenever the page changes
         * or a media element starts. Reports each address once. Plain JavaScript in a Kotlin raw string: the only
         * template in it is the bridge's name (the `$` at the end of a regular expression is a plain character).
         */
        val SCRIPT = """
(function () {
  if (window.__wtSniffReset) { window.__wtSniffReset(); return; }
  var B = window.$BRIDGE_NAME;
  if (!B) return;
  var FILE = /\.(zip|rar|7z|tar|gz|tgz|apk|xapk|apks|pdf|docx?|xlsx?|pptx?|epub|mobi|iso)$/i;
  var MEDIA = /\.(mp4|m4v|webm|mkv|mov|avi|flv|3gp|wmv|ogv|mp3|m4a|aac|ogg|oga|opus|wav|flac|wma|m3u8|mpd)$/i;
  var seen = {};
  var anchors = -1;
  var timer = 0;
  function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return ''; } }
  function pathOf(u) { try { return new URL(u).pathname; } catch (e) { return ''; } }
  function send(kind, url, name) {
    var key = kind + '|' + url;
    if (seen[key]) return;
    seen[key] = 1;
    try { B.report(kind, url, name || ''); } catch (e) {}
  }
  function scan() {
    var media = document.querySelectorAll('video,audio');
    for (var i = 0; i < media.length && i < 30; i++) {
      var el = media[i];
      var isVideo = el.tagName === 'VIDEO';
      // A silent looping clip without controls is decoration, not something to download.
      if (isVideo && el.loop && el.muted && !el.controls) continue;
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
      if (isVideo && !direct && (blob || el.readyState > 0 || el.videoWidth > 0)) send('page', '', '');
    }
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
  function later() {
    if (timer) return;
    timer = setTimeout(function () { timer = 0; scan(); }, 700);
  }
  window.__wtSniffReset = function () { seen = {}; anchors = -1; later(); };
  try {
    new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  } catch (e) {}
  ['play', 'playing', 'loadedmetadata'].forEach(function (t) { document.addEventListener(t, later, true); });
  scan();
})();
"""
    }
}
