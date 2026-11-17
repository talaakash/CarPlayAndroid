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
 * NOTE: the Surface comes from the NAVIGATION category, which is outside Google Play's car-app
 * policy; use for personal / sideloaded builds. Video is shown only while the car reports speed ~0
 * (unknown speed hides video except in debuggable builds, so the Desktop Head Unit works).
 */
class CarVideoService : CarAppService() {
    // TODO(release): replace with a HostValidator built from Google's allow-list.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarVideoSession()
}

private class CarVideoSession : Session() {
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

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val v = speed.rawSpeedMetersPerSecond
        val value = v.value
        moving = if (v.status == CarValue.STATUS_SUCCESS && value != null) value > 0.5f else null
        sync()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            dropRenderer()
            surfaceContainer.surface?.let { renderer = CarVideoRenderer(it).takeIf { r -> r.inputSurface != null } }
            renderer?.setVisibleArea(effectiveArea()) // the area callback may have arrived first
            sync()
        }

        override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) {
            this@CarVideoSession.visibleArea = android.graphics.Rect(visibleArea)
            areaChanged()
        }

        override fun onClick(x: Float, y: Float) = onUiClick(x, y)

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
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

    private fun dropRenderer() {
        if (attached) { IptvPlaybackService.clearCarSurface(); attached = false }
        renderer?.release()
        renderer = null
    }

    /** Shared setting: where video is rendered. Changes arrive from the phone UI in real time. */
    private fun outputIsCar() = IptvStorage.outputMode(carContext) == IptvStorage.OUTPUT_CAR

    private fun videoAllowed(): Boolean = when (moving) {
        false -> true
        true -> false
        null -> carContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /** Single place that decides whether the car renders the video or the artwork. */
    private fun sync() {
        screen?.invalidate()
        val r = renderer
        if (r != null && outputIsCar() && videoAllowed()) {
            if (!attached) {
                // Same Surface, same renderer: only the player's output changes, nothing else.
                IptvPlaybackService.setCarSurface(r)
                attached = true
            }
        } else if (attached) {
            IptvPlaybackService.clearCarSurface()
            attached = false
        }
        if (!attached && r != null) drawArtwork(r)
        updateOverlay()
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

    private fun onUiClick(x: Float, y: Float) {
        val r = renderer ?: return
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
            CarPlayerUi.Hit.LIST -> try { carContext.getCarService(androidx.car.app.ScreenManager::class.java).pop() } catch (_: Throwable) {}
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

    override fun onCreateScreen(intent: Intent): Screen {
        val ctx = carContext
        // Connecting also starts the playback service when the phone UI is not running.
        val token = SessionToken(ctx, ComponentName(ctx, IptvPlaybackService::class.java))
        val future = MediaBrowser.Builder(ctx, token).buildAsync()
        future.addListener({
            browser = try { future.get() } catch (_: Exception) { null }
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
        } catch (_: Throwable) { /* ACCESS_SURFACE not granted: no video, browsing still works */ }

        try {
            val info = ctx.getCarService(CarHardwareManager::class.java).carInfo
            speedInfo = info
            info.addSpeedListener(ContextCompat.getMainExecutor(ctx), speedListener)
        } catch (_: Throwable) { moving = null }

        IptvPlaybackService.setFill(IptvStorage.videoFit(ctx) == IptvStorage.FILL)
        IptvStorage.addChangeListener(changeListener)
        uiHandler.postDelayed(progressTick, 1_000)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                IptvStorage.removeChangeListener(changeListener)
                uiHandler.removeCallbacks(hideUi)
                uiHandler.removeCallbacks(progressTick)
                try { speedInfo?.removeSpeedListener(speedListener) } catch (_: Throwable) {}
                dropRenderer()
                browser?.release()
                browser = null
            }
        })
        // Home screen = the channel browser itself.
        return BrowseScreen(ctx, IptvPlaybackService.HOME, "IPTV", isRoot = true)
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
        if (sm.screenStack.any { it.marker == PLAYER_MARKER }) sm.popTo(PLAYER_MARKER)
        else sm.push(VideoScreen(carContext).also { it.marker = PLAYER_MARKER; screen = it })
    }

    // ---- Player screen --------------------------------------------------------------

    private inner class VideoScreen(ctx: CarContext) : Screen(ctx) {
        override fun onGetTemplate(): Template {
            // Everything else (title, favorite, fit/fill, LIVE bar, prev / play / next) is drawn over
            // the video by CarPlayerUi and handled in onClick. The host strip keeps one entry
            // (back to the list) as the required action and as the non-touch fallback.
            val builder = NavigationTemplate.Builder()
                .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_blank))
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
    ) : Screen(ctx) {
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
                    try { invalidate() } catch (_: Throwable) {}
                }, 400)
            }
        }

        private fun reload() { items = null; failed = false; requested = false; invalidate() }

        /** Re-fetch but keep showing the current rows until the new ones arrive (no flicker). */
        fun refresh() { requested = false; invalidate() }

        private fun load(b: MediaBrowser) {
            requested = true
            val ex = ContextCompat.getMainExecutor(carContext)
            val f = b.getChildren(parentId, page, pageSize, null)
            f.addListener({
                val r: LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>? =
                    try { f.get() } catch (_: Exception) { null }
                if (r == null || r.resultCode != LibraryResult.RESULT_SUCCESS) {
                    failed = true; invalidate(); return@addListener
                }
                val list = r.value ?: com.google.common.collect.ImmutableList.of()
                items = list
                if (list.size < pageSize) { hasNext = false; invalidate(); return@addListener }
                // Full page: peek at the next one to know whether a "Next page" row is needed.
                val g = b.getChildren(parentId, page + 1, pageSize, null)
                g.addListener({
                    hasNext = try { g.get().value?.isNotEmpty() == true } catch (_: Exception) { false }
                    invalidate()
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
            if (playingId == null) return null
            val strip = ActionStrip.Builder()
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
            val b = browser
            if (b != null && !requested) load(b)
            val list = items
            val header = if (isRoot) Action.APP_ICON else Action.BACK
            if (failed) {
                return MessageTemplate.Builder("Couldn't load this list")
                    .setTitle(title)
                    .setHeaderAction(header)
                    .addAction(Action.Builder().setTitle("Retry").setOnClickListener { reload() }.build())
                    .build()
            }
            if (list == null) {
                return ListTemplate.Builder().setTitle(title).setHeaderAction(header).setLoading(true).build()
            }
            val lb = ItemList.Builder()
            val playing = b?.currentMediaItem
            val playingId = playing?.mediaId?.let(IptvPlaybackService::parseChannelId)
            // Top level: the playing channel is the first row (tap = back to the player); its
            // favorite toggle and the player shortcut live in the header, so the list stays clean.
            if (parentId == IptvPlaybackService.HOME && page == 0 && playing != null && playingId != null) {
                lb.addItem(row(playing, toPlayer = true))
            }
            if (page > 0) {
                lb.addItem(Row.Builder().setTitle("Previous page").addText("Page $page")
                    .setImage(accentIcon(R.drawable.ic_car_chevron_left), Row.IMAGE_TYPE_ICON)
                    .setBrowsable(true).setOnClickListener { page--; reload() }.build())
            }
            list.forEach { lb.addItem(row(it)) }
            if (hasNext) {
                lb.addItem(Row.Builder().setTitle("Next page").addText("Page ${page + 2}")
                    .setImage(accentIcon(R.drawable.ic_car_chevron_right), Row.IMAGE_TYPE_ICON)
                    .setBrowsable(true).setOnClickListener { page++; reload() }.build())
            }
            val lt = ListTemplate.Builder()
                .setTitle(if (page > 0) "$title · ${page + 1}" else title)
                .setHeaderAction(header)
                .setSingleList(lb.build())
            actionStrip(playingId)?.let { lt.setActionStrip(it) }
            return lt.build()
        }
    }

    private companion object {
        const val PLAYER_MARKER = "player"
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

    private companion object { const val TILE = 96 }

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
