// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import java.io.File

/**
 * Keeps failed downloads on disk, so the Library still shows them (with Retry / Dismiss) after it was closed and
 * opened again, after a rotation, or after the system recreated it.
 *
 * Why a file and not memory: the queue lives in the download service's process, which stops itself when the queue is
 * empty (and may be killed by the system), and the Library's own list dies with the screen. Neither can be trusted to
 * remember a failure. The service writes the files, the Library only deletes them (Retry / Dismiss), so the two
 * processes never rewrite the same file.
 *
 * Two folders inside the app's private, not-backed-up storage, one small JSON file per job (named by the job's id):
 *  - `active/`: a record written when a job is added and removed when it finishes or is cancelled. A record that is
 *    still there while no service is running belongs to a download that was cut off (process killed, 6-hour limit,
 *    app swiped away): [recoverInterrupted] turns it into a failed one.
 *  - `failed/`: downloads that failed or were interrupted, oldest first.
 *
 * Nothing here may break a download: every function swallows its own errors.
 */
object FailedDownloads {

    private const val ROOT = "downloads_state"
    private const val ACTIVE = "active"
    private const val FAILED = "failed"
    private const val EXTENSION = ".json"

    /** More than this many failures are never kept: the oldest go first (a failed playlist can be long). */
    private const val MAX_FAILED = 100

    private var lastStamp = 0L

    private fun folder(context: Context, name: String): File =
        File(context.applicationContext.noBackupFilesDir, "$ROOT/$name").also { it.mkdirs() }

    /** The file of job [id], or null for an id that is not a plain id (it is never used as a path then). */
    private fun fileOf(context: Context, name: String, id: String): File? {
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' }) return null
        return File(folder(context, name), id + EXTENSION)
    }

    private fun filesIn(context: Context, name: String): List<File> =
        folder(context, name).listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()

    /** Strictly increasing, so the order in which jobs were written is the order they come back in. */
    @Synchronized
    private fun nextStamp(): Long {
        lastStamp = maxOf(System.currentTimeMillis(), lastStamp + 1)
        return lastStamp
    }

    /** Written beside the target and renamed over it: a reader never sees half a file. */
    private fun write(target: File, job: DownloadJob) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(DownloadEvents.encode(listOf(job)))
        temp.setLastModified(nextStamp())
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun read(file: File): DownloadJob? =
        runCatching { DownloadEvents.decode(file.readText()).firstOrNull() }.getOrNull()

    private fun asFailed(job: DownloadJob, message: String = job.error): DownloadJob =
        job.copy(status = JobStatus.FAILED, percent = -1, speed = "", eta = "", error = message)

    // ---- Written by the service ----

    /** A job was added to the queue: if the process dies before it ends, this record is what is left of it. */
    fun markActive(context: Context, job: DownloadJob) {
        runCatching { fileOf(context, ACTIVE, job.id)?.let { write(it, job) } }
    }

    /** The job ended on its own terms (saved, cancelled, or failed and already stored as a failure). */
    fun clearActive(context: Context, id: String) {
        runCatching { fileOf(context, ACTIVE, id)?.delete() }
    }

    /** The job failed: keep it until the person retries or dismisses it. */
    fun saveFailed(context: Context, job: DownloadJob) {
        runCatching {
            fileOf(context, FAILED, job.id)?.let { write(it, asFailed(job)) }
            fileOf(context, ACTIVE, job.id)?.delete()
            prune(context)
        }
    }

    /** The same video in the same format is being downloaded again: its old failure is no longer wanted. */
    fun removeMatching(context: Context, url: String, item: Int, options: DownloadOptions) {
        runCatching {
            for (file in filesIn(context, FAILED)) {
                val job = read(file) ?: continue
                if (job.url == url && job.item == item && job.options == options) file.delete()
            }
        }
    }

    // ---- Read by the Library ----

    /** Whether some download was started and has not ended: it is running now, or it was cut off. */
    fun hasActive(context: Context): Boolean =
        runCatching { filesIn(context, ACTIVE).isNotEmpty() }.getOrDefault(false)

    /** The failed downloads, oldest first. */
    fun loadFailed(context: Context): List<DownloadJob> =
        runCatching {
            filesIn(context, FAILED).mapNotNull { file ->
                val job = read(file)
                if (job == null) file.delete() // unreadable: nothing to show, nothing to keep
                job?.let { asFailed(it) }
            }
        }.getOrDefault(emptyList())

    /** Retry or Dismiss. */
    fun removeFailed(context: Context, id: String) {
        runCatching { fileOf(context, FAILED, id)?.delete() }
    }

    /**
     * Only for a moment when no download service is running: every `active/` record is then a download that was cut
     * off. Each one becomes a failed download carrying [message], in its original place in line.
     */
    fun recoverInterrupted(context: Context, message: String) {
        runCatching {
            for (file in filesIn(context, ACTIVE)) {
                val job = read(file)
                val target = job?.let { fileOf(context, FAILED, it.id) }
                if (job != null && target != null && !target.exists()) {
                    val stamp = file.lastModified()
                    write(target, asFailed(job, message))
                    target.setLastModified(stamp)
                }
                file.delete()
            }
            prune(context)
        }
    }

    private fun prune(context: Context) {
        val files = filesIn(context, FAILED)
        if (files.size > MAX_FAILED) files.take(files.size - MAX_FAILED).forEach { it.delete() }
    }
}
