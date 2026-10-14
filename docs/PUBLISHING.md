# Publishing guide / دليل النشر

## 1. Create the repository

1. On GitHub: **New repository** → name `WardTube` → Public → **Add license: GNU GPLv3**.
   (This creates the `LICENSE` file. If you create the repository empty instead, run once inside the project:
   `curl -o LICENSE https://www.gnu.org/licenses/gpl-3.0.txt`)
2. Push the project:

```bash
cd WardTube
git init
git add .
git commit -m "Ward Tube 2.6.27"
git branch -M main
git remote add origin https://github.com/<username>/WardTube.git
git pull origin main --allow-unrelated-histories   # only if GitHub already created LICENSE
git push -u origin main
```

Replace `<username>` with your GitHub name. Check with `git status` that no `.jks` / `.keystore` file is listed.

## 2. Create the signing key (once, keep it forever)

```bash
keytool -genkeypair -v -keystore wardtube-release.jks -alias wardtube \
  -keyalg RSA -keysize 4096 -validity 36500
```

- Back up `wardtube-release.jks` and its passwords in a safe place (not in the repository).
- If the key is lost, nobody can update over an installed copy: users would have to uninstall first.

## 3. Add the secrets to GitHub

Repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 wardtube-release.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `wardtube` |
| `KEY_PASSWORD` | the key password |

## 4. Publish a release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (and `ar/`), and a section in `CHANGELOG.md`.
3. Commit, then:

```bash
git tag v2.6.27
git push origin v2.6.27
```

GitHub Actions builds the signed APKs (`arm64-v8a`, `armeabi-v7a`, `universal`) and attaches
them to a new release. Every normal push only builds a debug APK to check that the project compiles.

## 5. Distribution

- **Obtainium**: users add `https://github.com/<username>/WardTube` and get every release automatically.
- **IzzyOnDroid** (recommended store): open a request at
  <https://gitlab.com/IzzyOnDroid/repo/-/issues> with the repository link. It reads the APKs from your releases and the
  `fastlane` folder. Add screenshots to `fastlane/metadata/android/en-US/images/phoneScreenshots/` (1.png, 2.png...).
- **F-Droid**: requires building everything from source. The yt-dlp/Python/ffmpeg binaries bundled by
  youtubedl-android may block acceptance; ask on the F-Droid requests tracker before investing time. YouTube access is
  usually flagged as a "non-free network service".
- **Google Play**: not suitable (ad blocking and YouTube downloading go against its policies).

## 6. Before the first release

- Screenshots in `fastlane/.../phoneScreenshots/`.
- The WhatsApp number in `app/src/main/res/values/strings.xml` (`aboutWhatsappNumber`) becomes public with the
  repository and stays in the git history even if you remove it later. Replace it before the first push if you do not
  want it public.
- Enable **Settings → Code security → Private vulnerability reporting** so that SECURITY.md works.
- The About screen and the README say the app is not affiliated with YouTube; keep that.
- Keep the Unlicense/GPL/Apache notices of the libraries (listed in the README and THIRD_PARTY_NOTICES.md), and keep the
  "based on webTube" credit (https://github.com/martykan/webTube, GPL-3.0-or-later) in the README, THIRD_PARTY_NOTICES.md and the store
  descriptions.

---

## الخلاصة بالعربية

1. أنشئ مستودعاً عاماً على GitHub واختر ترخيص **GNU GPLv3** عند الإنشاء، ثم ارفع المشروع بالأوامر أعلاه.
2. أنشئ مفتاح التوقيع مرة واحدة بأمر `keytool` أعلاه، واحتفظ بنسخة منه وكلمات مروره في مكان آمن. إذا ضاع فلن يستطيع
   المستخدمون التحديث فوق النسخة المثبتة.
3. أضف الأسرار الأربعة في إعدادات المستودع (القسم 3).
4. لنشر إصدار: ارفع `versionCode` و`versionName`، أضف ملف التغييرات، ثم `git tag vX.Y.Z` و`git push origin vX.Y.Z`.
   تبني GitHub Actions ملفات APK موقّعة وتضعها في صفحة الإصدارات.
5. للتوزيع: Obtainium ثم IzzyOnDroid. أما F-Droid فقد يرفض بسبب الملفات الجاهزة داخل مكتبة yt-dlp، وGoogle Play غير مناسب.
