// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) MWMods

/*
 * WardTube page script. Injected at document start (or right after the page starts
 * loading on WebViews without document-start scripts).
 *
 * window.__wardtubeConfig = { adblock, background, h264, baseCss, adCss } is prepended by PageScripts.kt.
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

  // ---- Prefer H.264 (optional) ----------------------------------------------
  // cfg.h264: the page claims it cannot play WebM / VP8 / VP9 / AV1, so YouTube serves H.264 video and AAC audio. A phone
  // decodes H.264 in hardware for very little power, while VP9 / AV1 often end up on the processor, which is what warms the
  // phone and drains the battery over a long video. The price: YouTube offers H.264 up to 1080p only (no 1440p / 4K).
  // YouTube's player asks what the device can play when it starts, so this has to be in place before its scripts run
  // (document start); a changed setting therefore applies from the next page load.
  // Adapted from NouTube's h264ify.ts, which is adapted from h264ify (MIT, github.com/erkserkserks/h264ify).
  if (cfg.h264 === true) {
    try {
      var BLOCKED_CODECS = ['webm', 'vp8', 'vp9', 'vp09', 'av01']; // vp09 is how VP9 is named when it comes in an mp4
      var codecBlocked = function (type) {
        if (typeof type !== 'string') return false;
        type = type.toLowerCase();
        for (var c = 0; c < BLOCKED_CODECS.length; c++) {
          if (type.indexOf(BLOCKED_CODECS[c]) !== -1) return true;
        }
        return false;
      };

      var videoProto = window.HTMLVideoElement && HTMLVideoElement.prototype;
      if (videoProto && videoProto.canPlayType) {
        var nativeCanPlayType = videoProto.canPlayType;
        videoProto.canPlayType = function (type) {
          return codecBlocked(type) ? '' : nativeCanPlayType.apply(this, arguments);
        };
      }

      if (window.MediaSource && typeof MediaSource.isTypeSupported === 'function') {
        var nativeIsTypeSupported = MediaSource.isTypeSupported.bind(MediaSource);
        MediaSource.isTypeSupported = function (type) {
          return codecBlocked(type) ? false : nativeIsTypeSupported(type);
        };
      }

      // Newer players also ask the Media Capabilities API which codec is smooth and power efficient.
      var mediaCaps = navigator.mediaCapabilities;
      if (mediaCaps && typeof mediaCaps.decodingInfo === 'function') {
        var nativeDecodingInfo = mediaCaps.decodingInfo.bind(mediaCaps);
        mediaCaps.decodingInfo = function (config) {
          var v = config && config.video, a = config && config.audio;
          if ((v && codecBlocked(v.contentType)) || (a && codecBlocked(a.contentType))) {
            return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
          }
          return nativeDecodingInfo(config);
        };
      }
    } catch (e) { /* never break the page */ }
  }

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
      nextShort(active);
    } catch (e) {}
  }

  /** Moves the Shorts page on to the next Short: its own "next" button if it has one, else the next item scrolled into view. */
  function nextShort(active) {
    var next = document.querySelector(NEXT_BTN);
    if (next) { next.click(); return; }
    var sib = active && active.nextElementSibling;
    if (sib && sib.scrollIntoView) sib.scrollIntoView({ block: 'start' });
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

  // ---- Long playback: no "Continue watching?" -------------------------------------
  // YouTube takes a page nobody touched for hours for an abandoned one: it pauses the video and asks "Continue watching?"
  // (a prompt that nobody sees when the phone is in a pocket). A long listen in the background is exactly that. Ported from
  // NouTube's background guard, in two parts:
  //  1. window._lact is YouTube's "last activity" clock. Keeping it fresh keeps the session active, so the prompt never
  //     comes; it is refreshed every minute for as long as the page lives.
  //  2. If the prompt comes anyway (the clock is only one of the things it looks at), answer it and play again. It is only
  //     answered when the video was playing a moment ago and is paused now, and only when the prompt has a single button, so
  //     a pause the person asked for, or a question with a choice in it, is never answered for them. With more buttons the
  //     video is just played again, which makes YouTube drop the prompt itself.

  setInterval(function () { window._lact = Date.now(); }, 60 * 1000);

  var CONTINUE_PROMPTS = ['ytmusic-you-there-renderer', 'ytm-confirm-dialog-renderer', 'yt-confirm-dialog-renderer'];
  var lastPlayingAt = 0;
  var lastPromptTry = 0;
  var promptTries = 0;

  function answerContinuePrompt() {
    for (var i = 0; i < CONTINUE_PROMPTS.length; i++) {
      var dialog = document.querySelector(CONTINUE_PROMPTS[i]);
      if (!dialog) continue;
      var buttons = dialog.querySelectorAll('button');
      if (buttons.length === 1) { buttons[0].click(); return true; }
      // a prompt left in the page that cannot be answered must not hide the next one
    }
    return false;
  }

  function keepWatching() {
    var video = mainVideo();
    if (!video) return;
    var now = Date.now();
    if (!video.paused && !video.ended) {
      lastPlayingAt = now;
      promptTries = 0;
      return;
    }
    // paused: only a pause that followed playing within the last half minute can be the prompt's
    if (video.ended || now - lastPlayingAt > 30000 || now - lastPromptTry < 2000 || promptTries >= 3) return;
    var dialog = null;
    for (var i = 0; i < CONTINUE_PROMPTS.length && !dialog; i++) dialog = document.querySelector(CONTINUE_PROMPTS[i]);
    if (!dialog) return;
    lastPromptTry = now;
    promptTries++;
    answerContinuePrompt();
    try {
      var player = document.getElementById('movie_player');
      if (player && player.playVideo) player.playVideo();
      else video.play();
    } catch (e) {}
  }

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

  // ---- Original audio track (player) ------------------------------------------------
  // YouTube's automatic dubbing gives a video extra audio tracks and may start the one in the viewer's language (Arabic, for
  // one) instead of the voice the video was made with. As soon as a video's tracks are known, the one YouTube labels
  // "original" is chosen. It is done once per video, so a track picked by hand in YouTube's menu stays picked.
  // The label is what YouTube writes in each interface language: the list comes from NouTube (audio.ts), which took it from
  // github.com/Bilalkamal/YouTube-Original-Audio-Track. A track's name is found the way NouTube finds it: the track object
  // keeps its details one level down, under a key that changes between YouTube builds, so look for the one that has a name
  // and an isDefault.

  var ORIGINAL_LABELS = [
    'original', 'originale', 'оригинал', 'オリジナル', '원본', '原始', 'أصلي', 'मूल', 'মূল', 'asli', 'orijinal', 'nguyên bản',
    'ต้นฉบับ', 'origineel', 'oryginalny', 'alkuperäinen', 'originál', 'πρωτότυπο', 'מקורי', 'eredeti', 'оригінал',
    'اصلی', 'asal', 'orihinal', 'அசல்', 'اصل', 'izvorni', 'оригинален', 'izvirnik', 'originalus', 'oriģināls',
    'originaal', 'ორიგინალი', 'asl', 'түпнұсқа', 'բնօրինակ', 'ដើម', 'မူရ်း', 'මුල්', 'የመጀመሪያው', 'मौलिक', 'ຕົ້ນສະບັບ',
    'эх', 'དངོས་གཞི།', 'gwreiddiol', 'originala', 'jatorrizko', 'orixinal', 'મૂળ', 'asali', 'izugbe', 'upprunalegt',
    'ಮೂಲ', 'orîjînal', 'түп нуска', 'taketake', 'യഥാർത്ഥ', 'मूळ', 'oriġinali', 'ମୌଳିକ', 'ਅਸਲ', 'اصلي', 'muamua',
    'chaicho', 'asalka ah', 'origjinal', 'asili', 'అసలు', 'asyl', 'оригиналь', 'ئەسلى', 'אריגינעל', 'atilẹba',
    'okwangempela', 'oorspronklik', 'originalni', 'uriginale', 'oarspronklik', 'bunaidh', 'tùsail', 'kumu', 'thawj',
    'gwredhek', 'originalis', 'ya liboso', 'tany am-boalohany', 'yoyamba', 'qallariy', 'cy\'umwimerere', 'ea pele',
    'аслӣ', 'ʻuluaki', 'njëkk', 'yokuqala'
  ];

  function audioTrackName(track) {
    try {
      var keys = Object.keys(track || {});
      for (var i = 0; i < keys.length; i++) {
        var v = track[keys[i]];
        if (v && typeof v === 'object' && 'isDefault' in v && v.name) return String(v.name);
      }
    } catch (e) {}
    return '';
  }

  function isOriginalName(name) {
    name = name.toLowerCase();
    if (!name) return false;
    for (var i = 0; i < ORIGINAL_LABELS.length; i++) {
      if (name.indexOf(ORIGINAL_LABELS[i]) !== -1) return true;
    }
    return false;
  }

  var audioFor = '';  // the video id the two values below belong to
  var audioDone = false;
  var audioTries = 0;

  function autoOriginalAudio() {
    if (cfg.route !== 'player') return;
    var id = currentId();
    if (id !== audioFor) { audioFor = id; audioDone = false; audioTries = 0; }
    if (audioDone || !id) return;
    if (++audioTries > 100) { audioDone = true; return; } // gave up after about 30 seconds
    var player = document.getElementById('movie_player');
    if (!player || !player.getAvailableAudioTracks || !player.setAudioTrack || adShowing()) return;
    try {
      var tracks = player.getAvailableAudioTracks() || [];
      if (tracks.length < 2) return; // one track, or the tracks are not known yet: nothing to choose from
      var original = null;
      for (var i = 0; i < tracks.length; i++) {
        if (isOriginalName(audioTrackName(tracks[i]))) { original = tracks[i]; break; }
      }
      audioDone = true;
      if (!original) return;
      var current = player.getAudioTrack ? player.getAudioTrack() : null;
      if (current && audioTrackName(current) === audioTrackName(original)) return; // already on it
      player.setAudioTrack(original);
    } catch (e) {}
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
      'transform:none!important;z-index:2147483646!important}' +
      // The picture in picture window (see "Picture in picture" below). Every element from the video up to the page's body
      // is pinned to the top-left corner at the page's width and at the height the app's window has (--wt-pip-aspect is
      // its width / height; the height is never more than the page's, PlayerHost.applyPip fits the very same box), and the
      // video fills that box whatever size YouTube's own inline styles give it.
      'html[data-wt-pip] [data-wt-pip-box]{position:fixed!important;top:0!important;left:0!important;right:auto!important;' +
      'bottom:auto!important;width:100vw!important;min-width:0!important;max-width:none!important;' +
      'height:min(calc(100vw / var(--wt-pip-aspect,1.7778)),100vh)!important;min-height:0!important;max-height:none!important;' +
      'margin:0!important;padding:0!important;border:0!important;transform:none!important;contain:none!important;' +
      'overflow:hidden!important;background:#000!important}' +
      'html[data-wt-pip] video{position:absolute!important;top:0!important;left:0!important;right:auto!important;' +
      'bottom:auto!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;' +
      'margin:0!important;transform:none!important;object-fit:contain!important}' +
      // The system draws the window's own buttons; a tap on the window brings the app back, so the page's controls go.
      'html[data-wt-pip] #player-control-overlay,html[data-wt-pip] .player-controls-background,' +
      'html[data-wt-pip] .ytp-chrome-top,html[data-wt-pip] .ytp-chrome-bottom,html[data-wt-pip] .ytp-gradient-top,' +
      'html[data-wt-pip] .ytp-gradient-bottom{display:none!important}');
  });

  // The app's answer to "is the player a small box right now?", or null when it cannot be asked (no bridge, old app).
  function appSaysMini() {
    try {
      var bridge = window.WardTubeNative;
      if (bridge && typeof bridge.isMini === 'function') return !!bridge.isMini();
    } catch (e) {}
    return null;
  }

  // ---- Picture in picture --------------------------------------------------------
  // In the system's little window the app used to MEASURE where the player's box ended up and fit the WebView to what it
  // found, while YouTube was still laying the page out (leaving full screen, a new viewport, the next video opening). What
  // it measured depended on the moment, and timers (settle, nudge, distrust windows) tried to catch up with it.
  // Now it is the other way round: the app decides the shape (the window's width / height, asked through
  // WardTubeNative.pipAspect, 0 outside the window), and the page is made to fit it. Every element from the video up to
  // the body is marked (data-wt-pip-box, an attribute: YouTube rewrites classes, not unknown attributes) and the CSS above
  // pins them to the top-left corner at the page's width and at width / aspect high; the video fills the box, letterboxed
  // by object-fit. The box is at the same place and the same shape whatever size YouTube gave its player, so nothing
  // is measured and nothing has to be waited for. The page's own layout size is never touched, so when the window is left
  // and the marks go, YouTube's player is exactly as it was.

  var pipAspect = 0;
  var PIP_BOX = 'data-wt-pip-box';

  // The app's answer to "is the window the picture in picture one, and what shape is it?", or null when it cannot be asked.
  function appSaysPipAspect() {
    try {
      var bridge = window.WardTubeNative;
      if (bridge && typeof bridge.pipAspect === 'function') {
        var a = Number(bridge.pipAspect());
        return isFinite(a) && a > 0 ? a : 0;
      }
    } catch (e) {}
    return null;
  }

  // Marks the video's ancestors (up to, not including, the body) and unmarks what was marked before and no longer holds it
  // (YouTube swapped the video element or its wrappers).
  function markPipBoxes(on) {
    var keep = [];
    var video = on ? mainVideo() : null;
    for (var n = video && video.parentElement; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
      keep.push(n);
    }
    var old = document.querySelectorAll('[' + PIP_BOX + ']');
    for (var i = 0; i < old.length; i++) {
      if (keep.indexOf(old[i]) < 0) old[i].removeAttribute(PIP_BOX);
    }
    for (var j = 0; j < keep.length; j++) {
      if (!keep[j].hasAttribute(PIP_BOX)) keep[j].setAttribute(PIP_BOX, '');
    }
  }

  function markPip() {
    var root = document.documentElement;
    if (!root) return;
    var on = pipAspect > 0 && mini && cfg.route === 'player';
    if (on) {
      var value = String(pipAspect);
      if (root.style.getPropertyValue('--wt-pip-aspect') !== value) root.style.setProperty('--wt-pip-aspect', value);
      if (!root.hasAttribute('data-wt-pip')) root.setAttribute('data-wt-pip', '');
      markPipBoxes(true);
    } else if (root.hasAttribute('data-wt-pip')) {
      root.removeAttribute('data-wt-pip');
      root.style.removeProperty('--wt-pip-aspect');
      markPipBoxes(false);
      // Back to the floating box (the full-screen player is told by markMini, which kicks the layout itself): YouTube's
      // player is sized for the page again, and is told the page changed.
      if (mini && cfg.route === 'player') kickLayout();
      if (cfg.route === 'player') afterPipExit();
    }
  }

  // Hides the page around the player while it is a small box. Only in the player's own WebView: the page being
  // browsed (route 'browse') is never touched.
  function markMini() {
    var root = document.documentElement;
    if (!root) return;
    var on = mini && cfg.route === 'player';
    if (on !== root.hasAttribute('data-wt-mini')) {
      if (on) {
        root.setAttribute('data-wt-mini', '');
      } else {
        root.removeAttribute('data-wt-mini');
        kickLayout();
      }
    }
  }

  // The page was a small box (the video pinned to the corner by the rules above) and is not any more: YouTube sized its
  // player while the box was pinned, and only sizes it again when the page tells it that the window changed, which
  // nothing did here. Without it the video could show for a moment after the player came back (from the picture in
  // picture window, most of all) and then go black. A few resize events, the first once the page is laid out again and the
  // others while YouTube may still be opening the next video.
  function kickLayout() {
    var fire = function () {
      try { window.dispatchEvent(new Event('resize')); } catch (e) {}
    };
    setTimeout(fire, 50);
    setTimeout(fire, 400);
    setTimeout(fire, 1200);
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

  // ---- The list's sheet opening while the player is a small box ------------------
  // In a mix YouTube sometimes opens the list's sheet (the title with the shuffle and repeat buttons, and the videos)
  // over the page by itself. In the full-screen player that only gets in the way; in the small box and the picture in
  // picture window it can leave the video black. Nothing in the app opens it, and nobody can tap it while the page is
  // hidden behind the box, so while the player is a box a sheet that is open is closed again. The sheet is found by what
  // it holds (the links of the list's videos in a layer above the page, one that is not the page around the player),
  // not by YouTube's element names, and its close button by its label. If nothing matches, nothing is done.

  var CLOSE_LABEL = /close|dismiss|إغلاق|اغلاق|إخفاء|fermer|cerrar|schlie|chiudi|fechar|закры|閉じ|关闭|關閉|닫기|kapat/i;
  var lastSheetLook = 0;
  var lastSheetClose = 0;

  // The layer above the page that holds [el] (a fixed-position box or a dialog), or null when it is just in the page.
  function overlayOf(el) {
    for (var n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
      var pos = '';
      try { pos = getComputedStyle(n).position; } catch (e) {}
      if (pos === 'fixed' || n.getAttribute('role') === 'dialog' || n.tagName === 'DIALOG') return n;
    }
    return null;
  }

  function closeListSheet() {
    var now = Date.now();
    if (now - lastSheetLook < 600) return false;
    lastSheetLook = now;
    var list = currentList();
    if (!list) return false;
    var video = mainVideo();
    var items = panelItems(list);
    for (var i = 0; i < items.length; i++) {
      var sheet = overlayOf(items[i].link);
      if (!sheet || (video && sheet.contains(video))) continue; // in the page, or the page's own fixed frame
      var r = sheet.getBoundingClientRect();
      // Open and on screen (closed sheets are display:none or pushed out of the window).
      if (r.width < 100 || r.height < 100 || r.bottom <= 0 || r.top >= window.innerHeight ||
          r.right <= 0 || r.left >= window.innerWidth) continue;
      var buttons = sheet.querySelectorAll('button,[role="button"]');
      for (var j = 0; j < buttons.length; j++) {
        var b = buttons[j];
        var label = (b.getAttribute('aria-label') || b.getAttribute('title') || '') + ' ' + (b.textContent || '').slice(0, 30);
        if (CLOSE_LABEL.test(label)) {
          if (now - lastSheetClose < 1500) return false; // one try at a time: give YouTube time to close it
          lastSheetClose = now;
          b.click();
          return true;
        }
      }
    }
    return false;
  }

  function keepTop() {
    if (cfg.route !== 'player') return;
    var asked = appSaysMini();
    if (asked !== null) mini = asked;
    var shape = appSaysPipAspect();
    if (shape !== null) pipAspect = shape;
    markMini();
    markPip();
    if (mini) {
      scrollToTop();
      try { closeListSheet(); } catch (e) {}
    }
  }

  // The script has just started in a new document: ask at once, before the first tick.
  keepTop();
  document.addEventListener('DOMContentLoaded', keepTop);

  // ---- Picture in picture: what the page tells the app ---------------------------
  // Ported from NouTube's player injection (content/picture-in-picture.ts). Two parts, only in the player's own page:
  //  1. The page tells the app AT ONCE whether a picture that the little window could show is playing, and how big it is
  //     (WardTubeNative.setPipVideo; 0 x 0 = nothing to show). The app used to learn it only from its own poll, which runs
  //     every 3 seconds while the video is full screen: leaving the app within those seconds of pressing play found the
  //     window not armed (no window at all), and leaving just after pausing still opened a window with a paused video.
  //     NouTube does the same: the page reports on every play / pause / end / resize and the app arms itself from that.
  //  2. The page's OUTER window size is kept at the screen's size. YouTube compares the outer window with the screen to
  //     tell that it is in the little window (or in the background) and pauses the video there. The WebView reports its
  //     own size as the outer size, so while the window shrank (a moment before the app lays the page out at full size
  //     again) the player stopped by itself and the app had to start it again. innerWidth / innerHeight are left alone:
  //     they are the real viewport, and the layout depends on them.
  if (cfg.route === 'player') {
    try {
      var keepOuterSize = function (key, screenKey) {
        var real = Object.getOwnPropertyDescriptor(window, key);
        Object.defineProperty(window, key, {
          configurable: true,
          enumerable: true,
          get: function () {
            var own = 0, screenSize = 0;
            try { own = real && real.get ? real.get.call(window) : (real ? real.value : 0); } catch (e) {}
            try { screenSize = window.screen[screenKey] || 0; } catch (e) {}
            return Math.max(own || 0, screenSize);
          }
        });
      };
      keepOuterSize('outerWidth', 'width');
      keepOuterSize('outerHeight', 'height');
    } catch (e) { /* never break the page */ }

    var pipReported = '';
    var reportPipVideo = function () {
      var w = 0, h = 0;
      try {
        var v = currentVideo();
        if (v && !v.paused && !v.ended && v.videoWidth > 0 && v.videoHeight > 0) {
          w = v.videoWidth;
          h = v.videoHeight;
        }
      } catch (e) {}
      var key = w + 'x' + h;
      if (key === pipReported) return;
      try {
        var bridge = window.WardTubeNative;
        // An app without it keeps working: it still has its own poll.
        if (!bridge || typeof bridge.setPipVideo !== 'function') return;
        bridge.setPipVideo(w, h);
        pipReported = key;
      } catch (e) {}
    };
    // Media events do not bubble, and YouTube swaps the video element around: listen for them on the way down.
    ['play', 'playing', 'pause', 'ended', 'emptied', 'loadedmetadata', 'resize'].forEach(function (type) {
      document.addEventListener(type, reportPipVideo, true);
    });
    ['yt-navigate-finish', 'popstate'].forEach(function (type) {
      window.addEventListener(type, reportPipVideo);
    });
    document.addEventListener('DOMContentLoaded', reportPipVideo);
  }

  // ---- Picture in picture: a pause that nobody asked for is undone ---------------------------------
  // Ported from NouTube's background guard (content/background-guard.ts). The little window cannot be touched, so a pause
  // that arrives while it is up did not come from the person: YouTube's page pauses a video by itself when the window
  // shrinks or changes shape (its own checks on the viewport, the visibility, a "Continue watching?" prompt). The app
  // used to start the video again only for the first 3 seconds after the window came, and only when the video had been
  // in full screen: after that, and from the normal watch page, the video simply stopped after a few seconds.
  //   - pipPlaying: the video has been playing and nobody has paused it on purpose since.
  //   - pipUserPaused: the app paused it on purpose (the window's button, the notification, a headset, the sleep timer):
  //     PlayerController tells the page through window.__wardtube.wantPlay, and such a pause is never fought.
  // Only while the app says the window is up (pipAspect > 0). At most 3 tries in a row (the count starts again when the
  // video is seen playing, or after 20 seconds), and none while a call is on (WardTubeNative.canAutoResume).
  var pipPlaying = false;
  var pipUserPaused = false;
  var pipTries = 0;
  var pipTryAt = 0;
  var pipNavUntil = 0;

  function pipGuardTick() {
    try {
      if (!(pipAspect > 0)) { pipTries = 0; return; }
      var v = currentVideo();
      if (!v) return;
      if (!v.paused) { pipPlaying = true; pipTries = 0; return; }
      if (v.ended || !pipPlaying || pipUserPaused) return;
      var now = Date.now();
      if (now - pipTryAt > 20000) pipTries = 0;
      if (now < pipNavUntil || now - pipTryAt < 1200 || pipTries >= 3) return;
      var bridge = window.WardTubeNative;
      if (bridge && typeof bridge.canAutoResume === 'function' && !bridge.canAutoResume()) return;
      pipTryAt = now;
      pipTries++;
      answerContinuePrompt();
      var player = document.getElementById('movie_player');
      if (player && typeof player.playVideo === 'function') player.playVideo();
      var played = v.play();
      if (played && typeof played.catch === 'function') played.catch(function () {});
    } catch (e) { /* never break the page */ }
  }

  if (cfg.route === 'player') {
    // A pause is looked at right away (not only at the next tick); the short wait lets a pause that YouTube follows with
    // its own play settle first.
    document.addEventListener('pause', function (e) {
      if (pipAspect > 0 && e.target && e.target.tagName === 'VIDEO') setTimeout(pipGuardTick, 300);
    }, true);
    ['play', 'playing'].forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (e.target && e.target.tagName === 'VIDEO') { pipPlaying = true; pipUserPaused = false; }
      }, true);
    });
    document.addEventListener('ended', function (e) {
      if (e.target && e.target.tagName === 'VIDEO') pipPlaying = false;
    }, true);
    // Next / previous from the window: the old video pauses while the page changes over, that is not a pause to undo.
    window.addEventListener('yt-navigate-start', function () { pipNavUntil = Date.now() + 1500; });
    setInterval(pipGuardTick, 1000);
  }

  // ---- After the picture in picture window: the picture is looked after until it is sure to show --------------------
  // Leaving the window used to leave the video black now and then: it showed for a moment, then the picture was gone and the
  // sound went on. Three things, in the order NouTube's setPictureInPicture(false) does them (content/picture-in-picture.ts)
  // and one more that it does not need:
  //  1. At once: the window's rules are gone (markPip). The app held the window's picture until its window had its size
  //     back (PlayerHost.exitHolding), so the page now sees ONE real change of size, to its final one.
  //  2. A short while later (NouTube waits 500 ms, "the full-size viewport settles"): YouTube is told the viewport changed
  //     (resize) and a video that was playing and was paused by the page meanwhile is played again (never one the person
  //     or the app paused on purpose, see pipUserPaused).
  //  3. The picture's layer is nudged (a harmless will-change toggle makes the browser build the video's layer again).
  //  4. For a few seconds the picture is WATCHED: a video that plays (its time moves) while the number of pictures shown
  //     does not move is a black one. It is brought back step by step: a seek to the same place, then the video element taken
  //     out of the layout and put back, and only if it is still black, the page is opened again at the same second.
  var exitWatch = null;

  function pokeVideoLayer() {
    try {
      var v = currentVideo();
      if (!v || !v.style) return;
      var old = v.style.willChange;
      v.style.willChange = 'transform';
      setTimeout(function () { try { v.style.willChange = old || ''; } catch (e) {} }, 120);
    } catch (e) {}
  }

  function fireResize() {
    try { window.dispatchEvent(new Event('resize')); } catch (e) {}
  }

  function afterPipExit() {
    var wasPlaying = false;
    try {
      var v = currentVideo();
      wasPlaying = !!(v && !v.paused && !v.ended) || !!(pipPlaying && !pipUserPaused);
    } catch (e) {}
    exitWatch = { until: Date.now() + 14000, frames: -1, time: -1, stalledAt: 0, step: 0 };
    setTimeout(pokeVideoLayer, 150);
    setTimeout(function () {
      if (pipAspect > 0) return; // back in the window meanwhile
      fireResize();
      pokeVideoLayer();
      try {
        var v = currentVideo();
        if (wasPlaying && !pipUserPaused && v && v.paused && !v.ended) {
          var player = document.getElementById('movie_player');
          if (player && typeof player.playVideo === 'function') player.playVideo();
          else { var played = v.play(); if (played && typeof played.catch === 'function') played.catch(function () {}); }
        }
      } catch (e) {}
    }, 500);
    setTimeout(function () { if (pipAspect <= 0) fireResize(); }, 1500);
  }

  function reopenAt(seconds) {
    try {
      var u = new URL(location.href);
      if (seconds >= 3) u.searchParams.set('t', Math.floor(seconds) + 's');
      location.replace(u.href);
    } catch (e) {}
  }

  function recoverPicture(v) {
    var step = exitWatch.step++;
    var player = document.getElementById('movie_player');
    if (step === 0) {
      fireResize();
      if (player && typeof player.seekTo === 'function' && typeof player.getCurrentTime === 'function') {
        player.seekTo(player.getCurrentTime(), true);
      } else {
        v.currentTime = v.currentTime;
      }
    } else if (step === 1) {
      var shown = v.style.display;
      v.style.display = 'none';
      void v.offsetHeight; // the layout notices the video is gone, so the layer is built again when it comes back
      v.style.display = shown;
      fireResize();
      pokeVideoLayer();
    } else if (step === 2) {
      reopenAt(v.currentTime || 0);
    }
  }

  function renderTick() {
    var w = exitWatch;
    if (!w) return;
    var now = Date.now();
    if (now > w.until || pipAspect > 0) { exitWatch = null; return; }
    try {
      var v = currentVideo();
      // Only a video that plays and has a picture to show is looked at (a paused, ended, buffering or seeking one is not black).
      if (!v || v.paused || v.ended || v.seeking || v.readyState < 3 || !(v.videoWidth > 0) ||
          typeof v.getVideoPlaybackQuality !== 'function') {
        w.stalledAt = 0;
        w.frames = -1;
        return;
      }
      var frames = v.getVideoPlaybackQuality().totalVideoFrames;
      var t = v.currentTime;
      if (w.frames < 0 || frames > w.frames) { // pictures are being shown
        w.frames = frames;
        w.time = t;
        w.stalledAt = 0;
        return;
      }
      if (t - w.time < 0.5) return; // the time does not move either: that is buffering, not a black picture
      if (!w.stalledAt) w.stalledAt = now;
      if (now - w.stalledAt < 1500) return;
      recoverPicture(v);
      w.stalledAt = now;
      w.frames = frames;
      w.time = t;
    } catch (e) { /* never break the page */ }
  }

  if (cfg.route === 'player') setInterval(renderTick, 500);

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

  // ---- "Open app" row in YouTube's menus -------------------------------------
  // The menu that a long press on a Short opens (and some other menus of the page) has an "Open app" row, which only
  // sends the person out of the app. The row is found by its label (the same list as the button above, plus the
  // English and Arabic wordings that name the YouTube app) and hidden; the other rows stay as they are.

  var MENU_ROWS = 'ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, yt-list-item-view-model, [role="menuitem"]';
  var MENU_SHEETS = 'ytm-bottom-sheet-renderer, bottom-sheet-layout, ytm-menu-popup-renderer, yt-contextual-sheet-layout, ' +
    'yt-sheet-view-model, [role="dialog"], [role="menu"]';
  var MENU_ANY = MENU_ROWS + ', ' + MENU_SHEETS;

  function isOpenAppRow(text) {
    var t = normText(text);
    if (!t || t.length > 40) return false;
    if (OPEN_APP_TEXTS.indexOf(t) >= 0) return true;
    if (/^open (in )?(the )?(youtube )?app$/.test(t)) return true; // "Open in YouTube app", "Open the app" ...
    // Arabic: the words for "open" and "app" together in one short label (e.g. "open in the YouTube app").
    return t.indexOf('\u0641\u062a\u062d') >= 0 && t.indexOf('\u062a\u0637\u0628\u064a\u0642') >= 0;
  }

  function hideNode(node) {
    node.setAttribute('data-wt-open-app', '1');
    node.style.setProperty('display', 'none', 'important');
  }

  function hideOpenAppRows() {
    try {
      var rows = document.querySelectorAll(MENU_ROWS);
      for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        if (row.getAttribute('data-wt-open-app') || row.closest('[data-wt-open-app]')) continue;
        if (!isOpenAppRow(row.textContent) && !isOpenAppRow(row.getAttribute('aria-label'))) continue;
        var target = row; // a row can be a custom element wrapped in another one: the outer one goes
        while (target.parentElement && target.parentElement.matches(MENU_ROWS)) target = target.parentElement;
        hideNode(target);
      }
      // A row of a kind this does not know: a button or link inside an open menu that says it.
      var sheets = document.querySelectorAll(MENU_SHEETS);
      for (var k = 0; k < sheets.length; k++) {
        var sheet = sheets[k];
        var els = sheet.querySelectorAll(CLICKABLE);
        for (var j = 0; j < els.length; j++) {
          var el = els[j];
          if (el.getAttribute('data-wt-open-app') || el.closest('[data-wt-open-app]')) continue;
          if (!isOpenAppRow(el.textContent) && !isOpenAppRow(el.getAttribute('aria-label'))) continue;
          var t2 = el; // the wrappers go with it as long as they hold nothing but this one button
          while (t2.parentElement && t2.parentElement !== sheet) {
            var p = t2.parentElement;
            if (p.querySelectorAll(CLICKABLE).length > 1 || !isOpenAppRow(p.textContent)) break;
            t2 = p;
          }
          hideNode(t2);
        }
      }
    } catch (e) {}
  }

  whenRoot(function () {
    var queued = false;
    var lastRun = 0;
    // Before the next paint where it can be (so the row is not seen), but not more than every 100 ms.
    function run() {
      if (!queued) return;
      queued = false;
      lastRun = Date.now();
      hideOpenAppRows();
    }
    function queue() {
      if (queued) return;
      queued = true;
      var wait = Math.max(0, 100 - (Date.now() - lastRun));
      if (wait === 0) requestAnimationFrame(run);
      setTimeout(run, wait === 0 ? 50 : wait);
    }
    // Only a change inside a menu, or one that brings a menu in, is looked at.
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length && i < 200; i++) {
        var r = records[i], t = r.target;
        if (t && t.nodeType === 1 && t.closest && t.closest(MENU_ANY)) { queue(); return; }
        var add = r.addedNodes;
        for (var j = 0; j < add.length; j++) {
          var n = add[j];
          if (n.nodeType === 1 && (n.matches(MENU_ANY) || n.querySelector(MENU_ANY))) { queue(); return; }
        }
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
    hideOpenAppRows();
  });

  // ---- Picture in picture: a button in the player ---------------------------------
  // A round button on the player that shrinks the app into the system's little window now, besides the window that opens by
  // itself when the person leaves with Home (WardTubeNative.enterPip; MainActivity.enterPipFromButton does the rest). It is put
  // on the player the way NouTube puts its full-screen controls button (content/fullscreen-controls.ts, content/css.txt):
  //  - a direct child of #player-container-id, the element the page's full screen is made of, NEXT TO the control overlay and
  //    not inside it: YouTube's control layers hit-test above anything put into the overlay, so a button in there is seen but
  //    never gets the tap. Being in the full-screen element, it is part of the full-screen picture too.
  //  - shown only while the page's controls are (the overlay's "fadein" class), so it is never left burned over the video.
  //    Until that class has been seen once in this page (a YouTube that names it differently) it shows for a few seconds after
  //    each touch on the player instead.
  //  - on the edge, at the middle of the video's height: clear of the centred play / seek buttons and of the cluster at the
  //    top. In full screen it sits higher, because on a phone turned sideways the camera is on a side edge at the middle height.
  // The taps on it never reach the page (they would toggle its controls or seek). It is built with createElementNS and
  // setAttribute only: YouTube's pages enforce Trusted Types, which refuses an innerHTML string. The small box and the window
  // never show it (the rules below), and a phone without picture in picture never gets it.

  var PIP_BTN_ID = 'wardtube-pip-btn';
  // The same picture-in-picture drawing as the app's menu (res/drawable/ic_menu_pip.xml).
  var PIP_BTN_PATH = 'M21,3H3C1.9,3 1,3.9 1,5v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3zM21,19.01H3V4.98h18V19.01zM19,11h-8v6h8V11z';
  var PIP_BTN_TOUCH_MS = 3500;
  var pipBtn = null;
  var pipBtnOk = null;      // does the app offer it? asked once the bridge is there
  var pipFadeSeen = false;  // the overlay's "fadein" class has been seen in this page: it says when the controls show
  var pipTouchAt = 0;

  whenRoot(function () {
    if (cfg.route !== 'player') return;
    var id = '#' + PIP_BTN_ID;
    addStyle('wardtube-pip-btn-css',
      id + '{display:flex;position:absolute;top:50%;right:12px;left:auto;z-index:2147483646;align-items:center;' +
      'justify-content:center;width:40px;height:40px;margin:-20px 0 0;padding:0;border:0;border-radius:50%;' +
      'background:rgba(0,0,0,.55);opacity:0;pointer-events:none;transition:opacity .2s;' +
      '-webkit-tap-highlight-color:transparent;touch-action:manipulation}' +
      id + '.wt-show{opacity:1;pointer-events:auto}' +
      id + ':active{background:rgba(255,255,255,.3)}' +
      id + ' svg{width:24px;height:24px;pointer-events:none}' +
      // Full screen: higher than the middle (the camera of a phone turned sideways), and clear of the display's cut-out.
      '#player-container-id:fullscreen>' + id + '{top:30%;margin:0;right:calc(16px + env(safe-area-inset-right,0px))}' +
      '#player-container-id:-webkit-full-screen>' + id + '{top:30%;margin:0;right:calc(16px + env(safe-area-inset-right,0px))}' +
      // The small box and the little window have buttons of their own.
      'html[data-wt-mini] ' + id + ',html[data-wt-pip] ' + id + '{display:none!important}');
  });

  // May the button be shown? The app says so: it has the method and the phone has picture in picture.
  function pipButtonAllowed() {
    if (pipBtnOk !== null) return pipBtnOk;
    try {
      var bridge = window.WardTubeNative;
      if (!bridge) return false; // the bridge may be a moment behind the script: asked again at the next tick
      pipBtnOk = typeof bridge.enterPip === 'function' &&
        (typeof bridge.pipSupported !== 'function' || !!bridge.pipSupported());
    } catch (e) { return false; }
    return pipBtnOk;
  }

  function makePipButton() {
    var ns = 'http://www.w3.org/2000/svg';
    var btn = document.createElement('button');
    btn.id = PIP_BTN_ID;
    btn.type = 'button';
    var lang = (document.documentElement.lang || navigator.language || '').toLowerCase();
    var label = lang.indexOf('ar') === 0 ? 'صورة داخل صورة' : 'Picture in picture';
    btn.setAttribute('aria-label', label);
    btn.setAttribute('title', label);
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    var path = document.createElementNS(ns, 'path');
    path.setAttribute('d', PIP_BTN_PATH);
    path.setAttribute('fill', '#fff');
    svg.appendChild(path);
    btn.appendChild(svg);
    // Nothing done on the button is seen by the page: a tap would toggle its controls, or count as a double tap that seeks.
    ['touchstart', 'touchend', 'touchmove', 'pointerdown', 'pointerup', 'mousedown', 'mouseup', 'dblclick'].forEach(function (type) {
      btn.addEventListener(type, function (e) { e.stopPropagation(); });
    });
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      e.preventDefault();
      try {
        var bridge = window.WardTubeNative;
        if (bridge && typeof bridge.enterPip === 'function') bridge.enterPip();
      } catch (err) {}
    });
    return btn;
  }

  // Keeps the button on the player (YouTube rebuilds its player and controls on every video) and shows or hides it with the
  // page's controls. Called on every tick and right after a touch on the player.
  function syncPipButton() {
    if (cfg.route !== 'player') return;
    try {
      var host = document.getElementById('player-container-id');
      if (!host || !pipButtonAllowed()) return;
      if (!pipBtn) pipBtn = makePipButton();
      if (pipBtn.parentNode !== host) {
        // The button is placed inside this box: it has to be a positioned one.
        var position = '';
        try { position = getComputedStyle(host).position; } catch (e) {}
        if (position === 'static') host.style.setProperty('position', 'relative');
        host.appendChild(pipBtn);
      }
      var overlay = document.getElementById('player-control-overlay');
      var controlsShown = !!overlay && overlay.classList.contains('fadein');
      if (controlsShown) pipFadeSeen = true;
      pipBtn.classList.toggle('wt-show', pipFadeSeen ? controlsShown : Date.now() - pipTouchAt < PIP_BTN_TOUCH_MS);
    } catch (e) { /* never break the page */ }
  }

  if (cfg.route === 'player') {
    // A touch on the player: the controls are about to show (or hide), so look at once instead of at the next tick.
    document.addEventListener('touchstart', function (e) {
      try {
        var t = e.touches && e.touches[0];
        var host = document.getElementById('player-container-id');
        if (!t || !host) return;
        var r = host.getBoundingClientRect();
        if (t.clientX < r.left || t.clientX > r.right || t.clientY < r.top || t.clientY > r.bottom) return;
        pipTouchAt = Date.now();
        setTimeout(syncPipButton, 80);
      } catch (err) {}
    }, { capture: true, passive: true });
  }

  // ---- A floating "Download" button over a video's three-dots menu ------------------
  // The fallback for the "Download video" row that menu-download.js puts in the menu itself (the first way into a download from
  // a card, the one the person sees in the menu): the button stays away while that row is in sight, and shows only when the row
  // could not be put in (a menu of a kind that script does not know). The long press (web/VideoLongPress.kt) is the other way. A tap on the three dots of a
  // video's card makes YouTube open its own menu (a sheet over the page); while that menu is open, a "Download" button floats just
  // above it. A tap on the button asks the app for the very sheet that a long press on the card opens (WardTubeNative.
  // downloadFromMenu -> VideoLongPress.openFromPage: the video, "Download video", share, copy the link), and the page's menu is
  // closed behind it. It is on the browsing page and on the player's page (the list under the video) alike, and never anywhere
  // else: it shows only after a tap on a card's three dots AND only if a menu really opened because of that tap.
  //  - The three dots are found by what they are: a button inside a video card that is not the card's picture / title link, and
  //    that is YouTube's menu button (its element or class names, its label in a few languages, or just an icon-only small button).
  //    A button that matches by mistake costs nothing: no menu opens, so no button shows.
  //  - A menu "opened" when menu rows or a sheet that were not visible at the tap are visible after it (MENU_ROWS / MENU_SHEETS, the
  //    ones the "Open app" row code above already relies on). The button then waits until the menu has stopped sliding in, sits
  //    above its first row, follows it if it moves, and goes when the menu is gone, when a row or button of the page is tapped
  //    (the menu is used up, or turns into another panel such as Share), or when the page navigates.
  //  - The video is the card's own link (a watch page, a Short, a live page, youtu.be), the title is the card's heading. The app
  //    checks the link again (VideoLinks.parse) because any frame of a page can call the bridge.
  // The button is built with createElement / createElementNS and setAttribute only (Trusted Types refuses an innerHTML string),
  // and it is added to <html> itself, not <body>, so nothing the page does to its body (inert, a lock on scrolling) reaches it.
  // The touches on it never reach the page.

  var DL_BTN_ID = 'wardtube-dl-btn';
  // The same as the cards of VideoLongPress.TITLE_JS: one video's card on any of YouTube's layouts.
  var DL_CARDS = 'ytm-media-item,ytm-compact-video-renderer,ytm-video-card-renderer,ytm-playlist-video-renderer,' +
    'ytm-rich-item-renderer,ytm-video-with-context-renderer,ytm-grid-video-renderer,ytm-reel-item-renderer,' +
    'ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,yt-lockup-view-model,' +
    'ytd-video-renderer,ytd-grid-video-renderer,ytd-compact-video-renderer,ytd-playlist-video-renderer';
  var DL_HEADINGS = 'h3,h4,#video-title,.media-item-headline,.yt-lockup-metadata-view-model__title,.title';
  var DL_DOTS = 'ytm-menu,ytm-menu-renderer,.media-item-menu,.yt-lockup-metadata-view-model__menu-button,' +
    '.shortsLockupViewModelHostOutsideMetadataMenu,[class*="menu-button"]';
  var DL_DOTS_LABEL = /action menu|more actions|more options|^more$|menu|المزيد|قائمة|الإجراءات|خيارات/i;
  var DL_BADGE = /^(?:\d+:)*\d+$|^live$/i; // the length or LIVE badge of a picture is not a title
  // The same download drawing as the app's (res/drawable/ic_download_24dp.xml).
  var DL_ICON = 'M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z';
  var DL_HEIGHT = 44;       // px, the button's height (see the style below)
  var DL_ABOVE = 28;        // px between the button and the first row of the menu: clear of the sheet's edge and handle
  var DL_WAIT_MS = 2200;    // how long after a tap on the dots a menu is waited for
  var DL_CLICK_GAP_MS = 800; // the click that follows a touch is the same tap
  var DL_ROW_GRACE_MS = 500; // a menu is given this long after the tap to get the row of menu-download.js before the button shows
  var DL_FALLBACK_MS = 900;  // dots that are surely a menu button but no menu seen after this long: the button shows anyway
  var DL_FALLBACK_MAX_MS = 9000; // ... and goes by itself after this long if no tap took it away
  var dlBtn = null;
  var dlOk = null;          // does the app offer it? asked once the bridge is there
  var dlShown = false;
  var dlTapAt = 0;
  var dlTimer = 0;
  var dlUntil = 0;
  var dlBefore = [];        // the rows and sheets that were visible when the dots were tapped
  var dlUrl = '';
  var dlTitle = '';
  var dlTop = -1;           // the menu's top edge (px from the top of the window) when last looked at
  var dlSteady = 0;         // how many looks in a row found the menu at the same place
  var dlMissed = 0;         // how many looks in a row found no menu while the button shows
  var dlSure = false;       // the last tap's dots were told by a known box or label (not only by being a small icon button)
  var dlFallback = false;   // the button shows without a menu seen (the page's menu is of a kind this does not know)
  var dlShownAt = 0;

  whenRoot(function () {
    var id = '#' + DL_BTN_ID;
    addStyle('wardtube-dl-btn-css',
      id + '{display:none;position:fixed;top:40%;left:50%;z-index:2147483647;align-items:center;justify-content:center;' +
      'gap:8px;box-sizing:border-box;height:' + DL_HEIGHT + 'px;margin:0;padding:0 20px 0 16px;border:0;border-radius:' + DL_HEIGHT / 2 + 'px;' +
      'background:#e62118;color:#fff;font:600 15px/1 Roboto,Arial,sans-serif;white-space:nowrap;transform:translateX(-50%);' +
      'box-shadow:0 4px 14px rgba(0,0,0,.45);-webkit-tap-highlight-color:transparent;touch-action:manipulation;' +
      '-webkit-user-select:none;user-select:none}' +
      id + '.wt-show{display:flex;animation:wardtube-dl-in .16s ease-out}' +
      id + ':active{background:#c31c13}' +
      id + ' svg{width:22px;height:22px;flex:none;pointer-events:none}' +
      id + ' span{pointer-events:none}' +
      '@keyframes wardtube-dl-in{from{opacity:0;transform:translateX(-50%) translateY(8px)}' +
      'to{opacity:1;transform:translateX(-50%) translateY(0)}}' +
      'html[data-wt-mini] ' + id + ',html[data-wt-pip] ' + id + '{display:none!important}');
  });

  // May the button be shown? The app says so: it has the method.
  function dlAllowed() {
    if (dlOk !== null) return dlOk;
    try {
      var bridge = window.WardTubeNative;
      if (!bridge) return false; // the bridge may be a moment behind the script: asked again at the next tap
      dlOk = typeof bridge.downloadFromMenu === 'function';
    } catch (e) { return false; }
    return dlOk;
  }

  // The address of a video that a link names (a watch page, a Short, a live page, youtu.be), absolute; '' for any other link.
  // These are the kinds of link the app's VideoLinks.parse accepts.
  function dlVideoLink(href) {
    var u;
    try { u = new URL(href, location.href); } catch (e) { return ''; }
    var host = u.hostname, id = '';
    if (host === 'youtu.be') {
      id = u.pathname.slice(1);
    } else if (host === 'youtube.com' || host.slice(-12) === '.youtube.com') {
      if (u.pathname === '/watch') {
        id = u.searchParams.get('v') || '';
      } else {
        var m = /^\/(?:shorts|live)\/([^\/]+)/.exec(u.pathname);
        if (m) id = m[1];
      }
    }
    return /^[A-Za-z0-9_-]{11}$/.test(id) ? u.href : '';
  }

  // The video of a card: the first of its links that names one.
  function dlCardLink(card) {
    var links = card.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var link = dlVideoLink(links[i].getAttribute('href') || '');
      if (link) return link;
    }
    return '';
  }

  // The card's heading names the video best; a link's own label is the second choice. '' when the page gives none (the app
  // then shows its own "YouTube video").
  function dlCardTitle(card) {
    var head = card.querySelector(DL_HEADINGS);
    var text = head && head.textContent && head.textContent.trim();
    if (text && !DL_BADGE.test(text)) return text.slice(0, 200);
    var links = card.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      if (!dlVideoLink(links[i].getAttribute('href') || '')) continue;
      var label = (links[i].getAttribute('title') || links[i].getAttribute('aria-label') || '').trim();
      if (label && !DL_BADGE.test(label)) return label.slice(0, 200);
    }
    return '';
  }

  // The id of the video that a link names ('' for any other link): same kinds of link as dlVideoLink.
  function dlVideoId(href) {
    var m = /[?&]v=([A-Za-z0-9_-]{11})(?:[&#]|$)/.exec(href) || /\/(?:shorts|live)\/([A-Za-z0-9_-]{11})(?:[?\/#]|$)/.exec(href) ||
      /youtu\.be\/([A-Za-z0-9_-]{11})(?:[?\/#]|$)/.exec(href);
    return m ? m[1] : '';
  }

  // A card of a layout DL_CARDS does not know: the nearest box around the tapped button that links exactly one video, is no
  // bigger than a card can be, and holds no player. null when there is none.
  function dlUnknownCard(t) {
    var btn = t.closest('button,[role="button"]') || t.closest(DL_DOTS);
    if (!btn || btn.closest('a[href]')) return null;
    var h = window.innerHeight || 800;
    var el = btn.parentElement;
    for (var depth = 0; el && depth < 7 && el !== document.body && el !== document.documentElement; depth++, el = el.parentElement) {
      var ids = {}, count = 0;
      var links = el.querySelectorAll('a[href]');
      for (var i = 0; i < links.length && i < 80; i++) {
        var link = dlVideoLink(links[i].getAttribute('href') || '');
        var id = link ? dlVideoId(link) : '';
        if (id && !ids[id]) { ids[id] = 1; count++; if (count > 1) return null; }
      }
      if (count === 1) {
        if (el.querySelector('video,#player-container-id')) return null;
        return el.getBoundingClientRect().height <= h * 0.7 ? el : null;
      }
    }
    return null;
  }

  // The card whose three dots [t] (the target of a tap) is, or null when the tap is not on a card's menu button.
  // dlSure tells afterwards whether the dots were told by a known box or by their label (and not only by being a small icon button).
  function dlCardOfDots(t) {
    dlSure = false;
    var known = t.closest(DL_CARDS);
    var card = known || dlUnknownCard(t);
    if (!card) return null;
    if (t.closest('a[href]')) return null; // the picture or the title: the long press has that
    var dots = t.closest(DL_DOTS);
    if (dots && card.contains(dots)) { dlSure = true; return card; }
    var btn = t.closest('button,[role="button"]');
    if (!btn || !card.contains(btn)) return null;
    if (DL_DOTS_LABEL.test((btn.getAttribute('aria-label') || '') + ' ' + (btn.getAttribute('title') || ''))) { dlSure = true; return card; }
    if (!known) return null; // an unknown card is only trusted for dots that name themselves
    // A button with no words, only a drawing, and small: that is the menu button of a layout this does not know.
    var r = btn.getBoundingClientRect();
    if ((btn.textContent || '').replace(/\s+/g, '') || r.width > 72 || r.height > 72) return null;
    return btn.querySelector('svg,yt-icon,c3-icon,.yt-spec-icon-shape') ? card : null;
  }

  // The rows and sheets of menus that are on screen now (see MENU_ROWS / MENU_SHEETS: the "Open app" row code above).
  function dlCollect() {
    var out = [];
    try {
      var all = document.querySelectorAll(MENU_ROWS + ',' + MENU_SHEETS);
      var w = window.innerWidth || 0, h = window.innerHeight || 0;
      for (var i = 0; i < all.length && i < 120; i++) {
        var el = all[i];
        var r = el.getBoundingClientRect();
        if (r.width < 100 || r.height < 28 || r.bottom <= 0 || r.top >= h || r.right <= 0 || r.left >= w) continue;
        var cs = getComputedStyle(el);
        if (cs.visibility === 'hidden' || +cs.opacity < 0.05) continue;
        out.push(el);
      }
    } catch (e) {}
    return out;
  }

  // True while the "Download video" row of menu-download.js is in sight: on the screen and on top (what is drawn at its centre is
  // the row itself), the way that script tells it. A menu kept in the page but not open does not count.
  function dlRowIn() {
    try {
      var row = document.querySelector('[data-wt-dl="1"]');
      if (!row || !row.isConnected) return false;
      var r = row.getBoundingClientRect(), w = window.innerWidth || 0, h = window.innerHeight || 0;
      if (r.width < 4 || r.height < 4 || r.bottom <= 0 || r.top >= h || r.right <= 0 || r.left >= w) return false;
      var t = document.elementFromPoint(Math.min(Math.max(r.left + r.width / 2, 1), w - 1), Math.min(Math.max(r.top + r.height / 2, 1), h - 1));
      return !!t && (row === t || row.contains(t));
    } catch (e) { return false; }
  }

  // The menu that the tap opened: { top } (its top edge), or null while there is none. It is what is on screen now and was not
  // before the tap. The top is that of its first row; with no row (a menu made of something this does not know) it is the
  // top of its sheet, unless that sheet fills the window (then it is a layer around the menu, and the middle of the window is used).
  function dlFresh() {
    var now = dlCollect();
    var rowsTop = Infinity, sheetsTop = Infinity, h = window.innerHeight || 0;
    for (var i = 0; i < now.length; i++) {
      var el = now[i];
      if (dlBefore.indexOf(el) >= 0) continue;
      var r = el.getBoundingClientRect();
      if (el.matches(MENU_ROWS)) rowsTop = Math.min(rowsTop, r.top);
      else if (r.height < h * 0.95) sheetsTop = Math.min(sheetsTop, r.top);
      else sheetsTop = Math.min(sheetsTop, h * 0.55);
    }
    var top = rowsTop < Infinity ? rowsTop : sheetsTop;
    return top < Infinity ? { top: top } : null;
  }

  function dlShow(top) {
    if (!dlBtn) dlBtn = dlMakeButton();
    if (dlBtn.parentNode !== document.documentElement) document.documentElement.appendChild(dlBtn);
    dlPlace(top);
    dlBtn.classList.add('wt-show');
    dlShown = true;
    dlShownAt = Date.now();
    dlMissed = 0;
  }

  function dlPlace(top) {
    // Above the menu; when the menu reaches the top of the window the button stays on screen (over the menu's own top).
    dlBtn.style.setProperty('top', Math.round(Math.max(12, top - DL_ABOVE - DL_HEIGHT)) + 'px');
  }

  function dlHide() {
    dlShown = false;
    dlFallback = false;
    if (dlTimer) { clearTimeout(dlTimer); dlTimer = 0; }
    if (dlBtn) dlBtn.classList.remove('wt-show');
  }

  // Looks for the menu (every 100 ms until one is steady, at most DL_WAIT_MS) and, once the button shows, for the menu going
  // (every 250 ms). Stops by itself: it never runs while no tap on a card's dots is being followed.
  function dlWatch() {
    dlTimer = 0;
    var keep;
    try {
      // The menu has the "Download video" row of menu-download.js: the button is not needed (and goes if it already showed).
      if (dlRowIn()) { dlHide(); return; }
      var found = dlFresh();
      var now = Date.now();
      if (!dlShown) {
        if (found) {
          dlSteady = dlTop >= 0 && Math.abs(found.top - dlTop) < 3 ? dlSteady + 1 : 0;
          dlTop = found.top;
          if (dlSteady >= 1 && now - dlTapAt >= DL_ROW_GRACE_MS) {
            dlShow(found.top);
          } else if (now > dlUntil - 600) {
            dlUntil = now + 600; // a menu that is still sliding in is given a little more time
          }
        } else if (dlSure && now - dlTapAt >= DL_FALLBACK_MS) {
          // The dots are surely a menu button, yet no menu of a kind this knows has shown: the page's menu is made of something
          // unknown. The button shows anyway, in the middle of the window; a menu found later moves it above its rows.
          dlTop = Math.round((window.innerHeight || 600) * 0.5);
          dlShow(dlTop);
          dlFallback = true;
        }
        keep = dlShown || now < dlUntil;
      } else {
        if (found) {
          dlMissed = 0;
          dlFallback = false;
          if (Math.abs(found.top - dlTop) >= 3) { dlTop = found.top; dlPlace(found.top); }
        } else if (!dlFallback) {
          dlMissed++;
        }
        // Without a menu to follow, the button goes with the next touch on the page (dlOnTap) or after DL_FALLBACK_MAX_MS.
        keep = dlFallback ? now - dlShownAt < DL_FALLBACK_MAX_MS : dlMissed < 2;
        if (!keep) dlHide();
      }
    } catch (e) { dlHide(); return; }
    if (keep) dlTimer = setTimeout(dlWatch, dlShown ? 250 : 100);
  }

  // The page's menu is closed the way a person closes it: a tap on the dimmed part of the page above the menu. Only a layer above
  // the page that is not a button, link or row is tapped, so the page's own content is never touched; if the menu cannot be
  // found that way it stays open (and the app's sheet shows over it).
  function dlCloseMenu() {
    try {
      if (dlTop < 90) return; // a menu that reaches the top of the window leaves no free place to tap
      var x = (window.innerWidth || 0) / 2, y = 40;
      var el = document.elementFromPoint(x, y);
      if (!el || el === document.documentElement || el === document.body) return;
      if (el.closest('a,button,[role="button"],input,textarea,video') || el.closest(MENU_ROWS)) return;
      if (!overlayOf(el)) return;
      var init = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0 };
      ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (type) {
        var Ctor = type.indexOf('pointer') === 0 && window.PointerEvent ? PointerEvent : MouseEvent;
        el.dispatchEvent(new Ctor(type, init));
      });
    } catch (e) {}
  }

  function dlPress(e) {
    e.stopPropagation();
    e.preventDefault();
    var url = dlUrl, title = dlTitle;
    dlHide();
    if (!url) return;
    try {
      var bridge = window.WardTubeNative;
      if (bridge && typeof bridge.downloadFromMenu === 'function') bridge.downloadFromMenu(url, title);
    } catch (err) {}
    setTimeout(dlCloseMenu, 60);
  }

  function dlMakeButton() {
    var ns = 'http://www.w3.org/2000/svg';
    var btn = document.createElement('button');
    btn.id = DL_BTN_ID;
    btn.type = 'button';
    var lang = (document.documentElement.lang || navigator.language || '').toLowerCase();
    var label = lang.indexOf('ar') === 0 ? 'تنزيل' : 'Download';
    btn.setAttribute('aria-label', label);
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    var path = document.createElementNS(ns, 'path');
    path.setAttribute('d', DL_ICON);
    path.setAttribute('fill', '#fff');
    svg.appendChild(path);
    btn.appendChild(svg);
    var text = document.createElement('span');
    text.textContent = label;
    btn.appendChild(text);
    // Nothing done on the button is seen by the page: its menu would take a touch outside the menu for a tap on its dimmed part.
    ['touchstart', 'touchend', 'touchmove', 'pointerdown', 'pointerup', 'mousedown', 'mouseup', 'dblclick'].forEach(function (type) {
      btn.addEventListener(type, function (e) { e.stopPropagation(); });
    });
    btn.addEventListener('click', dlPress);
    return btn;
  }

  // Every tap on the page: the first touch (before the page's own handlers, so what was on screen is known) and the click that
  // follows it (for a tap that came without a touch).
  function dlOnTap(e) {
    try {
      var t = e.target;
      if (!t || !t.closest || (dlBtn && dlBtn.contains(t))) return;
      // A row or a button of the page was used while the button shows: the menu is used up or turned into another panel.
      if (dlShown && e.type === 'click' && t.closest(MENU_ROWS + ',' + CLICKABLE)) dlHide();
      // A button shown without a menu seen has nothing to follow: the next touch on the page (the menu used or closed) takes it away.
      if (dlShown && dlFallback && e.type === 'touchstart') dlHide();
      var now = Date.now();
      if (now - dlTapAt < DL_CLICK_GAP_MS) return; // the click of the touch that was just looked at
      var card = dlCardOfDots(t);
      if (!card || !dlAllowed()) return;
      var link = dlCardLink(card);
      if (!link) return; // a playlist or channel card: no one video to give
      dlTapAt = now;
      dlHide();
      dlUrl = link;
      dlTitle = dlCardTitle(card);
      dlBefore = dlCollect();
      dlTop = -1;
      dlSteady = 0;
      dlMissed = 0;
      dlUntil = now + DL_WAIT_MS;
      dlTimer = setTimeout(dlWatch, 120);
    } catch (err) { /* never break the page */ }
  }

  document.addEventListener('touchstart', dlOnTap, { capture: true, passive: true });
  document.addEventListener('click', dlOnTap, true);
  // The page moved on (a link of the menu, the back button): the button goes with the menu.
  window.addEventListener('popstate', dlHide);
  window.addEventListener('pagehide', dlHide);

  // ---- Timer --------------------------------------------------------------

  var ticks = 0;
  setInterval(function () {
    ticks++;
    if (ticks % 5 === 0) { hideOpenApp(); hideOpenAppRows(); }
    skipAd();
    autoUnmute();
    autoOriginalAudio();
    keepTop();
    syncPipButton();
    skipShortsAd();
    autoUnmuteShort();
    if (ticks % 5 === 0) { dropIframes(); keepWatching(); }
  }, 300);

  // ---- Public API ---------------------------------------------------------

  /**
   * The video the person is looking at. A Short's page holds several <video> elements (the neighbouring Shorts are kept
   * ready, some of them playing or looping out of sight), so the first one is often not the one on screen. On a Short:
   * the active reel's video, else the one with the most area inside the window, else the page's main one. Elsewhere
   * it is the page's main video, as before. The app reads the play / pause state from this one (screen stays awake only
   * while it plays) and sends play / pause to it.
   */
  function currentVideo() {
    if (!onShorts()) return mainVideo();
    var active = document.querySelector(ACTIVE_REEL);
    var v = active && active.querySelector('video');
    if (v) return v;
    var all = document.querySelectorAll('video');
    var w = window.innerWidth || 0, h = window.innerHeight || 0;
    var best = null, bestArea = 0;
    for (var i = 0; i < all.length; i++) {
      var r = all[i].getBoundingClientRect();
      var vw = Math.min(r.right, w) - Math.max(r.left, 0);
      var vh = Math.min(r.bottom, h) - Math.max(r.top, 0);
      var area = vw > 0 && vh > 0 ? vw * vh : 0;
      if (area > bestArea) { best = all[i]; bestArea = area; }
    }
    return best || mainVideo();
  }

  // A video starts to play with sound (the person plays it): tell the app, so the built-in player of the Library, if it plays,
  // pauses instead of playing over it. A muted video (a preview) does not count. "playing" does not bubble: caught on the way down.
  document.addEventListener('playing', function (e) {
    try {
      var el = e.target;
      if (!el || el.muted || el.volume === 0) return;
      var bridge = window.WardTubeNative;
      if (bridge && bridge.mediaPlaying) bridge.mediaPlaying();
    } catch (err) {}
  }, true);

  window.__wardtube = {
    /** The video on view (see currentVideo). */
    video: function () {
      try { return currentVideo(); } catch (e) { return null; }
    },
    /** Next (step > 0) or previous video, for the notification buttons. */
    skip: function (step) {
      return step > 0 ? skipNext() : skipPrevious();
    },
    /**
     * The app shows the player as a small box (true) or full screen (false). [aspect]: the shape (width / height) of the
     * system's picture in picture window when that is what the box is, else nothing.
     */
    setMini: function (on, aspect) {
      mini = !!on;
      pipAspect = mini && aspect > 0 ? +aspect : 0;
      keepTop();
    },
    /**
     * The app plays (on = true) or pauses (on = false) on purpose, through PlayerController: a pause the app asked for is not
     * undone by the picture-in-picture guard (see pipGuardTick).
     */
    wantPlay: function (on) {
      pipUserPaused = !on;
      if (on) { pipPlaying = true; pipTries = 0; }
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
