// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mwmods.wardtube.util.CrashLog
import java.io.File
import kotlin.system.exitProcess

/** Shown right after a crash. Runs in its own process (":crash") so it works even though the app died. */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(CrashLog.EXTRA_REPORT)
            ?: runCatching { File(filesDir, "last_crash.txt").readText() }.getOrNull()
            ?: "No details available."
        runCatching { File(filesDir, "last_crash.txt").delete() }

        val dp = resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        val title = TextView(this).apply {
            text = "WardTube توقف بسبب خطأ\nWardTube stopped because of an error"
            textSize = 18f
            setPadding(pad(16), pad(16), pad(16), pad(8))
        }
        val body = TextView(this).apply {
            text = report
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad(16), pad(8), pad(16), pad(8))
        }
        val scroll = ScrollView(this).apply {
            addView(body)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(pad(8), pad(4), pad(8), pad(8))
            addView(button("Copy / نسخ") {
                CrashLog.copy(this@CrashActivity, report)
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            })
            addView(button("Share / مشاركة") { CrashLog.share(this@CrashActivity, report) })
            addView(button("Restart / إعادة") {
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
                finishAndRemoveTask()
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(title)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Own process: end it so the next report starts clean.
        if (isFinishing) exitProcess(0)
    }
}
