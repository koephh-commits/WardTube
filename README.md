# Ward Tube

[العربية](README.ar.md)

A lightweight Android app around the YouTube mobile site: ad blocker, background play, downloads and a
library that works offline. Free software under the [GPL-3.0](LICENSE).

> Ward Tube is **based on [webTube](https://github.com/martykan/webTube)** by martykan, an Android wrapper for the YouTube website under
> GPL-3.0-or-later. It started from webTube's code and was restructured in Kotlin, with the ad blocker, downloader,
> library, mini player and more added.

> Ward Tube is **not affiliated with or endorsed by Google or YouTube**. Blocking ads and downloading videos may
> go against YouTube's terms of service. You use the app at your own responsibility.

## Features

- **Ad blocker**: EasyList, Liste AR and built-in YouTube rules, ads inside Shorts included. The lists refresh
  themselves every four days.
- **Background play**, also with the screen off. The notification shows the cover and has Previous / Next.
- **Mini player** like the YouTube app: a playing video shrinks into a floating box you can drag around while you
  keep browsing.
- **Sleep timer**: ready-made times or a time you type (up to 24 hours).
- **Downloads** done on the device (yt-dlp + ffmpeg): MP4, MP3 or M4A, with subtitles. A queue shows progress.
- **Playlists**: share or paste a playlist, Mix or channel link (YouTube, and any other site yt-dlp reads as a list),
  tick the videos you want and they join the queue one by one.
- **Download by sharing**: share a video link from any app and pick Ward Tube.
- **Library** of your downloads, available without internet. Select several files to share or delete them together.
- **Browser** (globe button): paste a link of a video from any site and download it, or open the page and download
  files from it. Ads are blocked there too, and a floating download button turns on when the page offers a video or a
  file. It has previous / next page, reload and home buttons, and a browsing history (stored only on the device) that
  you can delete page by page, several at once, or all together.
- Voice search, dark mode, landscape fullscreen.

## Install

1. Open the [Releases](../../releases) page and download an APK. Most phones need `arm64-v8a`; if unsure,
   take the `universal` one.
2. Allow installing from your browser or file manager when Android asks.
3. For automatic updates, add this repository to [Obtainium](https://github.com/ImranR98/Obtainium).

Needs Android 7.0 (API 24) or newer.

## Privacy

No account, no analytics, no tracking. The app only talks to:

- YouTube (the page you browse, inside the WebView, and the video covers shown in the notification),
- `easylist-downloads.adblockplus.org` (ad filter lists),
- the yt-dlp update servers (only when you update the download engine or a download needs it).

The Browser screen opens whatever site you type or paste (a text that is not a link is searched on
`google.com`).

Your settings, browsing history and downloads stay on the device; Android backup is turned off for the app.

## Build

Open the folder in Android Studio (JDK 17+), or run `./gradlew assembleDebug`.

Signed releases are built by GitHub Actions when a tag such as `v2.4.0` is pushed. Setup:
[docs/PUBLISHING.md](docs/PUBLISHING.md).

## License

Copyright (C) MWMods. Based on [webTube](https://github.com/martykan/webTube), Copyright (C) the webTube authors (martykan and contributors),
also GPL-3.0-or-later. Ward Tube is free software: you can redistribute it and modify it under the terms of the
GNU General Public License version 3 (or, at your option, any later version). See [LICENSE](LICENSE).

It uses: [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense),
[youtubedl-android](https://github.com/yausername/youtubedl-android) and its ffmpeg build,
[EasyList](https://easylist.to/) and Liste AR (GPLv3+ / CC BY-SA 3.0), AndroidX and Material Components (Apache-2.0).
The full list is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); check each project's own license for the
exact terms.

## Contributing

Bug reports and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Security problems:
[SECURITY.md](SECURITY.md).

---

## Developer notes

### Source layout

```
app/src/main/java/com/mwmods/wardtube/
  WardTubeApp.kt             Application class: installs the crash handler, applies the theme
  MainActivity.kt            window, YouTube WebView setup, lifecycle
  ShareReceiverActivity.kt   "Share to Ward Tube": the transparent Download-as dialog
  CrashActivity.kt           crash report screen (own process); util/CrashLog.kt writes the report
  Prefs.kt                   typed SharedPreferences
  adblock/                   AdBlocker (request blocking), FilterEngine/FilterParser/NetworkRule/RuleSet/ResourceType
                             (EasyList syntax), FilterListManager (download + cache)
  browser/                   BrowserActivity: paste a link / small browser, downloads of files from pages;
                             MediaSniffer (finds the videos and files a page offers for the floating button);
                             BrowserHistory (the stored list of visited pages), HistoryActivity (its screen)
  player/                    PlayerHost (mini player), PlayerController (JS remote), PlaybackService (media
                             notification), SleepTimer, BecomingNoisyReceiver (pause on unplug)
  ui/                        MenuController (bottom bar and overflow menu), LibraryActivity/LibraryAdapter,
                             MultiSelection, ThumbnailLoader, AboutSheet, ThemeHelper, Insets
  download/                  Downloader (format dialog), DownloadService (queue + yt-dlp, runs in its own process),
                             DownloadJob (queue entry + broadcast codec), DownloadOptions, SubtitleLister/
                             SubtitleCleaner, PlaylistLister (reads a link's videos with yt-dlp; ListProbe runs it
                             in the background), YtDlpEngine
  util/                      UrlUtils, ShareLinks, PlaylistLinks (video or list?), CookieHelper, Permissions,
                             DownloadStore, CrashLog
  web/                       WardTubeWebViewClient, WardTubeChromeClient, PageScripts, NativeBridge, WebTargets,
                             BackgroundWebView
app/src/main/assets/
  adblock/                   hosts.txt, url_patterns.txt, cosmetic.css (+ optional lists/)
  wardtube/                  wardtube.js, base.css
```

### Ad blocker

Switchable from the menu ("Block ads"). It has four parts:

1. **EasyList and Liste AR** (`adblock/FilterEngine.kt` and friends): the lists are downloaded in the
   background on first start and refreshed every 4 days (menu: "Update ad filters"). Network rules are
   matched against every request; element-hiding rules for YouTube become CSS.
2. **Built-in rules** for YouTube's own ad endpoints that the public lists do not cover
   (`assets/adblock/hosts.txt`, `url_patterns.txt`, `cosmetic.css`).
3. **Player data**: `wardtube.js` removes the ad schedule (`adPlacements`, `playerAds`, `adSlots`) from player
   responses before the player sees it.
4. **Ad skipper**: any in-player ad that still gets through is muted, fast-forwarded and skipped.

#### How the lists are used

- Lists live in `files/filters/` after the first download. To ship them inside the APK instead, put
  `easylist.txt` and `liste_ar.txt` in `app/src/main/assets/adblock/lists/` (a downloaded copy wins).
  EasyList is dual-licensed GPLv3+ / CC BY-SA 3.0; check each list's header before redistributing it.
- WebView does not say what kind of resource a request is for, so `$script`, `$image`... options are
  matched against a guess made from the file extension and the Accept header.
- Rules the app cannot honour (`$popup`, `$csp`, `$redirect`, regular expressions, `#?#` extended
  selectors...) are skipped rather than applied wrongly. Rules for other sites are dropped while parsing.
- Generic element-hiding rules (`##.ad-banner`) are off: they hide parts of YouTube's own interface. Flip
  `APPLY_GENERIC_HIDING` in `FilterEngine.kt` to turn them on.

YouTube changes its markup and ad delivery regularly. The built-in rules are plain asset files, so when an
ad slips through, adding a host, URL pattern or CSS selector there is usually enough.

### Dark mode

Menu > Theme: **Follow system** (default), **Light** or **Dark**. The choice is stored in `Prefs.theme`
and applied by `ui/ThemeHelper.kt` (called from `WardTubeApp` before any activity exists).

- The app theme is `Theme.MaterialComponents.DayNight`. Dark colours live in `res/values-night/colors.xml`
  (`barBrowse` / `barWatch` are the top and bottom bars while browsing / watching).
- `MainActivity.applyWebViewDarkMode()` lets the WebView use YouTube's own dark theme and darken pages that
  have none (`ALGORITHMIC_DARKENING`, with the older `FORCE_DARK` as a fallback).
- `uiMode` is in the manifest's `configChanges` so a theme flip never kills background playback by itself;
  `MainActivity` rebuilds itself on a theme change, but only while visible (otherwise on the next `onResume`).
  The open page is reloaded when that happens.

### Library, voice search and About

- **Download by sharing**: share a video link from any app and pick *Ward Tube* (`ShareReceiverActivity`, text/plain `SEND`).
  Any site is accepted and yt-dlp decides whether it can download it. Only the transparent "Download as" dialog opens over
  the sharing app; the file is queued like any other download. `util/ShareLinks.kt` cuts the link out of the shared text;
  YouTube and TikTok links are also rewritten to a plain form, and their pages that are neither a video nor a list are refused.
  Lists (a playlist, Mix or channel; any other site yt-dlp reads as a list) get a choice of videos after the format window;
  each chosen video is queued as a job of its own (`DownloadService` takes the whole batch in one request).
- **Library** (bottom bar): lists every file downloaded from inside the app (`Download/WardTube`), with search, play, share and delete, one file at a time or several at once (long-press to select).
  The *Download* menu item downloads on the device itself (bundled yt-dlp + ffmpeg via youtubedl-android, no external website):
  pick MP4 1080/720/480 or MP3/M4A, the finished file lands in the Library.
  Several downloads can be started in a row: they run one after another, and the Library shows the whole queue at the top.
  The running one has a progress ring and bar (percent, speed, time left; a video is video + audio, so the percentage covers both),
  the ones behind it say *Waiting* with their place in line and move up as the earlier ones finish. Each card has a cancel button;
  a failed download stays on screen with *Try again* / *Dismiss*. The notification shows the same (current job + how many are waiting)
  and opens the Library when tapped.
  If YouTube changes and a download fails, the engine updates itself once and retries; *Update download engine* in the menu does it manually.
- **Voice search**: mic button in the bottom bar (system speech recognizer → YouTube search). YouTube's own mic button also works: the
  WebView asks for `RECORD_AUDIO`, which is only ever granted to youtube.com pages.
- **Permissions**: `READ_MEDIA_AUDIO/VIDEO` (Android 13+), `READ_EXTERNAL_STORAGE` (≤ 12L), `WRITE_EXTERNAL_STORAGE` (≤ 9).
  "All files access" (`MANAGE_EXTERNAL_STORAGE`) is optional and only lets the Library list every file in
  `Download/WardTube`, such as subtitle files; the app works without it.
- **About** (overflow menu): developer info, version and a WhatsApp contact link.

#### How the Library gets the live queue

`DownloadService` runs in the `:download` process, so the Library cannot read its memory. Instead the service broadcasts the whole
queue as JSON (`DownloadEvents.ACTION_STATE`, restricted to this app's package) whenever something changes (progress at most every
400 ms) and as a heartbeat every 2 s. A Library that opens asks with `ACTION_REQUEST_STATE`; no answer means nothing is downloading.
If the heartbeat stops for 6 s (the process was killed) the Library drops the queue. Jobs that finish simply leave the queue and the
Library reloads its file list; failures are kept by the Library itself until dismissed.
