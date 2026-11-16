package com.example.iptv_native

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
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

    // Platform-confirmed car connection (null until the platform answered).
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
        carConnection?.type?.removeObserver(connectionObserver)
        carConnection = null
        // The service (and its playback) keeps running; only release our controller.
        controller?.removeListener(this)
        controller?.release()
        controller = null
        controllerBuilding = false
        if (instance === this) instance = null
    }

    private fun attach(b: ActivityPluginBinding) { activity = b.activity; binding = b }
    private fun detach() { binding = null; activity = null }

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
            "videoWidth" to videoSize.width,
            "videoHeight" to videoSize.height,
            "pip" to inPip,
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
            emit(settingsMap())
        }
    }

    private fun selectionMap(sel: IptvStorage.Selection) = mapOf(
        "type" to "selection", "scope" to sel.scope, "label" to sel.label,
        "version" to sel.version, "origin" to sel.origin,
    )

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
        val logo = (m["logo"] as? String).orEmpty()
        return MediaItem.Builder()
            .setMediaId("${IptvPlaybackService.CH_PREFIX}ui|${m["id"]}")
            .setUri(m["url"] as String)
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
            val tv = TextureView(context)
            return object : PlatformView {
                init {
                    videoView = tv
                    controller?.setVideoTextureView(tv)
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
