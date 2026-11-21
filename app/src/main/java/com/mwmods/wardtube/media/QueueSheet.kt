// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.ImageViewCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.mwmods.wardtube.R
import com.mwmods.wardtube.ui.MaxHeightRecyclerView
import com.mwmods.wardtube.ui.ThumbnailLoader

/**
 * The queue of the built-in player: the sheet that the queue button of the player's title bar opens (`bottom_sheet_queue.xml`).
 *
 * It shows what the player has in its queue. The item that is playing is alone in a card of its own at the top of the sheet,
 * outside the list, so it never moves while the list scrolls (a soft red card, moving bars over its picture while it plays;
 * a tap on it pauses / resumes it, the x button removes it). Under the card is the list of what comes after it, under a small
 * "Next" label, with the real next one marked "Up next". The queue goes round and round ([QueueCycle]): the playing item is
 * always the first of the queue, what comes next is under it, and what was played is at the bottom, so nothing disappears from
 * the list: when the last one has played, the ones at the bottom come again from the first. With shuffle the order of the queue
 * says nothing about what comes next (the player picks a random order, a new one every round), so then every other item is
 * listed, without the label, and only the one the player goes to next is marked. The list works:
 *  - a tap on a row plays it;
 *  - the handle at the end of a row drags it to another place;
 *  - no row can be dragged past the playing item's place (the ones before it stay before it, the ones after it stay after it);
 *  - the x button, or swiping the row away, removes it (the last item cannot be removed: the queue is never left empty);
 *  - the Library button of the header makes the whole Library (videos and songs) the queue, and a second tap brings the
 *    previous queue back ([LibraryQueue] does the work; the sheet only draws the button and shows the result).
 *
 * It owns nothing: every change goes straight to the player through the [MediaController] the screen already has, and the
 * sheet listens to that same controller, so it always shows the real queue (also when the notification or the headset moves
 * to the next song while the sheet is open).
 */
class QueueSheet(private val activity: AppCompatActivity, private val library: LibraryQueue) {

    /** One line of the list: the file's address (the media id), its name, and the file the picture is taken from. */
    private class Row(val key: String, val title: String, val uri: Uri?)

    /** The views of one row, and which look its background has now (so it is only changed when it differs). */
    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ShapeableImageView = view.findViewById(R.id.queueCover)
        val glyph: View = view.findViewById(R.id.queueGlyph)
        val badge: View = view.findViewById(R.id.queueNowBadge)
        val bars: EqualizerView = view.findViewById(R.id.queueBars)
        val title: TextView = view.findViewById(R.id.queueItemTitle)
        val sub: TextView = view.findViewById(R.id.queueItemSub)
        val remove: View = view.findViewById(R.id.queueRemove)
        val drag: ImageView = view.findViewById(R.id.queueDrag)
        var isCurrent = false
        var backgroundRes = 0

        /** The card above the list (the playing item): its second line says the place in the queue, the label above it says "playing now". */
        var isPinned = false

        /** The end button shows the lock (the playing item, fixed in its place) instead of the drag handle. */
        var handleIsLock = false
    }

    /**
     * A line of the list that shows nothing but, at most, the small "Next" label: it stands where the playing item is (the item
     * itself is in the card above the list), and, while the queue is in its plain order, in the place of every item before it
     * (they have no height at all, so they are not seen and the list begins with what comes after the playing item).
     */
    private class MarkHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: View = view.findViewById(R.id.queueNextLabel)
    }

    private var dialog: BottomSheetDialog? = null
    private var controller: MediaController? = null
    private var adapter: QueueAdapter? = null
    private var subtitle: TextView? = null
    private var libraryButton: ImageButton? = null
    private var layoutManager: LinearLayoutManager? = null
    private var listView: MaxHeightRecyclerView? = null

    /** The card of the playing item above the list, the part of the sheet that holds it, and the item its picture was loaded for. */
    private var nowHolder: Holder? = null
    private var nowSection: View? = null
    private var nowKey: String? = null

    /** The Library mode was busy at the last look: when it stops being busy, the list goes to the playing item. */
    private var libraryWasBusy = false

    /** A row is being dragged: the list is the user's until the drop, so the player's events do not rebuild it. */
    private var dragging = false
    private var dragFrom = RecyclerView.NO_POSITION

    /** True while the sheet is open: a second tap does not open another one over it. */
    val isShowing: Boolean get() = dialog?.isShowing == true

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        ItemTouchHelper.START or ItemTouchHelper.END,
    ) {
        // A drag starts only from the handle (a long press on the row would fight with scrolling).
        override fun isLongPressDragEnabled(): Boolean = false

        // The last item stays: nothing to swipe away. The "Next" label is not an item: nothing to swipe either.
        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (viewHolder !is Holder || (adapter?.itemCount ?: 0) <= 1) 0 else super.getSwipeDirs(recyclerView, viewHolder)

        // The "Next" label cannot be picked up (and the playing item is not in the list at all: its card is outside it).
        override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (viewHolder !is Holder || viewHolder.isCurrent) 0 else super.getDragDirs(recyclerView, viewHolder)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            val a = adapter ?: return false
            // Not onto the playing item, and not past it: it stays exactly where it is.
            if (!a.canMove(from, to)) return false
            a.moveLocal(from, to)
            return true
        }

        // The dragged row stops at the playing item like at a wall, instead of sliding over it.
        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            var y = dY
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                val now = adapter?.currentIndex ?: -1
                val pos = viewHolder.bindingAdapterPosition
                val wall = if (now >= 0 && pos != RecyclerView.NO_POSITION && pos != now) {
                    recyclerView.findViewHolderForAdapterPosition(now)?.itemView
                } else {
                    null
                }
                if (wall != null) {
                    val v = viewHolder.itemView
                    y = if (pos < now) {
                        minOf(dY, (wall.top - v.bottom).toFloat().coerceAtLeast(0f))
                    } else {
                        maxOf(dY, (wall.bottom - v.top).toFloat().coerceAtMost(0f))
                    }
                }
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, y, actionState, isCurrentlyActive)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            removeAt(viewHolder.bindingAdapterPosition)
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragging = true
                dragFrom = viewHolder.bindingAdapterPosition
                (viewHolder as? Holder)?.let { adapter?.applyBackground(it, dragged = true) }
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            (viewHolder as? Holder)?.let { adapter?.applyBackground(it, dragged = false) }
            if (!dragging) return
            dragging = false
            val from = dragFrom
            val to = viewHolder.bindingAdapterPosition
            dragFrom = RecyclerView.NO_POSITION
            // One move for the player, when the finger leaves (the list followed the finger on its own meanwhile).
            // Never one that touches or passes the item that is playing (it may have changed while the finger was down:
            // then the list is read from the player again below and the row goes back).
            val now = controller?.currentMediaItemIndex ?: -1
            val touchesPlaying = now in minOf(from, to)..maxOf(from, to)
            if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION && from != to && !touchesPlaying) {
                controller?.moveMediaItem(from, to)
            }
            adapter?.refreshStates()
            sync()
        }
    })

    /** Opens the sheet for the queue of [c]. Nothing happens when it is already open or the queue is empty. */
    fun show(c: MediaController) {
        if (isShowing || activity.isFinishing || activity.isDestroyed || c.mediaItemCount == 0) return

        val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_queue, null)
        val sheet = BottomSheetDialog(activity, R.style.QueueSheetDialog)
        sheet.setContentView(content)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.setOnDismissListener { onDismissed(sheet) }

        val list = content.findViewById<MaxHeightRecyclerView>(R.id.queueList)
        // The whole sheet is at most this part of the screen: what is above the list (header, label and the playing item's card) is
        // taken off, the list gets the rest (and at least a few rows, also sideways).
        val density = activity.resources.displayMetrics.density
        list.maxHeightPx = (activity.resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION - FIXED_PARTS_DP * density).toInt()
            .coerceAtLeast((MIN_LIST_DP * density).toInt())
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        this.layoutManager = layoutManager
        listView = list
        val a = QueueAdapter()
        list.adapter = a
        touchHelper.attachToRecyclerView(list)

        // The playing item's own card: the row layout again, in a slot above the list, so it does not scroll with the rows.
        val nowSlot = content.findViewById<ViewGroup>(R.id.queueNowSlot)
        val nowView = activity.layoutInflater.inflate(R.layout.item_queue_entry, nowSlot, false)
        nowSlot.addView(nowView)
        val pinned = Holder(nowView)
        pinned.isPinned = true
        nowView.setOnClickListener { onTap(a.currentIndex) }
        pinned.remove.setOnClickListener { removeAt(a.currentIndex) }
        nowHolder = pinned
        nowSection = content.findViewById(R.id.queueNowSection)
        nowKey = null

        subtitle = content.findViewById(R.id.queueSubtitle)
        libraryButton = content.findViewById<ImageButton>(R.id.queueLibrary).also { it.setOnClickListener { library.toggle() } }
        libraryWasBusy = library.isBusy
        refreshLibraryButton()
        content.findViewById<View>(R.id.queueClose).setOnClickListener { sheet.dismiss() }

        dialog = sheet
        controller = c
        adapter = a
        c.addListener(playerListener)
        sync()
        sheet.show()
        showPlayingFirst()
    }

    /**
     * The list opens at what comes next: in the plain order the played items have no height, so the list already begins with the
     * "Next" label and what is after the playing item; with shuffle or "repeat all" (every other item is listed) it goes to the
     * row the player goes to next. It waits for the list to have its size (a scroll asked before that is lost when the sheet is
     * laid out).
     */
    private fun showPlayingFirst() {
        val list = listView ?: return
        list.doOnLayout {
            val c = controller ?: return@doOnLayout
            val a = adapter ?: return@doOnLayout
            val manager = layoutManager ?: return@doOnLayout
            val target = if (a.isOrdered || a.nextIndex < 0) c.currentMediaItemIndex else a.nextIndex
            manager.scrollToPositionWithOffset(target.coerceAtLeast(0), 0)
        }
    }

    /** Closes the sheet (the screen is being left or goes into the little window). */
    fun dismiss() {
        runCatching { dialog?.dismiss() }
    }

    private fun onDismissed(sheet: BottomSheetDialog) {
        if (dialog !== sheet) return
        controller?.removeListener(playerListener)
        touchHelper.attachToRecyclerView(null)
        dialog = null
        controller = null
        adapter = null
        subtitle = null
        libraryButton = null
        layoutManager = null
        listView = null
        nowHolder = null
        nowSection = null
        nowKey = null
        dragging = false
        dragFrom = RecyclerView.NO_POSITION
    }

    // ---- Keeping the list the same as the player's queue ----

    private fun rowOf(item: MediaItem): Row {
        val uri = PlayerLauncher.uriOf(item)
        val title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { uri?.lastPathSegment.orEmpty() }
        return Row(item.mediaId, title, uri)
    }

    private fun isPlayingNow(c: MediaController): Boolean =
        c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE

    /** Reads the player's queue and shows it: the same rows stay as they are, only the playing mark moves. */
    private fun sync() {
        val c = controller ?: return
        val a = adapter ?: return
        if (dragging) return
        if (c.mediaItemCount == 0) {
            dismiss()
            return
        }
        val rows = List(c.mediaItemCount) { i -> rowOf(c.getMediaItemAt(i)) }
        // The next one is what the player says (shuffle decides it), not just the row under the playing one.
        // Without shuffle the order of the queue is the order of play (the played ones were moved to the bottom, see QueueCycle);
        // with shuffle it is not.
        val ordered = !c.shuffleModeEnabled
        a.submit(rows, c.currentMediaItemIndex, c.nextMediaItemIndex, isPlayingNow(c), ordered)
        a.bindNowCard()
        // The playing item is always the first of an ordered queue, so "playing 1 of 12" would say nothing: it says how many there are.
        subtitle?.text = if (ordered) {
            activity.getString(
                if (library.isOn) R.string.playerQueueCountLibrary else R.string.playerQueueCount,
                c.mediaItemCount,
            )
        } else {
            activity.getString(
                if (library.isOn) R.string.playerQueueSubtitleLibrary else R.string.playerQueueSubtitle,
                c.currentMediaItemIndex + 1,
                c.mediaItemCount,
            )
        }
    }

    // ---- The Library button ----

    /** The "whole Library" mode changed or started / stopped working: the button is drawn again, and a finished change shows the playing item. */
    fun onLibraryChanged() {
        if (!isShowing) return
        val finished = libraryWasBusy && !library.isBusy
        libraryWasBusy = library.isBusy
        refreshLibraryButton()
        sync()
        if (finished) showPlayingFirst()
    }

    /** Red on a soft red square while the queue is the whole Library; grey otherwise; dimmed while it is being built. */
    private fun refreshLibraryButton() {
        val button = libraryButton ?: return
        val on = library.isOn
        button.isEnabled = !library.isBusy
        button.alpha = if (library.isBusy) 0.4f else 1f
        button.setBackgroundResource(if (on) R.drawable.bg_queue_toggle_on else R.drawable.bg_queue_toggle_off)
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(ContextCompat.getColor(activity, if (on) R.color.queueAccent else R.color.queueIcon)),
        )
        button.contentDescription = activity.getString(if (on) R.string.playerQueueLibraryOff else R.string.playerQueueLibrary)
    }

    // ---- What the list does ----

    private fun onTap(position: Int) {
        val c = controller ?: return
        if (position == RecyclerView.NO_POSITION || position >= c.mediaItemCount) return
        if (position == c.currentMediaItemIndex) {
            // The one that is playing: pause it, or let it go on.
            if (isPlayingNow(c)) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.play()
            }
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.seekToDefaultPosition(position)
            c.play()
        }
    }

    private fun removeAt(position: Int) {
        val c = controller ?: return
        val a = adapter ?: return
        if (position == RecyclerView.NO_POSITION || position >= a.itemCount) return
        if (a.itemCount <= 1) {
            // The queue is never left empty: the row that was swiped comes back.
            a.notifyItemChanged(position)
            return
        }
        a.removeLocal(position)
        c.removeMediaItem(position)
        sync()
    }

    // ---- The list ----

    private inner class QueueAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: MutableList<Row> = ArrayList()

        /** Which row the player is on, which one it goes to next (shuffle and repeat decide that; -1 when none), and whether the sound is running. */
        private var current = -1
        private var next = -1
        private var playing = false

        /**
         * True while the order of the queue is the order of play (no shuffle): then what comes after the playing item is what comes
         * next, and the played ones are at the bottom (the player keeps the playing item first, see QueueCycle). An item that is
         * still before the playing one for a moment (the player has not moved it yet) is not listed.
         */
        private var ordered = true

        /** The row of the item that is playing (-1 when there is none). */
        val currentIndex: Int get() = current

        /** The row the player goes to next (-1 when none). */
        val nextIndex: Int get() = next

        /** See [ordered]. */
        val isOrdered: Boolean get() = ordered

        override fun getItemCount(): Int = rows.size

        /** The playing item's place holds the small "Next" label (the item itself is in the card above the list); so do the played ones, with no height. */
        override fun getItemViewType(position: Int): Int =
            if (position == current || (ordered && position < current)) TYPE_MARK else TYPE_ROW

        /** Whether a row may go from [from] to [to]: never the playing one, and never from one side of it to the other. */
        fun canMove(from: Int, to: Int): Boolean {
            if (current < 0) return true
            if (from == current || to == current) return false
            return (from < current) == (to < current)
        }

        /**
         * Shows [newRows]. Rows that are the same as the ones shown stay (no flash, no lost scroll place); only the playing
         * mark and the "Up next" label move. Any other change (an item added, removed or renamed from outside) redraws the list.
         */
        fun submit(newRows: List<Row>, newCurrent: Int, newNext: Int, newPlaying: Boolean, newOrdered: Boolean) {
            val same = newRows.size == rows.size &&
                newRows.indices.all { rows[it].key == newRows[it].key && rows[it].title == newRows[it].title }
            if (!same) {
                rows = ArrayList(newRows)
                current = newCurrent
                next = newNext
                playing = newPlaying
                ordered = newOrdered
                notifyDataSetChanged()
                return
            }
            if (newCurrent != current || newNext != next || newPlaying != playing || newOrdered != ordered) {
                current = newCurrent
                next = newNext
                playing = newPlaying
                ordered = newOrdered
                refreshStates()
            }
        }

        /** The marks of every row again (playing / next / place in the list / the buttons), without touching the pictures. */
        fun refreshStates() {
            if (rows.isNotEmpty()) notifyItemRangeChanged(0, rows.size, STATE)
        }

        /** A drag step: the list follows the finger at once; the player is told once, at the drop. */
        fun moveLocal(from: Int, to: Int) {
            rows.add(to, rows.removeAt(from))
            current = shifted(current, from, to)
            next = shifted(next, from, to)
            notifyItemMoved(from, to)
        }

        /** Where the row that was at [index] is after the row at [from] went to [to] (-1 stays -1). */
        private fun shifted(index: Int, from: Int, to: Int): Int = when {
            index < 0 -> index
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }

        fun removeLocal(position: Int) {
            rows.removeAt(position)
            if (position < current) current -= 1
            // The row that was next is gone, or it moved up: the player says which one is next when the removal reaches it.
            next = when {
                position == next -> -1
                position < next -> next - 1
                else -> next
            }
            notifyItemRemoved(position)
            refreshStates()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_MARK) {
                return MarkHolder(activity.layoutInflater.inflate(R.layout.item_queue_next, parent, false))
            }
            val holder = Holder(activity.layoutInflater.inflate(R.layout.item_queue_entry, parent, false))
            holder.itemView.setOnClickListener { onTap(holder.bindingAdapterPosition) }
            holder.remove.setOnClickListener { removeAt(holder.bindingAdapterPosition) }
            holder.drag.setOnTouchListener { _, event ->
                // The playing item is fixed (its handle is a lock): a touch on it is only a tap on the row.
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !holder.isCurrent) touchHelper.startDrag(holder)
                false
            }
            return holder
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is Holder) {
                val row = rows[position]
                holder.title.text = row.title
                bindPicture(holder, row)
                bindState(holder, position)
            } else if (holder is MarkHolder) {
                bindMark(holder, position)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else if (holder is Holder) {
                bindState(holder, position)
            } else if (holder is MarkHolder) {
                bindMark(holder, position)
            }
        }

        /** The "Next" label is shown only in the playing item's place, while the queue is in its plain order and something comes after it. */
        private fun bindMark(holder: MarkHolder, position: Int) {
            val show = ordered && position == current && position < rows.size - 1
            holder.label.visibility = if (show) View.VISIBLE else View.GONE
        }

        /**
         * Draws the card above the list: the playing item, in the same layout as a row. The picture is loaded again only when
         * the playing item is another one; the marks (bars, place, buttons) are drawn every time.
         */
        fun bindNowCard() {
            val holder = nowHolder ?: return
            val row = rows.getOrNull(current)
            nowSection?.visibility = if (row == null) View.GONE else View.VISIBLE
            if (row == null) return
            if (nowKey != row.key) {
                nowKey = row.key
                bindPicture(holder, row)
            }
            if (holder.title.text.toString() != row.title) holder.title.text = row.title
            bindState(holder, current)
        }

        private fun bindPicture(holder: Holder, row: Row) {
            val uri = row.uri
            if (uri == null) {
                ThumbnailLoader.cancel(holder.cover)
                holder.cover.visibility = View.GONE
                holder.glyph.visibility = View.VISIBLE
                return
            }
            // audio = null: the file says whether it is a video (a frame) or a song (its cover); the queue lists both.
            ThumbnailLoader.loadCover(activity, uri, holder.cover, ThumbnailLoader.COVER_SIDE, audio = null) { has ->
                holder.cover.visibility = if (has) View.VISIBLE else View.GONE
                holder.glyph.visibility = if (has) View.GONE else View.VISIBLE
            }
        }

        private fun bindState(holder: Holder, position: Int) {
            val now = position == current
            holder.isCurrent = now
            applyBackground(holder, dragged = false)
            holder.title.setTextColor(color(if (now) R.color.queueAccent else R.color.queueText))
            holder.sub.setTextColor(color(if (now) R.color.queueAccent else R.color.queueTextDim))
            holder.sub.text = when {
                // The card above the list has "playing now" as its label: its second line says the place in the queue.
                now -> if (holder.isPinned) {
                    activity.getString(R.string.playerQueuePosition, position + 1, rows.size)
                } else {
                    activity.getString(R.string.playerQueueNow)
                }
                position == next -> activity.getString(R.string.playerQueueNext)
                else -> activity.getString(R.string.playerQueuePosition, position + 1, rows.size)
            }
            holder.badge.visibility = if (now) View.VISIBLE else View.GONE
            holder.bars.playing = now && playing
            // Alone in the queue: nothing to remove and nothing to put in another order.
            val several = if (rows.size > 1) View.VISIBLE else View.GONE
            holder.remove.visibility = several
            holder.drag.visibility = several
            bindHandle(holder, locked = now)
        }

        /** The end button of the row: the drag handle, or a red lock for the playing item (it stays in its place). */
        private fun bindHandle(holder: Holder, locked: Boolean) {
            if (holder.handleIsLock == locked) return
            holder.handleIsLock = locked
            if (locked) {
                holder.drag.setImageResource(R.drawable.ic_lock_24dp)
                ImageViewCompat.setImageTintList(holder.drag, ColorStateList.valueOf(color(R.color.queueAccent)))
                holder.drag.contentDescription = activity.getString(R.string.playerQueueFixed)
            } else {
                holder.drag.setImageResource(R.drawable.ic_drag_handle_24dp)
                ImageViewCompat.setImageTintList(holder.drag, ColorStateList.valueOf(color(R.color.queueIcon)))
                holder.drag.contentDescription = activity.getString(R.string.playerQueueDrag)
            }
        }

        /** The row's background: the soft red card for the playing item, solid while dragged, otherwise only the press effect. */
        fun applyBackground(holder: Holder, dragged: Boolean) {
            val res = when {
                dragged -> R.drawable.bg_queue_row_drag
                holder.isCurrent -> R.drawable.bg_queue_row_current
                else -> R.drawable.bg_queue_row
            }
            if (holder.backgroundRes == res) return
            holder.backgroundRes = res
            holder.itemView.setBackgroundResource(res)
        }

        private fun color(res: Int): Int = ContextCompat.getColor(activity, res)
    }

    private companion object {
        /** The sheet is at most this part of the screen's height; a longer queue scrolls inside the list. */
        const val SHEET_HEIGHT_FRACTION = 0.85f

        /** What stands above the list, in dp: the handle, the header, the "playing now" label and the card (the list gets the rest). */
        const val FIXED_PARTS_DP = 207

        /** The list is never shorter than this, in dp (a screen turned sideways has little height). */
        const val MIN_LIST_DP = 120

        /** The two kinds of line in the list: an item of the queue, and the small "Next" label in the place of the playing item. */
        const val TYPE_ROW = 0
        const val TYPE_MARK = 1

        /** The payload of a redraw that changes only the marks of a row, not its picture or name. */
        const val STATE = "state"
    }
}
