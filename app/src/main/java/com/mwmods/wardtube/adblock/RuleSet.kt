// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.adblock

/** Network rules indexed by host and by token, so a request only meets a handful of candidates. */
internal class RuleSet private constructor(
    private val hostIndex: HashMap<String, Array<NetworkRule>>,
    private val tokenIndex: HashMap<String, Array<NetworkRule>>,
    private val untokenized: Array<NetworkRule>,
) {

    fun find(ctx: RequestContext): NetworkRule? {
        var host = ctx.host
        while (true) {
            val bucket = hostIndex[host]
            if (bucket != null) {
                for (rule in bucket) if (rule.matches(ctx)) return rule
            }
            val dot = host.indexOf('.')
            if (dot < 0) break
            host = host.substring(dot + 1)
        }
        for (token in ctx.tokens) {
            val bucket = tokenIndex[token] ?: continue
            for (rule in bucket) if (rule.matches(ctx)) return rule
        }
        for (rule in untokenized) if (rule.matches(ctx)) return rule
        return null
    }

    class Builder {
        private val hosts = HashMap<String, ArrayList<NetworkRule>>()
        private val tokens = HashMap<String, ArrayList<NetworkRule>>()
        private val rest = ArrayList<NetworkRule>()

        var size = 0
            private set

        fun add(rule: NetworkRule) {
            size++
            val host = rule.hostKey()
            if (host != null) {
                hosts.getOrPut(host) { ArrayList(2) }.add(rule)
                return
            }
            val token = rule.token()
            if (token != null) {
                tokens.getOrPut(token) { ArrayList(2) }.add(rule)
            } else {
                rest.add(rule)
            }
        }

        fun build(): RuleSet = RuleSet(freeze(hosts), freeze(tokens), rest.toTypedArray())

        private fun freeze(map: HashMap<String, ArrayList<NetworkRule>>): HashMap<String, Array<NetworkRule>> {
            val frozen = HashMap<String, Array<NetworkRule>>(map.size * 2)
            for ((key, list) in map) frozen[key] = list.toTypedArray()
            return frozen
        }
    }
}
