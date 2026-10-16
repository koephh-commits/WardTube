// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.ui.LibraryActivity
import com.mwmods.wardtube.ui.RemoteThumbnails
import com.mwmods.wardtube.util.PlaylistLinks
import com.mwmods.wardtube.util.UrlUtils
import java.util.Locale
import kotlin.concurrent.thread

/** Entry point for downloads: asks for the format, then hands the job to [DownloadService] (which queues it). */
object Downloader {

    private val VIDEO_HEIGHTS = intArrayOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144)
    private val AUDIO_KBPS = intArrayOf(0, 320, 256, 192, 128, 96, 64)

    /**
     * [title] (the page title) names the job in the Library while it waits in the queue.
     *
     * A link can be one video or a list of them (a playlist, a Mix, a channel, a SoundCloud set...). A link that is known
     * to be a list, and any site this app knows nothing about, is read by yt-dlp right away, while the "Download as"
     * window is open, so that the answer is usually ready by the time the person taps Download. A list then shows its
     * videos to pick from, and every picked video becomes a download of its own.
     *
     * [onClosed] runs once the whole flow is over: cancelled, nothing to download, or queued and its message gone.
     * The share screen uses it to close itself; the browser screen has nothing to do and leaves it out.
     */
    fun chooseAndStart(
        activity: AppCompatActivity,
        videoUrl: String,
        snackbarAnchor: android.view.View,
        title: String? = null,
        onClosed: () -> Unit = {},
    ) {
        val prefs = Prefs(activity)
        val analysis = PlaylistLinks.analyze(videoUrl)
        var probe: ListProbe? = when (analysis.kind) {
            PlaylistLinks.Kind.LIST -> ListProbe(activity, analysis.listUrl ?: analysis.videoUrl, mustBeList = true).start()
            PlaylistLinks.Kind.UNKNOWN -> ListProbe(activity, analysis.videoUrl, mustBeList = false).start()
            else -> null
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download_options, null)
        val ui = OptionsUi(
            activity, view, DownloadOptions.fromJson(prefs.downloadOptionsJson),
            showScope = analysis.kind == PlaylistLinks.Kind.VIDEO_IN_LIST,
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (analysis.kind == PlaylistLinks.Kind.LIST) R.string.downloadChooseList else R.string.downloadChoose)
            .setView(view)
            // Closed only by its Cancel / Download buttons: a tap outside must not drop the choices being made.
            .setCancelable(false)
            .setPositiveButton(R.string.download) { _, _ ->
                val options = ui.read()
                prefs.downloadOptionsJson = options.copy(langs = "").toJson()
                // A video that was opened from a playlist: the person chose to download the whole list.
                if (analysis.kind == PlaylistLinks.Kind.VIDEO_IN_LIST && ui.wantsList) {
                    probe = ListProbe(activity, analysis.listUrl ?: analysis.videoUrl, mustBeList = true).start()
                }
                val reading = probe
                if (reading == null) {
                    startSingle(activity, singleUrl(analysis, videoUrl), options, snackbarAnchor, title, onClosed)
                } else {
                    val mustBeList = analysis.kind != PlaylistLinks.Kind.UNKNOWN
                    awaitList(activity, reading, mustBeList, singleUrl(analysis, videoUrl), options, snackbarAnchor, title, onClosed)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                probe?.cancel()
                onClosed()
            }
            .show()
    }

    /**
     * The link of one video. YouTube's playback position (&t=) and the playlist a video was opened from are not part of
     * the video. Other sites keep their address as it is: for them `t` may be part of what identifies the video.
     */
    private fun singleUrl(analysis: PlaylistLinks.Analysis, videoUrl: String): String = when {
        analysis.kind == PlaylistLinks.Kind.VIDEO_IN_LIST -> analysis.videoUrl
        UrlUtils.isYouTubeUrl(videoUrl) -> UrlUtils.withoutTime(videoUrl)
        else -> videoUrl
    }

    /** One video: its subtitle languages are asked first when subtitles were requested, then it joins the queue. */
    private fun startSingle(
        activity: AppCompatActivity,
        url: String,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        title: String?,
        onClosed: () -> Unit,
    ) {
        val targets = listOf(DownloadTarget(url, title))
        if (options.wantsSubs) {
            chooseSubtitles(activity, targets, options, snackbarAnchor, onClosed)
        } else {
            enqueue(activity, targets, options, snackbarAnchor, onClosed = onClosed)
        }
    }

    // ---- Lists of videos ----

    /** Waits for what yt-dlp says about the link (a "reading" window only if it is not ready yet) and acts on it. */
    private fun awaitList(
        activity: AppCompatActivity,
        probe: ListProbe,
        mustBeList: Boolean,
        singleUrl: String,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        title: String?,
        onClosed: () -> Unit,
    ) {
        val loading = if (probe.ready == null) {
            showLoading(activity, R.string.listLoading) {
                probe.cancel()
                onClosed()
            }
        } else {
            null
        }
        probe.whenReady { result ->
            loading?.dismiss()
            if (activity.isDestroyed) return@whenReady
            when (result) {
                is ProbeResult.Playlist -> chooseEntries(activity, result.info, options, snackbarAnchor, onClosed)
                // A link that is known to be a list but has no video in it: say so instead of starting a job that
                // would make yt-dlp download the whole page as one file.
                ProbeResult.Single ->
                    if (mustBeList) {
                        Snackbar.make(snackbarAnchor, R.string.listEmpty, Snackbar.LENGTH_LONG).thenClose(onClosed).show()
                    } else {
                        startSingle(activity, singleUrl, options, snackbarAnchor, title, onClosed)
                    }
                is ProbeResult.Failed ->
                    Snackbar.make(snackbarAnchor, R.string.listFetchFailed, Snackbar.LENGTH_LONG).thenClose(onClosed).show()
            }
        }
    }

    /**
     * The videos of a list, one row each (picture with its length, title, channel, tick box), all ticked at first, and
     * one button that ticks or unticks them all. The list is the dialog's own ListView, which reuses its rows.
     */
    private fun chooseEntries(
        activity: AppCompatActivity,
        info: PlaylistInfo,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        onClosed: () -> Unit,
    ) {
        val entries = info.entries
        val checked = BooleanArray(entries.size) { true }
        val adapter = EntryAdapter(activity, entries, checked)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(info.title.ifBlank { activity.getString(R.string.listChoose) })
            .setCancelable(false)
            // No click listener here: a dialog closes after a click on an item when it is given one.
            .setAdapter(adapter, null)
            .setPositiveButton(R.string.download) { _, _ ->
                val chosen = entries.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) {
                    Snackbar.make(snackbarAnchor, R.string.listPickOne, Snackbar.LENGTH_LONG).thenClose(onClosed).show()
                } else {
                    val targets = chosen.map { DownloadTarget(it.url, it.title.ifBlank { null }, it.item) }
                    if (options.wantsSubs) {
                        chooseSubtitles(activity, targets, options, snackbarAnchor, onClosed)
                    } else {
                        enqueue(activity, targets, options, snackbarAnchor, onClosed = onClosed)
                    }
                }
            }
            // The click listener is set after show(): a button given here would close the window.
            .setNeutralButton(R.string.listSelectNone, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onClosed() }
            .create()

        fun refresh() {
            val count = checked.count { it }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                text = activity.getString(R.string.listDownloadCount, count)
                isEnabled = count > 0
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text =
                activity.getString(if (count == checked.size) R.string.listSelectNone else R.string.listSelectAll)
        }
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                checked[position] = !checked[position]
                adapter.notifyDataSetChanged()
                refresh()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val tickAll = checked.any { !it }
                checked.fill(tickAll)
                adapter.notifyDataSetChanged()
                refresh()
            }
            refresh()
        }
        dialog.show()
        if (info.truncated) {
            Toast.makeText(activity, activity.getString(R.string.listTruncated, PlaylistLister.MAX_ENTRIES), Toast.LENGTH_LONG).show()
        }
    }

    /** A row for each video of a list. [checked] is shared with the dialog, which flips it and asks for a redraw. */
    private class EntryAdapter(
        private val context: Context,
        private val entries: List<PlaylistEntry>,
        private val checked: BooleanArray,
    ) : BaseAdapter() {

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_playlist_entry, parent, false)
            val entry = entries[position]
            val ticked = checked[position]

            row.findViewById<CheckBox>(R.id.cbEntry).isChecked = ticked
            // A video that is not ticked fades, so what will be downloaded stands out.
            row.alpha = if (ticked) 1f else 0.5f
            row.findViewById<TextView>(R.id.tvEntryTitle).text = entry.title.ifBlank { entry.url }
            row.findViewById<TextView>(R.id.tvEntryMeta).apply {
                text = entry.uploader
                isVisible = entry.uploader.isNotBlank()
            }
            row.findViewById<TextView>(R.id.tvEntryDuration).apply {
                text = formatDuration(entry.durationSec)
                isVisible = entry.durationSec > 0
            }

            // The play icon shows until the picture arrives, and stays when the video has none.
            val thumb = row.findViewById<ImageView>(R.id.ivThumb)
            val icon = row.findViewById<android.view.View>(R.id.ivEntryIcon)
            RemoteThumbnails.load(entry.thumbnail, thumb) { shown ->
                thumb.isVisible = shown
                icon.isVisible = !shown
            }
            return row
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** A window with a spinner and [text], closed only by Cancel: a stray tap or the Back button must not throw the work away. */
    private fun showLoading(activity: AppCompatActivity, @StringRes text: Int, onCancel: () -> Unit): AlertDialog {
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val loadingView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(CircularProgressIndicator(activity).apply { isIndeterminate = true })
            addView(TextView(activity).apply {
                setText(text)
                textSize = 16f
                setPadding(pad / 2, 0, 0, 0)
            })
        }
        return MaterialAlertDialogBuilder(activity)
            .setView(loadingView)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    /** The "Download" dialog: type, then only the settings that matter for that type. */
    private class OptionsUi(
        private val activity: AppCompatActivity,
        root: android.view.View,
        initial: DownloadOptions,
        showScope: Boolean = false,
    ) {
        private val chipScope = root.findViewById<ChipGroup>(R.id.chipScope)
        private val chipKind = root.findViewById<ChipGroup>(R.id.chipKind)
        private val chipContainer = root.findViewById<ChipGroup>(R.id.chipContainer)
        private val chipCodec = root.findViewById<ChipGroup>(R.id.chipCodec)
        private val chipAudio = root.findViewById<ChipGroup>(R.id.chipAudioFormat)
        private val chipSub = root.findViewById<ChipGroup>(R.id.chipSubFormat)
        private val spVideo = root.findViewById<Spinner>(R.id.spVideoQuality)
        private val spAudio = root.findViewById<Spinner>(R.id.spAudioQuality)
        private val cbSubs = root.findViewById<CheckBox>(R.id.cbWithSubs)

        private val sectionVideo = root.findViewById<android.view.View>(R.id.sectionVideo)
        private val sectionCodec = root.findViewById<android.view.View>(R.id.sectionCodec)
        private val sectionAudio = root.findViewById<android.view.View>(R.id.sectionAudio)
        private val sectionAudioQuality = root.findViewById<android.view.View>(R.id.sectionAudioQuality)
        private val lossless = root.findViewById<android.view.View>(R.id.tvLossless)
        private val sectionSubFormat = root.findViewById<android.view.View>(R.id.sectionSubFormat)

        private val kinds = MediaKind.entries
        private val containers = VideoContainer.entries
        private val audioCodecs = AudioCodec.entries
        private val subFormats = listOf("srt", "lrc")

        /** The video was opened from a playlist and the person picked the whole playlist instead of the one video. */
        val wantsList: Boolean get() = scopeShown && selectedIndex(chipScope) == 1
        private val scopeShown = showScope

        init {
            root.findViewById<android.view.View>(R.id.sectionScope).isVisible = showScope
            fill(chipScope, listOf(
                activity.getString(R.string.optScopeVideo),
                activity.getString(R.string.optScopeList),
            ), 0)
            fill(chipKind, kinds.map {
                activity.getString(when (it) {
                    MediaKind.VIDEO -> R.string.optKindVideo
                    MediaKind.AUDIO -> R.string.optKindAudio
                    MediaKind.SUBS -> R.string.optKindSubs
                })
            }, kinds.indexOf(initial.kind))
            fill(chipContainer, containers.map { it.label }, containers.indexOf(initial.container))
            fill(chipCodec, listOf(
                activity.getString(R.string.optCodecCompatible),
                activity.getString(R.string.optCodecBest),
            ), if (initial.compatibleCodec) 0 else 1)
            fill(chipAudio, audioCodecs.map { it.label }, audioCodecs.indexOf(initial.audioCodec))
            fill(chipSub, subFormats.map { it.uppercase() }, subFormats.indexOf(initial.subFormat).coerceAtLeast(0))

            spVideo.adapter = spinnerAdapter(VIDEO_HEIGHTS.map {
                if (it == 0) activity.getString(R.string.optBest) else "${it}p"
            })
            spVideo.setSelection(VIDEO_HEIGHTS.indexOf(initial.maxHeight).takeIf { it >= 0 } ?: 4)
            spAudio.adapter = spinnerAdapter(AUDIO_KBPS.map {
                if (it == 0) activity.getString(R.string.optBest) else "$it kbps"
            })
            spAudio.setSelection(AUDIO_KBPS.indexOf(initial.audioKbps).coerceAtLeast(0))
            cbSubs.isChecked = initial.withSubs

            val refresh = { updateVisibility() }
            chipKind.setOnCheckedStateChangeListener { _, _ -> refresh() }
            chipContainer.setOnCheckedStateChangeListener { _, _ -> refresh() }
            chipAudio.setOnCheckedStateChangeListener { _, _ -> refresh() }
            cbSubs.setOnCheckedChangeListener { _, _ -> refresh() }
            updateVisibility()
        }

        private fun spinnerAdapter(items: List<String>) =
            ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)

        private fun fill(group: ChipGroup, labels: List<String>, selected: Int) {
            labels.forEachIndexed { index, label ->
                val chip = LayoutInflater.from(activity).inflate(R.layout.chip_choice, group, false) as Chip
                chip.id = android.view.View.generateViewId()
                chip.text = label
                chip.tag = index
                group.addView(chip)
                if (index == selected) group.check(chip.id)
            }
        }

        private fun selectedIndex(group: ChipGroup): Int =
            (group.findViewById<Chip>(group.checkedChipId)?.tag as? Int) ?: 0

        private fun updateVisibility() {
            val kind = kinds[selectedIndex(chipKind)]
            val video = kind == MediaKind.VIDEO
            val audio = kind == MediaKind.AUDIO
            sectionVideo.isVisible = video
            // WebM fixes its own codecs, so the codec choice only matters for MP4 / MKV.
            sectionCodec.isVisible = video && containers[selectedIndex(chipContainer)] != VideoContainer.WEBM
            sectionAudio.isVisible = audio
            val lossy = audioCodecs[selectedIndex(chipAudio)].lossy
            sectionAudioQuality.isVisible = audio && lossy
            lossless.isVisible = audio && !lossy
            // For "subtitles only" the checkbox is pointless: the format is always asked.
            cbSubs.isVisible = kind != MediaKind.SUBS
            sectionSubFormat.isVisible = kind == MediaKind.SUBS || cbSubs.isChecked
        }

        fun read(): DownloadOptions = DownloadOptions(
            kind = kinds[selectedIndex(chipKind)],
            maxHeight = VIDEO_HEIGHTS[spVideo.selectedItemPosition.coerceIn(0, VIDEO_HEIGHTS.size - 1)],
            container = containers[selectedIndex(chipContainer)],
            compatibleCodec = selectedIndex(chipCodec) == 0,
            audioCodec = audioCodecs[selectedIndex(chipAudio)],
            audioKbps = AUDIO_KBPS[spAudio.selectedItemPosition.coerceIn(0, AUDIO_KBPS.size - 1)],
            subFormat = subFormats[selectedIndex(chipSub)],
            withSubs = cbSubs.isChecked,
        )
    }

    private fun enqueue(
        activity: AppCompatActivity,
        targets: List<DownloadTarget>,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        message: Int = R.string.downloadQueued,
        onClosed: () -> Unit = {},
    ) {
        val text = if (targets.size > 1) {
            DownloadService.startMany(activity, targets, options)
            activity.getString(R.string.downloadQueuedMany, targets.size)
        } else {
            val target = targets[0]
            DownloadService.start(activity, target.url, options, target.title, target.item)
            activity.getString(message)
        }
        Snackbar.make(snackbarAnchor, text, Snackbar.LENGTH_LONG)
            .setAction(R.string.library) { activity.startActivity(Intent(activity, LibraryActivity::class.java)) }
            .thenClose(onClosed)
            .show()
    }

    /** Runs [onClosed] when this message disappears (timeout, swipe, its action, or another message replacing it). */
    private fun Snackbar.thenClose(onClosed: () -> Unit): Snackbar = addCallback(object : Snackbar.Callback() {
        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) = onClosed()
    })

    /**
     * Second step for subtitles: reads which languages the video has, then lets the user tick the ones they want.
     * For several videos (a playlist) the languages of the first one are offered and apply to all of them.
     */
    private fun chooseSubtitles(
        activity: AppCompatActivity,
        targets: List<DownloadTarget>,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        onClosed: () -> Unit,
    ) {
        var cancelled = false
        val loading = showLoading(activity, R.string.subsLoading) {
            cancelled = true
            onClosed()
        }

        val app = activity.applicationContext
        val first = targets[0]
        thread(name = "wardtube-subtitle-list") {
            val fetched = runCatching { SubtitleLister.fetch(app, first.url, first.item) }
            activity.runOnUiThread {
                if (cancelled || activity.isDestroyed) return@runOnUiThread
                loading.dismiss()
                val list = fetched.getOrNull()
                val withoutSubs = options.copy(withSubs = false, langs = "")
                when {
                    // Subtitles only: with no subtitles there is nothing to download, so say why.
                    list == null && options.isSubsOnly ->
                        Snackbar.make(snackbarAnchor, R.string.subsFetchFailed, Snackbar.LENGTH_LONG)
                            .thenClose(onClosed).show()
                    list != null && list.isEmpty() && options.isSubsOnly ->
                        Snackbar.make(snackbarAnchor, R.string.subsNotAvailable, Snackbar.LENGTH_LONG)
                            .thenClose(onClosed).show()
                    // A video / audio download with "also download subtitles": the subtitles are a bonus, so the
                    // download goes on without them instead of being dropped.
                    list == null -> enqueue(activity, targets, withoutSubs, snackbarAnchor, R.string.subsFetchFailedContinue, onClosed)
                    list.isEmpty() -> enqueue(activity, targets, withoutSubs, snackbarAnchor, R.string.subsNoneContinue, onClosed)
                    else -> showSubtitleChoices(activity, targets, options, snackbarAnchor, list, onClosed)
                }
            }
        }
    }

    private fun showSubtitleChoices(
        activity: AppCompatActivity,
        targets: List<DownloadTarget>,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        tracks: List<SubtitleOption>,
        onClosed: () -> Unit,
    ) {
        val labels = tracks.map { option ->
            val kind = when (option.kind) {
                SubtitleOption.Kind.MANUAL -> R.string.subsManual
                SubtitleOption.Kind.AUTO -> R.string.subsAuto
                SubtitleOption.Kind.AUTO_TRANSLATED -> R.string.subsAutoTranslated
            }
            "${option.languageName} (${activity.getString(kind)})"
        }.toTypedArray()
        // Pre-tick the first track so one tap on "Download" is enough for the common case.
        val checked = BooleanArray(tracks.size).also { it[0] = true }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.subsChoose)
            .setCancelable(false)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.download) { _, _ ->
                val langs = tracks.filterIndexed { i, _ -> checked[i] }.joinToString(",") { it.code }
                if (langs.isEmpty() && options.isSubsOnly) {
                    Snackbar.make(snackbarAnchor, R.string.subsPickOne, Snackbar.LENGTH_LONG)
                        .thenClose(onClosed).show()
                } else if (langs.isEmpty()) {
                    // Video / audio with no language ticked: download it without subtitles.
                    enqueue(
                        activity, targets, options.copy(withSubs = false, langs = ""), snackbarAnchor,
                        R.string.subsSkipped, onClosed
                    )
                } else {
                    enqueue(activity, targets, options.copy(langs = langs), snackbarAnchor, onClosed = onClosed)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onClosed() }
            .show()
    }

    /** Manual engine update (menu). Reports the result in a snackbar. */
    fun updateEngine(activity: AppCompatActivity, snackbarAnchor: android.view.View) {
        Snackbar.make(snackbarAnchor, R.string.engineUpdating, Snackbar.LENGTH_SHORT).show()
        val app = activity.applicationContext
        thread(name = "wardtube-engine-update") {
            val ok = YtDlpEngine.update(app)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val message = if (ok) R.string.engineUpdated else R.string.engineUpdateFailed
                Snackbar.make(snackbarAnchor, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
