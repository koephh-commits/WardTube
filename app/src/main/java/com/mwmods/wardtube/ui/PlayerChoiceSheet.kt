// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mwmods.wardtube.R
import com.mwmods.wardtube.media.ExternalPlayers
import com.mwmods.wardtube.util.DownloadItem

/**
 * The player chooser: the sheet a tap on a video or a song of the Library opens (and the file menu's "Play with").
 *
 * It is drawn like the three-dots menu and the video sheet: the file on top (its picture, name and kind), then **the
 * built-in player in its own outlined card marked "Recommended"** (it is the one that plays the rest of the list as a
 * queue, shows the lyrics, keeps going with the screen off and has the picture-in-picture window), then the other player
 * apps of the phone with their own icons, and last a switch, "Always use my choice", that keeps the answer so the next
 * tap goes straight to it.
 *
 * It only offers and reports: what a choice does, and what is kept, is decided by the caller ([LibraryActivity]).
 */
class PlayerChoiceSheet(private val activity: AppCompatActivity) {

    private var dialog: BottomSheetDialog? = null

    /** True while the sheet is open: a second tap does not open another one over it. */
    val isShowing: Boolean get() = dialog?.isShowing == true

    /**
     * Opens the sheet for [item]. [apps] are the other players that can open it (may be empty); [remembered] is whether a
     * choice is kept now, so the switch starts on. Each callback also gets the switch's position at that moment.
     */
    fun show(
        item: DownloadItem,
        apps: List<ExternalPlayers.App>,
        remembered: Boolean,
        onBuiltIn: (remember: Boolean) -> Unit,
        onApp: (app: ExternalPlayers.App, remember: Boolean) -> Unit,
    ) {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return

        val inflater = activity.layoutInflater
        val content = inflater.inflate(R.layout.bottom_sheet_player_choice, null)
        val sheet = BottomSheetDialog(activity, R.style.MenuSheetDialog)
        sheet.setContentView(content)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        dialog = sheet

        bindHeader(content, item)

        // The switch is made first: the rows read it when they are tapped.
        val rememberCard = content.findViewById<MaterialCardView>(R.id.playerSheetRememberCard)
        val rememberRow = inflater.inflate(R.layout.item_menu_row, rememberCard, false)
        val remember = rememberRow.findViewById<SwitchMaterial>(R.id.menuRowSwitch)
        rememberRow.findViewById<TextView>(R.id.menuRowTitle).setText(R.string.playerChoiceRemember)
        rememberRow.findViewById<TextView>(R.id.menuRowSubtitle).apply {
            setText(R.string.playerChoiceRememberHint)
            visibility = View.VISIBLE
        }
        val tile = rememberRow.findViewById<ImageView>(R.id.menuRowIcon)
        tile.setImageResource(R.drawable.ic_check_24dp)
        val accent = ContextCompat.getColor(activity, R.color.menuAccentTeal)
        tile.backgroundTintList = ColorStateList.valueOf((accent and 0x00FFFFFF) or (TILE_ALPHA shl 24))
        tile.imageTintList = ColorStateList.valueOf(accent)
        remember.visibility = View.VISIBLE
        remember.isChecked = remembered
        rememberRow.setOnClickListener { remember.toggle() }
        rememberCard.addView(rememberRow)

        // The recommended one: the built-in player.
        val recommendedCard = content.findViewById<MaterialCardView>(R.id.playerSheetRecommended)
        val builtIn = inflater.inflate(R.layout.item_player_choice, recommendedCard, false)
        bindBuiltIn(builtIn)
        builtIn.setOnClickListener {
            sheet.dismiss()
            onBuiltIn(remember.isChecked)
        }
        recommendedCard.addView(builtIn)

        // The others, with their own icons.
        val appsBox = content.findViewById<LinearLayout>(R.id.playerSheetApps)
        if (apps.isEmpty()) {
            content.findViewById<View>(R.id.playerSheetOthersCard).visibility = View.GONE
            content.findViewById<View>(R.id.playerSheetNone).visibility = View.VISIBLE
        }
        for (app in apps) {
            if (appsBox.childCount > 0) appsBox.addView(inflater.inflate(R.layout.item_menu_divider, appsBox, false))
            val row = inflater.inflate(R.layout.item_player_choice, appsBox, false)
            row.findViewById<TextView>(R.id.playerName).text = app.label
            val icon = row.findViewById<ImageView>(R.id.playerIcon)
            icon.setImageDrawable(app.icon)
            row.setOnClickListener {
                sheet.dismiss()
                onApp(app, remember.isChecked)
            }
            appsBox.addView(row)
        }

        sheet.show()
    }

    /** The file: its picture (until it comes, or when it has none, the play mark shows), its name and "FORMAT • size". */
    private fun bindHeader(content: View, item: DownloadItem) {
        content.findViewById<TextView>(R.id.playerSheetName).text = item.name.substringBeforeLast('.').ifBlank { item.name }
        val parts = ArrayList<String>(2)
        if (item.extensionUpper.isNotEmpty()) parts.add(item.extensionUpper)
        if (item.sizeBytes > 0) parts.add(Formatter.formatShortFileSize(activity, item.sizeBytes))
        content.findViewById<TextView>(R.id.playerSheetMeta).text = parts.joinToString(META_SEPARATOR)

        val thumb = content.findViewById<ShapeableImageView>(R.id.playerSheetThumb)
        val glyph = content.findViewById<View>(R.id.playerSheetGlyph)
        ThumbnailLoader.load(activity, item, thumb) { hasPicture ->
            thumb.visibility = if (hasPicture) View.VISIBLE else View.GONE
            glyph.visibility = if (hasPicture) View.GONE else View.VISIBLE
        }
    }

    /** The built-in player's row: a red tile with a play mark, the "Recommended" badge and a line on what it adds. */
    private fun bindBuiltIn(row: View) {
        row.findViewById<TextView>(R.id.playerName).setText(R.string.playerChoiceBuiltIn)
        row.findViewById<View>(R.id.playerSubRow).visibility = View.VISIBLE
        row.findViewById<View>(R.id.playerBadge).visibility = View.VISIBLE
        row.findViewById<TextView>(R.id.playerHint).setText(R.string.playerChoiceBuiltInHint)

        val icon = row.findViewById<ImageView>(R.id.playerIcon)
        val pad = (ICON_PADDING_DP * activity.resources.displayMetrics.density).toInt()
        icon.setPadding(pad, pad, pad, pad)
        icon.setBackgroundResource(R.drawable.bg_menu_tile)
        icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.colorPrimary))
        icon.setImageResource(R.drawable.ic_play_white_24dp)
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.white))
    }

    private companion object {
        /** The same soft tint behind a row's icon as in the three-dots menu. */
        const val TILE_ALPHA = 0x2E
        const val ICON_PADDING_DP = 10
        const val META_SEPARATOR = " • "
    }
}
