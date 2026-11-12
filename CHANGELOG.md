# Changelog

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
