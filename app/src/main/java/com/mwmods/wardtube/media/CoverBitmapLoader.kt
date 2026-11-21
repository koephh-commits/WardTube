// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mwmods.wardtube.ui.ThumbnailLoader
import java.util.concurrent.Executors

/**
 * Gives the built-in player's media notification (and the lock screen's card) the picture of what is playing: a frame
 * of the video, or the cover of the song.
 *
 * The queue's items carry only the file's address under [KEY_COVER] (in the extras of their metadata), never the
 * picture itself: a picture per item would make the queue heavy for the session's binder and would need every file of a
 * big queue opened up front. The picture is made here, only for the item that is playing, on a background thread, and
 * kept for as long as that item plays (the notification is rebuilt at every play / pause, and must not open the file
 * again each time). Items without the key get no picture.
 */
@UnstableApi
class CoverBitmapLoader(context: Context) : BitmapLoader {

    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wardtube-cover").apply { isDaemon = true }
    }

    private var lastKey: String? = null
    private var lastFuture: ListenableFuture<Bitmap>? = null

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        executor.execute {
            val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
            if (bitmap != null) result.set(bitmap) else result.setException(IllegalStateException("Not a picture"))
        }
        return result
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = load(uri)

    @Synchronized
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val key = metadata.extras?.getString(KEY_COVER)?.takeIf { it.isNotBlank() } ?: return null
        val cached = lastFuture
        if (key == lastKey && cached != null) return cached
        val future = load(Uri.parse(key))
        lastKey = key
        lastFuture = future
        return future
    }

    private fun load(uri: Uri): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        executor.execute {
            // runCatching also catches an OutOfMemoryError: no picture is better than a crash.
            val bitmap = runCatching { ThumbnailLoader.decodeCover(app, uri, SIDE)?.let { squareCrop(it) } }.getOrNull()
            if (bitmap != null) result.set(bitmap) else result.setException(IllegalStateException("No picture in $uri"))
        }
        return result
    }

    /** The middle square, as the page player's notification does: a wide video frame fills the small icon. */
    private fun squareCrop(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        if (source.width == side && source.height == side) return source
        return Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    }

    companion object {
        /** Key, in the extras of an item's metadata, of the file's address the picture is made from. */
        const val KEY_COVER = "wt.cover.uri"

        private const val SIDE = 512
    }
}
