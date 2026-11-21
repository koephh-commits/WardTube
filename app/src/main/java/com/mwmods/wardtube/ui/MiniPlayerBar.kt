// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mwmods.wardtube.R
import com.mwmods.wardtube.media.MediaPlayerService
import com.mwmods.wardtube.media.PlayerLauncher
import java.util.Locale

/**
 * The mini player at the bottom of the Library (`view_mini_player.xml`): the song that the built-in player has now, with
 * its cover, its name, how far it is, and previous / play-pause / next / close. A tap on the bar opens the full player.
 *
 * It does not own anything: the player lives in [MediaPlayerService], and this bar is one more controller of it (the
 * full player screen is another), so both always show the same song and the same state. The bar only connects while
 * the Library is on screen ([start] / [stop]), and only when the service is already there, so opening the Library never
 * starts a player. Only music shows here: a video has its own screen and is paused when that screen is left.
 */
class MiniPlayerBar(private val activity: ComponentActivity, private val bar: View) {

    private val progress = bar.findViewById<ProgressBar>(R.id.miniProgress)
    private val cover = bar.findViewById<ImageView>(R.id.miniCover)
    private val coverIcon = bar.findViewById<View>(R.id.miniCoverIcon)
    private val title = bar.findViewById<TextView>(R.id.miniTitle)
    private val time = bar.findViewById<TextView>(R.id.miniTime)
    private val previous = bar.findViewById<ImageButton>(R.id.miniPrev)
    private val playPause = bar.findViewById<ImageButton>(R.id.miniPlayPause)
    private val next = bar.findViewById<ImageButton>(R.id.miniNext)
    private val close = bar.findViewById<ImageButton>(R.id.miniClose)

    private val handler = Handler(Looper.getMainLooper())
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** The song whose cover was asked for last, so a refresh for the same song does not load it again. */
    private var coverUri: Uri? = null

    /** What the play / pause button shows now, so its icon is only changed when it differs. */
    private var showingPause: Boolean? = null

    /** Set while the Library is picking files: the bar steps aside for the share / delete bar. */
    var suppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller
            if (c == null || bar.visibility != View.VISIBLE) return
            updateProgress(c)
            if (isPlayingNow(c)) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        bar.setOnClickListener { PlayerLauncher.openCurrent(activity) }
        previous.setOnClickListener { controller?.seekToPrevious() }
        next.setOnClickListener { controller?.seekToNext() }
        playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (isPlayingNow(c)) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.play()
            }
        }
        // Closing stops the music and empties the queue; the bar goes away and the notification with it.
        close.setOnClickListener {
            controller?.run {
                stop()
                clearMediaItems()
            }
            refresh()
        }
    }

    /** Library on screen: connect to the player if there is one. */
    fun start() {
        if (future != null) return
        if (!MediaPlayerService.running) {
            refresh()
            return
        }
        val token = SessionToken(activity, ComponentName(activity, MediaPlayerService::class.java))
        val f = MediaController.Builder(activity, token).buildAsync()
        future = f
        f.addListener({
            // Stopped before the connection was made: the controller is released by stop().
            if (future !== f) return@addListener
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
        }, ContextCompat.getMainExecutor(activity))
    }

    /** Library left (the player screen opens over it, or the app goes to the background): let go of the player. */
    fun stop() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        coverUri = null
        showingPause = null
    }

    // ---- What the bar shows ----

    /** Music is loaded: a song in the queue that is not a picture and did not fail. */
    private fun hasMusic(c: MediaController): Boolean =
        c.mediaItemCount > 0 &&
            c.playbackState != Player.STATE_IDLE &&
            !c.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)

    private fun isPlayingNow(c: MediaController): Boolean =
        c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE

    private fun refresh() {
        val c = controller
        if (c == null || suppressed || !hasMusic(c)) {
            handler.removeCallbacks(ticker)
            bar.visibility = View.GONE
            return
        }
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            ViewCompat.requestApplyInsets(bar) // a bar that was hidden asks again for the navigation bar's height
        }

        title.text = c.mediaMetadata.title?.toString().orEmpty()
        showCover(PlayerLauncher.uriOf(c.currentMediaItem))

        val playing = isPlayingNow(c)
        if (showingPause != playing) {
            showingPause = playing
            playPause.setImageResource(if (playing) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_white_24dp)
            playPause.contentDescription = activity.getString(if (playing) R.string.pause else R.string.play)
        }
        // Previous restarts the song when it is already a few seconds in, so it always works; next only when there is one.
        val hasNext = c.hasNextMediaItem()
        next.isEnabled = hasNext
        next.alpha = if (hasNext) 1f else 0.35f

        updateProgress(c)
        handler.removeCallbacks(ticker)
        if (playing) handler.postDelayed(ticker, TICK_MS)
    }

    private fun showCover(uri: Uri?) {
        if (uri == coverUri) return
        coverUri = uri
        if (uri == null) {
            ThumbnailLoader.cancel(cover)
            cover.visibility = View.GONE
            coverIcon.visibility = View.VISIBLE
            return
        }
        ThumbnailLoader.loadCover(activity, uri, cover, ThumbnailLoader.COVER_SIDE) { has ->
            cover.visibility = if (has) View.VISIBLE else View.GONE
            coverIcon.visibility = if (has) View.GONE else View.VISIBLE
        }
    }

    private fun updateProgress(c: MediaController) {
        val duration = c.duration
        val position = c.currentPosition.coerceAtLeast(0L)
        val known = duration != C.TIME_UNSET && duration > 0
        progress.progress = if (known) ((position * progress.max) / duration).toInt().coerceIn(0, progress.max) else 0
        time.text = if (known) "${format(position)} / ${format(duration)}" else format(position)
    }

    private fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private companion object {
        const val TICK_MS = 500L
    }
}
