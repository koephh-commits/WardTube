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

    /**
     * Picture in picture: leaving the app (Home / the gesture bar) while a video plays shrinks it into the system's small
     * floating window instead of leaving only its sound. Needs Android 8+; the menu row is not shown on older versions.
     */
    var pipAuto: Boolean
        get() = sp.getBoolean(PIP_AUTO, true)
        set(value) = sp.edit { putBoolean(PIP_AUTO, value) }

    /**
     * What a tap on a video or a song of the Library opens: [PLAYER_ASK] (the chooser sheet asks every time),
     * [PLAYER_BUILT_IN], or the package name of another player app the person asked to always use.
     */
    var playerChoice: String
        get() = sp.getString(PLAYER_CHOICE, PLAYER_ASK) ?: PLAYER_ASK
        set(value) = sp.edit { putString(PLAYER_CHOICE, value) }

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

    /**
     * The bottom bar is replaced by a side menu (a drawer) that a three-bars button in a bar at the top of the screen
     * opens, and a sideways swipe on the page opens it too. [autoHideBar] then applies to that slim top bar: it slides
     * away while scrolling down and comes back when scrolling up. See [com.mwmods.wardtube.ui.SideMenu].
     */
    var sideMenu: Boolean
        get() = sp.getBoolean(SIDE_MENU, false)
        set(value) = sp.edit { putBoolean(SIDE_MENU, value) }

    /**
     * The Subscriptions tab shows the app's own screen (a strip of channels that filters the videos in place, see
     * [com.mwmods.wardtube.subs.SubsController]) instead of YouTube's page.
     */
    var nativeSubs: Boolean
        get() = sp.getBoolean(NATIVE_SUBS, true)
        set(value) = sp.edit { putBoolean(NATIVE_SUBS, value) }

    var homepageTutorialSeen: Boolean
        get() = sp.getBoolean(HOMEPAGE_LEARNED, false)
        set(value) = sp.edit { putBoolean(HOMEPAGE_LEARNED, value) }

    /** The one-time tip that says a video is downloaded by a long press on it has been shown. */
    var videoPressTipSeen: Boolean
        get() = sp.getBoolean(VIDEO_PRESS_TIP_SEEN, false)
        set(value) = sp.edit { putBoolean(VIDEO_PRESS_TIP_SEEN, value) }

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

    /**
     * Off by default: the download engine (yt-dlp) is only updated when the person asks for it (menu: "Update download
     * engine"). On: when a download or a list fails, the engine is updated once and the work is tried again.
     * The download service runs in its own process, so it is not read there: [com.mwmods.wardtube.download.DownloadService]
     * is given this value with each start request.
     */
    var autoUpdateEngine: Boolean
        get() = sp.getBoolean(AUTO_UPDATE_ENGINE, false)
        set(value) = sp.edit { putBoolean(AUTO_UPDATE_ENGINE, value) }

    /**
     * How many downloads run at the same time, 1..[MAX_PARALLEL_DOWNLOADS] (Library: download settings). The download
     * service runs in its own process, so it is not read there: it is sent with each start request and, while the
     * service is running, in a broadcast when the person changes it (see [com.mwmods.wardtube.download.DownloadLimits]).
     */
    var maxParallelDownloads: Int
        get() = sp.getInt(MAX_PARALLEL, DEFAULT_PARALLEL_DOWNLOADS).coerceIn(1, MAX_PARALLEL_DOWNLOADS)
        set(value) = sp.edit { putInt(MAX_PARALLEL, value.coerceIn(1, MAX_PARALLEL_DOWNLOADS)) }

    /** The speed limit of each download in KiB per second; 0 = no limit. Reaches the service the same way. */
    var speedLimitKb: Int
        get() = sp.getInt(SPEED_LIMIT_KB, 0).coerceAtLeast(0)
        set(value) = sp.edit { putInt(SPEED_LIMIT_KB, value.coerceAtLeast(0)) }

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

    /**
     * The sites the person added to the browser screen's quick sites, as a JSON list of {"n": name, "u": address};
     * empty until one is added. The built-in sites are not stored (see [com.mwmods.wardtube.browser.BrowserHome]).
     */
    var quickSites: String
        get() = sp.getString(QUICK_SITES, "") ?: ""
        set(value) = sp.edit { putString(QUICK_SITES, value) }

    companion object {
        const val DEFAULT_HOMEPAGE = "https://m.youtube.com/"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        /** [playerChoice]: nothing kept, the Library asks which player to use each time. */
        const val PLAYER_ASK = ""

        /** [playerChoice]: always the app's own player. */
        const val PLAYER_BUILT_IN = "builtin"

        /** The most downloads that may run at once: every video download is a yt-dlp process, heavy for a small phone. */
        const val MAX_PARALLEL_DOWNLOADS = 5

        /** One at a time, as before the setting existed: nothing changes until the person asks for more. */
        const val DEFAULT_PARALLEL_DOWNLOADS = 1

        /** The speed limits the download settings offer, KiB per second (0 = no limit). */
        val SPEED_LIMITS_KB = intArrayOf(0, 128, 256, 512, 1024, 2048, 5120)

        private const val HOMEPAGE = "homepage"
        private const val BACKGROUND_PLAY = "backgroundPlayEnabled"
        private const val PIP_AUTO = "pipAuto"
        private const val PLAYER_CHOICE = "libraryPlayerChoice"
        private const val COOKIES = "cookiesEnabled"
        private const val AD_BLOCK = "adBlockEnabled"
        private const val AUTO_HIDE_BAR = "autoHideBar"
        private const val SIDE_MENU = "sideMenu"
        private const val NATIVE_SUBS = "nativeSubs"
        private const val HOMEPAGE_LEARNED = "homepageLearned"
        private const val NOTIFICATION_ASKED = "notificationPermissionAsked"
        private const val VIDEO_PRESS_TIP_SEEN = "videoPressTipSeen"
        private const val FILTERS_UPDATED_AT = "filtersUpdatedAt"
        /** Key of the removed favorites list; its leftover data is deleted when the app starts. */
        private const val LEGACY_BOOKMARKS = "bookmarks"
        private const val THEME = "theme"
        private const val ALL_FILES_ASKED = "allFilesAsked"
        private const val AUTO_UPDATE_ENGINE = "autoUpdateEngine"
        private const val DOWNLOAD_OPTIONS = "downloadOptions"
        private const val MAX_PARALLEL = "maxParallelDownloads"
        private const val SPEED_LIMIT_KB = "speedLimitKb"
        private const val DETECT_X = "browserDetectX"
        private const val DETECT_Y = "browserDetectY"
        private const val SEARCH_ENGINE = "browserSearchEngine"
        private const val QUICK_SITES = "browserQuickSites"
    }
}
