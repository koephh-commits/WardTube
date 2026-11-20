# Third-party notices

Ward Tube is licensed under the [GPL-3.0-or-later](LICENSE). It uses the following third-party components, each
under its own license. Check each project for the exact terms; this list is a summary, not legal advice.

## Origin: webTube

Ward Tube is based on [webTube](https://github.com/martykan/webTube) by martykan and contributors (original application id
`cz.martykan.webtube`), an Android wrapper for the YouTube website. webTube is free software under the GNU General
Public License, version 3 or (at your option) any later version, which is why Ward Tube can be distributed under
GPL-3.0-or-later. The original Java code was restructured in Kotlin and extended; the webTube authors keep their
copyright on the parts that come from it.

## Ideas taken from LiTube

Some behaviour of the full-screen player and of Shorts follows [LiTube](https://github.com/HydeYYHH/litube) by HydeYYHH
and contributors (GPL-3.0), an unofficial native YouTube client: the full-screen orientation rule (a picture taller than
wide turns the phone upright), the system's own rotation animation, hiding the system bars again when the window gets its
focus back, and the "next Short when one ends" playback order. Ward Tube is a WebView app, so none of LiTube's native
player code is used; the rules were re-written for it. Both projects are GPL-3.0.

## Ideas taken from NouTube

The way a video is named from a card follows [NouTube](https://github.com/nonbili/NouTube) by nonbili and contributors
(AGPL-3.0, according to its repository page), an app that shows YouTube in a WebView: Android's own hit test of the
WebView gives the link under a long press (`NouTubeView.kt`), and the title of the video is read from its card by the
video's id (`content/link-title.ts`). Ward Tube is a different app with its own sheet and download flow, so none of
NouTube's code is copied; the rules were re-written for it. GPL-3.0 allows combining its work with AGPL-3.0 work
(section 13), but check NouTube's repository for its exact terms.

## Libraries and tools bundled in the APK

| Component | License | Notes |
|---|---|---|
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense | Downloader engine, run inside the app. Updated from yt-dlp's own servers when the person updates it (or, with automatic updating turned on, when a download fails). |
| [youtubedl-android](https://github.com/yausername/youtubedl-android) (library + ffmpeg modules, 0.18.1, published on Maven Central as `io.github.junkfood02.youtubedl-android`) | GPL-3.0 | Packs yt-dlp together with a Python runtime and an FFmpeg build; the licenses of those bundled binaries apply too. Based on youtubedl-java by sapher. |
| Jackson, Apache Commons Compress | Apache-2.0 | Pulled in by youtubedl-android. |
| XZ for Java | Public domain | Pulled in by youtubedl-android. |
| AndroidX (core, appcompat, activity, webkit, media, recyclerview) | Apache-2.0 | |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache-2.0 | |

## Ad filter lists (downloaded at run time, not shipped)

[EasyList](https://easylist.to/) and Liste AR are downloaded from `easylist-downloads.adblockplus.org` on the
user's device. EasyList is dual-licensed GPLv3+ / CC BY-SA 3.0. If you ship copies of the lists inside the APK
(`app/src/main/assets/adblock/lists/`), check each list's header and keep its notice.

## Icons

The interface icons in `app/src/main/res/drawable*` are Material Design icons (Apache-2.0). The WhatsApp glyph
(`ic_whatsapp_24dp`) is only used for the contact link in the About screen; WhatsApp is a trademark of its owner.

## Trademarks

YouTube is a trademark of Google LLC and TikTok of its owner. Ward Tube is not affiliated with or endorsed by
them.
