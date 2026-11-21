// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mwmods.wardtube.web.WebMedia

/**
 * Owns the built-in player. Because the player lives here and not in the screen, music goes on with the screen off or
 * the app closed, with the media notification, lock-screen controls and headset buttons handled by Media3.
 */
@OptIn(UnstableApi::class)
class MediaPlayerService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // Default sources: content:// and file:// (the Library), plus http for anything else.
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // The queue goes round and round: what played goes to the bottom, and a shuffle makes a new random order each round.
        player.addListener(QueueCycle(player))
        // One sound at a time, the other direction (see pauseForOtherMedia): when the Library's player starts to play, the video
        // that plays on a YouTube or browser page pauses instead of going on under it.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) WebMedia.pauseAll()
            }
        })
        playerRef = player
        // Tapping the media notification (or the lock screen's card) brings the player screen back: PlayerActivity is
        // single-task, so the screen that is already open is reused. With no session activity the tap did nothing.
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The notification and the lock screen show the video's picture (or the song's cover): see CoverBitmapLoader.
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setBitmapLoader(CoverBitmapLoader(this))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the player unless it is playing: a playing song keeps going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        playerRef = null
        session?.run {
            player.release()
            release()
        }
        session = null
        running = false
        super.onDestroy()
    }

    companion object {
        /**
         * True while the service (and so the player) exists. The Library's mini player only connects when it does, so
         * opening the Library never starts a player just to find it has nothing to show.
         */
        @Volatile
        var running = false
            private set

        /** The player while the service exists (for [pauseForOtherMedia]). */
        @Volatile
        private var playerRef: ExoPlayer? = null

        /**
         * Something else in the app started to play with sound (a YouTube video, a video of a page in the browser): the built-in
         * player pauses instead of playing over it. Can be called from any thread; does nothing when there is no player or it is
         * not playing. The play button (screen, notification, headset) starts it again.
         */
        fun pauseForOtherMedia() {
            val player = playerRef ?: return
            Handler(Looper.getMainLooper()).post {
                if (playerRef === player && player.playWhenReady) player.pause()
            }
        }
    }
}
