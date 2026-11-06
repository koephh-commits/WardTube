// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mwmods.wardtube.download.Downloader
import com.mwmods.wardtube.util.Permissions
import com.mwmods.wardtube.util.ShareLinks

/**
 * Receives a link shared from another app (YouTube, TikTok, Facebook, a browser, a chat...) and offers to download it.
 *
 * The screen itself is transparent: only the usual "Download as" dialog shows, over the app the link came from, and
 * when it is done (or cancelled) this activity closes and the person is back where they were. The browser screen
 * ([MainActivity]) is not opened for a download, so downloading by sharing needs no visit to the app. A YouTube link
 * also offers "Play in Ward Tube", the one case where the browser screen is opened (on that video).
 */
class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var link: ShareLinks.Link

    /** True while a permission request is on screen: that also stops this activity, which must not end the flow. */
    private var waitingForPermission = false

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            waitingForPermission = false
            if (granted) {
                proceed()
            } else {
                toast(R.string.storagePermissionDenied)
                finish()
            }
        }

    // Only asked once; downloads work without it, the progress notification is just hidden.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            waitingForPermission = false
            proceed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An empty, transparent root: the dialog and its messages are drawn over the app underneath.
        setContentView(FrameLayout(this))
        prefs = Prefs(this)

        when (val result = ShareLinks.find(sharedText(intent))) {
            is ShareLinks.Result.Found -> {
                link = result.link
                proceed()
            }
            ShareLinks.Result.NotVideo -> closeWith(R.string.shareNotVideo)
            ShareLinks.Result.NoLink -> closeWith(R.string.shareNoLink)
        }
    }

    /** The text of a "share as text" request; some apps put it in the clip instead of EXTRA_TEXT. */
    private fun sharedText(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { return it }
        val clip = intent.clipData ?: return null
        return if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
    }

    /** Permissions first (Android 9 storage, Android 13 notifications), then the "Download as" dialog. */
    private fun proceed() {
        if (isFinishing || isDestroyed) return
        if (!Permissions.hasLegacyWrite(this)) {
            waitingForPermission = true
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (shouldAskForNotifications()) {
            prefs.notificationPermissionAsked = true
            waitingForPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // No title: the link carries none, and yt-dlp's own file name names the job in the Library.
        // A YouTube link also gets "Play in Ward Tube" next to Download (other sites have no player here).
        val play: (() -> Unit)? = if (link.site == ShareLinks.Site.YOUTUBE) this::openInApp else null
        Downloader.chooseAndStart(this, link.url, findViewById(android.R.id.content), null, onOpenInApp = play) { finish() }
    }

    /**
     * "Play in Ward Tube": hands the link to the browser screen, which plays a video in its player and opens a list or a
     * channel as a page. [MainActivity] is single-task: it comes to the front if the app is already running.
     */
    private fun openInApp() {
        val open = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(link.playUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(open)
    }

    /**
     * Leaving for another app (Home, Recents) drops the share instead of leaving a dialog behind that would reappear
     * over the sharing app later. A rebuild (rotation, language) is not leaving.
     */
    override fun onStop() {
        super.onStop()
        if (!waitingForPermission && !isChangingConfigurations) finish()
    }

    private fun shouldAskForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.notificationPermissionAsked) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun closeWith(message: Int) {
        toast(message)
        finish()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
