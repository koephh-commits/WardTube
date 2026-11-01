// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.ui.LibraryActivity
import com.mwmods.wardtube.util.CrashLog
import com.mwmods.wardtube.util.DownloadStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads videos / audio with the bundled yt-dlp. Jobs run one after another: the first one is
 * "downloading", the rest are "queued" and move up as the ones before them finish.
 *
 * A download that is running can be paused and resumed. Pausing stops its yt-dlp process (or its HTTP connection) but
 * keeps what was already downloaded: the job stays in the list as "paused", the jobs behind it carry on, and Resume
 * puts it back at the end of the line, where it continues from the same bytes. The service stays alive (in the
 * foreground) while a job is paused, because the half-downloaded file belongs to this process.
 *
 * The whole queue (progress, speed, ETA, waiting jobs) is broadcast to the Library through
 * [DownloadEvents] and mirrored in one notification. Each finished file is saved into
 * Download/WardTube, so it shows up in the Library.
 */
class DownloadService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val pendingJobs = AtomicInteger(0)
    private val resultIds = AtomicInteger(RESULT_BASE_ID)
    private val handler = Handler(Looper.getMainLooper())

    /** The queue in the order jobs were added; the first non-queued one is the job being worked on. Guarded by [lock]. */
    private val lock = Any()
    private val jobs = LinkedHashMap<String, DownloadJob>()
    private var lastPublish = 0L

    @Volatile private var currentJobId: String? = null
    @Volatile private var currentProcessId: String? = null
    /** The plain file being downloaded by the app itself (not by yt-dlp), when the current job is one. */
    @Volatile private var currentFile: FileDownloader? = null
    @Volatile private var cancelRequested = false
    @Volatile private var pauseRequested = false
    /** Set when the job that just ran was paused: it stays in the queue instead of leaving it. */
    @Volatile private var heldRun = false
    /**
     * Plain-file downloads that are paused, by job id. The downloader keeps the half-written file and how far it got,
     * so Resume continues with the same instance. (A yt-dlp job keeps its files in a folder instead, see [process].)
     * Guarded by [lock].
     */
    private val pausedFiles = HashMap<String, FileDownloader>()
    @Volatile private var engineUpdatedThisRun = false
    /**
     * Whether a failed download may update the engine and try again. It comes with each start request (the newest one
     * wins) because this service runs in its own process and cannot read the app's settings as they change.
     */
    @Volatile private var autoUpdateEngine = false
    @Volatile private var lastStartId = 0

    /** A Library that just opened asks for the queue; silence from this process means nothing is downloading. */
    private val stateRequests = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publish(force = true)
    }

    /** Tells the Library every couple of seconds that this process is alive (it may be killed on low-memory phones). */
    private val heartbeat = object : Runnable {
        override fun run() {
            publish(force = true)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this, stateRequests, IntentFilter(DownloadEvents.ACTION_REQUEST_STATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // A fresh service has no jobs: any download still recorded as running was cut off when the old process died.
        FailedDownloads.recoverInterrupted(this, getString(R.string.downloadInterrupted))
        // ...and whatever it left in the temporary folder (a paused download's parts included) is of no use any more.
        runCatching { File(cacheDir, "dl").deleteRecursively() }
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every request counts, cancel requests too: stopSelfResult() only stops for the newest startId.
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            // From the notification (no id: the current job) or from a Library card (that job).
            cancelJob(intent?.getStringExtra(EXTRA_JOB_ID))
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            // From the notification (no id: the current job) or from a Library card (that job).
            pauseJob(intent?.getStringExtra(EXTRA_JOB_ID))
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            resumeJob(intent?.getStringExtra(EXTRA_JOB_ID))
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        val request = intent
        val targets = if (request == null) emptyList<DownloadTarget>() else targetsOf(request)
        if (request == null || targets.isEmpty()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val options = DownloadOptions.fromJson(request.getStringExtra(EXTRA_OPTIONS))
        autoUpdateEngine = request.getBooleanExtra(EXTRA_AUTO_UPDATE, false)

        createChannel()

        // One request can carry a whole playlist: every video is a job of its own (own progress, cancel and retry).
        val added = ArrayList<String>()
        for (target in targets) {
            // The same video in the same format twice would only waste data and storage.
            val duplicate = synchronized(lock) {
                jobs.values.any { it.url == target.url && it.item == target.item && it.options == options }
            }
            if (duplicate) continue
            // Downloading it again: its old failure (kept for the Library) is no longer wanted.
            FailedDownloads.removeMatching(this, target.url, target.item, options)
            val job = DownloadJob(
                id = UUID.randomUUID().toString(),
                url = target.url,
                item = target.item,
                title = target.title.orEmpty().trim(),
                options = options,
                status = JobStatus.QUEUED,
            )
            synchronized(lock) { jobs[job.id] = job }
            // If this process dies before the job ends, the Library shows it as a failed (interrupted) download.
            FailedDownloads.markActive(this, job)
            pendingJobs.incrementAndGet()
            added += job.id
        }
        ensureForeground()
        if (added.isEmpty()) {
            toast(R.string.downloadAlreadyQueued)
            return START_NOT_STICKY
        }
        publish(force = true)
        for (jobId in added) schedule(jobId)
        return START_NOT_STICKY
    }

    /** The videos a start request asks for: the batch of a playlist, or the single link. */
    private fun targetsOf(request: Intent): List<DownloadTarget> {
        request.getStringExtra(EXTRA_ITEMS)?.let { json ->
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    DownloadTarget(url, o.optString("title"), o.optInt("item", 0))
                }
            }.getOrDefault(emptyList())
        }
        val url = request.getStringExtra(EXTRA_URL) ?: return emptyList()
        return listOf(
            DownloadTarget(url, request.getStringExtra(EXTRA_TITLE).orEmpty(), request.getIntExtra(EXTRA_ITEM, 0))
        )
    }

    /** Puts one queued job on the worker; the jobs run one after another. */
    private fun schedule(jobId: String) {
        worker.execute {
            // True when this run ended because the person paused the job: it stays in the queue, waiting for Resume.
            var held = false
            try {
                held = runJob(jobId)
            } catch (e: Throwable) {
                CrashLog.record(this, "download", e)
                fail(jobId, e.message ?: e.javaClass.simpleName)
            } finally {
                if (!held) finish(jobId)
                if (pendingJobs.decrementAndGet() == 0) {
                    // The last job did not save anything (it failed or was cancelled): still announce the earlier ones.
                    if (quietSaves > 0) {
                        notifyResult(getString(R.string.filesSavedMany, quietSaves), openLibrary = true)
                        quietSaves = 0
                    }
                    if (hasPausedJobs()) {
                        // Nothing is running, but a paused download waits for Resume: the service (and its notification,
                        // which now offers Resume) stays, because the half-downloaded file belongs to this process.
                        publish(force = true)
                    } else {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // Only stops if no newer start request arrived in the meantime.
                        stopSelfResult(lastStartId)
                    }
                }
            }
        }
    }

    /**
     * Android 15+: a dataSync foreground service may run for about 6 hours per day in total. When that limit is
     * reached the system calls this, and the service must stop within a few seconds or the app is crashed.
     * Stopping runs onDestroy(), which cancels the running yt-dlp process.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        notifyResult(getString(R.string.downloadFailed), null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(stateRequests) }
        currentProcessId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        currentFile?.cancel()
        worker.shutdownNow()
        // A paused download's half-written file cannot outlive this process: throw it away.
        val paused = synchronized(lock) { pausedFiles.values.toList().also { pausedFiles.clear() } }
        for (file in paused) runCatching { file.discard() }
        runCatching { File(cacheDir, "dl").deleteRecursively() }
        // Stopped by the system (6-hour limit...) with jobs still running or waiting: keep them as failed ones.
        FailedDownloads.recoverInterrupted(this, getString(R.string.downloadInterrupted))
        synchronized(lock) { jobs.clear() }
        broadcast(emptyList()) // the Library must not keep showing jobs that are gone
        super.onDestroy()
    }

    // ---- Queue state ----

    /** Changes one job. Progress ticks are throttled; pass [force] for changes the Library should see at once. */
    private fun update(id: String, force: Boolean = false, change: (DownloadJob) -> DownloadJob) {
        synchronized(lock) {
            val old = jobs[id] ?: return
            jobs[id] = change(old)
        }
        publish(force)
    }

    /** Takes a job out of the queue (finished, cancelled or failed) and tells the Library. */
    private fun finish(id: String) {
        val removed = synchronized(lock) { jobs.remove(id) }
        // Ended normally (saved, cancelled, or failed and already stored as a failure): not "interrupted".
        FailedDownloads.clearActive(this, id)
        if (removed != null) publish(force = true)
    }

    /**
     * [detail] is yt-dlp's whole message (warnings and the error): the Library shows it in full when the card is
     * tapped. Only the notification, a single line, gets a short version.
     */
    private fun fail(
        id: String,
        detail: String,
        notification: String = getString(R.string.downloadFailed) + ": " + detail.take(NOTIFICATION_ERROR_LENGTH),
    ) {
        notifyResult(notification, null)
        val message = detail.take(MAX_ERROR_LENGTH)
        // Written to disk first, so a Library that is closed (or reopened) right now still finds it. It stays there
        // (with Retry / Dismiss) until the person acts on it, long after the job has left the queue.
        synchronized(lock) { jobs[id] }?.let { job ->
            FailedDownloads.saveFailed(
                this, job.copy(status = JobStatus.FAILED, percent = -1, speed = "", eta = "", error = message)
            )
        }
        update(id, force = true) {
            it.copy(status = JobStatus.FAILED, percent = -1, speed = "", eta = "", error = message)
        }
    }

    private fun cancelJob(requestedId: String?) {
        val target = requestedId ?: currentJobId ?: return
        if (target == currentJobId) {
            cancelRequested = true
            currentProcessId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
            currentFile?.cancel()
            return
        }
        // Still waiting, or paused: just take it out of the queue; its task (if it has one) will find nothing to do.
        val removed = synchronized(lock) { jobs.remove(target) }
        // A paused download leaves a half-downloaded file behind: nobody will resume it now.
        discardPartial(target)
        FailedDownloads.clearActive(this, target)
        if (removed != null) publish(force = true)
    }

    /** Throws away what a paused job had already downloaded (the file being written, or yt-dlp's parts). */
    private fun discardPartial(id: String) {
        val file = synchronized(lock) { pausedFiles.remove(id) }
        if (file != null) runCatching { file.discard() }
        runCatching { File(cacheDir, "dl/$id").deleteRecursively() }
    }

    /**
     * Pauses the job that is downloading right now ([requestedId] null = that one): its yt-dlp process is stopped (or its
     * connection closed) and the job waits as "paused" with what it already has. Merging / converting, a job that is
     * only waiting, or a request that came too late (the job just ended) is left alone.
     */
    private fun pauseJob(requestedId: String?) {
        val target = requestedId ?: currentJobId ?: return
        if (target != currentJobId) return
        val job = synchronized(lock) { jobs[target] } ?: return
        if (job.status != JobStatus.DOWNLOADING) return
        pauseRequested = true
        currentProcessId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        currentFile?.pause()
    }

    /** The running job ended because it was paused: it stays in the queue, with its percentage, until Resume. */
    private fun hold(id: String) {
        heldRun = true
        update(id, force = true) { it.copy(status = JobStatus.PAUSED, speed = "", eta = "") }
    }

    /**
     * Puts a paused job back in line ([requestedId] null = the first paused one). It goes to the end of the queue: that
     * is where the worker finds it, and the Library shows its real place. It then continues from the bytes it has.
     */
    private fun resumeJob(requestedId: String?) {
        val id = requestedId ?: synchronized(lock) { jobs.values.firstOrNull { it.status == JobStatus.PAUSED }?.id } ?: return
        val resumed = synchronized(lock) {
            val job = jobs[id]
            if (job == null || job.status != JobStatus.PAUSED) {
                false
            } else {
                jobs.remove(id)
                jobs[id] = job.copy(status = JobStatus.QUEUED, speed = "", eta = "")
                true
            }
        }
        if (!resumed) return
        pendingJobs.incrementAndGet()
        publish(force = true)
        schedule(id)
    }

    private fun hasPausedJobs(): Boolean = synchronized(lock) { jobs.values.any { it.status == JobStatus.PAUSED } }

    /** A cancel / pause / resume request started the service and left it with nothing to do: stop it. */
    private fun stopIfIdle(startId: Int) {
        if (pendingJobs.get() == 0 && !hasPausedJobs()) stopSelfResult(startId)
    }

    /** Sends the queue to the Library and refreshes the notification. */
    private fun publish(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val snapshot: List<DownloadJob> = synchronized(lock) {
            if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) return
            lastPublish = now
            ArrayList(jobs.values)
        }
        broadcast(snapshot)
        updateNotification(snapshot)
    }

    private fun broadcast(snapshot: List<DownloadJob>) {
        runCatching {
            sendBroadcast(
                Intent(DownloadEvents.ACTION_STATE)
                    .setPackage(packageName) // never leaves the app
                    .putExtra(DownloadEvents.EXTRA_JOBS, DownloadEvents.encode(snapshot))
            )
        }
    }

    private fun toast(@StringRes message: Int) {
        handler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    // ---- One download ----

    /** Returns true when the job was paused: it stays in the queue then, instead of leaving it. */
    private fun runJob(id: String): Boolean {
        // Cancelled while it was waiting: it is no longer in the queue.
        val job = synchronized(lock) { jobs[id] } ?: return false
        cancelRequested = false
        pauseRequested = false
        heldRun = false
        currentJobId = id
        try {
            process(job)
        } finally {
            currentJobId = null
            currentProcessId = null
        }
        return heldRun
    }

    private fun process(job: DownloadJob) {
        val id = job.id
        update(id, force = true) { it.copy(status = JobStatus.STARTING) }
        // A plain file (zip, pdf, apk...) is downloaded by the app itself: yt-dlp is not needed for it.
        if (job.options.isFile) {
            processFile(job)
            return
        }
        if (!YtDlpEngine.ensureReady(this)) {
            val message = getString(R.string.downloadEngineFailed)
            fail(id, message, notification = message)
            return
        }

        // One folder per job, not per attempt: a paused download continues in it, and yt-dlp picks up its ".part" files.
        val workDir = File(cacheDir, "dl/$id").apply { mkdirs() }
        // True when the job was paused: its folder must stay for Resume.
        var keepWork = false
        try {
            var attempt = 0
            // Audio files get their cover picture embedded. The cover is a bonus: if embedding it fails, the download
            // is repeated once without it (the title / artist tags are still written).
            var withCover = job.options.isAudio && job.options.audioCodec.cover
            while (true) {
                try {
                    download(job, workDir, withCover)
                    break
                } catch (e: Throwable) {
                    if (cancelRequested) {
                        notifyResult(getString(R.string.downloadCancelled), null)
                        return
                    }
                    if (pauseRequested) {
                        // The process was stopped on purpose: its parts stay in workDir, and Resume continues from them.
                        keepWork = true
                        hold(id)
                        return
                    }
                    if (withCover && isCoverError(e)) {
                        withCover = false
                        workDir.listFiles()?.forEach { it.deleteRecursively() }
                        toast(R.string.coverSkipped)
                        continue
                    }
                    // Most failures are an outdated yt-dlp. Only when the person turned on automatic updating: update it
                    // once and retry. Otherwise the job fails as it is, and the person can update the engine from the menu
                    // (or from the failed download's message) and use Retry.
                    if (autoUpdateEngine && attempt++ == 0 && !engineUpdatedThisRun) {
                        engineUpdatedThisRun = true
                        update(id, force = true) {
                            it.copy(status = JobStatus.UPDATING, percent = -1, speed = "", eta = "", size = "")
                        }
                        if (YtDlpEngine.update(this)) continue
                    }
                    fail(id, e.message ?: e.javaClass.simpleName)
                    return
                }
            }
            if (cancelRequested) {
                notifyResult(getString(R.string.downloadCancelled), null)
                return
            }
            // Stopped for a pause without an error being thrown: its unfinished parts are still there.
            if (pauseRequested && workDir.listFiles()?.any { it.name.endsWith(".part") || it.name.endsWith(".ytdl") } == true) {
                keepWork = true
                hold(id)
                return
            }

            val options = job.options
            val files = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && it.length() > 0 }
                .orEmpty()
            // Subtitles: yt-dlp writes one file per language, named "<title>.<lang>.<format>". The one for the first
            // language the person picked comes first: it is the one that gets the plain name when saved (see below).
            val pickedLangs = options.langs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val subtitleFiles = if (options.wantsSubs) {
                files.filter { it.extension.equals(options.subFormat, ignoreCase = true) }
                    .sortedWith(compareBy({ subtitleRank(it, pickedLangs) }, { it.name }))
            } else {
                emptyList()
            }
            // Subtitles next to a video / audio: when YouTube refuses them (rate limit) yt-dlp only prints a warning and
            // the download "succeeds" without them, so tell the user instead of leaving them wondering.
            if (options.wantsSubs && !options.isSubsOnly && subtitleFiles.isEmpty()) toast(R.string.subsMissing)
            // YouTube's automatic captions repeat every line and carry filler cues: keep each line once.
            subtitleFiles.forEach { SubtitleCleaner.clean(it) }
            val media = if (options.isSubsOnly) {
                null
            } else {
                files.filter { it !in subtitleFiles }.maxByOrNull { it.length() }
            }
            val outputs = listOfNotNull(media) + subtitleFiles
            if (outputs.isEmpty() || (!options.isSubsOnly && media == null)) {
                if (options.isSubsOnly) {
                    val message = getString(R.string.subsNotAvailable)
                    fail(id, message, notification = message)
                } else {
                    fail(id, "", notification = getString(R.string.downloadFailed))
                }
                return
            }
            update(id, force = true) {
                it.copy(status = JobStatus.PROCESSING, percent = -1, speed = "", eta = "", size = "")
            }
            // A file found in the browser is named after the page (its own name is often the same for every episode).
            val pageName = RequestHeaders.nameOf(job.url)
            // The lyrics file must have exactly the name of the video / audio it belongs to, only the extension differs
            // ("Song.mp3" + "Song.srt"): players pair the two files by name, and one extra character (yt-dlp's ".ar",
            // the " (1)" the system adds to a name that is taken) and the lyrics are not found. So the media is saved
            // first, its real name is read back, and the lyrics are named after that.
            var mediaBase: String? = null
            val savedNames = ArrayList<String>()
            for (output in outputs) {
                val wanted = if (output == media) {
                    if (pageName != null) "$pageName.${output.extension}" else output.name
                } else {
                    subtitleSavedName(output, mediaBase, pickedLangs, primary = output == subtitleFiles.firstOrNull())
                }
                val uri = output.inputStream().use { input ->
                    DownloadStore.save(this, wanted, mimeFor(output), input)
                }
                val actual = DownloadStore.displayNameOf(this, uri) ?: wanted
                if (output == media) mediaBase = actual.substringBeforeLast('.')
                savedNames.add(actual)
            }
            val savedText = when {
                outputs.size == 1 -> getString(R.string.downloadSaved, savedNames[0])
                options.isSubsOnly -> getString(R.string.subsSavedMany, outputs.size)
                else -> getString(R.string.filesSavedMany, outputs.size)
            }
            reportSaved(savedText, outputs.size)
        } catch (e: Throwable) {
            fail(id, e.message ?: e.javaClass.simpleName)
        } finally {
            if (!keepWork) workDir.deleteRecursively()
        }
    }

    /** The language part of a yt-dlp subtitle file name ("Title.ar.srt" -> "ar"), only when it is one the person picked. */
    private fun subtitleLang(file: File, pickedLangs: List<String>): String? {
        val stem = file.name.removeSuffix("." + file.extension)
        val lang = stem.substringAfterLast('.', "")
        return pickedLangs.firstOrNull { it.equals(lang, ignoreCase = true) }
    }

    /** Position of the file's language in the picked list (the first picked language is 0); unknown ones go last. */
    private fun subtitleRank(file: File, pickedLangs: List<String>): Int {
        val lang = subtitleLang(file, pickedLangs) ?: return Int.MAX_VALUE
        return pickedLangs.indexOf(lang)
    }

    /**
     * The name a subtitle / lyrics file is saved under: "<name of the media>.<format>", with no language code, so a
     * player finds it next to the video / audio. [mediaBase] is the media's real saved name without its extension
     * (null for a "subtitles only" job: the video's title is used then). Only one language can have the plain name
     * ([primary]: the first one picked); any other language keeps its code ("Song.en.srt"), or it would overwrite it.
     */
    private fun subtitleSavedName(file: File, mediaBase: String?, pickedLangs: List<String>, primary: Boolean): String {
        val ext = file.extension
        val lang = subtitleLang(file, pickedLangs)
        val stem = file.name.removeSuffix(".$ext")
        val title = if (lang != null) stem.removeSuffix(".$lang") else stem
        val base = mediaBase ?: title
        return when {
            primary -> "$base.$ext"
            lang != null -> "$base.$lang.$ext"
            else -> file.name
        }
    }

    /**
     * A plain file found in the browser, downloaded over HTTP by [FileDownloader] (not by yt-dlp and not by the system's
     * download manager). The job's title is the file's name. Progress, cancel and retry work as for any other job.
     */
    private fun processFile(job: DownloadJob) {
        val id = job.id
        // A paused file continues with the same downloader: the bytes already written stay where they are.
        val downloader = synchronized(lock) { pausedFiles.remove(id) } ?: FileDownloader(this, job.url, job.title, object : FileDownloader.Listener {
            override fun onName(name: String) {
                if (job.title.isBlank()) update(id, force = true) { it.copy(title = name) }
            }

            override fun onProgress(progress: FileDownloader.Progress) {
                update(id) {
                    it.copy(
                        status = JobStatus.DOWNLOADING, percent = progress.percent, speed = progress.speed,
                        eta = progress.eta, size = progress.size, part = 1, parts = 1,
                    )
                }
            }
        })
        currentFile = downloader
        try {
            // Cancelled between being picked up and now.
            if (cancelRequested) downloader.cancel()
            val saved = downloader.run()
            reportSaved(getString(R.string.downloadSaved, saved.name), 1)
        } catch (e: FileDownloader.Paused) {
            synchronized(lock) { pausedFiles[id] = downloader }
            hold(id)
        } catch (e: FileDownloader.Cancelled) {
            notifyResult(getString(R.string.downloadCancelled), null)
        } catch (e: Throwable) {
            fail(id, e.message ?: e.javaClass.simpleName)
        } finally {
            currentFile = null
        }
    }

    /** Files saved while more jobs were still waiting; they are announced together when the queue is empty. */
    private var quietSaves = 0

    /**
     * One notification per saved file is right for a single download, but a playlist would bury the phone in them:
     * while more jobs are waiting nothing is posted, and the last one announces them all.
     */
    private fun reportSaved(text: String, files: Int) {
        if (pendingJobs.get() > 1) {
            quietSaves += files
            return
        }
        if (quietSaves > 0) {
            notifyResult(getString(R.string.filesSavedMany, quietSaves + files), openLibrary = true)
            quietSaves = 0
        } else {
            notifyResult(text, openLibrary = true)
        }
    }

    /** True when yt-dlp's error text points at the thumbnail / tagging step rather than at the download itself. */
    private fun isCoverError(e: Throwable): Boolean {
        val text = (e.message ?: "").lowercase()
        return listOf("thumbnail", "mutagen", "atomicparsley", "postprocessing", "ffmpeg").any { it in text }
    }

    private fun download(job: DownloadJob, workDir: File, withCover: Boolean) {
        val options = job.options
        val request = YoutubeDLRequest(job.url).apply {
            if (job.item > 0) {
                // A page with several videos and no address for each: ask for this one by its position.
                addOption("--playlist-items", job.item.toString())
            } else {
                // Every video of a playlist is its own job with its own address; a link that still carries a list
                // (watch?v=...&list=...) means the one video.
                addOption("--no-playlist")
            }
            addOption("--no-mtime")
            // A stream (HLS / DASH) arrives as hundreds of small fragments: asking for several at once is much faster
            // than one after the other. A video that is a single file is one piece, so this changes nothing for it.
            addOption("--concurrent-fragments", CONCURRENT_FRAGMENTS.toString())
            addOption("-o", "${workDir.absolutePath}/%(title).80s.%(ext)s")
            // A file found in the browser: the server is sent what the page sent (referer, user agent, cookies).
            for ((name, value) in RequestHeaders.headersOf(job.url)) addOption("--add-header", "$name:$value")
            when (options.kind) {
                MediaKind.FILE -> Unit // never reached: a plain file is downloaded by FileDownloader
                MediaKind.SUBS -> addOption("--skip-download")
                MediaKind.AUDIO -> {
                    addOption("-f", options.format)
                    addOption("-x")
                    addOption("--audio-format", options.audioCodec.ytName)
                    // FLAC / WAV are lossless: a bitrate would mean nothing.
                    if (options.audioCodec.lossy) addOption("--audio-quality", options.audioQualityArg)
                    // Title, artist, album, date... written into the file's tags.
                    addOption("--embed-metadata")
                    if (withCover) {
                        // The cover is the video's thumbnail: converted to JPEG (YouTube serves WebP) and cut to a
                        // centred square, which is what music players expect for album art. min() keeps portrait or
                        // already square pictures working; the quotes keep the commas out of ffmpeg's filter syntax.
                        addOption("--embed-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                        addOption("--ppa", "ThumbnailsConvertor+FFmpeg_o:-vf crop=\"'min(iw,ih)':'min(iw,ih)'\"")
                    }
                }
                MediaKind.VIDEO -> {
                    addOption("-f", options.format)
                    addOption("--merge-output-format", options.container.ext)
                }
            }
            if (options.wantsSubs) {
                // The languages the user picked; manual captions win over automatic ones.
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", options.langs.ifBlank { "ar,en" })
                addOption("--convert-subs", options.subFormat)
            }
        }
        if (options.isSubsOnly) {
            // There is no percentage for a few small text files.
            update(job.id, force = true) {
                it.copy(status = JobStatus.PROCESSING, percent = -1, speed = "", eta = "", size = "")
            }
        }
        val processId = UUID.randomUUID().toString()
        currentProcessId = processId
        // A resumed download starts the bar where it stopped (the percentage never goes backwards).
        val tracker = ProgressTracker(job.id, job.title, job.percent)
        // yt-dlp prints many lines a second; the tracker reads each one and the queue throttles what is broadcast.
        YoutubeDL.getInstance().execute(request, processId) { _, _, line -> tracker.onLine(line.orEmpty()) }
    }

    /**
     * Turns yt-dlp's output into job progress. A video is usually two downloads (video stream, then audio
     * stream) that each count 0-100%, so the overall percentage is computed over all streams and never goes backwards.
     */
    private inner class ProgressTracker(private val jobId: String, initialTitle: String, startPercent: Int) {
        private var titleKnown = initialTitle.isNotBlank()
        private var totalStreams = 1
        private var destinations = 0
        private var lastOverall = startPercent.coerceAtLeast(-1)
        private var processing = false
        /** True while yt-dlp fetches a subtitle file: its 100% lines are not part of the video's progress. */
        private var inSubtitleFile = false

        fun onLine(rawLine: String) {
            val line = rawLine.trim()
            if (line.isEmpty() || processing) return

            // Merging / converting: there is no percentage any more.
            if (PROCESSING_PREFIXES.any { line.startsWith(it) }) {
                processing = true
                update(jobId, force = true) {
                    it.copy(status = JobStatus.PROCESSING, percent = -1, speed = "", eta = "", size = "")
                }
                return
            }
            // "Downloading 1 format(s): 247+251" -> two streams.
            FORMATS_RE.find(line)?.let { m ->
                totalStreams = m.groupValues[1].split('+').size.coerceAtLeast(1)
                return
            }
            // A resumed download finds a stream that was already complete (the video, when the pause came during the
            // audio): it counts as a stream, or the overall percentage would count the next one as the first.
            ALREADY_RE.find(line)?.let { m ->
                inSubtitleFile = SUBTITLE_FILE_RE.containsMatchIn(m.groupValues[1].trim())
                if (inSubtitleFile) return
                destinations++
                if (destinations > totalStreams) totalStreams = destinations
                return
            }
            // A new stream starts; its file name also gives the video title.
            DESTINATION_RE.find(line)?.let { m ->
                inSubtitleFile = SUBTITLE_FILE_RE.containsMatchIn(m.groupValues[1].trim())
                if (inSubtitleFile) return
                destinations++
                if (destinations > totalStreams) totalStreams = destinations
                if (!titleKnown) {
                    val name = titleFromPath(m.groupValues[1])
                    if (name.isNotBlank()) {
                        titleKnown = true
                        update(jobId, force = true) { it.copy(title = name) }
                    }
                }
                return
            }

            if (inSubtitleFile) return
            val m = PROGRESS_RE.find(line) ?: return
            val streamPercent = m.groupValues[1].toDoubleOrNull() ?: return
            val index = (destinations - 1).coerceAtLeast(0)
            if (index >= totalStreams) totalStreams = index + 1
            val overall = (((index + streamPercent / 100.0) / totalStreams) * 100.0).toInt().coerceIn(0, 100)
            if (overall > lastOverall) lastOverall = overall

            val percent = lastOverall
            val part = index + 1
            val parts = totalStreams
            val size = m.groupValues[2]
            val speed = m.groupValues[3].takeIf { it.endsWith("/s") && !it.startsWith("Unknown") }.orEmpty()
            val eta = m.groupValues[4].takeIf { ETA_RE.matches(it) }.orEmpty()
            update(jobId) {
                it.copy(
                    status = JobStatus.DOWNLOADING, percent = percent, speed = speed, eta = eta,
                    size = size, part = part, parts = parts,
                )
            }
        }
    }

    private fun titleFromPath(path: String): String =
        File(path.trim()).nameWithoutExtension.replace(FORMAT_ID_SUFFIX_RE, "").trim()

    private fun mimeFor(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            "aac" -> "audio/aac"
            // Generic type on purpose: MediaStore may rename a file whose extension does not match a known type.
            "srt", "lrc" -> "application/octet-stream"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    // ---- Notifications ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.downloadChannel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** One notification for the whole queue: the current job's title and progress, plus how many are waiting. */
    private fun ongoingNotification(live: List<DownloadJob>): Notification {
        val current = live.firstOrNull { it.isActive } ?: live.firstOrNull { it.status == JobStatus.PAUSED } ?: live.firstOrNull()
        val waiting = live.count { it.status == JobStatus.QUEUED }

        var title = getString(R.string.app_name)
        var text = getString(R.string.downloadPreparing)
        var percent = -1
        var paused = false
        if (current != null) {
            if (current.title.isNotBlank()) title = current.title
            when (current.status) {
                JobStatus.UPDATING -> text = getString(R.string.engineUpdating)
                JobStatus.PROCESSING -> text = getString(R.string.downloadProcessing)
                JobStatus.PAUSED -> {
                    paused = true
                    if (current.percent >= 0) {
                        percent = current.percent
                        text = getString(R.string.jobPausedPercent, percent)
                    } else {
                        text = getString(R.string.jobPaused)
                    }
                }
                JobStatus.DOWNLOADING -> if (current.percent >= 0) {
                    percent = current.percent
                    text = getString(R.string.downloadProgress, percent)
                } else if (current.size.isNotEmpty()) {
                    // A file whose size the server did not tell: what has arrived so far.
                    text = getString(R.string.downloadRunning) + " " + current.size
                }
                else -> Unit
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (paused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0 && !paused)
            // Tapping it opens the Library, where every job has its own progress bar.
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 1, Intent(this, LibraryActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        // Pause for the download that is running, Resume for the one that is paused, then Cancel for either.
        if (current != null && current.status == JobStatus.DOWNLOADING) {
            builder.addAction(0, getString(R.string.jobPauseAction), servicePending(REQUEST_PAUSE, ACTION_PAUSE, current.id))
        }
        if (paused && current != null) {
            builder.addAction(0, getString(R.string.jobResumeAction), servicePending(REQUEST_RESUME, ACTION_RESUME, current.id))
        }
        // No id: the running job. A paused job is not running, so it is named.
        builder.addAction(
            0, getString(android.R.string.cancel),
            servicePending(REQUEST_CANCEL, ACTION_CANCEL, if (paused) current?.id else null)
        )
        if (waiting > 0) builder.setSubText(getString(R.string.downloadWaitingCount, waiting))
        return builder.build()
    }

    /** A notification button that sends [action] (about [jobId], when given) back to this service. */
    private fun servicePending(requestCode: Int, action: String, jobId: String?): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(action)
        if (jobId != null) intent.putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun showForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    /** Must be called for every start request: the system requires startForeground() after startForegroundService(). */
    private fun ensureForeground() {
        try {
            val live = synchronized(lock) { jobs.values.filter { it.status != JobStatus.FAILED } }
            showForeground(ongoingNotification(live))
        } catch (e: Throwable) {
            CrashLog.record(this, "startForeground", e)
        }
    }

    private fun updateNotification(snapshot: List<DownloadJob>) {
        val live = snapshot.filter { it.status != JobStatus.FAILED }
        if (live.isEmpty()) return // never re-post it after the service stopped being a foreground service
        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoingNotification(live))
    }

    private fun notifyResult(text: String, openLibrary: Boolean?) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
        if (openLibrary == true) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, LibraryActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        getSystemService(NotificationManager::class.java).notify(resultIds.getAndIncrement(), builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val ONGOING_ID = 2001
        private const val RESULT_BASE_ID = 2100
        /** A failed job keeps up to this much of the engine's message; the queue is broadcast with it. */
        private const val MAX_ERROR_LENGTH = 6000
        private const val NOTIFICATION_ERROR_LENGTH = 200

        private const val EXTRA_URL = "url"
        private const val EXTRA_OPTIONS = "options"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_AUTO_UPDATE = "autoUpdateEngine"
        private const val EXTRA_JOB_ID = "jobId"
        private const val ACTION_CANCEL = "com.mwmods.wardtube.CANCEL_DOWNLOAD"
        private const val ACTION_PAUSE = "com.mwmods.wardtube.PAUSE_DOWNLOAD"
        private const val ACTION_RESUME = "com.mwmods.wardtube.RESUME_DOWNLOAD"

        // Request codes of the notification's buttons (0 and 1 are taken by Cancel and by opening the Library).
        private const val REQUEST_CANCEL = 0
        private const val REQUEST_PAUSE = 3
        private const val REQUEST_RESUME = 4

        /** Progress is broadcast at most this often (status changes always go out at once). */
        private const val PUBLISH_INTERVAL_MS = 400L
        private const val HEARTBEAT_MS = 2000L

        /** Fragments of a stream fetched at the same time (yt-dlp's -N): enough to be fast, light for a 2 GB phone. */
        private const val CONCURRENT_FRAGMENTS = 4

        // yt-dlp output, e.g. "[download]  45.3% of   10.50MiB at    1.20MiB/s ETA 00:05"
        private val PROGRESS_RE =
            Regex("""\[download\]\s+(\d+(?:\.\d+)?)%\s+of\s+~?\s*(\S+)(?:\s+at\s+(\S+))?(?:\s+ETA\s+(\S+))?""")
        private val FORMATS_RE = Regex("""Downloading \d+ format\(s\): (\S+)""")
        private val DESTINATION_RE = Regex("""\[download\] Destination: (.+)""")
        private val ALREADY_RE = Regex("""\[download\] (.+) has already been downloaded""")
        private val SUBTITLE_FILE_RE = Regex("""\.(vtt|srt|ttml|srv\d|json3|lrc|ass)$""", RegexOption.IGNORE_CASE)
        private val ETA_RE = Regex("""\d+(:\d+)+""")
        private val FORMAT_ID_SUFFIX_RE = Regex("""\.f\d+$""")

        /** Post-processing steps that follow the download itself. */
        private val PROCESSING_PREFIXES = listOf(
            "[Merger]", "[ExtractAudio]", "[VideoConvertor]", "[VideoRemuxer]", "[Fixup", "[Metadata]",
            "[EmbedThumbnail]", "[ThumbnailsConvertor]"
        )

        /** [title] is shown while the job waits; yt-dlp's own file name replaces it if it is empty. */
        fun start(context: Context, url: String, options: DownloadOptions, title: String? = null, item: Int = 0) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ITEM, item)
                .putExtra(EXTRA_OPTIONS, options.toJson())
                .putExtra(EXTRA_TITLE, title.orEmpty())
                .putExtra(EXTRA_AUTO_UPDATE, Prefs(context).autoUpdateEngine)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Queues a plain file (zip, pdf, apk...) that the app downloads itself. [url] is the address with the page's
         * identity attached (see [RequestHeaders.attach]); [fileName] is what the file is saved as and the job's title.
         */
        fun startFile(context: Context, url: String, fileName: String) {
            start(context, url, DownloadOptions(kind = MediaKind.FILE), fileName)
        }

        /** Queues several videos (a playlist) with one request, so the Library and the notification are refreshed once. */
        fun startMany(context: Context, targets: List<DownloadTarget>, options: DownloadOptions) {
            val array = JSONArray()
            for (target in targets) {
                array.put(
                    JSONObject().put("url", target.url).put("title", target.title.orEmpty()).put("item", target.item)
                )
            }
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_ITEMS, array.toString())
                .putExtra(EXTRA_OPTIONS, options.toJson())
                .putExtra(EXTRA_AUTO_UPDATE, Prefs(context).autoUpdateEngine)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Cancels one job: the one downloading right now, one that is paused, or one that is still waiting. */
        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_JOB_ID, jobId)
            runCatching { context.startService(intent) }
        }

        /** Pauses a job that is downloading: what it has stays, and [resume] continues from there. */
        fun pause(context: Context, jobId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_JOB_ID, jobId)
            runCatching { context.startService(intent) }
        }

        /** Puts a paused job back in line; it continues from the bytes it already has. */
        fun resume(context: Context, jobId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_RESUME)
                .putExtra(EXTRA_JOB_ID, jobId)
            runCatching { context.startService(intent) }
        }
    }
}
