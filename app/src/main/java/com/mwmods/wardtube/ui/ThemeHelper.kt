// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import androidx.appcompat.app.AppCompatDelegate
import com.mwmods.wardtube.Prefs

/** Maps the saved theme choice (system / light / dark) to AppCompat's night mode. */
object ThemeHelper {

    fun apply(prefs: Prefs) {
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.theme) {
                Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
