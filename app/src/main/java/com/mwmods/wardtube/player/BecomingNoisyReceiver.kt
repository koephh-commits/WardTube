// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/** Pauses playback when the headphones are unplugged or the Bluetooth audio device disconnects. */
class BecomingNoisyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) PlayerController.pause()
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context,
            this,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
        } catch (e: IllegalArgumentException) {
            // Not registered
        }
    }
}
