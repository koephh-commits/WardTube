// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
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

    /** Addresses that gave no picture: not tried again until the app restarts. */
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val executor = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "wardtube-subs-image").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Puts the picture at [url] into [view]; when that address gives nothing, [fallback] is tried. [width] is about the
     * size, in pixels, the picture is shown at: the picture is decoded no larger than needed for it.
     */
    fun load(view: ImageView, url: String, fallback: String?, width: Int) {
        view.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        if (url.isEmpty()) return

        executor.execute {
            // The view was reused while this waited in line: nothing to do.
            if (view.tag != url) return@execute
            var bitmap: Bitmap? = null
            for (candidate in listOfNotNull(url, fallback)) {
                if (candidate in missing) continue
                // runCatching also catches an OutOfMemoryError: a missing picture is better than a crash.
                bitmap = runCatching { fetch(candidate, width) }.getOrNull()
                if (bitmap != null) break
                if (missing.size > 800) missing.clear()
                missing.add(candidate)
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
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
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
