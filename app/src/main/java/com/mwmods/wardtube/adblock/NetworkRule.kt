// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.adblock

import java.util.regex.Pattern

/** Everything a rule needs to know about one request. [url] is lower-case. */
internal class RequestContext(
    val url: String,
    val host: String,
    val pageHost: String?,
    val thirdParty: Boolean,
    val typeMask: Int,
) {
    val tokens: List<String> by lazy(LazyThreadSafetyMode.NONE) { tokenize(url) }
}

internal const val MIN_TOKEN = 3

internal fun isTokenChar(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9')

/** Splits a URL into runs of letters and digits (at least [MIN_TOKEN] long). */
internal fun tokenize(url: String): List<String> {
    val tokens = ArrayList<String>(32)
    val limit = minOf(url.length, 1024)
    var start = -1
    for (i in 0..limit) {
        val inToken = i < limit && isTokenChar(url[i])
        if (inToken) {
            if (start < 0) start = i
        } else if (start >= 0) {
            if (i - start >= MIN_TOKEN) tokens.add(url.substring(start, i))
            start = -1
        }
    }
    return tokens
}

/** True if [host] is [domain] or one of its subdomains. */
internal fun hostMatches(host: String, domain: String): Boolean {
    if (host == domain) return true
    val cut = host.length - domain.length
    return cut > 0 && host.endsWith(domain) && host[cut - 1] == '.'
}

/**
 * One network filter (`||ads.example.com^$third-party`, `/banner/`, ...), already parsed.
 * [pattern] is lower-case with the anchors removed.
 */
internal class NetworkRule(
    private val pattern: String,
    private val hostAnchored: Boolean,
    private val startAnchored: Boolean,
    private val endAnchored: Boolean,
    private val typeMask: Int,
    /** 0 = any, 1 = third-party only, -1 = first-party only */
    private val thirdParty: Int,
    private val includeDomains: List<String>?,
    private val excludeDomains: List<String>?,
    val important: Boolean,
) {
    private val needsRegex = hostAnchored || pattern.indexOf('*') >= 0 || pattern.indexOf('^') >= 0

    @Volatile
    private var regex: Pattern? = null

    fun matches(ctx: RequestContext): Boolean {
        if ((typeMask and ctx.typeMask) == 0) return false
        if (thirdParty != 0 && (thirdParty > 0) != ctx.thirdParty) return false
        if ((includeDomains != null || excludeDomains != null) && !pageDomainMatches(ctx.pageHost)) return false
        return urlMatches(ctx.url)
    }

    private fun pageDomainMatches(pageHost: String?): Boolean {
        if (pageHost == null) return includeDomains == null
        if (excludeDomains != null) {
            for (domain in excludeDomains) if (hostMatches(pageHost, domain)) return false
        }
        if (includeDomains == null) return true
        for (domain in includeDomains) if (hostMatches(pageHost, domain)) return true
        return false
    }

    private fun urlMatches(url: String): Boolean {
        if (!needsRegex) {
            return when {
                startAnchored && endAnchored -> url == pattern
                startAnchored -> url.startsWith(pattern)
                endAnchored -> url.endsWith(pattern)
                else -> url.contains(pattern)
            }
        }
        val compiled = regex ?: Pattern.compile(toRegex()).also { regex = it }
        return compiled.matcher(url).find()
    }

    private fun toRegex(): String {
        val sb = StringBuilder()
        if (hostAnchored) {
            sb.append("^[a-z][a-z0-9+.\\-]*://(?:[^/?#]*\\.)?")
        } else if (startAnchored) {
            sb.append('^')
        }
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-z0-9_\\-.%]|$)")
                else -> {
                    if (c.code < 128 && !c.isLetterOrDigit()) sb.append('\\')
                    sb.append(c)
                }
            }
        }
        if (endAnchored) sb.append('$')
        return sb.toString()
    }

    /**
     * Host for the host index, when the rule can only match requests to that host or its
     * subdomains (`||ads.example.com^...`). Null if the rule has to be indexed another way.
     */
    fun hostKey(): String? {
        if (!hostAnchored) return null
        var end = 0
        while (end < pattern.length && isHostChar(pattern[end])) end++
        if (end == 0) return null
        if (end == pattern.length) {
            if (!endAnchored) return null
        } else if (pattern[end] !in "^/:?") {
            return null
        }
        val host = pattern.substring(0, end)
        if (host.indexOf('.') < 0 || host.startsWith('.') || host.endsWith('.')) return null
        return host
    }

    /**
     * The token this rule is filed under. Every URL the rule matches contains the token as a
     * whole letters-and-digits run, so only the rules of a URL's tokens need to be tried.
     */
    fun token(): String? {
        val anchoredStart = hostAnchored || startAnchored
        var best: String? = null
        var i = 0
        while (i < pattern.length) {
            if (!isTokenChar(pattern[i])) {
                i++
                continue
            }
            var j = i
            while (j < pattern.length && isTokenChar(pattern[j])) j++
            val startOk = if (i == 0) anchoredStart else pattern[i - 1] != '*'
            val endOk = if (j == pattern.length) endAnchored else pattern[j] != '*'
            if (startOk && endOk && j - i >= MIN_TOKEN && (best == null || j - i > best.length)) {
                best = pattern.substring(i, j)
            }
            i = j
        }
        return best
    }

    private fun isHostChar(c: Char): Boolean = isTokenChar(c) || c == '.' || c == '-'
}
