import CarPlay
import UIKit

/// Scene delegate for the CarPlay scene (see UIApplicationSceneManifest in Info.plist).
/// CarPlay can launch the app without a Flutter engine, so everything here talks to the
/// native `IptvPlayerCore` and `IptvStorage` only.
@objc(IptvCarPlaySceneDelegate)
public final class IptvCarPlaySceneDelegate: NSObject, CPTemplateApplicationSceneDelegate {
    public func templateApplicationScene(_ scene: CPTemplateApplicationScene,
                                         didConnect interfaceController: CPInterfaceController) {
        IptvCarPlayController.shared.connect(interfaceController)
    }

    public func templateApplicationScene(_ scene: CPTemplateApplicationScene,
                                         didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        IptvCarPlayController.shared.disconnect()
    }
}

/// Builds the audio-app templates: tab bar (Favorites / Recent / Categories) -> list
/// templates -> Now Playing. Playback state is NOT owned here, so disconnecting and
/// reconnecting CarPlay never loses the current channel.
final class IptvCarPlayController: NSObject, CPSessionConfigurationDelegate {
    static let shared = IptvCarPlayController()

    /// Session info from CarPlay. Whether video may be presented (parked, supported
    /// vehicle, entitlement) is decided by the system, not by this app; the app only
    /// declares a preferred presentation per item and always keeps audio as the fallback.
    private var sessionConfiguration: CPSessionConfiguration?

    /// True when the connected CarPlay system reports video playback support (iOS 26.4+).
    var videoPlaybackSupported: Bool {
        if #available(iOS 26.4, *) { return sessionConfiguration?.supportsVideoPlayback ?? false }
        return false
    }

    private var interface: CPInterfaceController?
    private var phoneTemplate: CPListTemplate?
    /// True only while iOS reports a connected CarPlay scene.
    private(set) var isConnected = false
    private var favoritesTemplate: CPListTemplate?
    private var recentsTemplate: CPListTemplate?
    private var categoriesTemplate: CPListTemplate?
    private var observing = false

    private var maxItems: Int { max(1, CPListTemplate.maximumItemCount) }

    func connect(_ controller: CPInterfaceController) {
        interface = controller
        sessionConfiguration = CPSessionConfiguration(delegate: self)
        let fav = CPListTemplate(title: "Favorites", sections: [])
        fav.tabTitle = "Favorites"
        fav.tabImage = UIImage(systemName: "heart.fill")
        let rec = CPListTemplate(title: "Recent", sections: [])
        rec.tabTitle = "Recent"
        rec.tabImage = UIImage(systemName: "clock.fill")
        let cat = CPListTemplate(title: "Categories", sections: [])
        cat.tabTitle = "Categories"
        cat.tabImage = UIImage(systemName: "square.grid.2x2.fill")
        let phone = CPListTemplate(title: "Phone", sections: [])
        phone.tabTitle = "Phone"
        phone.tabImage = UIImage(systemName: "iphone")
        favoritesTemplate = fav; recentsTemplate = rec; categoriesTemplate = cat; phoneTemplate = phone
        refresh()

        let tabs = CPTabBarTemplate(templates: [phone, fav, rec, cat])
        tabs.delegate = nil
        controller.setRootTemplate(tabs, animated: false, completion: nil)

        isConnected = true
        NotificationCenter.default.post(name: .iptvCarConnectionChanged, object: nil)

        if !observing {
            observing = true
            NotificationCenter.default.addObserver(self, selector: #selector(dataChanged),
                                                   name: .iptvSelectionChanged, object: nil)
            NotificationCenter.default.addObserver(self, selector: #selector(dataChanged),
                                                   name: .iptvUserDataChanged, object: nil)
        }
        // Reconnect while something is playing: go straight back to Now Playing.
        if IptvPlayerCore.shared.current != nil {
            controller.pushTemplate(CPNowPlayingTemplate.shared, animated: false, completion: nil)
        }
    }

    // CPSessionConfigurationDelegate: availability can change at any time, so rebuild items.
    func sessionConfiguration(_ sessionConfiguration: CPSessionConfiguration,
                              limitedUserInterfacesChanged limitedUserInterfaces: CPLimitableUserInterface) {
        DispatchQueue.main.async { self.refresh() }
    }

    func disconnect() {
        sessionConfiguration = nil
        isConnected = false
        NotificationCenter.default.post(name: .iptvCarConnectionChanged, object: nil)
        phoneTemplate = nil
        interface = nil
        favoritesTemplate = nil; recentsTemplate = nil; categoriesTemplate = nil
    }

    @objc private func dataChanged() { DispatchQueue.main.async { self.refresh() } }

    private func refresh() {
        let store = IptvStorage.shared
        let sel = store.selection()
        // The template title is fixed; the phone's current selection is shown as the section header.
        let phoneSection = channelSection(store.channels(forScope: sel.scope))
        phoneTemplate?.updateSections([CPListSection(items: phoneSection.items, header: "Phone: \(sel.label)", sectionIndexTitle: nil)])
        favoritesTemplate?.updateSections([channelSection(store.favorites())])
        recentsTemplate?.updateSections([channelSection(store.recents())])
        categoriesTemplate?.updateSections([categorySection()])
        if #available(iOS 15.4, *) {
            phoneTemplate?.emptyViewSubtitleVariants = ["Choose a category or channel list in the phone app."]
            favoritesTemplate?.emptyViewSubtitleVariants = ["Mark channels as favorite in the phone app."]
            recentsTemplate?.emptyViewSubtitleVariants = ["Channels you watch will appear here."]
            categoriesTemplate?.emptyViewSubtitleVariants = ["Open the app on your phone to load the playlist."]
        }
    }

    private func channelSection(_ channels: [IptvChannel]) -> CPListSection {
        let list = Array(channels.prefix(maxItems))
        return CPListSection(items: list.map { item(for: $0, queue: list) })
    }

    private func categorySection() -> CPListSection {
        let groups = IptvStorage.shared.groups().prefix(maxItems)
        let items: [CPListItem] = groups.map { (name, count) in
            let li = CPListItem(text: name, detailText: "\(count) channels")
            li.accessoryType = .disclosureIndicator
            // Video where CarPlay supports it and the system allows it (it enforces parked-only
        // itself); otherwise the system shows Now Playing and audio keeps playing.
        if #available(iOS 26.4, *), videoPlaybackSupported {
            li.playbackConfiguration = CPPlaybackConfiguration(
                preferredPresentation: .video, playbackAction: .play,
                elapsedTime: .zero, duration: .zero)  // duration 0 = live stream
        }
        li.handler = { [weak self] _, completion in
                self?.showCategory(name)
                completion()
            }
            return li
        }
        return CPListSection(items: items)
    }

    private func showCategory(_ name: String) {
        IptvStorage.shared.setSelection(scope: "cat:" + name, label: name, origin: "car")
        let channels = Array(IptvStorage.shared.channels(inGroup: name).prefix(maxItems))
        let t = CPListTemplate(title: name, sections: [CPListSection(items: channels.map { item(for: $0, queue: channels) })])
        interface?.pushTemplate(t, animated: true, completion: nil)
    }

    private func item(for ch: IptvChannel, queue: [IptvChannel]) -> CPListItem {
        let li = CPListItem(text: ch.name, detailText: ch.group)
        li.isPlaying = IptvPlayerCore.shared.current?.id == ch.id
        IptvPlayerCore.shared.loadLogo(ch) { [weak li] img in li?.setImage(img) }
        li.handler = { [weak self] _, completion in
            IptvPlayerCore.shared.lastOrigin = "car"
            IptvPlayerCore.shared.play(ch, queue: queue)
            self?.showNowPlaying()
            completion()
        }
        return li
    }

    private func showNowPlaying() {
        guard let ic = interface else { return }
        if ic.topTemplate === CPNowPlayingTemplate.shared { return }
        ic.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
    }
}
