package com.example.iptv_native

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

/**
 * The app on the car display (Android Auto).
 *
 * Screens: the home screen is the channel browser (All channels / Categories / Countries /
 * Favorites / Recent / Playlists). Picking a channel opens the player screen; its list button
 * returns to wherever the user was browsing, so another channel can be picked at any time.
 *
 * Two jobs, both on top of the EXISTING ExoPlayer in [IptvPlaybackService] (no second player):
 *  1. Output: if the user chose "Android Auto" as the video output (phone player screen), the
 *     host-owned car Surface is handed to the player ([IptvPlaybackService.setCarSurface]).
 *     If they chose "Phone", the car Surface shows only the channel artwork and controls and
 *     the video stays on the phone. Both read the same shared setting ([IptvStorage.outputMode]).
 *  2. Channel browsing: a channel browser built from ListTemplate screens. It reads the very same
 *     MediaLibrarySession tree that Android Auto's media center uses (MediaBrowser.getChildren)
 *     and plays a picked channel through the same session (setMediaItem -> onSetMediaItems),
 *     so queue, Next/Previous, recents and favorites behave identically in both places.
 *
 * A third section, the car Browser, shows a real WebView (see [CarWebBrowser]); the home screen is a
 * TabTemplate (IPTV / Local Video / Browser) on hosts that support it (Car API level 6+).
 *
 * NOTE: the Surface comes from the NAVIGATION category, which is outside Google Play's car-app
 * policy; use for personal / sideloaded builds. Video is shown only while the car reports speed ~0
 * (unknown speed hides video except in debuggable builds or when the user enables
 * "Allow video when speed is unavailable" in the phone settings).
 */
class CarVideoService : CarAppService() {
    /**
     * Release builds only accept Google's Android Auto / Automotive hosts (the allow-list shipped with
     * the Car App Library). Debuggable builds accept any host, because the Desktop Head Unit is not
     * signed like a production host.
     */
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        AaLog.i("service", "createHostValidator debuggable=$debuggable -> ${if (debuggable) "ALLOW_ALL_HOSTS" else "hosts_allowlist_sample"}")
        return if (debuggable) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        AaLog.logIdentity(this, "CarVideoService.onCreate")
        AaLog.i("service", "CarVideoService created (activeSessions=${CarVideoSession.active.get()})")
    }

    override fun onDestroy() {
        AaLog.i("service", "CarVideoService destroyed (activeSessions=${CarVideoSession.active.get()})")
        super.onDestroy()
    }

    override fun onCreateSession(): Session {
        AaLog.i("service", "onCreateSession (activeSessions=${CarVideoSession.active.get()})")
        return CarVideoSession()
    }
}

private class CarVideoSession : Session() {
    private val sid = counter.incrementAndGet()
    private fun log(m: String) = AaLog.i("session#$sid", m)
    private fun templateLog(screenName: String, type: String) =
        AaLog.i("session#$sid", "onGetTemplate screen=$screenName -> $type (top=${topName()} stack=${stackNames()})")
    // Explicit names: release builds are minified, javaClass.simpleName would print "ye".
    private fun screenName(s: Screen): String = when (s) {
        is WebScreen -> "WebScreen"
        is VideoScreen -> "VideoScreen"
        is TabsScreen -> "TabsScreen(old-list-ui)"
        is BrowseScreen -> "BrowseScreen(old-list-ui)"
        is BrowserHomeScreen -> "BrowserHomeScreen(old-list-ui)"
        is ChannelSearchScreen -> "ChannelSearchScreen"
        is SearchScreen -> "SearchScreen"
        else -> "Screen"
    }
    private fun topName() = try { screenName(carContext.getCarService(androidx.car.app.ScreenManager::class.java).top) } catch (_: Throwable) { "?" }
    private fun stackNames() = try { carContext.getCarService(androidx.car.app.ScreenManager::class.java).screenStack.joinToString(">") { screenName(it) } } catch (_: Throwable) { "?" }
    private var browser: MediaBrowser? = null
    /** GL owner of the host Surface (video and artwork both go through it). */
    private var renderer: CarVideoRenderer? = null
    /** Unobscured part of the Surface, reported live by the host (shrinks when a secondary pane is shown). */
    private var visibleArea: android.graphics.Rect? = null
    private var attached = false
    /** true = moving, false = stopped, null = unknown. */
    private var moving: Boolean? = null
    private var screen: VideoScreen? = null
    private var speedInfo: CarInfo? = null
    private val browseScreens = mutableListOf<BrowseScreen>()
    private val artwork = CarArtwork()

    // ---- Car browser ----
    private val webBrowser by lazy { CarWebBrowser(carContext) { onWebChanged() } }
    private val browserUi by lazy { CarBrowserUi(carContext) }
    /** The web screen is the top screen (its NavigationTemplate surface is the page). */
    private var webActive = false
    /** The browser home (search, popular sites, history) is shown instead of the page, like the phone's Browser tab. */
    private var webHome = false
    private var homeScroll = 0f
    private var iconsLoaded = 0
    /** Which of the phone's three tabs the car shell shows (CarShellUi.NAV_*). */
    private var shellTab = CarShellUi.NAV_IPTV
    private var shellOk = false
    private var shellVersion = 0
    private var iptvSearch = ""
    private var chipScroll = 0f
    private val listScroll = floatArrayOf(0f, 0f)
    private var shellCacheKey = ""
    private var shellCache: List<IptvStorage.Channel> = emptyList()
    private val shellUi by lazy { CarShellUi(carContext) }
    private val localMeta by lazy { LocalVideoMeta(carContext) { uiHandler.post { metaTick++; overlayKey = ""; if (webActive) updateOverlay() } } }
    private val homeUi by lazy { CarBrowserHomeUi(carContext) }
    private var webAttachTried = false
    private var tabs: TabsScreen? = null
    private val tabsSupported get() = carContext.carAppApiLevel >= 6

    /** Phone -> car: [CarBrowserBridge.send] ends up here. */
    private val carSide = object : CarBrowserBridge.CarSide {
        override fun openUrl(url: String) { uiHandler.post { loadInCar(url) } }
        override fun syncUrl(url: String) { uiHandler.post { webBrowser.load(url) } } // no screen change, no reload if already there
    }

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val v = speed.rawSpeedMetersPerSecond
        val value = v.value
        moving = if (v.status == CarValue.STATUS_SUCCESS && value != null) value > 0.5f else null
        sync()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            AaLog.i("session#$sid", "surface available ${surfaceContainer.width}x${surfaceContainer.height} webActive=$webActive")
            dropRenderer()
            uiHandler.removeCallbacks(rendererRetry)
            if (ownSurfaceRequest) ownSurfaceRequest = false else rendererAttempts = 0 // our own reset must not restart the retry budget
            hostSurface = surfaceContainer.surface
            createRenderer("surfaceAvailable")
        }

        override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) {
            this@CarVideoSession.visibleArea = android.graphics.Rect(visibleArea)
            areaChanged()
        }

        override fun onClick(x: Float, y: Float) = onUiClick(x, y)

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (!webActive) return
            if (shellTab != CarShellUi.NAV_BROWSER) {
                if (shellTab == CarShellUi.NAV_IPTV && Math.abs(distanceX) > Math.abs(distanceY) * 1.2f) {
                    chipScroll = (chipScroll + distanceX).coerceIn(0f, shellUi.maxChipScroll)
                } else {
                    stopFling()
                    listScroll[shellTab] = (listScroll[shellTab] + distanceY).coerceIn(0f, shellUi.maxScroll)
                }
                updateOverlay()
            } else if (webHome) {
                homeScroll = (homeScroll + distanceY).coerceIn(0f, homeUi.maxScroll)
                updateOverlay()
            } else webBrowser.scroll(distanceX, distanceY)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            if (!webActive) return
            if (shellTab != CarShellUi.NAV_BROWSER) startFling(-velocityY)
            else if (!webHome) webBrowser.fling(velocityX, velocityY)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            AaLog.i("session#$sid", "surface destroyed")
            uiHandler.removeCallbacks(rendererRetry)
            hostSurface = null
            dropRenderer()
            sync()
        }
    }

    /**
     * The visible area the host reported (it shrinks when a secondary pane is shown), else the whole
     * Surface (null). The stable area is deliberately ignored: it also excludes the action strip, and
     * our controls overlay the picture. The renderer also keeps the full height (see CarVideoRenderer.clampedArea).
     */
    private fun effectiveArea() = visibleArea?.takeIf { !it.isEmpty }

    /** A pane was shown / hidden: the picture re-lays out inside the new area; the player is untouched. */
    private fun areaChanged() {
        val r = renderer ?: return
        r.setVisibleArea(effectiveArea())
        if (!attached) drawArtwork(r) // artwork re-centres in the uncovered region
        updateOverlay()
    }

    // ---- GL owner of the host Surface ----
    /** Host Surface from the last onSurfaceAvailable (valid until onSurfaceDestroyed). */
    private var hostSurface: Surface? = null
    private var rendererAttempts = 0
    private var ownSurfaceRequest = false
    private val rendererRetry = Runnable { createRenderer("retry#$rendererAttempts") }

    /**
     * Creates the GL renderer for the host Surface. Right after an APK replace, a process restart or a
     * quick reconnect the Surface can still be connected to the PREVIOUS process / session (EGL_BAD_ALLOC,
     * "already connected to another API"). Giving up there leaves the host showing the last frame of that
     * earlier instance - i.e. the OLD UI - so creation is retried until the old producer is gone.
     */
    private fun createRenderer(origin: String) {
        val surf = hostSurface ?: return
        if (renderer != null) return
        val r = CarVideoRenderer(surf)
        if (r.inputSurface != null) {
            renderer = r
            AaLog.i("session#$sid", "renderer ready ($origin, attempts=$rendererAttempts)")
            rendererAttempts = 0
            r.setVisibleArea(effectiveArea()) // the area callback may have arrived first
            overlayKey = ""
            sync()
            return
        }
        r.release()
        val delays = RENDERER_RETRY_MS
        // The old surface may never be released by its previous owner: after a couple of quick retries
        // reset the callback so the host destroys it and delivers a brand-new Surface.
        // 2nd failure: ask the host to bring this app forward again (what a manual re-open does);
        // 6th: reset the surface callback.
        if (rendererAttempts == 2) bringToFront()
        if (rendererAttempts == 6) requestFreshSurface()
        if (rendererAttempts < delays.size) {
            val d = delays[rendererAttempts++]
            if (rendererAttempts == 1) AaLog.snapshotSystemLog("renderer NOT ready")
            AaLog.w("session#$sid", "renderer NOT ready ($origin): host Surface still held by a previous instance -> the car would keep showing its stale frame; retry in ${d}ms (attempt $rendererAttempts/${delays.size})")
            uiHandler.postDelayed(rendererRetry, d)
        } else {
            AaLog.e("session#$sid", "renderer NOT ready after ${delays.size} retries; asking the host for a fresh surface")
            requestFreshSurface()
        }
    }

    private fun requestFreshSurface() {
        AaLog.w("session#$sid", "requesting a fresh host surface (setSurfaceCallback reset)")
        try {
            ownSurfaceRequest = true
            val am = carContext.getCarService(AppManager::class.java)
            am.setSurfaceCallback(null)
            am.setSurfaceCallback(surfaceCallback)
        } catch (t: Throwable) { AaLog.e("session#$sid", "fresh surface request failed", t) }
    }

    private fun dropRenderer() {
        webBrowser.detach()
        webAttachTried = false
        if (attached) { IptvPlaybackService.clearCarSurface(renderer); attached = false }
        renderer?.release()
        renderer = null
    }

    /** Shared setting: where video is rendered. Changes arrive from the phone UI in real time. */
    private fun outputIsCar() = IptvStorage.outputMode(carContext) == IptvStorage.OUTPUT_CAR

    private fun videoAllowed(): Boolean = when (moving) {
        false -> true
        true -> false
        // Speed unknown: debuggable builds (Desktop Head Unit) or the user's opt-in setting.
        null -> carContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
            IptvStorage.allowUnknownSpeed(carContext)
    }

    /** Single place that decides whether the car renders the video or the artwork. */
    private fun sync() {
        screen?.invalidate()
        val r = renderer
        if (r != null && !webActive && outputIsCar() && videoAllowed()) {
            if (!attached) {
                // Same Surface, same renderer: only the player's output changes, nothing else.
                IptvPlaybackService.setCarSurface(r)
                attached = true
                log("video initialised: player output attached to car surface (outputIsCar=${outputIsCar()} moving=$moving)")
            }
        } else if (attached) {
            IptvPlaybackService.clearCarSurface(r)
            attached = false
        }
        if (r != null) {
            if (webActive) syncWeb(r)
            else {
                if (webBrowser.attached) { webBrowser.detach(); webAttachTried = false } // page is kept for later
                if (!attached) drawArtwork(r)
            }
        }
        updateOverlay()
    }

    /** Browser on screen: show the page when parked, otherwise a black screen with the driving message. */
    private fun syncWeb(r: CarVideoRenderer) {
        AaLog.i("session#$sid", "syncWeb allowed=${videoAllowed()} attached=${webBrowser.attached} tried=$webAttachTried")
        if (shellTab != CarShellUi.NAV_BROWSER || webHome) {
            // IPTV / Local Video / the browser home are drawn screens: no WebView runs (page + history are kept for later).
            if (webBrowser.attached) { webBrowser.detach(); webAttachTried = false }
            r.showArtwork(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) })
            return
        }
        if (videoAllowed()) {
            if (!webBrowser.attached && !webAttachTried) {
                webAttachTried = true
                val (aw, ah) = r.areaSize()
                val ok = webBrowser.attach(r, aw, ah, CarBrowserUi.barHeight(ah))
                log("browser initialised: attach=$ok area=${aw}x$ah")
                if (ok) { r.showWeb(aw, ah); onWebChanged() }
            }
        } else {
            if (webBrowser.attached) { webBrowser.detach(); webAttachTried = false }
            r.showArtwork(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) })
        }
    }

    private fun onWebChanged() {
        updateOverlay()
        CarBrowserBridge.publish(CarBrowserBridge.State(webActive && webBrowser.attached, webBrowser.url, webBrowser.title, webBrowser.loading))
    }

    /** Loads [url] in the car browser and brings the browser screen to the front. */
    private fun loadInCar(url: String) {
        AaLog.i("session#$sid", "loadInCar (screens=${carContext.getCarService(androidx.car.app.ScreenManager::class.java).screenStack.size}, renderer=${renderer != null})")
        webBrowser.load(url)
        openWeb(home = false)
        if (renderer == null) bringToFront()
    }

    /** The car is showing something else (or a frozen frame of an earlier session): ask the host to bring this app forward. */
    private fun bringToFront() {
        try {
            carContext.startCarApp(
                Intent(Intent.ACTION_VIEW).setComponent(ComponentName(carContext, CarVideoService::class.java)),
            )
            AaLog.i("session#$sid", "startCarApp requested")
        } catch (t: Throwable) {
            AaLog.w("session#$sid", "startCarApp failed: ${t.javaClass.simpleName}")
        }
    }

    private fun openWeb(home: Boolean = false) {
        if (shellTab != CarShellUi.NAV_BROWSER) { shellTab = CarShellUi.NAV_BROWSER; overlayKey = ""; sync() }
        if (webHome != home) { webHome = home; homeScroll = 0f; overlayKey = ""; sync() }
        val sm = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        log("navigate openWeb(home=$home) stack=${stackNames()}")
        if (sm.screenStack.any { it.marker == WEB_MARKER }) sm.popTo(WEB_MARKER)
        else sm.push(WebScreen(carContext).also { it.marker = WEB_MARKER })
    }

    private fun openSearch() {
        carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(SearchScreen(carContext))
    }

    /** What the user needs to know right now, or null when video / artwork is simply showing. */
    private fun statusMessage(): Pair<String, String>? {
        val c = browser ?: return "Connecting…" to "Starting the player"
        val name = c.currentMediaItem?.mediaMetadata?.title?.toString()
        val onCar = outputIsCar()
        return when {
            c.playerError != null ->
                "Can't play ${name ?: "this channel"}" to
                    ((c.playerError?.errorCodeName ?: "Stream error") + " – try Next or pick another channel")
            c.mediaItemCount == 0 ->
                "No channel playing" to "Tap the list button to choose a channel"
            onCar && moving == true -> "Video paused while driving" to "Audio keeps playing"
            onCar && moving == null && !videoAllowed() -> "Video unavailable" to "Vehicle speed is not available – audio only"
            c.playbackState == Player.STATE_BUFFERING -> "Buffering…" to (name ?: "")
            else -> null
        }
    }


    private var overlayKey = ""
    private var uiVisible = true
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideUi = Runnable { uiVisible = false; updateOverlay() }

    /** Controls fade out 3 s after the last touch. */
    private fun scheduleHide() {
        uiHandler.removeCallbacks(hideUi)
        uiHandler.postDelayed(hideUi, 3_000)
    }
    private val ui by lazy { CarPlayerUi(carContext) }

    /** IPTV channel id (null for local videos: no favorites). */
    private fun currentChannelId() =
        browser?.currentMediaItem?.mediaId?.takeIf { !IptvPlaybackService.isLocal(it) }
            ?.let(IptvPlaybackService::parseChannelId)

    private fun currentIsLocal() = IptvPlaybackService.isLocal(browser?.currentMediaItem?.mediaId)

    /** Local video progress needs a once-a-second redraw while it plays and the controls show. */
    private val progressTick = object : Runnable {
        override fun run() {
            if (uiVisible && currentIsLocal() && browser?.isPlaying == true) updateOverlay()
            uiHandler.postDelayed(this, 1_000)
        }
    }

    /** Draws the player UI (same design as the phone's full screen) over the picture. */
    private fun updateOverlay() {
        val r = renderer ?: return
        if (webActive) { updateWebOverlay(r); return }
        val md = browser?.currentMediaItem?.mediaMetadata
        val id = currentChannelId()
        val st = CarPlayerUi.State(
            title = md?.title?.toString(),
            playing = browser?.playWhenReady == true,
            favorite = id?.let { IptvStorage.isFavorite(carContext, it) },
            fill = IptvStorage.videoFit(carContext) == IptvStorage.FILL,
            banner = statusMessage(),
            visible = uiVisible,
            progress = if (currentIsLocal()) {
                val b = browser
                (b?.currentPosition ?: 0L).coerceAtLeast(0L) / 1000 to
                    (b?.duration?.takeIf { it != C.TIME_UNSET } ?: -1L).let { if (it > 0) it / 1000 else -1L }
            } else null,
        )
        val (aw, ah) = r.areaSize()
        val key = "$st|$aw|$ah"
        if (key == overlayKey) return
        overlayKey = key
        r.setOverlay(ui.render(aw, ah, st))
    }

    private fun updateWebOverlay(r: CarVideoRenderer) {
        if (shellTab != CarShellUi.NAV_BROWSER) { updateShellOverlay(r); return }
        val banner: Pair<String, String>? = when {
            !videoAllowed() -> if (moving == true) "Browser paused while driving" to "Available again when the car is parked"
                else "Browser unavailable" to "Vehicle speed is not available"
            webBrowser.error != null && !webBrowser.attached -> "Can't start the browser" to webBrowser.error!!
            webBrowser.error != null -> "Can't open this page" to webBrowser.error!!
            else -> null
        }
        if (webHome && videoAllowed()) { updateHomeOverlay(r); return }
        val st = CarBrowserUi.State(webBrowser.title, webBrowser.url, webBrowser.loading, webBrowser.progress,
            webBrowser.canBack, webBrowser.canForward, banner)
        val (aw, ah) = r.areaSize()
        val key = "web|$st|$aw|$ah"
        if (key == overlayKey) return
        overlayKey = key
        r.setOverlay(browserUi.render(aw, ah, st))
    }

    private fun updateHomeOverlay(r: CarVideoRenderer) {
        val prefs = try { carContext.getSharedPreferences("FlutterSharedPreferences", android.content.Context.MODE_PRIVATE) } catch (_: Throwable) { null }
        val reload = { uiHandler.post { iconsLoaded++; overlayKey = ""; if (webActive && webHome) updateOverlay() }; Unit }
        val sites = PopularSites.all.map { site ->
            val url = PopularSites.favicon(site.domain)
            artwork.thumb(site.name, url, onLoaded = reload) // starts the download once
            CarBrowserHomeUi.SiteTile(site, artwork.cachedThumb(url))
        }
        val cur = if (webBrowser.hasPage) CarBrowserHomeUi.Row(webBrowser.title, webBrowser.url) else null
        val history = BrowserHistory.list(carContext).filterNot { cur != null && WebUrls.same(it.url, cur.url) }
            .take(6).map { CarBrowserHomeUi.Row(it.title, it.url) }
        val st = CarBrowserHomeUi.State(sites, history, cur, prefs?.getBoolean("flutter.iptv.dark", false) ?: false,
            prefs?.getString("flutter.iptv.accent", null), iconsLoaded)
        val (aw, ah) = r.areaSize()
        val frame = shellFrame()
        val key = "home|$st|$aw|$ah|$homeScroll|${frame.palette.surface}"
        if (key == overlayKey) return
        overlayKey = key
        val inset = CarShellUi.navHeight(ah)
        val bmp = homeUi.render(aw, ah, homeScroll, st, inset)
        if (bmp != null) shellUi.drawChrome(Canvas(bmp), aw, ah, shellFrame(), withMini = false)
        r.setOverlay(bmp)
        homeScroll = homeScroll.coerceIn(0f, homeUi.maxScroll)
    }

    // ---- Phone-style IPTV / Local Video screens (drawn on the Surface) ----------------------------

    private fun prefs() = try { carContext.getSharedPreferences("FlutterSharedPreferences", android.content.Context.MODE_PRIVATE) } catch (_: Throwable) { null }

    private fun shellFrame(mini: Boolean = false): CarShellUi.Frame {
        val pr = prefs()
        val pal = CarPalette.of(pr?.getBoolean("flutter.iptv.dark", false) ?: false, pr?.getString("flutter.iptv.accent", null))
        val b = browser
        val md = b?.currentMediaItem?.mediaMetadata
        val miniState = if (b != null && b.mediaItemCount > 0 && md != null) {
            val local = currentIsLocal()
            val status = when {
                b.playerError != null -> "Playback error"
                b.playbackState == Player.STATE_BUFFERING -> "Buffering…"
                b.playWhenReady -> if (local) "Playing local video" else "Live"
                else -> "Paused"
            }
            val url = md.artworkUri?.toString()
            val logo = if (url != null && url.startsWith("http")) { artwork.thumb(md.title?.toString() ?: "", url, onLoaded = { iconReloaded() }); artwork.cachedThumb(url) } else null
            CarShellUi.Mini(md.title?.toString().orEmpty(), status, logo, b.playWhenReady)
        } else null
        val chs = IptvStorage.channels(carContext).size
        val status = if (shellTab == CarShellUi.NAV_LOCAL) "Local videos"
            else (IptvStorage.playlists(carContext).firstOrNull { it.active }?.name ?: "Playlist") + " · $chs channels"
        return CarShellUi.Frame(pal, "Car Play", status,
            if (shellTab == CarShellUi.NAV_LOCAL) R.drawable.ic_car_video else R.drawable.ic_car_tv, shellTab, if (mini) miniState else miniState)
    }

    private fun iconReloaded() { uiHandler.post { iconsLoaded++; overlayKey = ""; if (webActive && shellTab != CarShellUi.NAV_BROWSER) updateOverlay() } }

    /** The phone's current list: all | favorites | recents | cat:<group> (shared selection, so both sides follow each other). */
    private fun shellScope(): String {
        val sc = IptvStorage.selection(carContext).scope
        return if (sc == "favorites" || sc == "recents" || sc.startsWith("cat:")) sc else "all"
    }

    private fun shellChannels(scope: String): List<IptvStorage.Channel> {
        val key = "$scope|$iptvSearch|$shellVersion"
        if (key == shellCacheKey) return shellCache
        val all = IptvStorage.channels(carContext)
        var list = when {
            scope == "favorites" -> IptvStorage.favorites(carContext)
            scope == "recents" -> IptvStorage.recents(carContext)
            scope.startsWith("cat:") -> all.filter { it.group == scope.removePrefix("cat:") }
            else -> all
        }
        val q = iptvSearch.trim().lowercase()
        if (q.isNotEmpty()) list = list.filter { it.name.lowercase().contains(q) }
        shellCacheKey = key; shellCache = list
        return list
    }

    private fun shellChips(scope: String): List<CarShellUi.Chip> {
        val chips = ArrayList<CarShellUi.Chip>()
        chips.add(CarShellUi.Chip("All", "all", R.drawable.ic_car_tv, scope == "all"))
        chips.add(CarShellUi.Chip("Favorites (${IptvStorage.favorites(carContext).size})", "favorites", R.drawable.ic_car_fav, scope == "favorites"))
        chips.add(CarShellUi.Chip("Recent", "recents", R.drawable.ic_car_history, scope == "recents"))
        IptvStorage.groups(carContext).forEach { (g, n) -> chips.add(CarShellUi.Chip("$g ($n)", "cat:$g", R.drawable.ic_car_category, scope == "cat:$g")) }
        return chips
    }

    private fun updateShellOverlay(r: CarVideoRenderer) {
        val (aw, ah) = r.areaSize()
        val frame = shellFrame()
        val mini = frame.mini
        val bmp: Bitmap?
        val key: String
        val miniKey = mini?.let { "${it.title}|${it.status}|${it.playing}|${it.logo != null}" }
        if (shellTab == CarShellUi.NAV_LOCAL) {
            val vids = IptvStorage.localVideos(carContext)
            val cur = browser?.currentMediaItem?.mediaId?.let(IptvPlaybackService::parseChannelId)
            val playing = browser?.playWhenReady == true
            key = "local|$aw|$ah|${vids.size}|$shellVersion|${listScroll[1]}|$cur|$playing|$miniKey|${frame.palette.surface}|${frame.palette.primary}|$iconsLoaded|${localMetaVersion()}"
            if (key == overlayKey) return
            overlayKey = key
            bmp = shellUi.renderLocal(aw, ah, frame, CarShellUi.LocalContent(vids, cur, playing) { localMeta.get(it) }, listScroll[1])
            listScroll[1] = listScroll[1].coerceIn(0f, shellUi.maxScroll)
        } else {
            val scope = shellScope()
            val list = shellChannels(scope)
            val cur = browser?.currentMediaItem?.takeIf { !currentIsLocal() }?.mediaId?.let(IptvPlaybackService::parseChannelId)
            val playing = browser?.playWhenReady == true
            key = "iptv|$aw|$ah|$scope|$iptvSearch|${list.size}|$shellVersion|${listScroll[0]}|$chipScroll|$cur|$playing|$miniKey|${frame.palette.surface}|${frame.palette.primary}|$iconsLoaded"
            if (key == overlayKey) return
            overlayKey = key
            val favs = IptvStorage.favorites(carContext).map { it.id }.toHashSet()
            val total = IptvStorage.channels(carContext).size
            val empty = when {
                total == 0 -> "No channels yet\nOpen the app on your phone to load your playlist"
                list.isEmpty() && scope == "favorites" && iptvSearch.isBlank() -> "No favorites yet.\nTap the heart on a channel."
                list.isEmpty() && scope == "recents" && iptvSearch.isBlank() -> "Nothing watched yet."
                else -> null
            }
            val content = CarShellUi.IptvContent(shellChips(scope), iptvSearch, "Search $total channels…", list, favs, cur, playing, empty,
                scope == "recents") { ch ->
                if (ch.logo.startsWith("http")) { artwork.thumb(ch.name, ch.logo, onLoaded = { iconReloaded() }); artwork.cachedThumb(ch.logo) } else null
            }
            bmp = shellUi.renderIptv(aw, ah, frame, content, listScroll[0], chipScroll)
            listScroll[0] = listScroll[0].coerceIn(0f, shellUi.maxScroll)
            chipScroll = chipScroll.coerceIn(0f, shellUi.maxChipScroll)
        }
        r.setOverlay(bmp)
    }

    private var metaTick = 0
    private fun localMetaVersion() = metaTick

    private fun playFromShell(scope: String, ch: IptvStorage.Channel) {
        val b = browser ?: return
        val item = MediaItem.Builder().setMediaId("${IptvPlaybackService.CH_PREFIX}$scope|${ch.id}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).setIsPlayable(true).setIsBrowsable(false).build()).build()
        b.setMediaItem(item)
        b.prepare()
        b.play()
        openPlayer()
    }

    private fun switchTab(id: Int) {
        if (id == shellTab && (id != CarShellUi.NAV_BROWSER || webHome)) return
        stopFling()
        shellTab = id
        if (id == CarShellUi.NAV_BROWSER) { webHome = true; homeScroll = 0f }
        overlayKey = ""
        sync()
        onWebChanged()
    }

    private fun onShellHit(hit: CarShellUi.Hit, x: Float, y: Float) {
        val b = browser
        when (hit.kind) {
            CarShellUi.Kind.NAV -> switchTab(hit.index)
            CarShellUi.Kind.MINI -> openPlayer()
            CarShellUi.Kind.MINI_PLAY -> b?.let { if (it.playWhenReady) it.pause() else it.play() }
            CarShellUi.Kind.MINI_PREV -> b?.seekToPreviousMediaItem()
            CarShellUi.Kind.MINI_NEXT -> b?.seekToNextMediaItem()
            CarShellUi.Kind.CHIP -> {
                val chip = shellChips(shellScope()).getOrNull(hit.index) ?: return
                val label = when {
                    chip.scope == "all" -> "All channels"
                    chip.scope == "favorites" -> "Favorites"
                    chip.scope == "recents" -> "Recently watched"
                    else -> chip.scope.removePrefix("cat:")
                }
                IptvStorage.setSelection(carContext, chip.scope, label, "car")
                listScroll[0] = 0f; shellVersion++; overlayKey = ""
                updateOverlay()
            }
            CarShellUi.Kind.SEARCH -> screenManager().push(ChannelSearchScreen(carContext))
            CarShellUi.Kind.SEARCH_CLEAR -> { iptvSearch = ""; listScroll[0] = 0f; shellVersion++; overlayKey = ""; updateOverlay() }
            CarShellUi.Kind.CLEAR_RECENTS -> IptvStorage.clearRecents(carContext)
            CarShellUi.Kind.ROW -> if (shellTab == CarShellUi.NAV_LOCAL) {
                IptvStorage.localVideos(carContext).getOrNull(hit.index)?.let { playFromShell(IptvPlaybackService.LOCAL_SCOPE, it) }
            } else {
                val scope = shellScope()
                shellChannels(scope).getOrNull(hit.index)?.let {
                    playFromShell(if (iptvSearch.isNotBlank()) IptvPlaybackService.SEARCH_PREFIX + iptvSearch.trim() else scope, it)
                }
            }
            CarShellUi.Kind.FAV -> shellChannels(shellScope()).getOrNull(hit.index)?.let { IptvStorage.toggleFavorite(carContext, it.id) }
        }
    }

    private fun screenManager() = carContext.getCarService(androidx.car.app.ScreenManager::class.java)

    // Momentum scrolling for the drawn lists.
    private var flingV = 0f
    private val flingTick = object : Runnable {
        override fun run() {
            if (!webActive || shellTab == CarShellUi.NAV_BROWSER || Math.abs(flingV) < 40f) { flingV = 0f; return }
            val old = listScroll[shellTab]
            listScroll[shellTab] = (old + flingV * 0.016f).coerceIn(0f, shellUi.maxScroll)
            if (listScroll[shellTab] == old) { flingV = 0f; updateOverlay(); return }
            flingV *= 0.94f
            updateOverlay()
            uiHandler.postDelayed(this, 16)
        }
    }
    private fun startFling(v: Float) { uiHandler.removeCallbacks(flingTick); flingV = v; uiHandler.post(flingTick) }
    private fun stopFling() { flingV = 0f; uiHandler.removeCallbacks(flingTick) }

    private fun onHomeClick(x: Float, y: Float) {
        shellUi.hitTestChrome(x, y)?.let { onShellHit(it, x, y); return }
        val hit = homeUi.hitTest(x, y) ?: return
        val cur = if (webBrowser.hasPage) webBrowser.url else null
        when (hit.kind) {
            CarBrowserHomeUi.Kind.SEARCH -> openSearch()
            CarBrowserHomeUi.Kind.CURRENT -> { webHome = false; overlayKey = ""; sync(); onWebChanged() }
            CarBrowserHomeUi.Kind.SITE -> PopularSites.all.getOrNull(hit.index)?.let { openFromHome(it.url) }
            CarBrowserHomeUi.Kind.HISTORY -> BrowserHistory.list(carContext)
                .filterNot { cur != null && WebUrls.same(it.url, cur) }.take(6).getOrNull(hit.index)?.let { openFromHome(it.url) }
            CarBrowserHomeUi.Kind.CLEAR -> { BrowserHistory.clear(carContext); overlayKey = ""; updateOverlay() }
        }
    }

    /** Home: stop the page (loading, scripts, media, network) and show the browser home; the page is kept to resume. */
    private fun goBrowserHome() {
        webBrowser.stop()
        webHome = true; homeScroll = 0f; overlayKey = ""
        sync() // detaches the WebView (state saved)
        onWebChanged()
    }

    private fun openFromHome(url: String) {
        webHome = false; overlayKey = ""
        webBrowser.load(url)
        sync() // attaches the WebView
        onWebChanged()
    }

    private fun onWebClick(r: CarVideoRenderer, x: Float, y: Float) {
        val a = r.areaRect()
        val lx = x - a.left
        val ly = y - a.top
        if (shellTab != CarShellUi.NAV_BROWSER) { shellUi.hitTest(lx, ly)?.let { onShellHit(it, lx, ly) }; return }
        if (webHome && videoAllowed()) { onHomeClick(lx, ly); return }
        if (ly < CarBrowserUi.barHeight(a.height())) {
            when (browserUi.hitTest(lx, ly)) {
                CarBrowserUi.Hit.HOME -> goBrowserHome()
                CarBrowserUi.Hit.BACK -> webBrowser.back()
                CarBrowserUi.Hit.FORWARD -> webBrowser.forward()
                CarBrowserUi.Hit.RELOAD -> if (webBrowser.loading) webBrowser.stop() else webBrowser.reload()
                CarBrowserUi.Hit.URL -> openSearch()
                null -> {}
            }
        } else if (videoAllowed()) webBrowser.click(lx, ly)
    }

    private fun onUiClick(x: Float, y: Float) {
        val r = renderer ?: return
        if (webActive) { onWebClick(r, x, y); return }
        val a = r.areaRect()
        val b = browser
        if (!uiVisible) { uiVisible = true; scheduleHide(); updateOverlay(); return }
        ui.seekFraction(x - a.left, y - a.top)?.let { f ->
            val d = b?.duration ?: C.TIME_UNSET
            if (b != null && d != C.TIME_UNSET && d > 0) { scheduleHide(); b.seekTo((d * f).toLong()); updateOverlay() }
            return
        }
        val hit = ui.hitTest(x - a.left, y - a.top)
        if (hit == null) { uiHandler.removeCallbacks(hideUi); uiVisible = false; updateOverlay(); return } // tap on the picture hides the UI
        scheduleHide()
        when (hit) {
            CarPlayerUi.Hit.PREV -> b?.seekToPreviousMediaItem()
            CarPlayerUi.Hit.NEXT -> b?.seekToNextMediaItem()
            CarPlayerUi.Hit.SEEK -> {}
            CarPlayerUi.Hit.PLAY -> b?.let { if (it.playWhenReady) it.pause() else it.play() }
            CarPlayerUi.Hit.FIT -> IptvStorage.setVideoFit(
                carContext,
                if (IptvStorage.videoFit(carContext) == IptvStorage.FILL) IptvStorage.FIT else IptvStorage.FILL,
            )
            CarPlayerUi.Hit.FAVORITE ->
                b?.sendCustomCommand(SessionCommand(IptvPlaybackService.CMD_TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
        }
    }

    /** Artwork + channel name on the car surface while the video plays on the phone. */
    private fun drawArtwork(r: CarVideoRenderer) {
        if (webActive) return // the browser owns the picture
        val md = browser?.currentMediaItem?.mediaMetadata
        val url = md?.artworkUri?.toString()
        val (aw, ah) = r.areaSize()
        artwork.render(
            aw, ah,
            md?.title?.toString() ?: "No channel playing",
            md?.artist?.toString().orEmpty(),
            url,
            onLoaded = { ContextCompat.getMainExecutor(carContext).execute { renderer?.let { if (!attached) drawArtwork(it) } } },
        )?.let { r.showArtwork(it) }
    }

    private fun icon(res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    /**
     * The phone app's accent (Settings > Accent) as the Material 3 "primary" for a dark surface, so
     * highlights in the car match the phone. The phone stores it in Flutter's shared preferences;
     * it is read on every template build, so a change shows up on the next refresh.
     */
    private fun accent(): CarColor {
        val name = try {
            carContext.getSharedPreferences("FlutterSharedPreferences", android.content.Context.MODE_PRIVATE)
                .getString("flutter.iptv.accent", null)
        } catch (_: Throwable) { null }
        val argb = when (name) {
            "blue" -> 0xFFAAC7FF
            "red" -> 0xFFFFB4AB
            "green" -> 0xFF78DC77
            "purple" -> 0xFFDDB8FF
            "orange" -> 0xFFFFB68A
            else -> 0xFFCFBCFF // deepPurple (default seed)
        }.toInt()
        return CarColor.createCustom(argb, argb)
    }

    /** Text spans only accept the host's standard colors (custom ones crash the template): nearest match. */
    private fun textAccent(): CarColor = when (
        try {
            carContext.getSharedPreferences("FlutterSharedPreferences", android.content.Context.MODE_PRIVATE)
                .getString("flutter.iptv.accent", null)
        } catch (_: Throwable) { null }
    ) {
        "blue" -> CarColor.BLUE
        "red" -> CarColor.RED
        "green" -> CarColor.GREEN
        else -> CarColor.PRIMARY
    }

    /** Accent-tinted glyph for list rows / header actions. */
    private fun accentIcon(res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(accent()).build()

    /** Shared data changed (phone or car): settings re-route video, lists reload in place. */
    private val changeListener: (String) -> Unit = { kind ->
        ContextCompat.getMainExecutor(carContext).execute {
            shellVersion++
            overlayKey = ""
            if (kind == "settings") {
                IptvPlaybackService.setFill(IptvStorage.videoFit(carContext) == IptvStorage.FILL)
                sync()
            }
            updateOverlay()
            if (kind == "data" || kind == "settings") {
                browseScreens.forEach { it.refresh() }
                screen?.invalidate()
            }
        }
    }

    /** The phone (or the car) changed the browser history: redraw the car Browser list when it is on screen. */
    private val historyListener: () -> Unit = {
        ContextCompat.getMainExecutor(carContext).execute {
            if (historyPending) return@execute
            historyPending = true
            uiHandler.postDelayed({
                historyPending = false
                // The Browser list lives on the tabs screen or the plain browser screen, not on `screen`.
                try { tabs?.invalidate() } catch (_: Throwable) {}
                try { browserHome?.invalidate() } catch (_: Throwable) {}
            }, 250)
        }
    }
    private var historyPending = false
    private var browserHome: Screen? = null

    override fun onNewIntent(intent: Intent) {
        log("onNewIntent action=${intent.action} data=${intent.dataString} stack=${stackNames()}")
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val ctx = carContext
        active.incrementAndGet()
        AaLog.logIdentity(ctx, "session#$sid.onCreateScreen")
        log("onCreateScreen intent=${intent.action} carApiLevel=${ctx.carAppApiLevel} activeSessions=${active.get()} carUiRevision=${AaLog.CAR_UI_REVISION}")
        try {
            val h = ctx.hostInfo
            log("host=${h?.packageName} uid=${h?.uid} hostApi=${ctx.carAppApiLevel} isDarkCar=${ctx.isDarkMode} " +
                "display=${ctx.resources.displayMetrics.widthPixels}x${ctx.resources.displayMetrics.heightPixels}@${ctx.resources.displayMetrics.densityDpi}dpi")
        } catch (t: Throwable) { log("host info unavailable: ${t.javaClass.simpleName}") }
        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                log("lifecycle $event (stack=${stackNames()})")
            }
        })
        // Connecting also starts the playback service when the phone UI is not running.
        val token = SessionToken(ctx, ComponentName(ctx, IptvPlaybackService::class.java))
        val future = MediaBrowser.Builder(ctx, token).buildAsync()
        future.addListener({
            browser = try { future.get() } catch (t: Exception) { AaLog.e("session#$sid", "MediaBrowser connect failed", t); null }
            log("MediaBrowser connected=${browser != null}")
            browser?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    invalidateTop()
                    if (!attached) renderer?.let { drawArtwork(it) }
                    updateOverlay()
                }
            })
            sync()
            invalidateTop() // lists that were waiting for the connection can load now
        }, ContextCompat.getMainExecutor(ctx))

        try {
            ctx.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            shellOk = true
            log("setSurfaceCallback OK -> shell UI (WebScreen) will be the home screen")
        } catch (t: Throwable) {
            /* ACCESS_SURFACE not granted: no video, browsing still works */
            AaLog.e("session#$sid", "setSurfaceCallback FAILED -> FALLBACK to the old list UI", t)
        }

        try {
            val info = ctx.getCarService(CarHardwareManager::class.java).carInfo
            speedInfo = info
            info.addSpeedListener(ContextCompat.getMainExecutor(ctx), speedListener)
        } catch (_: Throwable) { moving = null }

        log("config loaded: output=${IptvStorage.outputMode(ctx)} fit=${IptvStorage.videoFit(ctx)} allowUnknownSpeed=${IptvStorage.allowUnknownSpeed(ctx)} speedKnown=${moving != null} browserLastPage=${CarBrowserBridge.lastPage}")
        IptvPlaybackService.setFill(IptvStorage.videoFit(ctx) == IptvStorage.FILL)
        IptvStorage.addChangeListener(changeListener)
        BrowserHistory.addListener(historyListener)
        uiHandler.postDelayed(progressTick, 1_000)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                uiHandler.removeCallbacks(rendererRetry)
                hostSurface = null
                active.decrementAndGet()
                log("session destroyed (activeSessions=${active.get()})")
                IptvStorage.removeChangeListener(changeListener)
                BrowserHistory.removeListener(historyListener)
                uiHandler.removeCallbacks(hideUi)
                uiHandler.removeCallbacks(progressTick)
                try { speedInfo?.removeSpeedListener(speedListener) } catch (_: Throwable) {}
                CarBrowserBridge.detach(carSide)
                dropRenderer()
                webBrowser.destroy()
                browser?.release()
                browser = null
            }
        })
        // Phone -> car: a URL sent before this session existed is delivered right away.
        CarBrowserBridge.attach(carSide)
        // Home screen: IPTV / Local Video / Browser tabs; older hosts fall back to the channel list.
        val first: Screen = if (shellOk) WebScreen(ctx).also { it.marker = WEB_MARKER } // phone-style IPTV / Local Video / Browser, drawn on the Surface
        else if (tabsSupported) TabsScreen(ctx).also { tabs = it }
        else BrowseScreen(ctx, IptvPlaybackService.HOME, "IPTV", isRoot = true)
        if (first !is WebScreen) AaLog.snapshotSystemLog("fallback to old UI")
        AaLog.w("session#$sid", "FALLBACK to older screen ${screenName(first)} (shellOk=$shellOk tabsSupported=$tabsSupported carApi=${ctx.carAppApiLevel}); will retry the surface on start")
        log("initial screen = ${screenName(first)}")
        // The old list UI is only a last resort: if the host refused the surface at creation, ask again
        // once the session is visible and, when granted, replace the old root with the shell UI.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { if (!shellOk) upgradeToShell(first) }
        })
        return first
    }

    /** Retries the surface request; on success the old list UI root is replaced by the shell (WebScreen). */
    private fun upgradeToShell(oldRoot: Screen) {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        } catch (t: Throwable) {
            AaLog.e("session#$sid", "surface retry FAILED - staying on the old list UI", t)
            return
        }
        shellOk = true
        log("surface retry OK -> replacing old list UI with WebScreen")
        val sm = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        sm.push(WebScreen(carContext).also { it.marker = WEB_MARKER })
        try { sm.remove(oldRoot) } catch (t: Throwable) { AaLog.w("session#$sid", "could not remove old root: ${t.javaClass.simpleName}") }
    }

    private fun invalidateTop() {
        try { carContext.getCarService(androidx.car.app.ScreenManager::class.java).top.invalidate() }
        catch (_: Throwable) { screen?.invalidate() }
    }

    /** Opens the player screen, or returns to it if it is already in the stack. */
    private fun openPlayer() {
        uiVisible = true
        scheduleHide()
        val sm = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        log("navigate openPlayer stack=${stackNames()}")
        if (sm.screenStack.any { it.marker == PLAYER_MARKER }) sm.popTo(PLAYER_MARKER)
        else sm.push(VideoScreen(carContext).also { it.marker = PLAYER_MARKER; screen = it })
    }

    // ---- Player screen --------------------------------------------------------------

    private inner class VideoScreen(ctx: CarContext) : Screen(ctx) {
        override fun onGetTemplate(): Template {
            templateLog("VideoScreen", "NavigationTemplate")
            // Everything else (title, favorite, fit/fill, LIVE bar, prev / play / next) is drawn over
            // the video by CarPlayerUi and handled in onClick. The host strip keeps one entry
            // (back to the list) as the required action and as the non-touch fallback.
            val builder = NavigationTemplate.Builder()
                .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_close))
                            .setOnClickListener { screenManager.pop() }.build())
                        .build()
                )
            updateOverlay()
            return builder.build()
        }
    }

    // ---- Channel browser ------------------------------------------------------------

    /**
     * One level of the MediaLibrarySession tree as a list. Folders open another BrowseScreen;
     * a channel starts playing and opens the player screen; rows for empty/info states
     * are plain text. Long lists are paged to the host's list limit. Lists reload in place
     * when favorites / recents / the playlist change anywhere (see [changeListener]).
     */
    private inner class BrowseScreen(
        ctx: CarContext,
        private val parentId: String,
        private val title: String,
        private val isRoot: Boolean = false,
        /** Content of a TabTemplate tab: no title / header, and [host] is invalidated with it. */
        private val tabContent: Boolean = false,
        private val host: Screen? = null,
    ) : Screen(ctx) {
        private fun redraw() { invalidate(); host?.invalidate() }

        private var page = 0
        private var items: List<MediaItem>? = null
        private var hasNext = false
        private var failed = false
        private var requested = false

        init {
            browseScreens.add(this)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) { browseScreens.remove(this@BrowseScreen) }
            })
        }

        // Reserve rows for "Now playing", the favorite toggle and "Next page".
        private val pageSize: Int = (try {
            ctx.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (_: Throwable) { 6 } - 3).coerceIn(3, 48)

        private var thumbPending = false

        /** Logos arrive one by one: redraw the list at most every 400 ms. */
        private fun thumbLoaded() {
            ContextCompat.getMainExecutor(carContext).execute {
                if (thumbPending) return@execute
                thumbPending = true
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    thumbPending = false
                    try { redraw() } catch (_: Throwable) {}
                }, 400)
            }
        }

        private fun reload() { items = null; failed = false; requested = false; redraw() }

        /** Re-fetch but keep showing the current rows until the new ones arrive (no flicker). */
        fun refresh() { requested = false; redraw() }

        private fun load(b: MediaBrowser) {
            requested = true
            val ex = ContextCompat.getMainExecutor(carContext)
            val f = b.getChildren(parentId, page, pageSize, null)
            f.addListener({
                val r: LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>? =
                    try { f.get() } catch (_: Exception) { null }
                if (r == null || r.resultCode != LibraryResult.RESULT_SUCCESS) {
                    failed = true; redraw(); return@addListener
                }
                val list = r.value ?: com.google.common.collect.ImmutableList.of()
                items = list
                if (list.size < pageSize) { hasNext = false; redraw(); return@addListener }
                // Full page: peek at the next one to know whether a "Next page" row is needed.
                val g = b.getChildren(parentId, page + 1, pageSize, null)
                g.addListener({
                    hasNext = try { g.get().value?.isNotEmpty() == true } catch (_: Exception) { false }
                    redraw()
                }, ex)
            }, ex)
        }

        private fun play(item: MediaItem) {
            val b = browser ?: return
            // Only the id + metadata: the service expands it to the list it came from.
            b.setMediaItem(item.buildUpon().setUri(null as Uri?).build())
            b.prepare()
            b.play()
            openPlayer()
        }

        private fun toggleFavorite() {
            // Stay on the list: it reloads itself through the shared change notification.
            browser?.sendCustomCommand(SessionCommand(IptvPlaybackService.CMD_TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
        }

        /** Icon for a folder row, matching the phone's chips / menus. */
        private fun folderImage(item: MediaItem, name: String): CarIcon {
            val id = item.mediaId
            val P = IptvPlaybackService
            val res = when {
                id == P.FAVORITES -> R.drawable.ic_car_fav
                id == P.RECENTS -> R.drawable.ic_car_history
                id == P.CATEGORIES || id.startsWith(P.CAT_PREFIX) -> R.drawable.ic_car_category
                id == P.COUNTRIES || id.startsWith(P.COUNTRY_PREFIX) -> R.drawable.ic_car_country
                id == P.PLAYLISTS || id == P.PLAYLIST_ACTIVE -> R.drawable.ic_car_playlist
                id.startsWith(P.AZ_PREFIX) ->
                    return CarIcon.Builder(IconCompat.createWithBitmap(artwork.thumb(id.removePrefix(P.AZ_PREFIX), null) {})).build()
                else -> R.drawable.ic_car_tv
            }
            return accentIcon(res)
        }

        private fun row(item: MediaItem, toPlayer: Boolean = false): Row {
            val md = item.mediaMetadata
            val name = md.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId
            val id = item.mediaId.let(IptvPlaybackService::parseChannelId)
            val playingId = browser?.currentMediaItem?.mediaId?.let(IptvPlaybackService::parseChannelId)
            val current = md.isPlayable == true && id != null && id == playingId
            val fav = md.isPlayable == true && id != null && !IptvPlaybackService.isLocal(item.mediaId) &&
                IptvStorage.isFavorite(carContext, id)
            // Plain text only: this host rejects colored spans in row text (it crashed the template).
            val styled = (if (current) "▶  " else "") + name + (if (fav) "  ♥" else "")
            val r = Row.Builder().setTitle(styled)
            if (md.isPlayable == true) {
                val bmp = artwork.thumb(name, md.artworkUri?.toString(), onLoaded = { thumbLoaded() })
                r.setImage(CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build(), Row.IMAGE_TYPE_LARGE)
            } else if (md.isBrowsable == true) {
                r.setImage(folderImage(item, name), Row.IMAGE_TYPE_ICON)
            }
            val sub = md.subtitle?.toString()?.takeIf { it.isNotBlank() }
            if (current) {
                r.addText(if (sub != null) "Now playing · $sub" else "Now playing")
            } else sub?.let { r.addText(it) }
            when {
                toPlayer -> r.setBrowsable(true).setOnClickListener { openPlayer() }
                md.isBrowsable == true -> r.setBrowsable(true).setOnClickListener {
                    screenManager.push(BrowseScreen(carContext, item.mediaId, md.title?.toString() ?: title))
                }
                md.isPlayable == true -> r.setOnClickListener { play(item) }
                // else: explanatory row ("No favorites yet", ...) with no action
            }
            return r.build()
        }

        /** Header actions: jump back to the player (any list) and favorite the playing channel (top level). */
        private fun actionStrip(playingId: String?): ActionStrip? {
            // Hosts without TabTemplate: the browser is reached from the home list's header.
            val browserEntry = isRoot && !tabsSupported
            if (playingId == null && !browserEntry) return null
            val strip = ActionStrip.Builder()
            if (browserEntry) strip.addAction(Action.Builder().setIcon(accentIcon(R.drawable.ic_car_web))
                .setOnClickListener { openBrowserHome() }.build())
            if (playingId == null) return strip.build()
            if (parentId == IptvPlaybackService.HOME && !IptvPlaybackService.isLocal(browser?.currentMediaItem?.mediaId)) {
                val fav = IptvStorage.isFavorite(carContext, playingId)
                strip.addAction(
                    Action.Builder()
                        .setIcon(if (fav) CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_fav))
                            .setTint(CarColor.RED).build() else icon(R.drawable.ic_car_fav_border))
                        .setOnClickListener { toggleFavorite() }.build()
                )
            }
            strip.addAction(Action.Builder().setIcon(accentIcon(R.drawable.ic_car_nowplaying))
                .setOnClickListener { openPlayer() }.build())
            return strip.build()
        }

        override fun onGetTemplate(): Template {
            templateLog("BrowseScreen($parentId)", "ListTemplate/Message (items=${items?.size} failed=$failed)")
            val b = browser
            if (b != null && !requested) load(b)
            val list = items
            val header = if (isRoot) Action.APP_ICON else Action.BACK
            if (failed && tabContent) {
                return MessageTemplate.Builder("Couldn't load this list")
                    .addAction(Action.Builder().setTitle("Retry").setOnClickListener { reload() }.build())
                    .build()
            }
            if (failed) {
                return MessageTemplate.Builder("Couldn't load this list")
                    .setTitle(title)
                    .setHeaderAction(header)
                    .addAction(Action.Builder().setTitle("Retry").setOnClickListener { reload() }.build())
                    .build()
            }
            if (list == null && tabContent) return ListTemplate.Builder().setLoading(true).build()
            if (list == null) {
                return ListTemplate.Builder().setTitle(title).setHeaderAction(header).setLoading(true).build()
            }
            val lb = ItemList.Builder()
            val playing = b?.currentMediaItem
            val playingId = playing?.mediaId?.let(IptvPlaybackService::parseChannelId)
            // Top level: the playing channel is the first row (tap = back to the player); its
            // favorite toggle and the player shortcut live in the header, so the list stays clean.
            if ((parentId == IptvPlaybackService.HOME || tabContent) && page == 0 && playing != null && playingId != null) {
                lb.addItem(row(playing, toPlayer = true))
            }
            if (page > 0) {
                lb.addItem(Row.Builder().setTitle("Previous page").addText("Page $page")
                    .setImage(accentIcon(R.drawable.ic_car_chevron_left), Row.IMAGE_TYPE_ICON)
                    .setBrowsable(true).setOnClickListener { page--; reload() }.build())
            }
            // The Local Video tab has its own home: hide the duplicate "Local videos" folder in the IPTV tab.
            list.filter { !(tabContent && it.mediaId == IptvPlaybackService.LOCAL_SCOPE) }.forEach { lb.addItem(row(it)) }
            if (hasNext) {
                lb.addItem(Row.Builder().setTitle("Next page").addText("Page ${page + 2}")
                    .setImage(accentIcon(R.drawable.ic_car_chevron_right), Row.IMAGE_TYPE_ICON)
                    .setBrowsable(true).setOnClickListener { page++; reload() }.build())
            }
            if (tabContent) return ListTemplate.Builder().setSingleList(lb.build()).build()
            val lt = ListTemplate.Builder()
                .setTitle(if (page > 0) "$title · ${page + 1}" else title)
                .setHeaderAction(header)
                .setSingleList(lb.build())
            actionStrip(playingId)?.let { lt.setActionStrip(it) }
            return lt.build()
        }

    }

    // ---- Tabs: IPTV / Local Video / Browser ---------------------------------------------

    /** Fallback entry for hosts without tabs: the browser's start list as its own screen. */
    private fun openBrowserHome() {
        openWeb(home = true)
    }

    private fun browserRows(onLoaded: () -> Unit): ItemList {
        val lb = ItemList.Builder()
        lb.addItem(Row.Builder().setTitle("Search or enter a web address")
            .addText("Opens the keyboard (while parked)")
            .setImage(accentIcon(R.drawable.ic_car_search), Row.IMAGE_TYPE_ICON)
            .setBrowsable(true).setOnClickListener { openSearch() }.build())
        if (webBrowser.hasPage) {
            val t = webBrowser.title.ifBlank { webBrowser.url }
            lb.addItem(Row.Builder().setTitle(t.ifBlank { "Current page" })
                .addText(if (webBrowser.url.isNotBlank()) "Current page · ${webBrowser.url}" else "Current page")
                .setImage(accentIcon(R.drawable.ic_car_web), Row.IMAGE_TYPE_ICON)
                .setBrowsable(true).setOnClickListener { openWeb() }.build())
        }
        val limit = (try {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (_: Throwable) { 6 }).coerceAtLeast(3)
        // History shared with the phone (same file): newest first, pages already shown above are skipped.
        var room = (limit - (if (webBrowser.hasPage) 2 else 1)).coerceAtLeast(0)
        val recent = BrowserHistory.list(carContext).filterNot { webBrowser.hasPage && WebUrls.same(it.url, webBrowser.url) }
            .take(minOf(room, 3))
        recent.forEach { h ->
            val host = try { android.net.Uri.parse(h.url).host.orEmpty().removePrefix("www.") } catch (_: Throwable) { "" }
            lb.addItem(Row.Builder().setTitle(h.title.ifBlank { host.ifBlank { h.url } })
                .addText("History · ${host.ifBlank { h.url }}")
                .setImage(accentIcon(R.drawable.ic_car_history), Row.IMAGE_TYPE_ICON)
                .setBrowsable(true).setOnClickListener { loadInCar(h.url) }.build())
        }
        room -= recent.size
        PopularSites.all.take(room.coerceAtLeast(0)).forEach { site ->
            val bmp = artwork.thumb(site.name, PopularSites.favicon(site.domain), onLoaded = onLoaded)
            lb.addItem(Row.Builder().setTitle(site.name).addText(site.domain)
                .setImage(CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build(), Row.IMAGE_TYPE_LARGE)
                .setBrowsable(true).setOnClickListener { loadInCar(site.url) }.build())
        }
        return lb.build()
    }

    /** Home screen on hosts that support TabTemplate (Car API 6+): same data as before, one tab each. */
    private inner class TabsScreen(ctx: CarContext) : Screen(ctx) {
        private var active = TAB_IPTV
        private val iptv = BrowseScreen(ctx, IptvPlaybackService.HOME, "IPTV", tabContent = true, host = this)
        private val local = BrowseScreen(ctx, IptvPlaybackService.LOCAL_SCOPE, "Local Video", tabContent = true, host = this)
        private var thumbPending = false

        init {
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) { browseScreens.remove(iptv); browseScreens.remove(local); tabs = null }
            })
        }

        private fun faviconLoaded() {
            ContextCompat.getMainExecutor(carContext).execute {
                if (thumbPending) return@execute
                thumbPending = true
                uiHandler.postDelayed({ thumbPending = false; try { invalidate() } catch (_: Throwable) {} }, 400)
            }
        }

        override fun onGetTemplate(): Template {
            templateLog("TabsScreen", "TabTemplate (active=$active)")
            val content: Template = when (active) {
                TAB_LOCAL -> local.onGetTemplate()
                TAB_BROWSER -> ListTemplate.Builder().setSingleList(browserRows { faviconLoaded() }).build()
                else -> iptv.onGetTemplate()
            }
            fun tab(id: String, title: String, res: Int) =
                Tab.Builder().setTitle(title).setIcon(icon(res)).setContentId(id).build()
            val b = TabTemplate.Builder(object : TabTemplate.TabCallback {
                override fun onTabSelected(tabContentId: String) {
                    // Browser opens its own full screen (search bar, site grid, history); the tab bar keeps the previous tab.
                    if (tabContentId == TAB_BROWSER) openWeb(home = true) else { active = tabContentId; invalidate() }
                }
            })
                .setHeaderAction(Action.APP_ICON)
                .addTab(tab(TAB_IPTV, "IPTV", R.drawable.ic_car_tv))
                .addTab(tab(TAB_LOCAL, "Local Video", R.drawable.ic_car_video))
                .addTab(tab(TAB_BROWSER, "Browser", R.drawable.ic_car_web))
                .setTabContents(TabContents.Builder(content).build())
                .setActiveTabContentId(if (active == TAB_BROWSER) TAB_IPTV else active)
            return b.build()
        }
    }

    /** The browser's start list as a plain screen (hosts without tabs). */
    private inner class BrowserHomeScreen(ctx: CarContext) : Screen(ctx) {
        private var pending = false
        init {
            browserHome = this
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) { if (browserHome === this@BrowserHomeScreen) browserHome = null }
            })
        }
        override fun onGetTemplate(): Template = ListTemplate.Builder()
            .also { templateLog("BrowserHomeScreen", "ListTemplate") }
            .setTitle("Browser").setHeaderAction(Action.BACK)
            .setSingleList(browserRows {
                ContextCompat.getMainExecutor(carContext).execute {
                    if (!pending) { pending = true; uiHandler.postDelayed({ pending = false; try { invalidate() } catch (_: Throwable) {} }, 400) }
                }
            }).build()
    }

    /** The web page: the template only provides the Surface; page + toolbar are drawn into it. */
    private inner class WebScreen(ctx: CarContext) : Screen(ctx) {
        init {
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { AaLog.i("session#$sid", "web screen start"); webActive = true; overlayKey = ""; sync(); onWebChanged() }
                override fun onStop(owner: LifecycleOwner) {
                    AaLog.i("session#$sid", "web screen stop")
                    webActive = false; overlayKey = ""
                    onWebChanged()
                    sync() // video / artwork come back; the page is kept for later
                }
            })
        }

        override fun onGetTemplate(): Template = NavigationTemplate.Builder()
            .also { templateLog("WebScreen", "NavigationTemplate (shellTab=$shellTab webHome=$webHome browserAttached=${webBrowser.attached})") }
            .setBackgroundColor(CarColor.createCustom(Color.WHITE, Color.WHITE))
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setIcon(icon(R.drawable.ic_car_video))
                            .setOnClickListener { if (browser?.mediaItemCount ?: 0 > 0) openPlayer() }.build())
                    .build()
            )
            // PAN lets the host deliver scroll / fling gestures to the SurfaceCallback.
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    /** Keyboard entry for the IPTV tab's search field; matching channels are listed under it. */
    private inner class ChannelSearchScreen(ctx: CarContext) : Screen(ctx) {
        private var text = iptvSearch

        private fun apply(q: String) {
            iptvSearch = q.trim(); listScroll[0] = 0f; shellVersion++; overlayKey = ""
            screenManager.pop()
        }

        override fun onGetTemplate(): Template {
            templateLog("ChannelSearchScreen", "SearchTemplate")
            val items = ItemList.Builder()
            val q = text.trim().lowercase()
            if (q.isNotEmpty()) {
                IptvStorage.channels(carContext).filter { it.name.lowercase().contains(q) }.take(5).forEach { ch ->
                    items.addItem(Row.Builder().setTitle(ch.name).addText(ch.group)
                        .setImage(accentIcon(R.drawable.ic_car_tv), Row.IMAGE_TYPE_ICON)
                        .setOnClickListener { iptvSearch = ""; shellVersion++; overlayKey = ""; screenManager.pop(); playFromShell(IptvPlaybackService.SEARCH_PREFIX + text.trim(), ch) }.build())
                }
            }
            return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) { text = searchText; invalidate() }
                override fun onSearchSubmitted(searchTerm: String) = apply(searchTerm)
            })
                .setHeaderAction(Action.BACK)
                .setSearchHint("Search channels")
                .setInitialSearchText(iptvSearch)
                .setShowKeyboardByDefault(true)
                .setItemList(items.build())
                .build()
        }
    }

    /** Keyboard entry (SearchTemplate): a web address opens, other text is searched on Google. */
    private inner class SearchScreen(ctx: CarContext) : Screen(ctx) {
        private var text = ""

        private fun submit(input: String) {
            val target = WebUrls.resolve(input) ?: return
            screenManager.pop()
            loadInCar(target)
        }

        override fun onGetTemplate(): Template {
            templateLog("SearchScreen", "SearchTemplate")
            val items = ItemList.Builder()
            if (text.isNotBlank()) {
                val target = WebUrls.resolve(text)
                val isUrl = target != null && !target.startsWith("https://www.google.com/search?q=")
                items.addItem(Row.Builder().setTitle(if (isUrl) "Open $text" else "Search Google for \"$text\"")
                    .setImage(accentIcon(if (isUrl) R.drawable.ic_car_web else R.drawable.ic_car_search), Row.IMAGE_TYPE_ICON)
                    .setOnClickListener { submit(text) }.build())
            }
            PopularSites.all.filter { text.isBlank() || it.name.contains(text.trim(), true) || it.domain.contains(text.trim(), true) }
                .take(5).forEach { site ->
                    items.addItem(Row.Builder().setTitle(site.name).addText(site.domain)
                        .setImage(accentIcon(R.drawable.ic_car_web), Row.IMAGE_TYPE_ICON)
                        .setOnClickListener { screenManager.pop(); loadInCar(site.url) }.build())
                }
            return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) { text = searchText; invalidate() }
                override fun onSearchSubmitted(searchTerm: String) = submit(searchTerm)
            })
                .setHeaderAction(Action.BACK)
                .setSearchHint("Search or type a URL")
                .setShowKeyboardByDefault(true)
                .setItemList(items.build())
                .build()
        }
    }

    companion object {
        const val TAG = "CarBrowser"
        val RENDERER_RETRY_MS = longArrayOf(150, 300, 600, 1000, 1500, 2000, 3000, 4000, 6000, 8000)
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        private val counter = java.util.concurrent.atomic.AtomicInteger(0)
        const val PLAYER_MARKER = "player"
        const val WEB_MARKER = "web"
        const val TAB_IPTV = "iptv"
        const val TAB_LOCAL = "local"
        const val TAB_BROWSER = "browser"
    }
}

/**
 * Draws the channel artwork and name onto the car Surface (used when the video plays on the
 * phone). Logos are fetched once, off the main thread, and cached.
 */
private class CarArtwork {
    private val cache = android.util.LruCache<String, Bitmap>(8)
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()

    /** Renders the artwork screen into a new Bitmap (null if the size is unknown). */
    fun render(w: Int, h: Int, title: String, subtitle: String, url: String?, onLoaded: () -> Unit): Bitmap? {
        if (w <= 0 || h <= 0) return null
        var bmp: Bitmap? = null
        if (!url.isNullOrBlank() && url.startsWith("http")) {
            bmp = cache.get(url)
            if (bmp == null) fetch(url, onLoaded)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        run {
            canvas.drawColor(Color.BLACK)
            val w = w.toFloat()
            val h = h.toFloat()
            val box = minOf(w, h) * 0.42f
            val cx = w / 2
            val top = h * 0.14f
            val dest = RectF(cx - box / 2, top, cx + box / 2, top + box)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263238.toInt() }
            canvas.drawRoundRect(dest, box * 0.08f, box * 0.08f, bg)
            if (bmp != null) {
                // Fit the logo inside the tile, keeping its aspect ratio.
                val scale = minOf(dest.width() * 0.84f / bmp.width, dest.height() * 0.84f / bmp.height)
                val bw = bmp.width * scale
                val bh = bmp.height * scale
                canvas.drawBitmap(
                    bmp, null,
                    RectF(cx - bw / 2, dest.centerY() - bh / 2, cx + bw / 2, dest.centerY() + bh / 2),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            } else {
                val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = box * 0.45f; textAlign = Paint.Align.CENTER
                }
                val ch = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "TV"
                canvas.drawText(ch, cx, dest.centerY() + initial.textSize * 0.35f, initial)
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = h * 0.075f; textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val ty = dest.bottom + titlePaint.textSize * 1.4f
            canvas.drawText(ellipsize(title, titlePaint, w * 0.8f), cx, ty, titlePaint)
            if (subtitle.isNotBlank()) {
                val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFB0BEC5.toInt(); textSize = h * 0.05f; textAlign = Paint.Align.CENTER
                }
                canvas.drawText(ellipsize(subtitle, sub, w * 0.8f), cx, ty + sub.textSize * 1.5f, sub)
            }
        }
        return out
    }

    private fun ellipsize(t: String, p: Paint, max: Float): String =
        android.text.TextUtils.ellipsize(t, android.text.TextPaint(p), max, android.text.TextUtils.TruncateAt.END).toString()

    private val thumbs = android.util.LruCache<String, Bitmap>(96)
    private val thumbLoading = HashSet<String>()
    private val tiles = HashMap<String, Bitmap>()

    /** Small logo for a list row; a letter tile while it loads (or if the channel has no logo). */
    /** The downloaded logo tile if it has arrived (null while loading / on failure). */
    fun cachedThumb(url: String): Bitmap? = thumbs.get(url)

    fun thumb(name: String, url: String?, onLoaded: () -> Unit): Bitmap {
        if (!url.isNullOrBlank() && url.startsWith("http")) {
            thumbs.get(url)?.let { return it }
            val start = synchronized(thumbLoading) { url !in failed && thumbLoading.add(url) }
            if (start) Thread {
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    val raw = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    if (raw != null) {
                        thumbs.put(url, tile(raw)); onLoaded()
                    } else synchronized(thumbLoading) { failed.add(url) }
                } catch (_: Throwable) {
                    synchronized(thumbLoading) { failed.add(url) }
                } finally {
                    synchronized(thumbLoading) { thumbLoading.remove(url) }
                }
            }.start()
        }
        val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
        return tiles.getOrPut(letter) {
            val b = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawRoundRect(RectF(0f, 0f, TILE.toFloat(), TILE.toFloat()), 16f, 16f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF37474F.toInt() })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = TILE * 0.5f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            c.drawText(letter, TILE / 2f, TILE / 2f - (p.descent() + p.ascent()) / 2f, p)
            b
        }
    }

    /** Logo centred on a light rounded square, like the app's channel tiles. */
    private fun tile(src: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRoundRect(RectF(0f, 0f, TILE.toFloat(), TILE.toFloat()), 16f, 16f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFECEFF1.toInt() })
        val inner = TILE * 0.8f
        val sc = minOf(inner / src.width, inner / src.height)
        val w = src.width * sc
        val h = src.height * sc
        c.drawBitmap(src, null, RectF((TILE - w) / 2, (TILE - h) / 2, (TILE + w) / 2, (TILE + h) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        return b
    }

    companion object { const val TILE = 96 }

    private fun fetch(url: String, onLoaded: () -> Unit) {
        synchronized(loading) { if (url in failed || !loading.add(url)) return }
        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) { cache.put(url, bmp); onLoaded() } else synchronized(loading) { failed.add(url) }
            } catch (_: Throwable) {
                synchronized(loading) { failed.add(url) }
            } finally {
                synchronized(loading) { loading.remove(url) }
            }
        }.start()
    }
}
