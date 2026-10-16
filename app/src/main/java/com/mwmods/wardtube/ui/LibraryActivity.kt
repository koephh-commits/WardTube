// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.download.DownloadEvents
import com.mwmods.wardtube.download.DownloadJob
import com.mwmods.wardtube.download.DownloadService
import com.mwmods.wardtube.download.JobStatus
import com.mwmods.wardtube.download.MediaKind
import com.mwmods.wardtube.util.DownloadItem
import com.mwmods.wardtube.util.DownloadStore
import com.mwmods.wardtube.util.MediaCategory
import com.mwmods.wardtube.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Library: everything that was downloaded from inside the app (Download/WardTube, and the video / audio / lyrics
 * folders inside it), newest first, with search by file name and a filter (All / Video / Audio / Lyrics).
 * Tap a file to play it, or use its menu to share / delete it.
 *
 * Several files at once: a long press on a file, or "Select multiple" in the menu,
 * turns on the ticking mode. A tap ticks / unticks a file, a long press on another file ticks the whole range up to
 * it, and the bar at the bottom shares or deletes everything ticked. Back ends the mode (before it closes the search).
 *
 * The search field is hidden until the magnifier in the toolbar is tapped (the way the playlist search of the web
 * Wardio works): it opens the field and the keyboard; the magnifier again, or Back, closes it and clears the text.
 *
 * Above the files sits the download queue: the job that is downloading shows a live progress ring and bar
 * (percent, speed, time left), the jobs behind it show "waiting" and move up as the ones before them finish.
 * The queue comes from DownloadService (a separate process) through DownloadEvents broadcasts; a failed
 * job stays on screen with Retry / Dismiss.
 *
 * Deleting really removes the file from the phone, and a row only disappears once that succeeded:
 * - Android 11+: a single system confirmation (MediaStore.createDeleteRequest) when the app does not own the file.
 * - Android 10: direct delete; files from an earlier install need the system's approval dialog.
 * - Android 9 and older: the file is deleted from disk (needs WRITE_EXTERNAL_STORAGE).
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var adapter: LibraryAdapter
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyMessage: TextView
    private lateinit var progress: View
    private lateinit var contentArea: ViewGroup
    private lateinit var searchLayout: View
    private lateinit var searchField: TextInputEditText
    private lateinit var selectionBar: View
    private lateinit var shareSelected: TextView
    private lateinit var deleteSelected: TextView

    private lateinit var filterChips: List<Chip>

    private var allFiles: List<DownloadItem> = emptyList()
    private var searchQuery = ""
    private var searchOpen = false

    /** The chosen filter chip: a kind of file, or null for "All". */
    private var filter: MediaCategory? = null
    private var loadedOnce = false
    private var loadGeneration = 0

    /** The live queue as last reported by the download service. */
    private var jobs: List<DownloadJob> = emptyList()

    /** Failed downloads stay here (with Retry / Dismiss) after the service has dropped them. */
    private val failedJobs = LinkedHashMap<String, DownloadJob>()

    private val handler = Handler(Looper.getMainLooper())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onJobsUpdated(DownloadEvents.decode(intent?.getStringExtra(DownloadEvents.EXTRA_JOBS)))
        }
    }

    /** The service reports every couple of seconds; silence means its process died (it can on low-memory phones). */
    private val serviceSilent = Runnable {
        if (jobs.isNotEmpty()) {
            jobs = emptyList()
            refreshList()
            scheduleFileReload()
        }
    }

    // No permission check here: since Android 10 the files this app saved are always visible to it, so a user who
    // declined the media permission must still see a finished download appear. Without any access the query is empty.
    private val reloadFiles = Runnable { loadFiles(showSpinner = false) }

    /** Files waiting for the answer of a system question (permission / approval) before they are deleted. */
    private var pendingDelete: List<DownloadItem> = emptyList()

    /** Android 10 asks about each file it does not own separately: the ones still to ask about, one after another. */
    private val recoveryQueue = ArrayList<Pair<DownloadItem, IntentSender>>()

    /** Back closes the open search field first, and only then leaves the screen. */
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setSearchOpen(false)
        }
    }

    /** Back ends the ticking mode first. It is added after [searchBackCallback], so it is asked before it. */
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setSelectionMode(false)
        }
    }

    /** Ask once; whatever the answer, show what is visible. */
    private val readPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadFiles() }

    private val writePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val items = pendingDelete
            pendingDelete = emptyList()
            if (items.isEmpty()) return@registerForActivityResult
            if (granted) deleteItems(items) else toast(getString(R.string.storagePermissionDenied))
        }

    private val systemDelete =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val items = pendingDelete
            pendingDelete = emptyList()
            val approved = result.resultCode == Activity.RESULT_OK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The system dialog already deleted every file when the user agreed.
                if (approved && items.isNotEmpty()) onDeleted(items)
            } else {
                // Android 10: the approval only grants access to that file; delete it again, then ask about the next.
                if (approved) {
                    val done = items.filter { attemptDelete(it) is DeleteOutcome.Done }
                    if (done.isNotEmpty()) onDeleted(done)
                    if (done.size < items.size) toast(getString(R.string.deleteFailed, items.size - done.size))
                }
                launchNextRecovery()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        toolbar = findViewById(R.id.toolbar)
        list = findViewById(R.id.rvLibrary)
        emptyState = findViewById(R.id.emptyState)
        emptyMessage = findViewById(R.id.tvEmptyMessage)
        progress = findViewById(R.id.progressLoading)
        contentArea = findViewById(R.id.libraryContent)
        searchLayout = findViewById(R.id.searchInputLayout)
        searchField = findViewById(R.id.etSearch)
        selectionBar = findViewById(R.id.selectionBar)
        shareSelected = findViewById(R.id.btnShareSelected)
        deleteSelected = findViewById(R.id.btnDeleteSelected)
        setUpEdgeToEdge(findViewById(R.id.root), list)
        // The bar sits on the bottom edge of the screen: keep its buttons clear of the navigation bar.
        val barBottomPadding = selectionBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(selectionBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = barBottomPadding + bars.bottom)
            insets
        }

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.menu_library)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> {
                    setSearchOpen(!searchOpen)
                    true
                }
                R.id.action_multi_select -> {
                    setSelectionMode(!adapter.selecting)
                    true
                }
                R.id.action_all_files -> {
                    openAllFilesSettings()
                    true
                }
                else -> false
            }
        }
        updateAllFilesMenu()
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
        onBackPressedDispatcher.addCallback(this, selectionBackCallback)

        adapter = LibraryAdapter(
            onFileClick = ::onFileClicked,
            onFileLongClick = ::onFileLongClicked,
            onShare = ::shareItem,
            onDelete = { item -> confirmDelete(listOf(item)) },
            onCancelJob = ::cancelJob,
            onRetryJob = ::retryJob,
            onDismissJob = ::dismissJob,
        )
        list.adapter = adapter
        // Progress updates rebind the same row in place; a cross-fade on every tick would make the card flicker.
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // The filter survives a rotation / language change: it is saved with the screen's state.
        filter = savedInstanceState?.getString(STATE_FILTER)
            ?.let { saved -> runCatching { MediaCategory.valueOf(saved) }.getOrNull() }
            ?.takeIf { it != MediaCategory.OTHER }
        filterChips = listOf(R.id.chipAll, R.id.chipVideo, R.id.chipAudio, R.id.chipLyrics).map { findViewById<Chip>(it) }
        selectChip(chipIdFor(filter))
        for (chip in filterChips) {
            chip.setOnClickListener {
                filter = categoryFor(chip.id)
                selectChip(chip.id) // also re-checks the chip that was just tapped while it was already the chosen one
                refreshList(scrollToTop = true)
            }
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = DownloadStore.normalizeForSearch(s?.toString().orEmpty())
                refreshList(scrollToTop = true)
            }
        })
        // The keyboard's search key only puts the keyboard away: the results are already on screen.
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        shareSelected.setOnClickListener { shareItems(adapter.selectedItems()) }
        deleteSelected.setOnClickListener { confirmDelete(adapter.selectedItems()) }
        // A search that was open stays open (its text comes back with the field) after a rotation / language change.
        if (savedInstanceState?.getBoolean(STATE_SEARCH_OPEN) == true) {
            setSearchOpen(true, animate = false, showKeyboard = false)
        }

        if (Permissions.hasReadMedia(this)) loadFiles() else readPermission.launch(Permissions.readMedia())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, filter?.name)
        outState.putBoolean(STATE_SEARCH_OPEN, searchOpen)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(DownloadEvents.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Ask a running download service for its queue. No answer = nothing is downloading.
        sendBroadcast(Intent(DownloadEvents.ACTION_REQUEST_STATE).setPackage(packageName))
        if (jobs.isNotEmpty()) armSilenceTimer()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        handler.removeCallbacks(serviceSilent)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // A download may have finished while the screen was in the background.
        updateAllFilesMenu()
        if (loadedOnce) loadFiles(showSpinner = false)
    }

    // ---- "All files access" (Android 11+) ----

    private fun updateAllFilesMenu() {
        toolbar.menu.findItem(R.id.action_all_files)?.isVisible =
            Permissions.canRequestAllFiles() && !Permissions.hasAllFiles()
    }

    private fun openAllFilesSettings() {
        val appPage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(appPage)
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /** Asked once, after the first listing: the menu item stays available for later. */
    private fun offerAllFilesAccess() {
        val prefs = Prefs(this)
        if (!Permissions.canRequestAllFiles() || Permissions.hasAllFiles() || prefs.allFilesAsked) return
        prefs.allFilesAsked = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.allFilesTitle)
            .setMessage(R.string.allFilesMessage)
            .setPositiveButton(R.string.allFilesAllow) { _, _ -> openAllFilesSettings() }
            .setNegativeButton(R.string.allFilesLater, null)
            .show()
    }

    // ---- Loading ----

    /** [showSpinner] is off for the quiet reloads after a download finished, so the list does not flash. */
    private fun loadFiles(showSpinner: Boolean = true) {
        if (showSpinner) progress.visibility = View.VISIBLE
        val generation = ++loadGeneration
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { DownloadStore.query(this@LibraryActivity) }
            progress.visibility = View.GONE
            // A newer load was started meanwhile: its result is the fresher one.
            if (generation != loadGeneration) return@launch
            val first = !loadedOnce
            loadedOnce = true
            allFiles = files
            if (adapter.selecting) {
                // A ticked file that is gone (deleted elsewhere) cannot be shared or deleted any more.
                adapter.retainSelection(files.mapTo(HashSet()) { it.id })
                onSelectionChanged()
            }
            refreshList()
            if (first) offerAllFilesAccess()
        }
    }

    // ---- Search ----

    /**
     * Opens or closes the search field. Opening shows it under the toolbar and brings up the keyboard; closing puts the
     * keyboard away and clears the text, so the whole list comes back (the same as closing the search in Wardio).
     * [animate] is off when the state is restored after a rotation, [showKeyboard] too.
     */
    private fun setSearchOpen(open: Boolean, animate: Boolean = true, showKeyboard: Boolean = true) {
        if (open == searchOpen) return
        searchOpen = open
        searchBackCallback.isEnabled = open
        if (animate) {
            TransitionManager.beginDelayedTransition(contentArea, AutoTransition().setDuration(SEARCH_ANIMATION_MS))
        }
        if (open) {
            searchLayout.visibility = View.VISIBLE
            if (showKeyboard) {
                searchField.requestFocus()
                // After the layout pass: the field must be on screen before the keyboard can attach to it.
                searchField.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(searchField, 0) }
            }
        } else {
            hideKeyboard()
            searchLayout.visibility = View.GONE
            if (!searchField.text.isNullOrEmpty()) searchField.setText("")
        }
        updateSearchIcon()
    }

    /** The magnifier is highlighted while the search field is open. */
    private fun updateSearchIcon() {
        toolbar.menu.findItem(R.id.action_search)?.setIcon(
            if (searchOpen) R.drawable.ic_search_active_24dp else R.drawable.ic_search_24dp
        )
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    // ---- Selecting several files ----

    /** A tap plays a file, or while selecting ticks / unticks it. */
    private fun onFileClicked(item: DownloadItem) {
        if (adapter.selecting) {
            adapter.toggleSelection(item)
            onSelectionChanged()
        } else {
            playItem(item)
        }
    }

    /** A long press starts the ticking mode with this file ticked; while selecting, it ticks the range up to it. */
    private fun onFileLongClicked(item: DownloadItem) {
        if (adapter.selecting) {
            adapter.selectRangeTo(item)
        } else {
            setSelectionMode(true)
            adapter.toggleSelection(item)
        }
        onSelectionChanged()
    }

    private fun setSelectionMode(enabled: Boolean) {
        adapter.setSelecting(enabled)
        selectionBackCallback.isEnabled = enabled
        toolbar.menu.findItem(R.id.action_multi_select)
            ?.setTitle(if (enabled) R.string.libraryMultiSelectOff else R.string.libraryMultiSelectOn)
        onSelectionChanged()
    }

    /** After every change of the ticks: the title and the share / delete bar follow the count. */
    private fun onSelectionChanged() {
        updateToolbar()
        val count = adapter.selectedCount()
        val showBar = adapter.selecting && count > 0
        selectionBar.visibility = if (showBar) View.VISIBLE else View.GONE
        if (showBar) {
            shareSelected.text = getString(R.string.librarySelectionShare, count)
            deleteSelected.text = getString(R.string.librarySelectionDelete, count)
        }
    }

    /** Normally "Library" with how many files (and downloads); while selecting, how many are ticked and a hint. */
    private fun updateToolbar() {
        if (adapter.selecting) {
            val count = adapter.selectedCount()
            toolbar.title = if (count == 0) {
                getString(R.string.librarySelectFiles)
            } else {
                resources.getQuantityString(R.plurals.librarySelectedCount, count, count)
            }
            toolbar.subtitle = getString(R.string.libraryRangeHint)
            return
        }
        toolbar.title = getString(R.string.library)
        val downloading = jobs.size
        toolbar.subtitle = when {
            allFiles.isEmpty() && downloading == 0 -> null
            downloading == 0 -> getString(R.string.libraryCount, allFiles.size)
            else -> getString(R.string.libraryCountDownloading, allFiles.size, downloading)
        }
    }

    // ---- Filter chips ----

    /** Exactly one chip is checked: the one with [chipId]. */
    private fun selectChip(chipId: Int) {
        for (chip in filterChips) chip.isChecked = chip.id == chipId
    }

    private fun categoryFor(chipId: Int): MediaCategory? = when (chipId) {
        R.id.chipVideo -> MediaCategory.VIDEO
        R.id.chipAudio -> MediaCategory.AUDIO
        R.id.chipLyrics -> MediaCategory.LYRICS
        else -> null
    }

    private fun chipIdFor(category: MediaCategory?): Int = when (category) {
        MediaCategory.VIDEO -> R.id.chipVideo
        MediaCategory.AUDIO -> R.id.chipAudio
        MediaCategory.LYRICS -> R.id.chipLyrics
        else -> R.id.chipAll
    }

    /** The kind of file a download will produce, so the queue follows the chosen filter too. */
    private fun categoryOf(job: DownloadJob): MediaCategory = when (job.options.kind) {
        MediaKind.VIDEO -> MediaCategory.VIDEO
        MediaKind.AUDIO -> MediaCategory.AUDIO
        MediaKind.SUBS -> MediaCategory.LYRICS
    }

    private fun showsJob(job: DownloadJob): Boolean = filter == null || categoryOf(job) == filter

    private fun refreshList(scrollToTop: Boolean = false) {
        val files = allFiles.filter { file ->
            (filter == null || file.category == filter) && (searchQuery.isEmpty() || searchQuery in file.searchKey)
        }
        val rows = buildRows(files)
        adapter.submitList(rows) { if (scrollToTop) list.scrollToPosition(0) }

        updateToolbar()
        emptyState.visibility = if (rows.isEmpty() && loadedOnce) View.VISIBLE else View.GONE
        val narrowed = searchQuery.isNotEmpty() || filter != null
        emptyMessage.setText(if (narrowed) R.string.libraryNoResults else R.string.libraryEmpty)
    }

    /** Downloads first (the running one, then the waiting ones in order, then failures), then the saved files. */
    private fun buildRows(files: List<DownloadItem>): List<LibraryRow> {
        val rows = ArrayList<LibraryRow>(files.size + jobs.size + failedJobs.size + 2)
        // While searching, the list is about files only.
        // The queue follows the chosen filter: with "Audio" on, only the audio downloads are shown.
        val shownFailed = failedJobs.values.filter { showsJob(it) }
        val shownJobs = jobs.filter { showsJob(it) }
        val showQueue = searchQuery.isEmpty() && (shownJobs.isNotEmpty() || shownFailed.isNotEmpty())
        if (showQueue) {
            rows += LibraryRow.Section(R.string.libraryDownloads, shownJobs.size + shownFailed.size)
            // The position counts the whole queue, also the jobs hidden by the filter: it is the real place in line.
            var waiting = 0
            for (job in jobs) {
                val position = if (job.status == JobStatus.QUEUED) ++waiting else 0
                if (showsJob(job)) rows += LibraryRow.Download(job, position)
            }
            for (job in shownFailed) rows += LibraryRow.Download(job, 0)
            if (files.isNotEmpty()) rows += LibraryRow.Section(R.string.libraryFiles, files.size)
        }
        for (file in files) rows += LibraryRow.Saved(file)
        return rows
    }

    // ---- Download queue ----

    private fun onJobsUpdated(reported: List<DownloadJob>) {
        val previousIds = jobs.map { it.id }
        // Failures stay on screen until the user retries or dismisses them.
        for (job in reported) if (job.status == JobStatus.FAILED) failedJobs[job.id] = job
        jobs = reported.filter { it.status != JobStatus.FAILED }

        handler.removeCallbacks(serviceSilent)
        if (jobs.isNotEmpty()) armSilenceTimer()

        refreshList()
        // A job that left the queue without failing has finished (or was cancelled): its file may be new.
        val currentIds = jobs.map { it.id }.toSet()
        if (previousIds.any { it !in currentIds && it !in failedJobs }) scheduleFileReload()
    }

    private fun armSilenceTimer() {
        handler.removeCallbacks(serviceSilent)
        handler.postDelayed(serviceSilent, SILENCE_TIMEOUT_MS)
    }

    private fun scheduleFileReload() {
        handler.removeCallbacks(reloadFiles)
        handler.postDelayed(reloadFiles, 400L)
    }

    private fun cancelJob(job: DownloadJob) = DownloadService.cancel(this, job.id)

    private fun retryJob(job: DownloadJob) {
        if (!Permissions.hasLegacyWrite(this)) {
            toast(getString(R.string.storagePermissionDenied))
            return
        }
        failedJobs.remove(job.id)
        DownloadService.start(this, job.url, job.options, job.title, job.item)
        refreshList()
    }

    private fun dismissJob(job: DownloadJob) {
        failedJobs.remove(job.id)
        refreshList()
    }

    // ---- Play / share ----

    private fun playItem(item: DownloadItem) {
        // Subtitle files are stored with a generic type; text viewers understand them as plain text.
        val isSubtitle = item.isSubtitle
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, if (isSubtitle) "text/plain" else item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.noAppToOpen))
        }
    }

    private fun shareItem(item: DownloadItem) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mimeType)
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_with)))
    }

    /** One file goes the usual way; several are sent together, with one type if they all agree (all videos, say). */
    private fun shareItems(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            shareItem(items.first())
            return
        }
        val types = items.map { it.mimeType }.distinct()
        val families = items.map { it.mimeType.substringBefore('/') }.distinct()
        val type = when {
            types.size == 1 -> types.first()
            families.size == 1 -> families.first() + "/*"
            else -> "*/*"
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(if (type.isBlank() || type.startsWith("/")) "*/*" else type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_with)))
    }

    // ---- Delete ----

    /** One file: its name is in the question. Several: only how many. */
    private fun confirmDelete(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        val dialog = MaterialAlertDialogBuilder(this)
        if (items.size == 1) {
            dialog.setTitle(R.string.deleteConfirmTitle)
                .setMessage("${items.first().name}\n\n${getString(R.string.deleteConfirmMessage)}")
        } else {
            dialog.setTitle(R.string.deleteConfirmManyTitle)
                .setMessage(resources.getQuantityString(R.plurals.deleteConfirmManyMessage, items.size, items.size))
        }
        dialog
            .setPositiveButton(R.string.delete) { _, _ -> deleteItems(items) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Deletes every file the app may delete by itself (on a worker thread), then asks the system about the rest:
     * Android 11+ asks once for all of them; Android 10 asks file by file ([recoveryQueue]).
     */
    private fun deleteItems(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        if (!Permissions.hasLegacyWrite(this)) {
            pendingDelete = items
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        lifecycleScope.launch {
            val deleted = ArrayList<DownloadItem>()
            val needsApproval = ArrayList<DownloadItem>()
            val recoverable = ArrayList<Pair<DownloadItem, IntentSender>>()
            var failed = 0
            withContext(Dispatchers.IO) {
                for (item in items) {
                    when (val outcome = attemptDelete(item)) {
                        is DeleteOutcome.Done -> deleted += item
                        is DeleteOutcome.Failed -> failed++
                        is DeleteOutcome.NeedsUser -> {
                            val sender = outcome.sender
                            if (sender != null) recoverable += item to sender else needsApproval += item
                        }
                    }
                }
            }
            if (deleted.isNotEmpty()) onDeleted(deleted)
            if (failed > 0) toast(getString(R.string.deleteFailed, failed))
            if (needsApproval.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDelete = needsApproval
                val request = MediaStore.createDeleteRequest(contentResolver, needsApproval.map { it.uri })
                systemDelete.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
            recoveryQueue.clear()
            recoveryQueue.addAll(recoverable)
            launchNextRecovery()
        }
    }

    /** Android 10: the system question for the next file in [recoveryQueue], one at a time. */
    private fun launchNextRecovery() {
        if (recoveryQueue.isEmpty()) return
        val (item, sender) = recoveryQueue.removeAt(0)
        pendingDelete = listOf(item)
        systemDelete.launch(IntentSenderRequest.Builder(sender).build())
    }

    private sealed interface DeleteOutcome {
        object Done : DeleteOutcome
        object Failed : DeleteOutcome

        /** The app does not own the file. [sender] is the Android 10 approval; null on Android 11+ (one request for all). */
        class NeedsUser(val sender: IntentSender?) : DeleteOutcome
    }

    private fun attemptDelete(item: DownloadItem): DeleteOutcome {
        return try {
            // Android 9 and older: remove the real file first, then its MediaStore row.
            item.path?.let { File(it).delete() }
            contentResolver.delete(item.uri, null, null)
            if (item.path != null && File(item.path).exists()) DeleteOutcome.Failed else DeleteOutcome.Done
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> DeleteOutcome.NeedsUser(null)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    DeleteOutcome.NeedsUser(e.userAction.actionIntent.intentSender)
                else -> DeleteOutcome.Failed
            }
        }
    }

    /** [items] are really gone from the phone: they leave the list, and the ticking mode ends. */
    private fun onDeleted(items: List<DownloadItem>) {
        val ids = items.map { it.id }.toSet()
        allFiles = allFiles.filter { it.id !in ids }
        if (adapter.selecting) setSelectionMode(false)
        refreshList()
        toast(resources.getQuantityString(R.plurals.deletedCount, items.size, items.size))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        /** The service reports every 2 s while it is alive. */
        const val SILENCE_TIMEOUT_MS = 6000L

        const val STATE_FILTER = "libraryFilter"
        const val STATE_SEARCH_OPEN = "librarySearchOpen"

        /** How long the search field takes to slide open / shut. */
        const val SEARCH_ANIMATION_MS = 200L
    }
}
