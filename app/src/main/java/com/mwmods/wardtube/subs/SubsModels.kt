// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.subs

/** A channel the person is subscribed to. [avatar] is the https address of its round picture, or empty. */
data class SubsChannel(val id: String, val name: String, val avatar: String)

/**
 * A video of the feed. [duration], [published] and [views] are the texts YouTube itself wrote (in the phone's language,
 * like "5:13:41", "3 hours ago", "38K views"), or empty. A video that came from a channel's RSS feed has no such texts
 * but a date ([publishedMs], milliseconds since 1970) and a number ([viewCount], -1 when unknown) instead.
 */
data class SubsVideo(
    val id: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val duration: String = "",
    val published: String = "",
    val views: String = "",
    val publishedMs: Long = 0L,
    val viewCount: Long = -1L,
    /** A Short: it opens in the Shorts player and has no duration, date or channel name on the feed. */
    val short: Boolean = false,
    /** 0: a normal video, 1: live now, 2: scheduled (not started), 3: a past live stream (from a channel's Live tab). */
    val live: Int = 0,
    /** How much of the video the signed-in account has watched, in percent (0: not watched, or YouTube did not say). */
    val watched: Int = 0,
    /**
     * Set when this is not a video but a post of a channel (see [SubsPost]). A post travels through the feed and the
     * lists inside a SubsVideo so that it is ordered by age, hidden, saved on the phone and counted like a video: its
     * [id] is the post's id, its [title] the start of its text, [published] YouTube's own "18 hours ago".
     */
    val post: SubsPost? = null,
)

/** A link inside the text of a post: the characters from [start] to [end] (not included) open [url]. */
data class PostLink(val start: Int, val end: Int, val url: String)

/** One choice of a poll: its words and its picture (both may be empty, not both at once). */
data class PostChoice(val text: String, val image: String = "")

/**
 * A post of a channel (what YouTube calls a community post), as its card shows it: the words (with their [links]), then
 * what is attached to them, which is pictures, a poll or a video. [likes] and [comments] are YouTube's own texts
 * ("41", "1.2K"), or empty. Every address is https.
 */
data class SubsPost(
    val text: String,
    val links: List<PostLink> = emptyList(),
    /** The round picture of the channel that wrote it (empty: the one of the channels' strip is used). */
    val avatar: String = "",
    val images: List<String> = emptyList(),
    val choices: List<PostChoice> = emptyList(),
    /** The poll's "1K votes" text, or empty. */
    val votes: String = "",
    /** A video attached to the post (empty when there is none) and its title. */
    val videoId: String = "",
    val videoTitle: String = "",
    val likes: String = "",
    val comments: String = "",
)

/** The chips under the strip of channels, in the order they are shown (the position is the value). */
object SubsFilter {
    const val ALL = 0
    const val TODAY = 1
    const val VIDEOS = 2
    const val SHORTS = 3
    const val LIVE = 4
    const val POSTS = 5
}

/**
 * A filter chip of YouTube's Subscriptions page ("All", "Today", "Videos", "Shorts"...): [text] is its label in the
 * phone's language and [token] the continuation that loads its own list ("" when the page gives the chip none, as for
 * the chip that is already chosen).
 */
class SubsChip(val text: String, val token: String)

/**
 * One page of videos read from YouTube, with what is needed to ask for the next one ([token] null: no more).
 * [chips] are the filter chips of the page (only the first page of the feed has them).
 */
class SubsPage(
    val videos: List<SubsVideo>,
    val token: String?,
    val apiKey: String?,
    val context: String?,
    val chips: List<SubsChip> = emptyList(),
    /** What the answer held, in a few words (for the note under an empty list; see SubsRepository.describe). */
    val note: String = "",
)

/** What is kept on the phone between runs, so the screen opens at once and refreshes in the background. */
class SubsSnapshot(
    val account: String,
    val channels: List<SubsChannel>,
    val videos: List<SubsVideo>,
    val channelsAt: Long,
    val feedAt: Long,
)
