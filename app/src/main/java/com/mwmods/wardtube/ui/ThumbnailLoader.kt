// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.mwmods.wardtube.R
import com.mwmods.wardtube.util.DownloadItem
import com.mwmods.wardtube.util.MediaCategory
import java.util.Collections
import java.util.concurrent.Executors

/**
 * The picture of a Library row: a frame of the video, or the cover embedded in an audio file.
 *
 * No image library is used (the app keeps its size and memory small): pictures are made on two background threads,
 * shrunk to [MAX_SIDE] pixels, and kept in a small cache so scrolling back is instant.
 *
 * A row's views are reused while scrolling, so every request is tied to the ImageView through a tag: a result that
 * arrives after the view was given to another file is dropped.
 */
object ThumbnailLoader {

    private const val MAX_SIDE = 320

    /** The cache holds at most about 6 MB of pictures. */
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Files that have no picture (an audio file without a cover): not tried again until the app restarts. */
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "wardtube-thumbnail").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Puts the picture of [item] into [view]. [onResult] says whether [view] now shows a picture (true), or whether
     * the row should keep its icon (false: still loading, or the file has none). It is called on the main thread.
     */
    fun load(context: Context, item: DownloadItem, view: ImageView, onResult: (Boolean) -> Unit) {
        val key = item.uri.toString() + "#" + item.sizeBytes
        view.setTag(R.id.ivThumb, key)

        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            onResult(true)
            return
        }
        onResult(false)
        if (key in missing) return

        val app = context.applicationContext
        val audio = item.category == MediaCategory.AUDIO
        executor.execute {
            // The row was reused while this waited in line: nothing to do.
            if (view.getTag(R.id.ivThumb) != key) return@execute
            // runCatching also catches an OutOfMemoryError: a missing picture is better than a crash.
            val bitmap = runCatching { decode(app, item.uri, audio) }.getOrNull()
            main.post {
                if (bitmap != null) {
                    cache.put(key, bitmap)
                } else {
                    if (missing.size > 500) missing.clear()
                    missing.add(key)
                }
                if (bitmap != null && view.getTag(R.id.ivThumb) == key) {
                    view.setImageBitmap(bitmap)
                    onResult(true)
                }
            }
        }
    }

    /** The view no longer belongs to a picture (a row for a subtitle file): a result still on its way is ignored. */
    fun cancel(view: ImageView) {
        view.setTag(R.id.ivThumb, null)
    }

    // ---- Making the picture ----

    private fun decode(context: Context, uri: Uri, audio: Boolean): Bitmap? {
        // Android 10+: the system already keeps thumbnails (and album art) for its media files.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return scaleDown(context.contentResolver.loadThumbnail(uri, Size(MAX_SIDE, MAX_SIDE), null))
            } catch (e: Exception) {
                // No system thumbnail (or none for this kind of file): read the file itself below.
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return if (audio) {
                val bytes = retriever.embeddedPicture ?: return null
                decodeSampled(bytes)
            } else {
                videoFrame(retriever)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** A frame one second in (the very first one is often black). */
    private fun videoFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val atUs = 1_000_000L
        // Android 8.1+: the frame is made small right away, so a 4K video does not need 33 MB of memory just for this.
        // Only for videos that are not rotated: for those the width / height asked for are certainly the right way round.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w != null && h != null && w > 0 && h > 0 && rotation == 0) {
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                val scaled = retriever.getScaledFrameAtTime(
                    atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)
                )
                if (scaled != null) return scaled
            }
        }
        val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
            ?: return null
        return scaleDown(frame)
    }

    /** Decodes a cover picture at a reduced size, so a 3000 x 3000 cover is never fully loaded. */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { scaleDown(it) }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_SIDE) return source
        val scale = MAX_SIDE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true
        )
        if (scaled !== source) source.recycle()
        return scaled
    }
}
