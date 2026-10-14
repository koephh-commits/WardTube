// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Runtime permissions:
 * - Android 13+: READ_MEDIA_AUDIO / READ_MEDIA_VIDEO
 * - Android 12L and older: READ_EXTERNAL_STORAGE
 * - Android 9 and older: WRITE_EXTERNAL_STORAGE as well (needed to save / delete files)
 */
object Permissions {

    /** Permissions needed to list media files that were not created by this install. */
    fun readMedia(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasReadMedia(context: Context): Boolean = readMedia().all { granted(context, it) }

    /** WRITE_EXTERNAL_STORAGE only exists as a runtime permission up to Android 9 (API 28). */
    fun needsLegacyWrite(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasLegacyWrite(context: Context): Boolean =
        !needsLegacyWrite() || granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** "All files access" exists from Android 11 (API 30); older versions need nothing more. */
    fun canRequestAllFiles(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAllFiles(): Boolean = !canRequestAllFiles() || Environment.isExternalStorageManager()

    fun hasMicrophone(context: Context): Boolean = granted(context, Manifest.permission.RECORD_AUDIO)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
