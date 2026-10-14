# Third-party notices

Ward Tube is licensed under the [GPL-3.0-or-later](LICENSE). It uses the following third-party components, each
under its own license. Check each project for the exact terms; this list is a summary, not legal advice.

## Origin: webTube

Ward Tube is based on [webTube](https://github.com/martykan/webTube) by martykan and contributors (original application id
`cz.martykan.webtube`), an Android wrapper for the YouTube website. webTube is free software under the GNU General
Public License, version 3 or (at your option) any later version, which is why Ward Tube can be distributed under
GPL-3.0-or-later. The original Java code was restructured in Kotlin and extended; the webTube authors keep their
copyright on the parts that come from it.

## Libraries and tools bundled in the APK

| Component | License | Notes |
|---|---|---|
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense | Downloader engine, run inside the app. Updated from yt-dlp's own servers when needed. |
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
