import AVKit
import Flutter
import UIKit

public final class IptvNativePlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var sink: FlutterEventSink?
    private let core = IptvPlayerCore.shared
    fileprivate static var autoPip = false
    private var stateVersion = 0
    private func nextVersion() -> Int { stateVersion += 1; return stateVersion }

    public static func register(with registrar: FlutterPluginRegistrar) {
        // Keep the CarPlay scene delegate linked into the binary.
        _ = IptvCarPlaySceneDelegate.self

        let instance = IptvNativePlugin()
        let method = FlutterMethodChannel(name: "iptv_native/control", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: method)
        FlutterEventChannel(name: "iptv_native/events", binaryMessenger: registrar.messenger())
            .setStreamHandler(instance)
        registrar.register(IptvVideoViewFactory(), withId: "iptv_native/video_view")

        NotificationCenter.default.addObserver(instance, selector: #selector(stateChanged),
                                               name: .iptvPlayerStateChanged, object: nil)
        NotificationCenter.default.addObserver(instance, selector: #selector(userDataChanged),
                                               name: .iptvUserDataChanged, object: nil)
        NotificationCenter.default.addObserver(instance, selector: #selector(selectionChanged),
                                               name: .iptvSelectionChanged, object: nil)
        NotificationCenter.default.addObserver(instance, selector: #selector(connectionChanged),
                                               name: .iptvCarConnectionChanged, object: nil)
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    // MARK: Events

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        sink = events
        events(stateMap())
        events(connectionMap())
        events(selectionMap(IptvStorage.shared.selection()))
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? { sink = nil; return nil }

    @objc private func stateChanged() { sink?(stateMap()) }
    @objc private func userDataChanged() { sink?(["type": "userData"]) }
    @objc private func connectionChanged() { sink?(connectionMap()) }
    @objc private func selectionChanged(_ n: Notification) {
        if let sel = n.object as? IptvSelection { sink?(selectionMap(sel)) }
    }

    /// Reflects only what iOS reported: a CarPlay scene connected / disconnected.
    private func connectionMap() -> [String: Any?] {
        let c = IptvCarPlayController.shared.isConnected
        return ["type": "connection", "known": true, "connected": c, "platform": c ? "carPlay" : "none"]
    }

    private func selectionMap(_ s: IptvSelection) -> [String: Any?] {
        ["type": "selection", "scope": s.scope, "label": s.label, "version": s.version, "origin": s.origin]
    }

    private func stateMap() -> [String: Any?] {
        [
            "type": "state",
            "channelId": core.current?.id,
            "status": core.status.rawValue,
            "isPlaying": core.isPlaying,
            "error": core.errorMessage,
            "videoWidth": Int(core.videoSize.width),
            "videoHeight": Int(core.videoSize.height),
            "pip": core.pictureInPictureActive,
            "version": nextVersion(),
            "origin": core.lastOrigin,
        ]
    }

    // MARK: Methods

    private func channel(_ m: [String: Any]) -> IptvChannel? {
        guard let id = m["id"] as? String, let name = m["name"] as? String,
              let url = m["url"] as? String else { return nil }
        return IptvChannel(id: id, name: name, url: url, logo: m["logo"] as? String ?? "",
                           group: m["group"] as? String ?? "", country: "")
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any]
        switch call.method {
        case "storageDirectory": result(IptvStorage.shared.directory.path)
        case "state": result(stateMap())
        case "connection": result(connectionMap())
        case "selection": result(selectionMap(IptvStorage.shared.selection()))
        case "setSelection":
            if let scope = args?["scope"] as? String {
                IptvStorage.shared.setSelection(scope: scope, label: args?["label"] as? String ?? scope, origin: "phone")
            }
            result(nil)
        case "play":
            guard let c = (args?["channel"] as? [String: Any]).flatMap(channel) else {
                result(FlutterError(code: "bad_args", message: "channel missing", details: nil)); return
            }
            let q = (args?["queue"] as? [[String: Any]] ?? []).compactMap(channel)
            core.lastOrigin = "phone"
            core.play(c, queue: q)
            result(nil)
        case "resume": core.lastOrigin = "phone"; core.resume(); result(nil)
        case "pause": core.lastOrigin = "phone"; core.pause(); result(nil)
        case "stop": core.stop(); result(nil)
        case "next": core.lastOrigin = "phone"; core.next(); result(nil)
        case "previous": core.lastOrigin = "phone"; core.previous(); result(nil)
        case "retry": core.retry(); result(nil)
        case "dataChanged":
            NotificationCenter.default.post(name: .iptvUserDataChanged, object: nil)
            result(nil)
        case "clearRecents": IptvStorage.shared.clearRecents(); result(nil)
        case "pipSupported": result(AVPictureInPictureController.isPictureInPictureSupported())
        case "enterPip": IptvVideoView.active?.startPip(); result(nil)
        case "setAutoPip":
            IptvNativePlugin.autoPip = args?["enabled"] as? Bool ?? false
            IptvVideoView.active?.applyAutoPip()
            result(nil)
        default: result(FlutterMethodNotImplemented)
        }
    }
}

// MARK: - Video view (AVPlayerLayer on the shared AVPlayer)

final class IptvVideoView: UIView, AVPictureInPictureControllerDelegate {
    static weak var active: IptvVideoView?
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    private var pip: AVPictureInPictureController?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        playerLayer.player = IptvPlayerCore.shared.player
        playerLayer.videoGravity = .resizeAspect
        if AVPictureInPictureController.isPictureInPictureSupported() {
            pip = AVPictureInPictureController(playerLayer: playerLayer)
            pip?.delegate = self
            applyAutoPip()
        }
        IptvVideoView.active = self
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(didEnterBackground),
                       name: UIApplication.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(willEnterForeground),
                       name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    deinit {
        NotificationCenter.default.removeObserver(self)
        if IptvVideoView.active === self { IptvVideoView.active = nil }
    }

    func applyAutoPip() {
        if #available(iOS 14.2, *) {
            pip?.canStartPictureInPictureAutomaticallyFromInline = IptvNativePlugin.autoPip
        }
    }

    func startPip() { if pip?.isPictureInPicturePossible == true { pip?.startPictureInPicture() } }

    // Detach the video layer in the background so audio keeps playing (unless PiP).
    @objc private func didEnterBackground() {
        if !(pip?.isPictureInPictureActive ?? false) { playerLayer.player = nil }
    }

    @objc private func willEnterForeground() { playerLayer.player = IptvPlayerCore.shared.player }

    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        IptvPlayerCore.shared.pictureInPictureActive = true
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        IptvPlayerCore.shared.pictureInPictureActive = false
    }
}

final class IptvVideoPlatformView: NSObject, FlutterPlatformView {
    private let video = IptvVideoView(frame: .zero)
    func view() -> UIView { video }
}

final class IptvVideoViewFactory: NSObject, FlutterPlatformViewFactory {
    func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        IptvVideoPlatformView()
    }
}
