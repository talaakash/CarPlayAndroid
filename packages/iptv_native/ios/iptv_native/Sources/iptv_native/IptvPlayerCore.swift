import AVFoundation
import MediaPlayer
import Network
import UIKit

public enum IptvStatus: String { case idle, buffering, ready, ended, error }

/// The single playback engine on iOS. Shared by the Flutter UI (video through an
/// AVPlayerLayer attached to `player`) and CarPlay (audio of the same stream via the
/// Now Playing template). It works with or without a Flutter engine running.
public final class IptvPlayerCore: NSObject {
    public static let shared = IptvPlayerCore()

    public let player = AVPlayer()
    public private(set) var queue: [IptvChannel] = []
    public private(set) var index = 0
    /// phone | car | system: source of the most recent playback command.
    public var lastOrigin = "phone"
    public private(set) var errorMessage: String?
    public private(set) var videoSize = CGSize.zero
    public var pictureInPictureActive = false {
        didSet { postState() }
    }

    public var current: IptvChannel? { queue.indices.contains(index) ? queue[index] : nil }

    private var wantsPlaying = false
    private var retryCount = 0
    private var itemObservers: [NSKeyValueObservation] = []
    private var playerObservers: [NSKeyValueObservation] = []
    private let monitor = NWPathMonitor()
    private var artworkCache: [String: UIImage] = [:]
    private static let window = 100
    private static let maxRetries = 3

    private override init() {
        super.init()
        player.automaticallyWaitsToMinimizeStalling = true
        player.allowsExternalPlayback = false

        playerObservers.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            self?.postState(); self?.updateNowPlaying()
        })

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(interruption(_:)),
                       name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(routeChanged(_:)),
                       name: AVAudioSession.routeChangeNotification, object: nil)
        nc.addObserver(self, selector: #selector(itemFailed(_:)),
                       name: .AVPlayerItemFailedToPlayToEndTime, object: nil)
        nc.addObserver(self, selector: #selector(itemEnded(_:)),
                       name: .AVPlayerItemDidPlayToEndTime, object: nil)
        nc.addObserver(self, selector: #selector(itemStalled(_:)),
                       name: .AVPlayerItemPlaybackStalled, object: nil)

        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            DispatchQueue.main.async {
                guard let self, self.wantsPlaying, self.status == .error else { return }
                self.retryCount = 0
                self.reloadCurrent()
            }
        }
        monitor.start(queue: DispatchQueue(label: "iptv.network"))
        setupRemoteCommands()
    }

    // MARK: Public API

    public func play(_ channel: IptvChannel, queue newQueue: [IptvChannel]) {
        configureAudioSession()
        let list = newQueue.contains(where: { $0.id == channel.id }) ? newQueue : [channel]
        let idx = list.firstIndex(where: { $0.id == channel.id }) ?? 0
        let from = max(0, idx - Self.window)
        let to = min(list.count, idx + Self.window + 1)
        queue = Array(list[from..<to])
        index = idx - from
        retryCount = 0
        wantsPlaying = true
        loadCurrent()
    }

    public func resume() {
        configureAudioSession()
        wantsPlaying = true
        if status == .error || status == .ended { reloadCurrent() } else { player.play() }
    }

    public func pause() { wantsPlaying = false; player.pause(); postState() }

    public func stop() {
        wantsPlaying = false
        player.pause()
        player.replaceCurrentItem(with: nil)
        queue = []; index = 0; errorMessage = nil; videoSize = .zero
        itemObservers.removeAll()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        postState()
    }

    public func next() { step(1) }
    public func previous() { step(-1) }

    public func retry() { retryCount = 0; wantsPlaying = true; reloadCurrent() }

    private func step(_ delta: Int) {
        let n = index + delta
        guard queue.indices.contains(n) else { return }
        index = n
        retryCount = 0
        wantsPlaying = true
        loadCurrent()
    }

    public var status: IptvStatus {
        guard let item = player.currentItem else { return .idle }
        if errorMessage != nil || item.status == .failed { return .error }
        if item.status == .unknown { return .buffering }
        if player.timeControlStatus == .waitingToPlayAtSpecifiedRate { return .buffering }
        return .ready
    }

    public var isPlaying: Bool { player.timeControlStatus == .playing }

    // MARK: Loading

    private func configureAudioSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, options: [])
        try? s.setActive(true)
    }

    private func loadCurrent() {
        guard let ch = current, let url = URL(string: ch.url) else {
            errorMessage = "Invalid stream URL"
            postState()
            return
        }
        errorMessage = nil
        videoSize = .zero
        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = 10
        observe(item)
        player.replaceCurrentItem(with: item)
        if wantsPlaying { player.play() }
        IptvStorage.shared.addRecent(ch.id)
        updateNowPlaying()
        postState()
    }

    private func reloadCurrent() {
        guard current != nil else { return }
        loadCurrent()
    }

    private func observe(_ item: AVPlayerItem) {
        itemObservers.removeAll()
        itemObservers.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                if item.status == .failed { self.handleFailure(item.error) }
                else if item.status == .readyToPlay { self.retryCount = 0 }
                self.postState()
            }
        })
        itemObservers.append(item.observe(\.presentationSize, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async { self?.videoSize = item.presentationSize; self?.postState() }
        })
    }

    private func handleFailure(_ error: Error?) {
        let ns = error as NSError?
        errorMessage = ns?.localizedDescription ?? "Playback failed"
        // Retry transient network errors with a back-off; dead streams stay in error.
        if ns?.domain == NSURLErrorDomain, retryCount < Self.maxRetries, wantsPlaying {
            retryCount += 1
            let delay = Double(retryCount) * 2
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self, self.status == .error, self.wantsPlaying else { return }
                self.reloadCurrent()
            }
        }
        postState()
    }

    // MARK: Notifications

    @objc private func itemFailed(_ n: Notification) {
        guard (n.object as? AVPlayerItem) === player.currentItem else { return }
        let e = n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
        DispatchQueue.main.async { self.handleFailure(e) }
    }

    @objc private func itemEnded(_ n: Notification) {
        guard (n.object as? AVPlayerItem) === player.currentItem else { return }
        // Live streams should not end; if the server closes it, try to reconnect.
        DispatchQueue.main.async { if self.wantsPlaying { self.reloadCurrent() } }
    }

    @objc private func itemStalled(_ n: Notification) { postState() }

    @objc private func interruption(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        DispatchQueue.main.async {
            if type == .ended {
                let opts = (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
                if AVAudioSession.InterruptionOptions(rawValue: opts).contains(.shouldResume), self.wantsPlaying {
                    self.configureAudioSession()
                    self.player.play()
                }
            }
            self.postState()
        }
    }

    /// Headphones / Bluetooth / CarPlay output removed -> pause, like the system player.
    @objc private func routeChanged(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
        DispatchQueue.main.async { if self.isPlaying { self.pause() } }
    }

    private func postState() {
        let post = { NotificationCenter.default.post(name: .iptvPlayerStateChanged, object: nil) }
        if Thread.isMainThread { post() } else { DispatchQueue.main.async(execute: post) }
    }

    // MARK: Lock screen / CarPlay Now Playing

    private func setupRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.lastOrigin = "system"; self?.resume(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.lastOrigin = "system"; self?.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            if self.isPlaying { self.pause() } else { self.resume() }
            return .success
        }
        c.nextTrackCommand.addTarget { [weak self] _ in self?.lastOrigin = "system"; self?.next(); return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.lastOrigin = "system"; self?.previous(); return .success }
        c.skipForwardCommand.isEnabled = false
        c.skipBackwardCommand.isEnabled = false
        c.changePlaybackPositionCommand.isEnabled = false
        c.seekForwardCommand.isEnabled = false
        c.seekBackwardCommand.isEnabled = false
    }

    private func updateNowPlaying() {
        guard let ch = current else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: ch.name,
            MPMediaItemPropertyArtist: ch.group,
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let img = artworkCache[ch.logo] {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        } else if ch.logo.hasPrefix("http"), let u = URL(string: ch.logo) {
            URLSession.shared.dataTask(with: u) { [weak self] data, _, _ in
                guard let self, let data, let img = UIImage(data: data) else { return }
                DispatchQueue.main.async {
                    self.artworkCache[ch.logo] = img
                    if self.current?.id == ch.id { self.updateNowPlaying() }
                }
            }.resume()
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: Logos (shared with CarPlay)

    func loadLogo(_ ch: IptvChannel, completion: @escaping (UIImage) -> Void) {
        if let img = artworkCache[ch.logo] { completion(img); return }
        guard ch.logo.hasPrefix("http"), let u = URL(string: ch.logo) else { return }
        URLSession.shared.dataTask(with: u) { [weak self] data, _, _ in
            guard let data, let img = UIImage(data: data) else { return }
            DispatchQueue.main.async { self?.artworkCache[ch.logo] = img; completion(img) }
        }.resume()
    }
}
