package com.example.iptv_native

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.TextureView
import android.view.View
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Bridges Flutter to the shared Media3 service. Flutter never creates its own
 * player: it drives the service's player through a MediaController and attaches
 * a TextureView for video.
 */
class IptvNativePlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, Player.Listener {

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private var methodChannel: MethodChannel? = null
    private val policyListener: () -> Unit = { main.post { emit(ParkedVideoPolicy.toMap()) } }
    private var binding: ActivityPluginBinding? = null
    private var eventChannel: EventChannel? = null
    private var sink: EventChannel.EventSink? = null

    private var controller: MediaController? = null
    private var controllerBuilding = false
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()
    private var videoView: TextureView? = null

    private var activity: Activity? = null
    private var autoPip = false
    private var inPip = false
    private var videoSize = VideoSize.UNKNOWN
    private var stateVersion = 0L

    // Local videos: file picker result + background metadata / thumbnail work.
    private var pendingPick: MethodChannel.Result? = null
    private val ioPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    // Platform-confirmed car connection (null until the platform answered).
    private var debugReceiver: android.content.BroadcastReceiver? = null
    private var carConnection: CarConnection? = null
    private var connectionType: Int? = null
    private val connectionObserver = Observer<Int> { type ->
        connectionType = type
        ParkedVideoPolicy.setProjected(type == CarConnection.CONNECTION_TYPE_PROJECTION)
        emit(connectionMap())
    }

    // ---- FlutterPlugin ------------------------------------------------------------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        instance = this
        methodChannel = MethodChannel(binding.binaryMessenger, "iptv_native/control").also {
            it.setMethodCallHandler(this)
        }
        eventChannel = EventChannel(binding.binaryMessenger, "iptv_native/events").also {
            it.setStreamHandler(this)
        }
        ParkedVideoPolicy.start(appContext as android.app.Application)
        ParkedVideoPolicy.addListener(policyListener)
        binding.platformViewRegistry.registerViewFactory(
            "iptv_native/video_view", VideoViewFactory(),
        )
        IptvStorage.onUserDataChanged = { main.post { emit(mapOf("type" to "userData")) } }
        IptvStorage.onSelectionChanged = { sel -> main.post { emit(selectionMap(sel)) } }
        IptvPlaybackService.onCarSurfaceCleared = { main.post { reattachPhoneVideo() } }
        IptvStorage.addChangeListener(changeListener)
        CarBrowserBridge.onState = { st -> main.post { emit(carBrowserMap(st)) } }
        BrowserHistory.addListener(historyListener)
        // Debug builds only: `adb shell am broadcast -a com.example.car_play.DEBUG_SEND_URL --es url <url>`
        // runs the same path as the Send to Car button (used to test without touching the phone).
        if (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            debugReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val r = CarBrowserBridge.send(i.getStringExtra("url"), carConnected())
                    android.util.Log.i("CarBrowser", "debug send -> $r")
                    if (r == CarBrowserBridge.SENT) CarBrowserBridge.awaitConfirm(6_000) { android.util.Log.i("CarBrowser", "debug send confirmed -> $it") }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(appContext, debugReceiver,
                android.content.IntentFilter("com.example.car_play.DEBUG_SEND_URL"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
        carConnection = CarConnection(appContext).also { it.type.observeForever(connectionObserver) }
        withController { }  // connect early so state is available to Flutter
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        ParkedVideoPolicy.removeListener(policyListener)
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        IptvStorage.onUserDataChanged = null
        IptvStorage.onSelectionChanged = null
        IptvPlaybackService.onCarSurfaceCleared = null
        IptvStorage.removeChangeListener(changeListener)
        CarBrowserBridge.onState = null
        BrowserHistory.removeListener(historyListener)
        debugReceiver?.let { try { appContext.unregisterReceiver(it) } catch (_: Throwable) {} }
        debugReceiver = null
        carConnection?.type?.removeObserver(connectionObserver)
        carConnection = null
        // The service (and its playback) keeps running; only release our controller.
        controller?.removeListener(this)
        controller?.release()
        controller = null
        controllerBuilding = false
        ioPool.shutdown()
        pendingPick?.success(emptyList<Any>())
        pendingPick = null
        if (instance === this) instance = null
    }

    private fun attach(b: ActivityPluginBinding) {
        binding?.removeActivityResultListener(pickResultListener)
        activity = b.activity; binding = b
        b.addActivityResultListener(pickResultListener)
    }
    private fun detach() {
        binding?.removeActivityResultListener(pickResultListener)
        binding = null; activity = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attach(binding)
    override fun onDetachedFromActivityForConfigChanges() = detach()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = attach(binding)
    override fun onDetachedFromActivity() = detach()

    // ---- MediaController ---------------------------------------------------------

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        pendingActions.add(action)
        if (controllerBuilding) return
        controllerBuilding = true
        val token = SessionToken(appContext, ComponentName(appContext, IptvPlaybackService::class.java))
        // The hint lets the service tell phone-originated commands from car/system ones.
        val hints = android.os.Bundle().apply { putBoolean(IptvPlaybackService.HINT_PHONE, true) }
        val future = MediaController.Builder(appContext, token).setConnectionHints(hints).buildAsync()
        future.addListener({
            controllerBuilding = false
            val c = try { future.get() } catch (e: Exception) { pendingActions.clear(); return@addListener }
            controller = c
            c.addListener(this)
            videoView?.let { c.setVideoTextureView(it) }
            val actions = pendingActions.toList()
            pendingActions.clear()
            actions.forEach { it(c) }
            emitState()
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * Fit / Fill applied to the real video surface: the TextureView always matches the stage,
     * and its content transform shrinks (Fit: whole picture, bars) or grows (Fill: cover, crop
     * the overflow) the picture while keeping its aspect ratio.
     */
    private fun applyFit() {
        val tv = videoView ?: return
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (w <= 0f || h <= 0f || videoSize.width <= 0 || videoSize.height <= 0) { tv.setTransform(null); return }
        val va = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        val sa = w / h
        val fill = IptvStorage.videoFit(appContext) == IptvStorage.FILL
        val sx: Float
        val sy: Float
        if (!fill) { sx = if (va > sa) 1f else va / sa; sy = if (va > sa) sa / va else 1f }
        else { sx = if (va > sa) va / sa else 1f; sy = if (va > sa) 1f else sa / va }
        tv.setTransform(android.graphics.Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
    }

    /** The car released the output Surface: give the video back to the phone's TextureView. */
    private fun reattachPhoneVideo() {
        val c = controller ?: return
        val tv = videoView ?: return
        c.clearVideoTextureView(tv)
        c.setVideoTextureView(tv)
    }

    // ---- Player.Listener -----------------------------------------------------------

    override fun onEvents(player: Player, events: Player.Events) = emitState()

    override fun onVideoSizeChanged(size: VideoSize) {
        videoSize = size
        applyFit()
        emitState()
    }

    override fun onPlayerError(error: PlaybackException) = emitState()

    private fun stateMap(): Map<String, Any?> {
        val c = controller
        val err = c?.playerError
        val status = when {
            c == null -> "idle"
            err != null -> "error"
            c.mediaItemCount == 0 -> "idle"
            else -> when (c.playbackState) {
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "idle"
            }
        }
        return mapOf(
            "type" to "state",
            "channelId" to c?.currentMediaItem?.mediaId?.let(IptvPlaybackService::parseChannelId),
            "status" to status,
            "isPlaying" to (c?.isPlaying ?: false),
            "error" to err?.let { "${it.errorCodeName}: ${it.message ?: ""}" },
            // Pixel aspect ratio applied, so Dart sees the displayed shape.
            "videoWidth" to (videoSize.width * videoSize.pixelWidthHeightRatio).toInt(),
            "videoHeight" to videoSize.height,
            "pip" to inPip,
            "isLocal" to IptvPlaybackService.isLocal(c?.currentMediaItem?.mediaId),
            "positionMs" to (c?.currentPosition?.coerceAtLeast(0L) ?: 0L),
            "durationMs" to (c?.duration?.takeIf { it != C.TIME_UNSET } ?: -1L),
            "version" to ++stateVersion,
            "origin" to IptvPlaybackService.lastOrigin,
        )
    }

    /** Only values the platform reported; "unknown" until CarConnection answers. */
    private fun connectionMap(): Map<String, Any?> {
        val t = connectionType
        return mapOf(
            "type" to "connection",
            "known" to (t != null),
            "connected" to (t == CarConnection.CONNECTION_TYPE_PROJECTION || t == CarConnection.CONNECTION_TYPE_NATIVE),
            "platform" to when (t) {
                CarConnection.CONNECTION_TYPE_PROJECTION -> "androidAuto"
                CarConnection.CONNECTION_TYPE_NATIVE -> "androidAutomotive"
                else -> "none"
            },
        )
    }

    private fun settingsMap() = mapOf(
        "type" to "settings",
        "output" to IptvStorage.outputMode(appContext),
        "fit" to IptvStorage.videoFit(appContext),
    )

    private val changeListener: (String) -> Unit = { kind ->
        if (kind == "settings") main.post {
            IptvPlaybackService.setFill(IptvStorage.videoFit(appContext) == IptvStorage.FILL)
            applyFit()
            emit(settingsMap())
        }
    }

    private fun selectionMap(sel: IptvStorage.Selection) = mapOf(
        "type" to "selection", "scope" to sel.scope, "label" to sel.label,
        "version" to sel.version, "origin" to sel.origin,
    )

    private val historyListener: () -> Unit = { main.post { emit(mapOf("type" to "browserHistory")) } }

    private fun historyList() = BrowserHistory.list(appContext).map { mapOf("u" to it.url, "t" to it.title, "at" to it.time) }

    private fun carBrowserMap(s: CarBrowserBridge.State) = mapOf(
        "type" to "carBrowser", "open" to s.open, "url" to s.url, "title" to s.title, "loading" to s.loading,
    )

    private fun carConnected() =
        connectionType == CarConnection.CONNECTION_TYPE_PROJECTION || connectionType == CarConnection.CONNECTION_TYPE_NATIVE

    /** Non-web links (tel:, mailto:, intent:, market:) belong to the system, not to the in-app browser. */
    private fun openExternal(url: String): Boolean {
        return try {
            val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.selector = null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        } catch (_: Throwable) { false }
    }

    private fun emitState() = emit(stateMap())

    private fun emit(event: Map<String, Any?>) {
        if (Looper.myLooper() == Looper.getMainLooper()) sink?.success(event)
        else main.post { sink?.success(event) }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
        emitState()
        emit(connectionMap())
        emit(selectionMap(IptvStorage.selection(appContext)))
        emit(ParkedVideoPolicy.toMap())
        emit(settingsMap())
        emit(carBrowserMap(CarBrowserBridge.state))
    }
    override fun onCancel(arguments: Any?) { sink = null }

    // ---- MethodCallHandler -----------------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "storageDirectory" -> result.success(IptvStorage.dir(appContext).absolutePath)
            "state" -> result.success(stateMap())
            "play" -> {
                requestNotificationPermission()
                @Suppress("UNCHECKED_CAST")
                val channel = call.argument<Map<String, Any?>>("channel")!!
                @Suppress("UNCHECKED_CAST")
                val queue = call.argument<List<Map<String, Any?>>>("queue") ?: emptyList()
                withController { c ->
                    val items = windowed(channel, queue)
                    c.setMediaItems(items.first, items.second, C.TIME_UNSET)
                    c.prepare()
                    c.play()
                }
                result.success(null)
            }
            "resume" -> { withController { it.play() }; result.success(null) }
            "pause" -> { withController { it.pause() }; result.success(null) }
            "stop" -> { withController { it.stop(); it.clearMediaItems() }; result.success(null) }
            "next" -> { withController { it.seekToNextMediaItem() }; result.success(null) }
            "previous" -> { withController { it.seekToPreviousMediaItem() }; result.success(null) }
            "retry" -> {
                withController { it.seekToDefaultPosition(); it.prepare(); it.play() }
                result.success(null)
            }
            "seekTo" -> {
                val ms = (call.argument<Number>("positionMs") ?: 0).toLong()
                withController { it.seekTo(ms.coerceAtLeast(0L)) }
                result.success(null)
            }
            "position" -> {
                val c = controller
                result.success(mapOf(
                    "positionMs" to (c?.currentPosition?.coerceAtLeast(0L) ?: 0L),
                    "durationMs" to (c?.duration?.takeIf { it != C.TIME_UNSET } ?: -1L),
                    "bufferedMs" to (c?.bufferedPosition ?: 0L),
                ))
            }
            "pickVideos" -> pickVideos(result)
            "videoThumbnail" -> {
                val uri = call.argument<String>("uri")
                if (uri == null) { result.success(null) } else {
                    runIo(result) { videoThumbnail(Uri.parse(uri)) }
                }
            }
            "describeVideo" -> {
                val uri = call.argument<String>("uri")
                if (uri == null) { result.success(null) } else {
                    runIo(result) { describeVideo(Uri.parse(uri)) }
                }
            }
            "releaseVideoUri" -> {
                call.argument<String>("uri")?.let {
                    try {
                        appContext.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Throwable) {}
                }
                result.success(null)
            }
            "parkedVideo" -> result.success(ParkedVideoPolicy.toMap())
            "debugSimulateDriving" -> {
                val mode = when (call.argument<String>("mode")) {
                    "audioOk" -> ParkedVideoPolicy.Simulation.DRIVING_AUDIO_OK
                    "noAudio" -> ParkedVideoPolicy.Simulation.DRIVING_NO_AUDIO
                    else -> ParkedVideoPolicy.Simulation.OFF
                }
                result.success(ParkedVideoPolicy.simulate(mode))
            }
            "settings" -> result.success(settingsMap())
            "setOutput" -> {
                IptvStorage.setOutputMode(appContext, call.argument<String>("output") ?: IptvStorage.OUTPUT_PHONE)
                result.success(null)
            }
            "setFit" -> {
                IptvStorage.setVideoFit(appContext, call.argument<String>("fit") ?: IptvStorage.FIT)
                result.success(null)
            }
            "connection" -> result.success(connectionMap())
            "sendBrowserUrl" -> {
                val r = CarBrowserBridge.send(call.argument<String>("url"), carConnected())
                // "sent" is only reported once the car confirms it is showing the page.
                if (r == CarBrowserBridge.SENT) CarBrowserBridge.awaitConfirm(6_000) { result.success(it) }
                else result.success(r)
            }
            "syncBrowserUrl" -> result.success(CarBrowserBridge.sync(call.argument<String>("url"), carConnected()))
            "carBrowser" -> result.success(carBrowserMap(CarBrowserBridge.state))
            "browserHistory" -> result.success(historyList())
            "recordBrowserHistory" -> {
                BrowserHistory.record(appContext, call.argument<String>("url") ?: "", call.argument<String>("title") ?: "")
                result.success(null)
            }
            "removeBrowserHistory" -> { BrowserHistory.remove(appContext, call.argument<String>("url") ?: ""); result.success(null) }
            "clearBrowserHistory" -> { BrowserHistory.clear(appContext); result.success(null) }
            "importBrowserHistory" -> {
                val items = (call.argument<List<Map<String, Any?>>>("items") ?: emptyList()).mapNotNull {
                    val u = it["u"] as? String ?: return@mapNotNull null
                    BrowserHistory.Entry(u, it["t"] as? String ?: "", (it["at"] as? Number)?.toLong() ?: 0L)
                }
                BrowserHistory.import(appContext, items)
                result.success(null)
            }
            "openExternal" -> result.success(openExternal(call.argument<String>("url") ?: ""))
            "selection" -> result.success(selectionMap(IptvStorage.selection(appContext)))
            "setSelection" -> {
                val scope = call.argument<String>("scope")!!
                val label = call.argument<String>("label") ?: scope
                if (IptvStorage.setSelection(appContext, scope, label, "phone")) {
                    IptvPlaybackService.refreshSelected()
                }
                result.success(null)
            }
            "dataChanged" -> { IptvPlaybackService.refreshLibrary(); result.success(null) }
            "clearRecents" -> { IptvStorage.clearRecents(appContext); result.success(null) }
            "pipSupported" -> result.success(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            )
            "enterPip" -> { enterPip(); result.success(null) }
            "setAutoPip" -> {
                autoPip = call.argument<Boolean>("enabled") ?: false
                applyAutoPipParams()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun windowed(
        channel: Map<String, Any?>,
        queue: List<Map<String, Any?>>,
    ): Pair<List<MediaItem>, Int> {
        val id = channel["id"] as String
        val list = if (queue.any { it["id"] == id }) queue else listOf(channel)
        val idx = list.indexOfFirst { it["id"] == id }
        val from = (idx - WINDOW).coerceAtLeast(0)
        val to = (idx + WINDOW + 1).coerceAtMost(list.size)
        val items = list.subList(from, to).map(::toItem)
        return items to (idx - from)
    }

    private fun toItem(m: Map<String, Any?>): MediaItem {
        val url = m["url"] as String
        // A content:// or file:// URI is a local video: same engine, same queue, but a distinct
        // scope so the service can treat it as seekable / non-live / not an IPTV recent.
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return MediaItem.Builder()
                .setMediaId("${IptvPlaybackService.CH_PREFIX}${IptvPlaybackService.LOCAL_SCOPE}|${m["id"]}")
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(m["name"] as? String)
                        .setArtist("Local video")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                        .build()
                ).build()
        }
        val logo = (m["logo"] as? String).orEmpty()
        return MediaItem.Builder()
            .setMediaId("${IptvPlaybackService.CH_PREFIX}ui|${m["id"]}")
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(m["name"] as? String)
                    .setArtist(m["group"] as? String)
                    .setArtworkUri(if (logo.startsWith("http")) android.net.Uri.parse(logo) else null)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            ).build()
    }

    // ---- Local videos (Storage Access Framework: no storage permission needed) -------------

    /** Runs [work] off the main thread and posts its value back as the method result. */
    private fun runIo(result: MethodChannel.Result, work: () -> Any?) {
        try {
            ioPool.execute {
                val v = try { work() } catch (_: Throwable) { null }
                main.post { result.success(v) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { result.success(null) }
    }

    /**
     * System document picker (ACTION_OPEN_DOCUMENT, any video MIME type, multi-select). The user browses any
     * storage / cloud provider; the app gets a read grant that we persist so the library survives
     * restarts. No READ_EXTERNAL_STORAGE / READ_MEDIA_VIDEO permission is required.
     */
    private fun pickVideos(result: MethodChannel.Result) {
        val a = activity
        if (a == null) { result.error("no_activity", "No foreground activity", null); return }
        if (pendingPick != null) { result.error("busy", "A picker is already open", null); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pendingPick = result
        try { a.startActivityForResult(intent, REQ_PICK_VIDEOS) }
        catch (e: Exception) { pendingPick = null; result.error("no_picker", e.message, null) }
    }

    private val pickResultListener = io.flutter.plugin.common.PluginRegistry.ActivityResultListener { req, code, data ->
        if (req != REQ_PICK_VIDEOS) return@ActivityResultListener false
        val res = pendingPick ?: return@ActivityResultListener true
        pendingPick = null
        val uris = ArrayList<Uri>()
        if (code == Activity.RESULT_OK && data != null) {
            data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
        }
        for (u in uris) {
            try {
                appContext.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) { /* provider without persistable grants: usable until restart */ }
        }
        runIo(res) { uris.map { describeVideo(it) } }
        true
    }

    private fun describeVideo(uri: Uri): Map<String, Any?> {
        var name: String? = null
        var size = -1L
        try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        } catch (_: Throwable) {}
        var duration = -1L
        var w = 0
        var h = 0
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(appContext, uri)
            duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
        return mapOf(
            "uri" to uri.toString(),
            "name" to (name ?: uri.lastPathSegment ?: "Video"),
            "durationMs" to duration, "sizeBytes" to size, "width" to w, "height" to h,
        )
    }

    /** JPEG thumbnail (about 320 px wide) or null when the file has no decodable frame. */
    private fun videoThumbnail(uri: Uri): ByteArray? {
        var bmp: android.graphics.Bitmap? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try { bmp = appContext.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null) } catch (_: Throwable) {}
        }
        if (bmp == null) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(appContext, uri)
                bmp = r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.getFrameAtTime()
            } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
        }
        val b = bmp ?: return null
        val scaled = if (b.width > 320) {
            android.graphics.Bitmap.createScaledBitmap(b, 320, (b.height * 320f / b.width).toInt().coerceAtLeast(1), true)
        } else b
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun requestNotificationPermission() {
        val a = activity ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Needed only to show the media notification; playback works without it.
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301)
        }
    }

    // ---- Picture in Picture ----------------------------------------------------------

    private fun pipParams(auto: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val w = if (videoSize.width > 0) videoSize.width else 16
        val h = if (videoSize.height > 0) videoSize.height else 9
        // Android only accepts ratios between 1:2.39 and 2.39:1.
        val ratio = (w.toFloat() / h).coerceIn(0.42f, 2.38f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(auto)
        return b.build()
    }

    private fun applyAutoPipParams() {
        val a = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pipParams(autoPip)?.let { a.setPictureInPictureParams(it) }
        }
    }

    private fun enterPip() {
        val a = activity ?: return
        pipParams(false)?.let { a.enterPictureInPictureMode(it) }
    }

    private fun pipModeChanged(inPip: Boolean) {
        this.inPip = inPip
        emitState()
    }

    // ---- Video platform view --------------------------------------------------------

    private inner class VideoViewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
            val tv = object : TextureView(context) {
                override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                    super.onSizeChanged(w, h, ow, oh)
                    applyFit()
                }

                // A second view (full screen) gets its SurfaceTexture only after layout: bind the
                // player to it again once it is attached so the first frames are not dropped.
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    if (videoView === this) post {
                        controller?.let { it.clearVideoTextureView(this); it.setVideoTextureView(this) }
                    }
                }
            }
            return object : PlatformView {
                init {
                    videoView = tv
                    controller?.setVideoTextureView(tv)
                    applyFit()
                }
                override fun getView(): View = tv
                override fun dispose() {
                    if (videoView === tv) {
                        controller?.clearVideoTextureView(tv)
                        videoView = null
                    }
                }
            }
        }
    }

    companion object {
        private const val WINDOW = 100
        private const val REQ_PICK_VIDEOS = 7302
        private var instance: IptvNativePlugin? = null

        /** Called from MainActivity.onPictureInPictureModeChanged. */
        @JvmStatic
        fun onPictureInPictureModeChanged(isInPip: Boolean) = instance?.pipModeChanged(isInPip)

        /** Called from MainActivity.onUserLeaveHint (auto-PiP on Android < 12). */
        @JvmStatic
        fun onUserLeaveHint() {
            val p = instance ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && p.autoPip &&
                p.controller?.isPlaying == true
            ) p.enterPip()
        }
    }
}
