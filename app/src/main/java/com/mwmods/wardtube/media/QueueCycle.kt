// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultShuffleOrder
import java.util.Random

/**
 * Keeps the queue of the built-in player going round and round, so nothing in it ever disappears.
 *
 * It lives with the player (in [MediaPlayerService]), not with the screen, so it works the same with the screen closed.
 *
 *  - **Plain order (no shuffle):** the item that plays is always the first of the queue, and the items that were before it (played,
 *    or skipped by a tap on a later row) are moved to the end, in their order. So the queue is always exactly the order of play:
 *    what comes next is under the playing item, and what was played is at the bottom. When the last one has played, the ones
 *    that went to the bottom come again from the first, in the same order as at the start.
 *  - **Shuffle:** Media3 plays one random order and, with "repeat all", plays that same order again and again. Here, when a round is
 *    over (the player came back to an item it had already played in this round), a new random order is made for the next
 *    round, with the playing item first, so every round is a different random list.
 *
 * It only moves items that are not playing, so the sound / picture does not stop or jump.
 */
@OptIn(UnstableApi::class)
class QueueCycle(private val player: ExoPlayer) : Player.Listener {

    private val random = Random()

    /** The files played in this shuffle round, in the order they played (a "Previous" takes its last one off again). */
    private val played = ArrayList<String>()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null) return
        if (!player.shuffleModeEnabled) {
            moveBeforeToEnd()
            return
        }
        val id = mediaItem.mediaId
        when {
            // A new queue (or the playing item was removed): a new round starts from this item.
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || played.isEmpty() -> startRound(id)
            // "Previous": the item that played before the last one: the round goes one step back.
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && played.size >= 2 && played[played.size - 2] == id ->
                played.removeAt(played.size - 1)
            // The player came back, by itself, to an item that already played in this round: the round is over.
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && played.contains(id) -> {
                startRound(id)
                newRandomOrder()
            }
            // The same item again ("repeat one"), or one tapped that already played: nothing changes.
            played.contains(id) -> Unit
            else -> played.add(id)
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled) {
            startRound(player.currentMediaItem?.mediaId)
        } else {
            played.clear()
            moveBeforeToEnd()
        }
    }

    private fun startRound(id: String?) {
        played.clear()
        if (id != null) played.add(id)
    }

    /** Plain order: everything before the playing item goes to the end of the queue, in its order. */
    private fun moveBeforeToEnd() {
        val current = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (current <= 0 || current >= count) return
        player.moveMediaItems(0, current, count - current)
    }

    /** Shuffle: a new random order for the rest of the queue, with the playing item first (it goes on playing). */
    private fun newRandomOrder() {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count < 2 || current < 0 || current >= count) return
        val others = (0 until count).filter { it != current }.toMutableList()
        others.shuffle(random)
        val order = IntArray(count)
        order[0] = current
        others.forEachIndexed { i, index -> order[i + 1] = index }
        player.setShuffleOrder(DefaultShuffleOrder(order, random.nextLong()))
    }
}
