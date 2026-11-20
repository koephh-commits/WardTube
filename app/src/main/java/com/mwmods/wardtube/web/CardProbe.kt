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
 * Nothing here depends on how YouTube builds a card (it builds Home, Search and Watch history differently, and changes
 * them). The answer is a card's video, or nothing:
 *  - the touch is on a small pressable (a button of any kind, or any small box with a drawing in it: an icon) that is not
 *    in a menu, a dialog, a bar of the page, a chip bar or the player; looked for under the top of what is drawn too, so
 *    a transparent layer over the dots does not hide them; a layer that covers the page (an open menu) means "nothing";
 *    a pressable inside a link is left alone unless it is the three dots;
 *  - the card is found by structure first (going up from it, the first box that links a video links exactly one), and
 *    when that does not work (the dots are not inside the card, or a box above them links several videos) by position:
 *    the video whose links are nearest to the finger. Every card, whatever its layout, has its picture linked.
 *
 * When the touch looks like the three dots and still no video is found, the answer is a short description of what is
 * there ([diagnostic]) instead, which the app offers to copy so it can be seen what the page is made of.
 */
object CardProbe {
    class Hit(val url: String, val title: String?)

    private val ID = Regex("[A-Za-z0-9_-]{11}")

    /**
     * A function expression `function (px, py)`: (px, py) is the touch in device pixels, from the top left of the WebView.
     * Returns a JSON text `{"k": "video" | "shorts", "i": id, "t": title}`, `{"x": description}` for three dots with no
     * video found (see [diagnostic]), or an empty text. ES5 only.
     */
    private const val FUNCTION = """function (px, py) {
  try {
    var d = window.devicePixelRatio || 1;
    var x = px / d, y = py / d;
    var vw = window.innerWidth, vh = window.innerHeight;
    var NO = '[role="dialog"], [role="menu"], [role="menuitem"], ytm-bottom-sheet-renderer, bottom-sheet-layout, ' +
      'ytm-menu-popup-renderer, yt-contextual-sheet-layout, yt-sheet-view-model, ytm-pivot-bar-renderer, ' +
      'ytm-mobile-topbar-renderer, header, nav, [role="tablist"], ytm-chip-cloud-renderer, video, [data-wt-dl]';
    var PRESS = 'button, [role="button"], yt-icon-button, button-view-model, yt-button-shape, ytm-menu, ytm-menu-renderer';
    var LINKS = 'a[href*="/watch?"], a[href*="/shorts/"], img[src*="ytimg.com/vi"], img[data-src*="ytimg.com/vi"], img[src*="ytimg.com/an_webp"]';
    var MORE = /more|menu|action|option|المزيد|إجراء|إجراءات|خيارات|القائمة|plus|más|mehr|altro|もっと|更多|더보기|mais|еще|daha/i;
    var T = ['h3', 'h4', '[role="heading"]', '.media-item-headline', '[class*="title" i]'];

    function parse(a) {
      if (a.tagName === 'IMG') { // the picture of a card: its address holds the video's id (no <a> link needed)
        var tm = /ytimg\.com\/(?:vi|vi_webp|an_webp)\/([A-Za-z0-9_-]{11})\//.exec(a.getAttribute('src') || a.getAttribute('data-src') || '');
        return tm ? { k: 'video', i: tm[1] } : null;
      }

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
      if (!first) { // no link and no picture: the data YouTube keeps on the card's element
        try {
          var dd = root.data || null;
          var vid = dd && (dd.videoId || (dd.navigationEndpoint && dd.navigationEndpoint.watchEndpoint && dd.navigationEndpoint.watchEndpoint.videoId) ||
            (dd.onTap && dd.onTap.innertubeCommand && dd.onTap.innertubeCommand.watchEndpoint && dd.onTap.innertubeCommand.watchEndpoint.videoId));
          if (vid && /^[A-Za-z0-9_-]{11}$/.test(vid)) first = { k: 'video', i: vid };
        } catch (e) { /* no data */ }
      }
      return first;
    }
    function small(r) { return r.width >= 12 && r.height >= 12 && r.width <= 72 && r.height <= 72; }
    function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
    function fixedUp(el) {
      for (var n = el, h = 0; n && n !== document.body && h < 8; n = n.parentElement, h++) {
        if (getComputedStyle(n).position === 'fixed') return true;
      }
      return false;
    }
    // Three little dots: inside YouTube's menu element, a label that says "more / menu / actions", or a drawing of dots.
    function dotsLike(b) {
      if (b.closest('ytm-menu, ytm-menu-renderer')) return true;
      var l = b.getAttribute('aria-label') || b.getAttribute('title') || '';
      if (l && MORE.test(l)) return true;
      var svg = b.querySelector('svg');
      if (!svg) return false;
      if (svg.querySelectorAll('circle').length >= 3) return true;
      var ps = svg.querySelectorAll('path'), mv = 0, cl = 0, len = 0;
      for (var i = 0; i < ps.length; i++) {
        var dd = ps[i].getAttribute('d') || '';
        len += dd.length;
        mv += (dd.match(/[Mm]/g) || []).length;
        cl += (dd.match(/[Zz]/g) || []).length;
      }
      return mv === 3 && cl === 3 && len < 600;
    }

    // 1. What is under the finger: the top of what is drawn there, and a little below it (a transparent layer over the
    //    dots must not hide them). A layer that covers the page (a menu and its dimmed page) means: nothing here.
    var stack = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)];
    var btn = null, tip = null;
    for (var e = 0; e < stack.length && e < 4 && !btn; e++) {
      var el = stack[e];
      if (!el || !el.closest) continue;
      if (el.closest(NO)) return '';
      var er = el.getBoundingClientRect();
      if (er.width >= vw * 0.9 && er.height >= vh * 0.5 && fixedUp(el)) return '';
      if (!tip) tip = el;
      var b = el.closest(PRESS);
      if (b && small(b.getBoundingClientRect())) { btn = b; break; }
      // Not a button of a kind this knows: a small box with a drawing in it (an icon) counts too.
      for (var n = el, h = 0; n && n !== document.body && h < 4; n = n.parentElement, h++) {
        var nr = n.getBoundingClientRect();
        if (nr.width > 72 || nr.height > 72) break;
        if (small(nr) && (n.querySelector('svg, img, yt-icon, c3-icon') || ['svg', 'img', 'yt-icon', 'c3-icon'].indexOf(n.tagName.toLowerCase()) >= 0)) {
          btn = n;
          break;
        }
      }
    }
    if (!btn) return '';
    var dots = dotsLike(btn);
    if (btn.closest('a[href]') && !dots) return '';

    // 2. Which card? First by structure: going up from the button, the first box that links a video links exactly one.
    var card = null, vid = null;
    var up = btn.parentElement, hops = 0;
    while (up && up !== document.body && hops < 16) {
      var q = videoIn(up);
      if (q === 'many') break;
      if (q) { card = up; vid = q; break; }
      up = up.parentElement;
      hops++;
    }

    // 3. Else by position: the video whose links are nearest to the finger (the picture and the title of a card are
    //    links wherever the page puts the dots). A link under the finger's line counts for more than one beside it,
    //    one further down belongs to the next card.
    var title = '';
    if (!vid) {
      var all = document.querySelectorAll(LINKS), best = null, bestD = 1e9;
      for (var j = 0; j < all.length; j++) {
        var v = parse(all[j]);
        if (!v || all[j].closest(NO)) continue;
        var r = all[j].getBoundingClientRect();
        if (r.width < 8 || r.height < 8) continue;
        var dx = x < r.left ? r.left - x : (x > r.right ? x - r.right : 0);
        var dy = y < r.top ? r.top - y : (y > r.bottom ? y - r.bottom : 0);
        var cost = dx * 0.6 + dy * 2 + (y < r.top ? 25 : 0);
        if (cost < bestD) { bestD = cost; best = { v: v, a: all[j] }; }
      }
      if (best && bestD <= (dots ? 110 : 40)) {
        vid = best.v;
        var c = best.a;
        for (var k = 0; k < 6 && c.parentElement && c.parentElement !== document.body; k++) {
          if (videoIn(c.parentElement) === 'many') break;
          c = c.parentElement;
          if (!title) {
            for (var a = 0; a < T.length && !title; a++) {
              var te = c.querySelector(T[a]);
              var ts = te ? clean(te.textContent) : '';
              if (ts.length > 1) title = ts.slice(0, 200);
            }
          }
        }
        card = c;
        if (!title) title = clean(best.a.getAttribute('aria-label') || best.a.getAttribute('title') || best.a.textContent).slice(0, 200);
      }
    }

    if (!vid) {
      if (!dots) return '';
      // Nothing found for what looks like the three dots: say what is there, so it can be seen what the page is made of.
      var out = 'WardTube card probe, no card found\n' + location.pathname + '  ' + vw + 'x' + vh + '  dir=' + (document.dir || getComputedStyle(document.documentElement).direction) + '\n';
      var hold = btn, line = 0;
      while (hold && hold !== document.documentElement && line < 9) {
        var hr = hold.getBoundingClientRect();
        var cls = (typeof hold.className === 'string' ? hold.className : '').slice(0, 50);
        var lv = hold.querySelectorAll ? videoIn(hold) : null;
        out += hold.tagName.toLowerCase() + (cls ? '.' + cls.replace(/\s+/g, '.') : '') + ' ' + Math.round(hr.width) + 'x' + Math.round(hr.height) +
          (lv === 'many' ? ' links=many' : lv ? ' links=1' : ' links=0') + '\n';
        hold = hold.parentElement;
        line++;
      }
      var some = document.querySelectorAll(LINKS), shown = 0;
      out += 'video links on page: ' + some.length + '\n';
      for (var s = 0; s < some.length && shown < 4; s++) {
        var sr = some[s].getBoundingClientRect();
        if (sr.bottom < y - 200 || sr.top > y + 200) continue;
        out += '  ' + some[s].tagName.toLowerCase() + ' ' + Math.round(sr.left) + ',' + Math.round(sr.top) + ' ' + Math.round(sr.width) + 'x' + Math.round(sr.height) + ' ' + (some[s].getAttribute('href') || '').slice(0, 40) + '\n';
        shown++;
      }
      out += 'tap ' + Math.round(x) + ',' + Math.round(y) + ' on ' + (tip ? tip.tagName.toLowerCase() : '?') + '\n';
      return JSON.stringify({ x: out.slice(0, 1800) });
    }

    if (!title && card) {
      for (var a2 = 0; a2 < T.length && !title; a2++) {
        var e2 = card.querySelector(T[a2]);
        var s2 = e2 ? clean(e2.textContent) : '';
        if (s2.length > 1) title = s2.slice(0, 200);
      }
      if (!title) {
        var l = card.querySelector(LINKS);
        if (l) title = clean(l.getAttribute('aria-label') || l.getAttribute('title')).slice(0, 200);
      }
    }
    return JSON.stringify({ k: vid.k, i: vid.i, t: title });
  } catch (err) {
    return '';
  }
}"""

    /** The script to evaluate in the page for a touch at ([x], [y]) device pixels from the top left of the WebView. */
    fun script(x: Float, y: Float): String = "($FUNCTION)(${x.toInt()},${y.toInt()})"

    /** The description of the page that the probe gives for three dots it could not find a video for, or null. */
    fun diagnostic(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return o.optString("x").takeIf { it.isNotEmpty() }
    }

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
