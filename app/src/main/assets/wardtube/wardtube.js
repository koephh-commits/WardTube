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
  // which is how the app's player opens every video. Turn the sound on once, right after each video starts. It is
  // done once per video, so a video you mute yourself stays muted.
  // YouTube moves on to the next video of a list (autoplay) without loading a new page, and starts it muted again, so
  // the job begins again whenever the player loads a video ('loadstart' / 'emptied') or the address names another one.

  var unmuteDone = false;
  var unmuteTries = 0;
  var unmuteFor = ''; // the video id the two values above belong to

  function resetUnmute() {
    unmuteDone = false;
    unmuteTries = 0;
  }

  if (cfg.route === 'player') {
    // media events do not bubble: listen on the way down
    ['loadstart', 'emptied'].forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (e.target === mainVideo()) resetUnmute();
      }, true);
    });
  }

  function autoUnmute() {
    if (cfg.route !== 'player') return;
    var id = currentId();
    if (id !== unmuteFor) { unmuteFor = id; resetUnmute(); }
    if (unmuteDone) return;
    var video = mainVideo();
    var box = document.querySelector('.html5-video-player');
    if (!video || (box && box.classList.contains('ad-showing'))) return;
    if (!video.muted) {
      // playing with sound for a moment already (a little longer than a blink: YouTube may mute it again right after it starts)
      if (video.currentTime > 2) unmuteDone = true;
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

  // ---- Sound on in Shorts (browsing page) -------------------------------------
  // A Short opened by the app (from the Subscriptions screen, a link, the player) is a page loaded without any tap
  // inside it, so YouTube starts it muted, the same as it does a video in the player. The same job as above is done
  // for the Short on view: once per Short, right after it starts, so a Short you mute yourself stays muted. A Short
  // is a new "video" when the address names another one, when the page loads a new picture ('loadstart' / 'emptied';
  // YouTube may change the address a moment after it starts the next Short) or when you swipe to the next one.
  // An ad in the Shorts feed is left alone: the ad skipper above mutes it and moves on.

  var shortUnmuteDone = false;
  var shortUnmuteTries = 0;
  var shortUnmuteFor = '';

  function onShorts() {
    return location.pathname.indexOf('/shorts') === 0;
  }

  function resetShortUnmute() {
    shortUnmuteDone = false;
    shortUnmuteTries = 0;
  }

  if (cfg.route === 'browse') {
    ['loadstart', 'emptied'].forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (onShorts() && e.target && e.target.tagName === 'VIDEO') resetShortUnmute();
      }, true);
    });
  }

  /** The video of the Short on view: the active reel's, else the one that is playing, else the page's main one. */
  function shortVideo(active) {
    var v = active && active.querySelector('video');
    if (v) return v;
    var all = document.querySelectorAll('video');
    for (var i = 0; i < all.length; i++) {
      if (!all[i].paused && !all[i].ended) return all[i];
    }
    return mainVideo();
  }

  function autoUnmuteShort() {
    if (cfg.route !== 'browse') return;
    if (!onShorts()) { shortUnmuteFor = ''; return; }
    var m = /^\/shorts\/([^/?#]+)/.exec(location.pathname);
    var id = m ? m[1] : '';
    if (id !== shortUnmuteFor) { shortUnmuteFor = id; resetShortUnmute(); }
    if (shortUnmuteDone) return;
    var active = document.querySelector(ACTIVE_REEL);
    if (active && active.querySelector(AD_IN_REEL)) return;
    var video = shortVideo(active);
    if (!video) return;
    if (!video.muted) {
      // playing with sound for a moment already (YouTube may mute it again right after it starts)
      if (video.currentTime > 2) shortUnmuteDone = true;
      return;
    }
    if (++shortUnmuteTries > 50) { shortUnmuteDone = true; return; } // gave up after about 15 seconds
    var player = (active && active.querySelector('.html5-video-player')) || document.querySelector('.html5-video-player');
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

  // ---- Mini player --------------------------------------------------------------
  // While the app shows the player as a small box, only the video may be seen in it. Three things keep it so:
  //  - Everything on the page except the player is hidden (visibility, so nothing moves), and the page's background
  //    is black. The page below the video (the list of a mix, the suggestions, the comments, the "next video" bar,
  //    the header) can then never show in the box, wherever it ends up, and a page that is still loading shows a
  //    black box instead of its header.
  //  - The player's container is pinned to the top-left corner of the window (position: fixed). Wherever YouTube
  //    scrolls the page or moves its header, the player stays at the same place: the box never has to chase it.
  //  - The page stays scrolled to the top. YouTube scrolls it by itself at times (when it opens the next video of a
  //    list, it brings the current item of the list into view), and it is not always the window that scrolls: any
  //    container around the player may.
  //
  // Whether the player is a small box is the app's decision, and it is asked, not told: every document the player
  // loads starts with a fresh script (this variable back at false), so a "now it is a box" message sent while the page
  // was still loading (the user left the video before it opened) used to be lost and the page showed unhidden and
  // shifted until the app measured again. The app's answer (WardTubeNative.isMini) is read on every tick and at
  // once when the script starts; setMini is kept as a faster way of saying the same thing.

  var mini = false;

  whenRoot(function () {
    if (cfg.route !== 'player') return;
    addStyle('wardtube-mini-css',
      'html[data-wt-mini],html[data-wt-mini] body{background:#000!important}' +
      'html[data-wt-mini] body *{visibility:hidden!important}' +
      'html[data-wt-mini] .html5-video-player,html[data-wt-mini] .html5-video-player *{visibility:visible!important}' +
      'html[data-wt-mini] #player-container-id{position:fixed!important;top:0!important;left:0!important;' +
      'right:auto!important;bottom:auto!important;width:100%!important;max-width:100%!important;margin:0!important;' +
      'transform:none!important;z-index:2147483646!important}');
  });

  // The app's answer to "is the player a small box right now?", or null when it cannot be asked (no bridge, old app).
  function appSaysMini() {
    try {
      var bridge = window.WardTubeNative;
      if (bridge && typeof bridge.isMini === 'function') return !!bridge.isMini();
    } catch (e) {}
    return null;
  }

  // Hides the page around the player while it is a small box. Only in the player's own WebView: the page being
  // browsed (route 'browse') is never touched.
  function markMini() {
    var root = document.documentElement;
    if (!root) return;
    var on = mini && cfg.route === 'player';
    if (on !== root.hasAttribute('data-wt-mini')) {
      if (on) root.setAttribute('data-wt-mini', ''); else root.removeAttribute('data-wt-mini');
    }
  }

  // Scrolls the window and every container around the player back to the top.
  function scrollToTop() {
    var root = document.scrollingElement || document.documentElement;
    if ((window.pageYOffset || (root && root.scrollTop) || 0) > 0) window.scrollTo(0, 0);
    var player = document.querySelector('.html5-video-player') || mainVideo();
    for (var n = player && player.parentElement; n; n = n.parentElement) {
      if (n.scrollTop > 0) n.scrollTop = 0;
    }
    if (document.body && document.body.scrollTop > 0) document.body.scrollTop = 0;
  }

  function keepTop() {
    if (cfg.route !== 'player') return;
    var asked = appSaysMini();
    if (asked !== null) mini = asked;
    markMini();
    if (mini) scrollToTop();
  }

  // The script has just started in a new document: ask at once, before the first tick.
  keepTop();
  document.addEventListener('DOMContentLoaded', keepTop);

  // ---- "Open app" button ---------------------------------------------------
  // m.youtube.com puts an "Open app" pill in the top bar of the pages (video pages included). Inside this app it only
  // gets in the way, so it is hidden. base.css hides it by the names YouTube is known to give it; this also finds it by
  // its label, in the languages YouTube is likely to be shown in, so a renamed class does not bring it back.

  var OPEN_APP_TEXTS = [
    'open app', 'open the app', 'open in app', 'open in the app', 'open youtube app',
    '\u0641\u062a\u062d \u062a\u0637\u0628\u064a\u0642', '\u0641\u062a\u062d \u0627\u0644\u062a\u0637\u0628\u064a\u0642',
    '\u0627\u0641\u062a\u062d \u0627\u0644\u062a\u0637\u0628\u064a\u0642', '\u0641\u062a\u062d \u0641\u064a \u0627\u0644\u062a\u0637\u0628\u064a\u0642',
    "ouvrir l'application", 'ouvrir l\u2019application', "ouvrir l'appli", 'ouvrir l\u2019appli',
    'abrir app', 'abrir la app', 'abrir la aplicaci\u00f3n', 'abrir aplicativo', 'abrir o app', 'abrir a aplica\u00e7\u00e3o',
    'app \u00f6ffnen', 'apri app', "apri l'app", 'open de app',
    '\u043e\u0442\u043a\u0440\u044b\u0442\u044c \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435',
    '\u30a2\u30d7\u30ea\u3092\u958b\u304f', '\u30a2\u30d7\u30ea\u3067\u958b\u304f',
    '\uc571 \uc5f4\uae30', '\uc571\uc5d0\uc11c \uc5f4\uae30',
    '\u6253\u5f00\u5e94\u7528', '\u6253\u5f00\u5e94\u7528\u7a0b\u5e8f', '\u958b\u555f\u61c9\u7528\u7a0b\u5f0f',
    'uygulamay\u0131 a\u00e7', 'buka aplikasi', 'otw\u00f3rz aplikacj\u0119', 'm\u1edf \u1ee9ng d\u1ee5ng',
    '\u0910\u092a \u0916\u094b\u0932\u0947\u0902'
  ];

  function normText(s) {
    return (s || '').replace(/[\s\u200e\u200f\u202a-\u202e]+/g, ' ').trim().toLowerCase();
  }

  function isOpenAppLabel(el) {
    var t = normText(el.textContent);
    if (t && t.length <= 40 && OPEN_APP_TEXTS.indexOf(t) >= 0) return true;
    var a = normText(el.getAttribute && el.getAttribute('aria-label'));
    return !!a && OPEN_APP_TEXTS.indexOf(a) >= 0;
  }

  var CLICKABLE = 'a, button, [role="button"]';

  function hideOpenApp() {
    try {
      var bar = document.querySelector('ytm-mobile-topbar-renderer') || document.querySelector('header');
      if (!bar) return;
      var items = bar.querySelectorAll(CLICKABLE + ', ytm-button-renderer, yt-button-shape');
      for (var i = 0; i < items.length; i++) {
        var el = items[i];
        if (el.getAttribute('data-wt-open-app') || !isOpenAppLabel(el)) continue;
        // Takes the wrappers with it as long as they hold nothing but this one button (no empty pill left behind).
        var target = el;
        var label = normText(el.textContent);
        while (target.parentElement && target.parentElement !== bar) {
          var p = target.parentElement;
          if (p.querySelectorAll(CLICKABLE).length > 1 || normText(p.textContent) !== label) break;
          target = p;
        }
        el.setAttribute('data-wt-open-app', '1');
        target.setAttribute('data-wt-open-app', '1');
        target.style.setProperty('display', 'none', 'important');
      }
    } catch (e) {}
  }

  whenRoot(function () {
    var pending = false;
    new MutationObserver(function () {
      if (pending) return;
      pending = true;
      setTimeout(function () { pending = false; hideOpenApp(); }, 100);
    }).observe(document.documentElement, { childList: true, subtree: true });
    hideOpenApp();
  });

  // ---- Timer --------------------------------------------------------------

  var ticks = 0;
  setInterval(function () {
    ticks++;
    if (ticks % 5 === 0) hideOpenApp();
    skipAd();
    autoUnmute();
    keepTop();
    skipShortsAd();
    autoUnmuteShort();
    if (ticks % 5 === 0) dropIframes();
  }, 300);

  // ---- Public API ---------------------------------------------------------

  window.__wardtube = {
    /** Next (step > 0) or previous video, for the notification buttons. */
    skip: function (step) {
      return step > 0 ? skipNext() : skipPrevious();
    },
    /** The app shows the player as a small box (true) or full screen (false). */
    setMini: function (on) {
      mini = !!on;
      keepTop();
    },
    /**
     * The app asks the page to lay itself out again. YouTube sizes its player (and the picture inside it) when the page
     * changes size or leaves full screen; when the app's picture-in-picture window takes the player out of full screen
     * while the page is still changing size, the player can stay sized for an in-between moment (part of the video cut
     * off, black under it). The same events that a real change sends make it size itself again.
     */
    relayout: function () {
      try {
        if (!document.fullscreenElement && !document.webkitFullscreenElement) {
          document.dispatchEvent(new Event('webkitfullscreenchange'));
          document.dispatchEvent(new Event('fullscreenchange'));
        }
      } catch (e) {}
      try { window.dispatchEvent(new Event('resize')); } catch (e) {}
      keepTop();
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
