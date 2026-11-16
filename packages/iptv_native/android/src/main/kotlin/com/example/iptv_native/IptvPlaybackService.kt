package com.example.iptv_native

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import android.app.PendingIntent
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The single playback engine. Owns the ExoPlayer and exposes a browsable
 * library (Favorites / Recently watched / Categories) to Android Auto.
 *
 * Android Auto's media UI (this browse tree) is audio + controls; the video itself is
 * drawn by CarVideoService on the car Surface. The Flutter UI attaches its own video
 * surface to the very same player through a MediaController, so there is one engine.
 */
class IptvPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())
    private var retryCount = 0

    /** Direction of the last next/previous request (0 = channel picked directly). */
    private var navDir = 0
    private var skipStreak = 0
    private var lastSkipAt = 0L
    private lateinit var channelPlayer: ChannelPlayer
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent("Mozilla/5.0 (Linux; Android) IPTVPlayer/1.0")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 30_000, 2_500, 5_000)
                    .build()
            )
            // Audio focus (calls, navigation prompts, other media) and
            // "becoming noisy" (Bluetooth / headset disconnect) are handled by ExoPlayer.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.let(::parseChannelId) ?: return
                retryCount = 0
                IptvStorage.addRecent(applicationContext, id)
                notifyLibraryChanged(RECENTS)
                updateFavoriteButton()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    retryCount = 0
                    navDir = 0
                    skipStreak = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })

        channelPlayer = ChannelPlayer(player)
        val builder = MediaLibrarySession.Builder(this, channelPlayer, LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        session = builder.build()
        updateFavoriteButton()
        applyCarSurface() // the car may have offered a Surface before the player existed
        registerNetworkCallback()
        ParkedVideoPolicy.addListener(policyListener)
        applyParkedPolicy()
    }

    private var pausedByPolicy = false
    private val policyListener: () -> Unit = { main.post { applyParkedPolicy() } }

    /**
     * Driving: stop decoding video (audio-only fallback: the video track is disabled, so
     * HLS can fall back to audio renditions and no frames are decoded for a hidden surface),
     * keep audio only if the platform allows it, and refresh the commands controllers see.
     * Parked again: re-enable video and resume only what the policy itself paused.
     */
    private fun applyParkedPolicy() {
        val restricted = ParkedVideoPolicy.restricted
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, restricted)
            .build()
        if (ParkedVideoPolicy.playbackBlocked && player.playWhenReady) {
            pausedByPolicy = true
            player.pause()
        } else if (!restricted && pausedByPolicy) {
            pausedByPolicy = false
            player.play()
        }
        val s = session ?: return
        for (c in s.connectedControllers) {
            s.setAvailableCommands(c, sessionCommands(), channelPlayer.availableCommands)
        }
    }

    private fun applyCarSurface() {
        val s = carSurface ?: return
        if (s.isValid) player.setVideoSurface(s) // raw ExoPlayer: bypasses the phone guard
    }

    // ---- Favorite button (car + notification) ---------------------------------------

    private fun sessionCommands(): SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY)).build()

    private fun currentChannelId(): String? =
        player.currentMediaItem?.mediaId?.let(::parseChannelId)

    /** Heart button on the playback screen; filled when the current channel is a favorite. */
    private fun updateFavoriteButton() {
        val s = session ?: return
        val id = currentChannelId()
        if (id == null) { s.setMediaButtonPreferences(ImmutableList.of()); return }
        val fav = IptvStorage.isFavorite(applicationContext, id)
        s.setMediaButtonPreferences(
            ImmutableList.of(
                CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(if (fav) "Remove favorite" else "Add favorite")
                    .setSessionCommand(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                    .build()
            )
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Keep playing in the background if the user is listening; otherwise stop.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        ParkedVideoPolicy.removeListener(policyListener)
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        main.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        player.release()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Player wrapper used by the session ------------------------------------------

    /**
     * Wraps ExoPlayer for the MediaSession (Android Auto, notification, Bluetooth buttons
     * and the Flutter MediaController all go through this one object).
     *
     * Fixes behaviours that made the car controls look dead for live IPTV:
     *  - ExoPlayer's seekToPrevious() restarts the current *live window* when more than
     *    3 s were played, so "Previous" never switched channel. Next/Previous now always
     *    change channel (wrapping around at the ends of the list).
     *  - After a stream error ExoPlayer stays IDLE; seeking or play() did nothing until
     *    prepare() was called. We re-prepare whenever needed.
     *  - Play with an empty queue starts a sensible default channel.
     */
    private inner class ChannelPlayer(p: Player) : ForwardingPlayer(p) {

        override fun getAvailableCommands(): Player.Commands {
            val b = super.getAvailableCommands().buildUpon()
            if (mediaItemCount > 1) {
                b.addAll(
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
            }
            // Live streams: no scrubbing buttons in the car UI.
            b.removeAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
            // Driving without platform support for background audio: nobody may resume
            // playback until the vehicle is parked again.
            if (ParkedVideoPolicy.playbackBlocked) b.remove(Player.COMMAND_PLAY_PAUSE)
            return b.build()
        }

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

        // While the car owns the output Surface, the phone's TextureView (routed here by its
        // MediaController) must not steal or clear it. The car path calls the raw ExoPlayer.
        override fun setVideoSurface(surface: android.view.Surface?) {
            if (carSurface == null) super.setVideoSurface(surface)
        }
        override fun clearVideoSurface() {
            if (carSurface == null) super.clearVideoSurface()
        }
        override fun clearVideoSurface(surface: android.view.Surface?) {
            if (carSurface == null) super.clearVideoSurface(surface)
        }

        override fun seekToNext() = skip(+1)
        override fun seekToNextMediaItem() = skip(+1)
        override fun seekToPrevious() = skip(-1)
        override fun seekToPreviousMediaItem() = skip(-1)

        override fun play() {
            if (ParkedVideoPolicy.playbackBlocked) return
            if (mediaItemCount == 0 && !loadDefaultChannel()) return
            ensurePrepared()
            super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady && ParkedVideoPolicy.playbackBlocked) return
            if (playWhenReady) {
                if (mediaItemCount == 0 && !loadDefaultChannel()) return
                ensurePrepared()
            }
            super.setPlayWhenReady(playWhenReady)
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
            navDir = 0; skipStreak = 0
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            navDir = 0; skipStreak = 0
            super.setMediaItems(mediaItems, resetPosition)
        }

        fun skip(dir: Int, auto: Boolean = false) {
            val n = mediaItemCount
            if (n == 0) return
            val now = SystemClock.elapsedRealtime()
            if (!auto && now - lastSkipAt < 150) return // duplicate event from the same key press
            lastSkipAt = now
            if (!auto) { navDir = dir; skipStreak = 0 }
            val target = ((currentMediaItemIndex + dir) % n + n) % n
            super.seekTo(target, C.TIME_UNSET)
            ensurePrepared()
            super.play()
        }

        private fun ensurePrepared() {
            if (playbackState == Player.STATE_IDLE || playerError != null) prepare()
        }

        /** recents -> favorites -> first channels of the playlist. */
        private fun loadDefaultChannel(): Boolean {
            val ctx = applicationContext
            val (list, scope) = IptvStorage.recents(ctx).takeIf { it.isNotEmpty() }?.let { it to RECENTS }
                ?: IptvStorage.favorites(ctx).takeIf { it.isNotEmpty() }?.let { it to FAVORITES }
                ?: IptvStorage.channels(ctx).takeIf { it.isNotEmpty() }?.let { it.take(QUEUE_WINDOW * 2) to ALL }
                ?: return false
            super.setMediaItems(list.map { channelItem(it, scope) }, 0, C.TIME_UNSET)
            return true
        }
    }

    // ---- stream failure handling -------------------------------------------------

    private fun handleError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    main.postDelayed({
                        if (player.playerError != null) player.prepare()
                    }, 2_000L * retryCount)
                }
                // After MAX_RETRIES the error stays visible (Flutter + car UI); the
                // network callback below retries when connectivity returns.
            }
            else -> {
                // Dead stream. If the user was stepping through channels, keep going in
                // the same direction (bounded) instead of getting stuck on a dead one.
                if (navDir != 0 && skipStreak < MAX_AUTO_SKIPS && player.mediaItemCount > 1) {
                    skipStreak++
                    main.post { channelPlayer.skip(navDir, auto = true) }
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (player.playerError != null && player.playWhenReady) {
                        retryCount = 0
                        player.prepare()
                    }
                }
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun notifyLibraryChanged(parentId: String) {
        session?.notifyChildrenChanged(parentId, 0, null)
    }

    // ---- Android Auto library ---------------------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != CMD_TOGGLE_FAVORITE) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val id = currentChannelId()
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            IptvStorage.toggleFavorite(applicationContext, id)
            updateFavoriteButton()
            notifyLibraryChanged(FAVORITES)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            // Who is driving playback? Flutter marks its own controller with a connection hint.
            lastOrigin = when {
                controller.connectionHints.getBoolean(HINT_PHONE, false) -> "phone"
                session.isAutoCompanionController(controller) ||
                    session.isAutomotiveController(controller) -> "car"
                else -> "system" // notification, Bluetooth, Assistant, steering-wheel keys
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // List style for browsable folders and playable items on Android Auto.
            val extras = Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
            val root = folder(ROOT, "IPTV")
            return Futures.immediateFuture(
                LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build())
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = folderItem(mediaId) ?: resolveChannelItem(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val ctx = applicationContext
            val items: List<MediaItem> = when {
                // Four root tabs (the Android Auto limit): the everyday ones, then "More".
                parentId == ROOT -> listOf(
                    folder(FAVORITES, "Favorites", count(IptvStorage.favorites(ctx).size, "channel")),
                    folder(RECENTS, "Recent", count(IptvStorage.recents(ctx).size, "channel")),
                    folder(CATEGORIES, "Categories", count(IptvStorage.groups(ctx).size, "group")),
                    folder(PLAYLISTS, "Playlists", playlistsSubtitle()),
                )
                // Whole library on one level. The 4-tab media root stays small (Android Auto limit);
                // the car screen's channel browser starts here.
                parentId == HOME -> listOf(
                    folder(AZ, "All Channels", count(IptvStorage.channels(ctx).size, "channel")),
                    folder(CATEGORIES, "Categories", count(IptvStorage.groups(ctx).size, "group")),
                    folder(COUNTRIES, "Countries", count(IptvStorage.countries(ctx).size, "country")),
                    folder(FAVORITES, "Favorites", count(IptvStorage.favorites(ctx).size, "channel")),
                    folder(RECENTS, "Recent", count(IptvStorage.recents(ctx).size, "channel")),
                    folder(PLAYLISTS, "Playlists", playlistsSubtitle()),
                )
                parentId == PLAYLISTS -> IptvStorage.playlists(ctx).map { p ->
                    if (p.active) folder(PLAYLIST_ACTIVE, p.name, "Active · ${count(IptvStorage.channels(ctx).size, "channel")}")
                    else info("pl:${p.url}", p.name, "Select it in the phone app to browse it here")
                }
                // The channel cache only holds the active playlist, so only it can be browsed.
                parentId == PLAYLIST_ACTIVE -> listOf(
                    folder(CATEGORIES, "Categories", count(IptvStorage.groups(ctx).size, "group")),
                    folder(AZ, "All channels", count(IptvStorage.channels(ctx).size, "channel")),
                    folder(COUNTRIES, "Countries", count(IptvStorage.countries(ctx).size, "country")),
                    folderItem(SELECTED)!!,
                )
                parentId == CATEGORIES -> IptvStorage.groups(ctx).map { (g, n) ->
                    folder(CAT_PREFIX + g, g, count(n, "channel"))
                }
                parentId == COUNTRIES -> IptvStorage.countries(ctx).map { (c, n) ->
                    folder(COUNTRY_PREFIX + c, c, count(n, "channel"))
                }
                parentId == AZ -> {
                    val n = IptvStorage.channels(ctx).size
                    // Small playlists: one flat list. Large ones: A–Z buckets.
                    if (n <= FLAT_LIMIT) IptvStorage.channels(ctx).map { channelItem(it, ALL) }
                    else IptvStorage.azBuckets(ctx).map { (l, c) -> folder(AZ_PREFIX + l, l, count(c, "channel")) }
                }
                parentId == SELECTED -> {
                    // Mirrors what is selected on the phone; items are queued under the real scope.
                    val scope = IptvStorage.selection(ctx).scope
                    scopeChannels(scope)?.take(SELECTED_LIMIT)?.map { channelItem(it, scope) } ?: emptyList()
                }
                else -> {
                    // The car is browsing a folder: tell the phone (two-way browsing sync).
                    if (parentId == FAVORITES || parentId == RECENTS || parentId.startsWith(CAT_PREFIX)) {
                        val label = when (parentId) {
                            FAVORITES -> "Favorites"
                            RECENTS -> "Recent"
                            else -> parentId.removePrefix(CAT_PREFIX)
                        }
                        IptvStorage.setSelection(ctx, parentId, label, "car")
                    }
                    scopeChannels(parentId)?.map { channelItem(it, parentId) } ?: emptyList()
                }
            }.ifEmpty { listOf(emptyState(parentId)) }
            val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val n = searchChannels(query).size
            session.notifySearchResultChanged(browser, query, n, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = searchChannels(query).map { channelItem(it, SEARCH_PREFIX + query) }
                .ifEmpty { listOf(info("search", "No results", "Nothing matches \"$query\"")) }
            val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // A single channel picked in the car (or by voice): expand to the list it
            // was chosen from so next / previous switch channels.
            val first = mediaItems.singleOrNull()
            // Voice command ("play BBC News"): no mediaId, only a search query.
            val voice = first?.takeIf { it.mediaId.isEmpty() }?.requestMetadata?.searchQuery
            if (!voice.isNullOrBlank()) {
                val hits = searchChannels(voice)
                if (hits.isNotEmpty()) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            hits.map { channelItem(it, SEARCH_PREFIX + voice) }, 0, C.TIME_UNSET,
                        )
                    )
                }
            }
            if (first != null) {
                val id = parseChannelId(first.mediaId)
                val scope = parseScope(first.mediaId)
                val list = scope?.let { scopeChannels(it) }
                val idx = list?.indexOfFirst { it.id == id } ?: -1
                if (list != null && idx >= 0) {
                    val from = (idx - QUEUE_WINDOW).coerceAtLeast(0)
                    val to = (idx + QUEUE_WINDOW + 1).coerceAtMost(list.size)
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            list.subList(from, to).map { channelItem(it, scope) },
                            idx - from,
                            C.TIME_UNSET,
                        )
                    )
                }
            }
            val resolved = mediaItems.mapNotNull { resolveForPlayback(it) }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { resolveForPlayback(it) }.toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Car / Bluetooth "play" with nothing loaded: resume the last channel.
            val ctx = applicationContext
            val (list, scope) = IptvStorage.recents(ctx).takeIf { it.isNotEmpty() }?.let { it to RECENTS }
                ?: IptvStorage.favorites(ctx).takeIf { it.isNotEmpty() }?.let { it to FAVORITES }
                ?: IptvStorage.channels(ctx).takeIf { it.isNotEmpty() }?.let { it.take(QUEUE_WINDOW * 2) to ALL }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("No channels cached yet"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    list.map { channelItem(it, scope) }, 0, C.TIME_UNSET,
                )
            )
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private fun resolveForPlayback(item: MediaItem): MediaItem? {
        if (item.localConfiguration != null) return item
        val id = parseChannelId(item.mediaId) ?: return null
        val ch = IptvStorage.channelById(applicationContext, id) ?: return null
        return channelItem(ch, parseScope(item.mediaId) ?: ALL)
    }

    private fun resolveChannelItem(mediaId: String): MediaItem? {
        val id = parseChannelId(mediaId) ?: return null
        val ch = IptvStorage.channelById(applicationContext, id) ?: return null
        return channelItem(ch, parseScope(mediaId) ?: ALL)
    }

    private fun scopeChannels(scope: String): List<IptvStorage.Channel>? = when {
        scope == FAVORITES -> IptvStorage.favorites(applicationContext)
        scope == RECENTS -> IptvStorage.recents(applicationContext)
        scope == ALL -> IptvStorage.channels(applicationContext)
        scope == SELECTED -> scopeChannels(IptvStorage.selection(applicationContext).scope)
        scope.startsWith(CAT_PREFIX) -> {
            val g = scope.removePrefix(CAT_PREFIX)
            IptvStorage.channels(applicationContext).filter { it.group == g }
        }
        scope.startsWith(COUNTRY_PREFIX) -> {
            val c = scope.removePrefix(COUNTRY_PREFIX)
            IptvStorage.channels(applicationContext).filter { it.country.ifBlank { IptvStorage.UNKNOWN_COUNTRY } == c }
        }
        scope.startsWith(AZ_PREFIX) -> {
            val l = scope.removePrefix(AZ_PREFIX)
            IptvStorage.channels(applicationContext).filter { IptvStorage.azBucket(it.name) == l }
                .sortedBy { it.name.lowercase() }
        }
        scope.startsWith(SEARCH_PREFIX) -> searchChannels(scope.removePrefix(SEARCH_PREFIX))
        else -> null
    }

    private fun searchChannels(query: String): List<IptvStorage.Channel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return IptvStorage.channels(applicationContext)
            .filter { it.name.lowercase().contains(q) }.take(100)
    }

    private fun playlistsSubtitle(): String {
        val p = IptvStorage.playlists(applicationContext)
        return p.firstOrNull { it.active }?.name ?: "No playlist yet"
    }

    private fun count(n: Int, noun: String) =
        "$n " + if (n == 1) noun else if (noun.endsWith("y")) noun.dropLast(1) + "ies" else noun + "s"

    /** Browsable folder for a known id, or null (channel ids and unknown ids). */
    private fun folderItem(id: String): MediaItem? = when {
        id == ROOT -> folder(ROOT, "IPTV")
        id == FAVORITES -> folder(FAVORITES, "Favorites")
        id == RECENTS -> folder(RECENTS, "Recent")
        id == CATEGORIES -> folder(CATEGORIES, "Categories")
        id == HOME -> folder(HOME, "IPTV")
        id == PLAYLISTS -> folder(PLAYLISTS, "Playlists")
        id == PLAYLIST_ACTIVE -> folder(PLAYLIST_ACTIVE, IptvStorage.playlists(applicationContext).firstOrNull { it.active }?.name ?: "Playlist")
        id == COUNTRIES -> folder(COUNTRIES, "Countries")
        id == AZ -> folder(AZ, "All channels")
        id == SELECTED -> folder(SELECTED, "On phone: ${IptvStorage.selection(applicationContext).label}", "Same list as the phone app")
        id.startsWith(CAT_PREFIX) -> folder(id, id.removePrefix(CAT_PREFIX))
        id.startsWith(COUNTRY_PREFIX) -> folder(id, id.removePrefix(COUNTRY_PREFIX))
        id.startsWith(AZ_PREFIX) -> folder(id, "Channels · ${id.removePrefix(AZ_PREFIX)}")
        else -> null
    }

    /** Non-playable, non-browsable row that explains why a list is empty. */
    private fun info(id: String, title: String, subtitle: String): MediaItem =
        MediaItem.Builder().setMediaId(INFO_PREFIX + id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
                .setIsBrowsable(false).setIsPlayable(false).build()
        ).build()

    private fun emptyState(parentId: String): MediaItem = when {
        parentId == PLAYLISTS ->
            info(parentId, "No playlists", "Add a playlist in the phone app")
        IptvStorage.channels(applicationContext).isEmpty() ->
            info(parentId, "No channels yet", "Open the IPTV app on your phone once to load your playlist")
        parentId == FAVORITES ->
            info(parentId, "No favorites yet", "Use the heart button while a channel plays, or star one on the phone")
        parentId == RECENTS -> info(parentId, "Nothing watched yet", "Channels you play appear here")
        else -> info(parentId, "Nothing here", "No channels in this list")
    }

    private fun folder(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        ).build()

    private fun channelItem(ch: IptvStorage.Channel, scope: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("$CH_PREFIX$scope|${ch.id}")
            .setUri(ch.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ch.name)
                    .setArtist(ch.group)
                    .setSubtitle(listOf(ch.group, ch.country).filter { it.isNotBlank() && it != IptvStorage.UNKNOWN_COUNTRY }.joinToString(" · "))
                    .setArtworkUri(ch.logo.takeIf { it.startsWith("http") }?.toUri())
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            ).build()

    companion object {
        const val ROOT = "root"
        const val FAVORITES = "favorites"
        const val RECENTS = "recents"
        const val CATEGORIES = "categories"
        const val ALL = "all"
        const val SELECTED = "selected"
        const val HOME = "home"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST_ACTIVE = "playlist:active"
        private const val FLAT_LIMIT = 150
        const val COUNTRIES = "countries"
        const val AZ = "az"
        const val COUNTRY_PREFIX = "country:"
        const val AZ_PREFIX = "az:"
        const val INFO_PREFIX = "info:"
        const val CMD_TOGGLE_FAVORITE = "iptv.toggleFavorite"
        const val HINT_PHONE = "iptv_phone"
        private const val SELECTED_LIMIT = 500

        /** "phone" | "car" | "system": source of the most recent playback command. */
        @Volatile
        var lastOrigin = "phone"
        const val CAT_PREFIX = "cat:"
        const val SEARCH_PREFIX = "search:"
        const val CH_PREFIX = "ch|"
        private const val QUEUE_WINDOW = 100
        private const val MAX_RETRIES = 3
        private const val MAX_AUTO_SKIPS = 5

        @Volatile
        var instance: IptvPlaybackService? = null

        /** Car display Surface currently owning the video output (main thread). */
        @Volatile
        private var carSurface: android.view.Surface? = null

        /** Invoked after the car Surface is released so the phone view can re-attach. */
        @Volatile
        var onCarSurfaceCleared: (() -> Unit)? = null

        fun setCarSurface(surface: android.view.Surface) {
            carSurface = surface
            instance?.applyCarSurface()
        }

        fun clearCarSurface() {
            val old = carSurface ?: return
            carSurface = null
            instance?.player?.clearVideoSurface(old)
            onCarSurfaceCleared?.invoke()
        }

        /** mediaId format: ch|<scope>|<channelId>. */
        fun parseChannelId(mediaId: String): String? {
            if (!mediaId.startsWith(CH_PREFIX)) return null
            return mediaId.substringAfterLast('|').takeIf { it.isNotEmpty() }
        }

        fun parseScope(mediaId: String): String? {
            if (!mediaId.startsWith(CH_PREFIX)) return null
            return mediaId.removePrefix(CH_PREFIX).substringBeforeLast('|', "").takeIf { it.isNotEmpty() }
        }

        fun refreshSelected() {
            instance?.apply { main.post { notifyLibraryChanged(SELECTED); session?.notifyChildrenChanged(ROOT, 0, null) } }
        }

        fun refreshLibrary() {
            instance?.apply {
                main.post {
                    for (id in listOf(ROOT, PLAYLISTS, PLAYLIST_ACTIVE, FAVORITES, RECENTS, CATEGORIES, COUNTRIES, AZ)) {
                        notifyLibraryChanged(id)
                    }
                    notifyLibraryChanged(SELECTED)
                    updateFavoriteButton()
                }
            }
        }
    }
}
