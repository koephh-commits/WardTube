// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

/*
 * WardTube page script. Injected at document start (or right after the page starts
 * loading on WebViews without document-start scripts).
 *
 * window.__wardtubeConfig = { adblock, background, baseCss, adCss } is prepended by PageScripts.kt.
 * Settings can be changed live with window.__wardtube.configure({ adblock, background, adCss }).
 */
(function () {
  'use strict';

  if (window.top !== window || window.__wardtube) return;

  var cfg = window.__wardtubeConfig || {};
  var state = {
    adblock: cfg.adblock !== false,
    background: cfg.background !== false
  };

  var baseStyle = null;
  var adStyle = null;

  // ---- Styles -------------------------------------------------------------

  function whenRoot(fn) {
    if (document.documentElement) { fn(); return; }
    var mo = new MutationObserver(function () {
      if (document.documentElement) { mo.disconnect(); fn(); }
    });
    mo.observe(document, { childList: true });
  }

  function addStyle(id, css) {
    var el = document.createElement('style');
    el.id = id;
    el.textContent = css || '';
    (document.head || document.documentElement).appendChild(el);
    return el;
  }

  function applyAdStyle() {
    if (adStyle) adStyle.media = state.adblock ? 'all' : 'not all';
  }

  whenRoot(function () {
    baseStyle = addStyle('wardtube-base-css', cfg.baseCss);
    adStyle = addStyle('wardtube-adblock-css', cfg.adCss);
    applyAdStyle();
  });

  // ---- Ad data pruning ----------------------------------------------------
  // Player responses carry the ad schedule; dropping it means the player never asks for ads.

  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots'];
  // Top-level keys of YouTube API / page data that may carry ads inside lists (feeds, Shorts sequences).
  var LIST_HINTS = ['entries', 'contents', 'continuationContents', 'onResponseReceivedActions',
    'onResponseReceivedEndpoints', 'actions', 'items', 'frameworkUpdates'];
  var MAX_DEPTH = 14;

  // True when a list element is an ad (e.g. a sponsored Short placed between normal Shorts).
  function isAdItem(el, depth) {
    if (!el || typeof el !== 'object' || depth > 6) return false;
    if (el.adSlotRenderer || el.adSlotViewModel) return true;
    var acp = el.adClientParams;
    if (acp && (acp.isAd === true || acp.isAd === 'true')) return true;
    for (var k in el) {
      if (!Object.prototype.hasOwnProperty.call(el, k)) continue;
      var v = el[k];
      if (v && typeof v === 'object' && isAdItem(v, depth + 1)) return true;
    }
    return false;
  }

  function deepPrune(node, depth) {
    if (!node || typeof node !== 'object' || depth > MAX_DEPTH) return;
    if (Array.isArray(node)) {
      for (var i = node.length - 1; i >= 0; i--) {
        if (isAdItem(node[i], 0)) node.splice(i, 1);
        else deepPrune(node[i], depth + 1);
      }
      return;
    }
    for (var j = 0; j < AD_KEYS.length; j++) {
      if (AD_KEYS[j] in node) delete node[AD_KEYS[j]];
    }
    for (var key in node) {
      if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
      var val = node[key];
      if (val && typeof val === 'object') deepPrune(val, depth + 1);
    }
  }

  function looksLikeFeedData(obj) {
    for (var i = 0; i < LIST_HINTS.length; i++) {
      if (LIST_HINTS[i] in obj) return true;
    }
    return false;
  }

  function prune(obj) {
    if (!state.adblock || !obj || typeof obj !== 'object') return obj;
    try {
      for (var i = 0; i < AD_KEYS.length; i++) {
        if (AD_KEYS[i] in obj) delete obj[AD_KEYS[i]];
      }
      if (obj.playerResponse) prune(obj.playerResponse);
      if (Array.isArray(obj)) {
        for (var j = 0; j < obj.length; j++) {
          if (obj[j] && obj[j].playerResponse) prune(obj[j].playerResponse);
        }
      }
      if (Array.isArray(obj) || looksLikeFeedData(obj) || obj.responseContext) deepPrune(obj, 0);
    } catch (e) { /* never break the page */ }
    return obj;
  }

  try {
    var nativeParse = JSON.parse;
    JSON.parse = function () {
      return noteQueue(prune(nativeParse.apply(this, arguments)));
    };
  } catch (e) {}

  try {
    if (window.Response && Response.prototype.json) {
      var nativeJson = Response.prototype.json;
      Response.prototype.json = function () {
        return nativeJson.apply(this, arguments).then(function (data) { return noteQueue(prune(data)); });
      };
    }
  } catch (e) {}

  // The first player response is inlined in the HTML as `var ytInitialPlayerResponse = {...}`.
  try {
    var initialResponse = window.ytInitialPlayerResponse;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return initialResponse; },
      set: function (value) { initialResponse = prune(value); }
    });
  } catch (e) {}

  try {
    var initialData = window.ytInitialData;
    Object.defineProperty(window, 'ytInitialData', {
      configurable: true,
      get: function () { return initialData; },
      set: function (value) { initialData = noteQueue(prune(value)); }
    });
  } catch (e) {}

  // ---- Ad skipper (for ads that are still served inside the player) --------

  var saved = null;

  function mainVideo() {
    return document.querySelector('video.html5-main-video') || document.querySelector('video');
  }

  function restoreVideo() {
    if (!saved) return;
    var video = mainVideo();
    if (video) {
      video.muted = saved.muted;
      video.playbackRate = saved.rate;
    }
    saved = null;
  }

  function skipAd() {
    if (!state.adblock) { restoreVideo(); return; }
    var player = document.querySelector('.html5-video-player');
    var video = mainVideo();
    if (!video) return;

    if (player && player.classList.contains('ad-showing')) {
      var skip = document.querySelector(
        '.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-container button');
      if (skip) skip.click();
      if (!saved) saved = { muted: video.muted, rate: video.playbackRate };
      video.muted = true;
      if (isFinite(video.duration) && video.duration > 0) {
        video.playbackRate = 16;
        video.currentTime = video.duration;
      }
    } else {
      restoreVideo();
    }
  }

  // ---- Shorts ad skipper ----------------------------------------------------
  // Shorts ads are normal-looking items in the vertical feed (no `ad-showing` player state).
  // If one slips past the data pruning, detect it in the DOM and move on to the next Short.

  var AD_IN_REEL = [
    'ytm-ad-slot-renderer', 'ytm-reel-ad-renderer', 'ytm-shorts-ad-renderer',
    '.ad-badge', '.ytReelAdBadge', '[class*="ad-badge"]', '[class*="reel-ad"]',
    'ytm-ad-badge-renderer', 'ad-badge-view-model'
  ].join(',');
  var ACTIVE_REEL = [
    'ytm-reel-video-renderer[is-active]', 'ytm-reel-video-renderer[is-active="true"]',
    'ytm-shorts-lockup-view-model[is-active]', '.reel-video-in-sequence[is-active]'
  ].join(',');
  var NEXT_BTN = [
    '#navigation-button-down button', '.navigation-button-down button',
    'button[aria-label="Next video"]', 'button[aria-label="التالي"]'
  ].join(',');
  var lastShortsSkip = 0;

  function skipShortsAd() {
    if (!state.adblock || location.pathname.indexOf('/shorts') !== 0) return;
    var now = Date.now();
    if (now - lastShortsSkip < 900) return;
    var active = document.querySelector(ACTIVE_REEL);
    if (!active || !active.querySelector(AD_IN_REEL)) return;
    lastShortsSkip = now;
    try {
      var v = active.querySelector('video');
      if (v) v.muted = true;
      var next = document.querySelector(NEXT_BTN);
      if (next) { next.click(); return; }
      var sib = active.nextElementSibling;
      if (sib && sib.scrollIntoView) sib.scrollIntoView({ block: 'start' });
    } catch (e) {}
  }

  // ---- Background play ----------------------------------------------------
  // Makes the page believe it is always visible so the player is not paused when the app is hidden.

  try {
    var hiddenGetter = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden').get;
    var visibilityGetter = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState').get;
    Object.defineProperty(document, 'hidden', {
      configurable: true,
      get: function () { return state.background ? false : hiddenGetter.call(document); }
    });
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: function () { return state.background ? 'visible' : visibilityGetter.call(document); }
    });
    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (state.background) e.stopImmediatePropagation();
      }, true);
    });
  } catch (e) {}

  // ---- Iframes ------------------------------------------------------------
  // Removed to prevent WebRTC exploits (WebRTC leak protection).

  function dropIframes() {
    var frames = document.getElementsByTagName('iframe');
    while (frames.length) frames[0].parentNode.removeChild(frames[0]);
  }

  // ---- Video links and the native player ----------------------------------
  // cfg.route === 'browse': tapping a video link asks the app to play it in its video player (a second WebView),
  //   so the page being browsed stays exactly as it is, behind the mini player.
  // cfg.route === 'player': tapping a link to any other YouTube page (channel, hashtag, search...) asks the app
  //   to show that page in the browsing WebView instead of leaving the video.

  function linkOf(e) {
    var path = e.composedPath ? e.composedPath() : [];
    for (var i = 0; i < path.length; i++) {
      var n = path[i];
      if (n && n.tagName === 'A' && n.href) return n;
    }
    var t = e.target;
    while (t && t !== document) {
      if (t.tagName === 'A' && t.href) return t;
      t = t.parentNode;
    }
    return null;
  }

  function isYouTubeLink(a) {
    return /(^|\.)youtube\.com$/.test(a.hostname) || a.hostname === 'youtu.be';
  }

  function isVideoLink(a) {
    if (a.hostname === 'youtu.be') return a.pathname.length > 1;
    return /(^|\.)youtube\.com$/.test(a.hostname) && a.pathname.indexOf('/watch') === 0;
  }

  if (cfg.route) {
    window.addEventListener('click', function (e) {
      try {
        if (e.defaultPrevented || e.button > 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
        var bridge = window.WardTubeNative;
        if (!bridge) return;
        var a = linkOf(e);
        if (!a || (a.protocol !== 'https:' && a.protocol !== 'http:') || !isYouTubeLink(a)) return;
        var video = isVideoLink(a);
        if (cfg.route === 'browse' && video) {
          bridge.openVideo(a.href);
        } else if (cfg.route === 'player' && !video) {
          // a link to a spot on this same page is not a navigation
          if (a.pathname === location.pathname && a.search === location.search && a.hash) return;
          bridge.openPage(a.href);
        } else {
          return;
        }
        e.preventDefault();
        e.stopImmediatePropagation();
      } catch (err) { /* let the page handle the tap */ }
    }, true);
  }

  // ---- Sound on in the player -----------------------------------------------
  // YouTube's mobile page starts a video muted ("tap to unmute") when the page was opened without a tap inside it,
  // which is how the app's player opens every video. Turn the sound on once, right after the video starts. It is
  // done once per page load, so a video you mute yourself stays muted.

  var unmuteDone = false;
  var unmuteTries = 0;

  function autoUnmute() {
    if (cfg.route !== 'player' || unmuteDone) return;
    var video = mainVideo();
    var box = document.querySelector('.html5-video-player');
    if (!video || (box && box.classList.contains('ad-showing'))) return;
    if (!video.muted) {
      if (video.currentTime > 0.5) unmuteDone = true; // playing with sound already
      return;
    }
    if (++unmuteTries > 50) { unmuteDone = true; return; } // gave up after about 15 seconds
    var player = document.getElementById('movie_player');
    try { if (player && player.isMuted && player.isMuted() && player.unMute) player.unMute(); } catch (e) {}
    video.muted = false;
    if (video.muted) {
      var button = document.querySelector('.ytp-unmute');
      if (button && button.offsetParent !== null) button.click();
    }
  }

  // ---- Playlists and mixes (player only) -----------------------------------------
  // YouTube's mobile page does not always move on inside a playlist or a mix, most often with shuffle on: the video
  // ends and plays again, and its own next button does the same. So the page script does the moving itself. It finds
  // the list (the links of the list panel when the page shows them, else the queue found in the page's own data) and
  // opens the video next to the current one. YouTube's own way is used when the list is unknown or has no more videos.

  var END_GRACE_MS = 3000;   // how long YouTube gets to move on by itself after a video in a list ended
  var OPEN_CHECK_MS = 3500;  // how long a tapped list item gets to open before the page is loaded the plain way
  var NEXT_CHECK_MS = 3500;  // the same for YouTube's own next button

  var queue = { list: '', ids: [] };  // the list the page announced last, in play order
  var openCheck = 0;
  var nextCheck = 0;
  var endCheck = 0;

  function currentId() {
    var m = /[?&]v=([^&]+)/.exec(location.search);
    return m ? m[1] : '';
  }

  function currentList() {
    var m = /[?&]list=([^&]+)/.exec(location.search);
    return m ? m[1] : '';
  }

  function adShowing() {
    var box = document.querySelector('.html5-video-player');
    return !!box && box.classList.contains('ad-showing');
  }

  // Where YouTube puts the list panel in its data (a watch page, a "next" answer, or a side panel).
  function playlistPanelOf(data) {
    var c = data.contents;
    var watch = c && (c.singleColumnWatchNextResults || c.twoColumnWatchNextResults);
    var panel = watch && watch.playlist && watch.playlist.playlist;
    if (panel && Array.isArray(panel.contents)) return panel;
    var panels = data.engagementPanels;
    if (Array.isArray(panels)) {
      for (var i = 0; i < panels.length; i++) {
        var section = panels[i] && panels[i].engagementPanelSectionListRenderer;
        var side = section && section.content && section.content.playlistPanelRenderer;
        if (side && Array.isArray(side.contents)) return side;
      }
    }
    return null;
  }

  // Remembers the list a page or a "next" answer carries. Hooked into every parsed answer; returns it unchanged.
  function noteQueue(data) {
    if (cfg.route !== 'player' || !data || typeof data !== 'object') return data;
    try {
      var panel = playlistPanelOf(data);
      if (!panel) return data;
      var ids = [];
      for (var i = 0; i < panel.contents.length; i++) {
        var item = panel.contents[i] || {};
        var wrapper = item.playlistPanelVideoWrapperRenderer;
        var video = item.playlistPanelVideoRenderer ||
          (wrapper && wrapper.primaryRenderer && wrapper.primaryRenderer.playlistPanelVideoRenderer);
        if (video && video.videoId) ids.push(video.videoId);
      }
      if (ids.length) queue = { list: panel.playlistId || '', ids: ids };
    } catch (e) { /* never break the page */ }
    return data;
  }

  // The videos of list `list` as the page shows them: [{ id, link }] in on-screen order.
  function panelItems(list) {
    var items = [];
    var known = {};
    var links = document.querySelectorAll('a[href*="/watch"]');
    for (var i = 0; i < links.length; i++) {
      var url;
      try { url = new URL(links[i].href, location.href); } catch (e) { continue; }
      var id = url.searchParams.get('v');
      if (!id || known[id] || url.searchParams.get('list') !== list) continue;
      known[id] = true;
      items.push({ id: id, link: links[i] });
    }
    return items;
  }

  // The video `step` places after (1) or before (-1) the current one in its list: { id, link } (link is null when it
  // comes from the page's data and is not shown), or null when the list is unknown or ends there.
  function listNeighbour(step) {
    var cur = currentId();
    var list = currentList();
    if (!cur || !list) return null;
    var items = panelItems(list);
    for (var i = 0; i < items.length; i++) {
      if (items[i].id === cur && items[i + step]) return items[i + step];
    }
    if (queue.list && queue.list !== list) return null;
    var at = queue.ids.indexOf(cur);
    if (at >= 0 && queue.ids[at + step]) return { id: queue.ids[at + step], link: null };
    return null;
  }

  // True when the current video is found in its list (shown by the page, or in the page's data).
  function inKnownList() {
    var cur = currentId();
    var list = currentList();
    if (!cur || !list) return false;
    var items = panelItems(list);
    for (var i = 0; i < items.length; i++) {
      if (items[i].id === cur) return true;
    }
    return (!queue.list || queue.list === list) && queue.ids.indexOf(cur) >= 0;
  }

  // Opens a video of the list: a tap on its link (YouTube changes the page itself), or a plain page load when the
  // link is not shown or the tap changed nothing a moment later.
  function openItem(item) {
    var url = '/watch?v=' + encodeURIComponent(item.id) + '&list=' + encodeURIComponent(currentList());
    clearTimeout(openCheck);
    if (!item.link) { location.assign(url); return; }
    item.link.click();
    openCheck = setTimeout(function () {
      if (currentId() !== item.id) location.assign(url);
    }, OPEN_CHECK_MS);
  }

  // The first suggested video that is not the current one (what autoplay would pick). True if one was opened.
  function openSuggestion(cur) {
    var links = document.querySelectorAll(
      'ytm-watch-next-secondary-results-renderer a[href*="/watch"],' +
      'ytm-item-section-renderer a[href*="/watch"],a.media-item-thumbnail-container[href*="/watch"]');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href') || '';
      if (!cur || href.indexOf(cur) < 0) { links[i].click(); return true; }
    }
    return false;
  }

  // Next video (the app's notification and headset buttons). Returns how it was done, for tests.
  function skipNext() {
    var cur = currentId();
    var item = listNeighbour(1);
    if (item) { openItem(item); return 'list'; }

    var how = '';
    var video = mainVideo();
    var button = document.querySelector('.ytp-next-button,.player-control-next-button,button[aria-label="Next video"]');
    if (button && !button.disabled && button.offsetParent !== null) {
      button.click();
      how = 'button';
    } else if (currentList() && video && isFinite(video.duration) && video.duration > 0) {
      // inside a list the video jumps to its end, so YouTube moves to its next item by itself
      video.currentTime = Math.max(0, video.duration - 0.25);
      var played = video.play && video.play();
      if (played && played.catch) played.catch(function () {});
      how = 'seek-end';
    }
    if (how) {
      // YouTube replayed the same video instead of moving on: take the first suggestion
      clearTimeout(nextCheck);
      nextCheck = setTimeout(function () {
        if (currentId() === cur && !adShowing()) openSuggestion(cur);
      }, NEXT_CHECK_MS);
      return how;
    }
    return openSuggestion(cur) ? 'suggestion' : 'none';
  }

  // Previous video: restarts the current one after more than 5 seconds, like every media player. Otherwise the video
  // before it in its list, else the one watched before this one (browser history), else a restart.
  function skipPrevious() {
    var video = mainVideo();
    var cur = currentId();
    if (video && video.currentTime > 5) { video.currentTime = 0; return 'restart'; }

    var item = listNeighbour(-1);
    if (item) { openItem(item); return 'list'; }

    try {
      var nav = window.navigation;
      if (nav && nav.entries && nav.currentEntry) {
        var entries = nav.entries();
        for (var i = nav.currentEntry.index - 1; i >= 0; i--) {
          var url = new URL(entries[i].url);
          var id = url.pathname === '/watch' ? url.searchParams.get('v') : null;
          if (id && id !== cur) { nav.traverseTo(entries[i].key); return 'history'; }
        }
      }
    } catch (e) {}

    if (video) { video.currentTime = 0; return 'restart-fallback'; }
    return 'none';
  }

  // A video in a list ended and YouTube did not move on (it plays the same video again, or stays at the end):
  // open the next one of the list. Not done for a video the user set to repeat, nor for the end of the list. If the
  // list cannot be read at all (YouTube changed its page), the first suggestion is better than a video on repeat.
  if (cfg.route === 'player') {
    document.addEventListener('ended', function (e) {
      var video = e.target;
      if (video !== mainVideo() || adShowing() || !currentList()) return; // an ad ended, or this is not a list
      var id = currentId();
      clearTimeout(endCheck);
      endCheck = setTimeout(function () {
        if (currentId() !== id || video.loop || adShowing()) return; // YouTube moved on, or the video repeats on purpose
        var item = listNeighbour(1);
        if (item) openItem(item);
        else if (!inKnownList()) openSuggestion(id);
      }, END_GRACE_MS);
    }, true); // 'ended' does not bubble: listen on the way down
  }

  // ---- Timer --------------------------------------------------------------

  var ticks = 0;
  setInterval(function () {
    ticks++;
    skipAd();
    autoUnmute();
    skipShortsAd();
    if (ticks % 5 === 0) dropIframes();
  }, 300);

  // ---- Public API ---------------------------------------------------------

  window.__wardtube = {
    /** Next (step > 0) or previous video, for the notification buttons. */
    skip: function (step) {
      return step > 0 ? skipNext() : skipPrevious();
    },
    configure: function (next) {
      if (!next) return;
      if (typeof next.adblock === 'boolean') state.adblock = next.adblock;
      if (typeof next.background === 'boolean') state.background = next.background;
      if (typeof next.adCss === 'string') {
        cfg.adCss = next.adCss;
        if (adStyle) adStyle.textContent = next.adCss;
      }
      applyAdStyle();
      if (!state.adblock) restoreVideo();
    }
  };
})();
