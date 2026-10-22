// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mwmods.wardtube.R
import com.mwmods.wardtube.download.DownloadJob
import com.mwmods.wardtube.download.JobStatus
import com.mwmods.wardtube.download.RequestHeaders
import com.mwmods.wardtube.util.DownloadItem
import com.mwmods.wardtube.util.MediaCategory
import java.text.DateFormat
import java.util.Date

/** What the Library list shows, top to bottom: a "Downloads" group (running / waiting / failed), then the saved files. */
sealed interface LibraryRow {
    /** Identifies a row across updates, so DiffUtil animates moves instead of redrawing everything. */
    val key: String

    data class Section(@StringRes val title: Int, val count: Int) : LibraryRow {
        override val key: String get() = "section:$title"
    }

    /** A download in the queue. [queuePosition] is 1 for the next waiting job, 2 for the one after, and 0 if it is not waiting. */
    data class Download(val job: DownloadJob, val queuePosition: Int) : LibraryRow {
        override val key: String get() = "job:${job.id}"
    }

    data class Saved(val item: DownloadItem) : LibraryRow {
        override val key: String get() = "file:${item.id}"
    }
}

/**
 * The Library list. Saved files: a picture (video frame / audio cover), tap a row to play, a menu to share / delete.
 * Downloads: a card with a live
 * progress ring and bar, speed and time left, and a cancel button (retry / dismiss once failed).
 *
 * Multi-select: while [selecting], every saved file shows a check box in place of its menu, a tap
 * ticks the file instead of playing it, and a long press ticks the whole range up to it ([MultiSelection]). A ticked
 * row is only redrawn through a payload, so the pictures do not reload on every tick.
 * The adapter only reports taps; the Activity decides what they mean.
 */
class LibraryAdapter(
    private val onFileClick: (DownloadItem) -> Unit,
    private val onFileLongClick: (DownloadItem) -> Unit,
    private val onShare: (DownloadItem) -> Unit,
    private val onDelete: (DownloadItem) -> Unit,
    private val onCancelJob: (DownloadJob) -> Unit,
    private val onRetryJob: (DownloadJob) -> Unit,
    private val onDismissJob: (DownloadJob) -> Unit,
    private val onShowJobError: (DownloadJob) -> Unit,
) : ListAdapter<LibraryRow, RecyclerView.ViewHolder>(DIFF) {

    private val dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    /** true = ticking mode: a tap ticks a file instead of playing it, and the check boxes are shown. */
    var selecting: Boolean = false
        private set

    private val selection = MultiSelection()

    /** Turns the ticking mode on or off. Turning it on ticks nothing; turning it off forgets every tick. */
    fun setSelecting(enabled: Boolean) {
        if (selecting == enabled) return
        selecting = enabled
        if (!enabled) selection.clear()
        redrawSelection()
    }

    /** Ticks or unticks one file and redraws its row only. */
    fun toggleSelection(item: DownloadItem) {
        selection.toggle(item)
        val position = currentList.indexOfFirst { it is LibraryRow.Saved && it.item.id == item.id }
        if (position >= 0) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    /** Ticks every file between the last ticked one and [target], in the order the list shows them. */
    fun selectRangeTo(target: DownloadItem) {
        selection.selectRange(currentList.mapNotNull { (it as? LibraryRow.Saved)?.item }, target)
        redrawSelection()
    }

    /** Forgets ticked files that are not in [existing] any more (deleted elsewhere). */
    fun retainSelection(existing: Set<Long>) {
        if (selection.retainOnly(existing)) redrawSelection()
    }

    /** The ticked files, in the order they were ticked. */
    fun selectedItems(): List<DownloadItem> = selection.items()

    fun selectedCount(): Int = selection.count

    private fun redrawSelection() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LibraryRow.Section -> TYPE_SECTION
        is LibraryRow.Download -> TYPE_DOWNLOAD
        is LibraryRow.Saved -> TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> SectionHolder(inflater.inflate(R.layout.item_library_section, parent, false))
            TYPE_DOWNLOAD -> DownloadHolder(inflater.inflate(R.layout.item_library_job, parent, false))
            else -> FileHolder(inflater.inflate(R.layout.item_library_file, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is LibraryRow.Section -> (holder as SectionHolder).bind(row)
            is LibraryRow.Download -> (holder as DownloadHolder).bind(row)
            is LibraryRow.Saved -> (holder as FileHolder).bind(row.item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (PAYLOAD_SELECTION in payloads) {
            // Only the ticks changed: the picture and the texts stay as they are.
            val row = getItem(position)
            if (holder is FileHolder && row is LibraryRow.Saved) holder.bindSelection(row.item)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    // ---- Group heading ----

    inner class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view as TextView

        fun bind(row: LibraryRow.Section) {
            label.text = itemView.context.getString(row.title) + " • " + row.count
        }
    }

    // ---- A saved file ----

    inner class FileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.ivFileIcon)
        private val thumb: ImageView = view.findViewById(R.id.ivThumb)
        private val name: TextView = view.findViewById(R.id.tvFileName)
        private val meta: TextView = view.findViewById(R.id.tvFileMeta)
        private val more: ImageButton = view.findViewById(R.id.btnMore)
        private val tick: CheckBox = view.findViewById(R.id.cbSelected)

        /** An audio cover is square: it sits in the middle of the 16:9 box instead of being cut to fill it. */
        private val squareSize = (63 * view.resources.displayMetrics.density).toInt()

        fun bind(item: DownloadItem) {
            val category = item.category
            // The icon is what the file is; it shows until the picture is ready, and stays when there is none.
            // Subtitle files cannot be played: they open in a text viewer, so they get a document icon.
            icon.setImageResource(
                when (category) {
                    MediaCategory.LYRICS -> R.drawable.ic_subtitles_24dp
                    MediaCategory.AUDIO -> R.drawable.ic_music_note_24dp
                    MediaCategory.VIDEO -> R.drawable.ic_videocam_24dp
                    MediaCategory.OTHER -> R.drawable.ic_file_24dp
                }
            )
            icon.contentDescription =
                if (category == MediaCategory.LYRICS || category == MediaCategory.OTHER) null else itemView.context.getString(R.string.play)
            icon.visibility = View.VISIBLE
            thumb.visibility = View.GONE

            if (category == MediaCategory.VIDEO || category == MediaCategory.AUDIO) {
                val params = thumb.layoutParams
                params.width = if (category == MediaCategory.AUDIO) squareSize else ViewGroup.LayoutParams.MATCH_PARENT
                thumb.layoutParams = params
                ThumbnailLoader.load(itemView.context, item, thumb) { hasPicture ->
                    thumb.visibility = if (hasPicture) View.VISIBLE else View.GONE
                    icon.visibility = if (hasPicture) View.GONE else View.VISIBLE
                }
            } else {
                ThumbnailLoader.cancel(thumb)
            }

            name.text = item.name
            meta.text = buildMeta(item)
            itemView.setOnClickListener { onFileClick(item) }
            itemView.setOnLongClickListener {
                onFileLongClick(item)
                true
            }
            bindSelection(item)
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menu.add(0, MENU_SHARE, 0, R.string.share)
                    menu.add(0, MENU_DELETE, 1, R.string.delete)
                    setOnMenuItemClickListener { entry ->
                        when (entry.itemId) {
                            MENU_SHARE -> onShare(item)
                            MENU_DELETE -> onDelete(item)
                        }
                        true
                    }
                }.show()
            }
        }

        /** A ticked row is tinted and shows a ticked box; while selecting, the box replaces the menu button. */
        fun bindSelection(item: DownloadItem) {
            val ticked = selection.isSelected(item.id)
            itemView.isActivated = ticked
            tick.isChecked = ticked
            tick.visibility = if (selecting) View.VISIBLE else View.GONE
            more.visibility = if (selecting) View.GONE else View.VISIBLE
        }

        /** "FORMAT • size • date"; unknown parts are left out. */
        private fun buildMeta(item: DownloadItem): String {
            val parts = ArrayList<String>(3)
            if (item.extensionUpper.isNotEmpty()) parts.add(item.extensionUpper)
            if (item.sizeBytes > 0) parts.add(Formatter.formatShortFileSize(itemView.context, item.sizeBytes))
            if (item.dateAddedSec > 0) parts.add(dateFormat.format(Date(item.dateAddedSec * 1000L)))
            return parts.joinToString(" • ")
        }
    }

    // ---- A download in the queue ----

    inner class DownloadHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ring: CircularProgressIndicator = view.findViewById(R.id.progressRing)
        private val bar: LinearProgressIndicator = view.findViewById(R.id.progressBar)
        private val icon: ImageView = view.findViewById(R.id.ivJobIcon)
        private val title: TextView = view.findViewById(R.id.tvJobTitle)
        private val status: TextView = view.findViewById(R.id.tvJobStatus)
        private val meta: TextView = view.findViewById(R.id.tvJobMeta)
        private val cancel: ImageButton = view.findViewById(R.id.btnJobAction)
        private val retry: ImageButton = view.findViewById(R.id.btnJobRetry)
        private val dismiss: ImageButton = view.findViewById(R.id.btnJobDismiss)

        /** Called again on every progress update; the same views are reused, so the bar animates instead of jumping. */
        fun bind(row: LibraryRow.Download) {
            val job = row.job
            val context = itemView.context
            val failed = job.status == JobStatus.FAILED
            val waiting = job.status == JobStatus.QUEUED

            title.text = job.title.ifBlank { RequestHeaders.display(job.url) }
            itemView.alpha = if (waiting) 0.75f else 1f

            // Icon: what it is (audio / video), or why it is not moving (waiting / failed).
            icon.setImageResource(
                when {
                    failed -> R.drawable.ic_error_24dp
                    waiting -> R.drawable.ic_hourglass_24dp
                    job.options.isSubsOnly -> R.drawable.ic_subtitles_24dp
                    job.options.isAudio -> R.drawable.ic_music_note_24dp
                    job.options.isFile -> R.drawable.ic_file_24dp
                    else -> R.drawable.ic_videocam_24dp
                }
            )
            val accent = ContextCompat.getColor(context, if (failed) R.color.jobError else R.color.brandText)
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(accent))
            status.setTextColor(accent)

            // Buttons: cancel while it is in the queue; retry + dismiss once it failed.
            cancel.visibility = if (failed) View.GONE else View.VISIBLE
            retry.visibility = if (failed) View.VISIBLE else View.GONE
            dismiss.visibility = if (failed) View.VISIBLE else View.GONE
            cancel.setOnClickListener { onCancelJob(job) }
            retry.setOnClickListener { onRetryJob(job) }
            dismiss.setOnClickListener { onDismissJob(job) }
            // A failed card opens the whole error (the card itself shows only its first lines).
            val hasError = failed && job.error.isNotBlank()
            if (hasError) {
                itemView.setOnClickListener { onShowJobError(job) }
            } else {
                itemView.setOnClickListener(null)
                itemView.isClickable = false
            }

            val details = ArrayList<String>(4)
            // A plain file shows its own extension (PDF, ZIP...); the others the format that was chosen.
            val label = if (job.options.isFile) job.title.substringAfterLast('.', "").uppercase().take(5) else job.options.shortLabel
            if (label.isNotEmpty()) details.add(label)

            when (job.status) {
                JobStatus.QUEUED -> {
                    status.text = if (row.queuePosition <= 1) {
                        context.getString(R.string.jobWaitingNext)
                    } else {
                        context.getString(R.string.jobWaitingPos, row.queuePosition)
                    }
                    showProgress(0)
                }
                JobStatus.STARTING -> {
                    status.setText(R.string.downloadPreparing)
                    showUnknownProgress()
                }
                JobStatus.UPDATING -> {
                    status.setText(R.string.engineUpdating)
                    showUnknownProgress()
                }
                JobStatus.PROCESSING -> {
                    status.setText(R.string.downloadProcessing)
                    showUnknownProgress()
                }
                JobStatus.DOWNLOADING -> {
                    if (job.percent >= 0) {
                        status.text = if (job.parts > 1) {
                            context.getString(R.string.downloadProgressParts, job.percent, job.part, job.parts)
                        } else {
                            context.getString(R.string.downloadProgress, job.percent)
                        }
                        showProgress(job.percent)
                    } else {
                        // A file whose size the server did not tell has no percentage, but it is moving.
                        status.setText(if (job.size.isNotEmpty()) R.string.downloadRunning else R.string.downloadPreparing)
                        showUnknownProgress()
                    }
                    if (job.speed.isNotEmpty()) details.add(job.speed)
                    if (job.eta.isNotEmpty()) details.add(context.getString(R.string.downloadEta, job.eta))
                    if (job.size.isNotEmpty()) details.add(job.size)
                }
                JobStatus.FAILED -> {
                    status.setText(if (job.error.isNotBlank()) R.string.downloadFailedTap else R.string.downloadFailed)
                    ring.visibility = View.GONE
                    bar.visibility = View.GONE
                }
            }

            meta.text = details.joinToString(" • ") +
                if (failed && job.error.isNotBlank()) "\n" + job.error else ""
        }

        private fun showProgress(percent: Int) {
            ring.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            setDeterminate(ring, percent)
            setDeterminate(bar, percent)
        }

        private fun showUnknownProgress() {
            ring.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            setIndeterminate(ring)
            setIndeterminate(bar)
        }

        /** Animates to the new value; also switches an indeterminate indicator over smoothly. */
        private fun setDeterminate(indicator: BaseProgressIndicator<*>, percent: Int) {
            indicator.setProgressCompat(percent, true)
        }

        private fun setIndeterminate(indicator: BaseProgressIndicator<*>) {
            if (!indicator.isIndeterminate) indicator.isIndeterminate = true
        }
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_DOWNLOAD = 1
        const val TYPE_FILE = 2

        const val MENU_SHARE = 1
        const val MENU_DELETE = 2

        /** Payload of a redraw that only changes the ticks of the file rows. */
        const val PAYLOAD_SELECTION = "selection"

        val DIFF = object : DiffUtil.ItemCallback<LibraryRow>() {
            override fun areItemsTheSame(oldItem: LibraryRow, newItem: LibraryRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: LibraryRow, newItem: LibraryRow) = oldItem == newItem
        }
    }
}
