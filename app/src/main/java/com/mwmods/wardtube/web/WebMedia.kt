// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import java.lang.ref.WeakReference

/**
 * The other direction of "one sound at a time" (the first one is [MediaPlayerService.pauseForOtherMedia]: a video of a page starts,
 * the built-in player of the Library pauses). Here the Library's player starts to play, and the video that plays on a page
 * (YouTube's player, a Short or a video of the page being browsed, a video of the browser screen or of a frame inside it) pauses
 * instead of going on under it.
 *
 * Each screen that owns WebViews is a [Host]: it registers itself while it exists and pauses what its pages play when asked. The
 * registry holds the screens weakly, and everything here runs on the main thread (the player's listener, onCreate / onDestroy).
 */
object WebMedia {

    /** A screen with WebViews whose videos can be paused. */
    interface Host {
        /** Pause every video / audio of the screen's pages that plays with sound. Called on the main thread. */
        fun pauseWebMedia()
    }

    private val hosts = ArrayList<WeakReference<Host>>()

    fun register(host: Host) {
        hosts.removeAll { it.get() == null || it.get() === host }
        hosts.add(WeakReference(host))
    }

    fun unregister(host: Host) {
        hosts.removeAll { it.get() == null || it.get() === host }
    }

    /** The Library's player started to play with sound: asks every registered screen to pause what its pages play. */
    fun pauseAll() {
        hosts.toList().forEach { it.get()?.pauseWebMedia() }
    }

    /** The message a page sends to its own frames (and a frame to its frames) to ask them to pause; see `MediaSniffer.SCRIPT`. */
    const val PAUSE_MESSAGE = "__wardtube_pause__"

    /**
     * Run in a page's main frame: pauses the `<video>` / `<audio>` that play with sound (a muted preview or a silent loop is left
     * alone, as it is when a video starts and pauses the Library's player), and asks each iframe to do the same. A frame
     * answers only when the script of the browser screen is in it (`MediaSniffer.FRAME_SCRIPT`), and only to its own parent.
     */
    val PAUSE_SCRIPT = """
        (function () {
          try {
            var media = document.querySelectorAll('video,audio');
            for (var i = 0; i < media.length; i++) {
              try { if (!media[i].paused && !media[i].muted && media[i].volume > 0) media[i].pause(); } catch (e) {}
            }
          } catch (e) {}
          try {
            var frames = document.querySelectorAll('iframe');
            for (var j = 0; j < frames.length; j++) {
              try { frames[j].contentWindow.postMessage('$PAUSE_MESSAGE', '*'); } catch (e) {}
            }
          } catch (e) {}
        })();
    """.trimIndent()
}
