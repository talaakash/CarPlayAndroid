import Foundation

public struct IptvChannel {
    public let id: String
    public let name: String
    public let url: String
    public let logo: String
    public let group: String
    public let country: String
}

extension Notification.Name {
    static let iptvPlayerStateChanged = Notification.Name("iptv.playerStateChanged")
    static let iptvUserDataChanged = Notification.Name("iptv.userDataChanged")
    static let iptvSelectionChanged = Notification.Name("iptv.selectionChanged")
    static let iptvCarConnectionChanged = Notification.Name("iptv.carConnectionChanged")
}

/// Read-only view of the channel cache written by Dart's PlaylistService, plus the
/// favorites file (Dart is the only writer) and the recents file (native is the only
/// writer). One writer per file avoids cross-runtime races.
public final class IptvStorage {
    public static let shared = IptvStorage()
    static let maxRecents = 50

    private let lock = NSLock()
    private var cachedStamp: String = ""
    private var cachedChannels: [IptvChannel] = []
    private var cachedById: [String: IptvChannel] = [:]

    public var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("iptv", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func file(_ name: String) -> URL { directory.appendingPathComponent(name) }

    public func channels() -> [IptvChannel] {
        lock.lock(); defer { lock.unlock() }
        let url = file("channels.json")
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        let stamp = "\(attrs?[.modificationDate] ?? "")-\(attrs?[.size] ?? 0)"
        if stamp != cachedStamp {
            cachedChannels = parseChannels(url)
            cachedById = Dictionary(cachedChannels.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            cachedStamp = stamp
        }
        return cachedChannels
    }

    public func channel(id: String) -> IptvChannel? {
        _ = channels()
        lock.lock(); defer { lock.unlock() }
        return cachedById[id]
    }

    private func parseChannels(_ url: URL) -> [IptvChannel] {
        guard let data = try? Data(contentsOf: url),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            guard let id = o["id"] as? String, let name = o["name"] as? String,
                  let u = o["url"] as? String else { return nil }
            return IptvChannel(id: id, name: name, url: u, logo: o["logo"] as? String ?? "",
                               group: o["group"] as? String ?? "", country: o["country"] as? String ?? "")
        }
    }

    /// (group, channel count) sorted by name.
    public func groups() -> [(String, Int)] {
        var counts: [String: Int] = [:]
        for c in channels() { counts[c.group, default: 0] += 1 }
        return counts.sorted { $0.key.lowercased() < $1.key.lowercased() }.map { ($0.key, $0.value) }
    }

    public func channels(inGroup g: String) -> [IptvChannel] { channels().filter { $0.group == g } }

    public func favorites() -> [IptvChannel] {
        idList(file("favorites.json"), key: "favorites").compactMap { channel(id: $0) }
    }

    public func recents() -> [IptvChannel] {
        idList(file("recents.json"), key: "recents").compactMap { channel(id: $0) }
    }

    private func idList(_ url: URL, key: String) -> [String] {
        guard let data = try? Data(contentsOf: url),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let ids = o[key] as? [String] else { return [] }
        return ids
    }

    public func addRecent(_ id: String) {
        lock.lock()
        var ids = idList(file("recents.json"), key: "recents")
        ids.removeAll { $0 == id }
        ids.insert(id, at: 0)
        writeRecents(Array(ids.prefix(Self.maxRecents)))
        lock.unlock()
        NotificationCenter.default.post(name: .iptvUserDataChanged, object: nil)
    }

    public func clearRecents() {
        lock.lock(); writeRecents([]); lock.unlock()
        NotificationCenter.default.post(name: .iptvUserDataChanged, object: nil)
    }

    private func writeRecents(_ ids: [String]) {
        if let data = try? JSONSerialization.data(withJSONObject: ["recents": ids]) {
            try? data.write(to: file("recents.json"), options: .atomic)
        }
    }
}

public struct IptvSelection {
    public let scope: String   // all | favorites | recents | cat:<group>
    public let label: String
    public let version: Int
    public let origin: String  // phone | car
}

extension IptvStorage {
    private var selectionURL: URL { directory.appendingPathComponent("selection.json") }

    /// What the phone (or the car) is browsing. selection.json has one writer: native.
    public func selection() -> IptvSelection {
        guard let d = try? Data(contentsOf: selectionURL),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let scope = o["scope"] as? String, let label = o["label"] as? String,
              let version = o["version"] as? Int else {
            return IptvSelection(scope: "all", label: "All channels", version: 0, origin: "phone")
        }
        return IptvSelection(scope: scope, label: label, version: version, origin: o["origin"] as? String ?? "phone")
    }

    /// Returns false (and does nothing) if the scope is already selected, so echoes are dropped.
    @discardableResult
    public func setSelection(scope: String, label: String, origin: String) -> Bool {
        let cur = selection()
        if cur.scope == scope { return false }
        let next = IptvSelection(scope: scope, label: label, version: cur.version + 1, origin: origin)
        let obj: [String: Any] = ["scope": scope, "label": label, "version": next.version, "origin": origin]
        if let d = try? JSONSerialization.data(withJSONObject: obj) { try? d.write(to: selectionURL, options: .atomic) }
        NotificationCenter.default.post(name: .iptvSelectionChanged, object: next)
        return true
    }

    func channels(forScope scope: String) -> [IptvChannel] {
        switch scope {
        case "favorites": return favorites()
        case "recents": return recents()
        case "all": return channels()
        default:
            if scope.hasPrefix("cat:") { return channels(inGroup: String(scope.dropFirst(4))) }
            return []
        }
    }
}
