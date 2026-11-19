# car_play – Flutter IPTV player with Android Auto

## 1. Project overview

- **IPTV app (Flutter):** loads an M3U playlist (default: public iptv-org list; more can be added in Settings),
  and lets you browse channels, categories, favorites, recents, search, filter by category/country, add custom
  stream URLs, and watch inline or in Picture-in-Picture.
- **Android Auto:** the app has two car entry points. (1) The **car screen** (`CarVideoService`): video plus a
  list button that opens a channel browser (All Channels, Categories, Countries, Favorites, Recent, Playlists)
  and Prev / Play-Pause / Next buttons. (2) The **media center** entry, which browses the same library as a
  media app. Both read one `MediaLibrarySession` and control one player.
- **Video on the car display:** a separate car screen (`CarVideoService`) draws the stream on the car's
  Surface using the same player the phone uses.
- One player only. Phone UI, car UI, notification, Bluetooth and Assistant all control it.

## 2. Architecture

```
Flutter UI (lib/iptv)
   │ MethodChannel "iptv_native/control", EventChannel "iptv_native/events"
   ▼
IptvNativePlugin ── MediaController ──▶ IptvPlaybackService ◀── Android Auto media UI (browse + controls)
 (phone video TextureView)               Media3 ExoPlayer           via MediaLibrarySession
                                         + MediaLibrarySession
                                                ▲
CarVideoService (Car App Library) ── car Surface ┘   (video + ListTemplate channel browser;
   reads the same tree through a MediaBrowser and plays through the same session)

Shared JSON files in files/iptv/:  Flutter ⇄ IptvStorage ⇄ IptvPlaybackService
```

## 3. IPTV data flow

1. `IPTVRepository` (`lib/iptv/iptv_repository.dart`) starts, loads `IPTVSettingsService` (playlists, active
   playlist, custom streams in `shared_preferences`) and calls `refresh()`.
2. `PlaylistService` downloads the active playlist URL (or uses the cache if younger than the cache setting,
   default 12 h), `M3UParser` turns it into `Channel` objects (`id`, `name`, `url`, `logo`, `group`, `country`).
   Only M3U is supported (no Xtream Codes).
3. Channels are cached in `files/iptv/channels.json` (only the **active** playlist). `ChannelService` and
   `CategoryService` organize them for the phone UI; categories are the channel `group`.
4. After each refresh Flutter calls `IptvNative.notifyDataChanged()`, so native refreshes the car lists.
5. A channel's stream URL is simply `Channel.url`. Tapping a channel calls `IPTVPlayerService.play()` →
   `IptvNative.play()` → native `play` handler, which turns the channel and its neighbours into Media3
   `MediaItem`s (`setMediaItems`, `prepare`, `play`) on the shared player.

Shared files (`files/iptv/`): `channels.json` (Dart writes), `favorites.json` (Dart on the phone, native from
the car heart button; Dart re-reads before each toggle), `recents.json` (native writes), `selection.json`
(native writes; the list being browsed).

## 4. Android Auto integration

- **Connection:** `IptvPlaybackService` is a Media3 `MediaLibraryService` declared in the plugin manifest with the
  `androidx.media3.session.MediaLibraryService` and `android.media.browse.MediaBrowserService` actions, so
  Android Auto binds to it. The app is declared a media app by `com.google.android.gms.car.application` →
  `res/xml/automotive_app_desc.xml` (`media` and `template`).
- **Why there are two entry points:** a media app's library appears in Android Auto's media center; a
  navigation-category app (the car screen) opens its own template screens and cannot show a list inside
  `NavigationTemplate`. The car screen therefore pushes `ListTemplate` screens (`BrowseScreen`) that call
  `MediaBrowser.getChildren()` on the same library, so both show identical data.
- **Browse tree:** `LibraryCallback.onGetLibraryRoot` returns the `IPTV` root (four tabs, the Android Auto
  limit); the `home` folder lists the whole library on one level and is where the car screen's browser
  starts. `onGetChildren` builds everything from `IptvStorage` (real data, nothing hardcoded):

```
home  (car screen browser starts here)
├─ All Channels ─▶ flat list (≤150 channels) or A–Z buckets ─▶ channels
├─ Categories, Countries, Favorites, Recent, Playlists  (same folders as below)

IPTV  (media center root, 4 tabs)
├─ Favorites ─▶ channels
├─ Recent ─▶ channels
├─ Categories ─▶ <group> ─▶ channels
└─ Playlists ─▶ <active playlist> ─┬─ Categories
                                   ├─ All channels (flat list, or A–Z buckets above 150 channels)
                                   ├─ Countries ─▶ <country> ─▶ channels
                                   └─ On phone: <list the phone is showing>
```

- **Playlists:** names come from Flutter's prefs file (`FlutterSharedPreferences`, key
  `flutter.iptv.playlists`). Only the **active** playlist is browsable, because `channels.json` holds only that
  one. Other playlists appear as information rows ("Select it in the phone app").
- **Empty states:** rows such as "No channels yet", "No favorites yet", "Nothing watched yet", "No results".
- **Paging:** `onGetChildren` honours `page`/`pageSize`. The car browser sizes pages to the host's list limit
  (`ConstraintManager`) and adds "Previous page"/"Next page" rows.
- **Selecting a channel:** the channel item's `mediaId` is `ch|<scope>|<id>`. `onSetMediaItems` expands it to the
  list it came from (±100 channels), so Next/Previous move within that list; the player starts it. In the car
  browser `BrowseScreen.play()` calls `MediaBrowser.setMediaItem()`, then returns to the video screen
  (`popToRoot`). Back returns to the previous list; another channel can be chosen the same way.
- **Search:** `onSearch` / `onGetSearchResult`; voice "play <name>" is matched in `onSetMediaItems`.
- **Refresh:** `IptvPlaybackService.refreshLibrary()` notifies the root and all folders when Flutter reports a
  data change; `refreshSelected()` when the phone changes its list.

## 5. Video playback flow

Phone: `NativeVideoView` (`lib/iptv/ui/video_view.dart`) → `VideoViewFactory` creates a `TextureView` →
`MediaController.setVideoTextureView()`.

Car:
1. `CarVideoService.onCreateSession()` → `CarVideoSession.onCreateScreen()` registers a `SurfaceCallback` with
   `AppManager.setSurfaceCallback()` and binds the playback service through a `MediaController`.
2. `onSurfaceAvailable` stores the host Surface; `CarVideoSession.sync()` decides if video is allowed
   (vehicle speed ≤ 0.5 m/s; unknown speed hides video except in debug builds).
3. `IptvPlaybackService.setCarSurface()` → `applyCarSurface()` → `ExoPlayer.setVideoSurface()`.
4. While the car owns the Surface, `ChannelPlayer` ignores phone `setVideoSurface/clearVideoSurface` calls.
   On `onSurfaceDestroyed`, `clearCarSurface()` runs and `IptvNativePlugin.reattachPhoneVideo()` gives the video
   back to the phone view. A new Surface (reconnect) is attached the same way.

The Surface is host-owned, so wide video can look stretched.

## 6. Playback controls

- **Play/Pause, Next/Previous:** all through the session's player, `ChannelPlayer` (a `ForwardingPlayer`).
  Next/Previous always change channel and wrap around; Play with an empty queue starts the last recent channel,
  then favorites, then the first channels. Seek buttons are removed (live streams).
- **Favorite:** a heart button (`CMD_TOGGLE_FAVORITE` custom command) adds/removes the current channel in the
  media UI; in the car browser, the first row of the home list does the same.
- **Metadata:** each `MediaItem` carries title, group, "group · country" subtitle and logo; Android Auto shows
  them and updates on channel change.
- **State:** Media3 publishes the playback state; the phone gets it as events (`state`, `connection`,
  `selection`, `userData`, `parkedVideo`) on `iptv_native/events`.
- **Errors:** network errors retry up to 3 times (and again when connectivity returns); "behind live window"
  re-prepares; a dead stream is skipped while the user is stepping with Next/Previous (up to 5). The car video
  screen shows "Can't play …" for errors.
- **Driving policy:** `ParkedVideoPolicy` disables the video track and can block playback while restricted.

## 7. Flutter ⇄ native communication

- `packages/iptv_native/lib/iptv_native.dart` wraps `MethodChannel("iptv_native/control")` (play, resume, pause,
  stop, next, previous, retry, dataChanged, setSelection, clearRecents, enterPip, …) and
  `EventChannel("iptv_native/events")`.
- `IptvNativePlugin.onMethodCall` handles calls using one `MediaController` connected to the service.
- Files in `files/iptv/` carry the bulk data (channels, favorites, recents) so native never parses playlists.

## 8. Important files

| File / class | Responsibility |
|---|---|
| `lib/iptv/iptv_repository.dart` | Loads playlists, applies channels, tells native about changes |
| `lib/iptv/services/playlist_service.dart`, `core/m3u_parser.dart` | Download, parse, cache M3U |
| `lib/iptv/services/settings_service.dart` | Playlists, active playlist, custom streams, preferences |
| `lib/iptv/services/favorite_service.dart` | Favorites (`favorites.json`) |
| `lib/iptv/services/player_service.dart`, `playback_controller.dart` | Phone playback commands and state |
| `packages/iptv_native/lib/iptv_native.dart` | Dart side of the native bridge |
| `…/iptv_native/IptvNativePlugin.kt` | Method/event channels, phone video view, PiP |
| `…/iptv_native/IptvPlaybackService.kt` | The ExoPlayer, `MediaLibrarySession`, browse tree, controls, errors |
| `…/iptv_native/CarVideoService.kt` | Car screen: video Surface hand-off, `VideoScreen`, and `BrowseScreen` channel browser |
| `packages/iptv_native/android/src/main/res/drawable/ic_car_*.xml` | Icons for the car screen's action strip |
| `…/iptv_native/IptvStorage.kt` | Reads channels, favorites, recents, playlists; writes recents, favorites, selection |
| `…/iptv_native/ParkedVideoPolicy.kt` | Driving/parked decisions |
| `packages/iptv_native/android/src/main/AndroidManifest.xml` | Services, permissions, car metadata |
| `android/app/src/main/res/xml/automotive_app_desc.xml` | Declares `media` and `template` |

(`…` = `packages/iptv_native/android/src/main/kotlin/com/example/iptv_native`.)

## 9. Dependencies and configuration

- Flutter/Dart `^3.14.0`; `provider`, `http`, `cached_network_image`, `shared_preferences`, `wakelock_plus`,
  local plugin `packages/iptv_native`.
- Android plugin: Media3 `1.11.1` (exoplayer, hls, dash, smoothstreaming, rtsp, session),
  `androidx.car.app:app:1.7.0`, compileSdk 36, minSdk 24.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `androidx.car.app.NAVIGATION_TEMPLATES`,
  `androidx.car.app.ACCESS_SURFACE`, `com.google.android.gms.permission.CAR_SPEED`.
- `CarVideoService` is in the NAVIGATION category and uses `ALLOW_ALL_HOSTS_VALIDATOR` – replace it with
  Google's host allow-list before distributing.

## 10. Build and run

```bash
flutter pub get
flutter analyze && flutter test
flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk   # approve the install on the phone if asked
```

Android Auto: enable developer mode and start the head unit server in the Android Auto app, then

```bash
adb forward tcp:5277 tcp:5277
~/Library/Android/sdk/extras/google/auto/desktop-head-unit
```

Check: open the app once on the phone so the playlist loads; in the head unit browse Playlists, Categories,
Favorites, Recent; play a channel; go back and play another; Play/Pause/Next/Previous; the heart button;
search; open the car video screen and set speed to 0 in the sensor panel; disconnect and reconnect.

## Local videos

Play video files from the phone's storage through the same player, output selector and Android Auto video screen as IPTV channels.

**Using it.** Home screen → the video-library icon in the app bar → **Add videos** (system file picker, multi-select; MP4, MKV, MOV, WebM, 3GP … anything ExoPlayer can decode). The library shows thumbnail, name, duration and size. Tap a video to play it; the whole library becomes the queue, so Next / Previous (and automatic advance at the end of a file) step through the files. The player screen shows a seek bar, ±10 s buttons and the usual **Play video on: Mobile / Android Auto** selector; the mini-player and full-screen player work as for channels. Remove an entry with the trash icon.

**Architecture.** There is no second player. A local video is just a `MediaItem` with a `content://` URI queued into the one `ExoPlayer` owned by `IptvPlaybackService`:

* Dart: `LocalVideo.toChannel()` produces a normal `Channel` whose `url` is the URI, so `IptvNative.play(channel, queue)` is reused unchanged. `ChannelService.setLocal` makes the id resolvable by `PlaybackController.current` without adding it to the IPTV list.
* Native: `IptvNativePlugin.toItem` recognises `content://` / `file://` URLs and builds the item with media id `ch|local|<id>` (`IptvPlaybackService.isLocal`). The service then treats it as non-live: seek commands stay available (they are removed for live IPTV), the stall watchdog (which reloads at the "live edge") skips it, it is not added to IPTV Recents and has no favorite button.
* `DefaultMediaSourceFactory` now uses `DefaultDataSource.Factory(context, httpFactory)` so `content://` URIs are readable alongside http(s); IPTV behaviour is unchanged.
* The state event carries `isLocal`, `positionMs`, `durationMs`; Dart polls `position` (500 ms) only while a seek bar is on screen, and `seekTo` seeks the shared player.

**Android Auto browsing.** Dart publishes the library to `local_videos.json` (shared directory, Dart is the only writer) and calls `dataChanged`; `IptvPlaybackService` serves it as a **Local videos** folder on the car's home list (`IptvStorage.localVideos`), and picking one queues the whole folder under the `local` scope.

**Output handling.** Unchanged: the native `output` setting (phone / car) decides who owns the video Surface. The car overlay (`CarPlayerUi`) shows Play/Pause, Previous, Next, Fit/Fill and, for local videos, elapsed time, a tap-to-seek progress bar and total time instead of the LIVE bar (favorite is hidden). Switching output only moves the Surface, so playback and position are preserved. When Android Auto disconnects, `videoOnCar` goes false and the phone shows the video again; on reconnect the car session re-attaches its Surface to the running player.

**Storage permissions and URIs.** The picker is the Storage Access Framework (`ACTION_OPEN_DOCUMENT`), so **no storage permission is requested** (no `READ_EXTERNAL_STORAGE` / `READ_MEDIA_VIDEO`). The app takes a persistable read grant (`takePersistableUriPermission`) per file and stores only URI + metadata in SharedPreferences (`local_videos`); files are never copied. Removing an entry releases the grant. If a file is later moved or deleted, playback shows the error state ("This video cannot be played") with Retry / Next; unsupported or corrupt files surface the same way via `onPlayerError`.

**Key files.** `lib/iptv/core/local_video.dart`, `lib/iptv/services/local_video_service.dart`, `lib/iptv/ui/local_videos_screen.dart`, `lib/iptv/widgets/local_seek_bar.dart`; `packages/iptv_native/lib/iptv_native.dart` (`pickVideos`, `seekTo`, `position`, `videoThumbnail`); `IptvNativePlugin.kt` (picker, metadata, thumbnails, `toItem`); `IptvPlaybackService.kt` (`isLocal`, data source, seek/watchdog rules); `CarPlayerUi.kt` / `CarVideoService.kt` (progress bar, tap-to-seek).

**Status.** Dart analysis and unit tests pass and the debug APK builds. The on-device / Android Auto scenarios (mobile and car playback, output switching, seeking, sequential play, bad files, disconnect/reconnect, IPTV regression) have **not yet been run** — no device was connected when this was implemented.

## Browser, three tabs and Send to Car

**Phone UI.** `lib/main.dart` (`_MyHomePageState`) is a shell with a Material 3 `NavigationBar`: **IPTV**, **Local Video**, **Browser**. Tabs live in an `IndexedStack`, so switching never restarts a list, the WebView or playback (the player is native and independent of the UI). The IPTV tab is the unchanged `IptvHomeBody`; the Local Video tab is `LocalVideosScreen(embedded: true)` (the same list, add/remove and player screen as before; the old app-bar entry was replaced by the tab). The mini-player shows on the IPTV and Local Video tabs. The Browser tab is created on first visit only.

**Browser (`lib/browser/`).**
* `browser_controller.dart` – `BrowserController` owns the single `WebViewController` (`webview_flutter`, Android WebView) plus URL, title, progress, loading, error, back/forward state and history (last 100 pages, in `shared_preferences`). Navigation: back, forward, reload, stop (`window.stop()`), Home (the WebView keeps its page). Non-web links (`tel:`, `mailto:`, `intent:`, `market:`) are blocked in the page and handed to the system (`openExternal` in `IptvNativePlugin`). Main-frame load errors show a friendly message with Try again. System Back navigates the page first (`PopScope`).
* `browser_screen.dart` – address bar (clear button, progress line, title above the URL), home with popular sites and recent history, toolbar with **Send to Car**, history sheet, "Keep car in sync" (on by default, remembered; see *Phone ⇄ car synchronization*).
* `browser_sites.dart` – `popularSites` (edit this list to change shortcuts; icons are favicons fetched live with a coloured-letter fallback) and `BrowserInput.resolve` (URL vs search: schemes, host names, IPs and `localhost` become URLs; everything else is searched with `searchEngineUrl`, Google). The car has its own copy of the sites in `PopularSites.kt` and of the resolver in `WebUrls` (keep in sync).

**Send to Car.** Phone: `BrowserController.sendToCar()` reads the live URL from the WebView and calls `IptvNative.sendBrowserUrl` -> method channel `sendBrowserUrl` -> `CarBrowserBridge.send` (process-wide object in `CarBrowserBridge.kt`). The bridge validates (http/https only -> `invalid`), drops duplicates (same URL within 3 s, or the car already shows exactly that page -> `duplicate`), and returns `sent` when the car session is attached, `queued` when Android Auto is connected but the car app is not open yet (delivered the moment `CarVideoSession` attaches, expires after 5 min), or `noCar` when nothing is connected (the phone shows a message). Car -> phone: the car publishes `{open, url, title, loading}` through `CarBrowserBridge.publish`; the plugin emits it as the `carBrowser` event and the Browser home shows "On the car: ...".

**Car browser and the Android Auto limitation.** Android Auto has **no WebView/browser template** and never shows app views, so a browser cannot be built from official templates only. The best supported alternative, consistent with the existing video screen, is used: the car app (`CarVideoService`, NAVIGATION category, `ACCESS_SURFACE`) renders a *real* `WebView` off-screen (`CarWebBrowser`: `VirtualDisplay` + `Presentation`) into a second `SurfaceTexture` of `CarVideoRenderer`, which draws it onto the host Surface (still one GL owner per Surface). `CarBrowserUi` draws a large toolbar into the same frame (list, back, forward, reload/stop, address pill, progress). Taps, scroll and fling come from `SurfaceCallback` (`onClick`, `onScroll`, `onFling`; `Action.PAN` is set on the map action strip). Typing uses the official `SearchTemplate` (keyboard, only while parked). Like the video, the browser is **parked only** (speed > 0.5 m/s shows "Browser paused while driving"; unknown speed is treated as unavailable except in debuggable builds), and is outside Google Play's car-app policy (sideload/personal use). Leaving the web screen detaches the page (state saved) and the video/artwork returns; IPTV/local audio is never interrupted. Limitations: no text entry inside pages, no pinch zoom, no downloads or external-app links on the car.

**Phone ⇄ car synchronization.** *Why two WebViews:* the phone's WebView is a Flutter platform view inside the Activity; Android Auto never shows Activity views and a view cannot be drawn into the car's Surface from another process-visible window, so the car needs its own WebView (`CarWebBrowser`, VirtualDisplay + Presentation, created only while the car web screen is visible and released on detach). There is exactly one WebView per side, never more. *Single source of truth:* the current page URL. `BrowserController` (phone) and `CarWebBrowser` (car) follow each other through `CarBrowserBridge`:
* Phone -> car: with "Keep car in sync" on, every finished page on the phone calls `syncBrowserUrl` -> `CarBrowserBridge.sync`. This is a *background* sync: the car's browser takes the URL (`CarSide.syncUrl`) but the car screen is not changed, so IPTV video keeps playing; the page loads when the car Browser screen is opened ("Current page"). Phone Back/Forward produce a new URL and sync the same way. **Send to Car** is still the explicit "show it now" action (`openUrl`, brings the web screen forward).
* Car -> phone: the car publishes `{open, url, title, loading}`; when the user navigates on the car and the page finishes, the phone opens the same URL (`_onCarState`).
* Loop/duplicate protection: both sides compare pages with `same()` (case, trailing slash and `#fragment` ignored; `sameBrowserPage` in Dart, `WebUrls.same` in Kotlin); the car never reloads the page it already shows (`CarWebBrowser.load`), the bridge answers `duplicate`, and the phone remembers the last URL it exchanged (`_lastMirrored`).
* Reconnect: while the car session lives, detaching the Surface saves the WebView state (page + history) and re-attaching restores it. If the whole car session ends (Android Auto disconnect), `CarBrowserBridge.lastPage` keeps the last URL in the app process; the next session's `attach` loads it quietly, and the phone re-syncs when the car reports an empty state. Back/forward history of the *car* WebView is not kept across a full session end, only the page.
* Back / Forward / Reload / Stop and the address pill (keyboard through the official `SearchTemplate`) are on the car toolbar (`CarBrowserUi`); the car toolbar reuses the phone's palette-style dark surface, pill address bar, 3 px progress line and the same popular sites and search engine.

**Android Auto policy.** *Officially supported:* template screens (`TabTemplate`, `ListTemplate`, `SearchTemplate` keyboard), media playback through the media session. *Not supported by any category:* a WebView/browser. The WebView-on-Surface route used here relies on the NAVIGATION-category surface and a `VirtualDisplay`; it is for sideloaded/personal builds, parked only, and is **not suitable for Google Play** distribution. *Compliant alternative:* the car Browser tab lists current page, popular sites and history as a `ListTemplate` and only the phone shows the page. *Android Automotive OS:* a full in-car browser would need a separate AAOS build (the platform has its own browser); not done here.

**Sync verification status.** `flutter analyze` clean; `flutter test` 14/14 (adds `sameBrowserPage`); debug APK builds. **Not verified on a device or the DHU** (the phone went offline during install): background sync, car -> phone following, reconnect restore, no-reload on duplicate, network-error banner on the car, IPTV <-> browser switching, rotation/resize (the car web buffer is sized at attach and is not re-laid out on visible-area changes), and the new default-on sync's behaviour with real redirects.

**Car Browser home (mirrors the phone's Browser tab).** Android Auto templates cannot combine a search field, an icon grid and a list, so selecting the **Browser** tab opens the web screen in *home mode*: `CarBrowserHomeUi` draws, into the car Surface, the phone's home layout and Material 3 colours (light/dark and accent read from the phone's settings): search pill on top (tap = keyboard via `SearchTemplate`), optional "current page" card, **Popular sites** as a 4-column icon grid with names (favicons fetched live, coloured-letter fallback, same list as `browser_sites.dart`), and **Recently visited** history (6 rows, **Clear**) at the bottom. Drag scrolls the home, taps open a site/history entry. The page toolbar's first button is **Home**: it stops the page (loading, scripts, media and network) by saving its state and releasing the car WebView (`goBrowserHome`), then shows this view; the page stays listed as "current page" and is restored (not reloaded from scratch) when tapped. The browser home and the IPTV / Local Video tabs never keep a WebView alive. Home is shown only while parked, like the page. Hosts without TabTemplate open the same screen from the list header. Not verified on the head unit (build/install and service start only; layout was not seen on screen).

**Car screens that mirror the phone app (IPTV, Local Video, Browser).** When the host grants Surface access, the car's root screen is one Surface screen (`WebScreen`, root marker `web`) that draws the phone's three tabs: a bottom navigation bar (IPTV / Local Video / Browser), and above it, on the list tabs, the mini-player (logo, title, status, previous / play-pause / next). `CarShellUi` draws: **IPTV** = header with playlist status, category chips (All, Favorites (n), Recent, groups with counts; swipe sideways), a search field (tap = `ChannelSearchScreen`, keyboard through `SearchTemplate`), and the channel list (logo, name, "group · country", heart, now-playing badge, "Clear history" under Recent); **Local Video** = thumbnails, title, duration and size (read off the main thread by `LocalVideoMeta` with `MediaMetadataRetriever`), now-playing overlay, or the phone's empty state; **Browser** = `CarBrowserHomeUi` (above) with the same bottom bar. Light/dark and accent come from the phone's settings. The chip selection is the shared selection (`IptvStorage.selection`), so choosing a chip on the car changes the phone's list and the reverse. Tapping a channel plays it with the same queue scope the old list used (`all`, `favorites`, `recents`, `cat:<group>`, `search:<q>`, `local`) and opens the player screen; the heart toggles the favorite in the shared store. Lists scroll by drag with momentum. Only the Browser tab creates the WebView; leaving it detaches the WebView and keeps the page. If Surface access is not granted, the old `TabTemplate` / `ListTemplate` screens are used instead (unchanged). **Differences from the phone:** no filter sheet (category / country multi-select), no playlist menu, reload or settings, no "Add / remove videos" (done on the phone), and the lists stay usable while driving (video and the browser remain parked-only). **Policy:** these are custom-drawn UIs on a navigation Surface, so like the browser they are for sideloaded / personal builds only, and a touch UI that is usable while driving would not pass Play review. **Verification (Android Auto Desktop Head Unit, 800x400, debug APK on a real phone):** *seen working* - the Browser page and its toolbar; the browser home with the bottom bar; switching tabs from the bar; the IPTV tab (header, chips, search field, channel logos, hearts), the "All" chip, tapping a channel (video plays on the car, player controls shown), returning with the host back button, the mini-player with the now-playing highlight and badge; the Local Video tab (thumbnail, duration, size) and playing a local video. *Not verified:* swiping the chip row and lists, search typing, favorite toggling from the car, Clear history, mini-player buttons, light/dark and accent changes, larger or portrait displays, the fallback to the old list screens. On this small display the bar + mini-player leave room for about four rows, and the host's "Player" button covers the top-right corner of the header.

**Car home.** On hosts with Car API level 6+ the root is a `TabTemplate` (`TabsScreen`): IPTV (same list as before), Local Video, Browser (search row, current page, popular sites). Older hosts keep the old list, with a Browser button in its header (`BrowserHomeScreen`). A URL arriving from the phone calls `loadInCar` -> `openWeb()` (reuses the web screen if it is already open, so there is never a second browser instance).

**Reliability notes.** *Delivery confirmation:* Send to Car reports `sent` only after the car confirms the web screen is visible with its page surface attached (`CarBrowserBridge.awaitConfirm`, 6 s timeout, otherwise `unconfirmed` and the phone asks you to open the app on the car). *Restart recovery:* after the app process restarts the Android Auto host keeps showing the old frame and gives no surface until the app is brought forward; when a URL arrives and there is no surface, the car session calls `CarContext.startCarApp` (`bringToFront`). *Host validation:* release builds accept only Google's hosts (`hosts_allowlist_sample`); debuggable builds accept any host so the DHU works. *Layout:* the toolbar's address pill stays clear of the host's Home action in the top-right corner (`CarBrowserUi.HOST_STRIP_FRACTION`). *WebView:* released fully on every detach (about:blank, clients cleared, `destroy`). Event logs use the tag `CarBrowser` and never contain URLs.

**Debug hook.** In debuggable builds `adb shell am broadcast -a com.example.car_play.DEBUG_SEND_URL --es url https://example.com` runs the same path as the Send to Car button.

**Key files.** `lib/main.dart`, `lib/browser/*`, `packages/iptv_native/lib/iptv_native.dart` (`sendBrowserUrl`, `carBrowser`, `openExternal`), `CarBrowserBridge.kt` (bridge, `WebUrls`, `PopularSites`), `CarWebBrowser.kt`, `CarBrowserUi.kt`, `CarVideoRenderer.kt` (web mode), `CarVideoService.kt` (`TabsScreen`, `WebScreen`, `SearchScreen`), `IptvNativePlugin.kt`, `test/browser_input_test.dart`.

**Verification status.** `flutter analyze` clean, `flutter test` 13/13, debug APK builds and installs; the phone app starts with the three tabs and the IPTV tab renders as before; the car host accepted the `TabTemplate`; `sendBrowserUrl` returned `sent` and the host received the web `NavigationTemplate`. **Not verified on a device:** phone browser interaction (this phone blocks `adb input`), the page actually rendering on the head unit (the head-unit server stopped before I could see it), car touch/scroll/keyboard, queued delivery, Local Video and IPTV regression on the car.

## 11. Limitations

- Android Auto's media UI is lists only: no keyboard entry, settings screens or folder icons. Adding playlists
  or streams, theme, cache and PiP settings stay on the phone.
- Playlist switching is phone-only; the car can browse only the active playlist.
- The car browser's lists have no channel logos (the media-center tree does carry artwork URIs).
- Row counts per page follow the host's list limit, which is small while driving.
- No loading indicator in the car; an empty list tells you to open the phone app.
- No Xtream Codes, audio-track or subtitle selection (the app doesn't implement them).
- Video through the navigation surface is outside Google Play's car-app policy; use for personal/sideloaded
  builds. Play-compliant parked video needs the Android Auto video category (beta) or Android Automotive OS
  (`appCategory="video"` is declared but never run).
- iOS/CarPlay code exists under `ios/` but is not covered here.

## 12. Complete flow

Launch the app → `IPTVRepository` loads settings and downloads/parses the active playlist → `channels.json` is
written and native is notified → connect to Android Auto, which binds `IptvPlaybackService` and calls
`onGetLibraryRoot`/`onGetChildren` → browse Playlists/Categories/Favorites/Recent → pick a channel →
`onSetMediaItems` queues it, ExoPlayer plays it, metadata and state reach the car → open the car video screen:
`CarVideoService` passes the car Surface to the same ExoPlayer, and the video appears → control with
Play/Pause, Next/Previous, heart; go back to the list and choose another channel.

## Verification status

Verified with the debug build on a connected phone (`flutter analyze` clean, `flutter test` 7/7):

- A throwaway Media3 `MediaBrowser` client (since removed) read the real `MediaLibrarySession` the way Android
  Auto does: root `IPTV` is browsable; `home` returns 6 folders; All Channels = 823 channels (A–Z buckets),
  Categories = 38, Countries = 7, Favorites = 2, Recent = 14, Playlists = the configured playlist; folders
  are browsable, channels playable; paging works (A–E then F–J); search "news" returns channels.
- Selecting a channel from a category, a country, Recent and Favorites played that exact channel and queued that
  list (9, 14, 14, 2 items). Next/Previous moved within the list (dead streams are skipped). Another channel
  could be selected afterwards without restarting. Pause/Play worked. The favorite toggle wrote
  `favorites.json` and restored it; Recent updated.
- With the Desktop Head Unit connected, the Android Auto host accepted `CarVideoService`'s `NavigationTemplate`
  (no template or constraint errors in logcat).

Not verified: the rendered car UI itself. I could not tap in the head unit, so the `ListTemplate` channel
browser (`BrowseScreen`) and the media-center tabs were not seen or clicked on the car display, and the earlier
video, speed gating, and disconnect/reconnect behaviour were not re-tested for this build.
