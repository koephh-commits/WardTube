// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.mwmods.wardtube.R
import com.mwmods.wardtube.util.DownloadStore
import com.mwmods.wardtube.util.PendingFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * Downloads one plain file (zip, pdf, apk...) over HTTP(S) inside the app, instead of handing it to the phone's download
 * manager. It runs as a job of [DownloadService], so the file shares the queue, the notification, the progress card and
 * the Retry of the Library with the videos.
 *
 * - The server is sent what the page sent (referer, user agent, cookies of the file's host; see [RequestHeaders]).
 *   The cookie is dropped when a redirect leads to another host, so a login cookie never travels to a third party.
 * - The file is written straight into Download/WardTube (see [DownloadStore.begin]): no temporary copy.
 * - A dropped connection continues from the last byte received (HTTP Range) a few times before the job fails.
 * - [cancel] stops it at once, also while it waits for the server.
 *
 * One instance is one download: create it, call [run] on a worker thread, and [cancel] from any other thread.
 */
class FileDownloader(
    private val context: Context,
    /** The address as the job carries it (headers smuggled after `#__youtubedl_smuggle=` included). */
    private val jobUrl: String,
    /** The file name to save under; blank = the server's name (Content-Disposition), then the address's. */
    private val wantedName: String,
    private val listener: Listener,
) {

    interface Listener {
        /** The name the file is saved under, once known (the job's title is replaced with it when it was empty). */
        fun onName(name: String)

        fun onProgress(progress: Progress)
    }

    /** What the Library card shows: [percent] is -1 when the server did not tell the size. */
    class Progress(val percent: Int, val size: String, val speed: String, val eta: String)

    class Saved(val name: String, val uri: Uri)

    /** Stopped on purpose; not a failure. */
    class Cancelled : IOException("cancelled")

    /** A failure with a message that is fit to show to the person as it is. */
    class Failure(message: String) : IOException(message)

    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    /** Set only when the person accepted the "not secure" warning for this link (see [RequestHeaders.attach]). */
    private val allowInsecure = RequestHeaders.allowsInsecure(jobUrl)

    /** Stops the download: a read that waits for the server is interrupted by closing its connection. */
    fun cancel() {
        cancelled = true
        runCatching { connection?.disconnect() }
    }

    /** Downloads and saves the file. Throws [Cancelled] or [Failure] (or another IOException for a lost connection). */
    fun run(): Saved {
        var pending: PendingFile? = null
        try {
            val headers = RequestHeaders.headersOf(jobUrl)
            val startUrl = parse(RequestHeaders.display(jobUrl))

            var written = 0L
            var total = -1L
            var name = ""
            var failures = 0
            var lastTick = SystemClock.elapsedRealtime()
            var lastBytes = 0L
            var speed = 0.0

            while (true) {
                var opened: HttpURLConnection? = null
                try {
                    throwIfCancelled()
                    val conn = connect(startUrl, headers, written)
                    opened = conn
                    val code = conn.responseCode
                    if (written > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                        // The server started over (or cannot continue): the bytes already saved are of no use.
                        throw Failure(context.getString(R.string.fileErrNoResume))
                    }
                    if (pending == null) {
                        total = totalOf(conn, code)
                        val contentType = conn.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
                        name = wantedName.trim().ifEmpty {
                            URLUtil.guessFileName(conn.url.toString(), conn.getHeaderField("Content-Disposition"), contentType)
                        }
                        // An expired or protected link often answers with a web page; saving it as the file would only
                        // leave a broken file behind.
                        if (contentType == "text/html" && !mayBeHtml(name)) throw Failure(context.getString(R.string.fileErrPage))
                        val mime = mimeFor(name, contentType)
                        val folder = DownloadStore.subFolderFor(name, mime) ?: DownloadStore.FOLDER_FILES
                        pending = try {
                            DownloadStore.begin(context, name, mime, folder)
                        } catch (e: IOException) {
                            throw Failure(context.getString(R.string.fileErrStorage, e.message.orEmpty()))
                        }
                        listener.onName(name)
                    } else if (written > 0 && !startsAt(conn, written)) {
                        throw Failure(context.getString(R.string.fileErrNoResume))
                    }

                    val out = (pending ?: throw IOException("no file")).output
                    val buffer = ByteArray(BUFFER_BYTES)
                    conn.inputStream.use { input ->
                        while (true) {
                            throwIfCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            try {
                                out.write(buffer, 0, read)
                            } catch (e: IOException) {
                                // Not a network problem (a full phone, usually): trying again would not help.
                                throw Failure(context.getString(R.string.fileErrStorage, e.message.orEmpty()))
                            }
                            written += read
                            failures = 0

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTick >= TICK_MS) {
                                val instant = (written - lastBytes) * 1000.0 / (now - lastTick)
                                speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                                lastTick = now
                                lastBytes = written
                                listener.onProgress(progressOf(written, total, speed))
                            }
                        }
                    }
                    // The server closed the connection before the end: continue from here.
                    if (total > 0 && written < total) throw IOException(context.getString(R.string.fileErrCut))
                    break
                } catch (e: IOException) {
                    if (cancelled || e is Cancelled) throw Cancelled()
                    if (e is Failure) throw e
                    // A lost connection: the same file continues from the last byte received, a few times. A link that
                    // never answered even once (no such site, a refused connection) fails at once, except a timeout.
                    val neverConnected = pending == null && e !is SocketTimeoutException
                    if (neverConnected || ++failures > MAX_RETRIES) {
                        throw Failure(context.getString(R.string.fileErrNetwork, e.message ?: e.javaClass.simpleName))
                    }
                    pause(failures * RETRY_STEP_MS)
                } finally {
                    opened?.disconnect()
                }
            }

            listener.onProgress(progressOf(written, total.takeIf { it > 0 } ?: written, 0.0))
            val uri = try {
                DownloadStore.commit(context, pending!!)
            } catch (e: IOException) {
                throw Failure(context.getString(R.string.fileErrStorage, e.message.orEmpty()))
            }
            pending = null // saved: nothing to throw away
            return Saved(name, uri)
        } finally {
            pending?.let { DownloadStore.discard(context, it) }
        }
    }

    // ---- Connecting ----

    /**
     * Opens the file's address, following redirects by hand (a redirect to another host must not receive the cookie).
     * [from] > 0 asks the server to continue from that byte. Returns a connection whose answer is a success.
     */
    private fun connect(start: URL, headers: Map<String, String>, from: Long): HttpURLConnection {
        var current = secure(start)
        var sendCookie = true
        var hops = 0
        while (true) {
            throwIfCancelled()
            val conn = try {
                current.openConnection() as HttpURLConnection
            } catch (e: ClassCastException) {
                throw Failure(context.getString(R.string.browserFileUnsupported))
            }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            // Raw bytes: a transparently decompressed answer would not match its Content-Length or its Range.
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Accept", "*/*")
            for ((header, value) in headers) {
                if (header == "Cookie" && !sendCookie) continue
                conn.setRequestProperty(header, value)
            }
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            connection = conn
            // cancel() may have run between the check above and now, before this connection was published.
            if (cancelled) {
                conn.disconnect()
                throw Cancelled()
            }

            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                if (cancelled) throw Cancelled()
                throw e
            }
            when {
                code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL -> return conn
                code in REDIRECTS -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank() || ++hops > MAX_REDIRECTS) {
                        throw Failure(context.getString(R.string.fileErrHttp, code))
                    }
                    val next = try {
                        URL(current, location)
                    } catch (e: IOException) {
                        throw Failure(context.getString(R.string.browserFileUnsupported))
                    }
                    val protocol = next.protocol.lowercase(Locale.ROOT)
                    if (protocol != "http" && protocol != "https") {
                        throw Failure(context.getString(R.string.browserFileUnsupported))
                    }
                    if (!next.host.equals(current.host, ignoreCase = true) ||
                        (current.protocol.equals("https", true) && protocol == "http")
                    ) {
                        sendCookie = false
                    }
                    current = secure(next)
                }
                else -> {
                    conn.disconnect()
                    throw Failure(context.getString(R.string.fileErrHttp, code))
                }
            }
        }
    }

    /**
     * A plain http address is tried over https instead. It stays on http only when the person accepted the warning for
     * this link ([allowInsecure]): the browser screen asks before the job is queued, when the site has no https version.
     * (The manifest allows cleartext traffic, so this check, not the system, is what keeps a download from going out
     * unencrypted without the person's say.)
     */
    private fun secure(url: URL): URL {
        if (!url.protocol.equals("http", ignoreCase = true)) return url
        if (allowInsecure) return url
        return try {
            URL("https", url.host, if (url.port == 80) -1 else url.port, url.file)
        } catch (e: IOException) {
            throw Failure(context.getString(R.string.fileErrCleartext))
        }
    }

    private fun parse(address: String): URL {
        val url = try {
            URL(address)
        } catch (e: IOException) {
            throw Failure(context.getString(R.string.browserFileUnsupported))
        }
        val protocol = url.protocol.lowercase(Locale.ROOT)
        if (protocol != "http" && protocol != "https") throw Failure(context.getString(R.string.browserFileUnsupported))
        return url
    }

    // ---- What the server said ----

    /** The whole file's size, or -1 when the server did not say. */
    private fun totalOf(conn: HttpURLConnection, code: Int): Long {
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            CONTENT_RANGE.find(conn.getHeaderField("Content-Range").orEmpty())?.let { m ->
                return m.groupValues[2].toLongOrNull() ?: -1L
            }
            return -1L
        }
        return conn.contentLengthLong
    }

    /** True when a resumed answer starts at byte [from], where the saved part ends. */
    private fun startsAt(conn: HttpURLConnection, from: Long): Boolean {
        val m = CONTENT_RANGE.find(conn.getHeaderField("Content-Range").orEmpty()) ?: return false
        return m.groupValues[1].toLongOrNull() == from
    }

    private fun mayBeHtml(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in HTML_EXTENSIONS

    /** The type by the file's extension first (a server often says "octet-stream" for everything), then the server's. */
    private fun mimeFor(name: String, serverType: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return serverType.takeIf { it.isNotEmpty() && it != "application/octet-stream" } ?: "application/octet-stream"
    }

    // ---- Progress ----

    private fun progressOf(done: Long, total: Long, bytesPerSecond: Double): Progress {
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        val size = if (total > 0) "${bytes(done)} / ${bytes(total)}" else bytes(done)
        val speed = if (bytesPerSecond > 1) "${bytes(bytesPerSecond.toLong())}/s" else ""
        val eta = if (total > 0 && bytesPerSecond > 1) clock(((total - done) / bytesPerSecond).toLong()) else ""
        return Progress(percent, size, speed, eta)
    }

    private fun bytes(value: Long): String {
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < UNITS.size - 1) {
            amount /= 1024
            unit++
        }
        return if (unit == 0) "${value}B" else String.format(Locale.US, "%.1f%s", amount, UNITS[unit])
    }

    private fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", m, sec)
        }
    }

    // ---- Waiting and stopping ----

    private fun throwIfCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw Cancelled()
    }

    /** A pause before the next attempt that a cancel cuts short. */
    private fun pause(ms: Long) {
        var left = ms
        while (left > 0) {
            throwIfCancelled()
            try {
                Thread.sleep(minOf(left, 250L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Cancelled()
            }
            left -= 250L
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
        const val TICK_MS = 500L
        const val MAX_REDIRECTS = 10
        /** Lost connections in a row (without a single byte in between) before the download gives up. */
        const val MAX_RETRIES = 4
        const val RETRY_STEP_MS = 2_000L

        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        val HTML_EXTENSIONS = setOf("html", "htm", "xhtml", "php", "asp", "aspx", "jsp")

        /** "bytes 1000-1999/5000": the first byte and the whole size. */
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-\d+/(\d+)""", RegexOption.IGNORE_CASE)
    }
}
