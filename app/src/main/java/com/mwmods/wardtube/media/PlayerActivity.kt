// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.ui.ThumbnailLoader
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The built-in video and music player: a screen over the player that lives in [MediaPlayerService].
 * It is opened from the Library with a queue of files ([PlayerLauncher.openFiles]), each with the lyrics / subtitle
 * files the Library found for it in the `كلمات` folder ([LyricsFinder]).
 *
 * Picture in picture: a video can shrink into the system's small floating window with the button in the title bar, and
 * (when the "Picture in picture" switch of the three-dots menu is on) by itself when the app is left while it plays. The
 * window has the picture's own shape and the previous / play-pause / next buttons.
 *
 * Touch gestures ([PlayerGestures]): a double tap moves 10 seconds (right half forward, left half back; quick taps after it
 * add more), for a video and for a song; a sideways swipe on a video moves the position and jumps there when the finger leaves.
 *
 * Queue ([QueueSheet]): the queue button of the title bar opens the list that is playing, to jump to an item, put it in
 * another place or remove it. A button of the sheet makes the whole Library (videos and songs) the queue, and brings the
 * previous queue back ([LibraryQueue]).
 *
 * Audio only: for a video, a button of the title bar turns the picture off (the video renderer is disabled in the player, so
 * nothing is decoded for the picture). The file then counts as music everywhere it is told apart from video ([updateKind], the
 * Library's mini player): it shows the music panel, keeps playing when the screen is left, and the button turns the picture
 * back on.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var audioPanel: LinearLayout
    private lateinit var audioIcon: View
    private lateinit var lyricsView: LyricsView
    private lateinit var loading: View
    private lateinit var errorView: TextView
    private lateinit var pipButton: View
    private lateinit var queueButton: View
    private lateinit var audioOnlyButton: ImageButton

    /** The "rotate screen" button in the controller's row of buttons; null if the controller has no such row. */
    private var rotateButton: ImageButton? = null
    private lateinit var seekFeedback: TextView
    private lateinit var swipePreview: TextView
    private lateinit var gestures: PlayerGestures

    private val prefs by lazy { Prefs(this) }

    /** The list that is playing, shown as a sheet over the player. */
    private val queueSheet: QueueSheet by lazy { QueueSheet(this, libraryQueue) }

    /** The "whole Library as the queue" mode, switched by a button of the queue sheet. */
    private val libraryQueue: LibraryQueue by lazy { LibraryQueue(this, { controller }, this::itemWithWords) { queueSheet.onLibraryChanged() } }

    /** The phone has the small floating window (Android 8+ and the feature switched on by its maker). */
    private var pipSupported = false

    /** Android 12+ may enter the window by itself when the app is left: true only while a video plays and the switch is on. */
    private var pipArmed = false

    /** What was last handed to setPictureInPictureParams, so it is only sent again when it changes. */
    private var pipParamsKey = ""

    private var pipReceiverOn = false

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val whenReady = ArrayList<(MediaController) -> Unit>()

    /** The queue is being prepared (lyrics read and converted); a newer one replaces it. */
    private var prepareJob: Job? = null

    /** True while the file in play has sound but no picture: it is music, so the panel with the title and the words shows. */
    private var isMusic = false

    /** The song carries a picture: the player view draws it behind, so the note icon is not needed on top of it. */
    private var hasArtwork = false

    /**
     * A video that plays as sound only (the headphones button) has no picture of its own on screen: one of its frames is
     * drawn as the cover then, as a song's own picture is, instead of the big note. True while that frame is in the player view.
     */
    private var frameCover = false

    /** The file ([MediaItem.mediaId]) whose frame was asked for last, so that it is asked for once and not at every track change. */
    private var frameCoverKey = ""
    private var frameCoverJob: Job? = null

    /** The line of the words that is being sung now (what the subtitle track says at this moment). */
    private var cueText = ""

    /** Which song and which lyrics file the lyrics view shows now ("mediaId|file name"), so it is filled again only when that changes. */
    private var lyricsKey = ""

    /** The room the status bar / camera cut-out takes at the top: the music panel starts below the title bar. */
    private var panelTopInset = 0

    /** Gives the lyrics view the song's position a few times a second, so the sung line is highlighted and followed. */
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            controller?.let { lyricsView.setPosition(it.currentPosition) }
            tickHandler.postDelayed(this, LYRICS_TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            updateKind(tracks)
            updateKeepAwake()
        }
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = updateAudioOnlyButton()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateTitle(mediaMetadata)

        // The little window follows the player: the picture's shape, and the play / pause button.
        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
            updateKeepAwake()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePipParams()
            updateKeepAwake()
        }

        override fun onCues(cueGroup: CueGroup) {
            cueText = cueGroup.cues.mapNotNull { it.text?.toString()?.let { t -> LyricsText.tidy(t) }?.takeIf { line -> line.isNotEmpty() } }
                .joinToString("\n")
            // Only when the whole file's words are not known: then the view shows just this line.
            if (lyricsView.isSingle) lyricsView.setSingleLine(cueText)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The next song starts with no line: its own words come with its own subtitle track (also when the same song repeats).
            cueText = ""
            lyricsKey = ""
            controller?.let { refreshLyrics(it.currentTracks) }
            updatePipParams() // the next button depends on whether there is a next one
        }

        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING && controller?.playWhenReady == true) View.VISIBLE else View.GONE
            updatePipParams()
            updateKeepAwake()
        }

        override fun onPlayerError(error: PlaybackException) {
            // The real reason (e.g. ERROR_CODE_IO_BAD_HTTP_STATUS + "Response code: 403") is shown to find the cause.
            val detail = listOfNotNull(error.errorCodeName, error.cause?.message?.take(120)).joinToString("\n")
            android.util.Log.e("WardTubePlayer", "playback failed", error)
            showError(getString(R.string.playerError) + "\n\n" + detail)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.playerTopBar)
        titleView = findViewById(R.id.playerTitle)
        audioPanel = findViewById(R.id.playerAudioPanel)
        audioIcon = findViewById(R.id.playerAudioIcon)
        lyricsView = findViewById(R.id.playerLyrics)
        seekFeedback = findViewById(R.id.playerSeekFeedback)
        swipePreview = findViewById(R.id.playerSwipePreview)
        gestures = PlayerGestures(this, gestureHost)
        loading = findViewById(R.id.playerLoading)
        errorView = findViewById(R.id.playerError)
        pipButton = findViewById(R.id.playerPip)
        queueButton = findViewById(R.id.playerQueue)
        audioOnlyButton = findViewById(R.id.playerAudioOnly)

        findViewById<ImageButton>(R.id.playerBack).setOnClickListener { finish() }
        queueButton.setOnClickListener { controller?.let { queueSheet.show(it) } }
        audioOnlyButton.setOnClickListener { toggleAudioOnly() }
        pipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        pipButton.setOnClickListener {
            if (!enterPip()) Toast.makeText(this, R.string.playerPipUnavailable, Toast.LENGTH_LONG).show()
        }
        // The window's source place and shape change with the screen (rotation, split screen): keep the hint current.
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!isInPictureInPictureMode) updatePipParams() }
        errorView.setOnClickListener { handle(intent) }
        // Every touch of the picture / music screen that no control takes (a button or the time bar keeps its own): double tap,
        // quick taps, swipe, and the single tap that shows or hides the controls. Not in the little window.
        playerView.setOnTouchListener { _, event -> !isInPictureInPictureMode && gestures.onTouch(event) }

        playerView.setShowShuffleButton(true)
        playerView.setShowSubtitleButton(true)
        playerView.setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            topBar.visibility = if (isInPictureInPictureMode) View.GONE else v
        })
        addRotateButton()

        // The title bar starts below the status bar / camera cut-out.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            v.setPadding(v.paddingLeft, top + (4 * resources.displayMetrics.density).toInt(), v.paddingRight, v.paddingBottom)
            panelTopInset = top
            layoutAudioPanel()
            insets
        }
        applySystemBars()
        connect()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    // ---- Connecting to the player in the service ----

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, MediaPlayerService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener showError(getString(R.string.playerError))
            controller = c
            c.addListener(listener)
            playerView.player = c
            updateKind(c.currentTracks)
            updateKeepAwake()
            updateTitle(c.mediaMetadata)
            // Opened again while a song is playing (from the notification): its line shows at once, not at the next one.
            listener.onCues(c.currentCues)
            whenReady.forEach { it(c) }
            whenReady.clear()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: whenReady.add(block)
    }

    // ---- What to play ----

    private fun handle(intent: Intent) {
        errorView.visibility = View.GONE
        when {
            intent.hasExtra(PlayerLauncher.EXTRA_URIS) -> playFiles(intent)
            // Opened again from the notification: nothing new to start, the player is already there.
        }
    }

    private fun playFiles(intent: Intent) {
        val uris = intent.getStringArrayListExtra(PlayerLauncher.EXTRA_URIS).orEmpty()
        val titles = intent.getStringArrayListExtra(PlayerLauncher.EXTRA_TITLES).orEmpty()
        val subs = intent.getStringArrayListExtra(PlayerLauncher.EXTRA_SUBS).orEmpty()
        val startIndex = intent.getIntExtra(PlayerLauncher.EXTRA_START, 0)
        // The lyrics are files: they are read (and an LRC converted) off the main thread, then the queue starts.
        prepareJob?.cancel()
        LyricsStore.clear()
        // A queue that is opened new ends the "whole Library" mode of the one it replaces.
        libraryQueue.reset()
        prepareJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapIndexed { i, u -> itemWithWords(u, titles.getOrNull(i).orEmpty(), subs.getOrNull(i).orEmpty()) }
            }
            start(items, startIndex)
        }
    }

    /**
     * One item of a queue: the file at [uri], its [title], and its lyrics files as [PlayerLauncher.encodeSubs] writes them
     * ([encodedSubs]). Reads the lyrics files, so it is called off the main thread.
     */
    private fun itemWithWords(uri: String, title: String, encodedSubs: String): MediaItem {
        val subList = encodedSubs.split(PlayerLauncher.SUB_SEPARATOR).filter { it.isNotBlank() }
            .mapNotNull {
                subtitleSource(
                    uri,
                    Uri.parse(it.substringBefore(PlayerLauncher.NAME_SEPARATOR)),
                    it.substringAfter(PlayerLauncher.NAME_SEPARATOR, ""),
                )
            }
        return item(Uri.parse(uri), title, subList)
    }

    /**
     * The lyrics file as the player can read it: a SubRip / WebVTT file is used as it is; an LRC file (what the lyrics
     * download makes for a song) is converted to SubRip ([LrcToSrt]) into the app's own folder and that copy is used.
     * The words are also parsed into rows for the lyrics view and kept in [LyricsStore] under the song's [mediaId] and the
     * file's name as the subtitle track will carry it. Null when the file cannot be read or has no timed line: the song then
     * plays without words, never fails.
     */
    private fun subtitleSource(mediaId: String, uri: Uri, name: String): Pair<Uri, String>? {
        val isLrc = name.endsWith(".lrc", ignoreCase = true)
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (!isLrc) {
            if (text != null) LyricsStore.put(mediaId, name, runCatching { LyricsParser.parse(name, text) }.getOrDefault(emptyList()))
            return uri to name
        }
        return runCatching {
            val srt = text?.let { LrcToSrt.convert(it) }
            if (text == null || srt == null) {
                null
            } else {
                val dir = File(filesDir, LYRICS_DIR).apply { mkdirs() }
                val file = File(dir, convertedName(uri))
                // Only when it changed: the copy of a song that is playing now is not rewritten under the player.
                if (!file.exists() || file.readText(Charsets.UTF_8) != srt) file.writeText(srt, Charsets.UTF_8)
                val trackName = name.dropLast(".lrc".length) + ".srt"
                LyricsStore.put(mediaId, trackName, runCatching { LyricsParser.parse(name, text) }.getOrDefault(emptyList()))
                Uri.fromFile(file) to trackName
            }
        }.getOrNull()
    }

    /** One stable file name per lyrics file (a hash of its address), so playing a song again reuses its own copy. */
    private fun convertedName(uri: Uri): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { String.format(Locale.ROOT, "%02x", it) } + ".srt"
    }

    private fun item(
        uri: Uri,
        title: String,
        subtitles: List<Pair<Uri, String>>,
    ): MediaItem {
        // The picture for the notification is made from the file when this item plays (CoverBitmapLoader): only the file's
        // address goes in here. It is not the artwork fields of the metadata, which also decide how this screen looks.
        val meta = MediaMetadata.Builder()
            .setTitle(title.ifBlank { uri.lastPathSegment })
            .setExtras(Bundle().apply { putString(CoverBitmapLoader.KEY_COVER, uri.toString()) })
            .build()
        // The Library lists the best lyrics file first: only that one is switched on, the others are in the subtitle menu.
        val subs = subtitles.mapIndexed { index, (s, name) ->
            MediaItem.SubtitleConfiguration.Builder(s)
                .setMimeType(if (name.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(languageOf(name))
                .setLabel(name)
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(meta)
            .setSubtitleConfigurations(subs)
            .build()
    }

    /** "Title.ar.srt" -> "ar". */
    private fun languageOf(fileName: String): String? {
        val parts = fileName.substringBeforeLast('.').split('.')
        val last = parts.lastOrNull().orEmpty()
        return if (parts.size > 1 && last.length in 2..3 && last.all { it.isLetter() }) last.lowercase(Locale.ROOT) else null
    }

    private fun start(items: List<MediaItem>, startIndex: Int) {
        if (items.isEmpty()) return
        withController { c ->
            // Audio only belongs to what was playing: a queue that is opened new starts with its picture.
            setAudioOnly(c, false)
            // A queue of several files plays round and round: when the last one ends the first comes again, and "Previous" on the
            // first goes back to the one that played before it. A single file does not repeat itself. "Repeat one" stays as chosen,
            // and the repeat button of the controls still changes it.
            if (items.size > 1 && c.repeatMode == Player.REPEAT_MODE_OFF) c.repeatMode = Player.REPEAT_MODE_ALL
            if (items.size == 1 && c.repeatMode == Player.REPEAT_MODE_ALL) c.repeatMode = Player.REPEAT_MODE_OFF
            c.setMediaItems(items, startIndex, C.TIME_UNSET)
            c.prepare()
            c.play()
        }
    }

    // ---- Look ----

    /**
     * No picture means music: the screen shows the music panel (the note, or the song's own picture) instead of a black
     * rectangle, and the words of the song (when the file has a lyrics track switched on). A video keeps the usual subtitles at
     * the bottom of the picture.
     */
    private fun updateKind(tracks: Tracks) {
        val hasVideo = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        val hasAudio = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
        val hasWords = tracks.isTypeSelected(C.TRACK_TYPE_TEXT)
        val music = !hasVideo && hasAudio
        isMusic = music
        audioPanel.visibility = if (music) View.VISIBLE else View.GONE
        // For music the line is drawn in the panel: under the always-visible controls the subtitle view would be covered.
        playerView.subtitleView?.visibility = if (music) View.GONE else View.VISIBLE
        lyricsView.visibility = if (music && hasWords) View.VISIBLE else View.GONE
        refreshLyrics(tracks)
        // A file that has a picture track but plays without it (Audio only) is a video heard as sound.
        updateFrameCover(videoAsSound = music && tracks.isTypeSupported(C.TRACK_TYPE_VIDEO))
        layoutAudioPanel()
        playerView.controllerShowTimeoutMs = if (music) 0 else 3000
        if (music) playerView.showController()
        // Only a picture can go into the little window; a song has nothing to show there.
        pipButton.visibility = if (pipSupported && hasVideo) View.VISIBLE else View.GONE
        // Turning the screen is for a picture; a song has the words, which are read the same either way up.
        rotateButton?.visibility = if (hasVideo) View.VISIBLE else View.GONE
        // Audio only is offered for any file that has a picture track, also while that picture is switched off.
        audioOnlyButton.visibility = if (tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) View.VISIBLE else View.GONE
        updateAudioOnlyButton()
        updatePipParams()
    }

    // ---- Rotating the screen ----

    /**
     * Puts a "rotate screen" button in the controller's own row of control buttons (the one with shuffle, repeat and the
     * subtitles), just before the subtitles button, so it sits with the other controls and not in the title bar. The row is
     * Media3's, found by its name: a controller without that row simply gets no button. Shown only for a picture ([updateKind]).
     */
    private fun addRotateButton() {
        val row = playerView.findViewById<ViewGroup>(resources.getIdentifier("exo_basic_controls", "id", packageName)) ?: return
        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()
        val pad = (12 * density).toInt()
        val button = ImageButton(this)
        val ripple = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            button.setBackgroundResource(ripple.resourceId)
        } else {
            button.setBackgroundColor(0)
        }
        button.setPadding(pad, pad, pad, pad)
        button.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        button.setImageResource(R.drawable.ic_screen_rotation_24dp)
        button.contentDescription = getString(R.string.playerRotate)
        button.visibility = View.GONE
        button.setOnClickListener { rotateScreen() }
        val subtitle = row.findViewById<View>(resources.getIdentifier("exo_subtitle", "id", packageName))
        val at = if (subtitle != null && subtitle.parent === row) row.indexOfChild(subtitle) else 0
        row.addView(button, at, ViewGroup.LayoutParams(size, size))
        rotateButton = button
    }

    /**
     * Turns the screen to the other way (sideways when upright, upright when sideways) and keeps it there, also when the phone is
     * moved, until the button is pressed again or the player screen is closed. Either end of the axis is allowed
     * (the sensor picks), and the phone's own rotation lock does not get in the way, since this is the person's own choice.
     */
    private fun rotateScreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        playerView.showController() // the controls stay in sight after the turn, with their usual time before they hide
    }

    // ---- Audio only ----

    /** The picture is switched off: the player has the video track type disabled. */
    private fun isAudioOnly(): Boolean =
        controller?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_VIDEO) == true

    /** Turns the picture off ([on] true: only the sound is played) or back on. The player tells back through the listener. */
    private fun setAudioOnly(c: MediaController, on: Boolean) {
        val current = c.trackSelectionParameters
        if (current.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == on) return
        c.trackSelectionParameters = current.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on).build()
    }

    private fun toggleAudioOnly() {
        val c = controller ?: return
        val on = !isAudioOnly()
        setAudioOnly(c, on)
        if (on) Toast.makeText(this, R.string.playerAudioOnlyOn, Toast.LENGTH_SHORT).show()
    }

    /** Headphones while the picture is on (tap: sound only); a video camera, tinted, while it is off (tap: bring the picture back). */
    private fun updateAudioOnlyButton() {
        val on = isAudioOnly()
        audioOnlyButton.setImageResource(if (on) R.drawable.ic_videocam_24dp else R.drawable.ic_menu_headphones)
        audioOnlyButton.imageTintList = if (on) ColorStateList.valueOf(ContextCompat.getColor(this, R.color.queueAccent)) else null
        audioOnlyButton.contentDescription = getString(if (on) R.string.playerShowVideo else R.string.playerAudioOnly)
    }

    /**
     * The music panel has two looks. Without words: the big note (or the song's own picture behind), centred. With words: the
     * lyrics view under the title bar, taking all the room down to the controls; the note is gone, and the song's picture (if it
     * has one) stays as it is behind the words' own card (see [LyricsView]). The song's name is not drawn on the picture: the
     * title bar above already says it.
     */
    private fun layoutAudioPanel() {
        val words = lyricsView.visibility == View.VISIBLE
        val density = resources.displayMetrics.density
        audioIcon.visibility = if (words || hasArtwork || frameCover) View.GONE else View.VISIBLE
        audioPanel.gravity = if (words) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        // Under the title bar at the top, above the controls at the bottom (the controls need a little more room than the old line did).
        audioPanel.setPadding(
            audioPanel.paddingLeft,
            if (words) panelTopInset + (60 * density).toInt() else 0,
            audioPanel.paddingRight,
            ((if (words) 116 else 96) * density).toInt(),
        )
    }

    /**
     * Fills the lyrics view for the song and lyrics file now selected: all the rows of the file ([LyricsStore], read when the
     * queue started), or, if they are not known, only the line the subtitle track says at the moment.
     */
    private fun refreshLyrics(tracks: Tracks) {
        if (lyricsView.visibility != View.VISIBLE) {
            lyricsKey = ""
            lyricsView.clear()
            stopTick()
            return
        }
        val mediaId = controller?.currentMediaItem?.mediaId
        val label = tracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected && it.length > 0 }
            ?.getTrackFormat(0)?.label
        val key = "$mediaId|$label"
        if (key != lyricsKey) {
            lyricsKey = key
            val rows = LyricsStore.get(mediaId, label)
            if (rows.isNullOrEmpty()) lyricsView.setSingleLine(cueText) else lyricsView.setLines(rows)
        }
        startTick()
    }

    /**
     * Audio only on a video: its own frame (a second in, made by [ThumbnailLoader] off the main thread, the same picture the
     * notification gets) becomes the player view's default artwork, which the view draws behind the music panel when the file
     * says no artwork of its own. Back to the picture, or to a song: the frame goes away. A file whose frame cannot be made keeps
     * the note.
     */
    private fun updateFrameCover(videoAsSound: Boolean) {
        val mediaId = controller?.currentMediaItem?.mediaId.orEmpty()
        if (!videoAsSound || mediaId.isEmpty()) {
            frameCoverJob?.cancel()
            frameCoverKey = ""
            clearFrameCover()
            return
        }
        if (mediaId == frameCoverKey) return
        // Another video: the frame of the one before must not stay while the new one is made.
        frameCoverJob?.cancel()
        clearFrameCover()
        frameCoverKey = mediaId
        frameCoverJob = lifecycleScope.launch {
            val frame = withContext(Dispatchers.IO) {
                // runCatching also catches an OutOfMemoryError: no cover is better than a crash.
                runCatching { ThumbnailLoader.decodeCover(applicationContext, Uri.parse(mediaId), COVER_SIDE) }.getOrNull()
            }
            if (frame == null || frameCoverKey != mediaId) return@launch
            playerView.setDefaultArtwork(BitmapDrawable(resources, frame))
            frameCover = true
            layoutAudioPanel()
        }
    }

    private fun clearFrameCover() {
        if (!frameCover) return
        frameCover = false
        playerView.setDefaultArtwork(null as Drawable?)
        layoutAudioPanel()
    }

    private fun startTick() {
        tickHandler.removeCallbacks(tick)
        tickHandler.post(tick)
    }

    private fun stopTick() = tickHandler.removeCallbacks(tick)

    private fun updateTitle(meta: MediaMetadata) {
        val t = meta.title?.toString().orEmpty()
        titleView.text = t
        hasArtwork = meta.artworkData != null || meta.artworkUri != null
        layoutAudioPanel()
    }

    private fun showError(message: String) {
        loading.visibility = View.GONE
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        layoutAudioPanel()
    }

    // ---- Touch gestures (see PlayerGestures) ----

    private val hideSeekFeedback = Runnable {
        seekFeedback.animate().alpha(0f).setDuration(FEEDBACK_FADE_MS)
            .withEndAction { seekFeedback.visibility = View.GONE }.start()
    }

    private val hideSwipePreview = Runnable {
        swipePreview.animate().alpha(0f).setDuration(FEEDBACK_FADE_MS)
            .withEndAction { swipePreview.visibility = View.GONE }.start()
    }

    private val gestureHost = object : PlayerGestures.Host {
        override fun surfaceWidth() = playerView.width

        // A file that is loaded and can be moved (not a live stream, not one that failed): otherwise the taps do what they did before.
        override fun canSeek(): Boolean {
            val c = controller ?: return false
            return c.currentMediaItem != null && c.isCurrentMediaItemSeekable && c.playbackState != Player.STATE_IDLE
        }

        // Only a picture is swiped: on a song the sideways drag has nothing to do (the words are a card, not a list).
        override fun canSwipeSeek() = hasPicture() && canSeek()

        override fun positionMs() = controller?.currentPosition ?: 0L

        override fun durationMs(): Long {
            val d = controller?.duration ?: return 0L
            return if (d == C.TIME_UNSET) 0L else d
        }

        override fun onTapSeek(deltaMs: Long, forward: Boolean, totalSeconds: Int) {
            val c = controller ?: return
            val duration = durationMs()
            val target = c.currentPosition + deltaMs
            moveTo(c, if (duration > 0L) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
            showSeekFeedback(forward, totalSeconds)
        }

        override fun onSwipeStart() {
            seekFeedback.removeCallbacks(hideSeekFeedback)
            seekFeedback.animate().cancel()
            seekFeedback.visibility = View.GONE
            swipePreview.removeCallbacks(hideSwipePreview)
            swipePreview.animate().cancel()
            swipePreview.alpha = 1f
            swipePreview.visibility = View.VISIBLE
        }

        override fun onSwipeMove(targetMs: Long, deltaMs: Long) {
            val sign = if (deltaMs < 0L) "\u2212" else "+"
            swipePreview.text = sign + clock(abs(deltaMs)) + "\n" + clock(targetMs) + " / " + clock(durationMs())
        }

        override fun onSwipeEnd(targetMs: Long, deltaMs: Long, commit: Boolean) {
            // One seek, when the finger leaves; a drag of less than half a second is not worth interrupting the picture for.
            if (commit && abs(deltaMs) >= SWIPE_MIN_MS) controller?.let { moveTo(it, targetMs) }
            swipePreview.removeCallbacks(hideSwipePreview)
            hideSwipePreview.run()
        }

        override fun onSingleTap() {
            playerView.performClick() // shows or hides the controls, as a tap on the picture always did
        }
    }

    /** Moves the file to [positionMs]; the words (if shown) follow at once, also when their list was being held by hand. */
    private fun moveTo(c: MediaController, positionMs: Long) {
        c.seekTo(positionMs)
        if (lyricsView.visibility == View.VISIBLE) {
            lyricsView.resumeFollow()
            lyricsView.setPosition(positionMs)
        }
    }

    /** "+10 s" / "-10 s" on the half of the screen that was tapped (the side, not the reading direction: right is forward on every phone language). */
    @SuppressLint("RtlHardcoded")
    private fun showSeekFeedback(forward: Boolean, totalSeconds: Int) {
        val gravity = Gravity.CENTER_VERTICAL or (if (forward) Gravity.RIGHT else Gravity.LEFT)
        (seekFeedback.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.gravity != gravity) {
                it.gravity = gravity
                seekFeedback.layoutParams = it
            }
        }
        seekFeedback.text = String.format(
            Locale.ROOT, "%s%d %s", if (forward) "+" else "\u2212", totalSeconds, getString(R.string.playerSecondsShort),
        )
        seekFeedback.removeCallbacks(hideSeekFeedback)
        seekFeedback.animate().cancel()
        seekFeedback.alpha = 1f
        seekFeedback.visibility = View.VISIBLE
        seekFeedback.postDelayed(hideSeekFeedback, FEEDBACK_MS)
    }

    /** 83000 -> "1:23", 3723000 -> "1:02:03". */
    private fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = total / 60L % 60L
        val s = total % 60L
        return if (h > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Drops what the gestures were waiting for and hides their labels (the screen goes into the little window or is closed). */
    private fun clearGestureFeedback() {
        if (::gestures.isInitialized) gestures.cancel()
        seekFeedback.removeCallbacks(hideSeekFeedback)
        seekFeedback.animate().cancel()
        seekFeedback.visibility = View.GONE
        swipePreview.removeCallbacks(hideSwipePreview)
        swipePreview.animate().cancel()
        swipePreview.visibility = View.GONE
    }

    // ---- Picture in picture, leaving ----

    /** True while a picture (not only a song) is loaded: the only thing the little window can show. */
    private fun hasPicture(): Boolean = controller?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) == true

    /** What the window's play / pause button shows: playing now, or about to (waiting for data). */
    private fun isPlayingNow(c: MediaController): Boolean =
        c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE

    /**
     * Keeps the screen lit while there is something to watch: a picture that is playing (the way the web player does while a video
     * plays; MainActivity sets the same window flag only while the video really plays), or the words of a song (the lyrics view
     * is on the screen), so that the words can be followed without touching the phone. It is on from the moment it plays (also
     * while it waits for data, so a slow connection does not let the screen go dark) and off as soon as it is paused, ends, fails,
     * is turned to sound only without words, or is a song with no words (a song without words has nothing to watch; the screen
     * times out as it does in any music player). The flag stays while the video is in the little window, which is also on the
     * screen, and it is taken off when the screen is left (onStop). It keeps the screen from timing out; the power button still
     * locks the phone.
     */
    private fun updateKeepAwake() {
        val c = controller
        val on = c != null && !isFinishing && isPlayingNow(c) && (hasPicture() || lyricsView.visibility == View.VISIBLE)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * The window's shape: the picture's own (turned if the file is stored sideways), 16:9 until it is known. Android
     * refuses shapes beyond 2.39:1, so a wider picture gets the widest shape allowed.
     */
    private fun pipRatio(): Rational {
        val size = controller?.videoSize
        var aspect = 16f / 9f
        if (size != null && size.width > 0 && size.height > 0) {
            val sideways = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270
            val width = size.width * size.pixelWidthHeightRatio
            aspect = if (sideways) size.height / width else width / size.height
        }
        return Rational((aspect.coerceIn(PIP_MIN_ASPECT, PIP_MAX_ASPECT) * 1000f).roundToInt(), 1000)
    }

    /** Where the picture is on the screen now, for the system's shrinking animation; null until it is drawn. */
    private fun pipSourceRect(): Rect? {
        val surface = playerView.videoSurfaceView ?: return null
        val rect = Rect()
        return if (surface.getGlobalVisibleRect(rect) && !rect.isEmpty) rect else null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(iconRes: Int, labelRes: Int, action: String, requestCode: Int, enabled: Boolean = true): RemoteAction {
        val label = getString(labelRes)
        val intent = PendingIntent.getBroadcast(
            this, requestCode, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), label, label, intent).apply { isEnabled = enabled }
    }

    /**
     * Previous, play / pause, next (play / pause alone on a phone that allows fewer than three buttons). Each state of
     * the middle button has its own label and request code: the system window did not redraw one that differed only by
     * its icon.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(): List<RemoteAction> {
        val c = controller
        val playing = c != null && isPlayingNow(c)
        val playPause =
            if (playing) pipAction(R.drawable.ic_pause_white_24dp, R.string.pipPause, ACTION_PIP_TOGGLE, REQUEST_PAUSE)
            else pipAction(R.drawable.ic_play_white_24dp, R.string.pipPlay, ACTION_PIP_TOGGLE, REQUEST_PLAY)
        if (maxNumPictureInPictureActions < 3) return listOf(playPause)
        val previous = pipAction(R.drawable.ic_skip_previous_white_24dp, R.string.previousVideo, ACTION_PIP_PREVIOUS, REQUEST_PREVIOUS)
        val next = pipAction(
            R.drawable.ic_skip_next_white_24dp, R.string.nextVideo, ACTION_PIP_NEXT, REQUEST_NEXT,
            enabled = c?.hasNextMediaItem() == true,
        )
        // The window is drawn by the system, so it follows the phone's language, not the app's: on a right-to-left phone it
        // puts the first button on the right, which would show "previous" on the right with an arrow pointing left.
        val rtl = Resources.getSystem().configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (rtl) listOf(next, playPause, previous) else listOf(previous, playPause, next)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(ratio: Rational, sourceRect: Rect?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(pipActions())
        if (sourceRect != null) builder.setSourceRectHint(sourceRect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipArmed)
            builder.setSeamlessResizeEnabled(false) // for video, the system's smooth resize looks worse than a crisp one
        }
        return builder.build()
    }

    /**
     * Tells Android what the little window should look like and, from Android 12 on, whether it may enter it by itself
     * when the app is left (only while a picture plays and the menu's switch is on). Cheap to call: nothing is sent unless
     * something changed.
     */
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !pipSupported || isFinishing || isDestroyed) return
        val c = controller ?: return
        val playing = isPlayingNow(c)
        pipArmed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.pipAuto && playing && hasPicture()
        val ratio = pipRatio()
        // The place only matters when entering: once in the window it is not asked again.
        val sourceRect = if (isInPictureInPictureMode) null else pipSourceRect()
        val key = "$ratio|$playing|$pipArmed|$sourceRect|${c.hasNextMediaItem()}"
        if (key == pipParamsKey) return
        pipParamsKey = key
        try {
            setPictureInPictureParams(buildPipParams(ratio, sourceRect))
        } catch (e: Exception) {
            // The system refused (the feature was switched off meanwhile): the player works without the window.
        }
    }

    /** Shrinks the screen into the little window now. False when that is not possible (no picture, or the phone refuses). */
    private fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !pipSupported || isInPictureInPictureMode || !hasPicture()) return false
        return try {
            enterPictureInPictureMode(buildPipParams(pipRatio(), pipSourceRect()))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The app is being left (Home, Recents): a playing video goes into the window when the three-dots menu's switch is on.
     * Android 12+ does it by itself from the params ([updatePipParams]); before that this is where it is asked for.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pipSupported || !prefs.pipAuto || isInPictureInPictureMode) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pipArmed) return
        val c = controller ?: return
        if (c.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) {
            clearGestureFeedback()
            queueSheet.dismiss()
        }
        pipParamsKey = "" // the window changed: the next call sends the params again (without a place while inside)
        if (isInPictureInPictureMode) registerPipReceiver() else unregisterPipReceiver()
        updatePipParams()
    }

    /** The buttons of the little window: previous, play / pause and next. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = controller ?: return
            when (intent.action) {
                ACTION_PIP_TOGGLE -> if (isPlayingNow(c)) {
                    c.pause()
                } else {
                    if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                    c.play()
                }
                ACTION_PIP_PREVIOUS -> c.seekToPrevious()
                ACTION_PIP_NEXT -> c.seekToNext()
            }
        }
    }

    private fun registerPipReceiver() {
        if (pipReceiverOn) return
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_TOGGLE)
            addAction(ACTION_PIP_PREVIOUS)
            addAction(ACTION_PIP_NEXT)
        }
        ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiverOn = true
    }

    private fun unregisterPipReceiver() {
        if (!pipReceiverOn) return
        pipReceiverOn = false
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: IllegalArgumentException) {
            // Not registered after all.
        }
    }

    override fun onStart() {
        super.onStart()
        if (lyricsView.visibility == View.VISIBLE) startTick()
        updateKeepAwake()
    }

    override fun onStop() {
        super.onStop()
        stopTick()
        // Not on the screen any more (home, another app): the flag is taken off here, and set again when the screen returns (onStart).
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The small window was closed: a video stops, as it does everywhere. Music is never stopped by leaving.
        if (isInPictureInPictureMode && audioPanel.visibility != View.VISIBLE) controller?.pause()
    }

    override fun finish() {
        // Leaving a video screen stops the video; music goes on in the background.
        if (audioPanel.visibility != View.VISIBLE) controller?.pause()
        super.finish()
    }

    override fun onDestroy() {
        queueSheet.dismiss()
        stopTick()
        if (::gestures.isInitialized) gestures.cancel()
        unregisterPipReceiver()
        controller?.removeListener(listener)
        playerView.player = null
        future?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    private companion object {
        /** The longest side, in pixels, of the frame that is the cover of a video heard as sound. */
        const val COVER_SIDE = 1024

        /** Where LRC lyrics are kept once converted to SubRip (inside the app's own storage). */
        const val LYRICS_DIR = "lyrics"

        /** How often the lyrics view is told the song's position (the highlight changes only when a new line starts). */
        const val LYRICS_TICK_MS = 100L

        /** How long the double tap's label stays, and how long its fade takes. */
        const val FEEDBACK_MS = 650L
        const val FEEDBACK_FADE_MS = 160L

        /** A swipe that would move less than this does not seek at all. */
        const val SWIPE_MIN_MS = 500L

        // The buttons of the little window. Their own names: the browsing screen's window has buttons of its own.
        const val ACTION_PIP_TOGGLE = "com.mwmods.wardtube.player.PIP_TOGGLE"
        const val ACTION_PIP_PREVIOUS = "com.mwmods.wardtube.player.PIP_PREVIOUS"
        const val ACTION_PIP_NEXT = "com.mwmods.wardtube.player.PIP_NEXT"
        const val REQUEST_PAUSE = 21
        const val REQUEST_PLAY = 22
        const val REQUEST_PREVIOUS = 23
        const val REQUEST_NEXT = 24

        /** Android refuses a window shape beyond 2.39:1 (or 1:2.39); a little inside the limit, so rounding never crosses it. */
        const val PIP_MIN_ASPECT = 0.43f
        const val PIP_MAX_ASPECT = 2.38f
    }
}
