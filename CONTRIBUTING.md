# Contributing to Ward Tube

Thanks for helping. Bug reports, translations, ad-blocker rules and code are all welcome.

## Report a bug

Open an issue and fill in the template. The most useful details: the app version (About screen), your Android
version and phone model, the steps that trigger it, and, after a crash, the text from the crash screen.

## Ad slipped through?

YouTube changes its ads often. The built-in rules are plain files in `app/src/main/assets/adblock/`
(`hosts.txt`, `url_patterns.txt`, `cosmetic.css`). Adding a host, a URL pattern or a CSS selector there is usually the
whole fix, and a small pull request is the fastest way to share it.

## Build

Android Studio with JDK 17, or `./gradlew assembleDebug`. See the *Developer notes* in the [README](README.md) for
how the code is organised.

## Pull requests

- Keep one change per pull request, and say what you tested and on which Android version.
- New Kotlin files start with the two-line header the other files have
  (`// SPDX-License-Identifier: GPL-3.0-or-later` and `// Copyright (C) MWMods`).
- Add a line to `CHANGELOG.md` under a new version, and the matching short notes in
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (and `ar/`) when you change `versionCode`.
- Texts shown to the user go in `res/values/strings.xml` and `res/values-ar/strings.xml` (English and Arabic).
- The app must stay free of accounts, analytics and tracking, and must not contact servers other than the ones listed
  in the README's *Privacy* section without saying so there.

By contributing you agree that your work is released under the project's license, GPL-3.0-or-later.

---

**بالعربية:** المساهمات مرحّب بها: أبلغ عن الأخطاء عبر قالب البلاغ، وأرسل قواعد الإعلانات الجديدة في ملفات
`app/src/main/assets/adblock/`، وأضف النصوص بالعربية والإنجليزية، وحدّث `CHANGELOG.md` عند تغيير الإصدار. بمساهمتك
توافق على نشر عملك برخصة GPL-3.0 أو أي إصدار أحدث.
