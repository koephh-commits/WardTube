// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import com.mwmods.wardtube.adblock.AdBlocker
import com.mwmods.wardtube.browser.BrowserActivity
import com.mwmods.wardtube.download.Downloader
import com.mwmods.wardtube.player.BecomingNoisyReceiver
import com.mwmods.wardtube.player.PlaybackService
import com.mwmods.wardtube.player.PlayerController
import com.mwmods.wardtube.player.PlayerHost
import com.mwmods.wardtube.player.SleepTimer
import com.mwmods.wardtube.ui.MenuController
import com.mwmods.wardtube.util.CookieHelper
import com.mwmods.wardtube.util.Permissions
import com.mwmods.wardtube.util.PlaylistLinks
import com.mwmods.wardtube.util.UrlUtils
import com.mwmods.wardtube.web.BackgroundWebView
import com.mwmods.wardtube.web.ErrorKind
import com.mwmods.wardtube.web.NativeBridge
import com.mwmods.wardtube.web.PageEvents
import com.mwmods.wardtube.web.PageScripts
import com.mwmods.wardtube.web.WardTubeChromeClient
import com.mwmods.wardtube.web.WardTubeWebViewClient
import com.mwmods.wardtube.web.WebTargets

class MainActivity : AppCompatActivity(), PageEvents, WardTubeChromeClient.MicrophoneListener {

    private lateinit var prefs: Prefs
    private lateinit var webView: BackgroundWebView
    private lateinit var appWindow: View
    private lateinit var statusBarSpace: View
    private lateinit var bottomBar: View
    private lateinit var navBarSpace: View

    // Videos play in a second WebView (the player) that is full screen or a small floating box (the mini player),
    // so the page being browsed stays untouched behind it.
    private lateinit var webArea: FrameLayout
    private lateinit var playerHost: PlayerHost
    private lateinit var playerScripts: PageScripts
    private var playerChrome: WardTubeChromeClient? = null
    private var browseLoaded = false
    private var redirectedUrl: String? = null
    private var playerNavigatedAwayFrom: String? = null
    private var rendererGone = false

    private val targets = object : WebTargets {
        override val browse: WebView get() = webView
        override val current: WebView
            get() = if (playerHost.mode == PlayerHost.Mode.EXPANDED) playerHost.webView ?: webView else webView
        override val video: WebView? get() = playerHost.webView
        override val all: List<WebView> get() = listOfNotNull(webView, playerHost.webView)
        override fun openUrl(url: String) = this@MainActivity.openUrl(url)
    }

    private lateinit var adBlocker: AdBlocker

    /** The ad blocker is shared with the browser screen and outlives this activity: the listener is removed in onDestroy. */
    private val filtersListener: () -> Unit = {
        if (!isDestroyed) {
            scripts.install(webView)
            scripts.refreshCss(webView)
            playerHost.webView?.let {
                playerScripts.install(it)
                playerScripts.refreshCss(it)
            }
        }
    }
    private lateinit var scripts: PageScripts
    private lateinit var chromeClient: WardTubeChromeClient
    private lateinit var menuController: MenuController

    private val noisyReceiver = BecomingNoisyReceiver()
    private var errorSnackbar: Snackbar? = null

    // Dark mode: the theme this activity was built with, and whether it is visible right now.
    private var createdNight = false
    private var resumed = false
    private var recreating = false

    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleepTick = object : Runnable {
        override fun run() {
            if (!resumed) return
            updateSleepButton()
            sleepHandler.postDelayed(this, 1000)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* nothing to do */ }

    // Voice search: the microphone permission is requested at runtime, either for YouTube's own mic button
    // (a pending WebView request) or for the voice search button of this app.
    private var pendingMicRequest: PermissionRequest? = null
    private var voiceSearchPending = false

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingMicRequest
            pendingMicRequest = null
            if (request != null) {
                if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
            }
            val voiceSearch = voiceSearchPending
            voiceSearchPending = false
            if (granted) {
                if (voiceSearch) launchSpeechRecognizer()
            } else if (voiceSearch || request != null) {
                showMicDeniedMessage()
            }
        }

    // Downloads: Android 9 and older need WRITE_EXTERNAL_STORAGE before a file can be saved.
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadTitle: String? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val url = pendingDownloadUrl
            val title = pendingDownloadTitle
            pendingDownloadUrl = null
            pendingDownloadTitle = null
            if (granted && url != null) {
                Downloader.chooseAndStart(this, url, appWindow, title)
            } else if (!granted) {
                Snackbar.make(appWindow, R.string.storagePermissionDenied, Snackbar.LENGTH_LONG).show()
            }
        }

    private val speechRecognizer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                showBrowse()
                loadBrowse(SEARCH_URL + Uri.encode(text.trim()))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        createdNight = isNight()
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        webView = findViewById(R.id.webView)
        webArea = findViewById(R.id.webArea)
        appWindow = findViewById(R.id.appWindow)
        statusBarSpace = findViewById(R.id.statusBarSpace)
        bottomBar = findViewById(R.id.bottomBar)
        navBarSpace = findViewById(R.id.navBarSpace)

        applyWindowInsets()

        adBlocker = AdBlocker.shared(this, prefs)
        scripts = PageScripts(this, prefs, adBlocker, route = "browse")
        playerScripts = PageScripts(this, prefs, adBlocker, route = "player")

        configureWebView(webView)

        chromeClient = WardTubeChromeClient(
            findViewById<ProgressBar>(R.id.progress),
            findViewById<FrameLayout>(R.id.customViewContainer),
            FullscreenHandler { webView },
            this
        )
        webView.webChromeClient = chromeClient
        webView.webViewClient = WardTubeWebViewClient(adBlocker, scripts, this)
        webView.addJavascriptInterface(createBridge(), BRIDGE_NAME)
        scripts.install(webView)
        watchScrollDirection(webView)

        playerHost = PlayerHost(
            area = webArea,
            container = findViewById(R.id.playerContainer),
            overlay = findViewById(R.id.miniOverlay),
            playPauseButton = findViewById<ImageButton>(R.id.miniPlayPause),
            closeButton = findViewById<ImageButton>(R.id.miniClose),
            createWebView = { createPlayerWebView() },
            releaseWebView = { releasePlayerWebView(it) },
            onModeChanged = { onPlayerModeChanged(it) },
        )
        updateBarColors()

        menuController = MenuController(
            activity = this,
            targets = targets,
            prefs = prefs,
            adBlocker = adBlocker,
            applyScriptSettings = { applyScriptSettings() },
            snackbarAnchor = appWindow,
            openBrowser = { startActivity(Intent(this, BrowserActivity::class.java)) },
            onThemeChanged = { recreateOnce() },
            onVoiceSearch = { startVoiceSearch() },
            onDownload = { startDownloadFlow(it) },
            onSleepTimerChanged = { updateSleepButton(force = true) },
            onMinimize = { playerHost.minimize() },
            onAutoHideBarChanged = { if (!prefs.autoHideBar) setBarHidden(false) }
        )
        menuController.setUp(findViewById<ActionMenuView>(R.id.menu_main))

        // EasyList / Liste AR load in the background; pages opened from now on get their rules.
        adBlocker.addOnFiltersChanged(filtersListener)
        adBlocker.start()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isFullscreen -> exitFullscreen()
                    playerHost.mode == PlayerHost.Mode.EXPANDED -> playerHost.minimize()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        val restored = savedInstanceState?.getString(STATE_URL)
        val restoredPlayer = savedInstanceState?.getString(STATE_PLAYER_URL)
        when {
            restored != null || restoredPlayer != null -> {
                loadBrowse(restored?.takeUnless { UrlUtils.isVideoUrl(it) } ?: browseHomepage())
                if (restoredPlayer != null) {
                    playerHost.play(restoredPlayer)
                    if (savedInstanceState?.getBoolean(STATE_PLAYER_MINI) == true) playerHost.minimize()
                }
            }
            !loadUrlFromIntent(intent) -> openUrl(prefs.homepage)
        }
    }

    // ---- Opening pages and videos ----

    /** True while a video is in the system's full-screen mode (in either WebView). */
    private val isFullscreen: Boolean
        get() = chromeClient.isFullscreen || playerChrome?.isFullscreen == true

    private fun exitFullscreen() {
        chromeClient.exitFullscreen()
        playerChrome?.exitFullscreen()
    }

    /** The start page of the browsing WebView (a video saved as the home page plays in the player instead). */
    private fun browseHomepage(): String =
        if (UrlUtils.isVideoUrl(prefs.homepage)) Prefs.DEFAULT_HOMEPAGE else prefs.homepage

    private fun loadBrowse(url: String) {
        setBarHidden(false)
        browseLoaded = true
        if (::playerHost.isInitialized) playerHost.miniHidden = UrlUtils.isShortsPage(url)
        webView.loadUrl(url)
    }

    /** Opens a link: a video plays in the player, any other page loads in the browsing WebView. */
    private fun openUrl(url: String) {
        if (UrlUtils.isVideoUrl(url)) {
            openVideo(url)
        } else {
            showBrowse()
            loadBrowse(url)
        }
    }

    private fun openVideo(url: String) {
        if (!browseLoaded) loadBrowse(browseHomepage()) // something to browse behind the mini player
        errorSnackbar?.dismiss()
        errorSnackbar = null
        playerHost.play(url)
    }

    /** Hides a full-screen video behind the browsing page; it goes on playing in the mini player. */
    private fun showBrowse() {
        if (playerHost.mode == PlayerHost.Mode.EXPANDED) playerHost.minimize()
    }

    /** A link to another YouTube page was tapped in the player: show that page and leave the video playing. */
    private fun openPageFromPlayer(url: String) {
        if (isDestroyed) return
        if (UrlUtils.isShortsPage(url)) pauseVideo() // Shorts play on their own
        showBrowse()
        loadBrowse(url)
    }

    private fun pauseVideo() {
        PlayerController.pause()
        if (::playerHost.isInitialized) playerHost.setPlaying(false)
    }

    private fun applyScriptSettings() {
        scripts.applySettings(webView)
        playerHost.webView?.let { playerScripts.applySettings(it) }
    }

    private fun createBridge() = NativeBridge(
        onOpenVideo = { url -> if (!isDestroyed) openVideo(url) },
        onOpenPage = { url -> openPageFromPlayer(url) },
    )

    // ---- The video player ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayerWebView(): BackgroundWebView {
        val view = BackgroundWebView(this)
        configureWebView(view)
        // The video starts by itself when it opens: there is no tap inside this page to count as a user gesture.
        view.settings.mediaPlaybackRequiresUserGesture = false
        val chrome = WardTubeChromeClient(
            findViewById<ProgressBar>(R.id.progress),
            findViewById<FrameLayout>(R.id.customViewContainer),
            FullscreenHandler { view },
            this
        )
        playerChrome = chrome
        view.webChromeClient = chrome
        view.webViewClient = WardTubeWebViewClient(adBlocker, playerScripts, PlayerPageEvents())
        view.addJavascriptInterface(createBridge(), BRIDGE_NAME)
        view.keepVisible = prefs.backgroundPlay
        playerNavigatedAwayFrom = null
        playerScripts.install(view)
        return view
    }

    private fun releasePlayerWebView(view: BackgroundWebView) {
        playerScripts.forget()
        playerChrome?.exitFullscreen()
        playerChrome = null
        view.stopLoading()
        view.destroy()
    }

    private fun onPlayerModeChanged(mode: PlayerHost.Mode) {
        if (mode == PlayerHost.Mode.EXPANDED) setBarHidden(false)
        updateBarColors()
        updateBackgroundPlayback()
        if (::menuController.isInitialized) menuController.setMinimizeVisible(mode == PlayerHost.Mode.EXPANDED)
        if (mode != PlayerHost.Mode.NONE && prefs.backgroundPlay) askForNotificationPermission()
    }

    /** The bars are dark while a video is full screen, in the app's colour while browsing. */
    private fun updateBarColors() {
        val watching = playerHost.mode == PlayerHost.Mode.EXPANDED
        val color = ContextCompat.getColor(this, if (watching) R.color.barWatch else R.color.barBrowse)
        statusBarSpace.setBackgroundColor(color)
        bottomBar.setBackgroundColor(color)
        navBarSpace.setBackgroundColor(color)
    }

    /** What the player's WebView reports back. */
    private inner class PlayerPageEvents : PageEvents {

        override fun onPageChanged(url: String?) {
            if (url == null || !UrlUtils.isYouTubeUrl(url)) return
            if (UrlUtils.isVideoUrl(url)) {
                playerNavigatedAwayFrom = null
                playerHost.noteUrl(url)
                playerHost.remeasureSoon()
                return
            }
            // YouTube moved the player's page to something that is not a video without loading it (a tap the page
            // script could not catch): put the video back and show that page in the browsing WebView instead.
            if (url == playerNavigatedAwayFrom) return
            playerNavigatedAwayFrom = url
            val player = playerHost.webView ?: return
            if (player.canGoBack()) player.goBack()
            openPageFromPlayer(url)
        }

        override fun onInternalNavigation(url: String): Boolean {
            if (UrlUtils.isVideoUrl(url)) return false
            webArea.post { openPageFromPlayer(url) }
            return true
        }

        override fun onLoadError(kind: ErrorKind, description: String) {
            showLoadError(kind, description) { playerHost.webView?.reload() }
        }

        override fun onRenderProcessGone() = handleRendererGone()
    }

    /** Tells the activity when one of the two WebViews goes full screen or back. */
    private inner class FullscreenHandler(private val target: () -> WebView?) :
        WardTubeChromeClient.FullscreenListener {
        override fun onFullscreenChanged(fullscreen: Boolean) = handleFullscreen(fullscreen, target())
    }

    // ---- Setup ----

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            statusBarSpace.updateLayoutParams { height = bars.top }
            bottomBar.updatePadding(bottom = bars.bottom)
            appWindow.updatePadding(left = bars.left, right = bars.right)
            navigationInset = bars.bottom
            updateNavBarSpace()
            insets
        }
    }

    // ---- Hiding the bottom bar while scrolling ----

    private var barHidden = false
    private var barHeight = 0
    private var navigationInset = 0

    /** While the bar is hidden, the page keeps clear of the system navigation bar through this space instead. */
    private fun updateNavBarSpace() {
        val height = if (barHidden) navigationInset else 0
        if (navBarSpace.layoutParams.height != height) navBarSpace.updateLayoutParams { this.height = height }
    }

    /** Slides the bottom bar away ([hidden] = true), freeing its space for the page, or brings it back. */
    private fun setBarHidden(hidden: Boolean) {
        if (!::bottomBar.isInitialized || hidden == barHidden) return
        barHidden = hidden
        bottomBar.animate().cancel()
        if (hidden) {
            barHeight = bottomBar.height
            bottomBar.animate()
                .translationY(barHeight.toFloat())
                .setDuration(BAR_ANIMATION_MS)
                .withEndAction {
                    if (barHidden) {
                        bottomBar.visibility = View.GONE
                        bottomBar.translationY = 0f
                        updateNavBarSpace()
                    }
                }
                .start()
        } else {
            updateNavBarSpace()
            if (bottomBar.visibility != View.VISIBLE) {
                bottomBar.visibility = View.VISIBLE
                bottomBar.translationY = barHeight.toFloat()
            }
            // Still sliding out? Then it simply turns around from where it is.
            bottomBar.animate().translationY(0f).setDuration(BAR_ANIMATION_MS).start()
        }
    }

    /**
     * Follows the finger on the browsing page: dragging up (scrolling down) hides the bottom bar, dragging down
     * (scrolling up) shows it again. Touches are only watched, never consumed, so the page scrolls as usual.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun watchScrollDirection(view: WebView) {
        val threshold = BAR_SCROLL_THRESHOLD_DP * resources.displayMetrics.density
        var lastX = 0f
        var lastY = 0f
        var travelled = 0f
        var resync = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    travelled = 0f
                    resync = false
                }
                // A finger was added or lifted (pinch zoom): the position kept so far no longer belongs to
                // the finger that is left, so measure again from where it is instead of counting a jump.
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> resync = true
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                    if (resync) {
                        lastX = event.x
                        lastY = event.y
                        travelled = 0f
                        resync = false
                    } else {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        lastX = event.x
                        lastY = event.y
                        if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                            // Distance in one direction; it starts over when the finger turns around.
                            travelled = if (travelled * dy < 0f) dy else travelled + dy
                            if (travelled <= -threshold) onPageScrolled(down = true)
                            else if (travelled >= threshold) onPageScrolled(down = false)
                        }
                    }
                }
            }
            false
        }
    }

    private fun onPageScrolled(down: Boolean) {
        if (!prefs.autoHideBar || isFullscreen) return
        if (playerHost.mode == PlayerHost.Mode.EXPANDED) return
        // Shorts swipe up and down all the time: keep the bar where it is reachable and do not resize the page.
        if (UrlUtils.isShortsPage(webView.url)) {
            setBarHidden(false)
            return
        }
        setBarHidden(down)
    }

    /** Settings shared by the browsing WebView and the player. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        CookieHelper.setAccepting(view, prefs.cookies)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        view.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        view.apply {
            isHorizontalScrollBarEnabled = false
            isScrollbarFadingEnabled = true
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.webBackground))
            setNetworkAvailable(true)
        }
        applyWebViewDarkMode(view)
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * In dark mode the WebView shows YouTube's own dark theme, and darkens pages that have none.
     * The app theme (DayNight) decides whether the WebView treats the app as dark.
     */
    @Suppress("DEPRECATION")
    private fun applyWebViewDarkMode(view: WebView) {
        val night = isNight()
        when {
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ->
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, night)
            WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) -> {
                WebSettingsCompat.setForceDark(
                    view.settings,
                    if (night) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
                if (night && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(
                        view.settings,
                        WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                    )
                }
            }
        }
    }

    /** Rebuilds the activity with the new theme. The saved URL is restored by onCreate(). */
    private fun recreateOnce() {
        if (recreating) return
        recreating = true
        recreate()
    }

    // ---- Keep screen on while a video plays ----

    private val keepAwakeHandler = Handler(Looper.getMainLooper())
    private val keepAwakePoll = object : Runnable {
        override fun run() {
            if (!resumed) return
            PlayerController.queryState { state ->
                setKeepScreenOn(isFullscreen || state?.playing == true)
                if (state != null && playerHost.mode == PlayerHost.Mode.MINI) playerHost.setPlaying(state.playing)
            }
            keepAwakeHandler.postDelayed(this, if (playerHost.mode == PlayerHost.Mode.MINI) 1000L else 3000L)
        }
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- Sleep timer: the button shows the remaining time ----

    private var lastSleepUpdate = 0L

    /**
     * Refreshes the timer entry in the three-dots menu. Every second while that menu is open; otherwise
     * only now and then, so the menu is not rebuilt for nothing. Also notices when the time is up.
     */
    private fun updateSleepButton(force: Boolean = false) {
        SleepTimer.checkExpired()
        if (!::menuController.isInitialized) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && SleepTimer.isActive && !menuController.isOverflowShowing() && now - lastSleepUpdate < 10_000L) return
        lastSleepUpdate = now
        val left = SleepTimer.remainingSeconds
        if (!SleepTimer.isActive || left <= 0) {
            menuController.setSleepLabel(null)
            return
        }
        val h = left / 3600
        val m = (left % 3600) / 60
        val sec = left % 60
        val time = if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
        menuController.setSleepLabel(time)
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        resumed = true
        com.mwmods.wardtube.util.CrashLog.showIfAny(this)
        // The system theme changed while the app was in the background (see onConfigurationChanged).
        if (isNight() != createdNight) {
            recreateOnce()
            return
        }
        // The playback service keeps running while a video page is open (it was started in the foreground,
        // which newer Android versions require); it is only stopped when the user leaves the video page.
        if (!playerHost.isActive) PlaybackService.stop(this)
        // Automatic ad-filter update: every four days, checked each time the app comes to the foreground.
        adBlocker.refreshIfStale()
        sleepHandler.removeCallbacks(sleepTick)
        sleepHandler.post(sleepTick)
        noisyReceiver.register(this)
        keepAwakeHandler.removeCallbacks(keepAwakePoll)
        keepAwakeHandler.post(keepAwakePoll)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is listed in the manifest's configChanges, so the system does not rebuild the activity when
        // the theme flips. Do it here, but only while visible: rebuilding in the background would stop
        // background playback. onResume() picks the change up later.
        if (resumed && isNight() != createdNight) recreateOnce()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        keepAwakeHandler.removeCallbacks(keepAwakePoll)
        sleepHandler.removeCallbacks(sleepTick)
        setKeepScreenOn(false)
        noisyReceiver.unregister(this)
        if (recreating) return // being rebuilt for a theme change: no background service needed
        if (prefs.backgroundPlay) {
            // Normally already running (started while the page was visible); this is only a fallback.
            if (playerHost.isActive) PlaybackService.start(this)
        } else {
            PlayerController.pause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.url?.let { outState.putString(STATE_URL, it) }
        if (playerHost.isActive) {
            playerHost.currentUrl?.let { outState.putString(STATE_PLAYER_URL, it) }
            outState.putBoolean(STATE_PLAYER_MINI, playerHost.mode == PlayerHost.Mode.MINI)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadUrlFromIntent(intent)
    }

    override fun onDestroy() {
        // Leaving the app for real (not a rebuild for a theme change) cancels the timer.
        if (isFinishing && !recreating) SleepTimer.cancel()
        if (::adBlocker.isInitialized) adBlocker.removeOnFiltersChanged(filtersListener)
        if (::playerHost.isInitialized) playerHost.close()
        PlaybackService.stop(this)
        PlayerController.detach(webView)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun loadUrlFromIntent(intent: Intent): Boolean {
        val uri = intent.data
        if (intent.action != Intent.ACTION_VIEW || uri == null) return false
        // Only web links: never let another app load file:, javascript: or similar into the WebView.
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val url = uri.toString()
        if (UrlUtils.isVideoUrl(url) || url != webView.url) openUrl(url)
        return true
    }

    // ---- PageEvents ----

    override fun onPageStarted(url: String?) {
        errorSnackbar?.dismiss()
        errorSnackbar = null
    }

    override fun onPageChanged(url: String?) {
        if (::menuController.isInitialized) menuController.updateAdBlockTitle()
        val video = url?.takeIf { UrlUtils.isVideoUrl(it) }
        if (video != null) {
            // YouTube went to a video page without a load (a tap its page script could not catch). Videos play in
            // the player: stop this page's video, go back to the page that was being browsed and play it there.
            if (video != redirectedUrl) {
                redirectedUrl = video
                webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
                if (webView.canGoBack()) webView.goBack() else loadBrowse(browseHomepage())
                openVideo(video)
            }
        } else {
            redirectedUrl = null
            if (url != null && ::playerHost.isInitialized) {
                // A Short plays by itself: do not let it talk over a video in the mini player, and keep the
                // (paused) mini player out of its way until the Short is left.
                val onShorts = UrlUtils.isShortsPage(url)
                if (onShorts && playerHost.isActive) pauseVideo()
                playerHost.miniHidden = onShorts
            }
        }
    }

    override fun onInternalNavigation(url: String): Boolean {
        if (!UrlUtils.isVideoUrl(url)) return false
        webArea.post { openVideo(url) }
        return true
    }

    /**
     * Background play needs a foreground service that is started while the app is still visible:
     * Android 12+ refuses to start one from the background, and Android 14+ is stricter still.
     * It runs while the player has a video, full screen or in the mini player.
     */
    fun updateBackgroundPlayback() {
        val active = playerHost.isActive && prefs.backgroundPlay
        playerHost.webView?.keepVisible = active
        if (active) PlaybackService.start(this) else PlaybackService.stop(this)
    }

    override fun onLoadError(kind: ErrorKind, description: String) {
        showLoadError(kind, description) { retry() }
    }

    private fun showLoadError(kind: ErrorKind, description: String, onRetry: () -> Unit) {
        val message = when (kind) {
            ErrorKind.NETWORK_CHANGED -> {
                onRetry()
                return
            }
            ErrorKind.OFFLINE -> getString(R.string.errorNoInternet)
            ErrorKind.OTHER -> getString(R.string.error) + " " + description
        }
        setBarHidden(false) // the notice is anchored to the bar
        // Sits above the bottom bar so Library/Downloads stay reachable, and an offline notice
        // disappears by itself instead of staying on screen for good.
        val duration = if (kind == ErrorKind.OFFLINE) Snackbar.LENGTH_LONG else Snackbar.LENGTH_INDEFINITE
        errorSnackbar = Snackbar.make(appWindow, message, duration)
            .setAnchorView(bottomBar)
            .setAction(R.string.refresh) { onRetry() }
            .also { it.show() }
    }

    override fun onRenderProcessGone() = handleRendererGone()

    /** Both WebViews share one renderer, so they die together; the saved URLs are restored by onCreate() after the rebuild. */
    private fun handleRendererGone() {
        if (rendererGone || isFinishing || isDestroyed) return
        rendererGone = true
        recreate()
    }

    private fun retry() {
        loadBrowse(webView.url ?: browseHomepage())
    }

    // ---- Fullscreen video ----

    private fun handleFullscreen(fullscreen: Boolean, target: WebView?) {
        appWindow.visibility = if (fullscreen) View.GONE else View.VISIBLE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        setKeepScreenOn(fullscreen)
        // Fullscreen locks to landscape like the YouTube app: it flips between the two landscape sides
        // with the sensor (even when the system auto-rotate toggle is off) and holding the phone upright
        // does not rotate it back to portrait.
        requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            target?.evaluateJavascript("document.body.style.overflowX='hidden';window.scrollTo(0,0);", null)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            target?.evaluateJavascript("window.scrollTo(0,0);document.body.style.overflowX='';", null)
        }
    }

    // ---- Downloads ----

    private fun startDownloadFlow(url: String) {
        // A Short is never live (and the player may be holding a different video, even a live one). A playlist or
        // channel page is not a video at all: it is read as a list, whatever the player holds.
        if (UrlUtils.isShortsPage(url) || isListPage(url)) {
            beginDownload(url)
            return
        }
        // A live stream has no end to save: ask YouTube's player first and say so instead of starting a download
        // that cannot work. If the player cannot tell (no video on the page yet), the download goes on as usual.
        PlayerController.queryState { state ->
            if (isDestroyed) return@queryState
            if (state?.isLive == true) {
                Snackbar.make(appWindow, R.string.liveNotDownloadable, Snackbar.LENGTH_LONG).show()
            } else {
                beginDownload(url)
            }
        }
    }

    /** A YouTube playlist or channel page (a list of videos), as opposed to a video. */
    private fun isListPage(url: String): Boolean =
        UrlUtils.isYouTubeUrl(url) && PlaylistLinks.analyze(url).kind == PlaylistLinks.Kind.LIST

    private fun beginDownload(url: String) {
        // The page title names the download in the Library while it waits in the queue.
        // A Short and a list page are on the browsing page, whatever the player may be holding.
        val source = if (UrlUtils.isShortsPage(url) || isListPage(url)) webView else playerHost.webView ?: webView
        val title = source.title.orEmpty()
            .removeSuffix(" - YouTube")
            .replace(Regex("^\\(\\d+\\)\\s*"), "") // YouTube prefixes "(3) " when there are notifications
            .trim()
            .takeUnless { it.isEmpty() || it.equals("YouTube", ignoreCase = true) }
        if (Permissions.hasLegacyWrite(this)) {
            Downloader.chooseAndStart(this, url, appWindow, title)
        } else {
            pendingDownloadUrl = url
            pendingDownloadTitle = title
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ---- Voice search ----

    override fun onMicrophoneRequested(request: PermissionRequest) {
        if (Permissions.hasMicrophone(this)) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            return
        }
        pendingMicRequest?.deny() // a newer request replaces an unanswered one
        pendingMicRequest = request
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onMicrophoneRequestCanceled(request: PermissionRequest) {
        if (pendingMicRequest == request) pendingMicRequest = null
    }

    private fun startVoiceSearch() {
        if (Permissions.hasMicrophone(this)) {
            launchSpeechRecognizer()
        } else {
            voiceSearchPending = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Android's speech recognizer; the spoken text is searched on YouTube. */
    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voiceSearchPrompt))
        try {
            speechRecognizer.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(appWindow, R.string.voiceNotAvailable, Snackbar.LENGTH_LONG).show()
        }
    }

    /** After a permanent refusal the system dialog no longer appears, so offer the app settings. */
    private fun showMicDeniedMessage() {
        Snackbar.make(appWindow, R.string.micPermissionDenied, Snackbar.LENGTH_LONG)
            .setAction(R.string.openSettings) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .show()
    }

    // ---- Notification permission (Android 13+), asked once when background play is first useful ----

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.notificationPermissionAsked) return
        prefs.notificationPermissionAsked = true
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val STATE_URL = "url"
        const val STATE_PLAYER_URL = "playerUrl"
        const val STATE_PLAYER_MINI = "playerMini"
        const val BRIDGE_NAME = "WardTubeNative"
        const val SEARCH_URL = "https://m.youtube.com/results?search_query="
        const val BAR_ANIMATION_MS = 150L
        const val BAR_SCROLL_THRESHOLD_DP = 24f
    }
}
