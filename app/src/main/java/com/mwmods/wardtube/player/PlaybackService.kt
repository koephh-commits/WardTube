// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.mwmods.wardtube.MainActivity
import com.mwmods.wardtube.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps the process alive while a video plays with the screen off or the app in the background,
 * and shows a media notification with play/pause. Started from MainActivity while a video page is open
 * (in the foreground, as newer Android versions require), stopped when the page is left.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private val noisyReceiver = BecomingNoisyReceiver()
    private var wakeLock: PowerManager.WakeLock? = null

    private var foregroundStarted = false
    private var playing = false
    private var title = ""
    private var videoId = ""
    private var artwork: Bitmap? = null
    private var artworkId = ""
    private var destroyed = false
    private val artworkLoader = Executors.newSingleThreadExecutor()
    private var pausedSince = 0L
    private var missedPolls = 0

    // Progress shown by the system's seek bar. durationMs = -1 means unknown (no video yet, or a live stream).
    private var positionMs = 0L
    private var positionStamp = 0L
    private var durationMs = -1L
    private var rate = 1f

    // What the system's bar was last told (position, when, speed): it moves on its own from there, so this is what
    // the real position has to be compared with, not the previous poll.
    private var sessionPos = 0L
    private var sessionStamp = 0L
    private var sessionSpeed = 0.0

    private val poll = object : Runnable {
        override fun run() {
            PlayerController.queryState { state ->
                // The WebView's answer can arrive after onDestroy(): do nothing then, and do not poll again.
                if (destroyed) return@queryState
                if (state == null) {
                    stopSelf()
                    return@queryState
                }
                // The video element appears a moment after the page changes, so allow a few misses.
                if (!state.hasVideo) {
                    if (++missedPolls >= MAX_MISSED_POLLS) {
                        stopSelf()
                        return@queryState
                    }
                    handler.postDelayed(this, POLL_MS)
                    return@queryState
                }
                missedPolls = 0

                // Compare the real position with where the system's bar is by now: it moves on its own between
                // polls, so only a seek, a stall, a speed change or a new length needs a fresh update.
                val now = SystemClock.elapsedRealtime()
                val jumped = state.durationMs > 0 && state.positionMs >= 0 &&
                    kotlin.math.abs(state.positionMs - shownAt(now)) > JUMP_TOLERANCE_MS
                val lengthChanged = state.durationMs != durationMs
                val rateChanged = state.playing && kotlin.math.abs(state.rate - rate) > 0.01f
                if (state.positionMs >= 0) {
                    positionMs = state.positionMs
                    positionStamp = now
                }
                durationMs = state.durationMs
                rate = state.rate

                if (state.playing != playing || state.title != title || state.videoId != videoId) {
                    playing = state.playing
                    title = state.title
                    videoId = state.videoId
                    loadArtwork(videoId)
                    render()
                } else if (jumped || lengthChanged || rateChanged) {
                    updateSession() // the notification itself does not need to be rebuilt
                }
                if (playing) {
                    pausedSince = 0L
                    holdWakeLock(true) // renew the timeout
                } else {
                    if (pausedSince == 0L) pausedSince = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - pausedSince > MAX_PAUSED_MS) {
                        SleepTimer.cancel() // paused for a long time: nothing left for the timer to stop
                        stopSelf()
                        return@queryState
                    }
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "WardTube").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlayerController.play()
                    setPlaying(true)
                }

                override fun onPause() {
                    PlayerController.pause()
                    setPlaying(false)
                }

                override fun onSeekTo(pos: Long) {
                    if (durationMs <= 0) return
                    val target = pos.coerceIn(0L, durationMs)
                    PlayerController.seekTo(target)
                    positionMs = target
                    positionStamp = SystemClock.elapsedRealtime()
                    updateSession()
                }

                override fun onSkipToNext() = skip(next = true)

                override fun onSkipToPrevious() = skip(next = false)
            })
            isActive = true
        }
        noisyReceiver.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                PlayerController.toggle()
                setPlaying(!playing)
            }
            ACTION_NEXT -> skip(next = true)
            ACTION_PREV -> skip(next = false)
            ACTION_STOP -> {
                PlayerController.pause()
                SleepTimer.cancel() // the user stopped playback: the timer is no longer needed
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (!foregroundStarted) {
                title = PlayerController.title()
                videoId = PlayerController.videoId()
                loadArtwork(videoId)
                playing = true
                handler.removeCallbacks(poll)
                handler.post(poll)
            }
        }
        render()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        artworkLoader.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        noisyReceiver.unregister(this)
        setWakeLock(false)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    /** Next / previous video, from the notification, a headset or a Bluetooth device. */
    private fun skip(next: Boolean) {
        if (next) PlayerController.next() else PlayerController.previous()
        // Until the poll reports the new video, show it at the start rather than at the old position.
        positionMs = 0L
        positionStamp = SystemClock.elapsedRealtime()
        // The new page loads a moment later; make sure it plays, then let the poll pick up the new title and cover.
        handler.postDelayed({ if (!destroyed) PlayerController.play() }, 1500)
        setPlaying(true)
    }

    /** Downloads the video's cover (thumbnail) in the background and shows it in the notification. */
    private fun loadArtwork(id: String) {
        if (destroyed || id.isEmpty() || id == artworkId) return
        artworkId = id
        artwork = null
        artworkLoader.execute {
            val bitmap = try {
                val connection = URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (!destroyed && bitmap != null && artworkId == id) {
                    artwork = bitmap
                    render()
                }
            }
        }
    }

    private fun squareCrop(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        return Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    }

    private fun setPlaying(value: Boolean) {
        // Fix the position at this moment first, so the bar does not jump back to the last poll's value.
        val now = SystemClock.elapsedRealtime()
        positionMs = positionAt(now)
        positionStamp = now
        playing = value
        render()
    }

    /** Where the video is at [now]: the last reported position plus the time that passed while it was playing. */
    private fun positionAt(now: Long): Long {
        if (!playing) return positionMs
        val estimate = positionMs + ((now - positionStamp) * rate).toLong()
        return if (durationMs > 0) minOf(estimate, durationMs) else estimate
    }

    /** Where the system's seek bar is at [now], according to the last state it was given. */
    private fun shownAt(now: Long): Long {
        val estimate = sessionPos + ((now - sessionStamp) * sessionSpeed).toLong()
        return if (durationMs > 0) estimate.coerceIn(0L, durationMs) else estimate
    }

    /** Pushes the title, cover, play state and progress to the media session (what the system's seek bar reads). */
    private fun updateSession() {
        if (destroyed) return
        val canSeek = durationMs > 0
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
        if (canSeek) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        session.setMetadata(metadata.build())

        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (canSeek) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        val now = SystemClock.elapsedRealtime()
        val position = positionAt(now)
        val speed = if (playing) rate else 0f
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    if (canSeek) position else PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    speed
                )
                .build()
        )
        sessionPos = position
        sessionStamp = now
        sessionSpeed = speed.toDouble()
    }

    /** Updates the media session, wake lock and notification (starting foreground on first call). */
    private fun render() {
        if (destroyed) return
        updateSession()
        holdWakeLock(playing)

        val notification = buildNotification()
        if (!foregroundStarted) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                foregroundStarted = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the foreground service", e)
                stopSelf()
            }
        } else {
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones_white_24dp)
            .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
            .setContentTitle(title.ifEmpty { getString(R.string.app_name) })
            .setContentText(getString(R.string.app_name))
            .setLargeIcon(artwork?.let { squareCrop(it) })
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous_white_24dp, getString(R.string.previousVideo), servicePendingIntent(ACTION_PREV, 3))
            .addAction(
                if (playing) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_white_24dp,
                getString(if (playing) R.string.pause else R.string.play),
                servicePendingIntent(ACTION_TOGGLE, 1)
            )
            .addAction(R.drawable.ic_skip_next_white_24dp, getString(R.string.nextVideo), servicePendingIntent(ACTION_NEXT, 4))
            .addAction(R.drawable.ic_close_white_24dp, getString(R.string.stop), servicePendingIntent(ACTION_STOP, 2))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private val releaseWakeLock = Runnable { setWakeLock(false) }

    /**
     * Keeps the phone awake while the video plays. When playback stops the lock is held a few seconds longer: a video
     * that ended inside a list is followed by the next one a few seconds later (see wardtube.js), which a sleeping
     * phone with the screen off would only do much later.
     */
    private fun holdWakeLock(playing: Boolean) {
        handler.removeCallbacks(releaseWakeLock)
        if (playing) setWakeLock(true)
        else if (wakeLock != null) handler.postDelayed(releaseWakeLock, WAKE_LOCK_GRACE_MS)
    }

    private fun setWakeLock(held: Boolean) {
        if (held) {
            // Not reference counted, so every acquire() restarts the timeout: the poll renews it while the
            // video plays (long sleep timers), and it still expires on its own if the service is ever stuck.
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wardtube:playback")
                .apply { setReferenceCounted(false) }
            wakeLock = lock
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notificationChannel), NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1337
        private const val ACTION_TOGGLE = "com.mwmods.wardtube.action.TOGGLE"
        private const val ACTION_STOP = "com.mwmods.wardtube.action.STOP"
        private const val ACTION_NEXT = "com.mwmods.wardtube.action.NEXT"
        private const val ACTION_PREV = "com.mwmods.wardtube.action.PREV"
        private const val POLL_MS = 3_000L
        private const val MAX_MISSED_POLLS = 6
        private const val MAX_PAUSED_MS = 10 * 60 * 1000L
        private const val JUMP_TOLERANCE_MS = 2_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_GRACE_MS = 10_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                // Android refuses foreground services started from the background.
                Log.w(TAG, "Could not start the playback service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
