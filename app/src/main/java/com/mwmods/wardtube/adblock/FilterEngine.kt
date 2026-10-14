// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.adblock

import java.io.BufferedReader

/**
 * Blocking engine for Adblock Plus style filter lists.
 *
 * Network rules are matched against every request. Element-hiding rules are turned into a
 * stylesheet for YouTube only: the app never shows another site, so rules for other sites are
 * dropped while parsing, which also keeps memory use low on small phones.
 */
internal class FilterEngine private constructor(
    private val blocking: RuleSet,
    private val important: RuleSet,
    private val exceptions: RuleSet,
    /** CSS hiding the elements the lists name for YouTube. */
    val cosmeticCss: String,
    val ruleCount: Int,
) {

    fun shouldBlock(ctx: RequestContext): Boolean {
        if (important.find(ctx) != null) return true
        if (blocking.find(ctx) == null) return false
        return exceptions.find(ctx) == null
    }

    class Builder {
        private val blocking = RuleSet.Builder()
        private val important = RuleSet.Builder()
        private val exceptions = RuleSet.Builder()
        private val selectors = LinkedHashSet<String>()
        private val selectorExceptions = HashSet<String>()

        fun addList(reader: BufferedReader) {
            while (true) {
                val line = reader.readLine() ?: break
                addLine(line)
            }
        }

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty()) return
            val first = line[0]
            if (first == '!' || first == '[') return

            val cosmetic = FilterParser.parseCosmetic(line)
            if (cosmetic != null) {
                addCosmetic(cosmetic)
                return
            }
            val parsed = FilterParser.parseNetwork(line) ?: return
            val target = when {
                parsed.exception -> exceptions
                parsed.rule.important -> important
                else -> blocking
            }
            target.add(parsed.rule)
        }

        private fun addCosmetic(line: CosmeticLine) {
            if (line.extended || !FilterParser.isSupportedSelector(line.selector)) return
            val domains = line.domains.split(',').filter { it.isNotEmpty() }
            val hasInclude = domains.any { !it.startsWith("~") }
            if (!hasInclude && !APPLY_GENERIC_HIDING) return

            var listed = false
            var excluded = false
            for (entry in domains) {
                val negated = entry.startsWith("~")
                if (!hostMatches(TARGET_HOST, entry.removePrefix("~"))) continue
                if (negated) excluded = true else listed = true
            }
            if (excluded || (hasInclude && !listed)) return

            if (line.exception) selectorExceptions.add(line.selector) else selectors.add(line.selector)
        }

        fun build(): FilterEngine {
            val css = StringBuilder()
            var kept = 0
            for (selector in selectors) {
                if (selector in selectorExceptions) continue
                // One CSS rule per filter line: a line this WebView cannot parse only drops itself.
                css.append(selector).append("{display:none!important}\n")
                if (++kept >= MAX_SELECTORS) break
            }
            return FilterEngine(
                blocking.build(), important.build(), exceptions.build(),
                css.toString(), blocking.size + important.size + exceptions.size + kept,
            )
        }
    }
}

/** The page the element-hiding rules are compiled for. */
private const val TARGET_HOST = "m.youtube.com"

/**
 * Rules without a domain (`##.ad-banner`) hide things on every site. EasyList has thousands
 * of them and they can hide parts of YouTube's own interface, so they are skipped.
 */
private const val APPLY_GENERIC_HIDING = false

private const val MAX_SELECTORS = 2000
