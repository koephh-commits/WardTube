// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods
//
// "Download video" row for the menu YouTube opens from the three dots beside a video.
// Installed for every YouTube page of the browsing WebView (MenuDownload.kt).
// It never changes YouTube's own rows: it adds one row of its own, built by hand, and never uses innerHTML
// (YouTube's Trusted Types rules refuse it).
(function () {
  'use strict';
  if (window.__wtMenuDl) return;
  window.__wtMenuDl = true;

  var WINDOW_MS = 6000; // how long the menu is waited for after a tap on a button inside a video card

  // Links that name a video, inside a card.
  var LINKS = 'a[href*="/watch?v="], a[href*="/shorts/"]';
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

  var last = null; // { v: { kind, id }, title, at }: the video of the last tapped button inside a card
  var poll = 0;

  // ---- Which video is this card? ----

  function parse(a) {
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
    return first;
  }

  function cardOf(from) {
    var known = from.closest(CARDS);
    if (known) {
      var kv = videoIn(known);
      return kv && kv !== 'many' ? { v: kv, el: known } : null; // a card without a video (an ad) is not guessed at
    }
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

  function titleOf(el) {
    for (var i = 0; i < TITLES.length; i++) {
      var t = el.querySelector(TITLES[i]);
      var s = t ? (t.textContent || '').replace(/\s+/g, ' ').trim() : '';
      if (s.length > 1) return s.slice(0, 200);
    }
    return '';
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

    var ar = /^ar/i.test(document.documentElement.getAttribute('lang') || navigator.language || '');
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
    tx.textContent = ar ? 'تنزيل الفيديو' : 'Download video';
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

  function scan() {
    if (!active()) return;
    var mine = document.querySelector('[data-wt-dl]');
    if (mine && mine.isConnected && shown(mine)) return; // already added to this menu
    var nodes = document.querySelectorAll(ITEMS);
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].closest('[data-wt-dl]')) continue;
      var item = outer(nodes[i]);
      if (!shown(item) || !item.parentNode) continue; // a menu kept hidden in the page
      var info = { kind: last.v.kind, id: last.v.id, title: last.title };
      item.parentNode.insertBefore(build(item, info), item);
      return;
    }
  }

  function startPolling() {
    clearInterval(poll);
    var runs = 0;
    poll = setInterval(function () {
      if (!active() || ++runs > 40) {
        clearInterval(poll);
        return;
      }
      scan();
    }, 150);
  }

  // Closes YouTube's menu after the row was tapped, the way a finger would: a tap on the dimmed page above the menu
  // (whatever is on top there, unless it is a link, a button or part of the menu itself), then Escape.
  function closeSheet(from) {
    try {
      var root = from.closest(SHEETS);
      var el = document.elementFromPoint(Math.round(window.innerWidth / 2), 8);
      if (el && !from.contains(el) && !(root && root.contains(el)) &&
          !el.closest('a, button, input, [role="button"], [role="menuitem"]')) {
        el.click();
      }
    } catch (x) { /* Escape below */ }
    try {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
    } catch (x) { /* nothing more to try */ }
  }

  function pick(e, row) {
    e.preventDefault();
    e.stopImmediatePropagation();
    var info = row.__wt;
    if (!info) return;
    closeSheet(row);
    try {
      if (window.WardTubeMenuDl) window.WardTubeMenuDl.download(info.kind, info.id, info.title || '');
    } catch (x) { /* the app is not there */ }
  }

  // The first listener on the page (capture, window), so YouTube cannot stop the event before it is seen.
  window.addEventListener('click', function (e) {
    try {
      var t = e.target;
      if (!t || !t.closest) return;
      var mine = t.closest('[data-wt-dl]');
      if (mine) {
        pick(e, mine);
        return;
      }
      if (t.closest(SHEETS)) { // a tap inside a menu: it is not a new menu button
        last = null;
        return;
      }
      clearRows();
      var btn = t.closest('button, [role="button"], yt-icon-button, ytm-menu, ytm-menu-renderer');
      var card = btn ? cardOf(btn) : null;
      if (card) {
        last = { v: card.v, title: titleOf(card.el), at: Date.now() };
        startPolling();
      } else {
        last = null;
      }
    } catch (x) { /* never break the page */ }
  }, true);

  try {
    new MutationObserver(function () {
      if (last) scan();
    }).observe(document, { childList: true, subtree: true });
  } catch (x) { /* the polling above still finds the menu */ }
})();
