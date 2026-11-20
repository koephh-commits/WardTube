// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.download

/**
 * Keeps a download under a speed limit. After each piece of data that arrives, [delayFor] says how long to wait before
 * reading the next one, so that the average speed never goes above the limit.
 *
 * It is a ceiling, not a promise: a connection that is slower than the limit is not helped by it, and it does not "save
 * up" the time it was slow to spend it in a burst afterwards (the count starts again whenever the download is behind).
 * The limit is given with every call, so a change of the limit takes effect with the next piece of data.
 *
 * Not thread safe: one instance belongs to the reading loop of one download.
 */
class SpeedLimiter {

    private var windowStart = 0L
    private var windowBytes = 0L

    /**
     * [bytes] have just been read, at time [now] (a monotonic clock, in milliseconds); the limit is [bytesPerSecond]
     * (0 or less = no limit). Returns how many milliseconds to wait before the next read: 0 when no wait is needed.
     */
    fun delayFor(bytes: Int, bytesPerSecond: Long, now: Long): Long {
        if (bytesPerSecond <= 0L || bytes <= 0) {
            // No limit (or nothing arrived): a count that starts later starts from this moment.
            windowStart = now
            windowBytes = 0L
            return 0L
        }
        windowBytes += bytes
        // The moment by which this many bytes could have arrived, at exactly the limit.
        val due = windowStart + windowBytes * 1000L / bytesPerSecond
        val wait = due - now
        if (wait <= 0L) {
            // Already slower than the limit: nothing to wait for, and no credit is kept for later.
            windowStart = now
            windowBytes = 0L
            return 0L
        }
        return wait
    }
}
