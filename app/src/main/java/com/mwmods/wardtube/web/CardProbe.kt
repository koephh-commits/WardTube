// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Finds out, from the app's side, whether a touch on the browsing page fell on the three dots of a video's card, and which
 * video the card is.
 *
 * Why the app does it: the page script (`menu-download.js`) notes the taps itself, but it can only be trusted as far as it
 * is running in the page and sees the event, and neither could be checked on a phone. This probe is a self-contained
 * function that the app hands to the page ([script]) the moment a finger goes down: it needs no script in the page, no
 * bridge and no YouTube menu. It looks at what is drawn under the finger *before* the tap opens YouTube's menu (which
 * dims the page, so afterwards the dots could no longer be found there).
 *
 * The answer is a card's video, or nothing:
 *  - the touch is on a small pressable (button, `role=button`, icon button, `ytm-menu`...) that is not a link and not in
 *    a menu, a dialog, a bar of the page, a chip bar or the player;
 *  - going up from it, the first ancestor that links a video links exactly one (the card); one that links two different
 *    videos is not a card, so nothing is answered.
 */
object CardProbe {
    class Hit(val url: String, val title: String?)

    private val ID = Regex("[A-Za-z0-9_-]{11}")

    /**
     * A function expression `function (px, py)`: (px, py) is the touch in device pixels, from the top left of the WebView.
     * Returns a JSON text `{"k": "video" | "shorts", "i": id, "t": title}`, or an empty text. ES5 only.
     */
    private const val FUNCTION = """function (px, py) {
  try {
    var d = window.devicePixelRatio || 1;
    var el = document.elementFromPoint(px / d, py / d);
    if (!el || !el.closest) return '';
    var NO = '[role="dialog"], [role="menu"], [role="menuitem"], ytm-bottom-sheet-renderer, bottom-sheet-layout, ' +
      'ytm-menu-popup-renderer, yt-contextual-sheet-layout, yt-sheet-view-model, ytm-pivot-bar-renderer, ' +
      'ytm-mobile-topbar-renderer, header, nav, [role="tablist"], ytm-chip-cloud-renderer, video, [data-wt-dl]';
    if (el.closest(NO)) return '';
    var btn = el.closest('button, [role="button"], yt-icon-button, button-view-model, yt-button-shape, ytm-menu, ytm-menu-renderer');
    if (!btn || btn.closest('a[href]')) return '';
    var r = btn.getBoundingClientRect();
    if (r.width > 72 || r.height > 72 || r.width < 12 || r.height < 12) return '';
    var LINKS = 'a[href*="/watch?v="], a[href*="/shorts/"]';
    function parse(a) {
      var h = a.getAttribute('href') || '';
      var m = /[?&]v=([A-Za-z0-9_-]{11})/.exec(h);
      if (m && h.indexOf('/watch') !== -1) return { k: 'video', i: m[1] };
      m = /\/shorts\/([A-Za-z0-9_-]{11})/.exec(h);
      return m ? { k: 'shorts', i: m[1] } : null;
    }
    function videoIn(root) {
      var ls = root.querySelectorAll(LINKS), first = null;
      for (var j = 0; j < ls.length; j++) {
        var v = parse(ls[j]);
        if (!v) continue;
        if (!first) first = v;
        else if (v.i !== first.i) return 'many';
      }
      return first;
    }
    var n = btn.parentElement, hops = 0, card = null, vid = null;
    while (n && n !== document.body && hops < 16) {
      var q = videoIn(n);
      if (q === 'many') return '';
      if (q) { card = n; vid = q; break; }
      n = n.parentElement;
      hops++;
    }
    if (!card) return '';
    var T = ['h3', 'h4', '[role="heading"]', '.media-item-headline', '[class*="title" i]'];
    var title = '';
    for (var a = 0; a < T.length && !title; a++) {
      var e = card.querySelector(T[a]);
      var s = e ? (e.textContent || '').replace(/\s+/g, ' ').trim() : '';
      if (s.length > 1) title = s.slice(0, 200);
    }
    if (!title) {
      var l = card.querySelector(LINKS);
      if (l) title = (l.getAttribute('aria-label') || l.getAttribute('title') || '').replace(/\s+/g, ' ').trim().slice(0, 200);
    }
    return JSON.stringify({ k: vid.k, i: vid.i, t: title });
  } catch (err) {
    return '';
  }
}"""

    /** The script to evaluate in the page for a touch at ([x], [y]) device pixels from the top left of the WebView. */
    fun script(x: Float, y: Float): String = "($FUNCTION)(${x.toInt()},${y.toInt()})"

    /** [raw] is what `evaluateJavascript` hands back: a JSON-quoted text, "null", or empty. */
    fun parse(raw: String?): Hit? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = o.optString("i")
        if (!ID.matches(id)) return null
        val url = if (o.optString("k") == "shorts") {
            "https://m.youtube.com/shorts/$id"
        } else {
            "https://m.youtube.com/watch?v=$id"
        }
        val title = o.optString("t").trim().take(200).takeIf { it.isNotEmpty() }
        return Hit(url, title)
    }
}
