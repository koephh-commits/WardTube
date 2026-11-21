// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mwmods.wardtube.ui.RemoteThumbnails
import com.mwmods.wardtube.ui.ThumbnailLoader
import com.mwmods.wardtube.util.VideoLinks

/**
 * The picture that goes beside a download's notification: the video's cover, cut to a square as the media notification
 * does. Both functions block (network, or a file being read): call them from a background thread. Null when there is no
 * picture, and the notification then keeps its plain look.
 */
object NotificationCovers {

    private const val SIDE = 256

    /** The thumbnail of the YouTube video that [link] names, or null for any other address. */
    fun ofLink(link: String): Bitmap? {
        val video = VideoLinks.parse(link) ?: return null
        return RemoteThumbnails.fetch(video.thumbnailUrl)?.let { squareCrop(it) }
    }

    /** A frame of the saved video, or the cover of the saved song, whatever site it came from. */
    fun ofFile(context: Context, uri: Uri): Bitmap? =
        ThumbnailLoader.decodeCover(context, uri, SIDE)?.let { squareCrop(it) }

    private fun squareCrop(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        if (source.width == side && source.height == side) return source
        return Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    }
}
