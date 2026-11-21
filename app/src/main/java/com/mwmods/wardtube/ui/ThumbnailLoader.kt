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

    /** The size of a song's cover in the Library's mini player (the same as a row's picture). */
    const val COVER_SIDE = 320

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
            val bitmap = runCatching { decode(app, item.uri, audio, MAX_SIDE) }.getOrNull()
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


    /**
     * The cover of the song [uri] at up to [maxSide] pixels, for the Library's mini player (the rows use [load]). Same rules as [load]: tied to [view] through a tag, a late result for a view
     * that was given to another song is dropped, and [onResult] (main thread) says whether [view] now shows a cover
     * (true) or the caller should show its note icon (false: still loading, or the song has no cover).
     *
     * [audio] true (what the mini player uses) reads only the cover embedded in a song; null lets the file say whether it
     * is a video (a frame is made) or a song, which the player's queue needs because it lists both.
     */
    fun loadCover(
        context: Context,
        uri: Uri,
        view: ImageView,
        maxSide: Int,
        audio: Boolean? = true,
        onResult: (Boolean) -> Unit,
    ) {
        // The way of reading is part of the key: a video asked as a song has no picture, and that must not hide the frame.
        val key = "cover$maxSide:${audio ?: "auto"}:$uri"
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
        executor.execute {
            if (view.getTag(R.id.ivThumb) != key) return@execute
            val bitmap = runCatching { decode(app, uri, audio, maxSide) }.getOrNull()
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

    /**
     * The picture of the file at [uri], made on the calling thread and not cached, for notifications (the built-in
     * player's media notification and lock screen, and the download's "saved" notification): a frame of a video, or the
     * cover embedded in a song. Null when it has none.
     */
    fun decodeCover(context: Context, uri: Uri, side: Int): Bitmap? = decode(context.applicationContext, uri, null, side)

    /** [audio] null: the file itself says whether it has a picture track (a video) or not (a song). */
    private fun decode(context: Context, uri: Uri, audio: Boolean?, side: Int): Bitmap? {
        // Android 10+: the system already keeps thumbnails (and album art) for its media files.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return scaleDown(context.contentResolver.loadThumbnail(uri, Size(side, side), null), side)
            } catch (e: Exception) {
                // No system thumbnail (or none for this kind of file): read the file itself below.
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val isAudio = audio
                ?: (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes")
            return if (isAudio) {
                val bytes = retriever.embeddedPicture ?: return null
                decodeSampled(bytes, side)
            } else {
                videoFrame(retriever, side)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** A frame one second in (the very first one is often black). */
    private fun videoFrame(retriever: MediaMetadataRetriever, side: Int): Bitmap? {
        val atUs = 1_000_000L
        // Android 8.1+: the frame is made small right away, so a 4K video does not need 33 MB of memory just for this.
        // Only for videos that are not rotated: for those the width / height asked for are certainly the right way round.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w != null && h != null && w > 0 && h > 0 && rotation == 0) {
                val scale = minOf(1f, side.toFloat() / maxOf(w, h))
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
        return scaleDown(frame, side)
    }

    /** Decodes a cover picture at a reduced size, so a 3000 x 3000 cover is never fully loaded. */
    private fun decodeSampled(bytes: ByteArray, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { scaleDown(it, side) }
    }

    private fun scaleDown(source: Bitmap, side: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= side) return source
        val scale = side.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true
        )
        if (scaled !== source) source.recycle()
        return scaled
    }
}
