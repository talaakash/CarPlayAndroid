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

    /** Draws the status (buffering, error, driving...) over the picture instead of a host banner. */
    private fun updateOverlay() {
        val r = renderer ?: return
        val m = statusMessage()
        val (aw, ah) = r.areaSize()
        val key = "$m|$aw|$ah"
        if (key == overlayKey) return
        overlayKey = key
        r.setOverlay(if (m == null) null else artwork.renderBanner(aw, ah, m.first, m.second))
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

    /** Shared data changed (phone or car): settings re-route video, lists reload in place. */
    private val changeListener: (String) -> Unit = { kind ->
        ContextCompat.getMainExecutor(carContext).execute {
            if (kind == "settings") {
                IptvPlaybackService.setFill(IptvStorage.videoFit(carContext) == IptvStorage.FILL)
                sync()
            }
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

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                IptvStorage.removeChangeListener(changeListener)
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
        val sm = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        if (sm.screenStack.any { it.marker == PLAYER_MARKER }) sm.popTo(PLAYER_MARKER)
        else sm.push(VideoScreen(carContext).also { it.marker = PLAYER_MARKER; screen = it })
    }

    // ---- Player screen --------------------------------------------------------------

    private inner class VideoScreen(ctx: CarContext) : Screen(ctx) {
        private fun currentChannelId() =
            browser?.currentMediaItem?.mediaId?.let(IptvPlaybackService::parseChannelId)

        override fun onGetTemplate(): Template {
            fun action(res: Int, block: () -> Unit) =
                Action.Builder().setIcon(icon(res)).setOnClickListener { block() }.build()
            val playing = browser?.playWhenReady == true
            val fill = IptvStorage.videoFit(carContext) == IptvStorage.FILL
            val builder = NavigationTemplate.Builder()
                .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
                .setActionStrip(
                    ActionStrip.Builder()
                        // Back to the channel list the user came from; pick another channel there.
                        .addAction(action(R.drawable.ic_car_list) { screenManager.pop() })
                        .addAction(action(R.drawable.ic_car_prev) { browser?.seekToPreviousMediaItem() })
                        .addAction(action(if (playing) R.drawable.ic_car_pause else R.drawable.ic_car_play) {
                            browser?.let { if (it.playWhenReady) it.pause() else it.play() }
                        })
                        .addAction(action(R.drawable.ic_car_next) { browser?.seekToNextMediaItem() })
                        .build()
                )
            val mapActions = ActionStrip.Builder()
            // Shows the mode a tap switches to; the setting is shared with the phone player.
            mapActions.addAction(action(if (fill) R.drawable.ic_car_fit else R.drawable.ic_car_fill) {
                IptvStorage.setVideoFit(carContext, if (fill) IptvStorage.FIT else IptvStorage.FILL)
            })
            currentChannelId()?.let { id ->
                val fav = IptvStorage.isFavorite(carContext, id)
                mapActions.addAction(action(if (fav) R.drawable.ic_car_fav else R.drawable.ic_car_fav_border) {
                    browser?.sendCustomCommand(SessionCommand(IptvPlaybackService.CMD_TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
                })
            }
            builder.setMapActionStrip(mapActions.build())
            // No host message panel: it would shrink the visible area and with it the video.
            // Status is drawn over the picture by the renderer (see updateOverlay).
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

        private fun row(item: MediaItem): Row {
            val md = item.mediaMetadata
            val r = Row.Builder().setTitle(md.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId)
            md.subtitle?.toString()?.takeIf { it.isNotBlank() }?.let { r.addText(it) }
            when {
                md.isBrowsable == true -> r.setBrowsable(true).setOnClickListener {
                    screenManager.push(BrowseScreen(carContext, item.mediaId, md.title?.toString() ?: title))
                }
                md.isPlayable == true -> r.setOnClickListener { play(item) }
                // else: explanatory row ("No favorites yet", ...) with no action
            }
            return r.build()
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
            // At the top level: jump back to the player, and one tap adds/removes the playing
            // channel from Favorites.
            if (parentId == IptvPlaybackService.HOME && page == 0) {
                val cur = b?.currentMediaItem
                val id = cur?.mediaId?.let(IptvPlaybackService::parseChannelId)
                if (id != null) {
                    val name = cur.mediaMetadata.title?.toString() ?: "channel"
                    lb.addItem(
                        Row.Builder()
                            .setTitle("Now playing")
                            .addText(name)
                            .setBrowsable(true)
                            .setOnClickListener { openPlayer() }
                            .build()
                    )
                    val fav = IptvStorage.isFavorite(carContext, id)
                    lb.addItem(
                        Row.Builder()
                            .setTitle(if (fav) "Remove from Favorites" else "Add to Favorites")
                            .addText(name)
                            .setOnClickListener { toggleFavorite() }
                            .build()
                    )
                }
            }
            if (page > 0) {
                lb.addItem(Row.Builder().setTitle("Previous page").setBrowsable(true)
                    .setOnClickListener { page--; reload() }.build())
            }
            list.forEach { lb.addItem(row(it)) }
            if (hasNext) {
                lb.addItem(Row.Builder().setTitle("Next page").setBrowsable(true)
                    .setOnClickListener { page++; reload() }.build())
            }
            return ListTemplate.Builder()
                .setTitle(if (page > 0) "$title · ${page + 1}" else title)
                .setHeaderAction(header)
                .setSingleList(lb.build())
                .build()
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

    /** Transparent bitmap with a translucent status banner at the top centre (title + text). */
    fun renderBanner(w: Int, h: Int, title: String, text: String): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = h * 0.055f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCFD8DC.toInt(); textSize = h * 0.042f; textAlign = Paint.Align.CENTER
        }
        val maxW = w * 0.7f
        val t = ellipsize(title, tp, maxW - 48f)
        val x = ellipsize(text, sp, maxW - 48f)
        val boxH = tp.textSize * 1.6f + (if (x.isNotBlank()) sp.textSize * 1.5f else 0f) + 24f
        val box = RectF(w / 2f - maxW / 2f, h * 0.06f, w / 2f + maxW / 2f, h * 0.06f + boxH)
        canvas.drawRoundRect(box, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() })
        var y = box.top + 12f + tp.textSize * 1.1f
        canvas.drawText(t, w / 2f, y, tp)
        if (x.isNotBlank()) { y += sp.textSize * 1.5f; canvas.drawText(x, w / 2f, y, sp) }
        return out
    }

    private fun ellipsize(t: String, p: Paint, max: Float): String =
        android.text.TextUtils.ellipsize(t, android.text.TextPaint(p), max, android.text.TextUtils.TruncateAt.END).toString()

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
