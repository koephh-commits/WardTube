// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.mwmods.wardtube.ui.VideoSheet
import com.mwmods.wardtube.util.VideoLinks
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Download from anywhere a video is listed: a long press on a video's picture or title opens a sheet with the video and
 * its "Download video" button ([VideoSheet]). That is the way into a download from a card, in the browsing page and in
 * the player's page (the list under the video) alike. (The other way, the "Download video" row in the menu of a card's
 * three dots, is put there by the page script menu-download.js and does not come through here.)
 *
 * It is NouTube's way (`NouTubeView.onCreateContextMenu`, `content/link-title.ts`): Android's own hit test of the WebView
 * says which link is under the finger, whatever the page is made of, and the video is that link's. So nothing here looks
 * at how YouTube builds a card, its three dots or its menu, and nothing needs the page script. The only thing read from
 * the page is the card's title (see [TITLE_JS]), and a page that does not answer only costs the sheet its title.
 *
 * A WebView is given to [attach]; the activity forwards its `onCreateContextMenu` to [onContextMenu]. The system's own
 * context menu is left empty on purpose (an empty one is not shown): the sheet takes its place.
 */
class VideoLongPress(
    private val activity: AppCompatActivity,
    snackbarAnchor: View,
    /** False when the sheet must not open for [WebView] now (it is out of sight, full screen, in the small window ...). */
    private val canOpen: (WebView) -> Boolean,
    /** [url] is the address the download flow starts from; [title] is the card's, or null. */
    private val onDownload: (url: String, title: String?) -> Unit,
    /** [link] is the address to share. */
    private val onShare: (link: String, title: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sheet = VideoSheet(activity, snackbarAnchor)

    /** Counts the long presses: the answer of an older one (the link of a picture, the card's title) is dropped. */
    private var press = 0

    /** Long presses on [view] are reported to the activity's `onCreateContextMenu`. */
    fun attach(view: WebView) {
        activity.registerForContextMenu(view)
    }

    /** A long press on [view]: when the link under the finger is a video's, its sheet opens. */
    fun onContextMenu(view: WebView) {
        if (activity.isDestroyed || sheet.isShowing || !canOpen(view)) return
        val current = ++press
        val result = view.hitTestResult
        when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> result.extra?.let { open(view, it, null, current) }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // The address of the link around a picture comes a moment later (the extra of this type is the picture's).
                val answer = Message.obtain(Handler(Looper.getMainLooper()) { message ->
                    val link = message.data?.getString("url")
                    if (link != null) open(view, link, message.data?.getString("title"), current)
                    true
                })
                view.requestFocusNodeHref(answer)
            }
        }
    }

    private fun showSheet(view: WebView, video: VideoLinks.Video, title: String?) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        sheet.show(
            video,
            title,
            onDownload = { onDownload(video.downloadUrl, title) },
            onShare = { onShare(video.shareUrl, title.orEmpty()) },
        )
    }

    private fun open(view: WebView, link: String, focusedTitle: String?, current: Int) {
        val video = VideoLinks.parse(link) ?: return
        var opened = false
        fun show(title: String?) {
            if (opened || current != press || activity.isDestroyed || sheet.isShowing || !canOpen(view)) return
            opened = true
            showSheet(view, video, title)
        }
        // The card's heading names the video best. The title the hit test gives is often only the picture's badge
        // (its length, or LIVE), so it is the second choice. A page that is slow to answer does not hold the sheet back.
        view.evaluateJavascript("($TITLE_JS)(${JSONObject.quote(link)})") { raw ->
            show(usable(stringOf(raw)) ?: usable(focusedTitle))
        }
        main.postDelayed({ show(usable(focusedTitle)) }, TITLE_WAIT_MS)
    }

    /** The string that [WebView.evaluateJavascript] answered (JSON text: a quoted string, or `null`). */
    private fun stringOf(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    /** A title worth showing: not empty, and not just the length or LIVE badge of a picture. */
    private fun usable(text: String?): String? {
        val title = text?.trim()?.take(MAX_TITLE) ?: return null
        return title.takeUnless { it.isEmpty() || BADGE.matches(it) }
    }

    private companion object {
        const val TITLE_WAIT_MS = 350L
        const val MAX_TITLE = 200
        val BADGE = Regex("^(?:\\d+:)*\\d+\$|^live\$", RegexOption.IGNORE_CASE)

        /**
         * A function (self-contained: it needs nothing of the page script) that is given a video link and answers the
         * title of that video's card on the page, or an empty string. It is NouTube's `getVideoLinkTitle`: the video is
         * found by its id among the page's links (so a relative link, a playlist or tracking parameters do not matter),
         * and the heading of the card that holds one of those links is the title.
         */
        const val TITLE_JS = "function(href){try{" +
            "var CARD='ytm-media-item,ytm-compact-video-renderer,ytm-video-card-renderer,ytm-playlist-video-renderer," +
            "ytm-rich-item-renderer,ytm-video-with-context-renderer,ytm-grid-video-renderer,ytm-reel-item-renderer," +
            "ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,yt-lockup-view-model," +
            "ytd-video-renderer,ytd-grid-video-renderer,ytd-compact-video-renderer,ytd-playlist-video-renderer';" +
            "var HEAD='h3,h4,#video-title,.media-item-headline,.yt-lockup-metadata-view-model__title,.title';" +
            "function idOf(h){try{var u=new URL(h,location.href),host=u.hostname;" +
            "if(host==='youtu.be')return u.pathname.slice(1);" +
            "if(host!=='youtube.com'&&host.slice(-12)!=='.youtube.com')return '';" +
            "if(u.pathname==='/watch')return u.searchParams.get('v')||'';" +
            "var m=/^\\/(?:shorts|embed|live|v)\\/([^\\/]+)/.exec(u.pathname);return m?m[1]:'';}catch(e){return '';}}" +
            "var want=idOf(href);if(!want)return '';" +
            "var all=document.querySelectorAll('a[href]'),mine=[],i;" +
            "for(i=0;i<all.length;i++){if(idOf(all[i].getAttribute('href')||'')===want)mine.push(all[i]);}" +
            "for(i=0;i<mine.length;i++){var card=mine[i].closest(CARD);" +
            "var head=card&&card.querySelector(HEAD);var t=head&&head.textContent&&head.textContent.trim();if(t)return t;}" +
            "for(i=0;i<mine.length;i++){var t2=(mine[i].getAttribute('title')||mine[i].getAttribute('aria-label')||" +
            "mine[i].textContent||'').trim();if(t2&&!/^(?:\\d+:)*\\d+\$|^live\$/i.test(t2))return t2;}" +
            "}catch(e){}return '';}"
    }
}
