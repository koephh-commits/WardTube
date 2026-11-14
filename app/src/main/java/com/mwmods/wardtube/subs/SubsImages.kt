// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The pictures of the Subscriptions screen (video thumbnails and channel avatars), read from the internet.
 *
 * No image library is used (the app keeps its size and memory small): pictures are fetched on three background
 * threads, decoded at a reduced size (16-bit colour) and kept in a cache of about 14 MB. A row's views are reused while
 * scrolling, so every request is tied to its ImageView through the view's tag: a picture that arrives after the view
 * was given to another item is dropped, and a request that has not started yet is skipped.
 */
object SubsImages {

    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val TIMEOUT_MS = 8_000

    private val cache = object : LruCache<String, Bitmap>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Addresses that gave no picture (YouTube answered "not found", or the bytes were no picture), with the time they were
     * given up on: not tried again for [MISSING_MS]. A network failure (a timeout, a dropped connection, a server error) is
     * no proof that the address has no picture, so it is never written here: it used to be, until the app restarted,
     * and one bad moment of the network then left that picture out for good.
     */
    private val missing = HashMap<String, Long>()
    private const val MISSING_MS = 3 * 60 * 1000L

    private fun isMissing(address: String): Boolean =
        synchronized(missing) {
            val at = missing[address] ?: return false
            if (System.currentTimeMillis() - at < MISSING_MS) return true
            missing.remove(address)
            false
        }

    private fun markMissing(address: String) {
        synchronized(missing) {
            if (missing.size > 800) missing.clear()
            missing[address] = System.currentTimeMillis()
        }
    }

    private val executor = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "wardtube-subs-image").apply { isDaemon = true }
    }

    /** A line of its own for the pictures of a sheet (the person is looking at it now), so they do not wait behind the screen's. */
    private val urgentExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "wardtube-subs-image-now").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Puts the picture at [url] into [view]; when that address gives nothing, [fallback] is tried. [width] is about the
     * size, in pixels, the picture is shown at: the picture is decoded no larger than needed for it.
     */
    fun load(view: ImageView, url: String, fallback: String?, width: Int, urgent: Boolean = false) {
        view.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        if (url.isEmpty()) return

        (if (urgent) urgentExecutor else executor).execute {
            // The view was reused while this waited in line: nothing to do.
            if (view.tag != url) return@execute
            var bitmap: Bitmap? = null
            for (candidate in listOfNotNull(url, fallback)) {
                if (isMissing(candidate)) continue
                var trouble = false
                // Twice for a network failure. Anything thrown (an OutOfMemoryError too) is caught: a missing picture is
                // better than a crash.
                for (attempt in 0 until 2) {
                    trouble = false
                    try {
                        bitmap = fetch(candidate, width)
                    } catch (e: IOException) {
                        trouble = true
                    } catch (e: Throwable) {
                        trouble = true
                    }
                    if (bitmap != null || !trouble) break
                }
                if (bitmap != null) break
                if (!trouble) markMissing(candidate)
            }
            val result = bitmap
            main.post {
                if (result != null) {
                    cache.put(url, result)
                    if (view.tag == url) view.setImageBitmap(result)
                }
            }
        }
    }

    private fun fetch(address: String, width: Int): Bitmap? {
        if (!address.startsWith("https://")) return null
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // A server that is busy or failing may give the picture a moment later; any other answer is "no picture".
                if (code >= 500 || code == 429 || code == 408) throw IOException("HTTP $code")
                return null
            }
            if (connection.contentLength > MAX_BYTES) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }
}
