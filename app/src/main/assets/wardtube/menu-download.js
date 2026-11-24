// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods
//
// "Download video" row for the menu YouTube opens from the three dots beside a video.
// Runs on every YouTube page of the browsing WebView and of the player WebView: PageScripts.kt puts it after wardtube.js in
// the same script (so it also reaches WebViews without document-start scripts), and it asks the app through the page's
// WardTubeNative bridge (NativeBridge.menuDownload / menuDownloadCurrent).
// It never changes YouTube's own rows: it adds one row of its own, built by hand, and never uses innerHTML
// (YouTube's Trusted Types rules refuse it).
//
// On a card (the Home feed, search results, channels ...): after a tap on a button inside the card, the row goes into the
// menu that opens, in this order:
//   1. a row of YouTube's menu that is really in sight (what is drawn at its centre is the row itself: a menu that is
//      kept in the page hidden, transparent or off the screen is never used), the top one: the row goes before it;
//   2. no such row (a menu whose rows have names this script does not know): the rows are found by how they are drawn
//      (wide boxes with text, one above the other, in a layer fixed to the screen) and the row goes before the top one;
//   3. still none, or the row cannot be seen where it was put: nothing more is done here (since 2.6.172 there is no floating
//      button over the menu any more; the long press on the video is the other way into a download).
//   (A tap is noted on `pointerup` as well as on `click`: a page that opens its menu from the touch and cancels the click
//   would never have been seen.)
//
// A tap inside the comments (of a video, a Short or a post: the list, a comment, the box that writes one) never asks for the row, and
// neither does a touch that the app itself made to open a post's comments (`__wtNoDl`): the menu a comment opens (reply, like,
// dislike, report) is not a video's menu.
//
// On a Short (the vertical player at /shorts/ID) only the three dots of the Short count: a tap on any other button of it (like,
// dislike, comments, share, subscribe ...) starts nothing, so the row never shows over the Short or in the comments sheet. The
// video is the one the address names (it follows the swipes).
//
// On a watch page (the video's own row of buttons: like, dislike, share ... and the three dots) it works in this order:
//   1. the three dots of that row are found: the row is added to the menu they open;
//   2. no three dots in the row (or the menu could not take the row): a "Download" button is added to the row itself,
//      styled like the buttons beside it;
//   3. neither can be built (no bridge, no row found, any error, nothing visible): nothing is shown at all, and the
//      download button of the app's bottom bar is the way to download.
(function () {
  'use strict';
  if (window.top !== window || window.__wtMenuDl) return;
  window.__wtMenuDl = true;

  var WINDOW_MS = 6000; // how long the menu is waited for after a tap on a button inside a video card
  var SYNC_MS = 500;    // how often, at most, the row of buttons of a watch page is looked at again
  var SCAN_GAP_MS = 80;     // how often, at most, the page is looked at for the menu (the page changes all the time)
  var DRAWN_AFTER_MS = 400; // the menu is looked for by how it is drawn only if its names were not known by then
  var TAP_MS = 700;         // a touch that lasts longer, or moves further than TAP_PX, is not a tap on a button
  var TAP_PX = 12;
  var CLIP_MS = 500;       // a row that was added and is still not in sight after this long is taken out

  // Links that name a video, inside a card.
  var LINKS = 'a[href*="/watch?v="], a[href*="/shorts/"], img[src*="ytimg.com/vi"], img[data-src*="ytimg.com/vi"], img[src*="ytimg.com/an_webp"]';
  // The cards YouTube draws a video in (mobile page, old and new layouts).
  var CARDS = 'ytm-rich-item-renderer, ytm-media-item, ytm-video-with-context-renderer, ytm-compact-video-renderer, ' +
    'ytm-playlist-video-renderer, ytm-grid-video-renderer, ytm-reel-item-renderer, ytm-shorts-lockup-view-model, ' +
    'ytm-shorts-lockup-view-model-v2, yt-lockup-view-model';
  // The rows of the menu (bottom sheet) and the sheet itself.
  var ITEMS = 'ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, yt-list-item-view-model, [role="menuitem"]';
  var SHEETS = 'ytm-bottom-sheet-renderer, bottom-sheet-layout, ytm-menu-popup-renderer, yt-contextual-sheet-layout, ' +
    'yt-sheet-view-model, [role="dialog"], [role="menu"]';
  // Where a card keeps its title, best first.
  var TITLES = ['h3', 'h4', '[role="heading"]', '.media-item-headline', '[class*="title" i]'];
  // The row of buttons under the video of a watch page: where it is looked for. The like button is its landmark
  // (`aria-pressed` is on the like and dislike buttons whatever the language is).
  var ACTION_ANCHORS = 'ytm-segmented-like-dislike-button-renderer, segmented-like-dislike-button-view-model, ' +
    'like-button-view-model, ytm-like-button-renderer, ytm-slim-video-action-bar-renderer, ' +
    'yt-flexible-actions-view-model, button[aria-pressed]';
  // The comments panel of a video or a Short: a sheet that is never the three-dots menu, whatever its rows look like.
  var COMMENTS = 'ytm-engagement-panel, ytm-engagement-panel-section-list-renderer, ytm-comments-header-renderer, ' +
    'ytm-comment-section-renderer, ytm-comment-thread-renderer, ytm-comment-renderer, ytm-comments-entry-point-header-renderer, ' +
    '[target-id*="comment" i], [panel-identifier*="comment" i]';
  // What is never the menu: the cards, the bars of the page and its player (and the script's own row and button).
  var NOT_MENU = CARDS + ', ytm-pivot-bar-renderer, ytm-mobile-topbar-renderer, header, nav, [role="tablist"], ' +
    'ytm-chip-cloud-renderer, video, [data-wt-dl], [data-wt-dlb], ' + COMMENTS;
  var PRESSABLE = 'button, [role="button"]';
  // NouTube's way: a click anywhere inside a video's card is the start of a possible menu; the menu is waited for, and
  // only when one opens is the video of that card offered (a tap on the picture just opens the video: nothing happens).
  var NOU_CARDS = 'ytm-media-item, ytm-compact-video-renderer, ytm-video-card-renderer, ytm-playlist-video-renderer, ' +
    'ytm-video-with-context-renderer, ytm-rich-item-renderer, yt-lockup-view-model, yt-lockup-metadata-view-model, ' +
    'ytd-video-renderer, ytd-grid-video-renderer';
  // What the label of the three dots can say (read only inside the row of buttons of a watch page).
  var MORE_RE = /more|مزيد|إضافي|أخرى|plus|más|mehr|altro|もっと|更多|더보기|mais|еще|daha/i;

  // { v: { kind, id }, title, at, cur }: the video of the last tapped button (inside a card, or the three dots of a
  // watch page: then `cur` is true).
  var last = null;
  var poll = 0;
  var inserted = false;   // the row went into the menu that the last tap opened
  var clipped = false;    // the row was added to the menu and could not be seen there: it is not added again
  var insertedAt = 0;
  var scanAt = 0;
  var fallbackOn = false; // a menu could not take the row: the watch page gets its own button
  var hooked = null;      // the three dots of the watch page that the menu row is waited for from
  var syncTimer = 0;

  function bridge() {
    return window.WardTubeNative || null;
  }

  // ---- Which video is this card? ----

  function parse(a) {
      if (a.tagName === 'IMG') { // the picture of a card: its address holds the video's id (no <a> link needed)
        var tm = /ytimg\.com\/(?:vi|vi_webp|an_webp)\/([A-Za-z0-9_-]{11})\//.exec(a.getAttribute('src') || a.getAttribute('data-src') || '');
        return tm ? { kind: 'video', id: tm[1] } : null;
      }

    var h = a.getAttribute('href') || '';
    var m = /[?&]v=([A-Za-z0-9_-]{11})/.exec(h);
    if (m && h.indexOf('/watch') !== -1) return { kind: 'video', id: m[1] };
    m = /\/shorts\/([A-Za-z0-9_-]{11})/.exec(h);
    if (m) return { kind: 'shorts', id: m[1] };
    return null;
  }

  // The one video linked inside root; 'many' when it links different videos (then root is not a single card).
  function videoIn(root) {
    var links = root.querySelectorAll(LINKS), first = null;
    for (var i = 0; i < links.length; i++) {
      var v = parse(links[i]);
      if (!v) continue;
      if (!first) first = v;
      else if (v.id !== first.id) return 'many';
    }
    if (!first) { // no link and no picture: the data YouTube keeps on the card's element
        try {
          var dd = root.data || null;
          var vid = dd && (dd.videoId || (dd.navigationEndpoint && dd.navigationEndpoint.watchEndpoint && dd.navigationEndpoint.watchEndpoint.videoId) ||
            (dd.onTap && dd.onTap.innertubeCommand && dd.onTap.innertubeCommand.watchEndpoint && dd.onTap.innertubeCommand.watchEndpoint.videoId));
          if (vid && /^[A-Za-z0-9_-]{11}$/.test(vid)) first = { kind: 'video', id: vid };
        } catch (e) { /* no data */ }
      }
    return first;
  }

  // [strict]: only the cards YouTube is known to draw (on a watch page, where a guess could name another video).
  function cardOf(from, strict) {
    var known = from.closest(CARDS);
    if (known) {
      var kv = videoIn(known);
      return kv && kv !== 'many' ? { v: kv, el: known } : null; // a card without a video (an ad) is not guessed at
    }
    if (strict) return null;
    // A layout this script does not know: the smallest ancestor that links exactly one video.
    var n = from.parentElement, hops = 0;
    while (n && n !== document.body && hops < 14) {
      var v = videoIn(n);
      if (v === 'many') return null;
      if (v) return { v: v, el: n };
      n = n.parentElement;
      hops++;
    }
    return null;
  }

  // The card a click is inside, by NouTube's list of cards; a card that holds no link itself (the metadata part of a
  // lockup) is looked for again in the card around it.
  function cardNou(t) {
    var el = t.closest(NOU_CARDS), hops = 0;
    while (el && hops < 4) {
      var v = videoIn(el);
      if (v === 'many') return null;
      if (v) return { v: v, el: el };
      el = el.parentElement ? el.parentElement.closest(NOU_CARDS) : null;
      hops++;
    }
    return null;
  }

  function titleOf(el) {
    for (var i = 0; i < TITLES.length; i++) {
      var t = el.querySelector(TITLES[i]);
      var s = t ? (t.textContent || '').replace(/\s+/g, ' ').trim() : '';
      if (s.length > 1) return s.slice(0, 200);
    }
    return '';
  }

  // ---- Shorts ----

  // True on the Shorts player (/shorts/ID), which swipes from one Short to the next.
  function onShorts() {
    try {
      return /^\/shorts(?:\/|$)/.test(location.pathname);
    } catch (x) {
      return false;
    }
  }

  // The Short on the screen: the address says which (it follows the swipes). null when it names none.
  function shortsVideo() {
    try {
      var m = /^\/shorts\/([A-Za-z0-9_-]{11})(?:\/|$)/.exec(location.pathname);
      return m ? { kind: 'shorts', id: m[1] } : null;
    } catch (x) {
      return null;
    }
  }

  // What the buttons of a Short that are not its three dots are called (the like, comments, share ... column).
  var SHORTS_ACTION_RE = /like|comment|share|subscribe|أعجب|إعجاب|تعليق|مشاركة|اشتراك|اشترك/i;

  // True when `btn` is the three dots of a Short. Its own menu element or label, or its drawing (three little shapes), say so;
  // the like / dislike / comments / share / subscribe buttons never do.
  function isShortsDots(btn) {
    try {
      if (!btn) return false;
      if (btn.closest('ytm-menu, ytm-menu-renderer')) return true;
      if (btn.closest('[aria-pressed]')) return false;
      var label = (btn.getAttribute('aria-label') || btn.getAttribute('title') || '');
      if (label && label.length < 60 && SHORTS_ACTION_RE.test(label)) return false;
      if (label && label.length < 60 && MORE_RE.test(label)) return true;
      return looksLikeDots(btn);
    } catch (x) {
      return false;
    }
  }

  // A tap on a Short. Only the three dots start the wait for a menu; any other button (like, comments, share ...) ends it and
  // takes away a row that is still there, so nothing is ever added over the Short or to the comments sheet.
  function noteShortsTap(t, e, now) {
    var btn = t.closest(BUTTONS);
    var vid = null;
    if (btn && isShortsDots(btn)) {
      vid = shortsVideo();
      if (!vid) {
        var c = cardFromPath(e);
        vid = c ? c.v : null;
      }
    }
    if (vid && last && last.dots && last.v.id === vid.id && now - last.at < SAME_TAP_MS) return;
    clearRows();
    if (vid) {
      last = { v: vid, title: '', at: now, dots: true };
      startPolling();
    } else {
      last = null;
    }
  }

  // The video of a watch page: the address says which (a Short has its own page and is not handled here).
  function watchVideo() {
    try {
      if (location.pathname !== '/watch') return null;
      var m = /[?&]v=([A-Za-z0-9_-]{11})(?:&|$)/.exec(location.search);
      return m ? { kind: 'video', id: m[1] } : null;
    } catch (x) {
      return null;
    }
  }

  // ---- The menu ----

  function active() {
    return last !== null && Date.now() - last.at < WINDOW_MS;
  }

  function shown(el) {
    if (el.getClientRects().length) return true;
    var c = el.firstElementChild; // a row drawn with display: contents has no box of its own
    return !!(c && c.getClientRects().length);
  }

  // A row can be a custom element wrapped in another one: take the outer one.
  function outer(item) {
    var p = item.parentElement;
    while (p && p.matches(ITEMS)) {
      item = p;
      p = p.parentElement;
    }
    return item;
  }

  function clearRows() {
    var rows = document.querySelectorAll('[data-wt-dl]');
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].parentNode) rows[i].parentNode.removeChild(rows[i]);
    }
  }

  function firstText(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      if (n.nodeValue && n.nodeValue.trim().length > 0 && n.parentElement) return n;
    }
    return null;
  }

  function clamp(x, lo, hi, dflt) {
    return x >= lo && x <= hi ? Math.round(x) : dflt;
  }

  function icon(size) {
    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('width', String(size));
    svg.setAttribute('height', String(size));
    svg.setAttribute('fill', 'currentColor');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    svg.style.flex = 'none';
    var path = document.createElementNS(NS, 'path');
    path.setAttribute('d', 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z');
    svg.appendChild(path);
    return svg;
  }

  function isArabic() {
    return /^ar/i.test(document.documentElement.getAttribute('lang') || navigator.language || '');
  }

  // A plain row that looks like the first row of the menu (text style, height, spacing). A copy of YouTube's own row
  // is not used: its custom element would start without the data it draws itself from.
  function build(ref, info) {
    var cs = getComputedStyle(ref);
    var rtl = cs.direction === 'rtl';
    var node = firstText(ref);
    var textEl = node ? node.parentElement : ref;
    var ts = getComputedStyle(textEl);
    var rr = ref.getBoundingClientRect();
    var ic = ref.querySelector('svg') || ref.querySelector('img, yt-icon, c3-icon, [class*="icon" i]');
    var ir = ic ? ic.getBoundingClientRect() : null;
    var tr = null;
    if (node) {
      var rg = document.createRange();
      rg.selectNodeContents(node);
      tr = rg.getBoundingClientRect();
    }
    var size = ir ? clamp(ir.width, 16, 40, 24) : 24;
    var pad = ir ? clamp(rtl ? rr.right - ir.right : ir.left - rr.left, 8, 48, 16) : 16;
    var gap = ir && tr ? clamp(rtl ? ir.left - tr.right : tr.left - ir.right, 8, 40, 16) : 16;
    var height = clamp(rr.height, 40, 72, 48);

    var row = document.createElement('div');
    row.setAttribute('data-wt-dl', '1');
    row.setAttribute('role', 'menuitem');
    row.setAttribute('tabindex', '0');
    var s = row.style;
    s.display = 'flex';
    s.alignItems = 'center';
    s.boxSizing = 'border-box';
    s.width = '100%';
    s.minHeight = height + 'px';
    s.paddingInlineStart = pad + 'px';
    s.paddingInlineEnd = pad + 'px';
    s.columnGap = gap + 'px';
    s.direction = cs.direction;
    s.color = ts.color;
    s.fontFamily = ts.fontFamily;
    s.fontSize = ts.fontSize;
    s.fontWeight = ts.fontWeight;
    s.lineHeight = ts.lineHeight;
    s.letterSpacing = ts.letterSpacing;
    s.cursor = 'pointer';
    s.userSelect = 'none';
    s.webkitUserSelect = 'none';
    s.webkitTapHighlightColor = 'transparent';

    var tx = document.createElement('span');
    tx.textContent = isArabic() ? 'تنزيل الفيديو' : 'Download video';
    tx.style.flex = '1 1 auto';
    row.appendChild(icon(size));
    row.appendChild(tx);

    row.addEventListener('pointerdown', function () {
      row.style.backgroundColor = 'rgba(128, 128, 128, 0.25)';
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (name) {
      row.addEventListener(name, function () {
        row.style.backgroundColor = '';
      });
    });
    row.__wt = info;
    return row;
  }

  // ---- Is it really on the screen? ----
  // YouTube keeps menus in the page that are not open (hidden with visibility or opacity, or moved off the screen).
  // They have a box, so `getClientRects` says they are "shown", and they come first in the page: a row added to one
  // of them is never seen. What is drawn on the screen is looked at instead.

  // The box of an element; a row drawn with display: contents has none of its own: its first child's is used.
  function box(el) {
    var r = el.getBoundingClientRect();
    if (!r.width && !r.height && el.firstElementChild) r = el.firstElementChild.getBoundingClientRect();
    return r;
  }

  function byTop(a, b) {
    return box(a).top - box(b).top;
  }

  // True when the element is inside the window and on top: what is drawn at its centre is the element or inside it.
  function seen(el) {
    try {
      var r = box(el), vw = window.innerWidth, vh = window.innerHeight;
      if (r.width < 4 || r.height < 4 || r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) return false;
      var t = document.elementFromPoint(Math.min(Math.max(r.left + r.width / 2, 1), vw - 1),
        Math.min(Math.max(r.top + r.height / 2, 1), vh - 1));
      return !!t && t !== document.body && t !== document.documentElement && (el === t || el.contains(t));
    } catch (x) {
      return false;
    }
  }

  // The rows of YouTube's menu that are in sight, the top one first: those inside a sheet this script knows, else any.
  function menuRows() {
    var nodes = document.querySelectorAll(ITEMS), inSheet = [], other = [];
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].closest('[data-wt-dl]')) continue;
      var item = outer(nodes[i]);
      if (!item.parentNode || inSheet.indexOf(item) >= 0 || other.indexOf(item) >= 0 || item.closest(COMMENTS) || !seen(item)) continue;
      (item.closest(SHEETS) ? inSheet : other).push(item);
    }
    return (inSheet.length ? inSheet : other).sort(byTop);
  }

  // ---- A menu whose rows this script has no name for: it is found by how it is drawn ----
  // A menu is drawn over the page, in a layer fixed to the screen, and its rows are wide boxes with text, one line
  // or two tall, one above the other.

  function hasText(el) {
    var t = (el.textContent || '').replace(/\s+/g, '');
    return t.length > 0 && t.length < 160;
  }

  function onFixedLayer(el) {
    for (var n = el; n && n !== document.body; n = n.parentElement) {
      if (getComputedStyle(n).position === 'fixed') return true;
    }
    return false;
  }

  // The row of a menu drawn at `el`: the smallest box around it that is wide, not too tall and has text. A wrapper that
  // holds nothing but the row, at the same size, is part of the row.
  function rowAround(el, vw) {
    if (el.closest(NOT_MENU)) return null;
    for (var n = el, hops = 0; n && n !== document.body && hops < 10; n = n.parentElement, hops++) {
      var r = n.getBoundingClientRect();
      if (r.height > 110) return null; // taller than any row: the menu itself, the dimmed page or the page
      if (r.height < 36 || r.width < vw * 0.5 || !hasText(n)) continue;
      if (!onFixedLayer(n)) return null;
      while (n.parentElement && n.parentElement !== document.body) {
        var p = n.parentElement, pr = p.getBoundingClientRect();
        if (p.children.length !== 1 || Math.abs(pr.height - r.height) > 2 || Math.abs(pr.width - r.width) > 2) break;
        n = p;
      }
      return n;
    }
    return null;
  }

  // The rows of an open menu found by looking down its middle, the top one first.
  function drawnRows() {
    var vw = window.innerWidth, vh = window.innerHeight, rows = [];
    for (var y = vh - 8; y > vh * 0.3; y -= 12) {
      var el = document.elementFromPoint(Math.round(vw / 2), Math.round(y));
      var row = el ? rowAround(el, vw) : null;
      if (row && rows.indexOf(row) < 0) rows.push(row);
    }
    return rows.sort(byTop);
  }

  // ---- Is a menu open? ----

  // True while a layer that covers the page is open (a menu and the dimmed page behind it): what is drawn at the top
  // of the window, above any menu, is inside a fixed layer about as big as the window, and is not a bar of the page.
  function overlayUp() {
    try {
      var vw = window.innerWidth, vh = window.innerHeight;
      var el = document.elementFromPoint(Math.round(vw / 2), 6);
      if (!el || el.closest(NOT_MENU)) return false;
      for (var n = el, hops = 0; n && n !== document.body && hops < 8; n = n.parentElement, hops++) {
        if (getComputedStyle(n).position !== 'fixed') continue;
        var r = n.getBoundingClientRect();
        if (r.width >= vw * 0.9 && r.height >= vh * 0.5) return true;
      }
    } catch (x) { /* no layer is assumed */ }
    return false;
  }

  // ---- Putting the row in the menu ----

  function scan() {
    if (!active() || !bridge()) return;
    if (onShorts() && !last.dots) return; // on a Short only the three dots ask for the row
    var now = Date.now();
    if (now - scanAt < SCAN_GAP_MS) return;
    scanAt = now;
    var mine = document.querySelector('[data-wt-dl="1"]');
    if (mine) {
      if (mine.isConnected && seen(mine)) return; // already added to this menu, and in sight
      if (now - insertedAt < CLIP_MS) return; // the menu may still be sliding in
      // Not in sight: the menu was closed, or the row went where it cannot be seen. It is taken out and not put back.
      if (mine.parentNode) mine.parentNode.removeChild(mine);
      inserted = false;
      clipped = true;
    }
    var rows = [];
    if (!clipped) {
      rows = menuRows();
      if (!rows.length && now - last.at > DRAWN_AFTER_MS) rows = drawnRows();
    }
    if (rows.length) {
      var item = rows[0];
      var info = { kind: last.v.kind, id: last.v.id, title: last.title, cur: !!last.cur };
      var row = null;
      try {
        row = build(item, info);
        item.parentNode.insertBefore(row, item);
      } catch (x) {
        if (row && row.parentNode) row.parentNode.removeChild(row); // nothing half-built is left in the menu
        return;
      }
      inserted = true;
      insertedAt = now;
      return;
    }
    // (no row in the menu: nothing more is done for a card; the long press on the video is the other way; the watch page has
    // a button of its own for this: see the end of startPolling)
  }

  function startPolling() {
    clearInterval(poll);
    inserted = false;
    clipped = false;
    insertedAt = 0;
    scanAt = 0;
    var runs = 0;
    poll = setInterval(function () {
      if (!active() || ++runs > 40) {
        clearInterval(poll);
        // A tap on the three dots of a watch page whose menu took no row: the page gets a button of its own.
        if (last && last.cur && !inserted && !document.querySelector('[data-wt-dl]')) {
          fallbackOn = true;
          scheduleSync();
        }
        return;
      }
      scan();
    }, 150);
  }

  // Closes YouTube's menu after the row was tapped, the way a finger would: a tap on the dimmed page above the menu
  // (whatever is on top there, unless it is a link, a button or part of the menu itself), then Escape.
  function closeSheet(from) {
    try {
      var root = from ? from.closest(SHEETS) : null;
      var el = document.elementFromPoint(Math.round(window.innerWidth / 2), 8);
      if (el && !(from && from.contains(el)) && !(root && root.contains(el)) &&
          !el.closest('a, button, input, [role="button"], [role="menuitem"]')) {
        el.click();
      }
    } catch (x) { /* Escape below */ }
    try {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
    } catch (x) { /* nothing more to try */ }
  }

  function send(info) {
    try {
      var api = bridge();
      if (!api) return;
      if (info.cur && typeof api.menuDownloadCurrent === 'function') api.menuDownloadCurrent(info.kind, info.id);
      else if (typeof api.menuDownload === 'function') api.menuDownload(info.kind, info.id, info.title || '');
    } catch (x) { /* the app is not there */ }
  }

  function pick(e, row) {
    e.preventDefault();
    e.stopImmediatePropagation();
    var info = row.__wt;
    if (!info) return;
    last = null; // this menu is done with: nothing more is added to it while it closes
    closeSheet(row);
    send(info);
  }

  // ---- The row of buttons under the video of a watch page ----

  // The buttons of root that can be seen: the outermost of each (a wrapper of a button is not counted twice).
  function pressables(root) {
    var all = root.querySelectorAll(PRESSABLE), out = [];
    for (var i = 0; i < all.length; i++) {
      var b = all[i];
      if (b.closest('[data-wt-dlb]')) continue;
      if (b.querySelector(PRESSABLE)) continue;
      if (!b.getClientRects().length) continue;
      var h = b.getBoundingClientRect().height;
      if (h < 20 || h > 80) continue; // not a button of a row
      out.push(b);
    }
    return out;
  }

  function sameLine(bs) {
    var r0 = bs[0].getBoundingClientRect(), c0 = r0.top + r0.height / 2;
    for (var i = 1; i < bs.length; i++) {
      var r = bs[i].getBoundingClientRect();
      if (Math.abs(r.top + r.height / 2 - c0) > Math.max(r0.height, r.height) / 2) return false;
    }
    return true;
  }

  // The row of like / dislike / share ... buttons: { el, buttons }, or null. Its landmark is the like button, which
  // must be below the video (the player's own controls are not it).
  function findRow() {
    var anchors = document.querySelectorAll(ACTION_ANCHORS);
    var v = document.querySelector('video');
    var below = v ? v.getBoundingClientRect().bottom - 2 : 0;
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      if (a.closest('[data-wt-dlb]') || a.closest(CARDS) || !shown(a)) continue;
      if (a.getBoundingClientRect().top < below) continue;
      var n = a, hops = 0;
      while (n && n !== document.body && hops < 8) {
        var bs = pressables(n);
        if (bs.length >= 3) {
          if (sameLine(bs)) return { el: n, buttons: bs };
          break; // wider than one row: this anchor is not in the row of the actions
        }
        n = n.parentElement;
        hops++;
      }
    }
    return null;
  }

  function looksLikeDots(el) {
    var svg = el.querySelector('svg');
    if (!svg) return false;
    if (svg.querySelectorAll('circle').length >= 3) return true;
    var paths = svg.querySelectorAll('path'), moves = 0, closes = 0, len = 0;
    for (var i = 0; i < paths.length; i++) {
      var d = paths[i].getAttribute('d') || '';
      len += d.length;
      moves += (d.match(/[Mm]/g) || []).length;
      closes += (d.match(/[Zz]/g) || []).length;
    }
    return moves === 3 && closes === 3 && len < 600; // three little closed shapes
  }

  // The three dots of the row: inside YouTube's menu element, or named "more" in its label, or the last button of the
  // row when it is drawn as three dots. null when the row has none.
  function moreOf(row) {
    var bs = row.buttons;
    for (var i = 0; i < bs.length; i++) {
      var b = bs[i];
      if (b.closest('ytm-menu, ytm-menu-renderer')) return b;
      var label = (b.getAttribute('aria-label') || b.getAttribute('title') || '');
      if (label && MORE_RE.test(label)) return b;
    }
    var lastBtn = bs[bs.length - 1];
    return looksLikeDots(lastBtn) ? lastBtn : null;
  }

  function isMoreTap(btn) {
    var row = findRow();
    var more = row ? moreOf(row) : null;
    return !!more && (more === btn || more.contains(btn) || btn.contains(more));
  }

  function commonAncestor(els) {
    var a = els[0];
    while (a) {
      var ok = true;
      for (var i = 1; i < els.length; i++) {
        if (!a.contains(els[i])) { ok = false; break; }
      }
      if (ok) return a;
      a = a.parentElement;
    }
    return null;
  }

  function backgroundOf(el, stop) {
    var n = el;
    while (n) {
      var c = getComputedStyle(n).backgroundColor;
      if (c && c !== 'transparent' && !/^rgba\(\s*\d+,\s*\d+,\s*\d+,\s*0\s*\)$/.test(c)) return c;
      if (n === stop) break;
      n = n.parentElement;
    }
    return 'transparent';
  }

  // A button that looks like the last button of the row: its size, colours, corners and text style are copied.
  // Returns { btn, item } (item is the child of the row's container that it goes after), or null.
  function buildButton(row, host) {
    var bs = row.buttons, ref = bs[bs.length - 1];
    var item = ref;
    while (item.parentElement && item.parentElement !== host) item = item.parentElement;
    if (item.parentElement !== host) return null;

    var cs = getComputedStyle(ref);
    var rect = ref.getBoundingClientRect();
    if (rect.width < 24 || rect.width > 220 || rect.height < 24 || rect.height > 80) return null;
    var hasText = (ref.textContent || '').replace(/\s+/g, '').length > 0;
    var ic = ref.querySelector('svg');
    var size = ic ? clamp(ic.getBoundingClientRect().width, 16, 32, 24) : 24;
    var hs = getComputedStyle(host);
    var rtl = hs.direction === 'rtl';

    // The space between the last two items, when the container does not make it with a gap of its own.
    var margin = 0;
    if (!(parseFloat(hs.columnGap) > 0)) {
      var prev = item.previousElementSibling;
      while (prev && !prev.getClientRects().length) prev = prev.previousElementSibling;
      if (prev) {
        var a = prev.getBoundingClientRect(), b = item.getBoundingClientRect();
        margin = clamp(rtl ? a.left - b.right : b.left - a.right, 0, 24, 8);
      } else {
        margin = 8;
      }
    }

    var label = isArabic() ? 'تنزيل' : 'Download';
    var btn = document.createElement('div');
    btn.setAttribute('data-wt-dlb', '1');
    btn.setAttribute('role', 'button');
    btn.setAttribute('tabindex', '0');
    btn.setAttribute('aria-label', label);
    var s = btn.style;
    s.display = 'inline-flex';
    s.alignItems = 'center';
    s.justifyContent = 'center';
    s.boxSizing = 'border-box';
    s.flex = 'none';
    s.height = Math.round(rect.height) + 'px';
    if (hasText) {
      s.minWidth = Math.round(rect.height) + 'px';
      s.paddingInlineStart = cs.paddingLeft;
      s.paddingInlineEnd = cs.paddingRight;
      s.columnGap = '6px';
      s.fontFamily = cs.fontFamily;
      s.fontSize = cs.fontSize;
      s.fontWeight = cs.fontWeight;
      s.lineHeight = cs.lineHeight;
      s.letterSpacing = cs.letterSpacing;
      s.whiteSpace = 'nowrap';
    } else {
      s.width = Math.round(rect.width) + 'px';
    }
    s.direction = hs.direction;
    s.color = cs.color;
    s.backgroundColor = backgroundOf(ref, item);
    s.borderRadius = cs.borderRadius;
    s.border = cs.borderTopWidth !== '0px' ? cs.borderTopWidth + ' ' + cs.borderTopStyle + ' ' + cs.borderTopColor : '0';
    s.marginInlineStart = margin + 'px';
    s.cursor = 'pointer';
    s.userSelect = 'none';
    s.webkitUserSelect = 'none';
    s.webkitTapHighlightColor = 'transparent';
    btn.appendChild(icon(size));
    if (hasText) {
      var tx = document.createElement('span');
      tx.textContent = label;
      btn.appendChild(tx);
    }
    btn.addEventListener('pointerdown', function () { btn.style.opacity = '0.6'; });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (name) {
      btn.addEventListener(name, function () { btn.style.opacity = ''; });
    });
    return { btn: btn, item: item };
  }

  function removeButton() {
    var b = document.querySelector('[data-wt-dlb]');
    if (b && b.parentNode) b.parentNode.removeChild(b);
  }

  // Looks at the row of buttons of a watch page and sets up the download: the three dots are hooked (the menu gets the
  // row), or, with no three dots (or after a menu that could not take the row), the row gets a button. Anything that
  // does not work out leaves nothing on the page.
  function syncRow() {
    syncTimer = 0;
    try {
      if (!bridge() || !watchVideo()) {
        removeButton();
        hooked = null;
        return;
      }
      var mine = document.querySelector('[data-wt-dlb]');
      if (mine && mine.isConnected && shown(mine)) return;       // the button is there
      if (hooked && hooked.isConnected && shown(hooked) && !fallbackOn) return; // the menu takes it
      hooked = null;
      removeButton();
      var row = findRow();
      if (!row) return;
      var more = moreOf(row);
      if (more && !fallbackOn) {
        hooked = more;
        return;
      }
      var host = commonAncestor(row.buttons);
      var built = host ? buildButton(row, host) : null;
      if (!built) return;
      built.item.parentNode.insertBefore(built.btn, built.item.nextSibling);
      if (!shown(built.btn)) removeButton(); // not visible: not left in the page
    } catch (x) {
      try { removeButton(); } catch (y) { /* nothing more to do */ }
    }
  }

  function scheduleSync() {
    if (syncTimer || !watchVideo()) return;
    syncTimer = setTimeout(syncRow, SYNC_MS);
  }

  // What can be the three dots: a button of a card, or of the row under the video of a watch page.
  var BUTTONS = 'button, [role="button"], yt-icon-button, button-view-model, yt-button-shape, ytm-menu, ytm-menu-renderer';
  var SAME_TAP_MS = 600; // `pointerup` and then `click` of one tap are one tap

  // A tap on `t` (not on the script's own row or button, not inside a menu). On the three dots of a card the menu that
  // opens gets the row; on the three dots under the video of a watch page the row is waited for in the same way;
  // anywhere else the wait for a menu ends.
  // The video named by the data YouTube keeps on an element (a bounded look: no cycles, no page nodes).
  function idInData(o, depth, seen, budget) {
    if (!o || depth > 8 || budget.n-- < 0 || typeof o !== 'object' || o.nodeType || o === window) return '';
    if (seen.indexOf(o) >= 0) return '';
    seen.push(o);
    var keys = ['videoId', 'addedVideoId', 'removedVideoId'];
    for (var i = 0; i < keys.length; i++) {
      var v = o[keys[i]];
      if (typeof v === 'string' && /^[A-Za-z0-9_-]{11}$/.test(v)) return v;
    }
    var n = 0;
    for (var k in o) {
      if (n++ > 40) break;
      var r = '';
      try { r = idInData(o[k], depth + 1, seen, budget); } catch (e) { r = ''; }
      if (r) return r;
    }
    return '';
  }

  // The tap itself says which video: the elements it went through (the card, the button, the three dots) carry the data
  // their page part was built from, whatever the card looks like. e.composedPath() is the list from the touched
  // element up.
  function cardFromPath(e) {
    try {
      var path = e && e.composedPath ? e.composedPath() : [];
      for (var i = 0; i < path.length && i < 30; i++) {
        var el = path[i];
        if (!el || el.nodeType !== 1 || el === document.body) continue;
        if (el.closest && el.closest('[data-wt-dl], [data-wt-dlb]')) return null;
        var data = null;
        try { data = el.data || el.__data || null; } catch (x) { data = null; }
        var id = data ? idInData(data, 0, [], { n: 1500 }) : '';
        if (id) return { v: { kind: 'video', id: id }, el: el };
      }
    } catch (x) { /* nothing from the path */ }
    return null;
  }

  // True when `el` is inside the comments of a video, a Short or a post: the list, a comment, the box that writes one.
  // The menu a comment opens (reply, like, dislike, report) is never offered a download, whatever else the page holds
  // (a post with a video attached has one video on the whole page, which the guess of a card below would name).
  function inComments(el) {
    try {
      if (el.closest(COMMENTS)) return true;
      for (var n = el, hops = 0; n && n !== document.body && hops < 60; n = n.parentElement, hops++) {
        if ((n.localName || '').indexOf('comment') >= 0) return true;
        if (/(^|\s)(ytm-)?comment/i.test((n.getAttribute && n.getAttribute('class')) || '')) return true;
      }
    } catch (x) { /* not known: it is not taken for the comments */ }
    return false;
  }

  // True for a short while after the app itself touched the page to open the comments of a post (SubsController sets
  // `__wtNoDl`): a menu that follows such a touch is not a video's.
  function appTouch() {
    return Date.now() < (window.__wtNoDl || 0);
  }

  function noteTap(t, e) {
    if (appTouch() || inComments(t)) { // the comments: no wait for a menu, and a row that is still there goes
      clearRows();
      last = null;
      return;
    }
    if (onShorts()) {
      noteShortsTap(t, e, Date.now());
      return;
    }
    var btn = t.closest(BUTTONS);
    var watching = watchVideo();
    var more = !!(btn && watching && isMoreTap(btn));
    var card = !more && btn ? cardOf(btn, !!watching) : null;
    if (!more && btn && !card && !watching) card = cardFromPath(e);
    var late = !more && !card && !watching ? cardNou(t) : null; // inside a card, not on a known button: wait for a menu
    var now = Date.now();
    var vid = more ? watching : card ? card.v : null;
    if (vid && last && !!last.cur === more && last.v.id === vid.id && now - last.at < SAME_TAP_MS) return;
    clearRows();
    if (more) { // the three dots of the row under the video
      last = { v: watching, title: '', at: now, cur: true };
      startPolling();
    } else if (card) {
      last = { v: card.v, title: titleOf(card.el), at: now };
      startPolling();
    } else if (late) {
      last = { v: late.v, title: titleOf(late.el), at: now, late: true };
      startPolling(); // the row goes in only if a menu really opens (see scan)
    } else {
      last = null;
    }
  }

  // The first listeners on the page (capture, window), so YouTube cannot stop the event before it is seen.
  window.addEventListener('click', function (e) {
    try {
      var t = e.target;
      if (!t || !t.closest) return;
      var mine = t.closest('[data-wt-dl]');
      if (mine) {
        pick(e, mine);
        return;
      }
      var own = t.closest('[data-wt-dlb]');
      if (own) {
        e.preventDefault();
        e.stopImmediatePropagation();
        var v = watchVideo();
        if (v) send({ kind: v.kind, id: v.id, title: '', cur: true });
        return;
      }
      if (t.closest(SHEETS)) { // a tap inside a menu: it is not a new menu button
        last = null;
        return;
      }
      noteTap(t, e);
    } catch (x) { /* never break the page */ }
  }, true);

  // The same tap, seen when the finger lifts: a page that opens its menu from the touch and cancels the click that
  // follows it would not be seen by the listener above. A touch that moved (a scroll) or lasted long is not a tap.
  var down = null;
  window.addEventListener('pointerdown', function (e) {
    down = { x: e.clientX, y: e.clientY, at: Date.now() };
  }, true);
  window.addEventListener('pointerup', function (e) {
    try {
      var d = down;
      down = null;
      if (!d || Date.now() - d.at > TAP_MS || Math.abs(e.clientX - d.x) > TAP_PX || Math.abs(e.clientY - d.y) > TAP_PX) return;
      var t = e.target;
      if (!t || !t.closest || t.closest('[data-wt-dl], [data-wt-dlb]') || t.closest(SHEETS)) return;
      noteTap(t, e);
    } catch (x) { /* never break the page */ }
  }, true);

  try {
    new MutationObserver(function () {
      if (last) scan();
      scheduleSync();
    }).observe(document, { childList: true, subtree: true });
  } catch (x) { /* the polling above still finds the menu */ }
})();
