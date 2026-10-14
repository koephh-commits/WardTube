// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.player

import android.net.Uri
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Remote control for the page's <video> element. It keeps no static WebView reference, so
 * receivers and services never hold the view alive.
 */
object PlayerController {

    private const val VIDEO =
        "(document.querySelector('video.html5-main-video')||document.querySelector('video'))"

    /**
     * [positionMs] / [durationMs] are -1 when unknown (no video yet, or a live stream with no fixed length).
     * [rate] is the playback speed (1 = normal), [isLive] says YouTube's player reports a live stream.
     */
    data class State(
        val hasVideo: Boolean,
        val playing: Boolean,
        val title: String,
        val videoId: String,
        val positionMs: Long = -1L,
        val durationMs: Long = -1L,
        val rate: Float = 1f,
        val isLive: Boolean = false
    )

    // Tested in headless Chromium: normal videos, live streams (also with a rewind window), replays, no video.
    // "<-1 none | 0 paused | 1 playing>|<seconds>|<length in seconds, -1 if unknown or live>|<speed>|<1 if live>"
    private val STATE_SCRIPT = """
        (function () {
          var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
          if (!v) return '-1|0|-1|1|0';
          var m = location.search.match(/[?&]v=([^&]+)/), cur = m ? m[1] : '';
          var p = document.getElementById('movie_player');
          // A live stream has no fixed length. Ask YouTube's player (a live stream with a rewind window reports a finite,
          // growing duration that would otherwise look like a normal video).
          var live = !isFinite(v.duration);
          try { var d = p && p.getVideoData && p.getVideoData(); if (d && d.isLive) live = true; } catch (e) {}
          try { var r = p && p.getPlayerResponse && p.getPlayerResponse(); if (r && r.videoDetails && r.videoDetails.isLive) live = true; } catch (e) {}
          try {
            var ip = window.ytInitialPlayerResponse;
            if (ip && ip.videoDetails && ip.videoDetails.videoId === cur && ip.videoDetails.isLive) live = true;
          } catch (e) {}
          var length = (!live && isFinite(v.duration) && v.duration > 0) ? v.duration : -1;
          var rate = v.playbackRate > 0 ? v.playbackRate : 1;
          return (v.paused ? 0 : 1) + '|' + v.currentTime + '|' + length + '|' + rate + '|' + (live ? 1 : 0);
        })();
    """.trimIndent()

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

    private var webViewRef: WeakReference<WebView>? = null

    fun attach(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun detach(webView: WebView) {
        if (webViewRef?.get() === webView) webViewRef = null
    }

    fun play() = run("var v=$VIDEO;if(v)v.play();")

    fun pause() = run("var v=$VIDEO;if(v)v.pause();")

    fun toggle() = run("var v=$VIDEO;if(v){if(v.paused)v.play();else v.pause();}")

    /**
     * Next video. Inside a playlist or mix the page script opens the item after the current one in the list itself
     * (YouTube's own shuffle does not always move on); otherwise YouTube's own next button, a jump to the end of the
     * video inside a list, or the first suggested video (the autoplay pick). See wardtube.js.
     */
    fun next() = run("window.__wardtube&&window.__wardtube.skip(1);")

    /**
     * Previous video: restarts the current one if it played for more than 5 seconds. Otherwise the item before it in
     * the playlist, else the video watched before this one (browser history), else a restart. See wardtube.js.
     */
    fun previous() = run("window.__wardtube&&window.__wardtube.skip(-1);")

    /** Id of the video on the current page (watch, Shorts or youtu.be link), or "" if there is none. */
    fun videoId(): String {
        val url = webViewRef?.get()?.url ?: return ""
        val uri = Uri.parse(url)
        val id = uri.getQueryParameter("v")
            ?: uri.pathSegments.takeIf { it.size >= 2 && it[0] in setOf("shorts", "embed", "live") }?.get(1)
            ?: if (uri.host == "youtu.be") uri.pathSegments.firstOrNull() else null
        return if (id != null && ID_PATTERN.matches(id)) id else ""
    }

    /** Title of the current page without the " - YouTube" suffix, or "" if there is no page. */
    fun title(): String = webViewRef?.get()?.title.orEmpty().removeSuffix(" - YouTube")

    /** Reports the player state, or null if the WebView no longer exists. */
    fun queryState(callback: (State?) -> Unit) {
        val webView = webViewRef?.get()
        if (webView == null) {
            callback(null)
            return
        }
        webView.post {
            webView.evaluateJavascript(STATE_SCRIPT) { value ->
                val parts = value?.trim('"')?.split('|').orEmpty()
                val code = parts.getOrNull(0)?.toIntOrNull() ?: -1
                val position = parts.getOrNull(1)?.toDoubleOrNull()
                val duration = parts.getOrNull(2)?.toDoubleOrNull()
                val rate = parts.getOrNull(3)?.toFloatOrNull()?.takeIf { it > 0f && it.isFinite() } ?: 1f
                val live = parts.getOrNull(4) == "1"
                callback(
                    State(
                        hasVideo = code >= 0,
                        playing = code == 1,
                        title = title(),
                        videoId = videoId(),
                        positionMs = if (code >= 0 && position != null && position >= 0) (position * 1000).toLong() else -1L,
                        durationMs = if (code >= 0 && !live && duration != null && duration > 0) (duration * 1000).toLong() else -1L,
                        rate = rate,
                        isLive = live
                    )
                )
            }
        }
    }

    /** Jumps to [positionMs] in the current video (the notification's seek bar). Does nothing for live streams. */
    fun seekTo(positionMs: Long) =
        run("var v=$VIDEO;if(v&&isFinite(v.duration)&&v.duration>0)v.currentTime=${positionMs.coerceAtLeast(0) / 1000.0};")

    private fun run(script: String) {
        val webView = webViewRef?.get() ?: return
        webView.post { webView.evaluateJavascript(script, null) }
    }
}
