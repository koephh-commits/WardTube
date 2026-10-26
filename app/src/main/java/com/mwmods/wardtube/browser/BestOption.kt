// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.browser

import com.mwmods.wardtube.util.ShareLinks

/**
 * Picks the item of the "Found on this page" list that is marked "Recommended": the best thing to download.
 *
 * The app never asks a server about an item (a signed address may answer only once), so it knows no size and no
 * quality. The choice rests on what each kind of item is, best first:
 *  1. the page's own video on a site yt-dlp handles with a dedicated extractor ([ShareLinks.isMediaSite]: YouTube,
 *     TikTok, Facebook, Instagram, Vimeo...): it picks the best quality, joins picture and sound and names the file,
 *     while the file addresses such sites show to a page are often picture only or sound only;
 *  2. a video file: one file with nothing to join, the quickest and the most reliable;
 *  3. a stream (m3u8 / mpd): yt-dlp downloads and joins the pieces, which takes longer;
 *  4. an audio file.
 * Unconfirmed items (the page's own video on any other site, a player in a frame) are never recommended.
 * Plain files (zip, pdf...) are never recommended: they are not alternatives to each other.
 *
 * Between items of the same rank, the one whose address or name mentions the highest resolution (`1080p`, `_720.mp4`)
 * wins (a player often lists the same video in several sizes), and then the one found first, because the page's main
 * player usually loads before the rest.
 */
object BestOption {

    /** The recommended item of [items] (the list as the sheet shows it), or null when none of them can be one. */
    fun of(items: List<Detected>): Detected? {
        var best: Detected? = null
        var bestRank = Int.MAX_VALUE
        var bestResolution = 0
        for (item in items) {
            val rank = rankOf(item) ?: continue
            val resolution = resolutionOf(item)
            // A strict comparison: of equal items the first one stays.
            if (rank < bestRank || (rank == bestRank && resolution > bestResolution)) {
                best = item
                bestRank = rank
                bestResolution = resolution
            }
        }
        return best
    }

    /** How good the kind of [item] is (0 is the best), or null for what is never recommended. */
    private fun rankOf(item: Detected): Int? = when (item.kind) {
        // Only confirmed items are ever recommended: a page of a site with a dedicated extractor, or a direct file
        // address. A guessed page (yt-dlp has to find the video by itself) and a frame are never recommended.
        Detected.Kind.PAGE -> if (ShareLinks.isMediaSite(item.url)) 0 else null
        Detected.Kind.VIDEO -> 1
        Detected.Kind.STREAM -> 2
        Detected.Kind.FRAME -> null
        Detected.Kind.AUDIO -> 5
        Detected.Kind.FILE -> null
    }

    /**
     * A resolution written in the item's address (without its query) or name: "1080p" anywhere, or a plain number
     * between separators ("video_720.mp4", "/480/index.m3u8"). The highest one found; 0 when there is none.
     */
    private fun resolutionOf(item: Detected): Int {
        val text = item.url.substringBefore('?').substringBefore('#') + ' ' + item.name
        var best = 0
        for (match in RESOLUTION.findAll(text)) {
            val value = (match.groups[1] ?: match.groups[2])?.value?.toIntOrNull() ?: continue
            if (value in MIN_RESOLUTION..MAX_RESOLUTION && value > best) best = value
        }
        return best
    }

    private const val MIN_RESOLUTION = 144
    private const val MAX_RESOLUTION = 4320

    /** Group 1: a number followed by "p" ("1080p"). Group 2: one of the usual heights alone between separators. */
    private val RESOLUTION = Regex(
        """(?<![A-Za-z0-9])(\d{3,4})p(?![A-Za-z0-9])|(?<=[_\-./])(2160|1440|1080|720|480|360|240)(?=[_\-./]|$)"""
    )
}
