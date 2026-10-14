// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.adblock

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

internal class FilterList(val id: String, val name: String, val urls: List<String>)

/**
 * Keeps the filter lists on disk. A downloaded copy (files/filters/<id>.txt) wins over a copy
 * bundled in assets/adblock/lists/<id>.txt, so lists can also be shipped inside the APK.
 */
internal class FilterListManager(private val context: Context) {

    val lists = listOf(
        FilterList(
            "easylist", "EasyList",
            listOf("https://easylist-downloads.adblockplus.org/easylist.txt"),
        ),
        FilterList(
            "liste_ar", "Liste AR",
            // The first address is the usual one; the second is a fallback in case of a different file name.
            listOf(
                "https://easylist-downloads.adblockplus.org/Liste_AR.txt",
                "https://easylist-downloads.adblockplus.org/liste_ar.txt",
            ),
        ),
    )

    /** The list's rules, or null if it was never downloaded or bundled. */
    fun open(list: FilterList): BufferedReader? {
        val file = cacheFile(list)
        return when {
            file.isFile -> file.bufferedReader()
            hasAsset(list) -> context.assets.open(assetPath(list)).bufferedReader()
            else -> null
        }
    }

    /** Downloads [list]. Blocking: call from a background thread. */
    fun download(list: FilterList): Boolean {
        for (address in list.urls) {
            if (downloadFrom(address, cacheFile(list))) return true
        }
        return false
    }

    private fun downloadFrom(address: String, target: File): Boolean {
        val temp = File(target.parentFile, target.name + ".tmp")
        var connection: HttpURLConnection? = null
        try {
            target.parentFile?.mkdirs()
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WardTube")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> copyLimited(input, output) }
            }
            // Never replace a good list with an error page.
            if (!looksLikeFilterList(temp)) return false
            return temp.renameTo(target)
        } catch (e: IOException) {
            Log.w(TAG, "Could not download $address", e)
            return false
        } finally {
            connection?.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw IOException("Filter list is too large")
            output.write(buffer, 0, read)
        }
    }

    private fun looksLikeFilterList(file: File): Boolean {
        if (file.length() < 200) return false
        val first = file.bufferedReader().use { it.readLine() }?.trim().orEmpty()
        return first.startsWith("[") || first.startsWith("!")
    }

    private fun cacheFile(list: FilterList) = File(File(context.filesDir, "filters"), list.id + ".txt")

    private fun assetPath(list: FilterList) = "adblock/lists/${list.id}.txt"

    private fun hasAsset(list: FilterList): Boolean = try {
        context.assets.open(assetPath(list)).close()
        true
    } catch (e: IOException) {
        false
    }

    private companion object {
        const val TAG = "FilterListManager"
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
