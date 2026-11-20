// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.mwmods.wardtube.R
import com.mwmods.wardtube.util.VideoLinks

/**
 * The sheet a long press on a video's picture or title opens, wherever that video is shown (Home, search, history,
 * a channel, the list under the player ...): the video itself (its picture and title, so it is plain which one the press
 * found), the big "Download video" button and, below it, the smaller actions (share, copy the link).
 *
 * It is drawn like the three-dots menu (the same sheet style, rounded card and tinted rows), and it only shows what it
 * is given: what each action does is decided by the caller ([VideoLongPress]).
 */
class VideoSheet(private val activity: AppCompatActivity, private val snackbarAnchor: View) {

    private var dialog: BottomSheetDialog? = null

    /** True while the sheet is open: a second long press does not open another one over it. */
    val isShowing: Boolean get() = dialog?.isShowing == true

    /** Opens the sheet for [video]; [title] is its name, or null when the page gave none. */
    fun show(video: VideoLinks.Video, title: String?, onDownload: () -> Unit, onShare: () -> Unit) {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return

        val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_video, null)
        val sheet = BottomSheetDialog(activity, R.style.MenuSheetDialog)
        sheet.setContentView(content)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        dialog = sheet

        content.findViewById<TextView>(R.id.videoSheetTitle).text =
            title?.trim()?.takeUnless { it.isEmpty() } ?: activity.getString(R.string.videoSheetUntitled)
        val site = activity.getString(R.string.quickYoutube)
        content.findViewById<TextView>(R.id.videoSheetMeta).text =
            if (video.short) site + META_SEPARATOR + activity.getString(R.string.subsChipShorts) else site
        content.findViewById<View>(R.id.videoSheetBadge).visibility = if (video.short) View.VISIBLE else View.GONE

        // The picture comes from the internet a moment later; until it does (or when it never does) the play mark shows.
        val thumb = content.findViewById<ImageView>(R.id.videoSheetThumb)
        RemoteThumbnails.load(video.thumbnailUrl, thumb) { shown ->
            thumb.visibility = if (shown) View.VISIBLE else View.GONE
        }

        content.findViewById<View>(R.id.videoSheetDownload).setOnClickListener {
            sheet.dismiss()
            onDownload()
        }

        val rows = content.findViewById<LinearLayout>(R.id.videoSheetRows)
        addRow(sheet, rows, R.string.share, R.drawable.ic_share_24dp, R.color.menuAccentBlue) { onShare() }
        addRow(sheet, rows, R.string.omniboxCopy, R.drawable.ic_copy_24dp, R.color.menuAccentTeal) { copyLink(video) }

        sheet.show()
    }

    /** A row of the card: a thin line above it when it is not the first, its tinted icon and its title. A tap closes the sheet, then acts. */
    private fun addRow(
        sheet: BottomSheetDialog,
        card: LinearLayout,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        @ColorRes accent: Int,
        onClick: () -> Unit,
    ) {
        if (card.childCount > 0) card.addView(activity.layoutInflater.inflate(R.layout.item_menu_divider, card, false))
        val row = activity.layoutInflater.inflate(R.layout.item_menu_row, card, false)
        row.findViewById<TextView>(R.id.menuRowTitle).setText(title)
        val tile = row.findViewById<ImageView>(R.id.menuRowIcon)
        tile.setImageResource(icon)
        val color = ContextCompat.getColor(activity, accent)
        tile.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, TILE_ALPHA))
        tile.imageTintList = ColorStateList.valueOf(color)
        row.setOnClickListener {
            sheet.dismiss()
            onClick()
        }
        card.addView(row)
    }

    private fun copyLink(video: VideoLinks.Video) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("link", video.shareUrl)) }
        Snackbar.make(snackbarAnchor, R.string.omniboxCopied, Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        /** The same soft tint behind a row's icon as in the three-dots menu. */
        const val TILE_ALPHA = 0x2E
        const val META_SEPARATOR = " · "
    }
}
