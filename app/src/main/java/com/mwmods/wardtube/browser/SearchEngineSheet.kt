// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mwmods.wardtube.R
import com.mwmods.wardtube.ui.MaxHeightScrollView

/**
 * The search engine picker: a bottom sheet with the engines in two sections (web search, video sites). The engine in
 * use is highlighted and ticked; a tap on another one chooses it and closes the sheet.
 */
object SearchEngineSheet {

    fun show(activity: AppCompatActivity, current: SearchEngine, onChosen: (SearchEngine) -> Unit) {
        val inflater = activity.layoutInflater
        val content = inflater.inflate(R.layout.bottom_sheet_browser_list, null)
        content.findViewById<TextView>(R.id.sheetTitle).setText(R.string.browserEngineTitle)
        content.findViewById<TextView>(R.id.sheetSubtitle).setText(R.string.browserEngineSubtitle)
        content.findViewById<View>(R.id.sheetCount).visibility = View.GONE
        content.findViewById<MaxHeightScrollView>(R.id.sheetScroll).maxHeightPx = BrowserSheet.listMaxHeight(activity)
        val list = content.findViewById<LinearLayout>(R.id.sheetList)
        val dialog = BrowserSheet.create(activity, content)

        // groupBy keeps the order of the first engine of each group: web search first.
        for ((group, engines) in SearchEngines.all.groupBy { it.group }) {
            val header = inflater.inflate(R.layout.item_sheet_section, list, false)
            header.findViewById<TextView>(R.id.sectionTitle).setText(
                when (group) {
                    SearchEngine.Group.WEB -> R.string.browserEngineGroupWeb
                    SearchEngine.Group.VIDEO -> R.string.browserEngineGroupVideo
                }
            )
            header.findViewById<View>(R.id.sectionCount).visibility = View.GONE
            list.addView(header)

            for (engine in engines) {
                val selected = engine.id == current.id
                val row = inflater.inflate(R.layout.item_search_engine, list, false)
                row.isActivated = selected
                engine.paintBadge(row.findViewById(R.id.engineBadge))
                row.findViewById<TextView>(R.id.engineName).text = engine.name
                row.findViewById<TextView>(R.id.engineHost).text = engine.host
                row.findViewById<View>(R.id.engineCheck).visibility = if (selected) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener {
                    dialog.dismiss()
                    if (!selected) onChosen(engine)
                }
                list.addView(row)
            }
        }
        dialog.show()
    }
}
