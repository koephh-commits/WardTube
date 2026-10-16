// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.mwmods.wardtube.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

/**
 * The small pictures of the videos in a playlist, read from the internet (the list the person picks videos from).
 * [ThumbnailLoader] does the same for files on the phone.
 *
 * No image library is used: pictures are fetched on three background threads, decoded at a reduced size and kept in a
 * small cache, so ticking a row or scrolling back never fetches a picture twice. Only https addresses are read.
 *
 * A row's views are reused while scrolling, so every request is tied to the ImageView through a tag: a picture that
 * arrives after the view was given to another video is dropped, and a request that has not started yet is skipped.
 */
object RemoteThumbnails {

    private const val MAX_SIDE = 320
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 8_000

    /** The cache holds at most about 5 MB of pictures. */
    private val cache = object : LruCache<String, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Addresses that gave no picture: not tried again until the app restarts. */
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val executor = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "wardtube-remote-thumbnail").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Puts the picture at [address] into [view]. [onResult] says whether [view] now shows a picture (true), or whether
     * the row should keep its placeholder (false: still loading, or there is none). It is called on the main thread.
     */
    fun load(address: String, view: ImageView, onResult: (Boolean) -> Unit) {
        view.setTag(R.id.ivThumb, address)
        if (address.isEmpty()) {
            onResult(false)
            return
        }
        val cached = cache.get(address)
        if (cached != null) {
            view.setImageBitmap(cached)
            onResult(true)
            return
        }
        onResult(false)
        if (address in missing) return

        executor.execute {
            // The row was reused while this waited in line: nothing to do.
            if (view.getTag(R.id.ivThumb) != address) return@execute
            // runCatching also catches an OutOfMemoryError: a missing picture is better than a crash.
            val bitmap = runCatching { download(address) }.getOrNull()
            main.post {
                if (bitmap != null) {
                    cache.put(address, bitmap)
                } else {
                    if (missing.size > 500) missing.clear()
                    missing.add(address)
                }
                if (bitmap != null && view.getTag(R.id.ivThumb) == address) {
                    view.setImageBitmap(bitmap)
                    onResult(true)
                }
            }
        }
    }

    private fun download(address: String): Bitmap? {
        if (!address.startsWith("https://")) return null
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (connection.contentLength > MAX_BYTES) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) return null
            return decodeSampled(bytes)
        } finally {
            connection.disconnect()
        }
    }

    /** Decodes at a reduced size (16-bit colour is plenty for a small row picture and halves the memory). */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
