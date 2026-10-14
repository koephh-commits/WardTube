// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.graphics.Bitmap
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import java.util.Locale

class WardTubeChromeClient(
    private val progressBar: ProgressBar,
    private val fullscreenContainer: FrameLayout,
    private val listener: FullscreenListener,
    private val microphone: MicrophoneListener,
) : WebChromeClient() {

    interface FullscreenListener {
        fun onFullscreenChanged(fullscreen: Boolean)
    }

    /** Asked when a YouTube page wants the microphone (voice search); the activity owns the runtime permission. */
    interface MicrophoneListener {
        fun onMicrophoneRequested(request: PermissionRequest)
        fun onMicrophoneRequestCanceled(request: PermissionRequest)
    }

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    val isFullscreen: Boolean get() = customView != null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view)
        fullscreenContainer.visibility = View.VISIBLE
        listener.onFullscreenChanged(true)
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        listener.onFullscreenChanged(false)
    }

    fun exitFullscreen() = onHideCustomView()

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        progressBar.progress = newProgress
        progressBar.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
    }

    // ---- Microphone (voice search) ----

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val host = request.origin?.host?.lowercase(Locale.ROOT)
        val fromYouTube = host != null && (host == "youtube.com" || host.endsWith(".youtube.com"))
        val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        // Only YouTube may use the microphone. Camera, DRM and every other site stay denied.
        if (fromYouTube && wantsMic) microphone.onMicrophoneRequested(request) else request.deny()
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        if (request != null) microphone.onMicrophoneRequestCanceled(request)
    }

    /** Avoids a crash in some WebView versions that try to load the default grey poster. */
    override fun getDefaultVideoPoster(): Bitmap? =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
