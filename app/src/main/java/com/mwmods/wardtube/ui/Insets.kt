// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge for the secondary screens (Android 15 forces it anyway). The coloured toolbar sits
 * behind the status bar; [bottomTarget] (usually the list) gets the navigation-bar inset instead.
 */
fun ComponentActivity.setUpEdgeToEdge(root: View, bottomTarget: View? = null) {
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
    val bottomBase = bottomTarget?.paddingBottom ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
        if (bottomTarget != null) bottomTarget.updatePadding(bottom = bottomBase + bars.bottom)
        else view.updatePadding(bottom = bars.bottom)
        insets
    }
}
