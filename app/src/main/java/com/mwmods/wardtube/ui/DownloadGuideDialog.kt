package com.mwmods.wardtube.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R

/**
 * The message about the ways to download a video, with a picture for each: the Download button (beside the like buttons,
 * in the three-dots menu, in the bottom bar or the side menu) and the long press on a video. [MainActivity] shows it each
 * time the app is opened, until the person presses "Don't show again" ([Prefs.downloadGuideHidden]); "OK" (or a tap outside,
 * a swipe down, or Back) only closes it. The pictures are drawn illustrations (res/drawable-nodpi/guide_download_*.png), the
 * same in day and night mode: put a screenshot of the same name there to change one.
 *
 * It is a bottom sheet drawn like the three-dots menu and the player chooser ([PlayerChoiceSheet]): one soft card for each way,
 * with a tinted tile, its text and its pictures. The three pictures of the first way slide sideways, so the message stays short.
 */
object DownloadGuideDialog {

    /** Width of a picture in the sliding row; the next one shows in part, which says there is more. */
    private const val SLIDE_IMAGE_WIDTH_DP = 240
    private const val SLIDE_IMAGE_GAP_DP = 10

    /** How strong the tint behind a tile is (the same as the menu's tiles). */
    private const val TILE_ALPHA = 0x2E

    fun show(activity: Activity, prefs: Prefs) {
        if (activity.isFinishing || activity.isDestroyed) return

        val inflater = LayoutInflater.from(activity)
        val content = inflater.inflate(R.layout.dialog_download_guide, null)
        val density = activity.resources.displayMetrics.density

        fun tint(tile: ImageView, @ColorRes accentRes: Int) {
            val accent = ContextCompat.getColor(activity, accentRes)
            tile.backgroundTintList = ColorStateList.valueOf((accent and 0x00FFFFFF) or (TILE_ALPHA shl 24))
            tile.imageTintList = ColorStateList.valueOf(accent)
        }

        fun addImage(containerId: Int, @DrawableRes image: Int, @StringRes caption: Int, sliding: Boolean) {
            val box = content.findViewById<LinearLayout>(containerId)
            val item = inflater.inflate(R.layout.item_guide_image, box, false)
            item.findViewById<ImageView>(R.id.guideImage).setImageResource(image)
            item.findViewById<TextView>(R.id.guideCaption).setText(caption)
            if (sliding) {
                item.layoutParams = LinearLayout.LayoutParams(
                    (SLIDE_IMAGE_WIDTH_DP * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (SLIDE_IMAGE_GAP_DP * density).toInt() }
            }
            box.addView(item)
        }

        tint(content.findViewById(R.id.guideTile1), R.color.menuAccentRed)
        tint(content.findViewById(R.id.guideTile2), R.color.menuAccentBlue)

        addImage(R.id.guideImages1, R.drawable.guide_download_buttons, R.string.guideCaptionButtons, sliding = true)
        addImage(R.id.guideImages1, R.drawable.guide_download_menu, R.string.guideCaptionMenu, sliding = true)
        addImage(R.id.guideImages1, R.drawable.guide_download_bar, R.string.guideCaptionBar, sliding = true)
        addImage(R.id.guideImages2, R.drawable.guide_download_longpress, R.string.guideCaptionLongPress, sliding = false)

        val sheet = BottomSheetDialog(activity, R.style.MenuSheetDialog)
        sheet.setContentView(content)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        content.findViewById<View>(R.id.guideOk).setOnClickListener { sheet.dismiss() }
        content.findViewById<View>(R.id.guideDontShow).setOnClickListener {
            prefs.downloadGuideHidden = true
            sheet.dismiss()
        }
        sheet.show()
    }
}
