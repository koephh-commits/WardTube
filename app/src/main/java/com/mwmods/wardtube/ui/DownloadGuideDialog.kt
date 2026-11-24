package com.mwmods.wardtube.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.mwmods.wardtube.Prefs
import com.mwmods.wardtube.R

/**
 * The message about the ways to download a video, with a picture for each: the Download button (beside the like buttons,
 * in the three-dots menu, in the bottom bar or the side menu) and the long press on a video. [MainActivity] shows it each
 * time the app is opened, until the person presses "Don't show again" ([Prefs.downloadGuideHidden]); "OK" (or a tap outside,
 * or Back) only closes it. The pictures are drawn illustrations (res/drawable-nodpi/guide_download_*.png), the same in day
 * and night mode: put a screenshot of the same name there to change one.
 */
object DownloadGuideDialog {

    fun show(activity: Activity, prefs: Prefs) {
        val inflater = LayoutInflater.from(activity)
        val content = inflater.inflate(R.layout.dialog_download_guide, null)

        fun addImage(containerId: Int, @DrawableRes image: Int, @StringRes caption: Int) {
            val box = content.findViewById<LinearLayout>(containerId)
            val item = inflater.inflate(R.layout.item_guide_image, box, false)
            item.findViewById<ImageView>(R.id.guideImage).setImageResource(image)
            item.findViewById<TextView>(R.id.guideCaption).setText(caption)
            box.addView(item)
        }

        addImage(R.id.guideImages1, R.drawable.guide_download_buttons, R.string.guideCaptionButtons)
        addImage(R.id.guideImages1, R.drawable.guide_download_menu, R.string.guideCaptionMenu)
        addImage(R.id.guideImages1, R.drawable.guide_download_bar, R.string.guideCaptionBar)
        addImage(R.id.guideImages2, R.drawable.guide_download_longpress, R.string.guideCaptionLongPress)

        AlertDialog.Builder(activity)
            .setTitle(R.string.guideTitle)
            .setView(content)
            .setPositiveButton(R.string.guideClose, null)
            .setNeutralButton(R.string.guideDontShow) { _, _ -> prefs.downloadGuideHidden = true }
            .show()
    }
}
