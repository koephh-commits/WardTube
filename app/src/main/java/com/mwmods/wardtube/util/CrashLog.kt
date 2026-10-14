// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.util

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mwmods.wardtube.CrashActivity
import java.io.File
import kotlin.system.exitProcess

/**
 * Crash reporting that works even when the app dies:
 * - Java/Kotlin crashes (any process): a report screen opens immediately ([CrashActivity]) + a notification.
 * - Native crashes of the download engine (Android 11+): detected on the next resume via ApplicationExitInfo.
 */
object CrashLog {

    private const val CHANNEL = "crash"
    private const val NOTIF_ID = 3001
    const val EXTRA_REPORT = "report"

    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    private fun processName(): String =
        runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")

    fun install(context: Context) {
        // The report screen lives in its own process; it must never install the handler (no crash loops).
        if (processName().endsWith(":crash")) return
        val app = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = record(app, thread.name, error)
                showReport(app, text)
            }
            runCatching { Thread.sleep(400) }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    fun record(context: Context, where: String, error: Throwable): String {
        val text = buildString {
            append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  ABI=").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
            append("Process: ").append(processName()).append("  Thread: ").append(where).append("\n\n")
            append(error.stackTraceToString())
        }.take(12000)
        runCatching { file(context).writeText(text) }
        return text
    }

    /** Opens the report screen and also posts a notification (activity launches from the background can be blocked). */
    private fun showReport(app: Context, text: String) {
        val intent = Intent(app, CrashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_REPORT, text)
        runCatching { app.startActivity(intent) }
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Crash reports", NotificationManager.IMPORTANCE_HIGH))
            }
            val pi = PendingIntent.getActivity(app, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("WardTube error")
                    .setContentText(text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") } ?: "Tap to see details")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** Call from onResume: shows a saved Java crash, or a native crash / ANR / kill reported by the system. */
    fun showIfAny(activity: Activity) {
        val f = file(activity)
        var text: String? = null
        if (f.exists()) {
            text = runCatching { f.readText() }.getOrNull()
            f.delete()
        }
        if (text.isNullOrBlank()) text = nativeExitReport(activity)
        if (text.isNullOrBlank()) return
        showDialog(activity, text)
    }

    private fun nativeExitReport(activity: Activity): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val prefs = activity.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val am = activity.getSystemService(ActivityManager::class.java)
            val infos = am.getHistoricalProcessExitReasons(null, 0, 8)
            val seen = prefs.getLong("exit_seen", -1L)
            prefs.edit().putLong("exit_seen", System.currentTimeMillis()).apply()
            if (seen < 0) return null // first run: nothing to report
            val bad = infos.firstOrNull {
                it.timestamp > seen && (
                    it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_CRASH ||
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                    (it.processName.endsWith(":download") && (
                        it.reason == ApplicationExitInfo.REASON_SIGNALED ||
                        it.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                        it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
                    )
            } ?: return null
            val reason = when (bad.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH (download engine)"
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
                ApplicationExitInfo.REASON_SIGNALED -> "KILLED BY SIGNAL"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED: LOW MEMORY"
                else -> "REASON ${bad.reason}"
            }
            buildString {
                append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("  ABI=").append(Build.SUPPORTED_ABIS.joinToString()).append("\n\n")
                append(reason).append("\nProcess: ").append(bad.processName)
                append("\nStatus/signal: ").append(bad.status)
                append("\nDescription: ").append(bad.description ?: "-")
            }
        }.getOrNull()
    }

    private fun showDialog(activity: Activity, text: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Crash report / تقرير الخطأ")
            .setMessage(text.take(1500))
            .setPositiveButton("Share") { _, _ -> share(activity, text) }
            .setNeutralButton("Copy") { _, _ -> copy(activity, text) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun share(context: Context, text: String) {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("crash", text))
    }
}
