// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.ui.LibraryActivity
import com.mwmods.wardtube.util.UrlUtils
import kotlin.concurrent.thread

/** Entry point for downloads: asks for the format, then hands the job to [DownloadService] (which queues it). */
object Downloader {

    private val VIDEO_HEIGHTS = intArrayOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144)
    private val AUDIO_KBPS = intArrayOf(0, 320, 256, 192, 128, 96, 64)

    /**
     * [title] (the page title) names the job in the Library while it waits in the queue.
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
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download_options, null)
        val ui = OptionsUi(activity, view, DownloadOptions.fromJson(prefs.downloadOptionsJson))
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.downloadChoose)
            .setView(view)
            // Closed only by its Cancel / Download buttons: a tap outside must not drop the choices being made.
            .setCancelable(false)
            .setPositiveButton(R.string.download) { _, _ ->
                val options = ui.read()
                prefs.downloadOptionsJson = options.copy(langs = "").toJson()
                // YouTube's playback position (&t=) is not part of the video. Other sites keep their address as it is:
                // for them `t` may be part of what identifies the video.
                val url = if (UrlUtils.isYouTubeUrl(videoUrl)) UrlUtils.withoutTime(videoUrl) else videoUrl
                if (options.wantsSubs) {
                    chooseSubtitles(activity, url, options, snackbarAnchor, title, onClosed)
                } else {
                    enqueue(activity, url, options, snackbarAnchor, title, onClosed = onClosed)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onClosed() }
            .show()
    }

    /** The "Download" dialog: type, then only the settings that matter for that type. */
    private class OptionsUi(private val activity: AppCompatActivity, root: android.view.View, initial: DownloadOptions) {
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

        init {
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
        url: String,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        title: String?,
        message: Int = R.string.downloadQueued,
        onClosed: () -> Unit = {},
    ) {
        DownloadService.start(activity, url, options, title)
        Snackbar.make(snackbarAnchor, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.library) { activity.startActivity(Intent(activity, LibraryActivity::class.java)) }
            .thenClose(onClosed)
            .show()
    }

    /** Runs [onClosed] when this message disappears (timeout, swipe, its action, or another message replacing it). */
    private fun Snackbar.thenClose(onClosed: () -> Unit): Snackbar = addCallback(object : Snackbar.Callback() {
        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) = onClosed()
    })

    /** Second step for subtitles: reads which languages the video has, then lets the user tick the ones they want. */
    private fun chooseSubtitles(
        activity: AppCompatActivity,
        url: String,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        title: String?,
        onClosed: () -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val loadingView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(CircularProgressIndicator(activity).apply { isIndeterminate = true })
            addView(TextView(activity).apply {
                setText(R.string.subsLoading)
                textSize = 16f
                setPadding(pad / 2, 0, 0, 0)
            })
        }
        var cancelled = false
        val loading = MaterialAlertDialogBuilder(activity)
            .setView(loadingView)
            // Only the Cancel button stops the search: a stray tap outside the dialog or the Back button must not
            // throw the download away.
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelled = true
                onClosed()
            }
            .show()

        val app = activity.applicationContext
        thread(name = "wardtube-subtitle-list") {
            val fetched = runCatching { SubtitleLister.fetch(app, url) }
            activity.runOnUiThread {
                if (cancelled || activity.isDestroyed) return@runOnUiThread
                loading.dismiss()
                val list = fetched.getOrNull()
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
                    list == null -> enqueue(
                        activity, url, options.copy(withSubs = false, langs = ""), snackbarAnchor, title,
                        R.string.subsFetchFailedContinue, onClosed
                    )
                    list.isEmpty() -> enqueue(
                        activity, url, options.copy(withSubs = false, langs = ""), snackbarAnchor, title,
                        R.string.subsNoneContinue, onClosed
                    )
                    else -> showSubtitleChoices(activity, url, options, snackbarAnchor, title, list, onClosed)
                }
            }
        }
    }

    private fun showSubtitleChoices(
        activity: AppCompatActivity,
        url: String,
        options: DownloadOptions,
        snackbarAnchor: android.view.View,
        title: String?,
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
                        activity, url, options.copy(withSubs = false, langs = ""), snackbarAnchor, title,
                        R.string.subsSkipped, onClosed
                    )
                } else {
                    enqueue(activity, url, options.copy(langs = langs), snackbarAnchor, title, onClosed = onClosed)
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
