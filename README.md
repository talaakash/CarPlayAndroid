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
