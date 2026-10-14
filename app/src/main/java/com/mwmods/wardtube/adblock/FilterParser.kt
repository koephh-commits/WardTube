// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.adblock

internal class ParsedNetworkRule(val rule: NetworkRule, val exception: Boolean)

/** `domains##selector`, `domains#@#selector`; [extended] marks `#?#`, `#$#` and similar. */
internal class CosmeticLine(
    val domains: String,
    val exception: Boolean,
    val extended: Boolean,
    val selector: String,
)

/** Parser for the Adblock Plus filter syntax used by EasyList and Liste AR. */
internal object FilterParser {

    private val OPTIONS = Regex("^~?[\\w-]+(=[^,\\s]*)?(,~?[\\w-]+(=[^,\\s]*)?)*$")

    private val UNSUPPORTED_SELECTORS = listOf(
        ":-abp-", ":has-text(", ":contains(", ":xpath(", ":matches-css", ":style(", ":remove(",
        ":upward(", ":nth-ancestor(", ":min-text-length(", ":watch-attr(", ":others(",
        ":matches-attr(", ":matches-path(", ":-js(", "+js(",
    )

    /** Returns null if [line] is not an element-hiding rule. */
    fun parseCosmetic(line: String): CosmeticLine? {
        val hash = line.indexOf('#')
        if (hash < 0) return null
        for (i in 0 until hash) {
            if (line[i] in "/*|@\"!") return null
        }
        var i = hash + 1
        var exception = false
        if (i < line.length && line[i] == '@') {
            exception = true
            i++
        }
        var extended = false
        if (i < line.length && (line[i] == '?' || line[i] == '$' || line[i] == '%')) {
            extended = true
            i++
        }
        if (i >= line.length || line[i] != '#') return null
        val selector = line.substring(i + 1).trim()
        if (selector.isEmpty()) return null
        return CosmeticLine(line.substring(0, hash).lowercase(), exception, extended, selector)
    }

    /** Only plain CSS goes into the stylesheet, and nothing that could break out of the rule. */
    fun isSupportedSelector(selector: String): Boolean {
        if (selector.indexOf('{') >= 0 || selector.indexOf('}') >= 0 || selector.indexOf(';') >= 0) return false
        if (selector.indexOf('\n') >= 0 || selector.startsWith("@") || selector.contains("url(")) return false
        return UNSUPPORTED_SELECTORS.none { selector.contains(it) }
    }

    /**
     * Parses a network rule. Returns null for rules that cannot (or need not) be applied here:
     * regular-expression rules, options we cannot honour (`$popup`, `$csp`, `$redirect`, ...) and
     * rules limited to sites other than YouTube.
     */
    fun parseNetwork(raw: String): ParsedNetworkRule? {
        var line = raw
        var exception = false
        if (line.startsWith("@@")) {
            exception = true
            line = line.substring(2)
        }

        var options: String? = null
        val dollar = line.lastIndexOf('$')
        if (dollar >= 0) {
            val candidate = line.substring(dollar + 1)
            if (OPTIONS.matches(candidate)) {
                options = candidate
                line = line.substring(0, dollar)
            }
        }
        if (line.length >= 3 && line.startsWith("/") && line.endsWith("/")) return null

        var thirdParty = 0
        var positive = 0
        var negative = 0
        var important = false
        var include: List<String>? = null
        var exclude: List<String>? = null

        if (options != null) {
            for (option in options.split(',')) {
                val negated = option.startsWith("~")
                val body = option.removePrefix("~")
                val eq = body.indexOf('=')
                val name = (if (eq >= 0) body.substring(0, eq) else body).lowercase()
                val value = if (eq >= 0) body.substring(eq + 1) else ""
                when (name) {
                    "third-party", "3p" -> thirdParty = if (negated) -1 else 1
                    "first-party", "1p" -> thirdParty = if (negated) 1 else -1
                    "important" -> important = true
                    "match-case" -> Unit
                    "all" -> positive = ResourceType.ALL
                    "domain" -> {
                        val included = ArrayList<String>()
                        val excluded = ArrayList<String>()
                        for (domain in value.lowercase().split('|')) {
                            if (domain.isEmpty()) continue
                            if (domain.startsWith("~")) excluded.add(domain.substring(1)) else included.add(domain)
                        }
                        include = if (included.isEmpty()) null else included
                        exclude = if (excluded.isEmpty()) null else excluded
                    }
                    else -> {
                        val type = ResourceType.fromOption(name) ?: return null
                        if (negated) negative = negative or type else positive = positive or type
                    }
                }
            }
        }

        // A rule limited to other sites can never match: the app only shows YouTube.
        val included = include
        if (included != null && included.none { isRelevantDomain(it) }) return null

        val typeMask = (if (positive != 0) positive else ResourceType.ALL) and negative.inv()
        if (typeMask == 0) return null

        var pattern = line.lowercase()
        var hostAnchored = false
        var startAnchored = false
        var endAnchored = false
        if (pattern.startsWith("||")) {
            hostAnchored = true
            pattern = pattern.substring(2)
        } else if (pattern.startsWith("|")) {
            startAnchored = true
            pattern = pattern.substring(1)
        }
        if (pattern.endsWith("|")) {
            endAnchored = true
            pattern = pattern.substring(0, pattern.length - 1)
        }
        if (!hostAnchored && !startAnchored) pattern = pattern.trimStart('*')
        if (!endAnchored) pattern = pattern.trimEnd('*')
        // An empty pattern, or one made only of wildcards, would match every request.
        if (pattern.isEmpty() || pattern.all { it == '*' || it == '^' }) return null

        val rule = NetworkRule(
            pattern, hostAnchored, startAnchored, endAnchored,
            typeMask, thirdParty, include, exclude, important,
        )
        return ParsedNetworkRule(rule, exception)
    }

    private fun isRelevantDomain(domain: String): Boolean =
        domain.contains("youtube") || domain.contains("google") || domain == "youtu.be"
}
