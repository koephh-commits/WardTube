// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

package com.mwmods.wardtube.web

import org.json.JSONObject
import org.json.JSONTokener

/**
 * A second, simpler way to find which video the three dots of a card belong to, used when [CardProbe] found nothing
 * (it did not, on the search results and on the watch history: nothing lit up there).
 *
 * [CardProbe] has to recognise the *card* (a structure that YouTube draws differently on each page) and the *button*
 * (what kind of element the three dots are). This one needs neither:
 *  - the only thing it reads about the page are the links to videos (`/watch?v=...`, `/shorts/...`), which every card has
 *    on its picture and its title, whatever else the card is made of. All the links of one video make one box (picture
 *    and title); a touch inside that box's height, or a little under it, is on that video's card, wherever on the row the
 *    dots are (right of the title, far from a short title, under the title);
 *  - on a Short the address says which video it is;
 *  - whether the touch really opened YouTube's menu is looked at afterwards ([MENU_STATE]): the menu is a sheet
 *    over the page, and a sheet that was not there when the finger went down and is there afterwards was opened by this
 *    touch. Only then (or when the touch was clearly on a small button inside the card) is the video offered.
 *
 * When the touch opened a menu and still no video was found, [Result.diag] says what the page is made of there.
 */
object TapProbe {
    class Result(
        /** The video found, or null. */
        val hit: CardProbe.Hit?,
        /** Which menus were open when the finger went down (bit 1: a sheet that is in sight, bit 2: a layer over the page). */
        val menuBefore: Int,
        /** The touch was on a small button right inside the video's box: no need to wait for the menu. */
        val strong: Boolean,
        /** The page is a Short. */
        val onShorts: Boolean,
        /** What the page looks like here, when no video was found. */
        val diag: String,
    )

    private val ID = Regex("[A-Za-z0-9_-]{11}")

    /** `function menuState()`: 0 when no menu is open, bit 1 for a sheet in sight, bit 2 for a layer over the page. ES5. */
    private const val MENU_STATE = """function menuState() {
  var s = 0;
  try {
    var vw = window.innerWidth, vh = window.innerHeight;
    var nodes = document.querySelectorAll('ytm-bottom-sheet-renderer, bottom-sheet-layout, ytm-menu-popup-renderer, yt-contextual-sheet-layout, yt-sheet-view-model, [role="dialog"], [role="menu"], ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, yt-list-item-view-model, [role="menuitem"]');
    for (var i = 0; i < nodes.length && !(s & 1); i++) {
      var r = nodes[i].getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) continue;
      var t = document.elementFromPoint(Math.min(Math.max(r.left + r.width / 2, 1), vw - 1), Math.min(Math.max(r.top + r.height / 2, 1), vh - 1));
      if (t && (t === nodes[i] || nodes[i].contains(t))) s |= 1;
    }
    var el = document.elementFromPoint(Math.round(vw / 2), 6);
    if (el && !el.closest('ytm-pivot-bar-renderer, ytm-mobile-topbar-renderer, yt-top-bar-view-model, header, nav, [role="tablist"], ytm-chip-cloud-renderer, video')) {
      for (var n = el, h = 0; n && n !== document.body && h < 8; n = n.parentElement, h++) {
        if (getComputedStyle(n).position !== 'fixed') continue;
        var nr = n.getBoundingClientRect();
        if (nr.width >= vw * 0.9 && nr.height >= vh * 0.5) { s |= 2; break; }
      }
    }
  } catch (e) { /* no menu is assumed */ }
  return s;
}"""

    /** A function expression `function (px, py)`: the touch in device pixels from the top left of the WebView. ES5. */
    private const val FUNCTION = """function (px, py) {
  try {
    var d = window.devicePixelRatio || 1;
    var x = px / d, y = py / d;
    var vw = window.innerWidth, vh = window.innerHeight;
function menuState() {
  var s = 0;
  try {
    var vw = window.innerWidth, vh = window.innerHeight;
    var nodes = document.querySelectorAll('ytm-bottom-sheet-renderer, bottom-sheet-layout, ytm-menu-popup-renderer, yt-contextual-sheet-layout, yt-sheet-view-model, [role="dialog"], [role="menu"], ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, yt-list-item-view-model, [role="menuitem"]');
    for (var i = 0; i < nodes.length && !(s & 1); i++) {
      var r = nodes[i].getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) continue;
      var t = document.elementFromPoint(Math.min(Math.max(r.left + r.width / 2, 1), vw - 1), Math.min(Math.max(r.top + r.height / 2, 1), vh - 1));
      if (t && (t === nodes[i] || nodes[i].contains(t))) s |= 1;
    }
    var el = document.elementFromPoint(Math.round(vw / 2), 6);
    if (el && !el.closest('ytm-pivot-bar-renderer, ytm-mobile-topbar-renderer, yt-top-bar-view-model, header, nav, [role="tablist"], ytm-chip-cloud-renderer, video')) {
      for (var n = el, h = 0; n && n !== document.body && h < 8; n = n.parentElement, h++) {
        if (getComputedStyle(n).position !== 'fixed') continue;
        var nr = n.getBoundingClientRect();
        if (nr.width >= vw * 0.9 && nr.height >= vh * 0.5) { s |= 2; break; }
      }
    }
  } catch (e) { /* no menu is assumed */ }
  return s;
}
    var HARD = '[role="dialog"], [role="menu"], [role="menuitem"], ytm-bottom-sheet-renderer, bottom-sheet-layout, ' +
      'ytm-menu-popup-renderer, yt-contextual-sheet-layout, yt-sheet-view-model, [data-wt-dl], [data-wt-dlb]';
    var SOFT = 'ytm-pivot-bar-renderer, ytm-mobile-topbar-renderer, yt-top-bar-view-model, header, nav, [role="tablist"], ' +
      'ytm-chip-cloud-renderer, video';
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
    function tag(e) { return e ? e.tagName.toLowerCase() : '?'; }
    function cls(e) { return (typeof e.className === 'string' ? e.className : '').slice(0, 40).replace(/\s+/g, '.'); }

    // 1. The state of YouTube's menus before this touch is a tap (a menu that appears afterwards is this tap's).
    var before = menuState();

    // 2. What is drawn under the finger: the first thing that is not a layer covering the page. A covering layer that is
    //    fixed to the screen (an open menu and its dimmed page) means: not a new menu here.
    var stack = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)];
    var el = null;
    for (var s = 0; s < stack.length && s < 8; s++) {
      var c = stack[s];
      if (!c || !c.getBoundingClientRect || !c.closest) continue;
      var cr = c.getBoundingClientRect();
      if (cr.width >= vw * 0.9 && cr.height >= vh * 0.5) {
        if (fixedUp(c)) return '';
        continue;
      }
      el = c;
      break;
    }
    if (!el) return '';
    if (el.closest(HARD) || el.closest(SOFT)) return '';
    var link = el.closest('a[href]');
    var press = el.closest(PRESS);
    if (link && !press) return ''; // a tap on a link opens the video: it is not a menu

    // A small box with a drawing in it (an icon) counts as a button too.
    var btn = press;
    if (!btn) {
      for (var n = el, h = 0; n && n !== document.body && h < 4; n = n.parentElement, h++) {
        var nr = n.getBoundingClientRect();
        if (nr.width > 72 || nr.height > 72) break;
        if (small(nr) && (n.querySelector('svg, img, yt-icon, c3-icon') || ['svg', 'img', 'yt-icon', 'c3-icon'].indexOf(tag(n)) >= 0)) {
          btn = n;
          break;
        }
      }
    }
    var smallBtn = !!(btn && small(btn.getBoundingClientRect()));
    var dots = btn ? dotsLike(btn) : false;
    var out = { ok: 1, m: before, r: 0, s: 0 };

    // 3. A Short has its own page: the address says which video.
    var sp = /^\/shorts\/([A-Za-z0-9_-]{11})/.exec(location.pathname);
    if (sp) {
      out.k = 'shorts';
      out.i = sp[1];
      out.t = '';
      out.s = 1;
      out.r = dots ? 1 : 0;
      return JSON.stringify(out);
    }

    // 4. Which card? Only the video links are used, wherever the page puts the dots: all the links of one video make
    //    one box (its picture and its title); the dots are inside that box's height or just under it.
    var all = document.querySelectorAll(LINKS), groups = [], byId = {}, nLinks = 0;
    for (var j = 0; j < all.length; j++) {
      var a = all[j], v = parse(a);
      if (!v || a.closest(HARD) || a.closest(SOFT)) continue;
      var r = a.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) continue;
      nLinks++;
      var g = byId[v.i];
      var len = clean(a.textContent).length;
      if (!g) {
        g = { v: v, l: r.left, t: r.top, r: r.right, b: r.bottom, a: a, n: len };
        byId[v.i] = g;
        groups.push(g);
      } else {
        g.l = Math.min(g.l, r.left);
        g.t = Math.min(g.t, r.top);
        g.r = Math.max(g.r, r.right);
        g.b = Math.max(g.b, r.bottom);
        if (len > g.n) { g.a = a; g.n = len; }
      }
    }
    var single = vw < 700;
    var best = null, bestCost = 1e9, bestDy = 0;
    for (var q = 0; q < groups.length; q++) {
      var gg = groups[q];
      if (y < gg.t - 6) continue; // above the card: not its dots
      var dy = y > gg.b ? y - gg.b : 0;
      var dx = x < gg.l ? gg.l - x : (x > gg.r ? x - gg.r : 0);
      if (dy > 90 || (!single && dx > 60)) continue;
      var cost = (dy > 6 ? dy : 0) * 3 + (single ? 0 : dx * 0.5);
      if (cost < bestCost) { bestCost = cost; best = gg; bestDy = dy; }
    }
    if (best) {
      var title = '';
      var up = best.a;
      for (var k = 0; k < 7 && up.parentElement && up.parentElement !== document.body && !title; k++) {
        up = up.parentElement;
        if (videoIn(up) === 'many') break;
        for (var ti = 0; ti < T.length && !title; ti++) {
          var te = up.querySelector(T[ti]);
          var ts = te ? clean(te.textContent) : '';
          if (ts.length > 1) title = ts.slice(0, 200);
        }
      }
      if (!title) title = clean(best.a.getAttribute('aria-label') || best.a.getAttribute('title') || best.a.textContent).slice(0, 200);
      out.k = best.v.k;
      out.i = best.v.i;
      out.t = title;
      out.r = (smallBtn && bestDy <= 6) ? 1 : 0;
      return JSON.stringify(out);
    }

    // 5. No video found: what the page looks like here, so it can be seen what it is made of.
    var txt = 'WardTube tap probe, no video found\n' + location.pathname + '  ' + vw + 'x' + vh + '\n' +
      'tap ' + Math.round(x) + ',' + Math.round(y) + ' on ' + tag(el) + '.' + cls(el) + '\n' +
      'button=' + (btn ? tag(btn) + ' ' + Math.round(btn.getBoundingClientRect().width) + 'x' + Math.round(btn.getBoundingClientRect().height) : 'none') +
      ' dots=' + dots + '\nvideo links=' + nLinks + ' videos=' + groups.length + '\n';
    for (var gi = 0; gi < groups.length && gi < 4; gi++) {
      var G = groups[gi];
      txt += '  ' + G.v.i + ' ' + Math.round(G.l) + ',' + Math.round(G.t) + ' to ' + Math.round(G.r) + ',' + Math.round(G.b) + '\n';
    }
    var hold = btn || el, line = 0;
    while (hold && hold !== document.documentElement && line < 9) {
      var hr = hold.getBoundingClientRect();
      var lv = videoIn(hold);
      txt += tag(hold) + (cls(hold) ? '.' + cls(hold) : '') + ' ' + Math.round(hr.width) + 'x' + Math.round(hr.height) +
        (lv === 'many' ? ' links=many' : lv ? ' links=1' : ' links=0') + '\n';
      hold = hold.parentElement;
      line++;
    }
    out.d = txt.slice(0, 1800);
    return JSON.stringify(out);
  } catch (err) {
    return '';
  }
}"""

    fun script(x: Float, y: Float): String = "($FUNCTION)(${x.toInt()},${y.toInt()})"

    /** The script that says which menus are open now (a number, see [MENU_STATE]). */
    fun menuScript(): String = "(function(){ $MENU_STATE\n return menuState(); })()"

    /** [raw] is what `evaluateJavascript` hands back for [menuScript]. */
    fun menuBits(raw: String?): Int = raw?.trim()?.toIntOrNull() ?: 0

    /** [raw] is what `evaluateJavascript` hands back for [script]: null for a touch that is not on a card's area. */
    fun parse(raw: String?): Result? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optInt("ok") != 1) return null
        val id = o.optString("i")
        val hit = if (ID.matches(id)) {
            val url = if (o.optString("k") == "shorts") {
                "https://m.youtube.com/shorts/$id"
            } else {
                "https://m.youtube.com/watch?v=$id"
            }
            CardProbe.Hit(url, o.optString("t").trim().take(200).takeIf { it.isNotEmpty() })
        } else {
            null
        }
        return Result(hit, o.optInt("m"), o.optInt("r") == 1, o.optInt("s") == 1, o.optString("d"))
    }
}
