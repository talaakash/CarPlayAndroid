package com.example.iptv_native

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
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
 * Two jobs, both on top of the EXISTING ExoPlayer in [IptvPlaybackService] (no second player):
 *  1. Video: the host-owned car Surface is handed to the player ([IptvPlaybackService.setCarSurface]).
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
    private var hostSurface: Surface? = null
    private var attached = false
    /** true = moving, false = stopped, null = unknown. */
    private var moving: Boolean? = null
    private var screen: VideoScreen? = null
    private var speedInfo: CarInfo? = null

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val v = speed.rawSpeedMetersPerSecond
        val value = v.value
        moving = if (v.status == CarValue.STATUS_SUCCESS && value != null) value > 0.5f else null
        sync()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            hostSurface = surfaceContainer.surface
            sync()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            hostSurface = null
            sync()
        }
    }

    private fun videoAllowed(): Boolean = when (moving) {
        false -> true
        true -> false
        null -> carContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /** Single place that decides whether the car surface is attached to the player. */
    private fun sync() {
        screen?.invalidate()
        val s = hostSurface
        if (s != null && s.isValid && videoAllowed()) {
            if (!attached) {
                IptvPlaybackService.setCarSurface(s)
                attached = true
            }
        } else if (attached) {
            IptvPlaybackService.clearCarSurface()
            attached = false
        }
    }

    private fun icon(res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    override fun onCreateScreen(intent: Intent): Screen {
        val ctx = carContext
        // Connecting also starts the playback service when the phone UI is not running.
        val token = SessionToken(ctx, ComponentName(ctx, IptvPlaybackService::class.java))
        val future = MediaBrowser.Builder(ctx, token).buildAsync()
        future.addListener({
            browser = try { future.get() } catch (_: Exception) { null }
            browser?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { invalidateTop() }
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

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                try { speedInfo?.removeSpeedListener(speedListener) } catch (_: Throwable) {}
                hostSurface = null
                sync()
                browser?.release()
                browser = null
            }
        })
        return VideoScreen(ctx).also { screen = it }
    }

    private fun invalidateTop() {
        try { carContext.getCarService(androidx.car.app.ScreenManager::class.java).top.invalidate() }
        catch (_: Throwable) { screen?.invalidate() }
    }

    // ---- Video screen ---------------------------------------------------------------

    private inner class VideoScreen(ctx: CarContext) : Screen(ctx) {
        /** What the user needs to know right now, or null when video is simply playing. */
        private fun message(): Pair<String, String>? {
            val c = browser ?: return "Connecting…" to "Starting the player"
            val name = c.currentMediaItem?.mediaMetadata?.title?.toString()
            return when {
                c.playerError != null ->
                    "Can't play ${name ?: "this channel"}" to
                        ((c.playerError?.errorCodeName ?: "Stream error") + " – try Next or pick another channel")
                c.mediaItemCount == 0 ->
                    "No channel playing" to "Tap the list button to choose a channel"
                moving == true -> "Video paused while driving" to "Audio keeps playing"
                moving == null && !videoAllowed() -> "Video unavailable" to "Vehicle speed is not available – audio only"
                c.playbackState == Player.STATE_BUFFERING -> "Buffering…" to (name ?: "")
                else -> null
            }
        }

        override fun onGetTemplate(): Template {
            fun action(res: Int, block: () -> Unit) =
                Action.Builder().setIcon(icon(res)).setOnClickListener { block() }.build()
            val playing = browser?.playWhenReady == true
            val builder = NavigationTemplate.Builder()
                .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(action(R.drawable.ic_car_list) {
                            screenManager.push(BrowseScreen(carContext, IptvPlaybackService.HOME, "IPTV"))
                        })
                        .addAction(action(R.drawable.ic_car_prev) { browser?.seekToPreviousMediaItem() })
                        .addAction(action(if (playing) R.drawable.ic_car_pause else R.drawable.ic_car_play) {
                            browser?.let { if (it.playWhenReady) it.pause() else it.play() }
                        })
                        .addAction(action(R.drawable.ic_car_next) { browser?.seekToNextMediaItem() })
                        .build()
                )
            message()?.let { (title, text) ->
                builder.setNavigationInfo(MessageInfo.Builder(title).setText(text).build())
            }
            return builder.build()
        }
    }

    // ---- Channel browser ------------------------------------------------------------

    /**
     * One level of the MediaLibrarySession tree as a list. Folders open another BrowseScreen;
     * a channel starts playing and returns to the video screen; rows for empty/info states
     * are plain text. Long lists are paged to the host's list limit.
     */
    private inner class BrowseScreen(
        ctx: CarContext,
        private val parentId: String,
        private val title: String,
    ) : Screen(ctx) {
        private var page = 0
        private var items: List<MediaItem>? = null
        private var hasNext = false
        private var failed = false
        private var requested = false

        private val pageSize: Int = (try {
            ctx.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (_: Throwable) { 6 } - 2).coerceIn(4, 48) // room for the Previous/Next page rows

        private fun reload() { items = null; failed = false; requested = false; invalidate() }

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
            screenManager.popToRoot() // back to the video screen
        }

        private fun toggleFavorite() {
            browser?.sendCustomCommand(SessionCommand(IptvPlaybackService.CMD_TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
            screenManager.popToRoot()
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
            if (failed) {
                return MessageTemplate.Builder("Couldn't load this list")
                    .setTitle(title)
                    .setHeaderAction(Action.BACK)
                    .addAction(Action.Builder().setTitle("Retry").setOnClickListener { reload() }.build())
                    .build()
            }
            if (list == null) {
                return ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setLoading(true).build()
            }
            val lb = ItemList.Builder()
            // At the top level, one tap adds/removes the playing channel from Favorites.
            if (parentId == IptvPlaybackService.HOME && page == 0) {
                val cur = b?.currentMediaItem
                val id = cur?.mediaId?.let(IptvPlaybackService::parseChannelId)
                if (id != null) {
                    val fav = IptvStorage.isFavorite(carContext, id)
                    val name = cur.mediaMetadata.title?.toString() ?: "channel"
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
                .setHeaderAction(Action.BACK)
                .setSingleList(lb.build())
                .build()
        }
    }
}
