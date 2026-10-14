// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mwmods.wardtube.R
import java.util.Calendar

/** "About": developer info and a WhatsApp link. */
object AboutSheet {

    @Suppress("DEPRECATION")
    fun show(activity: AppCompatActivity) {
        val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_about, null)

        val versionName = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
        content.findViewById<TextView>(R.id.tvAppVersion).text = activity.getString(R.string.version, versionName)

        val year = Calendar.getInstance().get(Calendar.YEAR)
        content.findViewById<TextView>(R.id.tvAboutCopyright).text = activity.getString(R.string.aboutCopyright, year)

        content.findViewById<android.view.View>(R.id.rowWhatsapp).setOnClickListener {
            openWhatsapp(activity, activity.getString(R.string.aboutWhatsappNumber))
        }

        BottomSheetDialog(activity).apply { setContentView(content) }.show()
    }

    /** Opens a WhatsApp chat with the number; non-digit characters are stripped first. */
    private fun openWhatsapp(activity: AppCompatActivity, rawNumber: String) {
        val digits = rawNumber.filter { it.isDigit() }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.errorUnknown, Toast.LENGTH_SHORT).show()
        }
    }
}
