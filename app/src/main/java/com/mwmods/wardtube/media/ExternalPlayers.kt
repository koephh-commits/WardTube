// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.mwmods.wardtube.util.DownloadItem
import com.mwmods.wardtube.util.MediaCategory

/**
 * The other apps on the phone that can play a Library file: what the player chooser lists under the built-in player
 * ([com.mwmods.wardtube.ui.PlayerChoiceSheet]). On Android 11+ the phone only shows an app that the manifest's `<queries>`
 * asks about (video and audio "view" intents there), which is why the list can come back empty without it.
 */
object ExternalPlayers {

    /** One player app: the screen of it that opens this kind of file, its name and its icon. */
    class App(val component: ComponentName, val label: String, val icon: Drawable?) {
        val packageName: String get() = component.packageName
    }

    /** Opening [item] "the usual way": its own address with the read grant, typed as the video / song it is. */
    private fun viewIntent(item: DownloadItem): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, typeOf(item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** The system's own type for the file; a missing or odd one (octet-stream) is replaced by what its extension says. */
    private fun typeOf(item: DownloadItem): String {
        val mime = item.mimeType
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return mime
        return if (item.category == MediaCategory.AUDIO) "audio/*" else "video/*"
    }

    /**
     * The player apps that can open [item], by name, this app not among them. One entry for each app (an app that has
     * several screens for the same file is listed once). Empty when there is none, or when the phone says nothing.
     */
    fun find(context: Context, item: DownloadItem): List<App> {
        val pm = context.packageManager
        val found = try {
            pm.queryIntentActivities(viewIntent(item), PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            return emptyList()
        }
        return found.asSequence()
            .mapNotNull { it.activityInfo }
            .filter { it.exported && it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: info.packageName
                App(ComponentName(info.packageName, info.name), label, runCatching { info.loadIcon(pm) }.getOrNull())
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    /** Opens [item] in [app]. False when the app refused or is gone: the caller then plays it in the built-in player. */
    fun open(context: Context, item: DownloadItem, app: App): Boolean {
        val intent = viewIntent(item).setComponent(app.component)
        // The clip makes the read grant cover the address on every Android version, not only where the flag alone does.
        intent.clipData = ClipData.newRawUri("", item.uri)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // ActivityNotFoundException (removed since the list was made) or SecurityException (the app is not exported now).
            false
        }
    }
}
