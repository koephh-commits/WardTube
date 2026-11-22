// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.mwmods.wardtube.R

/**
 * The side menu of the "Side menu" display mode ([com.mwmods.wardtube.Prefs.sideMenu]): the six buttons of the bottom
 * bar as rows of a drawer that opens from the start edge (the right in Arabic). The bottom bar is gone in that mode
 * and a button in a bar at the top opens this drawer.
 *
 * The rows do exactly what the bar's buttons do: they call [MenuController.perform] with the same ids, so nothing is
 * done twice in code. The two buttons that change with the state of the app follow it through
 * [MenuController.onSlotsChanged]: the first row is the voice search microphone, or the minimize arrow while a video
 * is full screen; the download row is dimmed (still tappable, as in the bar) while there is nothing to download. The
 * last row opens the three-dots sheet, which is where the mode is switched back.
 *
 * [setActive] switches the whole drawer on or off. While it is off the drawer is not even in the layout and cannot be
 * pulled out by dragging from the edge.
 */
class SideMenu(
    private val activity: AppCompatActivity,
    private val drawerLayout: DrawerLayout,
    private val drawer: View,
    private val menu: MenuController,
) {

    /** A row of the drawer, with what it shows now: it is updated by [refresh]. */
    private class Entry(val id: Int, val row: View, val icon: ImageView, val title: TextView)

    private val entries = ArrayList<Entry>()

    init {
        val list = drawer.findViewById<LinearLayout>(R.id.sideDrawerList)
        drawer.findViewById<TextView>(R.id.sideDrawerVersion).apply {
            val version = menu.appVersionLabel()
            if (version != null) {
                text = version
                visibility = View.VISIBLE
            }
        }
        // The same six slots, in the same order as the bar.
        addEntry(list, R.id.action_minimize, R.color.menuAccentRed)
        addEntry(list, R.id.action_home, R.color.menuAccentTeal)
        addEntry(list, R.id.action_browser, R.color.menuAccentBlue)
        addEntry(list, R.id.action_download, R.color.menuAccentPurple)
        addEntry(list, R.id.action_library, R.color.menuAccentAmber)
        addEntry(list, R.id.action_more, R.color.menuAccentGray)
        refresh()
        menu.onSlotsChanged = { refresh() }
    }

    /** Turns the drawer on (side menu mode) or off. Off: closed at once, out of the layout, and locked. */
    fun setActive(active: Boolean) {
        if (!active) close(animate = false)
        drawer.visibility = if (active) View.VISIBLE else View.GONE
        drawerLayout.setDrawerLockMode(
            if (active) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START
        )
    }

    fun isOpen(): Boolean = drawerLayout.isDrawerOpen(GravityCompat.START)

    fun open() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    fun close(animate: Boolean = true) {
        if (isOpen()) drawerLayout.closeDrawer(GravityCompat.START, animate)
    }

    private fun addEntry(list: LinearLayout, id: Int, @ColorRes accent: Int) {
        val row = activity.layoutInflater.inflate(R.layout.item_menu_row, list, false)
        val icon = row.findViewById<ImageView>(R.id.menuRowIcon)
        val color = ContextCompat.getColor(activity, accent)
        icon.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, TILE_ALPHA))
        icon.imageTintList = ColorStateList.valueOf(color)
        row.setOnClickListener {
            // The drawer closes first; the action (a screen, a sheet, a message) follows.
            close()
            menu.perform(id)
        }
        list.addView(row)
        entries += Entry(id, row, icon, row.findViewById(R.id.menuRowTitle))
    }

    /** Puts each row's icon, title and strength in line with the app's state. */
    private fun refresh() {
        for (entry in entries) {
            val title: Int
            val icon: Int
            when (entry.id) {
                R.id.action_minimize -> if (menu.isVideoFullScreen) {
                    title = R.string.minimizePlayer
                    icon = R.drawable.ic_expand_more_white_24dp
                } else {
                    title = R.string.voiceSearch
                    icon = R.drawable.ic_mic_white_24dp
                }
                R.id.action_home -> {
                    title = R.string.home
                    icon = R.drawable.ic_home_white_24dp
                }
                R.id.action_browser -> {
                    title = R.string.browser
                    icon = R.drawable.ic_globe_white_24dp
                }
                R.id.action_download -> {
                    title = R.string.download
                    icon = R.drawable.ic_download_24dp
                }
                R.id.action_library -> {
                    title = R.string.library
                    icon = R.drawable.ic_library_white_24dp
                }
                else -> {
                    title = R.string.menuMore
                    icon = R.drawable.ic_dots_vertical_white_24dp
                }
            }
            entry.title.setText(title)
            entry.icon.setImageResource(icon)
            // As in the bar: nothing to download dims the button, but it can still be tapped (it explains what to do).
            entry.row.alpha = if (entry.id == R.id.action_download && !menu.isDownloadAvailable) DIMMED_ROW_ALPHA else 1f
        }
    }

    private companion object {
        /** The same soft fill behind a row's icon as in the three-dots sheet (about 18 percent of the accent). */
        const val TILE_ALPHA = 0x2E

        const val DIMMED_ROW_ALPHA = 0.45f
    }
}
