// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube

import android.app.Application
import com.mwmods.wardtube.ui.ThemeHelper

class WardTubeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.mwmods.wardtube.util.CrashLog.install(this)
        // Runs before any activity exists, so the very first screen already has the right theme.
        ThemeHelper.apply(Prefs(this))
    }
}
