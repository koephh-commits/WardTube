# Changelog

## 2.6.177
- New: **picture in picture and play in the background for Shorts**, the same way long videos have them
  (`assets/wardtube/wardtube.js`, `player/PlayerController.kt`, `MainActivity.kt`, `app/build.gradle.kts`).
  - **Before:** a Short plays in the browsing WebView (the player's own WebView is for long videos), and everything that makes the
    little window and the background notification was pointed at the player only. Pressing Home on a Short stopped it, and no window opened.
  - **Picture in picture:** pressing Home while a Short plays opens the little window (the switch of the three-dots menu decides, as for
    long videos), and a **round button on the Short** opens it by hand. The button is the player's own (same size, dark disc and
    drawing, label «صورة داخل صورة»), at the right edge above the column of buttons of the Short, and steps aside while the comments or a
    menu are open. The window has the shape of the Short's picture (vertical as a rule), shows only the video, and has the system's
    previous / play-pause / next buttons. Tapping it brings the app back; closing it stops the Short, as for a long video.
  - **Background:** with *Play in background* on, a Short goes on playing with the screen off or the app left, with the same notification as a long
    video (cover, title, previous / next, seek bar, stop) and the sleep timer. Previous / next move to the Short before / after (previous
    restarts the Short first when it played for more than 3 seconds).
  - **How:** `PlayerController` now acts on the Short of the browsing page while one is on view (and the player's video is not in front),
    so the notification, the window's buttons and the screen-on poll work on it; the browsing page is kept "visible" to Chromium only
    while a Short plays; the page script (`Picture in picture for a Short`) hides everything but the Short's video while the window
    is up (with `visibility`, so the feed's layout and position are not touched) and reports the picture to the app.
  - **Also:** a long video that opens in front of a Short now stops the Short (it played on behind it, unseen); a change of Short no longer
    pauses a Short by mistake while a long video waits behind it.
  - **Unchanged:** long videos, the mini player, the Subscriptions screen, the download button, the ad blocker.
  - **Tried:** `wardtube.js` was run in headless Chromium with touch on a mock Shorts page (top bar, a feed of three Shorts that snaps and
    marks the active one, a column of buttons, a title, a comments sheet): button shown once on a Short and not on other pages, round
    40 px as the player's, clear of the Short's buttons and the top bar, one call to the app per tap and the Short not paused by it;
    the picture reported; in the window only the active Short's video is visible, the feed is not moved, and everything is back when
    the window is left; next / previous move the feed; no script errors, and the player route is unchanged (28 checks).
    **Not tried:** the Kotlin side is not compiled here (no Android SDK), and nothing was tried on a phone or against live YouTube, whose real
    Shorts elements may differ from the mock. Please build it and try: a Short + Home (window), the button on the Short, a Short
    with the screen off (notification), next / previous, and leaving the window. If the button sits over something on your phone, or the
    window shows more than the video, please say so: both are a few lines in `wardtube.js`.

## 2.6.176
- Fixed: **on a Short, the "Download video" row showed in the wrong places** (`assets/wardtube/menu-download.js`, `app/build.gradle.kts`).
  - **Before:** a tap on *any* button of the Shorts player (like, comments, share ...) was taken for a tap on a menu button. The row was then
    put into whatever looked like a menu: over the Short itself (beside its title, for about two seconds, after a tap on like) and at the top of
    the comments sheet.
  - **Cause:** on a Short the script took the video from the data of the player's elements, which every button of the player carries, so every
    button started the wait for a menu; and the fallback that finds a menu "by how it is drawn" (wide boxes with text in a fixed layer) took the
    Short's own title bar and the comments sheet for one.
  - **Now:** on a Short (`/shorts/ID`) only the **three dots** of the Short start that wait (found by YouTube's menu element, its label or its
    drawing; a button with `aria-pressed` or named like / comment / share / subscribe never counts). Any other tap ends the wait and takes a row
    that is still there away. The video is the one the address names (it follows the swipes), as `shorts`. The comments panel is never taken for a
    menu either (`COMMENTS`). The label test of the three dots also knows "مزيد" (as in "مزيد من الإجراءات"), not only "المزيد".
  - **Unchanged:** the row in the three-dots menu of a Short, and everything on Home, Search, channels and the watch page.
  - **Tried:** the old and the new script were run in headless Chromium with touch on a mock Shorts page (top bar with back, search and the three
    dots; a column with like, comments and share; a title bar over the video; a menu sheet; a comments sheet). The old script shows the row over
    the Short after like (gone after about 2 s), in the comments sheet, and after share and search, as in the screenshots. The new one shows it only
    after the three dots: once in the menu, one call to the app with `shorts` and the right id; nothing after like, comments, share or search;
    a menu with rows the script has no name for still gets it; after a swipe to another Short the new id is used; a tap on comments while the
    menu is open adds nothing. **Not tried:** not compiled here (no Android SDK) and not tried on a phone or against live YouTube, whose real
    elements may differ from the mock. Please build it and try on a Short: like, comments, share (nothing should appear), then the three dots
    (the row should be in the menu). If the row is missing in the menu, please say so: the app's download button of the bottom bar and the
    long press still work.

## 2.6.175
- Changed: **the description under the "Picture in picture" row now says the window opens when the person presses Home**
  (`values/strings.xml`, `values-ar/strings.xml`, `app/build.gradle.kts`).
  - **Before:** "When you leave the app, the video keeps playing in a small floating window" / «عند الخروج من التطبيق يستمر الفيديو في نافذة
    صغيرة عائمة».
  - **Now:** "When you press Home, the video keeps playing in a small floating window" / «عند الخروج بزر الهوم يستمر الفيديو في نافذة صغيرة
    عائمة». The "Off" line follows it: "Off: pressing Home keeps only the sound (if play in background is on)" / «متوقف: الخروج بزر الهوم
    يُبقي الصوت فقط (إذا كان التشغيل في الخلفية مفعّلاً)».
  - **Unchanged:** the picture-in-picture feature itself and the switch; only the two description strings changed.
- Removed: the empty `NouTube` folder at the root of the project (it held no files).
- **Not tried:** not compiled here (no Android SDK) and not tried on a phone.

## 2.6.174
- Removed: **the note under the "Picture in picture" row of the three-dots menu** ("not reliable when the video is in fill or
  full-screen mode...", added in 2.6.90) (`ui/MenuController.kt`, `values/strings.xml`, `values-ar/strings.xml`,
  `app/build.gradle.kts`).
  - **Now:** the row shows only its state line ("When you leave the app the video keeps playing in a small floating window" /
    "Off: ..."), as it did before 2.6.90. The string `pictureInPictureNote` is deleted in English and Arabic.
  - **Unchanged:** the picture-in-picture feature itself and the switch.
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone.

## 2.6.173
- Changed: **the look of the message that explains the ways to download a video** (the one shown when the app opens): it is now a bottom
  sheet drawn like the three-dots menu and the player chooser, instead of a plain dialog with a long column of pictures
  (`ui/DownloadGuideDialog.kt`, `res/layout/dialog_download_guide.xml`, `res/layout/item_guide_image.xml`,
  `res/drawable/ic_touch_24dp.xml` (new), `values/strings.xml`, `values-ar/strings.xml`, `app/build.gradle.kts`).
  - **Before:** a dialog with two red headings, a text under each and the four pictures one under the other, so the message was long and
    the person had to scroll through all of it.
  - **Now:** a title with a line under it, then **one soft card for each way**, each with a tinted tile (the download arrow in red, a finger in
    blue), its name, its text and its pictures. The **three pictures of the first way slide sideways** (a piece of the next one shows, to say
    there is more), so the whole message fits on one screen. The second way has its picture as wide as the card. At the bottom a wide
    **OK** button and, under it, a quiet *Don't show again* text button.
  - **Text:** the "1." and "2." in front of the two names are gone (the two ways are choices, not steps), and a short line was added under the
    title ("Two ways to download any video"). The rest of the text is as it was.
  - **Unchanged:** when it shows (each new start, until *Don't show again*), what the buttons do, and that *OK*, a tap outside or Back only
    close it. A swipe down closes it too now, as with the other sheets.
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone. Please build it and look at: (1) the sheet in Arabic and in
    English, in day and night mode, (2) the first card's pictures slide sideways, the first one at the start side, (3) the buttons' Arabic
    letters are joined (their letter spacing is 0), (4) a small phone and landscape: the sheet scrolls to the buttons.

## 2.6.172
- Removed: **the floating "Download" button that showed over the menu of a video's three dots** (`assets/wardtube/wardtube.js`,
  `web/NativeBridge.kt`, `web/VideoLongPress.kt`, `MainActivity.kt`, `assets/wardtube/menu-download.js` (comments only)).
  - **Gone:** the whole "floating button over the menu" part of `wardtube.js` (the button, the look for the menu that a tap on the dots
    opens, the tap on a card's dots), the bridge method `downloadFromMenu` and its callback, and `VideoLongPress.openFromPage`.
  - **Unchanged:** the *Download video* row inside the menu of a video's three dots and the *Download* button beside the buttons under the
    video (2.6.171, `menu-download.js`), the long press on a video, the bottom bar's button, the Subscriptions screen's own menu.
    A menu that the row cannot be put in now shows nothing extra: the long press on the video is the way there.
- Added: **a message, with pictures, that explains the ways to download a video**, shown when the app opens, with a *Don't show again*
  button (`ui/DownloadGuideDialog.kt`, `res/layout/dialog_download_guide.xml`, `res/layout/item_guide_image.xml`,
  `res/drawable-nodpi/guide_download_{buttons,menu,bar,longpress}.png`, `Prefs.kt`, `MainActivity.kt`, `values/strings.xml`,
  `values-ar/strings.xml`).
  - **Text:** two ways, as asked: (1) the Download button, beside the like buttons or in the three-dots menu while a video plays, and, if it
    does not show, in the side menu or the bottom bar; (2) the long press on any video, and on the Home page the three dots then Download,
    or the long press if it does not show.
  - **Pictures:** four small drawn illustrations (the buttons under a video, the three-dots menu, the bottom bar, the long press), the same in
    day and night mode (about 30 KB together). They are not screenshots: to use a screenshot, put a PNG of the same name in
    `res/drawable-nodpi/`.
  - **When:** each time the app is opened (a new start, not a rotation or a theme change), until *Don't show again* is pressed
    (`Prefs.downloadGuideHidden`). *OK*, a tap outside or Back only closes it.
  - **The old message is gone:** the one-time tip above the bottom bar ("press and hold any video to download it"), with its page check,
    its preference (`videoPressTipSeen`) and its strings. Everyone sees the new message once more, also those who had seen the old tip.
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone. Please build it and try: (1) open the app: the message shows
    over the page, scrolls, and the four pictures are in it, in Arabic and in English, in day and night mode, (2) *OK*: it closes and shows
    again at the next start, (3) *Don't show again*: it never shows again, (4) tap the three dots of a video on Home: YouTube's menu with the
    *Download video* row and no floating red button, also in Search results, a channel and the list under the player.

## 2.6.171
- Added: **a "Download video" row inside the menu of a video's three dots, and a "Download" button beside the buttons under the video when
  that row has no three dots**, the way the older versions had them (`assets/wardtube/menu-download.js` back, `web/PageScripts.kt`,
  `web/NativeBridge.kt`, `MainActivity.kt`, `assets/wardtube/wardtube.js`, `README.md`, `README.ar.md`, `fastlane/.../217.txt`,
  `app/build.gradle.kts`).
  - **Before:** from a card the download was only a long press or the floating red button over YouTube's menu (2.6.169 / 2.6.170); the row
    inside the menu and the button of the watch page's row had been removed in 2.6.123.
  - **Now:** tap the three dots of a video (Home, Search, a channel, the list under the player): YouTube's menu opens and a *Download video*
    row, drawn like the menu's own rows, is put before its first row. Tap it: the usual download choice opens at once and YouTube's menu
    closes. Under the playing video: the three dots of the row of buttons (like, dislike, share ...) get the same row, and it downloads the
    video the page shows (the live check of the bottom bar's download button is kept); a row of buttons with no three dots gets a *Download*
    button of its own beside the last button, drawn like it.
  - **How:** the old page script `menu-download.js` is back, without what only the removed app-side button needed (`cardMenu`, `rowShown`,
    `closeMenu`). It is now part of the script that `PageScripts` injects (after `wardtube.js`), so it runs on the browsing page and the
    player's page, also on WebViews without document-start scripts, and it is limited to the top frame. It talks to the existing
    `WardTubeNative` bridge: new `NativeBridge.menuDownload(kind, id, title)` and `menuDownloadCurrent(kind, id)` (the app builds the
    `https://m.youtube.com/...` address from the 11-character id itself; two calls less than 0.6 s apart count as one), which reach
    `MainActivity.startMenuDownload` and `startDownloadFlow`.
  - **The floating button is now the backup.** It stays away while the row is in sight (`dlRowIn` in `wardtube.js`, which looks for the
    row the way the script itself does: what is drawn at its centre is the row) and waits 0.5 s after the tap before it may show, so it
    does not flash while the row goes in. A menu the script cannot put a row in (rows it has no name for and cannot find by how they are
    drawn) still gets the floating button, as in 2.6.170.
  - **Unchanged:** the long press, the sheet, the Subscriptions screen's own menu (it has its own *Download* row), the bottom bar's button.
  - **Tried:** `wardtube.js` + `menu-download.js` together, as one injected script, in headless Chromium with touch on mock pages (18 checks):
    a card with a menu of a known kind (row before the first row, no floating button at any moment of 2.5 s, one `menuDownload` call with the
    right kind, id and title, YouTube's menu closed behind it); a menu of an unknown kind found by how it is drawn; a menu no row can go in
    (no row, the floating button shows and calls `downloadFromMenu`); a watch page with three dots (row in their menu, `menuDownloadCurrent`);
    a watch page without three dots (button beside the last button, `menuDownloadCurrent`); a tap on the picture (nothing); the source run
    twice (one row); three opens of the menu in a row (one row each time). The same page with 2.6.170's `wardtube.js` alone gets no row, only
    the floating button. **Not tried:** not compiled here (no Android SDK) and not tried on a phone or against live YouTube, whose real menu
    and card elements may differ from the mocks (the script is the one of the older versions, which showed no row on the Search page at
    first; if it still does not show on some page, the floating button comes instead). Please build it and try: (1) Home: tap the dots of a video: the
    *Download video* row is in the menu, tap it, (2) the same in Search results, in a channel and in the list under the player, (3) under the
    playing video: the three dots (or the *Download* button beside the buttons), (4) tap the dots and then outside the menu: nothing stays
    on the page. If the row does not show, please say on which page.

## 2.6.170
- Fixed: **the floating "Download" button over a video's three-dots menu did not show** (`assets/wardtube/wardtube.js`,
  `fastlane/.../216.txt`, `app/build.gradle.kts`).
  - **Why it could stay away.** The button was shown only when the script *recognised the menu that opened* (its sheet and rows by YouTube's element
    names) and only for cards of the layouts it had a list for. If YouTube's menu is made of anything not in that list, or a card is of a new
    layout, nothing showed, with no message. (On the mock pages of 2.6.169 both were known, which is why they passed.)
  - **Now.** (1) When the tapped dots are surely a menu button (YouTube's menu box or its "more actions / action menu / more options" label) and no
    menu of a known kind has been seen 0.9 s later, the button shows anyway, in the middle of the window; if a menu is found later it moves above
    its first row as before. Without a menu to follow it goes with the next touch on the page, or after 9 s. (2) A card of a layout the list does
    not know is found as the nearest box around the dots that links exactly one video (no bigger than 70 % of the window, no player inside); for
    such a card only dots that name themselves (label / menu box) count.
  - **Unchanged.** Dots inside a video link are still not taken (the page's own routing opens the video for that tap), a small icon-only button
    that opens nothing still shows nothing, a playlist or channel card (several videos) gets no button. The app's own Subscriptions screen is
    native (Compose, over the page): the button is part of the page behind it, so it never shows there; that screen's own menu has its own
    "Download" row.
  - **Tried:** `wardtube.js` in headless Chromium with touch on 8 mock layouts (the old card, the `yt-lockup-view-model` card, a card + a menu of an
    unknown kind, an unknown card tag, dots inside a link, an icon-only button that opens nothing, a page with one video and a header "Menu"
    button, a card of two videos): 2.6.169 fails the two new cases and passes the rest; this version passes all eight, the bridge is called once
    with the right link and title, and the fallback button goes with the next touch. **Not tried:** not compiled here (no Android SDK) and not
    tried on a phone or against live YouTube, whose real elements may differ from the mocks. Please build it and try: tap the dots of a video on
    Home, Search, a channel and under the player. If it still does not show, please say on which page.

## 2.6.169
- Added: **a floating "Download" button over a video's three-dots menu**, a second way into a download from a card beside the long press
  (`assets/wardtube/wardtube.js`, `web/NativeBridge.kt`, `web/VideoLongPress.kt`, `MainActivity.kt`, `ui/VideoSheet.kt`, `README.md`,
  `README.ar.md`, `fastlane/.../215.txt`, `app/build.gradle.kts`).
  - **Before:** the only way into a download from a card was a long press on its picture or title.
  - **Now:** tap the three dots of a video's card (the browsing page, or the list under the player) and YouTube's menu opens as usual; a red
    "Download" / "تنزيل" button floats just above it, centred. Tap it and the app's own sheet opens (the video, "Download video", share, copy the
    link), exactly as after a long press, and YouTube's menu is closed behind it. The button shows only after a tap on a card's three dots, and
    only if a menu really opened because of that tap. It goes with the menu (a tap outside, a row or button of the menu used, back, a page change).
    A card that names no single video (a playlist or channel card) gets no button. The long press is unchanged.
  - **How:** a touch / click listener in `wardtube.js` finds the card's menu button (by YouTube's element and class names, by its label in a few
    languages, or as a small icon-only button inside a video card), notes which menu rows / sheets (`MENU_ROWS` / `MENU_SHEETS`, the ones the "Open
    app" row code already uses) are on screen, and then watches for new ones; the button waits until the menu has stopped sliding in and sits above
    its first row (or above the middle of the window when the menu is made of something unknown). The video is the card's own link and the title its
    heading. The press calls the new `NativeBridge.downloadFromMenu(url, title)` (two calls less than 0.6 s apart count as one), which reaches
    `VideoLongPress.openFromPage(view, link, title)`: the same sheet and the same conditions (`canOpenVideoSheet`) as a long press, and the link is
    checked again with `VideoLinks.parse`. `MainActivity.createBridge` now takes the WebView it is added to, so the answer opens on the page that asked.
    The button is built with `createElement` / `createElementNS` (Trusted Types) and added to `<html>`.
  - **Also:** `fastlane/.../en-US/changelogs/214.txt` was missing (only the Arabic one existed): added.
  - **Tried:** `wardtube.js` was run on mock pages in headless Chromium with touch (30 checks): three layouts of the card and menu (the old `ytm-media-item`
    with `ytm-menu` and a bottom sheet, the new `yt-lockup-view-model` with a separate scrim, a Short's card) plus one the script does not know
    (icon-only button, a `role="dialog"` layer over the whole window). The button shows above the first row once the menu has stopped sliding;
    one bridge call with the right link and title; the menu closes behind it; the page never sees the touch on the button; no button for a playlist
    card, for dots that open nothing, for the picture link, or without the bridge method; it goes on a tap outside the menu and on a row; it is
    hidden in the small box; a tall menu keeps it on screen. **Not tried:** not compiled here (no Android SDK) and not tried on a phone or against
    live YouTube, whose real menu elements may differ from the mocks. Please build it and try: (1) Home: tap the dots of a video: the button shows
    above the menu, tap it: the sheet opens, and YouTube's menu is closed, (2) the same on a search result, a channel's video, a Short's card and a
    video of the list under the player, (3) open the menu and tap outside: the button goes, (4) long press a card: it works as before. If the button
    does not show for some card, or sits on the menu, please say where (and which page).

## 2.6.168
- Added (compared with NouTube's `fullscreen-controls.ts` and `css.txt`): **a picture-in-picture button on the player** (`assets/wardtube/wardtube.js`,
  `web/NativeBridge.kt`, `MainActivity.kt`, `fastlane/.../214.txt`, `app/build.gradle.kts`).
  - **Before:** the little window only opened by itself when the person left the app with Home (and only with the menu's switch on).
  - **Now:** a round button (40 px, the app's own picture-in-picture drawing) sits on the player, on its right edge at the middle of the video's
    height (in full screen a little higher, clear of the camera of a phone turned sideways and of the display's cut-out). It shows and hides with the
    page's own controls and opens the same little window at once. It works from the normal watch page and from the system's full screen, and also
    when the menu's "Picture in picture" switch is off (that switch is only about the window coming by itself on Home).
  - **How it is placed (from NouTube):** a direct child of `#player-container-id`, NEXT TO the control overlay and not inside it (YouTube's control
    layers hit-test above anything put into the overlay, so a button there is seen but never gets the tap), shown while `#player-control-overlay` has
    the `fadein` class. If YouTube is found not to use that class, the button shows for 3.5 s after each touch on the player instead. Its touches never
    reach the page (no seek, no toggling of the controls). Built with `createElementNS` / `setAttribute` (no `innerHTML`: Trusted Types). Hidden in the
    small box and in the window (`html[data-wt-mini]`, `html[data-wt-pip]`); not on the browsing page; not on a phone without picture in picture.
  - **In the app:** `NativeBridge.enterPip()` (two calls less than 1 s apart count as one) and `NativeBridge.pipSupported()`;
    `MainActivity.enterPipFromButton()` remembers what the window is made from (`rememberStateAtLeave`, as when the person leaves with Home), asks for
    the window through the existing `enterPip()` and, if the phone refuses (picture in picture switched off for the app), shows the existing
    "Picture in picture is not available..." message. `leavingForPip()` is also true for the 1.5 s after the window was asked for, so the page is not let
    back into full screen while the window is coming, with or without the Home switch.
  - **Tried:** `wardtube.js` was run on a mock player page in headless Chromium (20 checks: shown with `fadein` and hidden without it, 40 x 40 at the
    middle of the right edge, higher in full screen, one `enterPip` per tap and the page sees no touch, hidden with `data-wt-mini` / `data-wt-pip`,
    put back after the page removes it, absent on the browsing page and when `pipSupported()` is false, the touch fallback). **Not tried:** not compiled
    here (no Android SDK) and not tried on a phone or against live YouTube. Please build it and try: (1) open a video, tap the player: the button
    shows with the controls; tap it: the window opens and the video goes on, (2) the same from full screen (landscape), (3) switch the menu's
    "Picture in picture" off and press the button, (4) tap the window to come back and press the button again. If the button is not seen, or sits on
    one of YouTube's buttons, please say where and in which mode (normal / full screen).

## 2.6.167
- Changed: **the comments button under a post in the Subscriptions screen now always shows** (`subs/SubsScreen.kt`, `res/values/strings.xml`,
  `res/values-ar/strings.xml`, `fastlane/.../213.txt`, `app/build.gradle.kts`).
  - **Before:** YouTube gives no comment count for a post without comments, and the card hid the button (and the whole row of counts when the likes
    were missing too), so such a post had no way to start the first comment.
  - **Now:** the button is always shown. With no count (empty, or "0") it reads "No comments, be the first to comment" / "لا توجد تعليقات، كن أول من
    يعلق" (`subsNoComments`); otherwise it shows the count as before. The tap is the same as for any post (it opens the post's page and taps the
    "Add a comment" box; that YouTube shows that box on a post with no comments is expected, not checked). The count label is kept to one line (ellipsis) so the longer text
    cannot push the row out of the card.
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone. Please build it and look at a post that has comments and one that has none.

## 2.6.166
- Changed: **the comments button under a post in the Subscriptions screen** (`subs/SubsScreen.kt`, `subs/SubsController.kt`, `fastlane/.../212.txt`,
  `app/build.gradle.kts`).
  - **Before:** the button did nothing of its own: the tap fell through to the card and opened the post's top.
  - **Now:** the button opens the post on YouTube's own page, opens its comments list there (the "Add a comment" box / the comments row, found by what
    the elements are and say, not by YouTube's class names) and taps the comment text box with a real touch, so the keyboard comes up by itself
    (a script's own `focus()` does not bring it up). The probe script (`SubsController.COMMENTS_JS`) answers `entry` / `input` / `wait`; the app looks
    every 0.4 s for up to about 16 s, taps at most 3 times, and stops as soon as the text box is tapped or another place is opened. If YouTube's
    comments are not found in time the person is simply on the post's page, as before.
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone or against live YouTube. The probe script was run on mock pages in
    headless Chromium (comment box below the fold, open text box, a button labelled in English or Arabic, the page still being the old one): it found
    the right element each time and left the search box alone. Please build it and try a post that has comments and one that has none.

## 2.6.165
- Fixed (compared with NouTube's `picture-in-picture.ts` and `player.ts`): **leaving the picture-in-picture window: the video showed for a moment and then
  went black while the sound played on** (`player/PlayerHost.kt`, `MainActivity.kt`, `assets/wardtube/wardtube.js`, `fastlane/.../211.txt`,
  `app/build.gradle.kts`).
  - **Cause (read from the code, not tried on a phone):** Android tells the app that the window is not picture in picture any more BEFORE its
    expansion animation has finished (NouTube's comment says the same). `exitPip` laid the player out for the full page at once, inside an area that
    was still small and landscape-shaped and then grew through several in-between sizes. YouTube's page saw those viewports (a small landscape one first,
    which it takes for a phone turned sideways) and its player could be left sized for one of them, or a full-screen request made for one of them
    attached the video to a window that was not ready: the picture was gone, the sound went on. The older nudges (`nudgeAfterExit`) tried to repair it
    afterwards.
  - **Now:** `PlayerHost.exitPip` only starts a hold (`exitHolding`): the window's picture is kept (the page stays pinned to the window's shape, the WebView
    keeps its upright layout and is only scaled to the area, as in the window) until the area has not changed size for 150 ms (at least 350 ms, at most
    1.2 s). Then `completeExit` lays the player out for real, in one step, at its final size, and the old settle sequence runs as before.
    Re-entering the window during the hold sets it up again from scratch; `close()` and `expand()` end the hold.
  - The page is not let into full screen while the window is being left (`MainActivity.playerExitHeld` in `canEnterFullscreen`), like NouTube's
    refusal while backgrounded / in the window.
  - **In the page (`wardtube.js`, `afterPipExit`), in the order of NouTube's `setPictureInPicture(false)`:** when the window's rules go, after 500 ms
    YouTube gets a `resize` and a video that was playing and was paused by the page is played again (never one that was paused on purpose); the
    video's layer is nudged (`will-change` toggle). For 14 seconds `renderTick` also WATCHES the picture: a video whose time moves while
    `getVideoPlaybackQuality().totalVideoFrames` does not is a black one, and it is brought back step by step (seek to the same second; the video
    element taken out of the layout and put back; only then the page is opened again at the same second with `t=`). Paused, buffering, seeking and
    ended videos are never touched.
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone. The page's script was syntax-checked and run against a fake page.
    Please build it and try: (1) play a video, press Home, tap the window to come back, repeat 10 times, (2) the same from full screen (landscape),
    (3) the same while the video is ending / the next one opens, (4) close the window with its X, (5) press Home again right after coming back.
    If it still goes black, please say how long after coming back, and whether it was full screen.

## 2.6.164
- Fixed (ported from NouTube's `background-guard.ts`): **the video stopped after a few seconds inside the picture-in-picture window**
  (`assets/wardtube/wardtube.js`, `player/PlayerController.kt`, `web/NativeBridge.kt`, `MainActivity.kt`, `fastlane/.../210.txt`,
  `app/build.gradle.kts`). Found by comparing with NouTube's code: YouTube's page pauses a video by itself when the window shrinks or changes
  shape, and nothing started it again. The app's own guard (`guardPipPlayback`) only looked for 3 seconds after the window came, and only when the
  video had been in full screen; from the normal watch page it did not run at all. WardTube's `keepWatching` only answers a "Continue watching?"
  prompt, it does not start a video that was paused without one.
  - **Now, like NouTube:** while the window is up the page watches its video every second and on every `pause` event. A video that was playing and is
    paused now, without the person or the app having asked for it, is played again (`pipGuardTick`): at most 3 tries in a row (counted again when the
    video is seen playing, or after 20 seconds), not while a call is on (`WardTubeNative.canAutoResume`, like NouTube's `canAutoResume`), not for a
    video that ended, and not for a moment after next / previous (the old video pauses while the page changes over).
  - **A pause that was asked for is never fought:** every `PlayerController.play / pause / toggle` first tells the page (`window.__wardtube.wantPlay`):
    the window's play / pause button, the notification, a headset, the sleep timer and the Library all go through it.
  - The app's own guard now also runs when the window comes from the normal watch page (not only after full screen), and looks for 8 seconds
    instead of 3 (`PIP_RESUME_CHECKS_MS`).
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone. The page's guard was run against a fake page (pause by YouTube is
    undone, pause by the person is not, 3-try limit, call, ended video, next / previous). Please build it and try: (1) play a video, press Home,
    let it run for a minute in the window, (2) the same from full screen, (3) press pause in the window (it must stay paused), then play,
    (4) next / previous from the window. If it still stops, please say after how many seconds, and whether it was full screen.

## 2.6.163
- Fixed: **a black picture-in-picture window when a video is opened and the app is left at once** (`MainActivity.kt`, `fastlane/.../209.txt`,
  `app/build.gradle.kts`). The cause is one line, found by reading the code, and it was there in 2.6.161 and 2.6.162: `openVideo` set `videoPlaying = true`
  ("a video starts by itself: leaving the app at once already means picture in picture"). That armed the little window (Android 12+) or let
  `onUserLeaveHint` ask for it (before 12) while the player's page was still loading, so the window opened with no picture to show: black.
  - **Now, like NouTube** (whose native side stays disarmed until the page reports a picture): `openVideo` sets `videoPlaying = false` and
    sends the params at once (the window is disarmed), and the window is armed only when the page reports a picture that really plays
    (`onPageVideoReport`, added in 2.6.162) or, for a video that was already open, when the poll sees it playing (up to 3 seconds).
  - **What the person sees:** leaving the app before the video has started no longer opens a window; the app goes to the background and, with background
    play on, the video plays as sound (as it already did without the window). Leaving after the video plays opens the window as before.
  - **Not tried:** not compiled here (no Android SDK) and not tried on a phone. Please build it and try: (1) open a video from the home page and press Home
    at once (no black window), (2) open a video, wait until the picture moves, press Home (the window comes with the picture), (3) tap a video link
    while another one plays in the floating box and press Home at once (no black window), (4) the same video's link tapped again while it plays
    (the window may need up to 3 seconds before it comes). If a black window still shows up, please say whether the video had started and which Android version.

## 2.6.162
- Fixed (ported from NouTube's player injection: `picture-in-picture.ts`, `split-player-window.ts`): **picture in picture is switched on the way
  NouTube does it** (`assets/wardtube/wardtube.js`, `web/NativeBridge.kt`, `MainActivity.kt`, `fastlane/.../208.txt`, `app/build.gradle.kts`).
  What was wrong, found by reading the code (not by seeing it fail on a phone):
  1. **The app learned "a video is playing" only from its own poll** (`keepAwakePoll`: every 3 seconds while the video is full screen).
     The window is armed (Android 12+: `setAutoEnterEnabled`) or asked for (`onUserLeaveHint`) from that value (`videoPlaying`), so pressing Home
     within those seconds of pressing play found no window at all, and pressing Home just after pausing still opened a window with a paused video.
  2. **YouTube's own check for "this is the little window / the background"** compares the page's *outer* window size with the screen and pauses the
     video. The WebView reports its own size as the outer size, so while the window shrank (before the app lays the page out at full size again) the
     player could stop by itself. `guardPipPlayback` only started it again afterwards (at 0.6 / 1.5 / 3 seconds).
  - **Now, like NouTube:** the player's page reports at once, on every `play`, `playing`, `pause`, `ended`, `emptied`, `loadedmetadata` and
    `resize` of its video and on every page change, the picture's size while a video plays and 0 x 0 when none does
    (`WardTubeNative.setPipVideo` -> `MainActivity.onPageVideoReport`). The window is armed from that the moment the video starts and disarmed the moment
    it pauses; the picture's own shape is known at once too (`videoAspect`, so the window's shape is right). The window's own play/pause button keeps its
    1.5 second grace, as before. Only the player's page reports (`route` "player"); an older page script without the call keeps working through the poll.
  - **And, like NouTube's `split-player-window.ts`:** in the player's page `outerWidth` / `outerHeight` never report less than the screen's size
    (`Math.max(real, screen)`), so YouTube's check never sees a small window and does not pause. `innerWidth` / `innerHeight` are the real viewport and are
    untouched, so the layout and the app's own picture-in-picture box (`PlayerHost.enterPip`) are exactly as before. `guardPipPlayback` stays as a safety net.
  - **Not changed on purpose:** the window's own layout code (`PlayerHost.enterPip` / `applyPip`, the page's `data-wt-pip` rules), the Android 12+ rule that
    the system enters the window by itself when armed, and the menu switch. NouTube instead lets the WebView follow the small window and fills it with
    the video by CSS; that is a different layout and was not copied, because it would replace code that works.
  - **Not tried:** the Kotlin was not compiled here (no Android SDK; names and brackets were checked by reading), the script was only syntax-checked
    (`node --check`) and its outer-size part run against a fake window, and nothing was tried on a phone. Please build it and try: (1) start a video and
    press Home at once (the window must come), (2) pause a video and press Home (no window must come, or on Android 12+ the app goes to the background as usual),
    (3) play from the window's own button, pause, play again, (4) watch a few minutes in the window (the video must not stop by itself), (5) tap the window to
    return to the app (full screen and the floating box both come back as before). If the window still fails, please say **what you see** (no window, black,
    paused, cut-off picture, from full screen or from the box) and the phone's Android version: that decides which of the two causes is the real one.

## 2.6.161
- Fixed: **a post's poll and its like work from the card in the Subscriptions page** (`subs/SubsModels.kt`, `subs/SubsRepository.kt`,
  `subs/SubsScreen.kt`, `subs/SubsController.kt`, `res/values*/strings.xml`, `fastlane/.../207.txt`, `app/build.gradle.kts`). Before, the
  card showed the poll's choices and the like count but did not own the taps: any tap fell through to the card, which opened YouTube's page
  of the post, and the vote or the like had to be made there.
  - The post's data already carries what YouTube's own page uses to vote and like, and the app was throwing it away. Now it is kept:
    each poll choice's `selectServiceEndpoint` / `deselectServiceEndpoint` and its `votePercentageIfSelected` / `votePercentageIfNotSelected`
    / `selected`, and the like button's `defaultServiceEndpoint` / `toggledServiceEndpoint` / `isToggled` (`PostChoice`, `SubsPost`; also
    in the phone's saved copy of the feed, under new keys, so an older saved copy simply has none).
  - **Voting:** a tap on a choice draws the vote at once (the voted choice in the accent colour and bold, every row with its share as a bar
    behind the words and the percentage at the end, as YouTube's page does) and sends YouTube's own command in the background
    (`SubsRepository.runCommand`, through the same signed-in `postApi` that the video menu's "Save to playlist" already uses). A second tap on the
    voted choice takes the vote back (when YouTube gave a deselect command). A second tap while YouTube is still being asked is ignored.
  - **Liking:** the thumb on the card is now a button (a 6 dp padding around it makes it an easy target; the row's own padding was reduced by the
    same amount, so nothing moves). It turns accent-coloured and a plain number count moves by one (`41` → `42`; a short form like `1.2K` stays);
    a second tap takes the like back.
  - **If YouTube does not take it** (no sign-in, no network, a refused call) the card goes back to what it was and a message says so
    (`subsPostFailed`: "open the post and try from its page"). **A choice or a like with no command** (a saved copy from before this version,
    or a page that gave none) is not tappable and behaves exactly as before: the tap opens the post. Nothing else on the card changed (the card tap,
    the three dots, links, pictures, the attached video, the comments count).
  - What the person did stays for the session (`PostLive`, by post id), so a refresh that reads the post again does not undo it on screen.
  - **Not tried:** the Kotlin was not compiled here (no Android SDK; the references and the brackets were checked by reading), and nothing was
    tried against the live YouTube or on a phone. The field names above are the ones YouTube's page is known to use (the poll's
    `selectServiceEndpoint`, `votePercentageIf*`, the like button's `toggleButtonRenderer`), but the exact body of the vote call is built generically
    (the endpoint's own `apiUrl` and its own fields; a like sends `target` and `params`; a comment action sends `actions`), so it is **not confirmed
    that YouTube accepts it**. Please build it and try: (1) vote on a poll in the Subscriptions page, (2) tap the voted choice again, (3) like and unlike
    a post, (4) turn the network off and vote (the card must go back and say so), (5) open the post to see that the vote or the like is really there.
    If a vote or a like is refused, the app puts it back and shows the message with a short reason in brackets (`HTTP 400`, `refused`,
    `nothing to send`, `no key`...): that reason is what is needed to correct the call, so please send it.

## 2.6.160
- Added (ported from NouTube's player injection: `background-guard.ts`, `audio.ts`, `h264ify.ts`): **three things for long listening on a
  phone** (`assets/wardtube/wardtube.js`, `Prefs.kt`, `web/PageScripts.kt`, `ui/MenuController.kt`, `res/values*/strings.xml`,
  `res/drawable/ic_menu_battery.xml` (new), `fastlane/.../206.txt`, `app/build.gradle.kts`).
  1. **A long playback in the background no longer stops at "Continue watching?"**.
     - YouTube's "last activity" clock (`window._lact`) is refreshed every minute, so the page never looks abandoned (NouTube does the same).
     - If the prompt shows up anyway, it is answered and the video plays again. NouTube's guard asks the shell whether the app is in the
       background and whether the pause was the user's; WardTube has no such flag, so the page decides by what it can see: the prompt
       (`ytmusic-you-there-renderer`, `ytm-confirm-dialog-renderer`, `yt-confirm-dialog-renderer`) must be in the page, the video must be paused,
       it must have been playing within the last 30 s, and a button is only clicked when the prompt has exactly one (with more, the video is
       just played again). At most three tries, two seconds apart. A pause from the notification or from the page's own button, with no
       prompt in the page, is never resumed.
  2. **Automatic dubbing: the original audio track is chosen** (the player's page only). When a video has two or more audio tracks, the
     one YouTube labels "original" (the 94 labels of NouTube's `audio.ts`, so also Arabic: «أصلي» / «الأصلية») is chosen once per video, as
     soon as the tracks are known (up to about 30 s). A track you pick by hand afterwards stays. Nothing is done when the video has a
     single track, no track is labelled original, or the player is already on it. There is no switch for it.
  3. **"Prefer H.264" switch** (Menu > Playback, off by default). The page tells YouTube it cannot play WebM / VP8 / VP9 / VP09 / AV1
     (`MediaSource.isTypeSupported`, `canPlayType` of the video element, and `mediaCapabilities.decodingInfo`), so YouTube sends H.264 video and
     AAC audio, which phones decode in hardware: less heat, less battery. YouTube offers H.264 up to 1080p only. It must be in place before
     YouTube's scripts ask, so it is part of the document-start script; the switch registers the script again for both WebViews and reloads
     the browsing page, and a video that is already playing changes format when the next one opens. (`vp09`, VP9 named inside an mp4, was added
     to NouTube's list: it is not covered by `vp9`.)
  - Tried in headless Chromium on fake watch pages (24 checks: the codec filters on / off / key missing, `_lact` refreshed twice, the prompt
    answered with one and with two buttons, no action on a pause without a prompt or on a prompt long after a pause, the original track
    chosen with Arabic and English names, tracks that appear late, already on it, picked by hand, no label, one track, the next video, and
    the browsing page untouched). 12 of them fail on 2.6.159's script. **Not tried:** the Kotlin side was not compiled here (no Android SDK;
    the references were checked by reading), and nothing was tried against the live YouTube page or on a phone, so the real names of the
    prompt's elements and of the audio track objects are NouTube's, not confirmed here. Please build it and try: (1) leave a long video or a
    playlist playing in the background for a few hours, (2) a video with automatic dubbing (Arabic interface), (3) the new switch, then open a
    video and look at "Stats for nerds" for the codec (`avc1`, not `vp09` / `av01`).

## 2.6.159
- Fixed: **the picture-in-picture window no longer depends on timing** (`player/PlayerHost.kt`, `MainActivity.kt`, `web/NativeBridge.kt`,
  `assets/wardtube/wardtube.js`, `app/build.gradle.kts`). The symptoms reported over the versions (the video cut off, black under the
  picture, the picture in the wrong part of the window, the video going black after the window) had one cause.
  - Cause: the app MEASURED where the player's box ended up in YouTube's page and fitted the WebView to what it found, while YouTube
    was still laying the page out (leaving the system's full screen, the viewport changing from sideways to the window's size to upright,
    the next video opening). What a measure found depended on the moment, so each version added a timer to catch the moment: six
    settle looks, a "do not trust a box that is not pinned" window of 6 s, a nudge of the page 6 px narrower, a measure every 2 s.
  - Now it is the other way round, and nothing is measured in the window. The app decides the box: at the page's top-left corner, as wide
    as the page and `aspect` high (never higher than the page), where `aspect` is the shape the system's window was given
    (`MainActivity.pipRatioInEffect`, the last params handed to Android; kept for as long as the window is up, so what the page reports can
    not feed back into the window's shape). The page is told the shape (`PlayerHost.pipAspect`, asked through the new
    `NativeBridge.pipAspect()` on every tick and at script start, and sent with `setMini(true, aspect)`), and the page script pins every
    element from the video up to the body to that box with `!important` CSS (`html[data-wt-pip]`, the elements marked with the attribute
    `data-wt-pip-box`) and makes the video fill it (`object-fit: contain`), whatever size YouTube's inline styles give its player at that
    moment. The page's own controls are hidden in the window (the system draws its own).
  - The WebView is still laid out at the screen's upright size and only scaled down, as before (so the page does not reflow, and YouTube does
    not see a landscape-shaped window, which makes it go full screen). Nothing in YouTube's layout is disturbed while the window is up, so when
    it is left and the marks go, the player is as it was; the page is told to lay out again (three resize events) in case it was not.
  - Removed as no longer needed: `pipSettle` (the six looks), `pipStrictUntil`, the in-window `nudgePage` and its counters, `relayoutPage`, and
    the in-window parts of `measureVideoBox` (the picture's-own-box fit). Not touched: the floating box's measuring, the exit settle after the
    window (`startExitSettle`, `nudgeAfterExit`), the keep-playing guard (`guardPipPlayback`), and the Library player's own window
    (`PlayerActivity`).
  - Tried in headless Chromium on fake watch pages (11 cases, 45 checks: the box pinned at 16:9, 4:3, 21:9 and vertical shapes; YouTube resizing
    its player behind the page's back; the video element or its wrappers being swapped; entering before the video exists; leaving for the
    floating box and for full screen; an old app without `pipAspect`; the browsing page untouched). The same cases fail on 2.6.158's script
    (23 failures), which is how the checks were shown to mean something. **Not tried:** the Kotlin side was not compiled here (no Android SDK
    here; only the references and brackets were checked by reading), and nothing was tried against the live YouTube page or on a phone.
    Please build it and try, in this order: (1) the window from the normal watch page, (2) from the system's full screen (Home while it
    plays), (3) tap the window to come back, (4) a video that ends in the window and the next one opening, (5) the window from the floating
    box.

## 2.6.158
- Fixed (a guess at the cause, see below): **in a mix, the list of the mix opened by itself while the player was a small box or the
  picture-in-picture window, and the video went black** (`assets/wardtube/wardtube.js`, `app/build.gradle.kts`).
  - What the code shows: nothing in the app opens the list. The only clicks the page script makes are on the next / previous video, a
    suggested video and the ad skip button. So it is YouTube's page opening the list's sheet (the title with the shuffle and repeat
    buttons, and the videos), and while the player is a box the page is hidden around the video (`html[data-wt-mini]`), so nobody can see
    or tap the sheet and the box can be left showing black.
  - Now, while the player is a box (the floating window or picture in picture), the page script looks about twice a second for a sheet
    that is open: a layer above the page (fixed position or a dialog, not the page's own frame around the video) that holds the links of
    the current list's videos. If it finds one on screen it presses its close button (found by its label: Close / إغلاق and the other
    common languages), one try every 1.5 s. The full-screen player is never touched, so a list you open yourself there stays open.
  - The sheet is found by what it holds, not by YouTube's element names; if YouTube's page differs from this, nothing is done.
  - Tried in headless Chromium on fake watch pages (seven cases: the sheet opening in the box and being closed, English and Arabic
    labels, the sheet already open when the player became a box, and no action for the full-screen player, a list inside the page, the
    page's own fixed frame, and a closed sheet pushed off screen). **Not tried against the live YouTube page** (no network here); the
    Kotlin side was not touched.

## 2.6.157
- Fixed: **with a video open, the sideways swipe did not open the side menu** (`MainActivity.kt`, `player/PlayerHost.kt`, `app/build.gradle.kts`).
  - Cause: the swipe was only watched on the browsing page's WebView, and the video is a different WebView (the player's); on top of that
    `sideMenuMayOpenBySwipe()` refused to open the menu whenever the player was full screen (`PlayerHost.Mode.EXPANDED`). So the swipe worked
    on Home, Subscriptions and the other pages but never with a video open (the three-bars button always did).
  - Now the player's page reports a quick sideways swipe too (`PlayerHost.trackSideSwipe` -> `onSideSwipe`), with the same rules as on the
    browsing page (long enough, quick, not slanted, toward the middle from the drawer's side). It is only watched, never consumed.
  - A swipe that begins on the video itself (its box plus a little below it, where the seek bar is), or on a row that scrolls sideways (the
    chips, a shelf), keeps its own meaning and does not open the menu (`PLAYER_SWIPE_PROBE_JS`). The swipe down that minimizes the video is
    untouched. Landscape full screen, the little window and picture in picture still never open it.

## 2.6.156
- Changed: **in the Library, the swipe between the filters (All / Video / Audio / Lyrics) is now a real drag: the list follows the finger and
  changes smoothly** (`ui/FilterSwipeLayout.kt` (new), `ui/LibraryActivity.kt`, `res/layout/activity_library.xml`, `app/build.gradle.kts`).
  - The list (with its empty-state text and spinner) moves with the finger while it is dragged and fades a little the further it goes. Letting
    go after a quarter of the width, or with a quick flick, carries it out of the way, the new filter's files go in, and the new list slides
    in from the other side, slowing down into place. Letting go sooner settles it back where it was.
  - At the first and the last filter (nothing further that way) the list only gives a little, as if held by a rubber band, and returns.
  - The chips follow the moment the swipe is accepted, not after the animation. A new touch while the last swipe is still moving ends it at
    once, so swiping twice in a row works.
  - The rows' own fade / move animations are switched off for that moment: before, the old rows faded out one after the other and the new
    rows only appeared after the list had already slid in. Everything else is as before: taps, the long press that starts the range
    selection (a finger that stays put as long as a long press never starts a swipe), the vertical scrolling, and the files ticked in the
    multi-select mode staying ticked across filters.
  - The same test as a ViewPager decides what is a swipe (clearly sideways: twice as wide as tall, past the touch slop), and a vertical scroll
    that drifts sideways is never taken for one. The old "quick fling at least 64dp" rule is gone.

## 2.6.155
- Changed: **the side menu mode no longer wastes space at the top: the bar that holds the three-bars button is slimmer and slides away while
  scrolling, and the menu opens by a sideways swipe too** (`MainActivity.kt`, `subs/SubsScreen.kt`, `subs/SubsController.kt`,
  `ui/MenuController.kt`, `ui/SideMenu.kt`, `Prefs.kt`, `res/layout/activity_main.xml`, strings in English and Arabic, `app/build.gradle.kts`).
  - The top bar is 40dp high instead of 48dp, and it hides like the bottom bar does: scrolling the page down slides it up and away together
    with the page (the page moves as one piece and grows into the freed room once the slide is done), scrolling up brings it back. It stays on
    Shorts, on a video that is open, and after a new page starts loading, as the bottom bar does.
  - **"Hide bar while scrolling" works in the side menu mode now** (it was disabled there in 2.6.154): the same switch governs the top bar. Its row
    is no longer dimmed and the "Not available with the side menu" note is gone.
  - **A quick sideways swipe over the page opens the side menu** (to the left in Arabic, toward the middle of the screen from the drawer's side),
    anywhere, not only from the screen's edge (where the system's Back gesture sits). It must be long (72dp), quick (under 0.7 s) and clearly
    sideways; scrolling, slow drags and pinch zoom never count. A row of the page that scrolls sideways (YouTube's chips, the Shorts shelf) or a
    slider keeps its own swipe: the page is asked what is under the finger (`SWIPE_PROBE_JS`) and the menu opens only when it is not one of those.
    The Subscriptions screen does the same (not over its strip of channels, its chips or a row of Shorts, and not while the list of every channel
    is open). Dragging from the edge still works. Nothing opens while a video is full screen, in the little window, or in the normal (bottom
    bar) mode.

## 2.6.154
- Added: **a "Side menu" switch in the three-dots menu (Display): the bottom bar becomes a side menu, opened by a three-bars button in a bar at
  the top of the screen** (`MainActivity.kt`, `ui/SideMenu.kt` (new), `ui/MenuController.kt`, `Prefs.kt`, `res/layout/activity_main.xml`,
  `res/drawable/ic_hamburger_white_24dp.xml` (new), strings in English and Arabic, `app/build.gradle.kts`: `androidx.drawerlayout`).
  - Off by default: nothing changes until the switch is turned on, and it takes effect at once (no restart). Turned off, the bottom bar is back.
  - On: the bottom bar is gone and a slim bar at the top (under the status bar) holds the three-bars button and the app's name. The button
    opens a drawer from the start edge (the right in Arabic); dragging from that edge opens it too, Back closes it first, and it is
    closed and locked while a video is full screen or in the little picture-in-picture window.
  - The drawer has the six buttons of the bar as rows, doing the same things (`MenuController.perform`): voice search (the minimize arrow while a
    video is full screen), Home, Browser, Download (dimmed while there is nothing to download, as in the bar), Library, and More (the three-dots
    menu, where the switch is turned off again).
  - The page's loading line moves to the lower edge of the top bar, the notices (load error, tip) sit at the bottom of the page instead of above
    the bar, and the space for the system navigation bar is kept at the bottom.
  - **"Hide bar while scrolling" is disabled in this mode**: its row is dimmed and says "Not available with the side menu", it takes no taps, and
    nothing slides away while scrolling (`MainActivity.setBarHidden` / `onPageScrolled` do nothing). Its value is kept for when the bottom bar comes back.

## 2.6.153
- Added: **in the Library, a sideways swipe on the list moves to the next filter: All -> Video -> Audio -> Lyrics, and the opposite swipe goes
  back** (`ui/LibraryActivity.kt`, `res/layout/activity_library.xml`).
  - In right-to-left (Arabic) the swipe to the right goes to the next filter, the way a page turns; in left-to-right it is the other way
    round. There is no wrap-around: on "All" the back swipe, and on "Lyrics" the forward swipe, do nothing.
  - Only a clearly sideways, long and quick swipe that starts on the list counts (not a vertical scroll that drifts, not a touch on the chips,
    the search field or the mini player's seek bar), and a long press (range selection) is never taken for a swipe. Rows, taps and scrolling
    work as before. The chip row follows (it scrolls to the new chip on a narrow screen), the new list slides in from the side of the swipe,
    and the files ticked in the multi-select mode stay ticked across filters.

## 2.6.152
- Changed: **when the built-in player of the Library starts to play, the video that plays on a YouTube page or on a page of the browser
  pauses instead of going on under it** (`web/WebMedia.kt` (new), `media/MediaPlayerService.kt`, `MainActivity.kt`,
  `browser/BrowserActivity.kt`, `browser/MediaSniffer.kt`).
  - It is the other direction of 2.6.148 (a video of a page that starts pauses the Library's player). Before, a video from the Library
    played over the YouTube video (or the video of a page) that was already playing, and both were heard together.
  - `MediaPlayerService` listens to its player: when it really starts to play (`onIsPlayingChanged`), `WebMedia.pauseAll()` asks each
    screen that owns WebViews to pause. A screen registers itself while it exists (`WebMedia.Host`, held weakly).
  - Main screen: YouTube's player is paused (`PlayerController.pause()`), and every video with sound of the page being browsed (a Short,
    a preview that was unmuted) too. Browser screen: the video of the open page, and of the frames inside it (a site's own player is
    usually an iframe): the page script already in each frame (`MediaSniffer.FRAME_SCRIPT`) pauses when its parent asks, and passes the
    request on to its own frames. A frame answers only to its parent, so a frame of an ad cannot pause the page's video.
  - A muted video (a preview, a silent loop) is left alone, as it is in 2.6.148. Nothing starts the paused video again by itself: its own
    play button does (and that pauses the Library's player, as in 2.6.148).

## 2.6.151
- Changed: **the screen stays on while the words of a song are on the screen and the song plays** (`media/PlayerActivity.kt`).
  - Before, the screen was kept lit only while a picture played (a video); a song, with or without words, let the phone's screen timeout
    turn it off, so the words could not be followed without touching the phone.
  - Now the window flag that keeps the screen on is also set while the lyrics view is shown (a song or a video in Audio only that has a
    lyrics file) and it plays (also while it waits for data). It goes off when the song is paused, ends or fails, when the words are
    not shown (a song with no lyrics file still lets the screen time out), and when the player screen is left.
  - It keeps the screen from timing out only: the power button still locks the phone, and then the song goes on playing.

## 2.6.150
- Changed: **a video that is played as sound only (the headphones button of the built-in player) now shows one of its own frames as the
  cover, instead of the big note** (`media/PlayerActivity.kt`).
  - Before, a video in Audio only had no picture to show, so the music panel (and the words of its lyrics, if it has them) sat on black
    with a note on it. Now the frame is drawn behind the panel, the way a song's own cover is, and the note is gone.
  - The frame is the one the notification already uses (`ThumbnailLoader.decodeCover`, a second into the video): it is made off the main
    thread, once for each video, and replaced when the next video comes. A video whose frame cannot be made keeps the note.
  - Bringing the picture back, or playing a song, removes the frame. Nothing changes for songs, or for a video that plays with its picture.

## 2.6.149
- Fixed: **a download with subtitles / lyrics (LRC, SRT) failed completely when YouTube refused one of the subtitle files** (HTTP 429,
  "Unable to download video subtitles for 'ar'") (`download/DownloadService.kt`).
  - Cause: yt-dlp treats a failed subtitle file as an error and stops the whole job, video included. The comment in the code said it was
    only a warning; it is one only when errors are ignored. The Arabic file of a video in another language is the usual case: it is an
    automatic caption translated by YouTube, and those requests are the ones YouTube limits first.
  - Now the download passes `--ignore-errors` when subtitles are asked for: a refused subtitle is a warning, the video / song is saved, the
    other languages go on, and the existing message "Saved without the subtitle file" tells the person. A failure of the media itself still
    fails the job (yt-dlp ends with an error code). A "subtitles only" job that gets no file still ends with "subtitles not available".
  - `--sleep-subtitles 3`: a pause between subtitle files, so that two languages in a row are less likely to be refused.

## 2.6.148
- Changed: **when a video starts to play with sound on a YouTube page or on a page of the browser, the built-in player of the Library
  (the video or song that plays from there) pauses instead of playing over it** (`media/MediaPlayerService.kt`, `web/NativeBridge.kt`,
  `assets/wardtube/wardtube.js`, `browser/MediaSniffer.kt`).
  - Before, both went on playing together: a WebView does not ask Android for the sound (audio focus), so the built-in player, which
    pauses only when another app takes the sound, never heard that a video of the app's own pages had started.
  - The page script of YouTube (`wardtube.js`) and the script the browser already injects to look for media (`MediaSniffer`, also
    inside the iframes of a page) now listen for the `playing` event of any video / audio, and tell the app through the bridge
    (`NativeBridge.mediaPlaying()`, `MediaSniffer.Bridge.playing()`). Both call `MediaPlayerService.pauseForOtherMedia()`, which pauses the
    built-in player on the main thread (only when it is playing).
  - A muted video (a preview, a decoration) does not count. A page can call the bridge too, but all it can do is pause that player.
  - Nothing starts the built-in player again by itself when the video stops: its play button (screen, notification, headset) does.

## 2.6.147
- Fixed: **2.6.146 did not build** (`media/QueueCycle.kt`): `DefaultShuffleOrder` is not a class of its own in Media3, it is nested in
  `ShuffleOrder`, so the import `androidx.media3.exoplayer.source.DefaultShuffleOrder` was not found. It is now imported as
  `androidx.media3.exoplayer.source.ShuffleOrder` and used as `ShuffleOrder.DefaultShuffleOrder(...)`. Nothing else changed: the queue
  that goes round and round (see 2.6.146 below) is the same.

## 2.6.146
- Changed: **the queue of the built-in player goes round and round: an item that has played goes to the bottom of the list instead of
  disappearing, and when the list is finished it starts again from the first** (`media/QueueCycle.kt` (new), `media/MediaPlayerService.kt`,
  `media/PlayerActivity.kt`, `media/LibraryQueue.kt`, `media/QueueSheet.kt`, `res/values/strings.xml`, `res/values-ar/strings.xml`).
  - Before, the items that had played (or the one that had just finished) were not listed any more: they were still in the player's queue,
    but the list showed only what came after the playing item, and when the last one ended the player stopped.
  - **Plain order (no shuffle):** the playing item is now always the first of the queue, and the items before it (played, or skipped by a tap
    on a later row, or the one that "Previous" came back from) are moved to the end of the queue in their order. So the queue is exactly the
    order of play: what comes next is under the playing item, and what played is at the bottom. When the last one has played, the first
    comes again, in the same order as at the start. Only items that are not playing are moved, so the sound / picture does not stop or jump.
  - **Shuffle:** Media3 plays one random order and, with "repeat all", plays that same order again and again. Now, when a round is over (the
    player comes back by itself to an item it already played in this round), a new random order is made for the next round, with the
    playing item first, so every round is a different random list. "Previous" in a shuffle goes one step back in the round and does not
    start a new one.
  - This lives in the player's service (`QueueCycle`, a `Player.Listener` added in `MediaPlayerService`), not in the screen, so it works the
    same when the screen is closed and the music goes on.
  - A queue of several files now starts with "repeat all" (when the repeat button was on "off"), so the first comes again after the last
    and "Previous" on the first goes back to the one that played before it; a single file does not repeat. "Repeat one" is kept as chosen,
    and the repeat button still changes it (`media/PlayerActivity.kt`, `start`).
  - The "whole Library" mode puts the Library's items after the playing one right after it and the ones before it at the end of the queue,
    so the Library's order goes round too (`media/LibraryQueue.kt`, `placeAround`).
  - The queue sheet: with no shuffle the "Next" label is shown with "repeat all" too (the order of the queue is the order of play), and
    its second line says how many items there are in the queue instead of "Playing 1 of N", which would always say 1 now
    (`playerQueueCount`, `playerQueueCountLibrary`).

## 2.6.145
- Fixed: **the screen went dark after a while while a video was playing in the built-in player** (`media/PlayerActivity.kt`).
  - The player screen never asked Android to keep the screen on, so the phone's screen timeout turned it off although the video
    went on playing. The web player never had this, because `MainActivity` sets the window's keep-screen-on flag while the video
    really plays. The built-in player now does the same: the flag is set while a picture is playing (also while it waits for
    data, so a slow connection does not let the screen go dark) and taken off when the video is paused, ends, fails, is turned to
    sound only (the headphones button), or when the screen is left (home, another app); it is set again when the screen returns.
  - It stays on while the video is in the little picture-in-picture window, as in the web player.
  - A song is not affected: it has no picture to watch, so its screen times out as in any music player.

## 2.6.144
- Fixed: **in the queue, the items that were already played showed right under the playing item's card, as if they came next**
  (`media/QueueSheet.kt`, `res/layout/item_queue_next.xml`).
  - Cause: 2.6.143 kept every item of the queue in the list, in the order of the queue, and only put the "Next" label in the
    place of the playing item. The played items were above that label, but the list could not scroll far enough to push them out
    of view when only a few items were left after the playing one, so they stood between the card and the label.
  - Now, while the queue is in its plain order (no shuffle, no "repeat all"), the items before the playing one are not listed at
    all: their lines have no height, so the list begins with the "Next" label and what comes after the playing item. They are
    still in the player's queue (the Previous button goes back to them); the list only does not offer them as what comes next.
  - With shuffle or "repeat all" the order of the queue says nothing about what comes next (with shuffle any item can come; with
    "repeat all" the first ones come again after the last), so then every other item is listed, without the "Next" label, and only
    the item the player goes to next is marked "Up next"; the list opens at it. Changing shuffle or repeat while the sheet is open
    changes the list at once.

## 2.6.143
- Changed: **in the queue, the item that is playing now is alone in a card at the top that never moves** (`media/QueueSheet.kt`,
  `res/layout/bottom_sheet_queue.xml`, `res/layout/item_queue_next.xml`), like the queue of the Wardio player.
  - Under the header there is now a label "Playing now" and, under it, one card with the playing item: its picture with the
    moving bars, its name, its place in the queue (`3 of 12`) and the x button. A tap on the card pauses / resumes it. The card
    is outside the list, so it stays where it is while the rows below scroll.
  - The list under the card has the rest of the queue, in the same order as before, with a small "Next" label in the place the
    playing item has in that order: the ones after it are under the label, and the ones before it are above it (a scroll up).
    The sheet opens with that label at the top of the list. Dragging, swiping away, the x button and a tap on a row work as
    before, and no row can be dragged across the playing item's place. When nothing comes after the playing item the label is hidden.
  - The part of the screen the sheet can take is now counted with the card in it (the list gets what is left, never less than a
    few rows), so the sheet does not grow past the screen, also sideways. The extra room that was added under the last row so
    the playing item could reach the top is gone: the playing item is no longer in the list.

## 2.6.142
- Changed: **the song's name is no longer drawn over the song's picture in the player** (`media/PlayerActivity.kt`,
  `res/layout/activity_player.xml`).
  - The music panel showed the title a second time (small, at the top of the picture when the song has words, big in the
    middle when it has none), right under the title bar that already says it. The extra title and everything that fed it are
    gone; the name stays only in the title bar, which is always on screen while a song plays. The picture, the words' card and
    the controls are as they were.

## 2.6.141
- Changed: **the words of a song are shown the way the Wardio web player shows them** (`media/LyricsView.kt`,
  `media/PlayerActivity.kt`, `res/layout/activity_player.xml`, `res/drawable/bg_lyrics_card.xml`).
  - The long list of every line (with its faded, shrinking rows and the dark veil over the picture) is gone. In its place is
    one dark, soft card at the bottom of the song's picture: the line being sung in the accent colour and bold, and the line
    after it right under, plain. When the song moves on, the new line fades in with a small lift.
  - The letters are no longer scaled by an animation (the old rows grew and shrank as the song moved, and the highlighted line
    showed broken letters on one phone): the card only fades and moves, and the text keeps its own size.
  - The picture is shown as it is, with no veil over it; the title above it has a soft shadow so it stays readable on it.
  - The card takes no touch: the double tap, the swipe and the tap that shows the controls work over the words as everywhere else.
    (Tapping a line no longer jumps to it; `PlayerGestures.lyricsListener` / `afterTapWindow` are not used by the player now.)
  - Before the first line of a song is reached, the first line is shown, as the web player does. A pause row (the "♪") is
    shown as the sung line during the pause, and is never offered as the line to come.
- Added: **a "rotate screen" button in the video player's controls** (`media/PlayerActivity.kt`,
  `res/drawable/ic_screen_rotation_24dp.xml`, `res/values/strings.xml`, `res/values-ar/strings.xml`).
  - It sits in the controller's own row of buttons (with shuffle, repeat and the subtitles, just before the subtitles button),
    not in the title bar. Shown only while a picture is playing.
  - One press turns the screen sideways when it is upright and upright when it is sideways, and keeps it that way (either end
    of the axis, by the sensor) until the button is pressed again or the player screen is closed.
- Fixed: **the queue did not open with the playing item at the top** (`media/QueueSheet.kt`).
  - The scroll to the playing item was asked before the list had its size, and a list also stops at its end, so an item near the
    end of the queue could not reach the top. The scroll now waits for the list to be laid out, and a queue longer than the sheet
    gets room under its last row so the playing item can always be the first one in view (the ones before it are a scroll up).
    The same happens after the Library button finishes changing the queue.

## 2.6.140
- Fixed: **the release build failed with "Type checking has run into a recursive problem"** (`media/PlayerActivity.kt`).
  - `queueSheet` and `libraryQueue` are `by lazy` properties that refer to each other (the queue sheet takes the library queue,
    and the library queue calls `queueSheet.onLibraryChanged()` when it changes). With no declared type the compiler could not
    infer either of them, and also reported `onLibraryChanged` as unresolved. Both now declare their type (`QueueSheet`,
    `LibraryQueue`). Nothing changes at run time: the call back to the sheet only happens after both exist.

## 2.6.139
- Fixed: **the player crashed on Android 16 (API 36) when the words of a song were shown** (`media/LyricsText.kt`,
  `media/LyricsParser.kt`, `media/LrcToSrt.kt`, `download/SubtitleCleaner.kt`).
  - The crash was an `ExceptionInInitializerError` from `PatternSyntaxException` in `LyricsText`: the pattern for notes in
    brackets had a bare `}` (`\{[^}]*}`), which Java accepts but the newer ICU regex engine of Android 16 rejects. It broke on the
    first caption line of a song, so the player closed.
  - Every regex of the app that had a bare `}` or `]` outside a character set now escapes it (`\}`, `\]`), and a `]` first in a
    set is escaped too (`[\])]`): the lyrics parser's tag pattern, the LRC stamp and offset patterns, and the subtitle cleaner's LRC and
    tag patterns. They match exactly what they matched before.

## 2.6.138
- Changed: **the "Up next" mark of the player's queue follows the player's real next item** (`media/QueueSheet.kt`).
  - It used to mark the row under the playing one. With **shuffle on** the next item is another one, so the mark was wrong: it
    now asks the player (`nextMediaItemIndex`), which also knows repeat (with repeat all, the last item is followed by the first).
  - Nothing else changes with shuffle: the list keeps its own order, the playing item stays fixed in its place, and the Library
    button adds and removes items around the playing one, so the shuffled order of the player goes on.

## 2.6.137
- Added: **the whole Library as the queue** (`media/LibraryQueue.kt`, `media/QueueSheet.kt`, `media/PlayerActivity.kt`,
  `media/PlayerLauncher.kt`, `media/LyricsParser.kt`, `res/layout/bottom_sheet_queue.xml`, `res/drawable/bg_queue_toggle_on.xml`,
  `res/drawable/bg_queue_toggle_off.xml`, `res/values/strings.xml`, `res/values-ar/strings.xml`).
  - **A Library button in the header of the queue sheet**: one tap makes every video and every song of the Library the queue
    (the Library's own order, newest first; the header then reads `Whole Library · Playing 3 of 120`) and the list scrolls to the
    item that is playing. The button turns red while the mode is on.
  - **What is playing does not stop or jump**: the item is left as it is in the player, and the Library's items before it are put
    before it and the ones after it after it (no new start, no seek). Its words (lyrics / subtitles) go on as they were; the
    other items get theirs from the Library like in any queue opened from it.
  - **A second tap brings the previous queue back** around the item that is playing now (when the item is not in the old queue it
    stays first and the old queue follows it; a file the Library no longer has is left out).
  - **The mode ends with its queue**: opening a new queue from the Library switches it off. It is also kept while the player
    screen is closed and the music goes on.
  - The button waits (dimmed) while the Library is read and the queue is rebuilt; a toast says when the mode is on or off, or that
    the Library could not be read (nothing is changed then).
- Changed: `LyricsStore.put` can be called again for the same song and lyrics file without listing it twice, and the converted
  SubRip copy of an LRC file is only written again when its text changed (a song that is playing is not rewritten under the player).

## 2.6.136
- Changed: **the item that is playing in the player's queue is fixed in its place** (`media/QueueSheet.kt`,
  `res/layout/item_queue_entry.xml`, `res/values/strings.xml`, `res/values-ar/strings.xml`).
  - **Its handle is a red lock**: the playing row cannot be picked up, so it can no longer be moved by mistake.
  - **No other row can be dragged onto it or past it**: the ones before it stay before it and the ones after it stay after it, so
    its place in the list (`3 of 12`) never changes by dragging. The dragged row stops at it like at a wall.
  - **Safe at the drop**: a move that would touch the playing item (it changed while the finger was down) is not sent to the
    player, and the list is read from the player again.
  - The x button and the swipe to remove still work on every row, and a tap on the playing row still pauses / resumes it.

## 2.6.135
- Added: **the queue of the built-in player** (the one the Library opens, for videos and for songs) (`media/QueueSheet.kt`,
  `media/EqualizerView.kt`, `media/PlayerActivity.kt`, `ui/MaxHeightRecyclerView.kt`, `ui/ThumbnailLoader.kt`,
  `res/layout/bottom_sheet_queue.xml`, `res/layout/item_queue_entry.xml`, `res/layout/activity_player.xml`).
  - **A queue button in the player's title bar opens the list that is playing**, as a dark sheet like the player screen: a header
    with the title and where the playing item is (`Playing 3 of 12`), and one row per item with its picture (a frame of a video,
    the cover of a song), its name (two lines, right-to-left names read correctly) and a line under it.
  - **The item that is playing is easy to find**: a soft red card, its name in red, little bars moving over its picture while it
    plays (standing still while it is paused), and the label *Playing now*. The next one is marked *Up next*; the others show
    their place (`4 of 12`). The sheet opens scrolled to the playing item, so what comes after it is right under it.
  - **The list works**: a tap plays the row (a tap on the one that is playing pauses / resumes it), the handle at the end of a
    row drags it to another place (one move for the player, when the finger leaves), and the x button or a swipe sideways removes it.
    The last item cannot be removed: the queue is never left empty.
  - **It is always the real queue**: the sheet listens to the same player as the screen, so it follows the next / previous
    buttons, the notification and the headset while it is open.
- Added: **audio only for a video** (`media/PlayerActivity.kt`, `res/layout/activity_player.xml`).
  - **A headphones button in the title bar** (shown for any file that has a picture) turns the picture off: only the sound plays,
    nothing is decoded for the picture, and it **keeps playing in the background** when the screen is left. The button becomes a
    tinted video camera that brings the picture back at the same place.
  - **An audio-only video counts as music**: it shows the music panel (the title, and the words when the file has lyrics or
    subtitles), the Library's mini player shows it, and the screen's close no longer stops it.
  - **A queue that is opened new starts with its picture**: audio only belongs to what was playing.
- Changed: `ThumbnailLoader.loadCover` can be asked to read a video's frame as well as a song's cover (`audio = null`); the mini
  player keeps reading covers only. The way of reading is now part of the cache key.

## 2.6.134
- Added: **the picture of the video or song in the notifications** (`media/CoverBitmapLoader.kt`, `download/NotificationCovers.kt`,
  `download/DownloadService.kt`, `media/MediaPlayerService.kt`, `media/PlayerActivity.kt`, `ui/ThumbnailLoader.kt`,
  `ui/RemoteThumbnails.kt`).
  - **The built-in player's media notification and the lock screen show a picture**: a frame of the video, or the cover of the
    song. It is made only for the item that is playing, on a background thread, and kept while that item plays, so the
    notification (rebuilt at every play / pause) does not open the file again each time. Items with no picture keep the plain look.
  - **The ongoing download notification shows the thumbnail of the video that is downloading** (YouTube links; a page of another
    site has none). It is fetched once per video, and the notification is posted again when it arrives.
  - **The "saved" notification shows a frame of the saved video, or the cover of the saved song**, whatever site it came from.
  - **The notification shows a name and a count of the others**: with several downloads running or saved together it reads
    "Name +2" instead of "3 downloads running" / "3 files saved". The count is of downloads, not of files, so a lyrics file saved
    beside a song no longer adds to it.
- Changed: the picture loader decides by itself whether a file is a video or a song (it asks the file whether it has a picture
  track), so one call serves both.

## 2.6.133
- Added: **touch gestures in the built-in player** (the one the Library opens, for videos and for songs) (`media/PlayerGestures.kt`,
  `media/PlayerActivity.kt`, `res/layout/activity_player.xml`).
  - **Double tap moves 10 seconds**: the right half of the screen goes forward, the left half goes back. It works in the video
    player and in the music player alike (also over the lyrics). A label ("+10 s") shows on the side that was tapped. Right is
    forward on every phone language: it is the side of the screen, not the reading direction.
  - **Quick taps after a double tap keep adding 10 seconds** (20, 30, ...) while each tap comes less than 0.7 s after the last;
    a tap on the other half starts again from 10 in that direction.
  - **A sideways swipe on a video moves the position**: the label in the middle shows how far it moves and where it will go
    (`+0:25` over `3:45 / 12:10`), and the video jumps there when the finger leaves (one seek, not one per move, so a slow phone is
    not flooded). A swipe over the whole width moves an eighth of the video, never less than a minute nor more than five.
  - **Nothing that worked before changed**: the buttons and the time bar keep their own touches (scrubbing the bar, or pressing
    next twice quickly, never seeks); the lyrics list still scrolls and a tap on a line still jumps to it, only that the jump now
    waits 0.3 s to be sure the tap is not the first half of a double tap; a file that cannot be moved (a live stream, one that failed)
    keeps the old behaviour.
  - A single tap on the picture shows or hides the controls 0.3 s later than before: that wait is what tells it from a double tap.

## 2.6.132
- Changed: **the lyrics view no longer shows what is not sung** (`media/LyricsText.kt`). Captions, above all YouTube's automatic
  ones, mix the words with notes for the reader, and they showed as lyrics: `راح صفي المالهم داعي حياتي [موسيقى]`.
  - **Notes are taken out wherever they stand in the line** (at the end, in the middle, or alone): `[موسيقى]`, `(Music)`,
    `[Applause]`, `[Chorus]`, `(تصفيق)`, `[ضحك]` and the like. Only a bracket that names a sound or a part of the song goes:
    `(ooh ooh)` is sung and stays.
  - **The `>>` / `»` mark of a change of speaker**, a dialogue dash at the start of a line, and the music marks (♪ ♫) are removed.
  - **A line that was only a note becomes a pause**: an intro of `[موسيقى]` cues, or a `[Music]` between verses, shows as one
    "♪" row (when it lasts six seconds or more) instead of the word "music".
  - It works on the files already saved (the cleaning is done when the song is shown); the one-line fallback and the converted
    LRC use the same rules.
- Changed: **the lyrics the app downloads are cleaned the same way** (`download/SubtitleCleaner.kt`): until now only a note at the
  start of a line or a line that was only a note was removed, so `... [موسيقى]` at the end of a line stayed in the saved file.

## 2.6.131
- Changed: **the song's words in the built-in player are now shown like a music app does** (`media/LyricsView.kt`,
  `media/LyricsParser.kt`; before, only the one line being sung was shown, in three small lines of text).
  - **All the lines are on screen.** The line being sung is big and bright, the lines around it fade with distance, and the
    list **follows the song by itself**, keeping the sung line a little above the middle, with a smooth glide. A line comes
    a fraction of a second early (as in music apps), which reads better than one that is late.
  - **A tap on any line jumps the song to it.**
  - **Moving the list by hand** pauses the following; about 3.5 seconds after the finger leaves, it goes back to the sung line.
  - **Pauses show as "♪"**: an intro, a solo or a gap of six seconds or more between two lines, so the screen never looks
    stuck on the last line while the music plays on.
  - The top and the bottom of the list **fade softly** into the background; the song's title sits small at the top; and when the
    song has its own picture, it gets a dark veil under the words so they stay easy to read (it clears towards the controls).
  - Works for LRC (also several stamps per line, `[offset:]`, word stamps ignored), SubRip and WebVTT, and for the
    other-language lyrics chosen from the subtitle menu: the list switches to that file's lines.
  - If the whole file cannot be read, the old one-line display is used instead, so the words never disappear.
  - A video keeps its usual subtitles at the bottom of the picture, as before.

## 2.6.130
- Added: **a mini player in the Library** (`ui/MiniPlayerBar.kt`, `res/layout/view_mini_player.xml`; the cover comes from `ThumbnailLoader.loadCover`). While a song is in the
  built-in player, a bar sits at the bottom of the Library with the song's **cover**, its name, how far it is (a thin
  progress line on top and `1:23 / 3:45`), and **previous, play / pause, next and close**.
  - **A tap on the bar opens the full player** on the same song (`PlayerLauncher.openCurrent`); back from it returns to the
    Library with the bar still there, so music started in the player can be controlled from the list.
  - It is one more controller of the same player that lives in `MediaPlayerService`, so the bar and the player screen
    always show the same song and the same state. It connects only while the Library is on screen, and only when the
    service already exists (`MediaPlayerService.running`): opening the Library never starts a player.
  - **Close** stops the music and empties the queue; the bar and the media notification go away.
  - Music only: a video has its own screen and is paused when that screen is left, so it never shows in the bar.
  - While files are ticked (share / delete bar) the mini player steps aside, then comes back.
  - Previous restarts the song when it is already a few seconds in, then goes to the one before (the player's own rule);
    next is dimmed on the last song. The buttons run left to right in both directions, like the player's controls.
- Changed: **the music screen of the player no longer puts the note over the song's picture.** The player view already draws
  the song's own picture behind the controls, so the note is shown only for a song that has none.

## 2.6.128
- Added: **the player chooser is back, as a sheet.** A tap on a video or a song of the Library no longer goes silently to the
  built-in player: a bottom sheet asks which player to use, drawn like the three-dots menu and the video sheet
  (`ui/PlayerChoiceSheet.kt`, `res/layout/bottom_sheet_player_choice.xml`, `item_player_choice.xml`).
  - **The file on top** (its picture, name, format and size), so it is plain which file is being opened.
  - **The built-in player first, in its own outlined card marked "Recommended"** (the same badge as the browser's *Found on
    this page* list), with a line on why: it plays the whole list as a queue, shows the lyrics, keeps going in the
    background and has picture in picture.
  - **The other player apps of the phone under it**, by name and with their own icons (`media/ExternalPlayers.kt`: every app
    that can *view* that kind of file, one entry per app, this app left out). Android 11+ shows other apps only when the
    manifest asks, so `<queries>` for `video/*` and `audio/*` *view* intents is added.
  - **"Always use my choice"** is a switch at the bottom (`Prefs.playerChoice`). On, the next tap goes straight to that player;
    off (the start), the sheet asks every time. A kept app that was removed from the phone is forgotten and the sheet asks again.
  - **Play with…** is a new row at the top of a file's three-dots menu: it opens the sheet even when a choice is kept, and
    turning the switch off there goes back to asking.
  - A phone with no other player app has nothing to choose from, so a tap goes straight to the built-in player (the sheet from
    *Play with…* then says there is no other player). If another app cannot open the file, the built-in player plays it and
    says so.
  - Another app gets the one file; the queue, the lyrics and the little window are the built-in player's.
- Added: **picture in picture in the Library's player** (`media/PlayerActivity.kt`).
  - **A button in the title bar** (shown for a video only, on a phone that has the window) shrinks the video into the system's
    small floating window at once. A song has no button.
  - **The window has the picture's own shape** (before, always 16:9, so a vertical video was cut or had black bands), also for
    a file stored sideways, and the system animates the shrinking from the place the picture is on the screen.
  - **Its buttons: previous, play / pause, next** (next is dimmed on the last file). On a right-to-left phone the order is
    mirrored, as in the browsing screen's window; the play / pause button follows the real state.
  - **Leaving the app with a video playing** (Home, Recents) goes into the window by itself when the *Picture in picture* switch
    of the three-dots menu is on (it used to ignore that switch); Android 12+ does it by its own gesture from the params the
    player keeps up to date. The title-bar button works whatever the switch says.
  - If the phone refuses the window (the permission is off for the app in its settings) a message says where to allow it.
- Not changed: the built-in player's controls, lyrics and notification, the browsing screen's picture in picture, and what a
  lyrics file does when tapped.
- Not built and not run on a phone (written without an Android SDK): checked by reading, by an XML and resource-reference
  check, and by a brace / import check of the Kotlin files.

## 2.6.127
- Fixed: **the built-in player did not know where the lyrics are.** The app saves them in the `كلمات` folder
  (Download/WardTube/`كلمات`), away from the videos (`فيديوهات`) and the audio (`أصوات`), and the player only looked for
  `.srt` files by name, so a song's `.lrc` (what *Lyrics / subtitles LRC* saves) was never found, and it could not have been
  read anyway: Media3 does not read LRC.
  - **Where it looks.** Every Library file now knows its sort folder (`DownloadItem.folder`, read from its path).
    `media/LyricsFinder.kt` takes a video / audio file and returns its `.lrc` / `.srt` files: the exact name
    (`Song.mp3` + `Song.lrc`), or with a language code (`Song.en.srt`); anything else with the same start
    (`Song.Remix.srt`, `Song 2.lrc`) is not taken. The copy in `كلمات` wins over an older one that an earlier version left
    straight in Download/WardTube, which is still found when it is the only one. For music LRC comes first, for a video SRT.
  - **LRC is read.** `media/LrcToSrt.kt` turns an LRC into SubRip (several stamps per line, `[offset:]`, word-by-word
    `<mm:ss.xx>` tags dropped, an empty line ends the line before it, plain digits 0-9 whatever the phone's language). The
    player does it off the main thread, keeps the converted copy in its own storage, and a file it cannot read only means
    the song plays without words.
  - **The words are shown.** For music the line that is being sung shows under the title in the player screen (it was
    hidden behind the controls as a subtitle); a video keeps its subtitles at the bottom of the picture. Only the first
    lyrics file is switched on, the others are in the subtitle button's menu.
- Fixed: **tapping the player's notification did nothing.** The media session had no screen to open; it now opens the
  player (`MediaPlayerService`: `setSessionActivity`).
- Not changed: how lyrics are downloaded and named, the Library list, and the video player's subtitles.
- `LrcToSrt` and `LyricsFinder` were compiled with Kotlin 2.0.21 (the project's version) and tested on their own (offset,
  Arabic text, repeated stamps, folder choice, names with dots). The Android side (`PlayerActivity`, the layout, the
  notification) was written without an Android SDK: not built and not run on a phone.

## 2.6.126
- Changed: **the download tip stays on screen until the person presses OK.** It used to go away by itself after 8 seconds,
  which was too short to read; now it is shown with `Snackbar.LENGTH_INDEFINITE` (`MainActivity.showVideoPressTip`) and the
  unused `VIDEO_PRESS_TIP_MS` is removed. It is still shown only once.
- Not changed: the text of the tip, where it shows, and when.

## 2.6.125
- Changed: **the text that explains how to download a video now says that the long press does not work in the custom
  Subscriptions page** (there the video's three-dots menu has its own *Download* row).
  - Both texts are changed, in English and Arabic: the one-time tip (`videoPressTip`) and the dialog of the bottom bar's
    download button (`error_select_video_to_download`).
  - The tip's notice may now take three lines instead of two, so the longer text is not cut (`MainActivity.showVideoPressTip`).
- Not changed: the long press, the sheet, and the Subscriptions screen itself.

## 2.6.124
- Added: **the way to download a video is now said in the app**, because a long press is not something a person finds alone
  (and the three-dots row it replaced is gone).
  - A one-time tip, *Tip: press and hold any video to download it*, shows above the bottom bar the first time a page with
    video cards is on screen (Home, results, a feed, a channel, a playlist). It is a notice, not a dialog; it waits while
    the Subscriptions screen, the full-screen player or an error notice is up, and it is never shown again
    (`MainActivity.showVideoPressTip`, `Prefs.videoPressTipSeen`).
  - The bottom bar's download button, pressed with nothing to download, now says where a video of a list is downloaded from
    (a long press on its picture or title) instead of only "select a video" (`MenuController.withWatchUrl`).
- Not changed: the sheet, the long press itself, and the Subscriptions screen.

## 2.6.123
- Changed: **one way to download from a card: a long press on the video.** NouTube's way (Android's own hit test of the
  WebView says which link is under the finger) is now the only one, in the browsing page and in the player's page (the list
  under the video) alike. The attempts that came before it, which all depended on how YouTube builds a card, its three dots
  and its menu, are removed.
  - **A new sheet instead of the system's plain menu.** It shows the video (its picture and title, so it is plain which
    video the press found), a big *Download video* button, and *Share* and *Copy the link* under it. It is drawn like the
    three-dots menu (`ui/VideoSheet.kt`, `res/layout/bottom_sheet_video.xml`).
  - **The title comes from the card.** As in NouTube's `link-title.ts`, the video is found by its id among the page's links
    and the heading of its card is the title; a page that does not answer only costs the sheet its title. The title the hit
    test gives (often only the picture's length or LIVE) is the second choice (`web/VideoLongPress.kt`).
  - **The address is built from the 11-character id** (`util/VideoLinks.kt`), so a playlist, a start time or tracking data in
    the page's link never travels with the video.
  - **In the player's page too.** A long press on a video of the list under the playing video opens the same sheet (before,
    only the browsing page had it). It opens only where the page can really be touched: not under the Subscriptions screen,
    not under the full-screen player, not in full screen or in the small window.
- Removed (the failed attempts): the *Download video* row added to YouTube's three-dots menu and the *Download* button added to
  the watch page's row (`assets/wardtube/menu-download.js`, `web/MenuDownload.kt`); the looks at a touch (`web/CardProbe.kt`,
  `web/TapProbe.kt`, `web/MenuVideo.kt`); the floating *Download video* chip over the page and its drawable; the bottom bar's
  download button that lit up and pulsed for a tapped card (`MenuController.setDownloadArmed`, `beforeBarAction`); the watch of
  copied links; the "could not find this video / copy details" message. The bottom bar's download button is as it was: it
  downloads the video that is open in the player, a Short, or a playlist / channel page.
- Not changed: the Subscriptions screen (its own menu, with *Download*, is untouched).
- Not built and not run on a phone (written without an Android SDK). The title lookup and the link reading were run in
  Chromium on a page that imitates YouTube's cards, and all the resources the sheet uses were checked to exist.

## 2.6.122
- Added: **NouTube's two ways of naming a video, ported** (read from NouTube's `menu.ts` and `NouTubeView.kt`).
  - **Long press on a video's link** (the picture or the title, on any page: Home, Search, Watch history ...): Android's own
    hit test of the WebView (`hitTestResult`, `requestFocusNodeHref`) gives the link under the finger and the menu offers
    *Download video*. It does not look at how the card is built (`MainActivity.kt`: `onCreateContextMenu`, `addLinkMenu`).
  - **A tap anywhere inside a video's card waits for a menu** (as NouTube's `menu.ts` does, with its list of cards, including
    `ytm-video-card-renderer` and `yt-lockup-metadata-view-model`): when a menu opens after the tap, the card's video is
    offered (lit, pulsing download button) even if the tap was not on a button this script knows. A tap that only opens the
    video offers nothing (`assets/wardtube/menu-download.js`).
- Not built and not run on a phone (written without an Android SDK).

## 2.6.121
- Added: **the video is named by its link alone.** Two ways that do not depend on how the page is built:
  - **A copied video link is the video to download.** When a YouTube video link (watch, Shorts, live, youtu.be) lands on the
    clipboard (YouTube's own *Share > Copy link*), the bottom bar's download button lights up and pulses, and one tap downloads
    it (`MainActivity.kt`: `watchCopiedLinks`, `videoLinkIn`).
  - **The tap's own data.** The page script also reads the video id from the data of the elements the tap went through
    (`composedPath`), when no link or picture names it (`assets/wardtube/menu-download.js`).
- Not built and not run on a phone (written without an Android SDK).

## 2.6.120
- Fixed: **the download button still worked only on Home, not in Search and Watch history.**
  - **What was wrong.** When the two looks at a touch (`CardProbe`, `TapProbe`) both found nothing, the app stopped there:
    `fireProbe` returned when the second look had no answer, so nothing waited for YouTube's menu and nothing was offered.
  - **Now the menu itself is the sign.** After a tap the app waits for YouTube's menu to open because of it (also when the
    two looks said nothing), then reads the video with the new `web/MenuVideo.kt`: first from the data of the open menu's rows
    (the video id that YouTube built them from), then from the picture or link at the finger's line. Nothing depends on how a
    card, its three dots or its links are built (`MainActivity.kt`: `awaitMenuOpenBlind`, `extractFromOpenMenu`).
  - Not built and not run on a phone (written without an Android SDK); the new script was only syntax-checked.

## 2.6.119
- Fixed: **the download button did not find the video of the three dots in Search results and Watch history.**
  The video of a card was found only from its `<a href="/watch?v=...">` links. It is now also read from the card's thumbnail
  address (`i.ytimg.com/vi/<id>/...`) and, last, from the data YouTube keeps on the card's element
  (`web/CardProbe.kt`, `web/TapProbe.kt`, `assets/wardtube/menu-download.js`).
- Added: **the download button of the bottom bar turns red and pulses after the three dots of any video are tapped**, also while a
  video is playing in the mini player (the button was already at full strength then, so nothing showed)
  (`ui/MenuController.kt`, `MainActivity.kt`).
- Not built and not run on a phone: written without an Android SDK.

## 2.6.117
- Fixed: **the download button did not show for the three dots of the videos in Watch history and Search results, only on Home**
  (`web/CardProbe.kt`, `MainActivity.kt`, `values/strings.xml`, `values-ar/strings.xml`).
  - **What was wrong.** The app-side look at a touch (2.6.116) only recognised three dots when *all* of this held: the touched
    thing is a `button` / `role=button` / `ytm-menu`..., it is not inside a link, nothing transparent is drawn over it, and, going
    up from it, the first box that links a video links exactly one. That matches how Home draws a card. Watch history and Search
    draw it differently and any one of those four was enough to get no answer, with no button and no message.
  - **Now nothing depends on how a card is built.** The touched thing can be a button of any kind or any small box with a drawing in
    it; what is drawn *under* the top layer is looked at too (a transparent layer over the dots no longer hides them); three dots
    inside a link are accepted (they are recognised by their `ytm-menu` box, their label or their drawing); and the video is found
    first by structure as before, and when that fails by position: the video whose links (picture, title) are nearest to the finger,
    a link on the finger's line counting for more than one beside it and one further down belonging to the next card.
    A layer that covers the page (YouTube's open menu and its dimmed page) still means "nothing here", so a tap that only closes
    the menu is never taken for the dots.
  - **If it still finds no video for the three dots** the app now says so instead of staying silent: a message *"The download
    button could not find this video"* with a **Copy details** button. It copies a short description of what the page is made of
    around the dots (tags, sizes, how many videos each box links, the nearest links): paste it in the chat and the next fix is
    built on what the page really is. (Only for things that look like three dots, so ordinary taps never show it.)
  - Tried in a headless Chromium on 14 stand-in layouts (Home's old card, the `yt-lockup-view-model` card, Watch history's
    horizontal card, a row of Shorts tiles, dots outside the card, dots with no `button`, dots inside a link, a transparent layer
    over the card, title not a link, right-to-left page, three pixel ratios) and 8 taps that must find nothing (picture, title, channel avatar,
    chip bar, top bar, a channel card's Subscribe button, an open menu and its dimmed page). The 2.6.116 probe found nothing in 5
    of the 14 (dots outside the card, no `button`, inside a link, under a transparent layer); the new one answers all of them with
    the right video and title and answers nothing for each of the 8. **The Kotlin side is not built and not run on a phone**
    (written without an Android SDK) and nothing was tried against live YouTube.

## 2.6.116
- Fixed: **the app's own "Download video" button did not show for the three dots of the videos in search results**
  (`web/CardProbe.kt` new, `MainActivity.kt`, `web/MenuDownload.kt`, `subs/SubsController.kt`).
  - **Why 2.6.114 could still show nothing.** The button was only ever started by the page script (`menu-download.js`)
    telling the app that the three dots of a card were tapped. If the script was not running in the page, did not see the
    tap, or did not recognise the card or the button, the app was never told and nothing showed, whatever the menu looked
    like. Nothing could be checked against live YouTube from here.
  - **Now the app looks at the touch itself.** `MainActivity.trackCardTouch` watches every touch on the browsing page. When a
    finger goes down, `CardProbe` is handed to the page (`evaluateJavascript`, a self-contained function: no script in the
    page, no bridge and no YouTube menu is needed). It looks at what is drawn under the finger *before* the tap can open
    YouTube's menu (which dims the page): a small pressable that is not a link and not in a menu, dialog, bar, chip bar or
    the player, inside a card that links exactly one video. If the finger then lifts without moving (a tap), the offer starts:
    the same button as before (a dark pill with the download icon, "Download video" and the title, at the top of the page),
    and the download button of the bottom bar lights up for that video.
  - The page script still tells the app too; a tap that both see counts once (`CARD_SAME_TAP_MS`), and when the row does show
    inside YouTube's menu the app's button is not drawn (as before). The button now waits 0.9 s for that (was 1.2 s).
  - **A tap elsewhere ends the offer, a drag does not** (before, any finger-down did, so touching the menu while looking for
    the row could take the button away). The button of the offer, the bottom bar, Back, leaving the screen, the download
    starting and 20 s still end it. No offer starts under the player, the Subscriptions screen, in full screen or on a page
    that is not YouTube (`canProbe`; `SubsController.isCovering` is new).
  - Closing YouTube's menu after the button was used no longer needs the page script either (`MenuDownload.closeMenu` does
    the same tap on the dimmed page and Escape by itself when the script is not there).
  - Tried the probe in a desktop Chromium on stand-in pages (the old card, the `yt-lockup-view-model` card, a `role=button`
    div, a Short with the dots over its picture): the right video id and title for each, nothing for the dots of a channel
    card, a link, the thumbnail, a chip, the dimmed page or a row of the menu. **The Kotlin side is not built and not run on
    a phone** (written without an Android SDK) and nothing was tried against live YouTube. Try it: search for something, tap
    the three dots of a result: about a second later a "Download video" button should show at the top, and the download button
    of the bottom bar should be lit; tapping either opens the format window.

## 2.6.115
- Fixed: **the player showing for a second and then going black after the picture-in-picture window is left**, mostly
  when the video had just ended and the next one was opening (`player/PlayerHost.kt`, `assets/wardtube/wardtube.js`).
  - **Cause.** Entering the window ran a sequence that tells the page its state, sends it a resize, lays it out a few
    pixels narrower for a split second and measures the video again, several times over about 1.4 s (`pipSettle`), because
    YouTube sizes its player for whatever size the page has at that moment and can stay stuck on an in-between one.
    Leaving the window had nothing of the kind: `exitPip()` gave the player its size back in one step and sent
    `setMini(false)` once. The window then grows for a while, and a player that is just opening the next video can be
    sized for one of the in-between moments: the video shows, then YouTube sizes it again wrongly and the box goes black
    (the sound goes on). A player that is not changing video takes the size change well, hence "sometimes".
  - **Now** `exitPip()` starts `exitSettle`: for about 4.2 s after leaving, six looks (0.2, 0.5, 1.0, 1.7, 2.7 and
    4.2 s in) tell the page whether it is full screen or the floating box, send it a `resize`, and at the second and the
    fifth look lay the page out 6 px narrower for 140 ms (`nudgeAfterExit`, not while a finger moves the player or the
    box changes size). In the floating box the video is also measured again at each look. If the page changes meanwhile
    (the next video opens) the sequence starts over; entering the window again, or closing the player, stops it.
  - **The page** (`markMini`) now sends three `resize` events (50 ms, 0.4 s, 1.2 s) every time it stops being a small
    box, whatever the app does, so the player is also sized again when the floating box is opened full screen.
  - Tried in a desktop Chromium on a stand-in page: the mini rules switch on and off (by `setMini` and by the app's
    answer alone), no `resize` while mini, three after, no script errors. **The Kotlin side is not built and not run on a
    phone** (written without an Android SDK) and nothing was tried against live YouTube. Try it: play a video in a
    playlist or a mix, press Home (the window comes), let the video end and the next one open, tap the window to
    return: the video should stay in the player. If it still goes black, tell me what the player does (sound, how long it
    shows).

## 2.6.114
- Fixed: **no download offer in the three dots of the videos in search results** (`assets/wardtube/menu-download.js`,
  `web/MenuDownload.kt`, `MainActivity.kt`, `ui/MenuController.kt`, `layout/activity_main.xml`,
  `drawable/bg_card_download_chip.xml`). Not fixed by 2.6.112, and the reason is why this one does not put its trust in the page:
  2.6.112 was written and tried on stand-in pages only, and the "Download video" row it adds *inside* YouTube's menu, and the
  button it drew over the page, both depended on how YouTube builds that menu (names of rows, layers, what is drawn where), which
  cannot be seen from here. On the real search page neither showed.
  - **Now the app does not need the menu.** The moment the three dots of a video's card are tapped, the page tells the app which
    video the card is (`Bridge.cardMenu`, with the card's title). If the row is then seen in YouTube's menu (`Bridge.rowShown`)
    nothing else is shown. If it is not (after 1.2 s), the app draws **its own "Download video" button** over the top of the
    page (a dark pill with the download icon, "Download video" and the title of the video), clear of YouTube's menu, which
    comes from the bottom. A tap on it opens the usual format window for that video and closes YouTube's menu.
  - **The download button of the bottom bar** goes to full strength while that offer is open, and downloads the same video
    (`MenuController.beforeBarAction`, asked before any bar button acts; any other bar button ends the offer).
  - The offer ends on any other touch (the bottom bar excepted), on Back, when the app is left, when the download starts and
    after 20 s. A scroll or a tap on a link never starts one.
  - The page now also notes a tap on `pointerup`, not only on `click`: a page that opens its menu from the touch and cancels
    the click that follows would never have been seen. `pointerup` and `click` of one tap count once. A touch that moved
    more than 12 px or lasted more than 0.7 s (a scroll, a long press) is not a tap.
  - The three dots are also looked for as `button-view-model`, `yt-button-shape` (besides `button`, `[role=button]`,
    `yt-icon-button`, `ytm-menu`, `ytm-menu-renderer`).
  - The button the script of 2.6.112 drew over the page ("the pill") is gone: the app's own replaces it. The row inside
    YouTube's menu is still tried first, on a watch page as before.
  - Tried on stand-in pages in a desktop Chromium (a menu whose rows cannot be seen from the page, a known menu, a page that
    opens its menu from `touchend` and cancels the click, a tap on a video link, a drag over the dots): the app is told
    once with the right id and title, `rowShown` only when the row is in the menu, no script errors. **The app side
    (`MainActivity`, the layout) is not built and not run on a phone** (written without an Android SDK), and nothing was tried
    against live YouTube. Try it: search for something, tap the three dots of a result: a "Download video" button should
    show at the top, and the download button of the bottom bar should be lit.

## 2.6.113
- Added: **download settings in the Library: how many downloads run at the same time, and a speed limit for each**
  (`download/DownloadService.kt`, `download/DownloadLimits.kt`, `download/SpeedLimiter.kt`, `download/FileDownloader.kt`,
  `ui/LibraryActivity.kt`, `ui/LibraryAdapter.kt`, `Prefs.kt`, `layout/dialog_download_settings.xml`).
  - Where: the sliders button on the **Downloads** heading of the Library, or **Download settings** in the Library's
    three dots (that one is there even when nothing is downloading). One dialog, no Save button: a choice is saved and sent
    to the download service at once.
  - **Downloads at the same time: 1 to 5.** The default is **1**, which is how it worked before: nothing changes until
    you pick more. (A video download is a yt-dlp process, heavy for a small phone, so the top is 5. The default is
    `Prefs.DEFAULT_PARALLEL_DOWNLOADS`.)
  - **Speed limit for each download:** no limit, 128 KB/s, 256 KB/s, 512 KB/s, 1 MB/s, 2 MB/s or 5 MB/s. It is for each
    download, so with 3 at once and 1 MB/s the total can reach 3 MB/s; the dialog shows that total while a limit is set.
    A video gets it as yt-dlp's `--limit-rate` (and takes its stream fragments one at a time then, so the limit is for the
    whole download and not for each fragment); a plain file (zip, pdf, apk...) is paced by the new `SpeedLimiter` in
    `FileDownloader`, which asks for the limit before every read.
  - **Live:** a higher number of downloads starts waiting jobs at once. A lower number stops nothing: the running
    downloads finish and no new one starts until fewer than the new number are running. A plain file takes a new speed
    limit at once; a video keeps the limit it started with until it is paused and resumed (yt-dlp cannot be told a new one
    while it runs), and the dialog says so.
  - How: the service used one thread (`newSingleThreadExecutor`) and kept the job it was working on in single fields
    (`currentJobId`, `currentProcessId`, `currentFile`, `cancelRequested`, `pauseRequested`, `heldRun`). Now each job
    that is being worked on has a `Run` of its own with those fields, and **`dispatch()`** starts waiting jobs, first in
    line first, while fewer than the allowed number are active. It runs when a job is added, resumed, paused or ended,
    and when the settings change. A job leaves "waiting" under the lock when it is started, so it can never start twice.
  - The settings reach the service (it runs in its own process, so it cannot read them) with every start request, and in
    a package-restricted broadcast (`DownloadLimits.ACTION_CHANGED`) while it is running.
  - Cancel, Pause and Resume work for each download on its own. A cancel that comes in the moment a job is picked up can
    no longer let it download to the end: the run is registered together with the check that the job still exists, and a
    job cancelled before its process exists does not start it. A job that is cancelled while it still waits is counted
    out of the pending jobs, which the single worker used to do when it found the job gone (otherwise the service would
    never stop).
  - Several downloads at once: the saved-file messages are kept per queue under the lock (`reportSaved`), only one job
    updates the engine when several fail together, and the notification shows **"N downloads running"** with their
    average progress and **Cancel all** (Pause is in each card in the Library). With one download it is as before, except that
    its Cancel button now names its job.
  - Texts in Arabic and English. README (both) and the file list in it updated.
  - **Not built and not run on a phone** (written without an Android SDK). The speed pacing (`SpeedLimiter`) was run on a
    JVM with a fake clock: 256 KB/s asked, 257 KB/s measured; 1 MB/s asked, 1028 KB/s; a slow source is not sped up, and a
    source that was slow and turns fast does not burst above the limit. The rest was checked by compiling the changed
    sources with the Kotlin compiler without Android, where only the missing Android classes were reported.

## 2.6.112
- Fixed: **the "Download video" row did not show in the three dots of the videos in search results** (`assets/wardtube/menu-download.js`).
  - Why: the script added its row before the first row of the page that `getClientRects()` called "shown". That test is
    also true for a menu YouTube keeps in the page without showing it (hidden with `visibility` or `opacity`, or moved off
    the screen), and such a menu comes first in the page. The row went into that menu, was never seen, and the script then
    took the job for done and looked no further. A menu whose rows have names the script does not know got no row at all.
    (Both were reproduced on a stand-in page in a desktop Chromium with the old script, and are gone with this one.)
  - Now a row of the menu counts only when it is **really on the screen**: it has a box inside the window and what is drawn at
    its centre (`elementFromPoint`) is the row itself. The top such row gets the "Download video" row before it.
  - A menu whose rows have **names this script does not know** is found by how it is drawn: wide boxes (half the window or
    more, 36 to 110 px tall) with text, one above the other, inside a layer fixed to the screen, never inside a card, a bar of
    the page or the player. The row goes before the top one. This is tried 0.4 s after the tap, when the known names found nothing.
  - **Last resort:** if a menu is open (a fixed layer that covers the page is on top) and still no row is in it after 1.4 s,
    or the row was added and cannot be seen there (after 0.5 s it is taken out), a **"Download video" button of the script's
    own** is shown above the menu. It goes when the menu goes, and is never shown when no menu is open or on a watch page
    (which has its own button).
  - The menu is looked at at most every 80 ms (it was on every change of the page), a tap on the row ends the wait for that
    menu, and the watch page's row of buttons works as before.
  - Tried on stand-in pages in a desktop Chromium (known menu, a hidden / off-screen / transparent menu in the page before
    it, unknown tags, a slow menu, rows too small to find, a menu that hides what it does not know, no menu at all, and the
    watch page with and without the three dots). Not built and not run on a phone, and not tried against live YouTube.

## 2.6.111
- Changed: **the x in the browser's link field now shows only while the field is being used** (`browser/BrowserActivity.kt`).
  Before, it showed whenever the field had text, so on the main screen and on an open page (where the field holds the
  page's address) it sat in the address bar all the time. Now `updateClearButton` shows it only when the suggestions are
  open, the field has the focus, the keyboard is up and there is text to clear. It goes as soon as the keyboard goes (the
  suggestions list dragged, the back button, a search started) and it is not there for the address bar at rest. The
  keyboard is read from the window insets that the screen already listens to (`keyboardVisible`).
- Not tested: written without an Android SDK to build it. Build it and try it: open a site (no x in the address bar), tap
  the bar (x shows with the keyboard), drag the suggestions (the keyboard goes, the x goes), tap the bar again (it comes back).

## 2.6.110
- Fixed: **the pencil on the open page's card now puts the page's address in the link field** (`browser/OmniboxPanel.kt`,
  `browser/BrowserActivity.kt`). The pencil (and a tap on the card) only focused the field and brought the keyboard up, so
  when the field was empty (the x pressed, or the selected address deleted) it stayed empty and the address could not be
  edited. Now `editAddress(url)` fills the field with the page's address, puts the cursor at its end and shows the keyboard.
- Fixed: tapping the field of an open page while it is empty now fills it with the page's address, as it already did when
  the field was not being used (`openOmnibox`).
- Not tested: written without an Android SDK to build it. Build it and try it: open a search or a site, tap the field,
  press the x, then the pencil on the card: the address should come back in the field with the keyboard up.

## 2.6.109
- Changed: **the browser's link field opens with one smooth movement** (`browser/BrowserActivity.kt`, `OmniboxPanel.kt`).
  - Before, the top row faded out, *then* the field moved, *then* the row faded in (three steps one after the other, about
    0.45 s), and the suggestions page appeared over the field while it was still on its way. Now the field and the page
    (or the main screen) under it glide up together in 0.24 s on one curve, the top row fades away at the start, the
    suggestions fade in and rise a few dp into place a moment later, and the field's first button grows into its new icon
    (the back arrow). Closing plays the same movement backwards, and the suggestions fade out quickly instead of vanishing.
  - `layoutHeader` is now the only place that sizes the header; `headerTransition` is its movement. The old
    `animateField` (a sequential `AutoTransition`) is gone.
- Changed: **the header is slim while a page is open** (any site, or the result of a search). The top row is 40dp (was
  64dp), the field 48dp (was 56dp), the gap above the field 2dp (was 12dp) and the one above the page 4dp (was 8dp): the
  page gets **46dp more of the screen** (the header went from 140dp to 94dp). The search engine button is hidden on the
  slim field, so the address has more room, and comes back (fading in) as soon as the field is tapped. The main screen
  keeps the roomy header as it was; opening or closing a page switches between the two at once.
- Removed: **"Next Short when one ends"**, because it did not work. The switch is gone from the menu (`MenuController`), with
  `Prefs.shortsAutoNext`, the flag sent to the page (`PageScripts`), `autoNextShort` in `wardtube.js`, the three strings
  (English and Arabic) and the lines in both READMEs. `nextShort` in the script stays: the Shorts ad skipper uses it. A
  Short that ends plays again, as on YouTube. An old `shortsAutoNext` value in the preferences of a phone that had the option
  is never read again and does no harm.
- Not tested: written without an Android SDK to build it. Build it and try it: tap the field on the main screen and with
  a page open (and press back to close it) and look at the movement; open a site and a search and see the slimmer header;
  check that Reload / Home / Back and the keyboard still work with it; check the menu no longer shows the Next Short row.

## 2.6.108
- Improved: **the search box of "All subscriptions"** (`subs/SubsScreen.kt`) is no longer always on the screen. Under the
  panel's title row there is now a **lens button with "Search for a channel in subscriptions" beside it** (a small rounded
  button, not a box); a tap turns the title row and the button into the search box (back arrow, the text, an x that empties
  it) and brings the keyboard up, and the back arrow (or the phone's back button, once) closes the search and shows them
  again. Closing the panel always leaves the search closed.
- New: **the browser's link field works like Chrome's address bar** (`browser/OmniboxPanel.kt`, `SearchHistory.kt`,
  `SearchSuggest.kt`, `BrowserActivity.kt`, `activity_browser.xml`).
  - A tap on the field **widens it**: the top row goes away, the field takes the width of the screen, and its globe becomes
    a back arrow. Under it the suggestions page opens; the back arrow or the back button closes it.
  - **Empty field** (or the open page's own address): the open page's card with *edit / copy / share* buttons, then the
    **recent searches** (the words searched in this field, newest first). With no searches yet, the last pages opened.
  - **While typing:** the text itself first (search it, or open it when it looks like an address), then the recent
    searches that match, then **the suggestions of the search engine in use**, then the pages of the browsing history that
    match. The arrow at the end of a row only puts its text in the field; a tap on the row searches / opens it; a long press
    on a recent search removes it. Dragging the list hides the keyboard.
  - **Privacy:** the typed words are sent for suggestions only to the engine chosen at the end of the field (Google,
    YouTube, Bing, DuckDuckGo, Brave and Yandex have a suggestion service; the other engines get no suggestions, and the text
    is not sent to any other one). Text that looks like an address is never sent. Searches are saved on the phone only.
  - The screen's own view now takes the first focus (so the field is not focused, and the suggestions are not opened, when
    the screen starts), and the field selects its whole text when it gets the focus.
- Not tested: written without an Android SDK to build it, and the suggestion services were not reached from here. Build it
  and try it: the lens in All subscriptions, then the field on the browser screen (empty, typing Arabic and English words,
  with a page open and without).

## 2.6.106
- New: **pull down to refresh** on the browsing page, like YouTube's own app and the Subscriptions screen: pulling the page
  down from its top loads it again, with the round spinner, which stops when the page has loaded
  (`MainActivity.setUpPullRefresh`, `ui/PullRefreshLayout.kt`, `PageEvents.onPageFinished`).
  - It only works on plain YouTube pages: not on Shorts (they move up and down by themselves), not on a video, not on a
    sign-in page, not in full screen or while the player is open over the page.
  - **It waits for the top.** The pull starts only when the WebView is at the top of the page, and a short script asks the
    page once per touch whether a list inside the page under the finger is scrolled (YouTube's pages scroll as a whole, but
    a list in one would look like the top to the WebView).
  - **Sideways swipes are left alone:** a slanted swipe along a row of chips no longer pulls the page (`PullRefreshLayout`
    stops offering the pull once the finger has gone further sideways than down).
- Not tested: written without an Android SDK to build it. Build it and try it on a device (Home, Search, a channel).

## 2.6.105
- Improved: **the Subscriptions screen** (`subs/SubsScreen.kt`, with small hooks in `SubsController.kt`), rewritten with
  [LiTube](https://github.com/HydeYYHH/litube)'s subscriptions screen as the model (LiTube is a Compose app, so its code
  cannot be copied into this View-based one; the ideas were re-written here). The data layer (`SubsRepository`) is
  untouched: what is read from YouTube, and how, is as before.
  - **All channels in a list.** The "All" button used to leave for YouTube's page. It now opens a panel over the screen
    with every subscription, a search box and the blue dot for new videos; a tap picks the channel. Its "Manage" button
    still opens YouTube's own page. The back button closes the panel first (`SubsController.onBack`).
  - **Two new chips:** *Continue watching* (videos with a red line between the ends, under 95 %) and *Unwatched* (no red
    line). They read further pages like the Videos chip does when few videos match (`SubsFilter.CONTINUE`/`UNWATCHED`).
  - **States instead of a blank screen.** A skeleton of cards while the first read is on its way; a "Couldn't load
    videos" screen with **Try again** when a read failed and there is nothing to show (`feedFailed`/`channelFailed`); a small
    spinner at the end of the list while older videos are read (`setLoadingMore`).
  - **The header slides away** with the finger (strip of channels and chips), and comes back when scrolling up; it settles
    only when the list has scrolled past it, so no gap is left.
  - **Lighter on slow phones:** the list is a `RecyclerView` updated with `DiffUtil` (a full redraw only for another list,
    new pictures, or more than 1500 rows) instead of being rebuilt on every page.
  - **Turning the phone:** the Shorts grid takes its columns from the width (2 to 5), and the pictures keep 16:9 / 9:16 at any
    width (`RatioFrame`).
- Not tested: this version was written without an Android SDK to build it. Build it (`./gradlew assembleDebug`) and check
  the Subscriptions screen on a device before publishing.

## 2.6.104
- Improved, from reading [LiTube](https://github.com/HydeYYHH/litube)'s player and Shorts code (LiTube is a native app, so
  its player cannot be copied into this WebView app; the rules that carry over were re-written here):
  - Full screen turns the phone to the picture's own shape (`MainActivity.lockFullscreenOrientation`, from LiTube's
    `PlayerWindowHost.applyImmersive`): sideways as before, but upright for a video taller than wide. The player's page is
    asked for the picture's size first so the phone turns once; no answer within 350 ms (or a page that is not the
    player) gives the old sideways lock.
  - The turn uses the system's own rotation animation (`ROTATION_ANIMATION_ROTATE`) instead of a cross-fade of a picture
    of the old orientation.
  - A dialog, the share sheet or the notification shade over the full-screen video can leave the system bars showing: they
    are hidden again when the window gets its focus back (`onWindowFocusChanged`).
  - New menu option **Next Short when one ends** (off by default; Playback section): YouTube's page only repeats a Short
    that ends. With the option on, the page moves on to the next Short a moment before the end, or right after the Short
    started over if its 0.3 s timer missed that moment (`autoNextShort` in `wardtube.js`; it shares `nextShort` with the Shorts
    ad skipper). Nothing happens for a paused Short or an ad.
- Fixed: **picture in picture could use the shape and the "where the video is" of the page behind full screen.** The
  window's params (shape, arming, source rectangle) were refreshed only by the player's report, every one to three
  seconds, so pressing Home soon after entering full screen handed Android the player box's old place on the screen, which
  crops the window to the wrong part. They are now refreshed the moment full screen starts, as LiTube does on every player
  state change. (Leaving full screen is left to the report: the box measured in full screen is the screen's shape.)
- Not changed: the picture-in-picture window itself still takes the video out of the page's full screen (see
  `enterPipUi`): LiTube's window works from full screen because its full screen is only a state of its own player view,
  while here full screen is the page's, and YouTube's page leaves it by itself when the window turns upright.

## 2.6.103
- Fixed: **posts were slow to come and rare in "All content", and Shorts were rare in it too.**
  - Posts, why they were rare: "All content" and "Today" put posts among the videos only from YouTube's own Posts list. When
    that list gave none (the case that 2.6.100 met), the channels' own posts tabs were read only for the Posts chip, so
    "All content" never had a post. Now, when YouTube's own list gives none, the same channels' posts are read for "All
    content" and "Today" too (the first 4 batches of 8 channels on their own, then 1 batch more every time the end of the
    list is reached, 12 at most between two feed refreshes), and they stand among the videos by their age. When YouTube's own
    list works, its next pages (3 at most) are read on their own until they reach as far back as the feed does (before,
    only at the end of the list), so posts are spread along the whole list and not only at its top.
  - Posts, why they were slow, in `SubsController.kt`:
    - A batch showed nothing until **all** of its 8 channels had answered (one slow channel, up to 12 s, held the other
      seven). Each channel's answer now goes into the list as it comes (the list is redrawn at most every 0.6 s).
    - The channels' posts shared 4 threads with the feed, the chips and the playlists. They now have a pool of 5 threads
      of their own (`postsPool`).
    - Most channels have no posts tab, and each one cost a whole page to find that out. What was learned is kept for 3 days
      (`subs_state`: `posts_no`, `posts_yes`): the channels with posts are read first, the ones without are skipped. A "no"
      is trusted only after some channel in the same run gave posts, so a reading that fails as a whole (a page of another
      shape) can never mark every channel as "no posts".
    - Every feed refresh threw the posts away and read all channels again from an empty list. The posts on view now stay
      until the new reading brings its own, and YouTube's own Posts chip, once it answered with no posts, is not asked
      again for 30 minutes (it cost a request at every refresh).
    - A feed refresh that arrived while the Posts chip's first request was in flight left `postsLoading` true for good
      (the answer was dropped as old); it is cleared now.
  - Shorts: the feed holds Shorts only in a few shelves, and "All content" listed only those, so its rows of Shorts ended
    after the first screens. "All content" now also reads YouTube's own list of Shorts (the one the Shorts chip uses),
    puts them after the feed's own Shorts in its rows, and reads on (the next pages) while the rows lack Shorts for the
    videos loaded so far. The Shorts chip opens at once, its list being read already. The list no longer jumps to its top
    when that list arrives under "All content" (`toTop` only for the Shorts chip).

## 2.6.102
- Fixed: **the pictures of the playlists in the "Save to…" sheet showed the first time and not the next times.**
  - Cause: a playlist's picture address is kept after the first read, and from then on it is handed to the sheet *while its
    rows are still being built*, before the sheet is shown. The row's callback only loaded the picture
    `if (dialog.isShowing)`, which was false at that moment, so every later opening dropped the picture (the first opening
    got it later, through the network, when the sheet was already showing). `SubsPlaylistSheet.kt` no longer gates on that.
  - `SubsImages.kt`: an address that failed was written in the "missing" list until the app restarted, whatever the reason,
    so one bad moment of the network lost that picture for good (the feed's thumbnails and avatars too). Now only a
    definite "no picture" (not found, not an image) is remembered, and only for 3 minutes; a timeout, a dropped
    connection or a server error (5xx, 429, 408) is tried once more at once and is never remembered.
  - `SubsImages.load(..., urgent = true)`: the sheet's pictures have two threads of their own, so they no longer wait behind
    the screen's thumbnails.
  - `SubsController.kt`: a playlist whose page gave no picture is asked again after 2 minutes (it was kept empty until the
    app closed), and a failed request is repeated once after 1.5 s.
  - A playlist without a picture now shows a tile with a list icon (the old placeholder was the colour of the sheet itself
    at night, so a missing picture looked like an empty hole). The names and "خاصة" lines stand at the start of the row
    (the right in Arabic) even when a name is in Latin letters ("M", "TikTok Songs" stood at the left).

## 2.6.101
- Changed: **the "مستوى العرض" (Visibility) menu of a new playlist now looks like YouTube's own**: a sheet rising from the
  bottom (instead of the plain grey list dialog) with the title and a thin line, then the three choices in YouTube's
  order, each with its icon, its name and what it means, and a tick at the end of the chosen one's row:
  - علنية (globe): "يمكن للجميع البحث عن قائمة التشغيل وعرضها"
  - غير مدرجة (link): "يمكن لأيّ مستخدم لديه الرابط عرض المحتوى"
  - خاصة (lock): "لا يستطيع أحد سواك عرض قائمة التشغيل"
  A tap picks and closes it; the keyboard is put away before it rises. New: `askPrivacy` in `SubsPlaylistSheet.kt`,
  `ic_link_24dp.xml`, `ic_lock_24dp.xml` and the three `subsPrivacy*Hint` strings (Arabic and English).
- Changed: the Arabic names of two visibility levels follow YouTube's: "عامة" is now "علنية" and "غير مُدرَجة" is "غير مدرجة"
  (also in the rows of the "Save to…" sheet).

## 2.6.100
- Fixed: **the Posts chip of the Subscriptions screen showed "لا توجد منشورات حديثة"** even though channels have posts.
  When YouTube's own list of posts could not be read (the chip's answer held no post the app knew the shape of), the
  chip fell back to the posts that the *feed* carries, and the feed carries none, so the list stayed empty. Now:
  - `SubsRepository.kt`: any object under a key that says "post" and carrying a `postId` is read as a post (YouTube renames
    its renderers now and then), and a post's text is also looked for under `content` and `text`. A chip's answer now
    comes with a short note of what it held (`SubsPage.note`: items, lockups, shorts, posts, and the keys that say "post").
  - `SubsController.kt`: when YouTube's own list gives no posts, the Posts chip lists **the posts tab of the channels
    themselves**, 8 channels at a time (the strip's order), put together newest first. It is **endless**: scrolling to
    the end reads the next channels, and, when every channel was read, the next page of the channels whose last post
    read is the newest. It stops only when the channels have no more (or after 15 batches in a row with nothing new,
    until the next tap on the chip). A pull-to-refresh gives YouTube's own list another chance first.
  - The Posts list can hold 3000 posts (was 1000).
  - When the list stays empty, the note under "لا توجد منشورات حديثة" now tells what the Posts chip's answers held and how
    many channels were read, instead of the numbers of the feed page (which said "posts 0" whatever the chip did).

## 2.6.99
- Fixed: **the build failed** in `MainActivity.kt` (`No value passed for parameter 'onDownload'`, `Cannot infer type for this
  parameter`). Since 2.6.96 `MenuDownload` has a second callback, `onDownloadCurrent`, as its last parameter, so the
  trailing lambda of `MenuDownload(webView) { url, title -> ... }` went to that one (it takes only a url) and `onDownload` got
  nothing. The call now names the parameter: `MenuDownload(webView, onDownload = { url, title -> ... })`, the way the player
  WebView's call already did.
- Fixed: **the screen now turns off by itself when the video is paused**, in full screen and on a Short. Before, it stayed
  on for as long as the video was paused.
  - `MainActivity.kt`: the screen was kept on whenever the video was in full screen, playing or not (`isFullscreen ||`
    in the poll, and `setKeepScreenOn(fullscreen)` when full screen opened or closed). Now only a playing video keeps it
    on. Opening or closing full screen asks the player at once (`refreshKeepAwake`) instead of waiting for the next poll.
  - On a Short, the app read the play / pause state from the page's *first* `<video>`, and a Short's page holds several
    (the neighbouring Shorts are kept ready, some playing or looping out of sight). If that one kept playing, the video
    on screen could be paused and the app still thought it played. `wardtube.js` has a new `__wardtube.video()` (the
    active reel's video, else the one with the most area in the window; any other page: the main video as before),
    and `PlayerController.kt` uses it for the state and for play / pause / seek.
  - Not built and not run on a phone, and not tried against live YouTube: the Short's real markup may differ from what is
    matched (the fallback is the video with the most area in view, which does not depend on the markup).

## 2.6.97
- Changed: **the "Open app" row is hidden in YouTube's menus**, the one that a long press on a Short opens (and any other
  menu of the page that has it). Before, only the "Open app" button of the top bar was hidden. The other rows of the menu
  stay as they are.
  - `assets/wardtube/wardtube.js` (`hideOpenAppRows`): a menu row is hidden when its label is one of the "Open app" labels
    of the top-bar button (the same list), or says "open (in) (the) (YouTube) app" in English, or has the Arabic words for
    "open" and "app" together in a short label (e.g. "فتح في تطبيق يوتيوب"). Rows of YouTube's own kinds are found first
    (`ytm-menu-service-item-renderer`, `ytm-menu-navigation-item-renderer`, `yt-list-item-view-model`, `role="menuitem"`),
    then any button or link inside an open menu; the wrappers go with it as long as they hold nothing else.
  - Only a change inside a menu, or one that brings a menu in, is looked at, and the row is hidden before the next paint (at
    most every 100 ms), so it is not seen even for a moment. The slow timer looks again every 1.5 s as a last resort.
  - `base.css`: `[data-wt-open-app]` stays `display: none`, so the row does not come back if YouTube clears the inline
    style when it redraws it.
  - Tried on a stand-in menu in a desktop Chromium (three kinds of rows, a label that arrives after the row, English,
    French and Arabic labels, a menu without the row, a second opening): the row goes, the others stay. Not built and not
    run on a phone, and not tried against live YouTube: the menu's real wording or markup may differ from what is matched.

## 2.6.96
- Added: **a download entry in the three dots under the video of the watch page** (the row with like, dislike, share ...).
  The three dots open YouTube's menu (Save, Report ...) and **"Download video"** is now its first row, with the look of
  the others. A tap runs the same flow as the download button of the bottom bar (a live video is told so first, the
  format window follows, then the Library). The same row is now in the three dots of the videos listed under the player.
  - If the row has no three dots (or their menu could not take the row), **the row gets a "Download" button of its own**,
    after its last button, with the size, colours, corners and spacing copied from the buttons beside it.
  - If neither can be built (no bridge, the row of buttons is not found, an error, nothing visible), **nothing is shown**
    and the bottom bar's download button stays the way to download. A half-built row or button is never left on the page.
  - Why it was missing: `menu-download.js` ran only in the browsing WebView; the watch page lives in the player WebView,
    which did not have it. `MainActivity.createPlayerWebView` now installs a `MenuDownload` on it with each player
    (`onPageChanged` of the player injects it by hand on a WebView too old for document-start scripts).
  - `assets/wardtube/menu-download.js`: finds the row from the like button (its tag names, or `aria-pressed`, whatever the
    language, and it must be below the video, so the player's controls and the top bar are never taken for it), then
    the three dots in it (inside YouTube's menu element, or a label that says "more", or a last button drawn as three
    dots). The video is the one in the address (`/watch?v=`), read again at the tap. A tap on any other button of the
    page (share, save ...) gets no row. In the menu, the rows of an open sheet are tried before any other row.
  - `web/MenuDownload.kt`: new bridge method `downloadCurrent(kind, id)` (the address is built by the app from the
    11-character id, as for `download`) and the `onDownloadCurrent` callback.
  - Tried on a stand-in watch page in a desktop Chromium (menu with the three dots, row without them, a menu that cannot
    take the row, no bridge, no row, another page, the share sheet, a card's three dots, a move to another video): all as
    described. Not built and not run on a phone, and not tried against live YouTube: its real markup may differ from the
    stand-in, and then the button or the row simply does not show.

## 2.6.95
- Changed: **"Save to playlist" (the three dots of a video or a Short on the Subscriptions screen) now opens YouTube's own
  kind of sheet** instead of a plain list of tick boxes: a dark card rising from the bottom with the account's playlists
  ("Watch later" first), each with its picture, its name, who can see it (Private / Public / Unlisted) and a bookmark that
  is filled when the video is already in it. A tap on a row puts the video in, or takes it out, at once (a failed tap puts
  the bookmark back).
  - Added: **"New playlist"**, a button under the list. It asks for a title and the visibility (private to begin with),
    "Create" is off until a title is typed, and the card closes only with its two buttons. The playlist is made with the
    video already in it (`playlist/create`), then the sheet closes.
  - The pictures: taken from YouTube's answer when it carries them, else each playlist's first video picture is asked for
    in the background (`browse` of `VL<playlist id>`) and kept until the app closes; a row shows a plain tile meanwhile.
  - New: `SubsPlaylistSheet`, `SubsRepository.createPlaylist` / `fetchPlaylistThumb`, `PlaylistOption.privacy` / `thumb`,
    `SubsPalette.button`, the filled bookmark icon and nine strings (English and Arabic); the "Done" string went.
  - Not built on a phone, and not tried against live YouTube: the playlist creation and the picture requests are the
    parts that depend on YouTube's answers.

## 2.6.94
- Added: **the three dots on the Shorts of the Subscriptions screen.** Every Short card (in the Shorts rows of "All content"
  and in the two columns of the Shorts chip) now has the three dots at its top corner on the end side (the left in
  Arabic), like the long videos' cards and like the YouTube app's. They open the same menu as a video's: **Watch later**,
  **Save to playlist**, **Download**, **Share** and **Hide** (**Unsubscribe** too when the feed told which channel the
  Short belongs to). A tap on the dots does not open the Short.
  - Changed: `SubsScreen.buildShortCard` / `bindShort` (the dots button and a light shade behind it, so the white dots show
    over a bright picture); the menu itself (`SubsController.onVideoMenu`) is unchanged, it already worked for a Short.
  - Not built on a phone.

## 2.6.93
- Added: **a "Posts" chip on the Subscriptions screen, and posts in "All content" and "Today".** The chip stands last in the
  bar (after Live, as in the YouTube app) and lists the posts of the channels you subscribe to, newest first, drawn as
  YouTube draws them: the channel's picture, its name and the age of the post, the words, what is attached (a picture, a
  poll with its picture choices and its votes, or a video), then the likes and the comments. Links in the words are
  coloured and open (a video plays, a YouTube page opens in the app, another site goes to the phone's browser); a tap
  anywhere else on the card opens the post's own page. The three dots offer **Share**, **Unsubscribe** and **Hide**.
  - "All content" now shows everything: videos, Shorts (in their rows) and posts, in order of age. "Today" lists the posts
    of the last day with the videos. With a channel chosen, "All content" is its videos, posts and Shorts (read one tab
    after the other, the videos first); "Posts" is its posts tab.
  - Where the posts come from: the feed pages themselves (they carry posts as `backstagePostRenderer` / `postRenderer`),
    and YouTube's own Posts chip, read the way the Shorts chip is (the chip named like "Posts" in any of eleven languages,
    else the sixth one; its answer counts only if it is nearly all posts, so a wrong guess shows nothing wrong). Its list
    goes on with its own next page; "All content" reads on in it as the feed goes deeper, and shows only the posts as old
    as the videos loaded so far, so the order stays true. With no usable chip, the Posts chip lists the posts of the feed.
  - Kept on the phone with the rest of the feed (new `po` field of `subscriptions.json`; an older file loads as before);
    hidden posts are remembered like hidden videos (their ids are longer: the 11-character filter became "at least 11").
  - New: `SubsPost`, `PostLink`, `PostChoice` and `SubsVideo.post` (a post travels in a `SubsVideo`, so ordering, hiding
    and saving needed no second path), `SubsFilter.POSTS`, `SubsListener.onLink`, `SubsRepository.mostlyPosts`, the
    post card in `SubsScreen`, two icons and three strings (English and Arabic). The streams-only tab check became
    `tabOpen(node, tab)`, which also knows the posts tab.
  - The empty screen of the Posts chip shows the page diagnostic (the new `posts N` count) like "All content" does.
  - Not built on a phone. The reading of posts, their saving and the tab check were run on the JVM against sample data in
    the shape of the posts in the screenshots (a poll with picture choices and a link, a picture post, a post with a video);
    YouTube's real pages were not available to test against.

## 2.6.92
- Added: **the three dots on the videos of the Subscriptions screen**, with a menu like the YouTube app's. The dots stand
  at the end of the title row of each video card (the left in Arabic) and open a rounded card beside them (dark grey at
  night, white by day) over a light dim; a tap outside it or the back button closes it.
  - Rows: **Save to Watch later**, **Save to playlist** (the account's playlists with a tick on those that already hold the
    video; a tap adds or takes it out at once), **Download** (the app's usual download flow), **Share** (the phone's share
    sheet with the `youtu.be` link), **Unsubscribe** (asks first; the channel leaves the strip and its videos leave the
    lists) and **Hide** (the video leaves the lists at once, with an "Undo" bar).
  - Watch later, the playlists and Unsubscribe call YouTube's own API (`youtubei/v1`) with the WebView's cookies, the same
    way the screen already reads the feed (`SubsRepository.postApi`, new `ApiAccess`). "Hide" is kept on the phone only
    (per account, up to 1000 videos): YouTube is not told, so the video stays in YouTube's own page and app.
  - Not in this version: "Play on another device" (needs Google's Cast library, which an F-Droid app cannot carry) and the
    two queue rows (the app has no queue yet). Shorts tiles have no dots yet.
  - New: `SubsVideoMenu`, `SubsListener.onVideoMenu`, `SubsPalette.menu`; `SubsController` takes `onDownload` and `onShare`
    from `MainActivity` (`shareLink`). Four icons and 18 strings (English and Arabic).
  - Not built or run on a phone.

## 2.6.91
- Fixed: **hiding the bottom bar while scrolling did not work on the Subscriptions screen** (the bar did not hide when the
  list was scrolled, and a bar that was already hidden did not come back).
  - Cause: the hiding follows the finger through a touch listener on the browsing WebView (`watchScrollDirection`). The
    Subscriptions screen is the app's own view placed over the WebView, so none of its touches ever reached that listener.
  - `SubsScreen` now watches the finger over the whole screen (`dispatchTouchEvent`, touches are only watched, never
    consumed) with the same rule as the page: 24 dp in one direction, starting over when the finger turns, horizontal
    drags and pinches ignored. It sees a pull-down at the top of the list too, so a hidden bar can always be brought back.
    New `SubsListener.onDrag(down)`, passed on by `SubsController` (new `onScrollDirection` parameter) to
    `MainActivity.onPageScrolled`, so the "Hide the bar while scrolling" switch and the exceptions (full screen,
    expanded player, load-error notice, Shorts page) apply here exactly as on the other pages.
  - Not built or run on a phone.

## 2.6.90
- Added: **a note under the "Picture in picture" row of the three-dots menu** (shown whether the switch is on or off):
  the window is not reliable when the video is in fill or full-screen mode, and works well with the video in the portrait
  page or in the mini player. New string `pictureInPictureNote` (English and Arabic); `MenuController` adds it on a new
  line after the state line. Nothing else changed: the 2.6.89 changes to the window stay as they are.

## 2.6.89
- Fixed: **the picture-in-picture window, entered from the player's full screen, did not look the same as the one entered
  from the normal watch page or the mini player** (a band of black under the picture, the picture pinned to the top of the
  window instead of filling it).
  - Cause (read from the screenshots, not confirmed on a phone): after full screen the page's player box is the full 16:9
    but YouTube leaves the `<video>` sized for an in-between moment of the page, a few percent shorter than its box. The window
    fitted the box, so the missing part showed as black under the picture. The synthetic `resize` events of `relayoutPage` did
    not make YouTube size the video again.
  - `PlayerHost.nudgePage`: the WebView is laid out 6 px narrower for 140 ms and then given its size back, a real change of
    width that makes YouTube's own resize handling run. Once after full screen (about 1.4 s into the window), and, at most 2
    times and not closer than 0.9 s, whenever the measure finds the picture shorter than its box. Nothing is measured while
    the nudge lasts.
  - `MEASURE_SCRIPT` also reports the `<video>`'s own box (four more numbers, zeros if none). `measureVideoBox`, in picture
    in picture only, fits that box instead of the player's when it is inside it, nearly as large (90% wide, 85% tall) and
    shorter than it by more than 2%. A wide film's bars or a 4:3 video's sides (picture much smaller than its box) are not
    touched, and neither are the mini player and the normal page.
  - Not built or run on a phone or against live YouTube pages.

## 2.6.88
- Fixed: **the picture-in-picture window, entered from the player's full screen, came out right only some of the times.**
  - Cause: a race in `MainActivity.enterPipUi`. While the window shrinks, YouTube's page can leave full screen by itself
    (its viewport turns upright) before `onPictureInPictureModeChanged` arrives. `isFullscreen` was already false then, so
    `takeOver` was false: the box measured in full screen was kept, the page was not laid out again, and a video the page
    paused was not started again. When the page was slower, everything was right.
  - `MainActivity`: `onUserLeaveHint` now remembers (before its early returns, because Android 12+ enters the window by
    itself) whether the player was in full screen and whether the video was playing (`playerFullscreenAtLeave`,
    `playingAtLeave`, `leftAppAt`). `enterPipUi` decides `takeOver` and `wasPlaying` from that memory as well as from the
    state it finds. The memory is cleared in `onResume` and once `enterPipUi` has used it.
  - `canEnterFullscreen` also refuses while the app was just left and the window has not come yet (`leavingForPip`): the
    page asks for full screen again when the window's shape changes, which put the landscape lock back inside the window.
- Changed: **the "Open app" button of YouTube's top bar is hidden.**
  - `base.css` hides it by the names it is known to have (`ytm-open-app-button`, classes containing `open-app`).
  - `wardtube.js` (`hideOpenApp`) also finds it by its label (Arabic, English and about 15 other languages), inside the top
    bar only, and hides it with the wrappers that hold nothing else. It looks again when the page changes and every
    1.5 seconds.
  - Not built or run on a phone or against live YouTube pages: the script was only tried on test pages, so the real name of
    the button is not confirmed.

## 2.6.87
- Changed: **the Shorts chip of the Subscriptions screen (no channel chosen) goes on with no end, like YouTube's.**
  - Cause: the chip only picked the Shorts out of the feed of every video. YouTube puts Shorts in the feed in a few
    shelves, so a page of 20-30 videos held a handful of them; the chip then stopped asking for more after 8 pages in a
    row with no Short, or at 500 Shorts, and a list too short to scroll had no scroll to ask for the next page.
  - `SubsRepository`: the feed's first page now also gives its filter chips (`collectChips`: `chipCloudChipRenderer` and
    `chipViewModel`, each with its continuation token). `fetchChip` reads the first page of one chip's own list, and
    `mostlyShorts` says whether that answer is (nearly) all Shorts. The pages after it come through the same `fetchMore`
    as the feed's, from the continuation at the end of each page.
  - `SubsController` (`ensureShorts`, `onShortsHead`, `moreShorts`): when the Shorts chip is chosen it asks YouTube for
    the chip's own list (the chip named "Shorts" or "قصير", else the one in the Shorts chip's usual place, the fourth),
    keeps it in `shortsFeed` with its own next page, and asks for the next page whenever the end of the grid comes in
    view, until YouTube has no older Short (a stop at 10,000 only guards against a list that never ends). A chip's answer
    is used only if it is (nearly) all Shorts, so a wrong guess of which chip it is can never show another list.
  - If no chip gives Shorts (YouTube changed its page, or the page has no chips), the chip lists the Shorts of the feed as
    before, with wider limits: 20 pages in a row with no Short (was 8), 2000 Shorts (was 500). A read that failed (offline,
    an error answer) is tried again after a minute or at the next tap on the chip; the feed's Shorts show meanwhile.
  - The list is read again after a pull-to-refresh and when the chip is tapped after 5 minutes.
  - `SubsScreen`: the Shorts grid asks for its next page 12 tiles before its end (about three screens; 4 items for the
    other lists), so the list goes on under the finger without a wait.
  - A channel's own Shorts (a channel chosen in the strip) are unchanged.
  - Not built or run on a phone or against live YouTube pages (no Android SDK and no access to YouTube where it was
    written): the code was checked with the Kotlin compiler for syntax and for the names it uses.

## 2.6.86
- Added: **the sound is turned on for a Short that opens muted, the same way it is for a normal video in the player.**
  2.6.85 let the browsing WebView play a Short with sound, but YouTube's own page can still start a Short muted (it does so
  for any page opened without a tap inside it), so the page script now does what it already did for the player.
  - `wardtube.js`, `autoUnmuteShort` (new, run by the 300 ms timer on the browsing page only, on `/shorts/...` addresses):
    once per Short it unmutes the video (`player.unMute()` when the page has it, then `video.muted = false`, then a tap on
    `.ytp-unmute` if that is on view), tries for about 15 seconds at most, and counts the job done after the Short has
    played 2 seconds with sound, so a Short the person mutes by hand stays muted.
  - A new Short starts the job again: when the address names another one, when the page loads a new picture in a video
    (`loadstart` / `emptied`, because YouTube may change the address a moment after it starts the next Short), or when the
    person swipes to the next one.
  - The video of the Short on view is the active reel's, else the one that is playing, else the page's main video.
  - An ad reel is left alone (the existing Shorts ad skipper mutes it and moves on).
  - Checked with a fake page in node (12 checks: unmute, the person's own mute, next Short, `loadstart`, ad reel, other
    pages and the player untouched, giving up after 50 tries, `player.unMute`, the fallback to the playing video). Not
    built or run on a phone or against live YouTube pages (no Android SDK where it was written).

## 2.6.85
- Fixed: **a Short opened from the app (most of all from the Subscriptions screen) did not play its sound until the
  person tapped the screen.**
  - Cause: a Short opens in the browsing WebView through `loadBrowse` (`webView.loadUrl`), so the page loads without any
    tap inside it. The browsing WebView kept WebView's default `mediaPlaybackRequiresUserGesture = true`, which keeps the
    sound of a video off until the page has had a tap. (The player's WebView, where normal videos play, has had it
    switched off since the start, which is why those start with their sound.)
  - `MainActivity.allowAutoplayFor` (new) sets the rule from the address: off while a Short's page is the one on view,
    on for every other page of the browsing WebView (so nothing else starts to play by itself). It runs in `loadBrowse`
    (before the page is loaded), `onPageStarted` (a Short reached by a link of YouTube's own page loads as a new page
    too) and `onPageChanged` (YouTube changing the page without a load).
  - Not built or run here (no Android SDK where it was written). If a Short still starts without sound on a phone, the
    cause is YouTube's own page choosing to start it muted, and the next step is to unmute it from the page script.

## 2.6.84
- Fixed: **the strip of channels on the Subscriptions screen still scrolled strangely when the screen opened (2.6.83 did
  not fix it).** The person noticed that it began with 2.6.82, when the "All" button was fixed beside the strip.
  - Probable cause: in 2.6.82 the strip became a weighted child (width 0, weight 1) of a horizontal `LinearLayout` next to
    the button. A `LinearLayout` measures such a child twice, first with an unspecified width and then with the width
    left over, and a `RecyclerView` lays its items out in both passes, so on every layout (and the screen has many in its
    first moments: the pictures arrive one by one) the strip was laid out with two different widths. Not confirmed on a
    phone: it is the one thing 2.6.82 changed in the strip's measuring, and 2.6.81 did not have the problem.
  - `SubsScreen`: the row (`stripRow`) is a `FrameLayout` now. The strip fills its whole width, as it did before 2.6.82
    (one measure with an exact width), and the "All" button sits over its end in a holder of the screen's own colour that
    hides the channels scrolling under it and takes the touches there. The strip's end padding leaves room for the button,
    so the last channel can still be scrolled into view. `setPaddingRelative` and `Gravity.END` keep the button on the left
    in Arabic.
  - `SubsScreen.scrollStripToStart` also pins the strip: after every layout of the strip, while the person has not touched
    it, the strip is put back at its first channel if that is not fully in view (at most 8 times, a guard against a loop).
    Touching the strip ends the pin.
  - 2.6.83's changes stay (the order of the strip is kept while the screen is open; the strip returns to its first channel
    when the screen is opened again), they were a real cause of shuffling but not the one of this problem.
  - Not built or run here (no Android SDK where it was written).

## 2.6.83
- Fixed: **the strip of channels on the Subscriptions screen moved and scrolled by itself when the screen opened, and did
  not start from its first channel.**
  - Cause 1: the strip was put in "newest video first" order again every time the feed finished loading
    (`SubsController.finishFeed`), a second or two after the screen opened, and the adapter was reset
    (`notifyDataSetChanged`), so the pictures changed places under the person's finger.
  - Cause 2: nothing ever took the strip back to its first channel (`scrollToPosition(0)` was only called for the list of
    videos), and the screen is kept, only hidden, between visits, so the strip opened where it was left, and then its
    channels were reordered under that place.
  - `SubsController.orderStrip` (new) sets the strip. While the screen is on view and the strip was already ordered by a
    feed, its order stays: channels that are gone leave, new ones join at the end, the rest take the newest data. The new
    order is applied when the screen is opened again, after a pull-to-refresh, and the first time a feed is read (before
    that the channels were not in a meaningful order). The pictures and blue dots are still updated at once.
  - `SubsController.show`: when the screen is opened (it was hidden) the strip goes back to its first channel, unless a
    channel is chosen. After a new order is applied while the screen is on view, the strip goes back to its start too.
  - `SubsScreen`: `setChannels` / `setSelected` only rebind the items when the order did not change
    (`notifyItemRangeChanged`, no change animation) instead of resetting the adapter; `scrollStripToStart` (new).
  - Not built or run here (no Android SDK where it was written). The ordering rules were checked on a port of the logic.

## 2.6.82
- Changed: **the "All" button of the Subscriptions screen is fixed in its place, like YouTube's, and opens the list of
  every subscription.** It used to be the first item of the strip of channels: it scrolled away with them, and a tap on
  it only took the chosen channel away (back to the videos of every channel).
  - `SubsScreen`: the strip now sits in a row (`stripRow`) with the "All" button after it, outside the scrolling list, at
    the end of the row (the right in a left-to-right language, the left in Arabic, where YouTube has it too). The
    channels scroll beside it and are cut where its space begins. The button is level with the channels' pictures, in the
    same blue as before. The strip's adapter lists only channels now (`TYPE_ALL` and `buildAllItem` are gone).
  - `SubsListener.onOpenAllChannels` (new) and `SubsController.onOpenAllChannels`: the tap opens
    `https://m.youtube.com/feed/channels` (YouTube's "All subscriptions" page, the one the app also reads its list of
    channels from) in the browsing WebView. The screen is held over the page until the new page is on view, the same way
    as for "Go to channel" (2.6.80), so YouTube's own Subscriptions list does not show for a moment. The Back button
    returns to the Subscriptions screen.
  - To go back from one channel's videos to every channel's, tap the chosen channel again (it was already so; the "All"
    button used to do it too, and no longer does).
  - Not tested on a phone (no device or Android SDK where it was written, so the app was not built either): the button's
    size and the place where the channels are cut were chosen from the screenshot of YouTube's own page.

## 2.6.81
- Removed: **the SoundCloud shortcut** from the browser's quick sites (the main screen of the browser).
  - `BrowserHome.builtIn()` lists six sites now (YouTube, TikTok, Facebook, Instagram, Dailymotion, Vimeo), and the
    string `quickSoundcloud` (English and Arabic) is gone with it.
  - It was a built-in tile, so it could not be removed with a long press like the sites the person adds. Sites the person
    added themselves (`Prefs.quickSites`) are not touched, so a SoundCloud tile added by hand stays.
  - Not built or run here (no Android SDK where it was written); the change is the removal of one line.

## 2.6.80
- Fixed: **"Go to channel" on the Subscriptions screen showed YouTube's own Subscriptions list** for a second, or for the
  whole time the channel's page was loading, before the channel appeared.
  - Cause: the tap opens the channel's page in the browsing WebView, and the screen was taken away as soon as that page
    started loading (`SubsController.onPage` sees an address that is not the Subscriptions one). A WebView goes on
    drawing the page it had until the new page paints its first content, so the page under the screen, YouTube's own
    Subscriptions list, was uncovered for the whole load (long on a phone with little memory).
  - `SubsController`: the tap now starts a hold (`startHold`): while it lasts `onPage` does not take the screen away.
    The hold ends (`endHold`, which then takes the screen away as before) when the WebView reports that the channel's page
    painted (`onPageShown`), or after `HOLD_MAX_MS` = 12 seconds if it never does (offline, a failed load). While it lasts
    the pull-to-refresh spinner of the screen is shown, so the person sees that the channel is loading.
  - `PageEvents.onPageCommitVisible` (new, empty by default) is sent by `WardTubeWebViewClient.onPageCommitVisible`
    (the WebView's "first content of the new page is drawn; no leftovers of the old page" callback, API 23+; the app's
    minimum is 24) and `MainActivity` hands it to `SubsController.onPageShown`.
  - Only the tap on "Go to channel" holds the screen. Any other change of page (YouTube's own tabs and links) takes the
    screen away at once, as before.
  - Not tested on a phone (no device or Android SDK where it was written, so the app was not built either): if the
    old list still shows for a moment, the WebView reported the new page's first paint before it had really replaced
    the old content.

## 2.6.79
- Fixed: **the Live chip of the Subscriptions screen listed ordinary videos for a chosen channel** that has no live
  streams (a channel's own list under "Live" showed its normal videos, with their lengths, as if they were past streams).
  - Cause: the Live chip of a chosen channel reads the channel's streams page (`/channel/<id>/streams`) and marked every
    video found there as a past live stream (live type 3). A channel with no streams has no such tab, so YouTube answers
    that address with the channel's first tab instead (its ordinary videos), and those were all taken for streams.
  - `SubsRepository.streamsTabOpen` (new): reads which tab the page data says is open (the `tabRenderer` marked
    `selected`: its address ends with `/streams`, or its `params` are the streams tab's). `fetchChannelTab("streams")`
    now returns an empty page when another tab is open, so nothing is listed for a channel without streams. A page that
    names no open tab at all is taken as before (so a change in YouTube's page cannot hide the streams of the channels
    that have them).
  - `SubsScreen`: an empty Live chip says "No live streams" (`subsEmptyLive`) instead of "No recent videos".
  - Only the streams tab of a chosen channel is changed. The Live chip of the whole feed (no channel chosen) already lists
    only videos YouTube marks as live or scheduled, and the Shorts and Videos chips are as they were.
  - Not tested on a phone or against YouTube's live pages (no device, Android SDK or access to YouTube where it was
    written; it was checked against small models of the page data). If a channel without streams still lists its
    videos, YouTube's page names its open tab in a way the check does not know.

## 2.6.78
- Fixed: **the Shorts chip of the Subscriptions screen was limited**: it listed only the few Shorts found in the feed's
  first 200 videos and then stopped, while "All content" and "Today" went on loading as the list was scrolled.
  - Cause: the Shorts chip lists the Shorts of the feed (`feed.filter { it.short }`), and the feed stops growing at
    `MAX_FEED` = 200 videos of every kind, of which only a small share are Shorts (about 40 in a model with 6 Shorts to a
    page of 30). Also, only the "All content" and "Videos" chips asked for more when the end of their list was already in
    view (`applyVideos` → `checkEnd`), so a short Shorts grid that fitted on the screen never asked.
  - `SubsController`: while the Shorts chip is chosen the feed goes on past `MAX_FEED` until it holds
    `MAX_SHORTS` = 500 Shorts (`feedFull`); past 200 videos only the Shorts of each new page are kept, so the other chips
    keep their size and speed (`onEnd`).
  - The Shorts chip asks for the next page by itself when the end of its grid is in view (`shortsChipReadsOn`), and stops
    doing so after `MAX_DRY_PAGES` = 8 pages in a row with no new Short (`shortsDry`); scrolling still asks for one more.
  - Only the feed's Shorts chip is changed. A chosen channel's own Shorts / Videos / Live list still shows the first page
    of that channel's tab (`loadChannel`), as before.
  - Not tested on a phone (no device or Android SDK where it was written; the loading rules were only checked on a small
    model). If YouTube's later pages of the feed hold few Shorts, the chip ends sooner than 500.

## 2.6.77
- Fixed: **the picture-in-picture window after leaving the app from a full-screen video** showed the video outside the
  window's frame: only the top part of the picture (cut off) with black under it, instead of the whole picture filling
  the window like the mini player does inside the app.
  - Likely cause (worked out from the code and the screenshot; the phone was not at hand): in
    `MainActivity.enterPipUi` the video is taken out of the system's full screen while the window is already shrinking,
    so `PlayerHost.enterPip` fitted the picture while the
    page was still between layouts: the WebView had just come back from the full-screen view (its viewport went from the
    sideways full screen, to the little window, to the upright layout), and YouTube's player sizes itself at each step. The
    box measured at that moment (or one kept from before) was used, and the player could also stay sized for one of those
    moments. The mini player never meets this: its page is already settled when it is measured.
  - `PlayerHost.enterPip(afterFullscreen = true)` (only when the window comes from full screen; the normal path is
    unchanged): the box measured before is dropped, and for 6 seconds (`PIP_STRICT_MS`) a measured box is only believed when
    it is where the page script pins the player (top-left corner, as wide as the page, not taller than wide); otherwise
    the last box, or 16:9 at the top, stays and the next look decides (`measureVideoBox`).
  - `PlayerHost.pipSettle`: in the first 3.6 seconds the window tells the page it is the box, fits the picture to the
    window's current size and measures the box again six times (`PIP_SETTLE_MS`), instead of once and then every 2
    seconds, so a wrong first fit is corrected at once. After full screen each of these also calls `relayoutPage()`.
  - `assets/wardtube/wardtube.js`: new `window.__wardtube.relayout()`. It sends the events a real change sends (leaving
    full screen when the page is not in it, and a window resize) so YouTube sizes its player and the picture inside it
    again for the page's final size.
  - Not tested on a phone (no device where it was written; there is no network or Android SDK either, so it was also not
    built here): if the window still shows the video cut off, YouTube's player is left in a state that the events above
    do not reach.

## 2.6.76
- Added: **a "Download video" row in the menu of the three dots** beside a video on YouTube's pages (the Home feed,
  search results, channels, playlists, rows of Shorts). The row is the first one of the menu, with the look of the other
  rows (same text style, height and spacing), and it starts the same download as the download button: the format window,
  then the Library.
  - `assets/wardtube/menu-download.js` (new) runs on every YouTube page of the browsing WebView. A tap on a button inside a
    video card remembers that card's video (the video link inside the card, and its title); when YouTube's menu appears
    in the next 6 seconds the row is added before its first row. A card with no video link (an ad) gets nothing, and a
    menu opened by anything else is left alone. Tapping the row is swallowed (YouTube does not see it), the menu is
    closed the way a finger would (a tap on the dimmed page, then Escape) and the app is told.
  - The row is a plain `div` that copies the first row's text style, not a copy of YouTube's own row: that is a custom
    element that would start without the data it draws itself from. No `innerHTML` is used (YouTube's Trusted Types
    rules refuse it).
  - `web/MenuDownload.kt` (new) installs the script at the start of every YouTube page (`addDocumentStartJavaScript`;
    for a WebView too old for it, `MainActivity.onPageChanged` injects it by hand) and receives the tap through its own
    bridge, `WardTubeMenuDl`. The app builds the address itself from the 11-character video id (`/watch?v=` or
    `/shorts/`), so a page cannot hand it any other address.
  - `MainActivity.startMenuDownload` / `beginDownload`: the video belongs to a card of the browsing page, so the player
    (which may hold another video, even a live one) is not asked about it and the page's own title is not used as its
    name; the card's title is, when there is one.
  - Only the browsing page has the row (not the watch page, not the app's own Subscriptions screen).
  - A live stream picked here is not refused beforehand (the card does not say it is live).
  - Not tested against YouTube's live pages (no network where it was written; checked against a small model of the
    page): if the row does not appear in the menu, YouTube's menu uses names this script does not know.

## 2.6.75
- Fixed: **leaving the app from a landscape / full-screen video** left the picture-in-picture window wrong and the video
  stopped (it started when play was pressed, in the window or in the notification, and stopped again).
  - Cause: the video was left in the system's full screen, which is locked to landscape (`SENSOR_LANDSCAPE`), while the
    phone's home screen is upright. The window came out with the old full-screen layout (part of the page instead of the
    picture), the page's own full-screen changes (YouTube leaves and enters full screen when the shape changes) kept
    stopping the video, and `pipSourceRect` gave the shrinking animation the place of the player's hidden box.
  - `MainActivity.enterPipUi`: when the player's video is full screen as the window comes, it is taken out of full screen
    (`exitFullscreen`, which also gives the screen's rotation back) and the player fills the window the same way it does
    from the normal watch page (`PlayerHost.enterPip`). The page is laid out upright there (`pipLayoutSize`).
  - `MainActivity.pipSourceRect`: no source rectangle while the video is full screen.
  - `WardTubeChromeClient.FullscreenListener.canEnterFullscreen`: the page is refused full screen while the app is the
    little window, so it cannot put the landscape lock and the bars back inside it.
  - `MainActivity.guardPipPlayback`: the video was playing when the window came, so for 3 seconds it is started again if it
    reports paused (not when the window's own button was pressed).
  - Tapping the window brings the app back to the full-screen player (the watch page), not to the landscape full screen.
  - Not tested on a phone (no device where it was written).

## 2.6.74
- Changed: **shorter lists, faster loading** in the Subscriptions screen and in the "download a list" window.
  - **Subscriptions**: the feed now stops at 200 videos (was 1000, set in 2.6.73). Each new page rebuilds the whole
    list on the main thread, so a very long feed made the screen slow. `SubsController.MAX_FEED`.
  - **Download a playlist / channel**: only the first 50 videos are read and offered (was 200). yt-dlp has fewer pages of
    YouTube's list to ask for (a playlist page holds up to 100 videos), so the "Reading the list…" window is
    shorter, and the list to tick is shorter. The existing note ("Only the first 50 videos of the list are shown") follows the
    new number. `PlaylistLister.MAX_ENTRIES`.

## 2.6.73
- Fixed: the **Subscriptions screen** showed only the newest videos and then stopped; it now scrolls on without an end
  (like YouTube) and lists the newest videos first, older ones below, in every chip.
  - Most likely cause: the "load more" request used the *last* continuation command found anywhere in YouTube's page,
    and the page also holds such commands in its filter chips and menus. When one of those stood after the list, the
    request asked for something that is not the next page of videos, got no videos, and the feed was marked as ended.
    `SubsRepository.collectVideos` now takes the token of the list's own `continuationItemRenderer` (any other
    continuation command is only a fallback, `Stats.loose`).
  - A page with nothing new no longer ends the feed at once: the next page is tried (up to 3 empty pages in a row).
  - When the end of the list is already in view (a short list, or a page that finished loading while you were at the
    bottom) no scroll event comes, so the list stuck there. `SubsScreen.checkEnd` now asks for more after each layout
    (All content and Videos).
  - The feed read back from the phone's copy had lost its way to ask for older videos, so for the first minutes after
    the app started the list ended at the saved videos. It is now read again at once (`cursorMissing`). Only the newest
    120 videos are saved on the phone; the feed in memory may hold up to 1000 (was 300).
  - **Order**: every chip is sorted by age from YouTube's own text (`SubsAge.newestFirst`; videos of the same age keep
    YouTube's order, a scheduled stream stays beside the video before it). *Live*: streams on now, then scheduled ones,
    then past ones. *Today* stops asking for more pages once the loaded videos are older than a day.
  - Not tested against YouTube's live pages (no network where it was written). With a channel chosen, the list still
    shows that channel's first page only, as before.

## 2.6.72
- Added: **filter chips, Shorts and a real blue dot on the Subscriptions screen**, like the YouTube app.
  - **Chips** under the strip of channels: All content, Today, Videos, Shorts, Live. They scroll sideways and the chosen one
    is drawn inverted. *Today*: videos of the last 24 hours (a live stream that is on counts). *Videos*: normal videos
    (no Shorts, no streams). *Shorts*: only Shorts, in two columns. *Live*: streams on now and scheduled ones.
    A chip that matches only a few videos of the feed asks for the next pages by itself (up to three).
  - **Shorts** are no longer left out of the feed: *All content* shows a row of Shorts (title with the red Shorts mark,
    tall rounded cards with title and views, scrolling sideways) after the third video and then after every sixth; a
    tap opens the Short in the Shorts player. Live videos have a red "LIVE" badge, scheduled ones "Upcoming", and a
    video the account has partly watched has YouTube's red line under its picture.
  - **With a channel chosen** the chips work on that channel: its Shorts and its live streams are read from the
    channel's `/shorts` and `/streams` pages when those chips are tapped (`SubsRepository.fetchChannelTab`).
  - **Blue dot** (`SubsController.newIds`): a channel gets it while it has a video of the last 7 days that was not seen.
    Seen means: the account watched part of it (YouTube's own watch state, read from the feed), it was opened from this
    screen, or the channel was tapped in the strip. Shorts and streams that have not started do not count. Before, the
    first run counted everything as seen, so the dots showed only after a new upload and never at the start.
    The feed is refreshed when the tab is opened and the last read is older than 5 minutes (was 15).
  - New: `SubsAge` (age in hours from YouTube's own text: Arabic, English, French, Spanish, Portuguese, German; other
    languages give "unknown": not in *Today*), `SubsFilter`, and `SubsVideo` got `short`, `live` and `watched`
    (kept in `subscriptions.json`, older files still load). Opened videos are kept in `subs_state` (`seen_ids`, last 1500).
  - Not tested against YouTube's live pages (written and checked against sample data of every shape it uses):
    if the Shorts rows or the dots do not appear, the screen's small text under "No recent videos" shows what was found.

## 2.6.71
- Added: **the Subscriptions tab has its own screen**, like the YouTube app. A strip of channel pictures sits on top
  (the ones with a new video first, with a blue dot) and the videos are big cards below, with the duration, views and
  age written by YouTube in the phone's language. Tapping a channel shows only its videos right there and the strip
  stays where it is (tap it again, or "All", to see every channel); "Go to channel" opens the channel's page. Pull down
  to refresh; the list loads more as it is scrolled. Before, tapping a channel in YouTube's mobile page left the feed
  and opened the channel page.
  - New package `subs`: `SubsController` shows `SubsScreen` (built in code, a RecyclerView strip and list) over the
    browsing WebView while it is on `/feed/subscriptions`; the WebView keeps running underneath, so YouTube's header and
    bottom tabs stay (the screen covers only the space between them, measured from the page).
  - Data, with no API key: the list of channels, the feed and a channel's videos are read from YouTube's own pages
    (`/feed/channels`, `/feed/subscriptions`, `/channel/<id>/videos`) fetched with the cookies the WebView already holds,
    so they are the signed-in account's own (`SubsRepository`: `ytInitialData`, both the `videoRenderer` and the newer
    `lockupViewModel` shapes, Shorts left out; more pages through continuation requests). A channel's RSS feed is the
    way out when a page gives nothing. Everything is kept on the phone (`subscriptions.json`), so the screen opens at
    once and refreshes in the background. Pictures: `SubsImages` (small cache, no image library).
  - If the list of channels cannot be read (signed out, or YouTube changed its page) the screen is not shown and
    YouTube's own page appears as before, with a short message once per run. If there are channels but no videos, the
    screen says why in small text under "No recent videos". Menu: **Subscriptions screen** switches it off.
  - New dependency: `androidx.swiperefreshlayout` (pull to refresh).

## 2.6.69
- Fixed: two problems in the **picture-in-picture window's buttons**.
  - On a right-to-left phone (Arabic) the previous and next buttons looked swapped: the system draws the window and puts
    the first button on the right, so "previous" appeared on the right with an arrow pointing left. `pipActions()` now
    reverses the order when the phone's layout direction is right-to-left (`Resources.getSystem()`, because the window
    follows the phone's language, not the app's).
  - The play / pause button kept showing the pause icon after it was pressed. The two states differed only by their icon
    (same label, same request code), and the system window did not redraw such a button. Each state now has its own label
    (`pipPause` / `pipPlay`) and request code (`PIP_REQUEST_PAUSE` / `PIP_REQUEST_PLAY`). Also, a poll right after the
    press could still report the old state and flip the icon back, so for `PIP_TOGGLE_GRACE_MS` after a press the polls
    no longer overwrite the state (`pipToggledAt` in `onVideoState`).

## 2.6.68
- Added: **previous and next buttons in the picture-in-picture window**. The window now has previous, play / pause and
  next (before it had play / pause only), the same buttons as the playback notification and with the same behaviour:
  inside a playlist or mix they move to the neighbouring item; otherwise next uses YouTube's own next button or the first
  suggestion, and previous restarts a video that played for more than 5 seconds, else goes to the video before it.
  - `MainActivity`: the window's actions are built by `pipActions()` (previous, play / pause, next) and handled by the
    existing `pipReceiver`, which calls `PlayerController.previous()` / `next()`. On a phone that allows fewer than three
    window buttons (`maxNumPictureInPictureActions`) only play / pause is kept.

## 2.6.67
- Added: **picture in picture** (Android 8+). Press Home or swipe up while a video plays and it keeps playing in the
  system's small floating window instead of leaving only its sound. The window shows the video like the mini player:
  just the picture, in its real shape, with a play / pause button; tapping the window brings the app back (the player
  returns to full screen or to the floating box, as it was) and closing the window pauses the video.
  - New menu switch **Picture in picture** (Playback group, on by default; only shown when the phone supports it).
    Background play still works as before when it is off or the window is closed.
  - The window is the same player as the mini player: the page script hides everything but the video (`miniWanted`) and
    `PlayerHost.enterPip` keeps the WebView laid out at the screen's size and only scales its drawing into the window
    (`applyPip`), so the page does not reflow while the window shrinks and the video does not stutter.
  - No right-to-left problems: the player is laid out with `Gravity.LEFT` (never START, which turned the video black in
    Arabic), and the container is forced to `LAYOUT_DIRECTION_LTR` while the window is shown, whatever the phone's language.
  - The window takes the video's shape (`PlayerController.State` now reports the picture's and the player box's size), within
    the 1:2.39 to 2.39:1 range Android allows, and the shrinking animation starts from where the video was
    (`PlayerHost.pipSourceRect`). From Android 12 Home / the gesture bar enters the window by itself.
  - It is only entered while a video is playing and the player is on screen (not tucked away at the edge, not docked, not
    on Shorts). The app's own screens, permission dialogs and the share sheet do not start it (`ownLaunchUntil`).
  - New setting `Prefs.pipAuto`; new strings `pictureInPicture*` (English, Arabic) and icon `ic_menu_pip`; the activity is
    declared `android:supportsPictureInPicture="true"`.

## 2.6.66
- Fixed: the **Refresh button on the "page could not load" notice kept disappearing**. Two things took it away:
  - The "no internet connection" notice was set to vanish after a few seconds (`LENGTH_LONG`), unlike the other
    load errors. It now stays, like them, until Refresh is tapped or another page starts loading.
  - A drag on the error page counted as scrolling and slid the bottom bar away, and the notice is anchored to that bar,
    so it went with it. While the notice is showing, the bar now stays in place (`onPageScrolled`).

## 2.6.65
- Added: **swipe down to minimize**, like the YouTube app. While a video is full screen, put a finger on the video and
  drag down: the player follows the finger and slides down over the browsing page; let go past about 72 dp, or flick
  down, and the video shrinks into the mini player's corner; let go earlier and it slides back up. It does the same as
  the minimize arrow and the back key. Before, the video could only be minimized with those two.
  - Only a swipe that starts **on the video** counts (and not on the seek bar along its bottom edge), so scrolling the
    description or the comments, seeking, and taps all work as before. The page is asked where its video is on every
    touch (`VIDEO_BOX_SCRIPT`, which changes nothing in the page); a touch that starts elsewhere, goes up or sideways, or
    uses two fingers is left to the page. It is off while the phone is held sideways, where the page is mostly the video.
  - `PlayerHost.onPlayerTouch` sees the player's touches before its WebView. When the drag takes over, the WebView is sent
    a cancel (so it neither scrolls nor takes the end of the touch for a tap), `container.translationY` follows the finger
    (taken from the screen's coordinates, because the player moves with the finger) and a `VelocityTracker` fed the same
    way tells a flick from a slow drag.
  - The shrink reuses the double tap's resize animation (`animateResize`), starting from the video's size and place on
    screen. `minimize()` now delegates to a private `enterMini(box)`, which can be given the video's box so a video that
    is not 16:9 shrinks into its real shape instead of a 16:9 one that jumps when the first measure arrives.

## 2.6.64
- Added: when a YouTube link is shared to Ward Tube, the share window now starts with a **Play in Ward Tube** card
  (**Open in Ward Tube** for a playlist or channel): a soft rounded card in the style of the three-dots menu, with a
  tinted icon, a line saying what it does and an arrow. Tapping it opens the video in the app's own player, or the list /
  channel as a page, instead of downloading it. Below it an "or download" line leads into the usual download choices, with
  the Cancel / Download buttons unchanged. The window's title is then "What would you like to do?". Before, the share
  window could only download.
  - `ShareReceiverActivity` hands the link to `MainActivity` (a normal `ACTION_VIEW`, which already plays YouTube links),
    so the app comes to the front if it is running and starts if it is not.
  - A link shared with "start at" (`t=`) plays from that time: `ShareLinks.Link` has a new `playUrl` that keeps `t`,
    while `url` (the download address) still drops it.
  - `Downloader.chooseAndStart` has a new optional `onOpenInApp`; only the share screen sets it, so the other download
    windows (browser screen, mini player) are unchanged. Other sites get no such button: they have no player in the app.

## 2.6.63
- Fixed (security): `UrlUtils`, which decides whether an address is YouTube's and whether a plain `http://` address is
  a local one, was too loose. A link to another site could be taken for a YouTube link and so get YouTube's treatment
  (stay inside the app, skip the referer that other sites' embedded players get, lose its `t` parameter on download),
  and a crafted `http://` address could be read as a local one, so the unencrypted-download warning was skipped.
  `UrlUtils` now:
  - accepts only YouTube's own domains (`youtube.com`, `youtube.co.uk`, `youtube.com.tr`, `youtu.be` and their
    subdomains) and Google's sign-in hosts matched exactly. Before, any host with a `youtube` label (for example
    `youtube.xyz`) or starting with `accounts.google.` (for example `accounts.google.evil.com`) was accepted;
  - checks the host in `isWatchPage` / `isShortsPage` / `videoId` too, and matches the path exactly (`/watch`,
    `/watch/...`) instead of by prefix, so `/watch-next` and other sites' `/watch` pages no longer count;
  - never trusts an address with a backslash or a control character (Android's `Uri` and the WebView read
    `https://evil.com\@youtube.com/` differently: `Uri` says youtube.com, the WebView goes to evil.com), and
    `isYouTubeUrl` also rejects an address that carries a user name or password;
  - ignores the trailing dot of a fully qualified host name (`youtube.com.`);
  - for plain `http://` addresses, shows the warning (and refuses the automatic upgrade to https) whenever the address
    cannot be read with certainty, instead of treating it as a local one.

## 2.6.62
- Fixed: when a Short was opened from the feed while the bottom bar was hidden (the bar slides away as you scroll down
  the feed), the bar stayed hidden and only came back after you swiped to the next Short. Cause: the bar was only
  brought back in two places: `loadBrowse()` (a full page load) and `onPageScrolled()` (a swipe on a Shorts page).
  A tap on a Short in the feed does not load a page, YouTube just changes the address, so neither ran until the first
  swipe. `onPageChanged()` now brings the bar back as soon as the page becomes a Shorts page.

## 2.6.61
- Fixed: the Library listed the sort folders (`فيديوهات`, `أصوات`, `كلمات`, `ملفات`) as if they were files: three or four
  extra rows with a document icon and no size or date, which also counted in the file total and could have been picked
  for deletion. Cause: `DownloadStore.query` reads MediaStore's general Files collection (added so that `.srt` / `.lrc`
  files show up), and that collection lists folders as well as files; the sort folders inside Download/WardTube match
  the path query like the files inside them. `DownloadStore.isFolderRow` now drops them: a row with the type the system
  gives directories, one with the name of one of the app's own sort folders, one whose path is a directory on disk
  (looked at only for rows without a real type), or one with no type and no extension. Real files, including `.srt` /
  `.lrc`, always have an extension, so none is dropped by mistake.
- This is unrelated to the mini player fix of 2.6.60: the sort folders have been in the list since they were introduced.

## 2.6.60
- Fixed: the mini player showed the wrong part of the page (the page's header, the list of a mix, the suggestions or the
  "Next video" bar instead of the video) when you tapped a video and went back before it had loaded. It stayed wrong
  until you opened the player full screen and minimized it again (which measured everything afresh). Causes:
  - Whether the player is a small box was only ever *told* to the page (`setMini(true)`). Every page the player loads
    starts a new script with `mini` back at false, so the message sent while the video page was still loading (you left
    before it opened) was lost: the page stayed unhidden and unpinned, and the box, which showed the top 16:9 of the
    page until the first measure, showed the header and the page's content. The next message came only with the 2-second
    measure, and measuring a page that is still laying itself out could give a wrong box that was then kept.
  - The box showed a part of the page picked by a measurement. Anything that moved the player afterwards (the page's
    header appearing, a list panel opening, YouTube scrolling to the current item of a mix) left the box on the wrong part.
  Fixed by:
  - The page now *asks* the app (`WardTubeNative.isMini()`, a new method of `NativeBridge`, backed by the volatile
    `PlayerHost.miniWanted`) every 0.3 seconds and as soon as its script starts, instead of waiting to be told. The flag
    is set before the page is told, so the answer is never behind the message. `PlayerHost` also tells the page right
    away in `minimize()` and whenever the page changes (`remeasureSoon()`), as before but faster.
  - While the player is a box, the page's background is black and everything except the player is hidden even before the
    player exists, so a page that is still loading shows a black box, not its header (it used to hide things only once
    the player element was there).
  - While the player is a box, the player's container (`#player-container-id`) is pinned to the top-left corner of the
    window (`position: fixed`). The video is then always in the same place, wherever YouTube scrolls the page or moves its
    header, and it is exactly where the box looks before the first measure. Full screen takes the pin off at once.
  - The page is only ever marked as a box in the player's own WebView (`route == 'player'`); the page being browsed is
    never touched.
- Checked in headless Chromium with a fake watch page (fixed header, player that appears 1.2 s after the page, list
  panel, "next video" bar, scrolling): with 2.6.59 the box region showed 24% foreign content (the header and the page)
  until the first 2-second measure; with 2.6.60 it shows only the player at every moment, after scrolling too, and the
  page is back to normal when the player is full screen again. Not checked on the real YouTube page (no network here).

## 2.6.59
- Fixed: the mini player still showed the wrong thing in a mix or playlist: the list of the mix (the shuffle and repeat
  buttons and the current item) or the suggestions instead of the video. Cause: after a video changes, YouTube brings the
  current item of the list into view and scrolls the page; it is not always the window that scrolls (it can be a
  container around the player), so putting only the window back at the top did nothing and the player was left above the
  box. The page script now, while the mini player shows (`window.__wardtube.setMini(true)`):
  - puts the window and every container around the player back at the top, at once and again every 0.3 seconds;
  - hides everything on the page except the player (`html[data-wt-mini]`, `visibility: hidden`: nothing moves), so the
    list, the suggestions, the header and the "Next video" bar can never show in the box, wherever they end up. Nothing
    is hidden when the page has no player, and full screen shows everything again at once (`setMini(false)`).
- Checked in headless Chromium with a fake watch page (list panel under the player, a "next video" bar, scrolling in the
  window and in an inner container): with 2.6.58 the inner container stayed scrolled and the list showed; with 2.6.59 the
  box shows only the player in both cases.

## 2.6.58
- Fixed: the next video of a list (autoplay) started muted. YouTube moves on to the next video without loading a new page
  and starts it muted again ("tap to unmute"), but the page script turned the sound on only once per page load, so it
  never did it for the second video on. It now starts over for every video: when the player loads a video
  (`loadstart` / `emptied`) or the address names another one, the sound is turned on again right after it starts.
  A video counts as "with sound" only after 2 seconds of playing (it was 0.5), because YouTube can mute it again right
  after the start. A mute you set yourself later in the video still stays.
- Fixed: the mini player sometimes showed the wrong part of the page (the picture of the suggested next video and the
  "Next video" bar, not the video), most often after a video ended and YouTube opened the next one. Causes found and
  fixed:
  - The video's box was measured once, 500 ms after the address changed, and never again. If YouTube had not finished
    laying out the page by then, or moved things afterwards, the box stayed wrong. `PlayerHost` now looks at it again
    every 2 seconds while the mini player shows and changes the box only when it really moved.
  - The measuring script took the first element it found, wherever it was. It now takes the first box, in the same order
    as before (`#player-container-id`, `#movie_player`, `.html5-video-player`, `video`), that is wide enough and sits
    near the top of the page, and goes on to the next one otherwise (a stale copy of the player or a suggestion's
    picture far down the page is no longer taken for the video). `PlayerHost` also refuses a box lower than 35% of the
    screen's height.
  - YouTube can scroll the page by itself when it opens the next video of a list. While the mini player shows, the page
    script (`window.__wardtube.setMini(true)`) now puts the page back at the top at once. Full screen leaves the page
    alone (`setMini(false)`).
- Checked in headless Chromium: with the old script the second and later videos stayed muted and a far-away box was
  taken for the player; with the new one both work.

## 2.6.57
- Changed: the developer contact in the About screen is now the WhatsApp username `@Mohamed_ward505` instead of a phone
  number, which no longer works. The row opens `https://wa.me/Mohamed_ward505` (`AboutSheet.openWhatsapp` drops the
  leading "@" and no longer keeps only digits). The string is now `aboutWhatsappUsername` (it replaces
  `aboutWhatsappNumber`), and `docs/PUBLISHING.md` points to it.

## 2.6.56
- Added: a running download can now be paused and resumed from the Library. The card of a download that is moving has a
  pause button next to Cancel; while it is paused the same button resumes it, and the card shows "Paused" with the
  percentage it had reached. The download notification got the same Pause / Resume button.
  - A video / audio (yt-dlp): pausing stops the yt-dlp process but keeps its partial files. The job now has its own
    temporary folder (named by the job, not by the attempt), so on Resume yt-dlp finds its `.part` files and continues
    from the same byte. The progress tracker also counts a stream that is "already downloaded" and starts the bar at
    the old percentage, so a resumed video does not jump back.
  - A plain file (zip, pdf, apk... from the browser): `FileDownloader` keeps the half-written file open and how far it
    got (`pause()` / `Paused`), and Resume continues with HTTP Range from the last byte. If the server cannot continue
    (it answers 200 instead of 206), the file is started again from its first byte instead of failing.
  - A paused job does not hold the queue back: the next waiting job starts at once. Resume puts it at the end of the
    line (it is shown as waiting with its old percentage) and it continues when its turn comes.
  - Only a download that is moving can be paused (not one that is waiting, preparing or merging). Cancel works on a
    paused download too and throws its partial file away.
  - The download service now stays alive (in the foreground) while a download is paused, because the half-downloaded
    file belongs to that process. If the system stops it anyway, the paused download shows as an interrupted one with
    Try again, like any other cut-off download. Leftover temporary folders from a stopped service are deleted when
    the service starts and stops.
  - New: `JobStatus.PAUSED`, `DownloadService.pause()` / `resume()`, `FileDownloader.pause()` / `discard()`,
    `ic_pause_24dp`, and the strings `jobPause`, `jobResume`, `jobPaused`, `jobPausedPercent`, `jobPauseAction`,
    `jobResumeAction` (English and Arabic).

## 2.6.55
- Fixed: the browser's download button was only sometimes found a video / song that was playing in an iframe or in a
  web music player. Causes found and fixed in `MediaSniffer`:
  - The page's script never looked inside iframes, so a `<video>` / `<audio>` that lives in one (a music widget, a
    site's own player) was only seen when its address happened to end in `.mp3` / `.mp4`. The script now also runs in
    every frame (`FRAME_SCRIPT`, a document-start script like `NET_SCRIPT`) and reports what plays there: the file's
    address, or the frame's own address when the sound / picture is built in memory (blob). Only what is playing, and
    not a short clip, is reported from a frame, which keeps ad frames and interface sounds out.
  - A player in an iframe that was hidden or not laid out when the page loaded (tabs, "choose a server", lazy players)
    was measured once, rejected for its size and never looked at again. The media and the frames are now looked at
    again every few seconds (a cheap look, only while the page is visible), and a music widget (a bar, not a box) whose
    address says player + music is accepted.
  - A song / video built in memory on the page itself (a blob) was reported for video only; a long playing audio is
    now reported too.
  - The network watcher gave up after 3 files (and 20 finds) per page: in a single-page music site the 4th song was
    never found. The limits are raised, and an answer that hides its size (common for another site's audio) is no
    longer dropped.
  - When the address of a single-page site changed, the list was emptied and what the player had already loaded was
    never asked for again. If something is still playing, the two latest finds are given back.
  - The list of 40 found items refused new ones when full; it now drops the oldest, so what plays now always gets in.
- Checked in headless Chromium with a mock bridge: an extensionless song inside a cross-site iframe, a player shown
  after load, a song played from a blob, and five songs fetched one after the other are all found now, and none was
  before; a normal page (direct audio, a link to a zip, an ad frame) reports exactly what it did.

## 2.6.54
- Fixed: the lyrics / subtitles file is now saved with exactly the same name as the video / audio it belongs to, only the
  extension differs ("Song.mp3" + "Song.srt" / "Song.lrc"). yt-dlp names its files "<title>.<lang>.<format>", so the
  lyrics used to be saved as "Song.ar.srt", and players, which pair the two files by name, did not find them.
  `DownloadService.process` now saves the media first, reads its real name back (`DownloadStore.displayNameOf`: the
  system may have added " (1)" to a name that was taken, or the page's name was used) and names the lyrics after it,
  so the pair stays matched in those cases too.
- When several languages are ticked, only one file can have the plain name: the first language picked gets it, the
  others keep their language code ("Song.en.srt") so that nothing is overwritten. A "subtitles only" download uses the
  video's title the same way ("<title>.srt").

## 2.6.53
- Fixed: the video could be played out of sight. With the mini player dragged to the edge of the screen (docked: the
  box slides away, the video pauses, only the arrow tab stays), pressing Play in the notification, on a headset or on a
  Bluetooth device, or Next / Previous, started the video while the box stayed hidden. Now the box comes back at the
  height it left from as soon as the video plays again (`PlayerHost.onPlaybackState` undocks when it is told "playing" while
  docked; the state poll in `MainActivity.keepAwakePoll` already feeds it every second, and straight away when the app
  is opened again). `dockedAt` / `DOCK_SETTLE_MS` ignore a "playing" report that was already on its way when the box was
  docked, so the box does not bounce back at once. Tapping the arrow tab works as before.

## 2.6.52
- A download that failed or was cut off no longer disappears from the Library when the screen is closed and opened
  again (or rotated, or recreated by the system). Failures used to live only in the Library's memory
  (`LibraryActivity.failedJobs`), and the download service, which runs in its own process and stops itself when the
  queue is empty, forgot them too. They are now kept on disk until the person taps Try again or Dismiss.
  New `download/FailedDownloads.kt`: one small JSON file per job in the app's private, not-backed-up storage
  (`noBackupFilesDir/downloads_state/`). The service writes (`fail()` stores the failure before the Library is told);
  the Library only reads and deletes, so the two processes never rewrite the same file. Keeps at most 100 failures.
- A download that was cut off without a word (the system killed the download process, the Android 15 six-hour limit
  ended it, the app was swiped away) is now shown as a failed one with Try again, instead of vanishing. Every job leaves
  an "active" record while it is queued or running and removes it when it ends; a record that outlives its service is
  turned into a failure by the next service start or by the Library when no service answers.
- Downloading the same video again clears its old failure; a download the person cancels leaves nothing behind, as before.
- New string `downloadInterrupted` (English and Arabic).

## 2.6.51
- The first slot of the bottom bar is no longer an empty gap. It used to be a transparent, disabled minimize arrow
  whenever no video was full screen, so the bar looked as if the minimize button had vanished (with the mini player
  open, for example). It now shows the minimize arrow while a video is full screen and the voice search microphone
  the rest of the time. Voice search is therefore one tap away in the bar (it is still in the three-dots sheet too).
  The slot is always in the bar and always enabled, so the other five buttons still never move
  (`MenuController.setVideoFullScreen` replaces `setMinimizeVisible`; the click handler picks minimize or voice
  search; `menu_main.xml` comment updated; the unused `HIDDEN_ALPHA` is gone).

## 2.6.50
- The "What do you want to download?" dialog is tidied up (`MenuController.askWhatToDownload`, `fillChoiceRow`,
  `dialog_download_choice.xml`). It used to be a plain list with the video title run into the same line as its label.
  It is now one soft rounded card in the style of the three-dots menu, with two rows: the video that is playing (red
  badge, its title on the line under it, up to two lines) and the open playlist or channel (blue badge, "Pick the
  videos you want from the list" under it). Each row ends with an arrow, and a tap on a row starts that download.
  When it appears and how it closes are unchanged: only with the mini player running on a playlist / channel page,
  and only through its Cancel button. New: `ic_list_24dp`, the string `downloadChoiceListHint` (English and Arabic).

## 2.6.49
- The bottom bar no longer jumps. The download and minimize buttons used to be added to and removed from the bar, and
  every time that happened `ActionMenuView` spread the remaining buttons over the width again, so home, browser, library
  and the three-dots button moved sideways (a wrong tap when the page changed). All six slots now stay in the bar:
  the download button is dimmed while there is nothing to download (tapping it still explains that a video has to be
  chosen), and the minimize button is transparent and disabled unless a video is full screen
  (`MenuController.setUp`, `setMinimizeVisible`, `setDownloadVisible`; `menu_main.xml` no longer hides them).
- Download button with the mini player running and a playlist / channel page open: it used to download the list without
  asking. It now asks "What do you want to download?" with the playing video (and its title) or the open list. With no
  video playing, or on any other page, nothing changes (`MenuController.withDownloadUrl`, `askWhatToDownload`). The
  dialog closes only through its Cancel button, like the other download dialogs.

## 2.6.45
- The three-dots menu is redesigned (`MenuController.showMenuSheet`); nothing was added or removed, every entry does what
  it did and the order of the groups is the same. What changed is how it looks and reads:
  - The sheet has big rounded top corners and starts with the app badge (the blue-to-cyan of the launcher icon), the app
    name and its version.
  - Refresh, share and voice search are three soft cards with a round colored badge each.
  - Every group (Downloads, Playback, Display, Browsing, Ads, and About at the end) is one rounded card with a colored
    title; the color is the group's (blue, purple, amber, teal, red, gray) and tints the icon of each of its rows, so a
    row is found by color and icon before it is read. Rows in a card are separated by a thin line that starts under
    the text, not under the icon.
  - A row that opens a choice shows its current value at the end, in the group's color (the chosen theme, the sleep time
    left), before the arrow. The switches keep their second line (what the state does, how many ads were blocked).
  - The parts of the sheet slide in one after the other when it opens (a fraction of a second), and a switch row gives a
    light tick when it is flipped.
  - Light and dark mode both have their own colors (`menuAccent*`, `menuCard`, `menuHandle` in `values/` and
    `values-night/`). Ten new icons (`ic_menu_*`) were added; no new string: the texts are the ones that were there.

## 2.6.44
- The three-dots menu is tidied up. It was one long flat list of twelve plain rows (and on most phones refresh, share and
  voice search sat in it too, because the bottom bar has no room for them). It is now a sheet (`MenuController.showMenuSheet`)
  with a row of quick actions on top (refresh, share, voice search) and the settings in titled groups:
  - Downloads: download, update download engine, update the engine automatically.
  - Playback: play in background, sleep timer.
  - Display: theme, hide bar while scrolling.
  - Browsing: set as home, accept cookies.
  - Ads: block ads, update ad filters.
  - and "About" at the end.
- Each setting shows its state where it helps: the switches show on / off, the theme row shows the chosen theme, the sleep
  timer row shows the time left (refreshed every second while the sheet is open), the ad blocker row shows how many requests
  were blocked, and the "update the engine automatically" row explains what its current state does (it used to be a message
  at the bottom that the sheet would now cover). Tapping a switch row keeps the sheet open; any other row closes it first.
- The bottom bar keeps its four buttons (home, browser, library, three dots). `menu_main.xml` now only holds those; the
  rest of the menu is built in code, so `MenuController` no longer needs the overflow menu.
- Nothing was removed: every old menu entry is in the sheet and does what it did.

## 2.6.43
- The download engine (yt-dlp) is now updated by hand only. Until now a failed download, or a list that could not be read,
  updated it by itself once and tried again. Now nothing updates it unless the person taps "Update download engine" in the
  menu.
- New menu option "Update the engine automatically" (`Prefs.autoUpdateEngine`, off by default). When it is on, the old
  behaviour comes back: a failed download or an unreadable list updates the engine once and tries again. The download
  service runs in its own process and cannot read the app's settings as they change, so the choice travels with each start
  request (`DownloadService.EXTRA_AUTO_UPDATE`, filled in by `start` / `startMany`) instead of being read there.
- The full message of a failed video / stream download (tap its card in the Library) has an "Update engine" button, so the
  update is one tap away; "Try again" on the card then uses the new engine. Plain files do not get it (yt-dlp is not used).
- Browser screen: the item of the "Found on this page" list that is best to download is marked "Recommended" (a badge and an
  outlined row). `BestOption` chooses it from what each kind of item is, because no server is asked about sizes: the page's
  own video on a site yt-dlp handles with its own extractor (YouTube, TikTok, Facebook... see `ShareLinks.isMediaSite`),
  then a video file, then a stream (m3u8 / mpd), then the page's video on any other site, then a player frame, then audio.
  Plain files (zip, pdf...) are never marked. Between items of the same kind the one with the highest resolution in its
  address or name (`1080p`, `_720.mp4`) wins, and then the one found first.

## 2.6.42
- A plain `http://` link now always warns the person, wherever it is used:
  - Opening a site in the browser screen (a typed or pasted address, a quick site, a history entry, and also a link or a
    redirect inside a page that leads to an http address): a window says the site is not secure and offers "Open
    anyway", "Try https" (the same address over https) or Cancel, and nothing is loaded before the answer. A site the
    person agreed to open is not asked about again while the screen lives (`BrowserActivity.insecureHosts`), and a
    page that redirects several times shows one window, not a stack of them.
  - Downloading a video or a stream (yt-dlp) from an http link, from any entry (the browser's floating button, a pasted
    link, "Share to Ward Tube"): the window "This link is not secure" comes first, with "Download anyway" / Cancel
    (`Downloader.chooseAndStart`). Nothing is requested from the server for it (the rule of 2.6.34 holds).
  - Files and audio found in the browser keep the behaviour of 2.6.39 (the https address is tried first, the warning
    appears when the site has none). When the https address answers, a short message that goes away by itself says the
    http link was downloaded over https instead (`browserFileStartedHttps`); no window is shown.
  - Addresses on the phone or on a home network (localhost, 127.x, 10.x, 192.168.x, 172.16-31.x, *.local) are left
    alone: http is normal there (`UrlUtils.isInsecureWeb`).

## 2.6.41
- Streams (HLS / DASH) download faster: yt-dlp is now told to fetch 4 fragments at the same time
  (`--concurrent-fragments`, `DownloadService.CONCURRENT_FRAGMENTS`). A video that is one file is not affected.
- Browser screen: a stream is now found on far more sites. Until now the list only knew a playlist by `.m3u8` / `.mpd` at
  the end of its address. A new script (`MediaSniffer.NET_SCRIPT`), registered as a document-start script for every
  frame of every page, watches what the page's own code downloads (`fetch` and `XMLHttpRequest`) and reads what the
  server SAYS about each answer: a playlist type (`mpegurl`, `dash+xml`), or a generic type (`octet-stream`,
  `text/plain`, `xml`, none) whose first bytes are `#EXTM3U` or `<MPD`. It reports to the new `Bridge.reportNet`.
  Nothing extra is requested from any server (the signed-address rule of 2.6.34 still holds): the first chunk of a copy
  of an answer is read, and the page always gets its own answer whole. The same page-side watcher offers a big video /
  audio file (1 MiB or more) the page fetches by itself, but only when no playlist was seen on the page, so the pieces
  of a stream are never listed.
- Browser screen: a playlist named inside an address's query (`/play?src=https%3A%2F%2Fcdn.site%2Fmaster.m3u8`) is found
  by its address too (`MediaSniffer.embeddedStream`).
- Library: a file's menu has "Rename". Only the name is edited, the extension stays, so the file keeps its type and its
  folder. Android 10+ renames the MediaStore row (the system adds " (1)" if the name is taken); Android 9 and older
  renames the real file and tells MediaStore. A file the app does not own (an earlier install) asks the system's
  approval first (`DownloadStore.rename`, `RenameResult`).

## 2.6.40
- Browser screen, main screen redesigned. The link field is now one filled pill (the globe at its start, the x when
  there is text, and the search engine button inside it at its end; light green outline while it has the focus).
- Under the field: "Quick sites" (rows of four tiles: YouTube, TikTok, Facebook, Instagram, SoundCloud, Dailymotion and
  Vimeo, then an Add tile; sites the person adds are kept in `Prefs.quickSites` and removed with a long press), "Recently
  opened" (the last three pages of the browsing history, with "See all" opening the history screen) and a hint card
  that shows the floating download button and advises playing the video for 2 to 5 seconds before tapping it.
  All of it is in the new `BrowserHome`; it is hidden while a page is open. The Paste button stays where it was.

## 2.6.39
- Files with a plain `http` address: the app now tries the same address over `https` first (a short check before the
  download is queued). If the site answers there, the file is downloaded over `https`. If the site has no `https` version,
  a warning says the link is not secure and the person chooses "Download anyway" or Cancel; nothing is downloaded over
  `http` without that choice, and it is remembered for the job's Retry (`wt_insecure` in `RequestHeaders`).
- The manifest now allows cleartext traffic (needed for the approved `http` downloads); `FileDownloader` decides per link.

## 2.6.38
- Files found in the browser (zip, pdf, apk, audio links...) are now downloaded by the app itself instead of the phone's
  download manager. `FileDownloader` runs as a job of the same `DownloadService`, so these files share the queue, the
  waiting list, the notification (with its Cancel button), the progress card and the Retry of the Library with the videos.
  Their cards show the file's icon, its extension, the size done and the size in all, the speed and the time left.
  Nothing is handed to the system any more: `DownloadManager` is no longer used anywhere in the app.
- The file is sent what the page sent (the open page as referer, the WebView's user agent and the cookies of the file's
  host, carried by `RequestHeaders` as for the videos). A redirect to another host (or from https to http) does not
  receive the cookie.
- The file is written straight into Download/WardTube (`DownloadStore.begin` / `commit` / `discard`), in the sub-folder of
  its kind (videos, audio, lyrics, or `ملفات` for the rest), with no temporary copy in the app's storage: big files need no
  room twice. On Android 10+ it stays hidden (pending) until complete; on Android 9 and older it is written under a
  hidden name and renamed at the end. A cancelled or failed download leaves nothing behind.
- A dropped connection continues from the last byte received (HTTP Range) up to four times in a row before the job fails;
  when the server cannot continue, the job fails with a clear message instead of saving a broken file. A web page sent in
  place of the file (an expired or protected link) is refused for the same reason.
- Android refuses plain `http` connections made by the app itself (the system's download manager had no such limit): an
  `http` address is tried over `https`, and when the site has none the job fails saying so.
- The Library's "Cancel" works at once on these downloads, also while the server is slow to answer. A new file kind
  (`MediaKind.FILE`) is not offered in the "Download as" window and shows under "All" in the Library's filter.

## 2.6.37
- Browser screen: search engines. A button at the end of the link field shows the engine in use (its colored badge) and
  opens a bottom sheet to choose another one: Google, Bing, DuckDuckGo, Brave Search, Yahoo, Yandex, Ecosia, Startpage
  and Qwant (web search), YouTube and Dailymotion (video sites). The engine in use is highlighted and ticked, the choice
  is remembered (`Prefs.searchEngine`) and a text that is not a link is searched with it (before: always Google). The
  engines are listed in `SearchEngines`; the first one is the default.
- Browser screen: the list of videos and files found on the page is now a bottom sheet (`DetectedSheet`) instead of a
  plain dialog of one-line texts. The header names the page and counts what was found; the items come in sections
  (Videos, Audio, Files) with their own counts; each row has an icon tile colored by kind, the name (up to two lines),
  a badge with the format (MP4, M3U8, PDF...), the kind with the site it comes from, and a download button. Nothing is
  requested from a server to fill it in, so there is no file size. A single found item is still downloaded at once, and
  the "play the video first" window is unchanged.
- Both sheets share one dark-green style (`BrowserSheetDialog`) that follows the browser screen in day and night mode.
- The link field's hint now reads "Search or paste a link".

## 2.6.36
- Browser screen: when the floating button is tapped and the page offers only a player's frame (the video file it loads
  has not been caught yet), the "Download as" window no longer opens. A window says that the video file has not
  appeared yet and asks the person to play the video for a few seconds and tap again. A "Try the player" button
  remains for the sites whose players yt-dlp does understand (YouTube's embed, for one), so nothing is taken away.
  Before, a player yt-dlp cannot read led through the format choice and a wait to end in "Unsupported URL".

## 2.6.35
- Browser screen: when a page has loaded a video file or a stream, the list no longer offers the "Player in the page"
  (frame) entry next to it. For a player yt-dlp has no extractor for (Anime3rb's, for one) that entry always failed
  with "Unsupported URL", while the file the player loaded worked, and the two were easy to mix up. The frame is
  still listed when it is the only thing found, since for some players (YouTube's embed, for one) it is exactly what
  yt-dlp understands. The count on the floating button follows the shorter list.

## 2.6.34
- Browser screen: a video file or a stream found on a page is now downloaded by the app's own queue (yt-dlp) instead of
  the system's download manager, so its progress, waiting list and retry show in the Library. Some sites (Anime3rb's
  player, for one) have no extractor in yt-dlp and their player page fails with "Unsupported URL", while the video file
  the player loads can be downloaded when the server is answered as it answers the page. The file is therefore sent
  what the page sent: the open page as referer, the WebView's user agent and the cookies of the file's host. They are
  carried inside the address (`RequestHeaders`, after `#__youtubedl_smuggle=`), so they also survive a retry from the
  Library, and the service turns them into `--add-header` options.
- The file is saved under the page's title (before, it kept the server's name, often "480p.mp4" for every episode).
- A file or stream the browser found is never read from its server before the person chooses (no list check): a
  signed address may answer only once.
- Audio and other files found in the browser still go to the system's download manager.
- Library: a waiting job without a title shows its address without the hidden header part.
- Library: a failed download can now show its whole error. The card still shows only the first lines, but it now says
  "tap for the full error"; the tap opens a window with the complete message of yt-dlp (warnings included), whose text
  can be selected, and a Copy button that copies it together with the title, the link and the format. Before, the
  message was cut to 200 characters when the job failed, so the end of a long address (the part that tells most) was
  lost. The job now keeps up to 6000 characters; the notification still shows a short version.

## 2.6.33
- Browser screen: a pasted link is now told apart by what it is. A direct link to a file or a stream still opens the
  file's confirmation window, and a link of YouTube, TikTok or another video site (Facebook, Instagram, X, Vimeo,
  Dailymotion, SoundCloud... see `ShareLinks.MEDIA_SITES`) still opens the "Download as" window. Any other link is an
  ordinary site and is opened in the browser (before, every site's link was handed to yt-dlp as if it were a video).
  A link with no file extension that the server answers with a download is caught by the WebView's download listener;
  when it was the first thing opened, the empty page behind the window is closed.
- Browser screen: a player that a site shows in its own iframe is now found. The floating button lights up and lists it
  as "Player in the page"; choosing it gives yt-dlp the frame's address, with the open page as referer (smuggled into
  the address, which needs no change in the download queue). Only frames that look like a player are listed: a visible
  box that allows full screen, or a video-shaped box whose address says player / embed / video. Ads, comment boxes,
  share widgets and hidden frames are left out.

## 2.6.32
- Wording only. The message shown when a link has no subtitles said "this video", even when the download was an audio
  file. It now says "this clip" and mentions lyrics, like the "Lyrics / subtitles LRC" option does: "This clip has no
  lyrics or subtitles" (Arabic: «لا توجد كلمات أو ترجمة لهذا المقطع»). Both messages of this kind changed: the one shown
  with "subtitles only" and the one shown before a video / audio download goes on without them.

## 2.6.31
- The list of videos shown for a playlist is tidier. Every video is a row of the same height: a tick box, its picture
  (16:9, with the video's length in its corner), the title (two lines at most) and the channel under it. The length no
  longer sits inside the title, which scrambled mixed Arabic / English titles; a title with any Arabic letter is now laid
  out right to left as a whole. A video that is not ticked fades.
- The pictures are small ones fetched on three background threads and kept in a cache of about 5 MB (no image library;
  https only). For YouTube they are `i.ytimg.com/vi/ID/mqdefault.jpg`, the same picture the playback notification uses.
  For other sites the address comes from the list yt-dlp reads.

## 2.6.30
- Playlists. A link to a list of videos used to be refused ("not a video link"), and a list that did get through was
  downloaded as one file. Now the "Download as" window opens for a list too, and after it the videos of the list are
  shown with a tick box each (all ticked, a button ticks / unticks them all). Every ticked video becomes a download of
  its own in the queue, with its own progress, cancel and retry. Up to 200 videos of a list are read.
  - YouTube: playlists, Mixes (the link of a Mix such as `playlist?list=RD...` is read through its video, which is
    the form YouTube and yt-dlp accept) and channels (`@name`, `/channel/`, `/c/`, `/user/`: their videos). A video
    opened from a playlist keeps its list: the window then asks "This video only" or "The whole playlist".
    The Download item of the menu also works on a playlist or channel page that is open in the app.
  - Other sites: yt-dlp is asked what is behind the link while the format window is still open (SoundCloud sets,
    Vimeo albums, Dailymotion / Bilibili lists, TikTok profiles and collections, a post with several videos...).
    A link that is one video downloads as before; if yt-dlp cannot tell within 25 seconds the link is treated as one video.
  - A list that cannot be read says so (and the engine is updated once and tried again first) instead of starting a download.
  - Subtitles with a list: the languages offered are those of the first video and apply to all of them.
  - Notifications: while a playlist downloads, the files are announced with one "Saved N files" message at the end
    instead of one notification per file.

## 2.6.29
- Build files only, no change in the app: new manual workflow `.github/workflows/update-wrapper.yml` that regenerates
  `gradle-wrapper.jar`, `gradlew` and `gradlew.bat` for Gradle 8.9 and checks the jar against Gradle's published checksum
  (the jar was from Gradle 4.10, which is what the F-Droid scanner reported as a version mismatch).

## 2.6.28
- Build files only, no change in the app: `gradle/wrapper/gradle-wrapper.properties` now has `distributionSha256Sum`
  for `gradle-8.9-bin.zip`, so the Gradle download is verified (F-Droid scanner finding).

## 2.6.27
- Browser screen: a text that is not a link is now searched with Google (google.com/search) instead of DuckDuckGo.

## 2.6.26
- Fixes the build error of 2.6.25: the link field's layout used an attribute (`endIconVisible`) that does not exist.
  The x is hidden at start by the code, which already did it, so nothing changes for the person using the app.

## 2.6.25
- Link field: the x shows whenever the field has text, also right after the Paste button (before it showed only while
  the field had the cursor). Tapping it empties the field and brings the keyboard up.

## 2.6.24
- Link field: the x now shows. It is the field's own clear button, at its end, shown while something is typed (the same
  kind of field as the Library's search). The globe moved to the start of the field and still starts the search.
- History: one visit is one entry. A search page changes its address while it loads (typed address, redirect, an extra
  parameter) and each change was written as a page of its own; now a page is written once it has settled.
- History: selecting works like the Library's. A tap ticks a page; a long press ticks every page between the last
  ticked one and the pressed one. A hint says so while selecting.

## 2.6.23
- Browser screen: previous page and next page buttons at the end of the top row while a page is open (dimmed when there
  is no page before / after the current one), next to Reload and Home.
- Browsing history: every page opened in the browser is kept in a history (newest first, one entry per address, up to 500).
  The new History button in the top row opens it. Tap a page to open it. Long press (or "Select multiple" in the menu) to
  tick several pages and delete them together, "Select all" ticks everything, and "Clear all history" empties the list.
- The link field shows an x while something is typed; a tap on it clears the field (like the Library's search).

## 2.6.22
- Browser screen: the title "Browser" moved up into the top row, next to the back and Library buttons, and the link field
  took its old place right under that row.
- Two new buttons at the end of the top row while a page is open: Reload (reloads the page) and Home (leaves the page and
  goes back to the main browser screen with an empty field). They are hidden on the main screen.

## 2.6.21
- Browser screen: the direct Download button under the Paste button is gone. The globe button in the field and Go on the
  keyboard already open the download window for a link, so it had nothing left to do.

## 2.6.20
- Browser screen: the globe button in the field (and Go on the keyboard) no longer opens the site first. A link is looked
  at for something to download: a file or a video / audio file address shows the file's confirmation window, a video
  page shows the "Download as" window, and anything else shows a "nothing found, try opening the site" message with an
  "Open site" button. Text that is not a link is still searched with DuckDuckGo.
- The Paste and Download buttons are shown on the main browser screen only; they are hidden while a site is open.
- The floating download button shown on sites can be dragged anywhere on the screen, and its place is remembered. It is
  no longer disabled while dimmed: a tap says that nothing was detected yet.
- The arrow at the top of the browser screen leaves the browser instead of going back a page; the phone's back button
  goes back through the pages.

## 2.6.19
- The ad blocker now works in the Browser screen too: every request of the page goes through the same EasyList /
  Liste AR rules and the same on/off switch as the YouTube screen. The filter lists are loaded once for the whole app.
- A floating download button appears in the Browser screen while a page is open (not on the paste screen). It is dimmed
  and disabled at first, and turns on when the page offers a video or a file: a video file or a streamed video
  (m3u8 / mpd) seen in the page's requests, a `<video>` / `<audio>` in the page, or a link to a file (zip, pdf, apk...).
  One find starts the download straight away, several are listed to choose from. Files go to the system's download
  manager, videos that live inside the page and streams go to yt-dlp.

## 2.6.18
- The Bookmarks (star) button and everything behind it are gone: the side drawer, the list and its saved data.
- A globe button takes its place in the bottom bar and opens the new Browser screen, laid out like the Seal app:
  a link field, a Paste button and a Download button. Paste a link of a video from any site and press Download to get
  the usual "Download as" window and queue, the same as when you share a link to the app.
- The globe inside the field (or Go on the keyboard) opens the address in a small browser; text that is not a link is
  searched with DuckDuckGo. With a page open, Download takes the page itself when the field is untouched. Back goes
  back through the pages, then returns to the paste screen.
- A file a page offers for download (zip, pdf, apk, a direct video link...) asks for a confirmation and is saved by the
  system's download manager into Download/WardTube (sub-folder `ملفات`; videos and audio go to their own folders), so
  it shows in the Library. Files that are not media get a document icon there.
- Pop-up windows and non-web links (intent:, market:...) are blocked inside the browser screen.

## 2.6.17
- Library: select several files at once, like in Vid2Audio. Long-press a file (or choose *Select multiple* in the menu)
  to start: tap files to tick / untick them, long-press another file to tick everything up to it, and use the bar at the
  bottom to share or delete all the ticked files together. Back ends the selection first. Deleting asks once for the
  whole group (Android 11+).
- Library: an empty search now says "No results" instead of "No matching results".

## 2.6.16
- Library: the search field is no longer always on screen. A magnifier button in the toolbar opens it (and the
  keyboard) under the title; tap the magnifier again, or Back, to close it. Closing clears the search, so the whole list
  comes back, the same way the search of the web Wardio works. The button is highlighted while the field is open, and an
  open search survives a rotation.

## 2.6.15
- Downloads are sorted into folders inside Download/WardTube: `فيديوهات` (videos), `أصوات` (audio) and `كلمات`
  (lyrics / subtitles). Files from earlier versions stay where they are and still show in the Library.
- Library: filter buttons under the search field (All / Video / Audio / Lyrics, always on one line; the download queue
  follows the chosen one), a picture for each video (a frame from it) and each audio file (the cover inside it), and a
  single menu button per file (share / delete) in place of the two buttons.

## 2.6.14
- Download by sharing now works for links from any site: Facebook, Instagram, X / Twitter, Reddit, Vimeo, Dailymotion
  and every other site the bundled yt-dlp knows. The app does not keep a list of sites; it hands the link over and the
  Library shows the download or the failure. YouTube and TikTok links are still tidied first, and their pages that are
  not a video (channels, playlists, profiles, photo posts) get a short message instead of being downloaded as a list.
- A site's own `t=` parameter is no longer cut from the address (only YouTube's start time is).

## 2.6.13
- New: download by sharing. In the YouTube app, TikTok, a browser or any other app, share a video link and pick
  *Ward Tube*: the usual "Download as" dialog opens over that app, the download starts in the background, and you are
  back where you were (the app itself is not opened). Works for YouTube videos, Shorts and youtu.be links, and for
  TikTok videos and short links (vm.tiktok.com / vt.tiktok.com). The link is found inside the shared text, so
  "Check out this video! ... https://vm.tiktok.com/..." works; other links get a short message.

## 2.6.12
- Fixed: Download did nothing on a Short (it said no video was selected), because the menu only accepted ordinary
  watch pages. It now downloads the Short on screen, the one you have swiped to. Share works on Shorts too.
- The Library names a queued Short download after the Short, not after a video held in the mini player.

## 2.6.11
- Mini player: the enlarge/shrink animation now lasts half a second.

## 2.6.10
- Mini player: the double-tap enlarge (and the shrink back) is now animated: the box grows or shrinks smoothly from
  its old size and place into the new ones, instead of jumping. A touch during the animation ends it at once.

## 2.6.9
- Mini player: drag it past the left or right edge of the screen (about a third of its width) and it slides out of
  sight, the video pauses, and a small tab with an arrow stays at the edge. Tap the arrow and the box comes back from
  that edge at the same height and the video goes on (a video that was already paused stays paused). Let go before
  reaching that point and the box slides to the nearer side as before.

## 2.6.8
- Mini player: the dark discs behind the play/pause and close buttons now fade away after 3 seconds without a touch
  (the icons stay), like the YouTube app's floating player. They come back as soon as you touch the box, and stay
  while you drag it or press a button.
- Mini player: a quick double tap on the box enlarges it to nearly the screen's width; another double tap shrinks it
  back to its normal size, on the same side of the screen. A single tap still brings the video back full screen (it
  now waits a fraction of a second to be sure it is not the first half of a double tap).

## 2.6.7
- Playing in the background with the screen off: the phone now stays awake for 10 seconds after playback stops, so a
  video of a playlist or mix that ended without YouTube moving on is followed by the next one right away instead of
  whenever the phone wakes up.

## 2.6.6
- Playlists and mixes: when YouTube's own shuffle gets stuck (the video ends and plays again, and its next button
  does the same), the app now moves on itself. The next / previous buttons of the notification open the video after /
  before the current one in the list, and a video in a list that ended without YouTube moving on is followed by the next
  one of the list after a few seconds. YouTube's own way is still used for the end of a list and for videos outside
  lists, and a video set to repeat is left alone.
- The mini player no longer shows over Shorts: it hides while a Short is open (its video waits, paused) and comes back
  when you leave the Short.

## 2.6.5
- The mini player now stays where you put it when the bar slides away or comes back (before, it dropped back to the
  bottom corner whenever the page area changed size). Turning the phone still puts it back in its corner.

## 2.6.4
- The bottom bar (Home, Bookmarks, Library, three dots) now slides away while you scroll down a page and comes back as
  soon as you scroll up, so the page gets the whole screen. It also comes back when a video opens, when a page fails to
  load, and while watching Shorts it stays in place. Turn it off in the three-dots menu: "Hide bar while scrolling".

## 2.6.3
- Fixed the mini player showing a black box instead of the video when the phone's language is right-to-left (Arabic):
  the player's page was pushed against the wrong edge of the small box.
- Videos opened in the player now start with sound. YouTube's mobile page started them muted ("tap to unmute") because
  the page was opened without a tap inside it; the sound is now turned on right after the video starts (a mute you
  choose yourself afterwards stays).

## 2.6.2
- When you let go of the mini player after dragging it, it slides to the nearer side of the screen (it keeps the
  height where you left it), like the YouTube app's floating player.

## 2.6.1
- The mini player can be dragged: hold the box and move it anywhere on the screen (it stays inside the page area, above
  the bottom bar). A tap without moving still brings the video back full screen. Turning the phone puts the box back in
  its corner.

## 2.6.0
- Mini player, like the YouTube app: a video now plays in a player of its own, on top of the page you were browsing.
  Press Back (or the new arrow-down button in the bottom bar) and the video shrinks to a small floating box in the
  corner while it keeps playing, so you can look through the home page, your subscriptions or search results and
  open the next video. The box has play/pause and close buttons; tapping it brings the video back full screen.
- A video link tapped on any page opens in the player; a link to a channel, hashtag or search tapped inside the video
  shows that page behind the mini player instead of stopping the video.
- The player is only created when you open a video and is destroyed when you close it, to spare memory on small phones.
- The Share, Download and bookmark actions and the background-play notification follow the video in the player.

## 2.5.5
- The download format window and the subtitle language window also close only with their buttons (tapping outside
  or pressing Back no longer drops the choices).

## 2.5.4
- The "looking for subtitles / lyrics" window can only be closed with its Cancel button: tapping outside it or pressing
  Back no longer cancels the download.

## 2.5.3
- Smaller APKs: only the arm64-v8a and armeabi-v7a builds are produced (the universal APK no longer carries x86 / x86_64),
  and the libraries' strings for languages the app does not use are left out. No change in behaviour.

## 2.5.2
- Fixed downloaded SRT lyrics losing the first line of each block (the first line of the song was missing, or showed for a
  fraction of a second, late). The SRT reader cut the cue that introduces that line in two and dropped its text; it now
  reads the file line by line.

## 2.5.1
- Fixed downloaded SRT / LRC subtitle files: when the phone language is Arabic the timestamps were written with Arabic-Indic
  digits (٠٠:٠٠:٤١,٧٥٠), so players could not read them. They are now always written with 0-9.

## 2.5.0
- Audio downloads now carry their tags (title, artist, album, date...) and an embedded cover picture (the video's
  thumbnail, cut to a centred square) for MP3, M4A, Opus, OGG and FLAC. A raw AAC stream and WAV cannot hold a cover.
  If embedding the cover fails, the download is repeated once without it.

## 2.4.9
- Fixed a crash when leaving a video page (or pressing Stop in the notification) right after the video started: the
  background-play service could receive the player's answer after it had been closed and try to load the cover.

## 2.4.8
- Video / audio download with "also download subtitles": when the video has no subtitles (or the list cannot be read, or no
  language is ticked) the download now goes on without them, with a message, instead of being dropped.

## 2.4.7
- The Download button no longer starts on a live stream: it says that live streams cannot be downloaded.

## 2.4.6
- Removed the "Open in browser" menu item.

## 2.4.5
- Notification progress bar: follows the real playback speed (1.25x / 1.5x no longer drift away from the video),
  is compared with what the bar shows instead of with the previous check, and live streams are recognised through
  YouTube's player, so a live stream with a rewind window no longer gets a bar. Tested in headless Chromium.

## 2.4.4
- Previous (notification, headset, Bluetooth) goes back to the video watched before this one when the video is not in
  a playlist, instead of only restarting the current video. Tested in headless Chromium on a mock watch page.

## 2.4.3
- The background-play notification (and the lock screen) now shows a progress bar you can drag to seek. It is
  shown by Android 13 and newer in the notification, and by Android 11-12 in the quick-settings player. Live
  streams have no bar.

## 2.4.2
- Next / Previous from the notification, headset or Bluetooth now also work inside playlists and mixes.

## 2.4.1
- The About screen says "All rights reserved" again.

## 2.4.0
- The background-play notification shows the video's cover and has Previous / Next buttons (also used by headsets
  and Bluetooth controls). The cover is the video thumbnail from YouTube's image server.

## 2.3.5
- The About screen now says the app is open source (GPL-3.0).
- Release signing through environment variables, GitHub Actions workflow, F-Droid/Izzy metadata.

## 2.3.4
- Sleep timer: the wake lock is renewed while a video plays (long timers with the screen off); the timer is
  cancelled when playback is stopped from the notification or stays paused for 10 minutes; "playback stopped"
  is only announced when something was actually playing.

## 2.3.3
- Sleep timer: custom time (hours and minutes), one clock for display and stop, the menu entry refreshes every
  second only while the menu is open, the timer is cancelled when the app is really closed.

## 2.3.2
- Sleep timer entry in the three-dots menu shows the remaining time.

## 2.2.0 - 2.3.1
- Sleep timer. Picture-in-picture was tried and removed (the video stopped in the small window).

## 2.1.3
- The "no internet" notice disappears by itself and sits above the bottom bar, so the Library stays reachable offline.

## 2.1.2
- Fullscreen locks to landscape (both sides, follows the sensor) like the YouTube app.

## 2.1.1
- Ads inside Shorts are removed (deep pruning of YouTube's data, Shorts ad skipper, extra CSS).
- Ad filters refresh every four days.
