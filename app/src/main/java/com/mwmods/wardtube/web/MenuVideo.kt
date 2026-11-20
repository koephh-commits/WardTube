// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

/**
 * The last way to find which video the three dots belong to, used when [CardProbe] and [TapProbe] found nothing (they
 * found nothing on Search and Watch history) or were not answered at all.
 *
 * It does not look at the card at all. It is run only *after* YouTube's own menu has opened because of the touch (the
 * app waits for that), and then it asks, in this order:
 *  1. the menu itself: the rows of the open sheet carry the data YouTube built them from (save to playlist, share, add to
 *     queue ...), and that data names the video. The most frequent video id in it is the answer;
 *  2. the pictures and links of the page behind the dimmed layer: the video whose picture or link is at the finger's
 *     line (or nearest above it) is the card that was tapped. Every card has its picture, whatever else it is made of.
 *
 * The answer has the same shape as [CardProbe]'s, so [CardProbe.parse] reads it.
 */
object MenuVideo {
    /** A function expression `function (px, py)`: the touch in device pixels from the top left of the WebView. ES5. */
    private const val FUNCTION = """function (px, py) {
  try {
    var d = window.devicePixelRatio || 1;
    var x = px / d, y = py / d;
    var vw = window.innerWidth, vh = window.innerHeight;
    var ID = /^[A-Za-z0-9_-]{11}${'$'}/;
    var SHEET = 'ytm-bottom-sheet-renderer, bottom-sheet-layout, ytm-menu-popup-renderer, yt-contextual-sheet-layout, yt-sheet-view-model, [role="dialog"], [role="menu"]';
    var ITEM = 'ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, yt-list-item-view-model, [role="menuitem"]';
    var KEYS = { videoId: 1, addedVideoId: 1, removedVideoId: 1, contentId: 1 };
    function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

    // 1. The data of the open menu.
    var counts = {}, seen = [], budget = 6000;
    function add(id) { if (ID.test(id)) counts[id] = (counts[id] || 0) + 1; }
    function walk(o, depth) {
      if (!o || budget-- < 0 || depth > 9) return;
      var t = typeof o;
      if (t === 'string') {
        var m = /(?:[?&]v=|\/shorts\/|\/vi\/)([A-Za-z0-9_-]{11})(?:[&\/?"]|${'$'})/.exec(o);
        if (m) add(m[1]);
        return;
      }
      if (t !== 'object' || o.nodeType || o === window) return;
      if (seen.indexOf(o) >= 0) return;
      seen.push(o);
      if (Array.isArray(o)) {
        for (var i = 0; i < o.length && i < 40; i++) walk(o[i], depth + 1);
        return;
      }
      for (var k in o) {
        var v;
        try { v = o[k]; } catch (e) { continue; }
        if (KEYS[k] && typeof v === 'string') add(v);
        else walk(v, depth + 1);
      }
    }
    var nodes = document.querySelectorAll(ITEM + ', ' + SHEET);
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i], r = n.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || r.bottom <= 0 || r.top >= vh) continue;
      var cur = n;
      for (var up = 0; up < 3 && cur; up++, cur = cur.parentElement) {
        try { walk(cur.data, 0); walk(cur.__data, 0); } catch (e) { /* next */ }
      }
    }
    var bestId = '', bestN = 0, ids = 0;
    for (var id in counts) { ids++; if (counts[id] > bestN) { bestN = counts[id]; bestId = id; } }
    var inMenu = bestId && ids === 1; // two different videos in a menu: not sure, the page is used instead

    // 2. The pictures and links of the page behind the menu: the one at the finger's line.
    function parse(a) {
      if (a.closest(SHEET)) return null;
      if (a.tagName === 'IMG') {
        var tm = /ytimg\.com\/(?:vi|vi_webp|an_webp)\/([A-Za-z0-9_-]{11})\//.exec(a.getAttribute('src') || a.getAttribute('data-src') || '');
        return tm ? { k: 'video', i: tm[1] } : null;
      }
      var h = a.getAttribute('href') || '';
      var m = /[?&]v=([A-Za-z0-9_-]{11})/.exec(h);
      if (m && h.indexOf('/watch') !== -1) return { k: 'video', i: m[1] };
      m = /\/shorts\/([A-Za-z0-9_-]{11})/.exec(h);
      return m ? { k: 'shorts', i: m[1] } : null;
    }
    var all = document.querySelectorAll('a[href*="/watch?"], a[href*="/shorts/"], img[src*="ytimg.com/vi"], img[data-src*="ytimg.com/vi"], img[src*="ytimg.com/an_webp"]');
    var groups = {}, order = [];
    for (var j = 0; j < all.length; j++) {
      var a = all[j], v = parse(a);
      if (!v) continue;
      var rr = a.getBoundingClientRect();
      if (rr.width < 8 || rr.height < 8) continue;
      var g = groups[v.i];
      if (!g) { g = groups[v.i] = { v: v, t: rr.top, b: rr.bottom, l: rr.left, r: rr.right, a: a, n: 0 }; order.push(g); }
      else { g.t = Math.min(g.t, rr.top); g.b = Math.max(g.b, rr.bottom); g.l = Math.min(g.l, rr.left); g.r = Math.max(g.r, rr.right); }
      var len = clean(a.textContent).length;
      if (len > g.n) { g.n = len; g.a = a; }
    }
    var best = null, bestCost = 1e9;
    for (var q = 0; q < order.length; q++) {
      var G = order[q];
      if (y < G.t - 6) continue;                  // above the card: not its dots
      var dy = y > G.b ? y - G.b : 0;
      if (dy > 120) continue;                     // under it by more than a row: the next card's
      var cost = dy * 3 + (y - G.t > 0 ? 0 : 50);
      if (cost < bestCost) { bestCost = cost; best = G; }
    }
    var pick = null;
    if (inMenu) {
      pick = { k: 'video', i: bestId };
      if (groups[bestId]) { pick.k = groups[bestId].v.k; best = groups[bestId]; }
    } else if (best) {
      pick = best.v;
    }
    if (!pick) return '';

    var title = '';
    if (best) {
      var c = best.a, TS = ['h3', 'h4', '[role="heading"]', '.media-item-headline', '[class*="title" i]'];
      for (var k = 0; k < 7 && c && c !== document.body && !title; k++, c = c.parentElement) {
        for (var ti = 0; ti < TS.length && !title; ti++) {
          var te = c.querySelector ? c.querySelector(TS[ti]) : null;
          var ts = te ? clean(te.textContent) : '';
          if (ts.length > 1) title = ts.slice(0, 200);
        }
      }
      if (!title) title = clean(best.a.getAttribute('aria-label') || best.a.getAttribute('title')).slice(0, 200);
    }
    return JSON.stringify({ k: pick.k, i: pick.i, t: title });
  } catch (err) {
    return '';
  }
}"""

    /** The script to evaluate once YouTube's menu is open, for a touch at ([x], [y]) device pixels from the WebView's top left. */
    fun script(x: Float, y: Float): String = "($FUNCTION)(${x.toInt()},${y.toInt()})"
}
