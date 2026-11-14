// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mwmods.wardtube.R

/**
 * The "Save to…" sheet of the three-dots menu, drawn like YouTube's own: a dark card rising from the bottom with the
 * account's playlists, each with its picture, its name, who can see it and a bookmark that is filled when the video is
 * already in it (a tap puts the video in, or takes it out, at once), and a "New playlist" button under them that asks
 * for a title and who can see the list, then makes the playlist with the video in it.
 *
 * It only draws and asks: what each tap does on YouTube is decided by [SubsController] through the [Host].
 */
class SubsPlaylistSheet(
    private val activity: Activity,
    private val palette: SubsPalette,
    private val host: Host,
) {

    interface Host {
        /** A row was tapped: the video goes into the playlist ([add]) or out of it; [onFailed] puts the tick back. */
        fun onToggle(option: SubsRepository.PlaylistOption, add: Boolean, onFailed: () -> Unit)

        /** "Create" was tapped: makes a playlist of [title] (and [privacy]) with the video in it; [done] gets whether it worked. */
        fun onCreate(title: String, privacy: String, done: (Boolean) -> Unit)

        /** A playlist came without a picture: [show] is called with its address once it is known (never when it has none). */
        fun onThumb(option: SubsRepository.PlaylistOption, show: (String) -> Unit)
    }

    private val density = activity.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = radius.toFloat()
        return shape
    }

    private fun outlined(radius: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(Color.TRANSPARENT)
        shape.cornerRadius = radius.toFloat()
        shape.setStroke(dp(1), palette.outline)
        return shape
    }

    private val pictureShape = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
        }
    }

    private fun privacyText(privacy: String): String =
        when (privacy) {
            "PRIVATE" -> activity.getString(R.string.subsPrivacyPrivate)
            "PUBLIC" -> activity.getString(R.string.subsPrivacyPublic)
            "UNLISTED" -> activity.getString(R.string.subsPrivacyUnlisted)
            else -> ""
        }

    fun show(options: List<SubsRepository.PlaylistOption>) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = BottomSheetDialog(activity)
        val screenHeight = activity.resources.displayMetrics.heightPixels

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        val top = dp(24).toFloat()
        val card = GradientDrawable()
        card.setColor(palette.menu)
        card.cornerRadii = floatArrayOf(top, top, top, top, 0f, 0f, 0f, 0f)
        root.background = card

        val handle = View(activity)
        handle.background = rounded(palette.outline, dp(2))
        val handleParams = LinearLayout.LayoutParams(dp(36), dp(4))
        handleParams.gravity = Gravity.CENTER_HORIZONTAL
        handleParams.topMargin = dp(10)
        handleParams.bottomMargin = dp(8)
        root.addView(handle, handleParams)

        val title = TextView(activity)
        title.text = activity.getString(R.string.subsPlaylistTitle)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(palette.text)
        title.setPadding(dp(24), dp(12), dp(24), dp(12))
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Which playlists hold the video now (a tap changes it at once, and a failed tap changes it back).
        val contains = HashMap<String, Boolean>()
        for (option in options) contains[option.id] = option.contains

        val list = LinearLayout(activity)
        list.orientation = LinearLayout.VERTICAL
        for (option in options) {
            list.addView(row(dialog, option, contains), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT)))
        }
        val scroll = NestedScrollView(activity)
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The list is as tall as its rows, up to half of the screen, then it scrolls.
        val listHeight = minOf(options.size * dp(ROW_HEIGHT), screenHeight / 2)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight))

        val create = LinearLayout(activity)
        create.orientation = LinearLayout.HORIZONTAL
        create.gravity = Gravity.CENTER
        create.background = rounded(palette.button, dp(26))
        create.isClickable = true
        val plus = ImageView(activity)
        plus.setImageResource(R.drawable.ic_add_24dp)
        plus.imageTintList = ColorStateList.valueOf(palette.text)
        create.addView(plus, LinearLayout.LayoutParams(dp(24), dp(24)))
        val createLabel = TextView(activity)
        createLabel.text = activity.getString(R.string.subsPlaylistNew)
        createLabel.textSize = 16f
        createLabel.setTextColor(palette.text)
        val labelParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        labelParams.marginStart = dp(12)
        create.addView(createLabel, labelParams)
        val createParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        createParams.setMargins(dp(16), dp(8), dp(16), dp(20))
        root.addView(create, createParams)
        create.setOnClickListener { askNewPlaylist(dialog) }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            // The sheet's own background is replaced by the card drawn above.
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    /** A playlist's row: its picture, its name with who can see it, and the bookmark. */
    private fun row(dialog: BottomSheetDialog, option: SubsRepository.PlaylistOption, contains: HashMap<String, Boolean>): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), 0, dp(16), 0)
        row.isClickable = true
        val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            row.background = attrs.getDrawable(0)
        } finally {
            attrs.recycle()
        }

        // The picture is the row's first child, so it stands at the start of the row: the right in Arabic, the left otherwise.
        // A tile with a list icon stands under the picture, so a playlist whose picture is not (yet) there still shows a
        // tile (the old placeholder had the colour of the card itself, so a missing picture looked like an empty hole).
        val tile = FrameLayout(activity)
        tile.setBackgroundColor(palette.button)
        tile.outlineProvider = pictureShape
        tile.clipToOutline = true
        val glyph = ImageView(activity)
        glyph.setImageResource(R.drawable.ic_list_24dp)
        glyph.imageTintList = ColorStateList.valueOf(palette.secondary)
        tile.addView(glyph, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        val picture = ImageView(activity)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        tile.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(tile, LinearLayout.LayoutParams(dp(82), dp(46)))
        if (option.thumb.isNotEmpty()) {
            SubsImages.load(picture, option.thumb, null, dp(82), urgent = true)
        } else {
            host.onThumb(option) { url ->
                // Not gated on the sheet "showing": a picture already known is handed over while the rows are still
                // being built (before the sheet is shown), and a gate there dropped it every time but the first.
                if (!activity.isFinishing && !activity.isDestroyed) SubsImages.load(picture, url, null, dp(82), urgent = true)
            }
        }

        val texts = LinearLayout(activity)
        texts.orientation = LinearLayout.VERTICAL
        val name = TextView(activity)
        name.text = option.title
        name.textSize = 16f
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.textAlignment = View.TEXT_ALIGNMENT_VIEW_START // a Latin title stands at the start too (the right in Arabic)
        name.setTextColor(palette.text)
        texts.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val privacy = privacyText(option.privacy)
        if (privacy.isNotEmpty()) {
            val who = TextView(activity)
            who.text = privacy
            who.textSize = 14f
            who.maxLines = 1
            who.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            who.setTextColor(palette.secondary)
            texts.addView(who, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val textParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        textParams.marginStart = dp(16)
        textParams.marginEnd = dp(12)
        row.addView(texts, textParams)

        val mark = ImageView(activity)
        mark.imageTintList = ColorStateList.valueOf(palette.text)
        fun draw() {
            val held = contains[option.id] == true
            mark.setImageResource(if (held) R.drawable.ic_bookmark_filled_24dp else R.drawable.ic_bookmark_24dp)
        }
        draw()
        row.addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)))

        row.setOnClickListener {
            val add = contains[option.id] != true
            contains[option.id] = add
            draw()
            host.onToggle(option, add) {
                contains[option.id] = !add
                draw()
            }
        }
        return row
    }

    /**
     * "New playlist": a card asking for the title and who can see the list (private to begin with, as on YouTube).
     * "Create" is off until a title is typed. It closes only with its two buttons, so a stray tap does not lose the title.
     * When the playlist is made, [sheet] is closed.
     */
    private fun askNewPlaylist(sheet: BottomSheetDialog) {
        var privacy = "PRIVATE"

        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(24), dp(24), dp(8))

        val heading = TextView(activity)
        heading.text = activity.getString(R.string.subsPlaylistNew)
        heading.textSize = 22f
        heading.setTextColor(palette.text)
        box.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val input = EditText(activity)
        input.hint = activity.getString(R.string.subsPlaylistNameHint)
        input.setHintTextColor(palette.secondary)
        input.setTextColor(palette.text)
        input.textSize = 16f
        input.maxLines = 1
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.filters = arrayOf(InputFilter.LengthFilter(150))
        input.background = outlined(dp(12))
        input.setPadding(dp(16), dp(16), dp(16), dp(16))
        val inputParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        inputParams.topMargin = dp(24)
        box.addView(input, inputParams)

        val who = LinearLayout(activity)
        who.orientation = LinearLayout.HORIZONTAL
        who.gravity = Gravity.CENTER_VERTICAL
        who.background = outlined(dp(12))
        who.setPadding(dp(16), dp(10), dp(16), dp(10))
        who.isClickable = true
        val whoTexts = LinearLayout(activity)
        whoTexts.orientation = LinearLayout.VERTICAL
        val whoLabel = TextView(activity)
        whoLabel.text = activity.getString(R.string.subsPlaylistVisibility)
        whoLabel.textSize = 12f
        whoLabel.setTextColor(palette.secondary)
        val whoValue = TextView(activity)
        whoValue.text = privacyText(privacy)
        whoValue.textSize = 16f
        whoValue.setTextColor(palette.text)
        whoTexts.addView(whoLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        whoTexts.addView(whoValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        who.addView(whoTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = ImageView(activity)
        chevron.setImageResource(R.drawable.ic_expand_more_white_24dp)
        chevron.imageTintList = ColorStateList.valueOf(palette.text)
        who.addView(chevron, LinearLayout.LayoutParams(dp(24), dp(24)))
        val whoParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        whoParams.topMargin = dp(16)
        box.addView(who, whoParams)
        who.setOnClickListener {
            // The keyboard is put away first, so the sheet does not rise behind it.
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            askPrivacy(privacy) { picked ->
                privacy = picked
                whoValue.text = privacyText(picked)
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(box)
            .setNegativeButton(R.string.subsPlaylistCancel, null)
            .setPositiveButton(R.string.subsPlaylistCreate, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.let {
            it.setBackgroundDrawable(InsetDrawable(rounded(palette.menu, dp(28)), dp(16)))
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()

        val buttonColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.accent, palette.secondary),
        )
        val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        create.setTextColor(buttonColors)
        cancel.setTextColor(palette.accent)
        create.isEnabled = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                create.isEnabled = !s.isNullOrBlank()
            }
        })
        create.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            host.onCreate(name, privacy) { made -> if (made) sheet.dismiss() }
        }
        input.requestFocus()
    }

    /** One choice of the visibility sheet: what it is stored as, its icon, its name and what it means. */
    private class PrivacyChoice(val value: String, val icon: Int, val label: Int, val hint: Int)

    /**
     * "Visibility": a sheet rising from the bottom like YouTube's own (a card with its handle, the title with a thin
     * line under it, then the three choices in YouTube's order, each with its icon, its name and what it means; the
     * chosen one carries a tick at the end of its row). A tap on a choice picks it and closes the sheet.
     */
    private fun askPrivacy(current: String, onPicked: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val choices = listOf(
            PrivacyChoice("PUBLIC", R.drawable.ic_globe_white_24dp, R.string.subsPrivacyPublic, R.string.subsPrivacyPublicHint),
            PrivacyChoice("UNLISTED", R.drawable.ic_link_24dp, R.string.subsPrivacyUnlisted, R.string.subsPrivacyUnlistedHint),
            PrivacyChoice("PRIVATE", R.drawable.ic_lock_24dp, R.string.subsPrivacyPrivate, R.string.subsPrivacyPrivateHint),
        )
        val dialog = BottomSheetDialog(activity)

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        val top = dp(24).toFloat()
        val card = GradientDrawable()
        card.setColor(palette.menu)
        card.cornerRadii = floatArrayOf(top, top, top, top, 0f, 0f, 0f, 0f)
        root.background = card

        val handle = View(activity)
        handle.background = rounded(palette.outline, dp(2))
        val handleParams = LinearLayout.LayoutParams(dp(36), dp(4))
        handleParams.gravity = Gravity.CENTER_HORIZONTAL
        handleParams.topMargin = dp(10)
        handleParams.bottomMargin = dp(8)
        root.addView(handle, handleParams)

        val title = TextView(activity)
        title.text = activity.getString(R.string.subsPlaylistVisibility)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(palette.text)
        title.setPadding(dp(24), dp(12), dp(24), dp(16))
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val line = View(activity)
        line.setBackgroundColor(palette.outline)
        root.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            for (choice in choices) {
                val row = LinearLayout(activity)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.minimumHeight = dp(72)
                row.setPadding(dp(24), dp(12), dp(24), dp(12))
                row.isClickable = true
                row.background = attrs.getDrawable(0)?.constantState?.newDrawable()

                // The icon is the row's first child, so it stands at the start of the row (the right in Arabic) and the
                // tick, the last one, at its end, as in YouTube's sheet.
                val icon = ImageView(activity)
                icon.setImageResource(choice.icon)
                icon.imageTintList = ColorStateList.valueOf(palette.text)
                row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))

                val texts = LinearLayout(activity)
                texts.orientation = LinearLayout.VERTICAL
                val name = TextView(activity)
                name.text = activity.getString(choice.label)
                name.textSize = 17f
                name.setTextColor(palette.text)
                val hint = TextView(activity)
                hint.text = activity.getString(choice.hint)
                hint.textSize = 14f
                hint.setTextColor(palette.secondary)
                texts.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                texts.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val textParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textParams.marginStart = dp(24)
                textParams.marginEnd = dp(16)
                row.addView(texts, textParams)

                val tick = ImageView(activity)
                tick.setImageResource(R.drawable.ic_check_24dp)
                tick.imageTintList = ColorStateList.valueOf(palette.text)
                tick.visibility = if (choice.value == current) View.VISIBLE else View.INVISIBLE
                row.addView(tick, LinearLayout.LayoutParams(dp(28), dp(28)))

                row.setOnClickListener {
                    onPicked(choice.value)
                    dialog.dismiss()
                }
                root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } finally {
            attrs.recycle()
        }
        root.addView(View(activity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))

        dialog.setContentView(root)
        dialog.setOnShowListener {
            // The sheet's own background is replaced by the card drawn above.
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private companion object {
        /** The height of a playlist's row, in dp. */
        const val ROW_HEIGHT = 64
    }
}
