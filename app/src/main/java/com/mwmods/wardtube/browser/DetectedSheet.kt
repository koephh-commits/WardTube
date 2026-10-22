// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import com.mwmods.wardtube.R
import com.mwmods.wardtube.ui.MaxHeightScrollView
import java.text.NumberFormat
import java.util.Locale

/**
 * The list of what the open page offers (videos, audio, files), as a bottom sheet:
 *  - the header names the page and counts what was found;
 *  - the items come in three sections (videos, audio, files), each with its own count;
 *  - a row has a colored icon by kind, the name (the page's title for its own video), a small badge (the format:
 *    MP4, M3U8, PDF...) and the kind with the site it comes from, and a download button at its end.
 * A tap on a row closes the sheet and hands the item to [show]'s callback.
 *
 * Only what the sniffer already knows is shown: nothing is requested from a server to fill the list in (a signed
 * address may answer only once), so there is no file size.
 */
object DetectedSheet {

    /** The sections, in the order shown; an empty one is left out. */
    private enum class Section(val titleRes: Int, val kinds: Set<Detected.Kind>) {
        VIDEOS(
            R.string.browserSectionVideos,
            setOf(Detected.Kind.PAGE, Detected.Kind.FRAME, Detected.Kind.VIDEO, Detected.Kind.STREAM),
        ),
        AUDIO(R.string.browserSectionAudio, setOf(Detected.Kind.AUDIO)),
        FILES(R.string.browserSectionFiles, setOf(Detected.Kind.FILE)),
    }

    /** How one kind of item looks. */
    private class Look(val icon: Int, val color: Int, val kindLabel: Int)

    private fun lookOf(activity: AppCompatActivity, kind: Detected.Kind): Look {
        fun color(res: Int) = ContextCompat.getColor(activity, res)
        return when (kind) {
            Detected.Kind.PAGE -> Look(R.drawable.ic_videocam_24dp, color(R.color.detectVideo), R.string.browserKindPage)
            Detected.Kind.FRAME -> Look(R.drawable.ic_play_white_24dp, color(R.color.detectPlayer), R.string.browserKindFrame)
            Detected.Kind.VIDEO -> Look(R.drawable.ic_videocam_24dp, color(R.color.detectVideo), R.string.browserKindVideo)
            Detected.Kind.STREAM -> Look(R.drawable.ic_videocam_24dp, color(R.color.detectStream), R.string.browserKindStream)
            Detected.Kind.AUDIO -> Look(R.drawable.ic_music_note_24dp, color(R.color.detectAudio), R.string.browserKindAudio)
            Detected.Kind.FILE -> Look(R.drawable.ic_file_24dp, color(R.color.detectFile), R.string.browserKindFile)
        }
    }

    fun show(
        activity: AppCompatActivity,
        pageTitle: String?,
        pageUrl: String?,
        items: List<Detected>,
        onChosen: (Detected) -> Unit,
    ) {
        val inflater = activity.layoutInflater
        val content = inflater.inflate(R.layout.bottom_sheet_browser_list, null)
        content.findViewById<TextView>(R.id.sheetTitle).setText(R.string.browserDetectTitle)
        val subtitle = pageTitle?.trim().orEmpty().ifEmpty { hostOf(pageUrl) }
        content.findViewById<TextView>(R.id.sheetSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        content.findViewById<TextView>(R.id.sheetCount).text = NumberFormat.getInstance().format(items.size)
        content.findViewById<MaxHeightScrollView>(R.id.sheetScroll).maxHeightPx = BrowserSheet.listMaxHeight(activity)
        val list = content.findViewById<LinearLayout>(R.id.sheetList)
        val dialog = BrowserSheet.create(activity, content)

        for (section in Section.values()) {
            val inSection = items.filter { it.kind in section.kinds }
            if (inSection.isEmpty()) continue
            val header = inflater.inflate(R.layout.item_sheet_section, list, false)
            header.findViewById<TextView>(R.id.sectionTitle).setText(section.titleRes)
            header.findViewById<TextView>(R.id.sectionCount).text = NumberFormat.getInstance().format(inSection.size)
            list.addView(header)

            for (item in inSection) {
                val row = inflater.inflate(R.layout.item_detected, list, false)
                bind(activity, row, item, pageTitle)
                row.setOnClickListener {
                    dialog.dismiss()
                    onChosen(item)
                }
                list.addView(row)
            }
        }
        dialog.show()
    }

    private fun bind(activity: AppCompatActivity, row: View, item: Detected, pageTitle: String?) {
        val look = lookOf(activity, item.kind)
        val host = hostOf(item.url)

        // The icon: the kind's color on a soft tile of the same color.
        row.findViewById<View>(R.id.detectedTile).background =
            BrowserSheet.rounded(activity, ColorUtils.setAlphaComponent(look.color, TILE_ALPHA), TILE_RADIUS_DP)
        row.findViewById<ImageView>(R.id.detectedIcon).apply {
            setImageResource(look.icon)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(look.color))
        }

        // The name: the page's title for the page's own video; otherwise what the sniffer named the item.
        row.findViewById<TextView>(R.id.detectedName).text = when (item.kind) {
            Detected.Kind.PAGE -> pageTitle?.takeIf { it.isNotBlank() } ?: host.ifEmpty { item.name }
            else -> item.name
        }

        // The badge: the format when there is one, otherwise what the item is.
        row.findViewById<TextView>(R.id.detectedBadge).apply {
            text = badgeOf(activity, item)
            setTextColor(look.color)
            background = BrowserSheet.rounded(activity, ColorUtils.setAlphaComponent(look.color, BADGE_ALPHA), BADGE_RADIUS_DP)
        }

        // Under the name: the kind, then the site the item comes from.
        val kind = activity.getString(look.kindLabel)
        row.findViewById<TextView>(R.id.detectedMeta).text = if (host.isEmpty()) kind else "$kind · $host"
    }

    private fun badgeOf(activity: AppCompatActivity, item: Detected): String = when (item.kind) {
        Detected.Kind.PAGE -> activity.getString(R.string.browserBadgePage)
        Detected.Kind.FRAME -> activity.getString(R.string.browserBadgePlayer)
        else -> extensionOf(item).ifEmpty {
            activity.getString(
                when (item.kind) {
                    Detected.Kind.VIDEO -> R.string.browserKindVideo
                    Detected.Kind.STREAM -> R.string.browserKindStream
                    Detected.Kind.AUDIO -> R.string.browserKindAudio
                    else -> R.string.browserKindFile
                }
            )
        }
    }

    /** The format in capitals (MP4, M3U8, PDF): from the item's name, else from its address; empty when unknown. */
    private fun extensionOf(item: Detected): String {
        fun valid(ext: String) = ext.length in 1..5 && ext.all { it.isLetterOrDigit() }
        val fromName = item.name.substringAfterLast('.', "")
        if (valid(fromName)) return fromName.uppercase(Locale.ROOT)
        val fromAddress = Uri.parse(item.url).lastPathSegment.orEmpty().substringAfterLast('.', "")
        return if (valid(fromAddress)) fromAddress.uppercase(Locale.ROOT) else ""
    }

    /** The site of an address without a leading "www."; empty when it has none. */
    private fun hostOf(url: String?): String =
        url?.let { Uri.parse(it).host }.orEmpty().removePrefix("www.")

    private const val TILE_ALPHA = 0x2E
    private const val TILE_RADIUS_DP = 12f
    private const val BADGE_ALPHA = 0x33
    private const val BADGE_RADIUS_DP = 6f
}
