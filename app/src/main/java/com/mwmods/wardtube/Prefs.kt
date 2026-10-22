// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Typed access to the app's SharedPreferences.
 *
 * Preferences live in a file named after the package (`<package>_preferences`).
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.applicationContext.let {
        it.getSharedPreferences("${it.packageName}_preferences", Context.MODE_PRIVATE)
    }

    init {
        if (sp.contains(LEGACY_BOOKMARKS)) sp.edit { remove(LEGACY_BOOKMARKS) }
    }

    var homepage: String
        get() = sp.getString(HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        set(value) = sp.edit { putString(HOMEPAGE, value) }

    var backgroundPlay: Boolean
        get() = sp.getBoolean(BACKGROUND_PLAY, true)
        set(value) = sp.edit { putBoolean(BACKGROUND_PLAY, value) }

    var cookies: Boolean
        get() = sp.getBoolean(COOKIES, true)
        set(value) = sp.edit { putBoolean(COOKIES, value) }


    var adBlock: Boolean
        get() = sp.getBoolean(AD_BLOCK, true)
        set(value) = sp.edit { putBoolean(AD_BLOCK, value) }

    /** The bottom bar slides away while scrolling down and comes back when scrolling up. */
    var autoHideBar: Boolean
        get() = sp.getBoolean(AUTO_HIDE_BAR, true)
        set(value) = sp.edit { putBoolean(AUTO_HIDE_BAR, value) }

    var homepageTutorialSeen: Boolean
        get() = sp.getBoolean(HOMEPAGE_LEARNED, false)
        set(value) = sp.edit { putBoolean(HOMEPAGE_LEARNED, value) }

    var notificationPermissionAsked: Boolean
        get() = sp.getBoolean(NOTIFICATION_ASKED, false)
        set(value) = sp.edit { putBoolean(NOTIFICATION_ASKED, value) }

    var filtersUpdatedAt: Long
        get() = sp.getLong(FILTERS_UPDATED_AT, 0L)
        set(value) = sp.edit { putLong(FILTERS_UPDATED_AT, value) }

    /** One of [THEME_SYSTEM], [THEME_LIGHT], [THEME_DARK]. */
    var theme: Int
        get() = sp.getInt(THEME, THEME_SYSTEM)
        set(value) = sp.edit { putInt(THEME, value) }

    var allFilesAsked: Boolean
        get() = sp.getBoolean(ALL_FILES_ASKED, false)
        set(value) = sp.edit { putBoolean(ALL_FILES_ASKED, value) }

    /** The last choices made in the Download dialog, as [com.mwmods.wardtube.download.DownloadOptions] JSON. */
    var downloadOptionsJson: String
        get() = sp.getString(DOWNLOAD_OPTIONS, "") ?: ""
        set(value) = sp.edit { putString(DOWNLOAD_OPTIONS, value) }

    /**
     * Where the browser screen's floating download button was left, as fractions (0..1) of the area it can move in;
     * -1 while it was never moved (it sits in its own corner).
     */
    var detectButtonX: Float
        get() = sp.getFloat(DETECT_X, -1f)
        set(value) = sp.edit { putFloat(DETECT_X, value) }

    var detectButtonY: Float
        get() = sp.getFloat(DETECT_Y, -1f)
        set(value) = sp.edit { putFloat(DETECT_Y, value) }

    /**
     * The id of the search engine the browser screen searches with (see
     * [com.mwmods.wardtube.browser.SearchEngines]); empty until the person chooses one, which means the default.
     */
    var searchEngine: String
        get() = sp.getString(SEARCH_ENGINE, "") ?: ""
        set(value) = sp.edit { putString(SEARCH_ENGINE, value) }

    companion object {
        const val DEFAULT_HOMEPAGE = "https://m.youtube.com/"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        private const val HOMEPAGE = "homepage"
        private const val BACKGROUND_PLAY = "backgroundPlayEnabled"
        private const val COOKIES = "cookiesEnabled"
        private const val AD_BLOCK = "adBlockEnabled"
        private const val AUTO_HIDE_BAR = "autoHideBar"
        private const val HOMEPAGE_LEARNED = "homepageLearned"
        private const val NOTIFICATION_ASKED = "notificationPermissionAsked"
        private const val FILTERS_UPDATED_AT = "filtersUpdatedAt"
        /** Key of the removed favorites list; its leftover data is deleted when the app starts. */
        private const val LEGACY_BOOKMARKS = "bookmarks"
        private const val THEME = "theme"
        private const val ALL_FILES_ASKED = "allFilesAsked"
        private const val DOWNLOAD_OPTIONS = "downloadOptions"
        private const val DETECT_X = "browserDetectX"
        private const val DETECT_Y = "browserDetectY"
        private const val SEARCH_ENGINE = "browserSearchEngine"
    }
}
