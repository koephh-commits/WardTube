// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import org.json.JSONArray
import org.json.JSONObject

/** Where a download is in its life. A finished job simply leaves the list: its file shows up in the Library. */
enum class JobStatus {
    /** Waiting for the jobs before it. */
    QUEUED,
    /** Picked up: unpacking the engine / reading the video. */
    STARTING,
    /** The engine is being updated before a retry. */
    UPDATING,
    DOWNLOADING,
    /** Merging / converting / saving. No percentage is known. */
    PROCESSING,
    FAILED,
}

/** One entry of the download queue, as shown in the Library. */
data class DownloadJob(
    val id: String,
    val url: String,
    /** Video title; empty until it is known. */
    val title: String,
    val options: DownloadOptions,
    val status: JobStatus,
    /** Overall progress 0..100 (video + audio streams together), or -1 when unknown. */
    val percent: Int = -1,
    val speed: String = "",
    val eta: String = "",
    /** Size of the stream that is being downloaded right now (e.g. "10.5MiB"). */
    val size: String = "",
    /** Which stream is downloading (1-based) and how many there are (video + audio = 2). */
    val part: Int = 1,
    val parts: Int = 1,
    val error: String = "",
) {
    val isActive: Boolean
        get() = status == JobStatus.STARTING || status == JobStatus.UPDATING ||
            status == JobStatus.DOWNLOADING || status == JobStatus.PROCESSING
}

/**
 * The download service runs in its own process (":download"), so the Library cannot read its memory.
 * The service broadcasts its whole queue as JSON (package-restricted, so nothing leaves the app) and
 * answers [ACTION_REQUEST_STATE] with the current queue. No answer means nothing is downloading.
 */
object DownloadEvents {
    const val ACTION_STATE = "com.mwmods.wardtube.DOWNLOAD_STATE"
    const val ACTION_REQUEST_STATE = "com.mwmods.wardtube.DOWNLOAD_REQUEST_STATE"
    const val EXTRA_JOBS = "jobs"

    fun encode(jobs: List<DownloadJob>): String {
        val array = JSONArray()
        for (job in jobs) {
            array.put(
                JSONObject()
                    .put("id", job.id)
                    .put("url", job.url)
                    .put("title", job.title)
                    .put("options", job.options.toJson())
                    .put("status", job.status.name)
                    .put("percent", job.percent)
                    .put("speed", job.speed)
                    .put("eta", job.eta)
                    .put("size", job.size)
                    .put("part", job.part)
                    .put("parts", job.parts)
                    .put("error", job.error)
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<DownloadJob> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            val jobs = ArrayList<DownloadJob>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                jobs += DownloadJob(
                    id = o.getString("id"),
                    url = o.optString("url"),
                    title = o.optString("title"),
                    options = DownloadOptions.fromJson(o.optString("options")),
                    status = runCatching { JobStatus.valueOf(o.optString("status")) }.getOrDefault(JobStatus.QUEUED),
                    percent = o.optInt("percent", -1),
                    speed = o.optString("speed"),
                    eta = o.optString("eta"),
                    size = o.optString("size"),
                    part = o.optInt("part", 1),
                    parts = o.optInt("parts", 1),
                    error = o.optString("error"),
                )
            }
            jobs
        } catch (e: Exception) {
            emptyList()
        }
    }
}
