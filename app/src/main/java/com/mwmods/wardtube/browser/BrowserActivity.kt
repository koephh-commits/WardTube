// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R
import com.mwmods.wardtube.adblock.AdBlocker
import com.mwmods.wardtube.download.Downloader
import com.mwmods.wardtube.download.RequestHeaders
import com.mwmods.wardtube.ui.LibraryActivity
import com.mwmods.wardtube.util.CookieHelper
import com.mwmods.wardtube.util.DownloadStore
import com.mwmods.wardtube.util.Permissions
import com.mwmods.wardtube.util.ShareLinks
import com.mwmods.wardtube.util.UrlUtils
import kotlin.math.hypot
import org.json.JSONObject

/**
 * The browser screen: paste (or type) the link of a video from any site and download it, or open the page in a small
 * browser and download from there.
 *
 * - Globe button in the field (or Go on the keyboard): a link is looked at first (see [onSearch]). A direct link to a
 *   file goes to the file's confirmation window; a link of YouTube, TikTok or another video site goes to the same
 *   "Download as" dialog and queue that sharing a link to the app uses (yt-dlp); any other link is an ordinary page and
 *   is opened here, where the floating button finds its videos and files. Text that is not a link is searched in a
 *   WebView.
 * - A file the page offers for download (zip, pdf, apk, a direct video link...) is saved by the system's download
 *   manager into Download/WardTube, so it shows in the Library too.
 * - The app's ad blocker (the same rules and the same on/off switch as the YouTube screen) filters every request of
 *   the page.
 * - A floating download button, there only while a page is open, is dimmed until the page offers a video or a file (see
 *   [MediaSniffer]); then it lists what was found and downloads the choice. It can be dragged anywhere on the screen.
 * - The Paste button is on the main screen only: it is hidden while a page is open.
 * - The arrow at the top leaves the browser screen; going back through the pages is the phone's back button.
 * - The title sits in the top row, next to the back, Library and History buttons, and the link field right under it.
 *   While a page is open the title gives way to four buttons at the end of the row: previous page, next page, Reload
 *   (reloads the page) and Home (back to the main screen, where the WebView is released and the field is emptied).
 * - Every page opened is written to the browsing history ([BrowserHistory]); the History button opens its screen
 *   ([HistoryActivity]) and a page tapped there is opened here.
 * - The globe at the start of the link field starts the search; an x at its end clears it. The x shows whenever the
 *   field has text (typed, pasted with the Paste button, or the address of the open page), focused or not.
 * - A page is written to the history only once it has settled: redirects and address changes made while it loads
 *   (a search page often changes its address twice) count as one visit (see [scheduleRecord]).
 *
 * The WebView exists only while a page is open: the plain paste screen costs no browser memory.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var title: TextView
    private lateinit var fieldLayout: TextInputLayout
    private lateinit var linkField: TextInputEditText
    private lateinit var webContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private var webView: WebView? = null
    private lateinit var detectButton: FloatingActionButton
    private lateinit var homeButtons: View
    private lateinit var reloadButton: ImageButton
    private lateinit var homeButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton

    /** True while the open page offers something; only changes how the floating button looks (it is always tappable). */
    private var detectFound = false

    /** Where the floating button was left, as fractions of the area it moves in (-1: still in its own corner). */
    private var detectX = -1f
    private var detectY = -1f
    private var detectDragging = false

    /** The pages opened here, newest first (shown by [HistoryActivity]). */
    private val history by lazy { BrowserHistory.get(this) }

    /** The app's single ad blocker, shared with the YouTube screen: the filter lists are loaded only once. */
    private val adBlocker by lazy { AdBlocker.shared(this, prefs) }

    /** Finds the videos and files the open page offers; the floating button follows what it finds. */
    private val sniffer = MediaSniffer(pageUrl = { webView?.url }, onChanged = { showDetected(it) })

    private val handler = Handler(Looper.getMainLooper())

    /** True between the start and the end of a page load. */
    private var pageLoading = false

    /** True once the open WebView has shown a page (a link that was a file never does: see the download listener). */
    private var pageShown = false

    /** A page waits to be written to the history (see [scheduleRecord]). */
    private var recordPending = false
    private val recordRunnable = Runnable { recordPage(force = false) }

    /** The page (without its #fragment) the sniffer's list belongs to. */
    private var sniffedPage: String? = null

    /** What to run once the permission being asked for is answered. */
    private var afterPermission: (() -> Unit)? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterPermission
            afterPermission = null
            if (!granted) {
                snack(R.string.storagePermissionDenied)
            } else if (action != null) {
                withPermissions(action)
            }
        }

    // Only asked once; downloads work without it, the progress notification is just hidden.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val action = afterPermission
            afterPermission = null
            action?.invoke()
        }

    // A page tapped in the history screen is opened here.
    private val historyScreen =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val url = result.data?.getStringExtra(HistoryActivity.EXTRA_URL)
            if (result.resultCode == RESULT_OK && !url.isNullOrEmpty()) openPage(url)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        prefs = Prefs(this)

        root = findViewById(R.id.browserRoot)
        title = findViewById(R.id.browserTitle)
        fieldLayout = findViewById(R.id.browserFieldLayout)
        linkField = findViewById(R.id.browserField)
        webContainer = findViewById(R.id.browserWebContainer)
        progress = findViewById(R.id.browserProgress)
        detectButton = findViewById(R.id.browserDetect)
        homeButtons = findViewById(R.id.browserHomeButtons)
        reloadButton = findViewById(R.id.browserReload)
        homeButton = findViewById(R.id.browserHome)
        prevButton = findViewById(R.id.browserPrev)
        nextButton = findViewById(R.id.browserNext)
        reloadButton.setOnClickListener { webView?.reload() }
        homeButton.setOnClickListener { goHome() }
        prevButton.setOnClickListener { webView?.takeIf { it.canGoBack() }?.goBack() }
        nextButton.setOnClickListener { webView?.takeIf { it.canGoForward() }?.goForward() }
        findViewById<ImageButton>(R.id.browserHistory).setOnClickListener {
            hideKeyboard()
            recordPage(force = true)
            historyScreen.launch(Intent(this, HistoryActivity::class.java))
        }
        detectButton.setOnClickListener { onDetectClicked() }
        detectX = prefs.detectButtonX
        detectY = prefs.detectButtonY
        makeDraggable(detectButton)
        // Keeps the dragged button where it was left when the screen changes (keyboard, rotation) or it reappears.
        val keepPlace = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyDetectPosition() }
        root.addOnLayoutChangeListener(keepPlace)
        detectButton.addOnLayoutChangeListener(keepPlace)
        // This screen can be the first one a restored app opens: make sure the filter lists are loading.
        adBlocker.start()

        // The keyboard lifts the buttons too (adjustResize in the manifest makes this work before Android 11).
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, keyboard.bottom),
            )
            insets
        }

        // The arrow leaves the browser; the phone's own back button goes back through the pages (see below).
        findViewById<ImageButton>(R.id.browserBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.browserLibrary).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<FloatingActionButton>(R.id.browserPaste).setOnClickListener { pasteFromClipboard() }
        fieldLayout.setStartIconOnClickListener { onSearch() }
        // The x: there while the field has text; it empties the field and brings the keyboard up for the next text.
        fieldLayout.isEndIconVisible = !linkField.text.isNullOrEmpty()
        linkField.doAfterTextChanged { fieldLayout.isEndIconVisible = !it.isNullOrEmpty() }
        fieldLayout.setEndIconOnClickListener {
            linkField.setText("")
            linkField.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(linkField, 0)
        }
        linkField.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                onSearch()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = webView
                when {
                    web != null && web.canGoBack() -> web.goBack()
                    web != null -> closePage()
                    else -> finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        adBlocker.refreshIfStale()
    }

    override fun onPause() {
        recordPage(force = true)
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        closePage()
        super.onDestroy()
    }

    // ---- The link field ----

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString()?.trim() else null
        if (text.isNullOrEmpty()) {
            snack(R.string.browserClipboardEmpty)
            return
        }
        // A copied text is rarely a bare link ("Look at this! https://..."): keep only the link when there is one.
        val link = (ShareLinks.find(text) as? ShareLinks.Result.Found)?.link?.url
        linkField.setText(link ?: text)
        linkField.setSelection(linkField.text?.length ?: 0)
    }

    /** The text as an address: a link as it is, or a bare "site.com/page" completed with https. Null for anything else. */
    private fun asUrl(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return t
        return if ('.' in t) "https://$t" else null
    }

    /** Permissions first (Android 9 storage, Android 13 notifications), then [action]. */
    private fun withPermissions(action: () -> Unit) {
        if (!Permissions.hasLegacyWrite(this)) {
            afterPermission = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (shouldAskForNotifications()) {
            prefs.notificationPermissionAsked = true
            afterPermission = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        action()
    }

    private fun shouldAskForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.notificationPermissionAsked) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    // ---- The page ----

    /**
     * The globe button and Go on the keyboard. What a link is decides what happens:
     *  - the address is itself a file, a video / audio file or a streamed video: the file's confirmation window;
     *  - a link of YouTube, TikTok or another site known for its videos ([ShareLinks.isMediaSite]): the "Download as"
     *    window (a page of those sites that is not a video says so and offers to open the site);
     *  - any other link is an ordinary site: it is opened here. If it turns out to be a file (an address with no
     *    extension that the server answers with a download), the WebView's download listener asks about it; if it is a
     *    page, the floating button finds the videos and files inside it, frames included.
     * Text that is not a link is searched with Google in the browser.
     */
    private fun onSearch() {
        val text = linkField.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            snack(R.string.browserNothingToDownload)
            return
        }
        hideKeyboard()
        val address = asUrl(text) ?: ShareLinks.firstUrl(text)
        if (address == null) {
            openPage(SEARCH_URL + Uri.encode(text))
            return
        }
        val file = sniffer.directFile(address)
        if (file != null) {
            startDetected(file)
            return
        }
        when (val result = ShareLinks.find(address)) {
            is ShareLinks.Result.Found ->
                if (ShareLinks.isMediaSite(result.link.url)) {
                    withPermissions { Downloader.chooseAndStart(this, result.link.url, root, null) }
                } else {
                    openPage(address)
                }
            // A YouTube / TikTok page that is not a video says so; any other site's home page is simply opened.
            else -> if (ShareLinks.isMediaSite(address)) notFound(address) else openPage(address)
        }
    }

    /** Nothing to download at [address]: says so, and offers to open the site anyway. */
    private fun notFound(address: String) {
        Snackbar.make(root, R.string.browserNothingFound, NOT_FOUND_MS)
            .setAction(R.string.browserOpenSite) { openPage(address) }
            .show()
    }

    private fun openPage(url: String) {
        hideKeyboard()
        val web = webView ?: createWebView()
        web.loadUrl(url)
        web.requestFocus()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(linkField.windowToken, 0)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web.setBackgroundColor(Color.WHITE)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            // No pop-up windows: they are how most download sites throw ads at the visitor.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieHelper.setAccepting(web, prefs.cookies)
        // The page's script tells what it offers through this (see MediaSniffer: every call is checked there).
        web.addJavascriptInterface(sniffer.bridge, MediaSniffer.BRIDGE_NAME)

        web.webViewClient = object : WebViewClient() {
            // Blocked requests get an empty answer; what is let through is looked at for videos and files.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null
                if (adBlocker.shouldBlock(request, view)) return adBlocker.emptyResponse()
                sniffer.inspect(request)
                return null
            }

            // http(s) pages stay here; anything else (intent:, market:, tel:...) is dropped.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase() ?: return false
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageLoading = true
                showAddress(url)
                pageChanged(url, newDocument = true)
            }

            // The page is visible / loaded: its script looks for videos and files (a second run only looks again).
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                pageShown = true
                view?.evaluateJavascript(MediaSniffer.SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(MediaSniffer.SCRIPT, null)
                pageLoading = false
                history.setTitle(view?.url, view?.title)
                updateNavButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                showAddress(url)
                pageChanged(url, newDocument = false)
                if (!isReload) scheduleRecord()
                updateNavButtons()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) snack(R.string.browserLoadError)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // The title of the page being opened goes to its entry in the browsing history.
            override fun onReceivedTitle(view: WebView?, title: String?) {
                history.setTitle(view?.url, title)
            }
        }
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            offerFileDownload(url, userAgent, contentDisposition, mimeType)
            // The link that was just opened was itself a file: there is no page to look at behind the window.
            if (!pageShown) handler.post { if (!pageShown) closePage() }
        }

        // Under the progress bar (index 0).
        webContainer.addView(web, 0)
        webContainer.visibility = View.VISIBLE
        // The title gives way to Reload / Home but keeps its room, so that the two buttons stay at the end of the row.
        title.visibility = View.INVISIBLE
        prevButton.visibility = View.VISIBLE
        nextButton.visibility = View.VISIBLE
        reloadButton.visibility = View.VISIBLE
        homeButton.visibility = View.VISIBLE
        // Paste belongs to the main screen only.
        homeButtons.visibility = View.GONE
        // Dimmed until the page offers something (see showDetected).
        detectButton.visibility = View.VISIBLE
        webView = web
        updateNavButtons()
        return web
    }

    /** The address bar follows the page, unless the person is typing in it. */
    private fun showAddress(url: String?) {
        if (url.isNullOrEmpty() || url == "about:blank" || linkField.hasFocus()) return
        linkField.setText(url)
    }

    /** Back to the plain paste screen: the WebView is released. */
    private fun closePage() {
        recordPage(force = true)
        val web = webView ?: return
        webView = null
        webContainer.removeView(web)
        web.stopLoading()
        web.destroy()
        sniffedPage = null
        pageLoading = false
        pageShown = false
        sniffer.clear()
        detectButton.visibility = View.GONE
        prevButton.visibility = View.GONE
        nextButton.visibility = View.GONE
        reloadButton.visibility = View.GONE
        homeButton.visibility = View.GONE
        homeButtons.visibility = View.VISIBLE
        webContainer.visibility = View.GONE
        progress.visibility = View.GONE
        if (!isFinishing) title.visibility = View.VISIBLE
    }

    /** The previous / next page buttons are dimmed (and do nothing) when the page has no page before / after it. */
    private fun updateNavButtons() {
        val web = webView
        setNavEnabled(prevButton, web != null && web.canGoBack())
        setNavEnabled(nextButton, web != null && web.canGoForward())
    }

    private fun setNavEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else NAV_DISABLED_ALPHA
    }

    /**
     * An address change of the open page: it is written to the browsing history once it has settled, not at once.
     * A page often changes its address while it loads (a search page: the typed address, then a redirect, then one
     * more parameter), and each of those would be a history entry of its own; a short wait that every new change
     * restarts, and that lasts while the page is still loading, leaves one entry: the page that stayed.
     */
    private fun scheduleRecord() {
        recordPending = true
        handler.removeCallbacks(recordRunnable)
        handler.postDelayed(recordRunnable, RECORD_DELAY_MS)
    }

    /** Writes the open page to the history. [force]: now, even while it still loads (the screen is being left). */
    private fun recordPage(force: Boolean) {
        handler.removeCallbacks(recordRunnable)
        if (!recordPending) return
        if (!force && pageLoading) {
            handler.postDelayed(recordRunnable, RECORD_DELAY_MS)
            return
        }
        recordPending = false
        val web = webView ?: return
        val url = web.url
        if (url.isNullOrEmpty()) return
        // Web pages only.
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        // While the page still loads its title can be the previous page's: it comes later, through onReceivedTitle.
        history.record(url, if (pageLoading) null else web.title)
    }

    /** The Home button: leaves the open page and shows the main screen again, with an empty field. */
    private fun goHome() {
        hideKeyboard()
        linkField.clearFocus()
        closePage()
        linkField.setText("")
    }

    // ---- Files offered by a page ----

    private fun offerFileDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        /** The name the page gave the file, when it is known; otherwise it is worked out from the address. */
        knownName: String? = null,
    ) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            // blob: and data: links only exist inside the page; the download manager cannot fetch them.
            snack(R.string.browserFileUnsupported)
            return
        }
        val name = knownName?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        val referer = webView?.url
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browserFileTitle)
            .setMessage(name)
            .setPositiveButton(R.string.download) { _, _ ->
                withPermissions { enqueueFile(url, userAgent, mimeType, name, referer) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Hands the file to the system's download manager, which saves it in a sub-folder of Download/WardTube. */
    @Suppress("DEPRECATION")
    private fun enqueueFile(url: String, userAgent: String?, mimeType: String?, name: String, referer: String?) {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "download" }
        val folder = DownloadStore.subFolderFor(safeName, mimeType.orEmpty()) ?: DownloadStore.FOLDER_FILES
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safeName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "${DownloadStore.FOLDER}/$folder/$safeName",
                )
            request.allowScanningByMediaScanner()
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            // The page's own identity, so a download that needs the visit (logged in, a hot-link check) still works.
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (!referer.isNullOrBlank()) request.addRequestHeader("Referer", referer)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Snackbar.make(root, getString(R.string.browserFileStarted, safeName), Snackbar.LENGTH_LONG)
                .setAction(R.string.library) { startActivity(Intent(this, LibraryActivity::class.java)) }
                .show()
        } catch (e: Exception) {
            snack(R.string.browserFileFailed)
        }
    }

    // ---- The floating download button ----

    /**
     * The sniffer's list belongs to one page: when the page changes, what the old one offered is dropped.
     * [newDocument]: a page load started (a reload counts); otherwise the same document went to another address
     * (a single-page site), and its script is asked to look again.
     */
    private fun pageChanged(url: String?, newDocument: Boolean) {
        val page = url?.substringBefore('#')
        if (page.isNullOrEmpty() || page == "about:blank") return
        if (!newDocument && page == sniffedPage) return
        sniffedPage = page
        sniffer.clear()
        if (!newDocument) webView?.evaluateJavascript(MediaSniffer.RESET_CALL, null)
        // The address can itself be a media file (a video link opened directly).
        sniffer.inspectPage(page)
    }

    /**
     * Full color while the open page offers something to download, dimmed otherwise (a tap then says nothing was found
     * yet); hidden together with the page. It stays enabled so that it can be dragged in both states.
     */
    private fun showDetected(items: List<Detected>) {
        val found = webView != null && items.isNotEmpty()
        val wasFound = detectFound
        detectFound = found
        detectButton.alpha = if (found) 1f else DISABLED_ALPHA
        detectButton.contentDescription = if (found) {
            getString(R.string.browserDetectFound, items.size)
        } else {
            getString(R.string.browserDetectNone)
        }
        if (found && !wasFound) {
            // A short pulse: the button has just come alive.
            detectButton.animate().cancel()
            detectButton.animate().scaleX(PULSE_SCALE).scaleY(PULSE_SCALE).setDuration(PULSE_MS).withEndAction {
                detectButton.animate().scaleX(1f).scaleY(1f).setDuration(PULSE_MS).start()
            }.start()
        }
    }

    private fun onDetectClicked() {
        val items = sniffer.snapshot()
        when {
            items.isEmpty() -> snack(R.string.browserDetectNone)
            items.size == 1 -> startDetected(items[0])
            else -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.browserDetectTitle)
                .setItems(items.map { labelFor(it) }.toTypedArray()) { _, which -> startDetected(items[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun labelFor(item: Detected): String {
        val kind = getString(
            when (item.kind) {
                Detected.Kind.PAGE -> R.string.browserKindPage
                Detected.Kind.FRAME -> R.string.browserKindFrame
                Detected.Kind.VIDEO -> R.string.browserKindVideo
                Detected.Kind.STREAM -> R.string.browserKindStream
                Detected.Kind.AUDIO -> R.string.browserKindAudio
                Detected.Kind.FILE -> R.string.browserKindFile
            }
        )
        val name = if (item.kind == Detected.Kind.PAGE) {
            webView?.title?.takeIf { it.isNotBlank() } ?: item.name
        } else {
            item.name
        }
        return "$kind — ${name.take(LABEL_NAME_LENGTH)}"
    }

    private fun startDetected(item: Detected) {
        when (item.kind) {
            // A video that lives inside the page: yt-dlp's job.
            Detected.Kind.PAGE -> downloadPageVideo()
            // A player in an iframe: yt-dlp is given the frame's page, told that the open page embeds it.
            Detected.Kind.FRAME -> withPermissions {
                Downloader.chooseAndStart(this, withReferer(item.url, webView?.url), root, webView?.title)
            }
            // A video file or a stream (m3u8 / mpd) the page loaded: the app's own queue (yt-dlp), so its progress shows
            // in the Library. It is sent what the page sent (see [withIdentity]), as the system's download manager was.
            Detected.Kind.VIDEO, Detected.Kind.STREAM -> {
                val scheme = Uri.parse(item.url).scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    // blob: and data: addresses only exist inside the page; nothing outside it can fetch them.
                    snack(R.string.browserFileUnsupported)
                } else {
                    withPermissions {
                        Downloader.chooseAndStart(this, withIdentity(item.url), root, webView?.title, direct = true)
                    }
                }
            }
            // Audio and other files: the system's download manager, after a confirmation that shows the name.
            else -> offerFileDownload(
                item.url, webView?.settings?.userAgentString, null, item.mimeType.ifEmpty { null }, item.name,
            )
        }
    }

    /**
     * A player in a frame often answers only when the page that embeds it is the referer. yt-dlp reads a referer that
     * is smuggled into the address (the part after `#__youtubedl_smuggle=`, which it removes before using the link).
     * YouTube's own embeds and addresses without an open page are given as they are.
     */
    private fun withReferer(frameUrl: String, page: String?): String {
        val referer = page?.substringBefore('#')
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: return frameUrl
        val host = Uri.parse(frameUrl).host?.lowercase().orEmpty()
        if (host.isEmpty() || UrlUtils.isInternalHost(host)) return frameUrl
        val data = JSONObject().put("referer", referer).toString()
        return frameUrl.substringBefore('#') + "#__youtubedl_smuggle=" + Uri.encode(data)
    }

    /**
     * A media address with what the open page would send for it: the page as referer, the WebView's user agent and the
     * cookies of the address's host. The video servers of many sites refuse a request that lacks them.
     * The file is named after the page's title, because its own name is often the same for every episode.
     */
    private fun withIdentity(fileUrl: String): String = RequestHeaders.attach(
        fileUrl,
        referer = webView?.url,
        userAgent = webView?.settings?.userAgentString,
        cookie = CookieManager.getInstance().getCookie(fileUrl),
        name = webView?.title,
    )

    private fun downloadPageVideo() {
        when (val result = ShareLinks.find(webView?.url.orEmpty())) {
            is ShareLinks.Result.Found -> withPermissions {
                Downloader.chooseAndStart(this, result.link.url, root, webView?.title)
            }
            ShareLinks.Result.NotVideo -> snack(R.string.shareNotVideo)
            ShareLinks.Result.NoLink -> snack(R.string.shareNoLink)
        }
    }

    // ---- Moving the floating download button ----

    /**
     * A finger that moves the button drags it (it stays inside the screen and its place is remembered); a touch that
     * does not move is still a tap.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(button: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTx = v.translationX
                    startTy = v.translationY
                    detectDragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!detectDragging && hypot(dx, dy) > slop) {
                        detectDragging = true
                        v.isPressed = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (detectDragging) moveDetectButton(startTx + dx, startTy + dy)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (detectDragging) {
                        detectDragging = false
                        rememberDetectPosition()
                    } else {
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (detectDragging) {
                        detectDragging = false
                        rememberDetectPosition()
                    }
                }
            }
            true
        }
    }

    /** Where the button's top-left corner can be, in the root's own coordinates; null before the first layout. */
    private fun detectRange(): RectF? {
        val button = detectButton
        if (button.width == 0 || root.width == 0) return null
        val edge = EDGE_DP * resources.displayMetrics.density
        val range = RectF(
            root.paddingLeft + edge,
            root.paddingTop + edge,
            root.width - root.paddingRight - button.width - edge,
            root.height - root.paddingBottom - button.height - edge,
        )
        return if (range.right > range.left && range.bottom > range.top) range else null
    }

    /** [translationX] / [translationY] are relative to the button's own corner of the layout. */
    private fun moveDetectButton(translationX: Float, translationY: Float) {
        val range = detectRange() ?: return
        val button = detectButton
        button.translationX = (button.left + translationX).coerceIn(range.left, range.right) - button.left
        button.translationY = (button.top + translationY).coerceIn(range.top, range.bottom) - button.top
    }

    private fun rememberDetectPosition() {
        val range = detectRange() ?: return
        val button = detectButton
        detectX = ((button.left + button.translationX - range.left) / (range.right - range.left)).coerceIn(0f, 1f)
        detectY = ((button.top + button.translationY - range.top) / (range.bottom - range.top)).coerceIn(0f, 1f)
        prefs.detectButtonX = detectX
        prefs.detectButtonY = detectY
    }

    /** Puts the button where it was left (nothing to do while it was never moved). */
    private fun applyDetectPosition() {
        if (detectDragging || detectX < 0f || detectY < 0f) return
        val range = detectRange() ?: return
        val button = detectButton
        button.translationX = range.left + detectX * (range.right - range.left) - button.left
        button.translationY = range.top + detectY * (range.bottom - range.top) - button.top
    }

    private fun snack(message: Int) = Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()

    private companion object {
        /** What a text that is not a link is searched with. */
        const val SEARCH_URL = "https://www.google.com/search?q="

        /** How long the "nothing found" message stays, so there is time to press its button. */
        const val NOT_FOUND_MS = 8000

        /** The closest the floating download button can get to the edge of the screen. */
        const val EDGE_DP = 8

        /** How long an address stays unchanged before the page is written to the history. */
        const val RECORD_DELAY_MS = 2000L

        /** The previous / next page buttons when there is no page to go to. */
        const val NAV_DISABLED_ALPHA = 0.38f

        /** The floating download button while the page offers nothing. */
        const val DISABLED_ALPHA = 0.4f
        const val PULSE_SCALE = 1.25f
        const val PULSE_MS = 150L
        const val LABEL_NAME_LENGTH = 60
    }
}
