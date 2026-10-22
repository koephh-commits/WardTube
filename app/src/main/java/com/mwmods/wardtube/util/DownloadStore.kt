// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the Library filter sorts files by. [OTHER] (an unknown extension) only shows under "All". */
enum class MediaCategory { VIDEO, AUDIO, LYRICS, OTHER }

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "3gp")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")

/** One file in the Library. */
data class DownloadItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    /** Absolute path; only filled on Android 9 and older, where files are deleted from disk directly. */
    val path: String?,
    /** [name] normalised once, so typing in the search field does not recompute it per key press. */
    val searchKey: String,
) {
    val extensionUpper: String
        get() = if (name.contains('.')) name.substringAfterLast('.').uppercase(Locale.ROOT) else ""

    /** A subtitle / lyrics file: text, not media, so the Library shows a document icon instead of a play button. */
    val isSubtitle: Boolean
        get() = name.endsWith(".srt", ignoreCase = true) || name.endsWith(".lrc", ignoreCase = true)

    /** Video, audio or lyrics: decided by the extension first, then by the type the system recorded. */
    val category: MediaCategory
        get() {
            if (isSubtitle) return MediaCategory.LYRICS
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when {
                ext in VIDEO_EXTENSIONS || mimeType.startsWith("video/") -> MediaCategory.VIDEO
                ext in AUDIO_EXTENSIONS || mimeType.startsWith("audio/") -> MediaCategory.AUDIO
                else -> MediaCategory.OTHER
            }
        }
}

/**
 * A file that is still being written (see [DownloadStore.begin]): the bytes go to [output], and the file only joins the
 * Library once [DownloadStore.commit] is called; [DownloadStore.discard] throws a half-written file away.
 */
class PendingFile internal constructor(
    /** Android 10+: the MediaStore row, hidden from every other app and from the Library until it is committed. */
    internal val uri: Uri?,
    /** Android 9 and older: where the file will be, and the hidden file that is written meanwhile. */
    internal val target: File?,
    internal val work: File?,
    internal val mimeType: String,
    val output: OutputStream,
)

/**
 * Saves downloaded files into Download/WardTube and lists them again for the Library.
 * Each kind of file gets its own sub-folder: videos, audio and lyrics / subtitles (see [subFolderFor]).
 *
 * - Android 10+: MediaStore.Downloads (no permission needed for files this app created).
 * - Android 9 and older: a real file in the public Downloads folder, registered with the media scanner.
 */
object DownloadStore {

    const val FOLDER = "WardTube"

    // Sub-folder names are fixed Arabic names on purpose: they are names on the disk, so they must not change
    // when the phone's language does (or the files would be split between two folders).
    const val FOLDER_VIDEOS = "فيديوهات"
    const val FOLDER_AUDIO = "أصوات"
    const val FOLDER_LYRICS = "كلمات"

    /** Files saved from the browser screen that are not media (zip, pdf, apk...). */
    const val FOLDER_FILES = "ملفات"

    /**
     * The sub-folder of Download/WardTube a file belongs in: lyrics / subtitles (.srt / .lrc), videos, or audio.
     * Null (anything else) leaves the file in the main folder.
     */
    fun subFolderFor(fileName: String, mimeType: String): String? = when {
        fileName.endsWith(".srt", ignoreCase = true) || fileName.endsWith(".lrc", ignoreCase = true) -> FOLDER_LYRICS
        mimeType.startsWith("video/") -> FOLDER_VIDEOS
        mimeType.startsWith("audio/") -> FOLDER_AUDIO
        else -> null
    }

    // ---- Saving ----

    /** Copies [input] into the Library folder and returns the new file's Uri. Throws on failure. */
    fun save(context: Context, fileName: String, mimeType: String, input: InputStream): Uri {
        val safeName = sanitize(fileName)
        val subFolder = subFolderFor(safeName, mimeType)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, safeName, mimeType, subFolder, input)
        } else {
            saveLegacy(context, safeName, mimeType, subFolder, input)
        }
        remember(context, uri)
        return uri
    }

    // ---- Own record of saved files ----
    // The Library normally finds files by their folder. Every Uri MediaStore hands back when a file is saved is also
    // kept here, so a file (a subtitle: .srt / .lrc) whose path or type the system recorded differently still shows up.

    private const val RECORD_PREFS = "wardtube_saved_files"
    private const val RECORD_KEY = "uris"

    private fun remember(context: Context, uri: Uri) {
        if (uri.scheme != "content") return
        synchronized(this) {
            val prefs = context.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE)
            val all = HashSet(prefs.getStringSet(RECORD_KEY, emptySet()).orEmpty())
            if (all.add(uri.toString())) prefs.edit().putStringSet(RECORD_KEY, all).apply()
        }
    }

    private fun forget(context: Context, uris: Collection<String>) {
        if (uris.isEmpty()) return
        synchronized(this) {
            val prefs = context.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE)
            val all = HashSet(prefs.getStringSet(RECORD_KEY, emptySet()).orEmpty())
            if (all.removeAll(uris.toSet())) prefs.edit().putStringSet(RECORD_KEY, all).apply()
        }
    }

    private fun remembered(context: Context): List<String> = synchronized(this) {
        context.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE)
            .getStringSet(RECORD_KEY, emptySet()).orEmpty().toList()
    }

    private val RECORD_PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
    )

    private fun rowToItem(cursor: Cursor, uri: Uri, id: Long): DownloadItem? {
        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: return null
        return DownloadItem(
            id = id,
            uri = uri,
            name = name,
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                ?: "application/octet-stream",
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
            dateAddedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)),
            path = null,
            searchKey = normalizeForSearch(name),
        )
    }

    /** Adds the recorded files the folder query missed; drops records of files that no longer exist. */
    private fun addRecorded(context: Context, into: MutableMap<Long, DownloadItem>) {
        val gone = ArrayList<String>()
        for (text in remembered(context)) {
            val uri = Uri.parse(text)
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: continue
            if (into.containsKey(id)) continue
            val row: DownloadItem? = try {
                val cursor = context.contentResolver.query(uri, RECORD_PROJECTION, null, null, null) ?: continue
                cursor.use { c -> if (c.moveToFirst()) rowToItem(c, uri, id) else null }
            } catch (e: RuntimeException) {
                continue // could not ask (permission, provider hiccup): keep the record, try again next time
            }
            if (row != null) into[id] = row else gone.add(text) // no row: the file was deleted
        }
        forget(context, gone)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(
        context: Context, name: String, mimeType: String, subFolder: String?, input: InputStream,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER" + (subFolder?.let { "/$it" } ?: "")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the file")
            out.use { input.copyTo(it) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // never leave a half-written file behind
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context, name: String, mimeType: String, subFolder: String?, input: InputStream,
    ): Uri {
        val main = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        val dir = if (subFolder != null) File(main, subFolder) else main
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the folder")
        val target = uniqueFile(dir, name)
        try {
            target.outputStream().use { input.copyTo(it) }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return scan(context, target, mimeType)
    }

    /** Registers a file with MediaStore so that it shows up in the Library (and in other apps). */
    private fun scan(context: Context, target: File, mimeType: String): Uri {
        var scanned: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType)) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(target)
    }

    // ---- Saving while the bytes still arrive (the app's own file downloads) ----

    /**
     * Starts a file whose bytes are not all there yet (a download in progress): write to [PendingFile.output], then
     * call [commit]. Nothing is copied twice and no temporary copy fills the phone's internal storage, which matters
     * for big files. Throws on failure; nothing is left behind then.
     */
    fun begin(context: Context, fileName: String, mimeType: String, subFolder: String?): PendingFile {
        val safeName = sanitize(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            beginWithMediaStore(context, safeName, mimeType, subFolder)
        } else {
            beginLegacy(safeName, mimeType, subFolder)
        }
    }

    /** The file is complete: it becomes visible and the Library finds it. Returns its Uri. Throws on failure. */
    fun commit(context: Context, pending: PendingFile): Uri {
        pending.output.close()
        val uri = pending.uri
        if (uri != null) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
            remember(context, uri)
            return uri
        }
        val target = pending.target ?: throw IOException("Could not save the file")
        val work = pending.work ?: throw IOException("Could not save the file")
        if (!work.renameTo(target)) {
            work.delete()
            throw IOException("Could not save the file")
        }
        val scanned = scan(context, target, pending.mimeType)
        remember(context, scanned)
        return scanned
    }

    /** The download failed or was cancelled: whatever was written is deleted. */
    fun discard(context: Context, pending: PendingFile) {
        runCatching { pending.output.close() }
        pending.uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        pending.work?.let { runCatching { it.delete() } }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun beginWithMediaStore(context: Context, name: String, mimeType: String, subFolder: String?): PendingFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER" + (subFolder?.let { "/$it" } ?: "")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file")
        val out = try {
            resolver.openOutputStream(uri) ?: throw IOException("Could not open the file")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // never leave an empty row behind
            throw e
        }
        return PendingFile(uri, null, null, mimeType, out)
    }

    @Suppress("DEPRECATION")
    private fun beginLegacy(name: String, mimeType: String, subFolder: String?): PendingFile {
        val main = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        val dir = if (subFolder != null) File(main, subFolder) else main
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the folder")
        val target = uniqueFile(dir, name)
        // Written under a hidden name (the Library skips names that start with a dot) and renamed when complete, so
        // that a download cut short by the system never leaves a broken file in the folder.
        val work = File(dir, ".${target.name}.part")
        return PendingFile(null, target, work, mimeType, work.outputStream())
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (file.exists()) file = File(dir, "$base ($index)$ext").also { index++ }
        return file
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.ifEmpty { "download" }.take(120)
    }

    // ---- Listing ----

    /** Every file saved by this app, newest first. */
    @Suppress("DEPRECATION")
    fun query(context: Context): List<DownloadItem> {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (legacy) {
            val indexed = queryCollection(
                context, MediaStore.Files.getContentUri("external"), legacy = true,
                selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?",
                args = arrayOf("%/${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/%"),
            )
            return addUnindexed(context, indexed, legacy = true)
        }
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER%")
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        // The Downloads view can leave out files the system does not classify as media (subtitles: .srt / .lrc),
        // so the general Files collection is read as well. Both share the same ids: merge by id.
        val merged = LinkedHashMap<Long, DownloadItem>()
        val collections = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        for (collection in collections) {
            for (item in queryCollection(context, collection, legacy = false, selection = selection, args = args)) {
                merged.putIfAbsent(item.id, item)
            }
        }
        addRecorded(context, merged)
        val result = merged.values.sortedByDescending { it.dateAddedSec }
        // With "All files access" the folder can be read directly: a file the index lacks is registered now.
        return if (Permissions.canRequestAllFiles() && Permissions.hasAllFiles()) {
            addUnindexed(context, result, legacy = false)
        } else {
            result
        }
    }

    /**
     * Files that sit in the folder but that MediaStore does not list (typically a .srt / .lrc the media scanner
     * skipped). They are handed to the media scanner, and the Uri it returns makes them listable, openable and
     * deletable like every other row. Whatever the scanner cannot register stays hidden: there is no safe Uri for it.
     */
    @Suppress("DEPRECATION")
    private fun addUnindexed(context: Context, indexed: List<DownloadItem>, legacy: Boolean): List<DownloadItem> {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (dir.listFiles() == null) return indexed // folder unreadable (no permission) or missing
        // The main folder and its sub-folders (videos / audio / lyrics): files saved by older versions sit in the main one.
        val onDisk = dir.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.length() > 0 && !it.name.startsWith(".") }
            .toList()
        val known = indexed.mapTo(HashSet()) { it.name }
        val missing = onDisk.filter { it.name !in known }
        if (missing.isEmpty()) return indexed

        val scanned = ConcurrentHashMap<String, Uri>()
        val latch = CountDownLatch(missing.size)
        MediaScannerConnection.scanFile(context, missing.map { it.absolutePath }.toTypedArray(), null) { path, uri ->
            if (path != null && uri != null) scanned[path] = uri
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)

        val merged = LinkedHashMap<Long, DownloadItem>()
        for (item in indexed) merged[item.id] = item
        for (file in missing) {
            val uri = scanned[file.absolutePath] ?: continue
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: continue
            merged.putIfAbsent(
                id,
                DownloadItem(
                    id = id,
                    uri = uri,
                    name = file.name,
                    mimeType = "application/octet-stream",
                    sizeBytes = file.length(),
                    dateAddedSec = file.lastModified() / 1000,
                    path = if (legacy) file.absolutePath else null,
                    searchKey = normalizeForSearch(file.name),
                ),
            )
        }
        return merged.values.sortedByDescending { it.dateAddedSec }
    }

    @Suppress("DEPRECATION")
    private fun queryCollection(
        context: Context,
        collection: Uri,
        legacy: Boolean,
        selection: String,
        args: Array<String>,
    ): List<DownloadItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATA,
        )

        val items = ArrayList<DownloadItem>()
        try {
            context.contentResolver.query(
                collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    items += DownloadItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedSec = cursor.getLong(dateCol),
                        path = if (legacy) cursor.getString(dataCol) else null,
                        searchKey = normalizeForSearch(name),
                    )
                }
            }
        } catch (e: SecurityException) {
            // Read permission refused: nothing to show.
        } catch (e: RuntimeException) {
            // A collection this device does not answer for: the other one still lists the files.
        }
        return items
    }

    // ---- Search ----

    /** Lower-case, no Arabic diacritics, and the common Arabic letter variants unified. */
    fun normalizeForSearch(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase(Locale.ROOT)) {
            when (ch) {
                in '\u064B'..'\u065F', '\u0640' -> Unit // diacritics, tatweel
                'أ', 'إ', 'آ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }
}
