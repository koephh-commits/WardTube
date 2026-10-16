# Changelog

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
