# Android Auto release build: stale UI investigation

Written for: the developer maintaining this app.

## 1. Root cause

**The car shows the previous instance's last frame, because the new session cannot take over the host Surface.**

The "new" car UI (IPTV / Local Video / Browser, video, web page) is not a template. It is drawn by
OpenGL onto the Surface that the Android Auto host gives the `NavigationTemplate`
(`CarVideoSession` -> `CarVideoRenderer`). If the new session cannot create its GL renderer on that
Surface, nothing new is ever drawn and the host keeps showing the last frame an earlier instance
drew. That frame is the old UI, and none of the new browser/video code is running.

The old code had no recovery:

```kotlin
surfaceContainer.surface?.let { renderer = CarVideoRenderer(it).takeIf { r -> r.inputSurface != null } }
// renderer == null  ->  silently nothing, and no retry
```

Secondary defects that make reconnects unreliable:

* `IptvPlaybackService.clearCarSurface()` was global. A session being torn down (disconnect/reconnect
  overlap) could clear the output the **new** session had just attached.
* The fallback to the old list UI (`TabsScreen`/`BrowseScreen`) was silent and permanent for the
  session if `setSurfaceCallback` threw once.
* Release builds are minified, so `javaClass.simpleName` is useless in logs (`ye`).

## 2. Evidence

All of this is from a physical phone (Xiaomi M2103K19PI, Android 13, Android Auto 18.0) plus the
Desktop Head Unit, using a release build.

1. **Install over a live Android Auto session reproduces it.** `adb install -r app-release.apk` while
   connected: the host auto-rebinds the new process and starts a session, but
   `libEGL: eglCreateWindowSurface: native_window_api_connect ... failed (already connected to another API?)`
   / `EGL_BAD_ALLOC` is logged, the log says `renderer ready=false`, and the DHU keeps showing the
   previous frame. This is the `flutter run --release` case: "app appears, old UI".
2. Retrying creation alone does not fix it (10 retries over ~25 s, all fail). Resetting the surface
   callback does not fix it either (the host hands out a new Surface, which also fails).
3. **`startCarApp()` does fix it.** The host destroys the stale surface, calls `onNewIntent`, delivers a
   fresh Surface, and the log reads `renderer ready (surfaceAvailable, attempts=0)`.
4. Session code is deterministic. The only path that yields the old list UI is `shellOk == false`,
   i.e. `setSurfaceCallback` throwing. It never threw in these runs (`setSurfaceCallback OK`).
5. **The builds are equivalent.** `flutter build apk --release` and the plain
   `./gradlew :app:assembleRelease` that Android Studio runs produce the same manifest, the same file
   list, byte-identical `classes.dex` and `libapp.so`, and the same debug-key signature
   (SHA-256 `4622...3d3`). So there is no "Android Studio builds a different module" problem in this
   project. The installed APK on the phone was also byte-identical to the Flutter output.
   * `flutter run --release` builds only the device ABI (~21 MB); Android Studio builds all ABIs (~57 MB).
     That is a size difference only.
6. `versionCode=1`/`versionName=1.0.0` never change, and release is signed with the debug key unless
   configured. A "Generate Signed APK" in Android Studio with its own keystore signs with a
   *different* key than `flutter run`, so one cannot be installed over the other
   (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and people uninstall/reinstall. That is a plausible
   contributor to Case 1 but is **not proven**; see Limitations.

## 3. Files modified

| File | Change |
|---|---|
| `packages/iptv_native/.../AaLog.kt` (new) | `ANDROID_AUTO_DEBUG` logger + per-process identity line (package, version, build type, install time, UI revision) |
| `packages/iptv_native/.../CarVideoService.kt` | logging; renderer creation with retry + `startCarApp()` recovery; surface ownership on teardown; old-UI fallback now retries and self-heals; explicit screen names |
| `packages/iptv_native/.../IptvPlaybackService.kt` | `clearCarSurface(owner)` only clears if the caller owns the surface; logging |
| `packages/iptv_native/.../CarBrowserBridge.kt` | attach/detach logging |
| `packages/iptv_native/.../IptvNativePlugin.kt` | engine / MethodChannel / CarConnection logging |
| `android/app/build.gradle.kts` | optional shared release keystore (`android/key.properties`), falls back to debug key |

## 4. Changes in detail

* **Renderer recovery** (`createRenderer`): on failure, retry at 150/300 ms; on the 2nd failure call
  `startCarApp()` (the call that actually recovered it); on the 6th reset the surface callback;
  budget of 10 retries, not restarted by our own resets.
* **Ownership**: `clearCarSurface(renderer)` ignores callers that no longer own the output.
* **Deterministic home screen**: if the surface request fails at creation, the old list UI is shown
  only as a fallback and `onStart` retries; on success the old root is replaced by the shell UI.
* `onNewIntent` is logged (it is how the host re-delivers a re-open).

## 5. Android Studio release build

1. Optional but recommended: create `android/key.properties` (git-ignored):
   ```
   storeFile=../release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```
   Without it both tools sign with the debug key (identical, as verified).
2. Build > Generate App Bundles or APKs > APK > variant **release**, or `cd android && ./gradlew :app:assembleRelease`.
3. Output: `build/app/outputs/apk/release/app-release.apk`.

## 6. Flutter release build

```
flutter build apk --release          # build/app/outputs/flutter-apk/app-release.apk
flutter run --release                # builds + installs for the connected device only
```

## 7. Install

```
adb install -r build/app/outputs/flutter-apk/app-release.apk
```
If you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed copy was signed with a different key:
`adb uninstall com.example.car_play` first. Always use one signing setup from then on.

## 8. Android Auto testing requirements

* Android Auto developer settings: **Unknown sources** enabled. This only lets the host list
  non-Play-Store apps; it does not by itself make any Car App Library app eligible. Sideloaded
  builds are for development; this app uses the NAVIGATION category and the car Surface, which is
  outside Google Play's car-app policy.
* The release build accepts only Google's Android Auto host signatures (allow-list). Debuggable builds
  accept any host (needed for some DHU setups).
* Reconnect/disconnect Android Auto after installing a new APK if the launcher entry is missing.
* Video is shown only when the car reports speed 0, or when "Allow video when speed is unavailable"
  is enabled. This is a safety rule and was not bypassed.

## 9. Diagnostic logs

```
adb logcat -s ANDROID_AUTO_DEBUG
```
Every line is `[bootId/pid] area: message`. A new `bootId` means a new process.

Read it like this:

| You see | Meaning |
|---|---|
| `identity: ... lastUpdate=... carUiRevision=car-ui-r3-web-shell` | which APK is actually running |
| `initial screen = WebScreen` | new UI selected |
| `FALLBACK to older screen ...(old-list-ui)` | the old UI was chosen, with the reason |
| `renderer ready (...)` | the new UI is being drawn |
| `renderer NOT ready ... stale frame` | the host Surface is held by a previous instance; the car shows the old frame |
| `startCarApp requested` -> `surface destroyed` -> `onNewIntent` -> `renderer ready` | automatic recovery worked |
| `clearCarSurface ignored: caller does not own...` | a stale session teardown was prevented from killing the new output |
| `onGetTemplate screen=... -> Template` | every template returned |
| `browser initialised` / `video initialised` | feature initialisation status |

## 10. Test results

Tested on the physical phone + Desktop Head Unit, release APK built by `flutter build apk --release`.

| Scenario | Result |
|---|---|
| Release APK builds, Flutter vs Gradle (Studio-equivalent) | Verified: identical manifest/dex/libapp/signature |
| Fresh install, first Android Auto connection | Verified: new UI, renderer ready |
| Install over a live Android Auto session (the failing case) | Reproduced before fix; after fix `renderer NOT ready` -> `startCarApp` -> `renderer ready` |
| 5 consecutive disconnect/reconnect cycles (DHU restart) | Verified in logs: every cycle `initial screen = WebScreen` + `renderer ready` (2 in the same process, 3 in new processes) |
| Application process termination and restart | Verified (process died on reconnect cycles 3-5, new session came up correctly) |
| `flutter test` | 14 passed |
| Browser: home -> Wikipedia loads, toolbar shown, `browser initialised: attach=true` | Verified on DHU |
| IPTV channel pick -> player screen (artwork + LIVE bar + prev/pause/next), close -> main with mini-player | Verified on DHU (output mode = Phone, so the car shows artwork) |
| Browser -> video -> back to browser (history entry kept) | Verified on DHU |
| Video frames rendered on the car Surface (output = Android Auto) | **Unverified**: needs the phone's output switch, and adb tap injection is blocked on this phone |
| Android Studio GUI release run/install | **Unverified**; only the equivalent Gradle command was run |
| Phone restart then reconnect | **Unverified** |
| Real car head unit (not DHU) | **Unverified** |

## 11. Limitations

* Case 1 (Android Studio release APK absent from the launcher) was **not reproduced**. Here the
  Studio-equivalent APK is identical to the Flutter one, and the app is listed in the launcher. The most
  likely remaining causes are a different signing key (reinstall/uninstall) or Android Auto's launcher
  list not refreshed after install. The identity/log lines will show which in the field.
* The stale-surface failure was reproduced on install-over-live-session. The "old UI on every second
  reconnect" pattern on a real car was not reproduced on the DHU; the recovery covers the same failure
  mode (surface not takeable), and the ownership fix covers overlapping sessions, but this is not
  confirmed on a real vehicle.
* The stale frame and the fresh frame look identical when the UI revision is unchanged, so visual
  confirmation was done through the logs (`renderer ready`), not by eye.
