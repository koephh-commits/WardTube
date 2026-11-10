// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.ActionMenuView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.adblock.AdBlocker
import com.mwmods.wardtube.player.SleepTimer
import com.mwmods.wardtube.util.CookieHelper
import com.mwmods.wardtube.util.PlaylistLinks
import com.mwmods.wardtube.download.Downloader
import com.mwmods.wardtube.util.UrlUtils
import com.mwmods.wardtube.web.WebTargets

/**
 * The bottom action bar and the three-dots menu.
 *
 * The bar only holds the buttons that are always there (home, browser, library), the download button (dimmed while
 * there is nothing to download), one slot that is the minimize arrow while a video is full screen and the voice
 * search microphone the rest of the time (never an empty gap), and the three-dots button. All six slots stay in
 * the bar at all times so the buttons never jump sideways. The last button opens a
 * sheet: the app's
 * badge and version, a row of quick actions (refresh, share, voice search), then the settings in titled groups
 * (downloads, playback, display, browsing, ads), each group one rounded card whose rows have a tinted icon in the
 * group's color, and "About" at the end. See [showMenuSheet].
 */
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
    /** Picture in picture is possible on this phone (Android 8+ with the feature): only then the menu has its switch. */
    private val pipAvailable: Boolean = false,
    private val onPipChanged: () -> Unit = {},
) : ActionMenuView.OnMenuItemClickListener {

    private lateinit var actionMenu: ActionMenuView

    /** The three-dots sheet while it is open. */
    private var sheet: BottomSheetDialog? = null

    /** The remaining sleep time ("12:34"), or null when no timer runs. Kept here so a sheet opened later shows it. */
    private var sleepLabel: String? = null

    /** The lines of the open sheet that change while it is open. */
    private var sleepValue: TextView? = null
    private var adBlockValue: TextView? = null

    /** The last state given to each of the two changing slots; null until [setUp] gives the first one. */
    private var videoFullScreen: Boolean? = null
    private var downloadAvailable: Boolean? = null

    fun setUp(menuView: ActionMenuView) {
        actionMenu = menuView
        activity.menuInflater.inflate(R.menu.menu_main, menuView.menu)
        menuView.setOnMenuItemClickListener(this)
        // Both slots are in the bar from the start; they only change how they look.
        setVideoFullScreen(false)
        setDownloadVisible(false)
    }

    /**
     * The first slot of the bar is never empty. While a video is full screen it is the minimize arrow (hide the
     * player behind the page); the rest of the time (browsing, with or without the mini player) it is the
     * voice search microphone, so voice search is one tap away instead of inside the three-dots sheet. The slot
     * itself is always in the bar and always enabled: a hidden item makes ActionMenuView spread the other buttons
     * over the width again, and they would move sideways under the finger.
     */
    fun setVideoFullScreen(fullScreen: Boolean) {
        if (!::actionMenu.isInitialized || fullScreen == videoFullScreen) return
        videoFullScreen = fullScreen
        val item = actionMenu.menu.findItem(R.id.action_minimize) ?: return
        item.isEnabled = true
        item.setIcon(
            slotIcon(
                if (fullScreen) R.drawable.ic_expand_more_white_24dp else R.drawable.ic_mic_white_24dp,
                FULL_ALPHA
            )
        )
        item.setTitle(if (fullScreen) R.string.minimizePlayer else R.string.voiceSearch)
    }

    /**
     * The download button is full strength while there is something to download (a video, a Short, a playlist or
     * channel) and dimmed otherwise. It stays in the bar and still reacts to a tap: it then explains that a video has
     * to be chosen first (see [withWatchUrl]).
     */
    fun setDownloadVisible(visible: Boolean) {
        if (!::actionMenu.isInitialized || visible == downloadAvailable) return
        downloadAvailable = visible
        val item = actionMenu.menu.findItem(R.id.action_download) ?: return
        item.setIcon(slotIcon(R.drawable.ic_download_24dp, if (visible) FULL_ALPHA else DIMMED_ALPHA))
    }

    /** A private copy of an icon (so the other users of the drawable keep their look) drawn at [alpha] (0-255). */
    private fun slotIcon(@DrawableRes res: Int, alpha: Int): Drawable? =
        AppCompatResources.getDrawable(activity, res)?.mutate()?.also { it.alpha = alpha }

    /** True while the three-dots menu is open (the sleep time is then refreshed every second). */
    fun isOverflowShowing(): Boolean = sheet?.isShowing == true

    /** Closes the three-dots menu if it is open (the app is turning into the small picture-in-picture window). */
    fun dismissSheet() {
        sheet?.dismiss()
    }

    /** The sleep timer line of the menu shows the remaining time under its name while a timer runs. */
    fun setSleepLabel(label: String?) {
        if (label == sleepLabel) return
        sleepLabel = label
        sleepValue?.let { showValue(it, label) }
    }

    /** Shows how many requests were blocked so far. */
    fun updateAdBlockTitle() {
        adBlockValue?.let { showValue(it, adBlockSubtitle()) }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_minimize -> if (videoFullScreen == true) onMinimize() else onVoiceSearch()
            R.id.action_home -> {
                showHomepageTutorial()
                targets.openUrl(prefs.homepage)
            }
            R.id.action_browser -> openBrowser()
            R.id.action_download -> withDownloadUrl { onDownload(it) }
            R.id.action_library -> activity.startActivity(Intent(activity, LibraryActivity::class.java))
            R.id.action_more -> showMenuSheet()
            else -> return false
        }
        return true
    }

    // ---- The three-dots sheet ----

    /** The card the next rows go into, the accent color of its group, and how many rows it holds so far. */
    private var group: LinearLayout? = null
    private var groupAccent = 0
    private var groupRows = 0

    /** The parts of the open sheet that slide in one after the other when it opens. */
    private val entrance = ArrayList<View>()

    /**
     * The badge on top, quick actions, then the settings by purpose, one card per group. Every row closes the sheet
     * before it acts (a dialog, a message or another screen follows), except the switches, which stay open so the
     * change is seen at once.
     */
    private fun showMenuSheet() {
        if (sheet?.isShowing == true) return
        // The remaining sleep time is only refreshed now and then while the menu is closed; bring it up to date.
        onSleepTimerChanged()
        group = null
        groupRows = 0
        entrance.clear()

        val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_menu, null)
        val list = content.findViewById<LinearLayout>(R.id.menuList)
        val dialog = BottomSheetDialog(activity, R.style.MenuSheetDialog)
        dialog.setContentView(content)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener {
            if (sheet === dialog) {
                sheet = null
                sleepValue = null
                adBlockValue = null
            }
        }
        sheet = dialog

        entrance += content.findViewById<View>(R.id.menuHeader)
        entrance += content.findViewById<View>(R.id.menuQuickRow)
        showValue(content.findViewById<TextView>(R.id.menuHeaderVersion), appVersionLabel())

        setUpQuickAction(content, R.id.quickRefresh, R.id.quickRefreshIcon, R.color.menuAccentTeal) {
            targets.current.reload()
        }
        setUpQuickAction(content, R.id.quickShare, R.id.quickShareIcon, R.color.menuAccentBlue) {
            shareVideo()
        }
        setUpQuickAction(content, R.id.quickVoice, R.id.quickVoiceIcon, R.color.menuAccentPurple) {
            onVoiceSearch()
        }

        addSection(list, R.string.menuSectionDownloads, R.color.menuAccentBlue)
        addRow(R.string.updateEngine, R.drawable.ic_menu_engine) { Downloader.updateEngine(activity, snackbarAnchor) }
        addSwitchRow(
            R.string.autoUpdateEngine, R.drawable.ic_menu_autorenew, prefs.autoUpdateEngine,
            subtitle = { on ->
                activity.getString(if (on) R.string.autoUpdateEngineOn else R.string.autoUpdateEngineOff)
            }
        ) { on -> prefs.autoUpdateEngine = on }

        addSection(list, R.string.menuSectionPlayback, R.color.menuAccentPurple)
        addSwitchRow(R.string.playInBackground, R.drawable.ic_menu_headphones, prefs.backgroundPlay) { on ->
            prefs.backgroundPlay = on
            applyScriptSettings()
            (activity as? com.mwmods.wardtube.MainActivity)?.updateBackgroundPlayback()
        }
        if (pipAvailable) {
            addSwitchRow(
                R.string.pictureInPicture, R.drawable.ic_menu_pip, prefs.pipAuto,
                subtitle = { on ->
                    activity.getString(if (on) R.string.pictureInPictureOn else R.string.pictureInPictureOff)
                }
            ) { on ->
                prefs.pipAuto = on
                onPipChanged()
            }
        }
        sleepValue = addRow(R.string.sleepTimer, R.drawable.ic_menu_moon, value = sleepLabel, chevron = true) {
            showSleepTimerDialog()
        }

        addSection(list, R.string.menuSectionDisplay, R.color.menuAccentAmber)
        addRow(
            R.string.theme, R.drawable.ic_menu_theme,
            value = themeLabels()[prefs.theme.coerceIn(0, 2)], chevron = true
        ) {
            showThemeDialog()
        }
        addSwitchRow(R.string.hideBarOnScroll, R.drawable.ic_menu_swap_vert, prefs.autoHideBar) { on ->
            prefs.autoHideBar = on
            onAutoHideBarChanged()
        }

        addSection(list, R.string.menuSectionBrowsing, R.color.menuAccentTeal)
        addRow(R.string.set_as_home, R.drawable.ic_home_white_24dp) {
            targets.current.url?.let {
                prefs.homepage = it
                Snackbar.make(snackbarAnchor, R.string.homePageSet, Snackbar.LENGTH_LONG).show()
            }
        }
        addSwitchRow(R.string.acceptCookies, R.drawable.ic_menu_cookie, prefs.cookies) { on ->
            prefs.cookies = on
            targets.all.forEach { CookieHelper.setAccepting(it, on) }
            if (!on) CookieHelper.clear()
        }

        addSection(list, R.string.menuSectionAds, R.color.menuAccentRed)
        adBlockValue = addSwitchRow(
            R.string.blockAds, R.drawable.ic_menu_shield, prefs.adBlock,
            subtitle = { adBlockSubtitle() }
        ) { on ->
            prefs.adBlock = on
            applyScriptSettings()
            targets.current.reload()
        }
        addRow(R.string.updateFilters, R.drawable.ic_menu_filter) { updateFilters() }

        addSection(list, null, R.color.menuAccentGray)
        addRow(R.string.about, R.drawable.ic_menu_info, chevron = true) { AboutSheet.show(activity) }

        playEntrance(dialog)
        dialog.show()
    }

    /** One of the quick actions on top: its badge takes the [accent] color, a tap closes the sheet and runs [onClick]. */
    private fun setUpQuickAction(
        content: View,
        @androidx.annotation.IdRes tileId: Int,
        @androidx.annotation.IdRes iconId: Int,
        @ColorRes accent: Int,
        onClick: () -> Unit,
    ) {
        paintTile(content.findViewById<ImageView>(iconId), ContextCompat.getColor(activity, accent))
        content.findViewById<View>(tileId).setOnClickListener {
            sheet?.dismiss()
            onClick()
        }
    }

    /** The badge, quick actions and groups rise and fade in one after the other, a short moment after the sheet shows. */
    private fun playEntrance(dialog: BottomSheetDialog) {
        val rise = 16 * activity.resources.displayMetrics.density
        val parts = entrance.toList()
        parts.forEach {
            it.alpha = 0f
            it.translationY = rise
        }
        dialog.setOnShowListener {
            parts.forEachIndexed { index, part ->
                part.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(40L + index * 30L)
                    .setDuration(260L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        }
    }

    /**
     * Starts a group: its title (in the group's [accent] color; none when [title] is null) and the rounded card that
     * the rows added next go into.
     */
    private fun addSection(list: LinearLayout, @StringRes title: Int?, @ColorRes accent: Int) {
        groupAccent = ContextCompat.getColor(activity, accent)
        groupRows = 0
        if (title != null) {
            val text = activity.layoutInflater.inflate(R.layout.item_menu_section, list, false) as TextView
            text.setText(title)
            text.setTextColor(groupAccent)
            list.addView(text)
            entrance += text
        }
        val card = activity.layoutInflater.inflate(R.layout.item_menu_card, list, false)
        if (title == null) {
            (card.layoutParams as LinearLayout.LayoutParams).topMargin =
                (20 * activity.resources.displayMetrics.density).toInt()
        }
        group = card.findViewById<LinearLayout>(R.id.menuCardContent)
        list.addView(card)
        entrance += card
    }

    /** A row in the open group: a thin line above it when it is not the first, its title, and its tinted icon. */
    private fun inflateRow(@StringRes title: Int, @DrawableRes icon: Int): View {
        val card = checkNotNull(group) { "addSection comes first" }
        if (groupRows > 0) card.addView(activity.layoutInflater.inflate(R.layout.item_menu_divider, card, false))
        val row = activity.layoutInflater.inflate(R.layout.item_menu_row, card, false)
        row.findViewById<TextView>(R.id.menuRowTitle).setText(title)
        val tile = row.findViewById<ImageView>(R.id.menuRowIcon)
        tile.setImageResource(icon)
        paintTile(tile, groupAccent)
        card.addView(row)
        groupRows++
        return row
    }

    /**
     * A row that does something: the sheet closes, then [onClick] runs. [value] (when there is one) is shown at the
     * end in the group's color, before the arrow if [chevron]. Returns that value line; it shows only if it has text.
     */
    private fun addRow(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        value: String? = null,
        chevron: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val row = inflateRow(title, icon)
        val valueView = row.findViewById<TextView>(R.id.menuRowValue)
        valueView.setTextColor(groupAccent)
        showValue(valueView, value)
        if (chevron) row.findViewById<View>(R.id.menuRowChevron).visibility = View.VISIBLE
        row.setOnClickListener {
            sheet?.dismiss()
            onClick()
        }
        return valueView
    }

    /**
     * A row with a switch: a tap anywhere on it flips the switch and calls [onChanged]; the sheet stays open.
     * [subtitle] gives the second line for the switch's state (null for none). Returns that line.
     */
    private fun addSwitchRow(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        checked: Boolean,
        subtitle: (Boolean) -> String? = { null },
        onChanged: (Boolean) -> Unit,
    ): TextView {
        val row = inflateRow(title, icon)
        val second = row.findViewById<TextView>(R.id.menuRowSubtitle)
        val switch = row.findViewById<SwitchMaterial>(R.id.menuRowSwitch)
        switch.visibility = View.VISIBLE
        switch.isChecked = checked
        showValue(second, subtitle(checked))
        row.setOnClickListener {
            val on = !switch.isChecked
            switch.isChecked = on
            showValue(second, subtitle(on))
            row.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onChanged(on)
        }
        return second
    }

    /** Tints a row's or quick action's badge: a soft fill of [color] behind the icon, which is [color] itself. */
    private fun paintTile(icon: ImageView, color: Int) {
        icon.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, TILE_ALPHA))
        icon.imageTintList = ColorStateList.valueOf(color)
    }

    private fun showValue(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** "Version 2.6.45" for the top of the sheet, or null if the version cannot be read. */
    @Suppress("DEPRECATION")
    private fun appVersionLabel(): String? = try {
        val name = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        if (name.isNullOrEmpty()) null else activity.getString(R.string.version, name)
    } catch (e: Exception) {
        null
    }

    private fun adBlockSubtitle(): String? {
        val count = adBlocker.blockedCount
        return if (count > 0) activity.getString(R.string.adsBlockedCount, count) else null
    }

    private fun themeLabels() = arrayOf(
        activity.getString(R.string.themeSystem),
        activity.getString(R.string.themeLight),
        activity.getString(R.string.themeDark),
    )

    private fun shareVideo() = withWatchUrl { url ->
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_with)))
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
        val labels = themeLabels()
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
     * Download: a playlist or channel page that is open is downloaded as a list (the person picks the videos);
     * otherwise it is the video being looked at, as for [withWatchUrl].
     */
    private fun withDownloadUrl(action: (String) -> Unit) {
        val page = targets.current.url
        if (page != null && UrlUtils.isYouTubeUrl(page) &&
            PlaylistLinks.analyze(page).kind == PlaylistLinks.Kind.LIST
        ) {
            // A playlist / channel page is on screen. If a video is also playing in the mini player, which of the two
            // is meant is not clear, so ask; with no video playing the list is the only thing there is to download.
            val player = targets.video?.takeIf { it !== targets.current }
            val playingUrl = player?.url?.takeIf { UrlUtils.isWatchPage(it) }
            if (player != null && playingUrl != null) {
                askWhatToDownload(player, playingUrl, page, action)
            } else {
                action(page)
            }
        } else {
            withWatchUrl(action)
        }
    }

    /**
     * The choice between the video playing in the mini player and the list on the page: two rows in one card, in the
     * style of the three-dots menu, each saying what it is. Like the other download dialogs it closes only through
     * its own button, not by tapping outside or pressing Back.
     */
    private fun askWhatToDownload(player: WebView, videoUrl: String, listUrl: String, action: (String) -> Unit) {
        val title = player.title.orEmpty()
            .removeSuffix(" - YouTube")
            .replace(Regex("^\\(\\d+\\)\\s*"), "") // YouTube prefixes "(3) " when there are notifications
            .trim()
            .take(70)
        val content = activity.layoutInflater.inflate(R.layout.dialog_download_choice, null)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.downloadChoiceTitle)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create()
        fillChoiceRow(
            content.findViewById(R.id.choicePlaying),
            R.string.downloadChoicePlaying,
            title.ifEmpty { null },
            R.drawable.ic_videocam_24dp,
            R.color.menuAccentRed,
        ) {
            dialog.dismiss()
            action(videoUrl)
        }
        fillChoiceRow(
            content.findViewById(R.id.choiceList),
            R.string.downloadChoiceList,
            activity.getString(R.string.downloadChoiceListHint),
            R.drawable.ic_list_24dp,
            R.color.menuAccentBlue,
        ) {
            dialog.dismiss()
            action(listUrl)
        }
        dialog.show()
    }

    /** One row of the choice dialog: its tinted icon, [title], the line under it ([subtitle], none when null) and an arrow. */
    private fun fillChoiceRow(
        row: View,
        @StringRes title: Int,
        subtitle: String?,
        @DrawableRes icon: Int,
        @ColorRes accent: Int,
        onClick: () -> Unit,
    ) {
        row.findViewById<TextView>(R.id.menuRowTitle).setText(title)
        val second = row.findViewById<TextView>(R.id.menuRowSubtitle)
        second.maxLines = 2
        second.ellipsize = TextUtils.TruncateAt.END
        showValue(second, subtitle)
        val tile = row.findViewById<ImageView>(R.id.menuRowIcon)
        tile.setImageResource(icon)
        paintTile(tile, ContextCompat.getColor(activity, accent))
        row.findViewById<View>(R.id.menuRowChevron).visibility = View.VISIBLE
        row.setOnClickListener { onClick() }
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

    private companion object {
        /** How strong the tint behind a badge is (0-255): about 18 percent of the accent color. */
        const val TILE_ALPHA = 0x2E

        /** The alpha (0-255) of a bar icon: normal, or dimmed (download with nothing to download). */
        const val FULL_ALPHA = 255
        const val DIMMED_ALPHA = 90
    }
}
