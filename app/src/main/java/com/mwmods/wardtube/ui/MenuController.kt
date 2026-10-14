// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.Intent
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.adblock.AdBlocker
import com.mwmods.wardtube.player.SleepTimer
import com.mwmods.wardtube.util.CookieHelper
import com.mwmods.wardtube.download.Downloader
import com.mwmods.wardtube.util.UrlUtils
import com.mwmods.wardtube.web.WebTargets

/** The bottom action bar and its overflow menu. */
class MenuController(
    private val activity: AppCompatActivity,
    private val targets: WebTargets,
    private val prefs: Prefs,
    private val adBlocker: AdBlocker,
    private val applyScriptSettings: () -> Unit,
    private val snackbarAnchor: View,
    private val openBrowser: () -> Unit,
    private val onThemeChanged: () -> Unit,
    private val onVoiceSearch: () -> Unit,
    private val onDownload: (String) -> Unit,
    private val onSleepTimerChanged: () -> Unit,
    private val onMinimize: () -> Unit,
    private val onAutoHideBarChanged: () -> Unit,
) : ActionMenuView.OnMenuItemClickListener {

    private lateinit var actionMenu: ActionMenuView
    private var sleepTitle = ""

    fun setUp(menuView: ActionMenuView) {
        actionMenu = menuView
        activity.menuInflater.inflate(R.menu.menu_main, menuView.menu)
        menuView.setOnMenuItemClickListener(this)
        menuView.overflowIcon = ContextCompat.getDrawable(activity, R.drawable.ic_dots_vertical_white_24dp)

        val menu = menuView.menu
        menu.findItem(R.id.action_backgroundPlay).isChecked = prefs.backgroundPlay
        menu.findItem(R.id.action_adblock).isChecked = prefs.adBlock
        menu.findItem(R.id.action_auto_hide_bar).isChecked = prefs.autoHideBar
        menu.findItem(R.id.action_accept_cookies).isChecked = prefs.cookies
        updateAdBlockTitle()
    }

    /** The minimize button (hide the player behind the page) is only shown while a video is full screen. */
    fun setMinimizeVisible(visible: Boolean) {
        if (!::actionMenu.isInitialized) return
        actionMenu.menu.findItem(R.id.action_minimize)?.isVisible = visible
    }

    /** True while the three-dots menu is open (the sleep time is then refreshed every second). */
    fun isOverflowShowing(): Boolean = ::actionMenu.isInitialized && actionMenu.isOverflowMenuShowing

    /** The menu entry shows the remaining time next to its name while a sleep timer runs. */
    fun setSleepLabel(label: String?) {
        if (!::actionMenu.isInitialized) return
        val name = activity.getString(R.string.sleepTimer)
        val title = if (label == null) name else "$name   $label"
        if (title == sleepTitle) return
        sleepTitle = title
        actionMenu.menu.findItem(R.id.action_sleep_timer).title = title
    }

    /** Shows how many requests were blocked so far. */
    fun updateAdBlockTitle() {
        if (!::actionMenu.isInitialized) return
        val count = adBlocker.blockedCount
        actionMenu.menu.findItem(R.id.action_adblock).title =
            if (count > 0) activity.getString(R.string.blockAdsCount, count)
            else activity.getString(R.string.blockAds)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_minimize -> onMinimize()
            R.id.action_home -> {
                showHomepageTutorial()
                targets.openUrl(prefs.homepage)
            }
            R.id.action_refresh -> targets.current.reload()
            R.id.action_share -> withWatchUrl { url ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_with)))
            }
            R.id.action_browser -> openBrowser()
            R.id.action_voice_search -> onVoiceSearch()
            R.id.action_library -> activity.startActivity(Intent(activity, LibraryActivity::class.java))
            R.id.action_about -> AboutSheet.show(activity)
            R.id.action_set_as_home -> targets.current.url?.let {
                prefs.homepage = it
                Snackbar.make(snackbarAnchor, R.string.homePageSet, Snackbar.LENGTH_LONG).show()
            }
            R.id.action_theme -> showThemeDialog()
            R.id.action_backgroundPlay -> {
                prefs.backgroundPlay = !prefs.backgroundPlay
                item.isChecked = prefs.backgroundPlay
                applyScriptSettings()
                (activity as? com.mwmods.wardtube.MainActivity)?.updateBackgroundPlayback()
            }
            R.id.action_sleep_timer -> showSleepTimerDialog()
            R.id.action_auto_hide_bar -> {
                prefs.autoHideBar = !prefs.autoHideBar
                item.isChecked = prefs.autoHideBar
                onAutoHideBarChanged()
            }
            R.id.action_adblock -> {
                prefs.adBlock = !prefs.adBlock
                item.isChecked = prefs.adBlock
                applyScriptSettings()
                targets.current.reload()
            }
            R.id.action_update_filters -> updateFilters()
            R.id.action_download -> withWatchUrl { onDownload(it) }
            R.id.action_update_engine -> Downloader.updateEngine(activity, snackbarAnchor)
            R.id.action_accept_cookies -> {
                prefs.cookies = !prefs.cookies
                targets.all.forEach { CookieHelper.setAccepting(it, prefs.cookies) }
                if (!prefs.cookies) CookieHelper.clear()
                item.isChecked = prefs.cookies
            }
            else -> return false
        }
        return true
    }

    private fun updateFilters() {
        Snackbar.make(snackbarAnchor, R.string.filtersUpdating, Snackbar.LENGTH_SHORT).show()
        adBlocker.updateNow { ok, count ->
            if (activity.isDestroyed) return@updateNow
            val message = if (ok) activity.getString(R.string.filtersUpdated, count)
            else activity.getString(R.string.filtersUpdateFailed)
            Snackbar.make(snackbarAnchor, message, Snackbar.LENGTH_LONG).show()
        }
    }

    fun showSleepTimerDialog() {
        val minutes = intArrayOf(15, 30, 45, 60, 90, 120)
        val labels = mutableListOf<String>()
        val active = SleepTimer.isActive
        if (active) labels += activity.getString(R.string.sleepTimerOff)
        minutes.forEach { labels += activity.getString(R.string.sleepTimerMinutes, it) }
        labels += activity.getString(R.string.sleepCustom)
        val title = if (active) activity.getString(R.string.sleepTimerLeft, SleepTimer.remainingMinutes)
        else activity.getString(R.string.sleepTimer)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                val index = if (active) which - 1 else which
                when {
                    index < 0 -> {
                        SleepTimer.cancel()
                        onSleepTimerChanged()
                        Snackbar.make(snackbarAnchor, R.string.sleepTimerCanceled, Snackbar.LENGTH_SHORT).show()
                    }
                    index < minutes.size -> startSleepTimer(minutes[index])
                    else -> showCustomSleepDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startSleepTimer(totalMinutes: Int) {
        SleepTimer.start(activity, totalMinutes)
        onSleepTimerChanged()
        Snackbar.make(
            snackbarAnchor,
            activity.getString(R.string.sleepTimerSet, totalMinutes),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /** Lets the user type any time, in hours and minutes. */
    private fun showCustomSleepDialog() {
        val density = activity.resources.displayMetrics.density
        fun field(hint: Int) = EditText(activity).apply {
            setHint(hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (6 * density).toInt(); marginEnd = (6 * density).toInt() }
        }
        val hours = field(R.string.sleepHours)
        val mins = field(R.string.sleepMinutesField)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(hours)
            addView(mins)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.sleepCustomTitle)
            .setView(row)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = hours.text.toString().toIntOrNull() ?: 0
                val m = mins.text.toString().toIntOrNull() ?: 0
                val total = h * 60 + m
                if (total < 1 || total > SleepTimer.MAX_MINUTES) {
                    Snackbar.make(snackbarAnchor, R.string.sleepInvalid, Snackbar.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    startSleepTimer(total)
                }
            }
            hours.requestFocus()
        }
        dialog.show()
    }

    private fun showThemeDialog() {
        val labels = arrayOf(
            activity.getString(R.string.themeSystem),
            activity.getString(R.string.themeLight),
            activity.getString(R.string.themeDark),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, prefs.theme) { dialog, which ->
                dialog.dismiss()
                if (which != prefs.theme) {
                    prefs.theme = which
                    ThemeHelper.apply(prefs)
                    onThemeChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHomepageTutorial() {
        if (prefs.homepageTutorialSeen) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.home)
            .setMessage(R.string.homePageHelp)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                prefs.homepageTutorialSeen = true
            }
            .show()
    }

    /**
     * Runs [action] with the URL of the video being looked at; otherwise explains what to do. A Short plays in the
     * browsing page, not in the player, so while one is on screen its address (which follows the swipes) is the one.
     */
    private fun withWatchUrl(action: (String) -> Unit) {
        val shortUrl = targets.current.url?.takeIf { UrlUtils.isShortsPage(it) }
        val url = shortUrl ?: targets.video?.url
        if (url != null && (shortUrl != null || UrlUtils.isWatchPage(url))) {
            action(url)
        } else {
            AlertDialog.Builder(activity)
                .setTitle(R.string.error_no_video)
                .setMessage(R.string.error_select_video_and_retry)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

}
