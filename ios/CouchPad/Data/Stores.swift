import Foundation
import UIKit

// MARK: - ProfileStore

enum ProfileStore {

    private static let nameKey = "cp_profile.name"

    /// Last loaded/saved profile. load() is a @State default expression on the main
    /// screens, so it re-runs on every struct init (each parent body re-eval). Only
    /// the .standard domain is cached; an injected defaults (tests) always reads
    /// through. Main-thread only.
    private static var cached: Profile?

    /// Get-or-create: returns the stored name, or on first launch mints a `FunnyName`
    /// and persists it — so every screen's load() reads the same identity instead of
    /// each minting its own.
    static func load(defaults: UserDefaults = .standard) -> Profile {
        if defaults === UserDefaults.standard, let cached {
            return cached
        }
        let profile: Profile
        let stored = defaults.string(forKey: nameKey) ?? ""
        if !stored.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            profile = Profile(name: stored)
        } else {
            profile = Profile(name: FunnyName.random())
            save(profile, defaults: defaults)
        }
        if defaults === UserDefaults.standard {
            cached = profile
        }
        return profile
    }

    /// Writes the name verbatim — no trimming on save.
    static func save(_ profile: Profile, defaults: UserDefaults = .standard) {
        if defaults === UserDefaults.standard {
            cached = profile
        }
        defaults.set(profile.name, forKey: nameKey)
    }
}

// MARK: - ManifestStore

/// The games list the launcher renders: the last manifest fetched from
/// couchpad.games (persisted verbatim), seeded from the bundled copy until a
/// fetch has ever succeeded. `refresh()` pulls at most once per process launch —
/// a launch-fresh list is fresh enough, and every failure silently keeps the
/// current list. Served art paths may name files this build didn't ship; ArtCache
/// pulls those through ArtworkCache.
@MainActor final class ManifestStore: ObservableObject {

    static let shared = ManifestStore()

    @Published private(set) var games: [Game]

    private static let jsonKey = "cp_manifest.json"
    private static let manifestURL = URL(string: "https://\(CP.launcherHost)/games-manifest.json")!
    /// Sanity cap for a served manifest (ours is ~1 KB) — a deploy mistake must not balloon memory.
    private static let maxBytes = 1 << 20

    private var refreshed = false

    private init() {
        if let data = UserDefaults.standard.data(forKey: Self.jsonKey),
           let cached = GamesManifest.parse(data) {
            games = cached
        } else {
            games = GamesManifest.load()
        }
    }

    /// Fetches the served manifest and, when it validates, persists + publishes it live.
    func refresh() async {
        guard !refreshed else { return }
        refreshed = true
        guard let (data, response) = try? await URLSession.shared.data(from: Self.manifestURL),
              (response as? HTTPURLResponse)?.statusCode == 200,
              data.count <= Self.maxBytes,
              // Identical to the seeded copy (the common case every launch) — the
              // current list already reflects it, so skip the re-parse, the
              // rewrite, and the objectWillChange.
              data != UserDefaults.standard.data(forKey: Self.jsonKey),
              let fresh = GamesManifest.parse(data) else {
            return
        }
        UserDefaults.standard.set(data, forKey: Self.jsonKey)
        games = fresh
    }
}

// MARK: - RecentRoom

/// A captured favicon plus whether its opaque content reads as light.
struct Favicon {
    let image: UIImage
    let contentIsLight: Bool

    init(_ image: UIImage) {
        self.image = image
        self.contentIsLight = image.contentIsLight()
    }
}

/// The room this phone is in or just left, with everything the home rejoin card needs.
/// `joinUrl` omits cpv/cpName — re-wrapped with the current name at rejoin. `favicon`
/// and `title` are captured from the controller page mid-session, so they're nil until
/// then.
struct RecentRoom {
    let game: Game
    let joinUrl: String
    let roomCode: String
    let favicon: Favicon?
    let title: String?
}

/// Single-slot, in-memory memory of the current room. Deliberately not persisted:
/// rejoin is a same-session convenience, so the slot dies with the process and ages
/// out after `ttl` — a fresh launch simply shows no card. `remember` sets the base at
/// join; the favicon and title arrive later, captured in-game.
enum RecentRoomStore {

    private static let ttl: TimeInterval = 20 * 60
    private static let maxTitleLength = 64

    private static let lock = NSLock()
    private static var game: Game?
    private static var joinUrl = ""
    private static var roomCode = ""
    private static var favicon: Favicon?
    private static var title: String?
    private static var savedAt = Date.distantPast

    static func remember(game: Game, joinUrl: String, roomCode: String) {
        lock.lock(); defer { lock.unlock() }
        self.game = game
        self.joinUrl = joinUrl
        self.roomCode = roomCode
        favicon = nil
        title = nil
        savedAt = Date()
    }

    static func putFavicon(_ image: UIImage) {
        let captured = Favicon(image)
        lock.lock(); defer { lock.unlock() }
        if game != nil { favicon = captured }
    }

    /// Sanitizes `raw` (trim, collapse whitespace, cap length), stores it as the
    /// active room's title, and returns the cleaned value so callers can display the
    /// same text. Nil when there's no active room or nothing survives cleaning.
    @discardableResult
    static func putTitle(_ raw: String) -> String? {
        let clean = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .prefix(maxTitleLength)
        lock.lock(); defer { lock.unlock() }
        guard game != nil, !clean.isEmpty else { return nil }
        let cleaned = String(clean)
        title = cleaned
        return cleaned
    }

    /// The current room while still fresh, else nil (clearing an aged-out slot).
    static func current() -> RecentRoom? {
        lock.lock(); defer { lock.unlock() }
        guard let game else { return nil }
        if Date().timeIntervalSince(savedAt) > ttl {
            clearLocked()
            return nil
        }
        return RecentRoom(game: game, joinUrl: joinUrl, roomCode: roomCode, favicon: favicon, title: title)
    }

    static func clear() {
        lock.lock(); defer { lock.unlock() }
        clearLocked()
    }

    // Caller holds the lock.
    private static func clearLocked() {
        game = nil
        joinUrl = ""
        roomCode = ""
        favicon = nil
        title = nil
        savedAt = .distantPast
    }
}

private extension UIImage {
    /// Alpha-weighted mean perceptual luminance of the opaque pixels > 0.6. Renders
    /// into a small premultiplied RGBA buffer, so a transparent surround can't drag
    /// the mean toward black and the cost stays fixed regardless of source size.
    func contentIsLight() -> Bool {
        guard let cg = cgImage else { return false }
        let side = 16
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        guard let ctx = CGContext(
            data: &pixels, width: side, height: side, bitsPerComponent: 8,
            bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return false }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
        var lumSum = 0.0, alphaSum = 0.0
        var i = 0
        while i < pixels.count {
            let a = Double(pixels[i + 3]) / 255.0
            if a > 0 {
                // Premultiplied: r/g/b are already * alpha, so summing them is the
                // alpha-weighted luminance; divide by total alpha for the mean.
                let r = Double(pixels[i]) / 255.0
                let g = Double(pixels[i + 1]) / 255.0
                let b = Double(pixels[i + 2]) / 255.0
                lumSum += 0.299 * r + 0.587 * g + 0.114 * b
                alphaSum += a
            }
            i += 4
        }
        return alphaSum > 0 && lumSum / alphaSum > 0.6
    }
}

// MARK: - Profile URL wrapping (contract §7.1)

/// If the profile is set, append cpv=1 then cpName=<androidUriEncode(name)> to the query,
/// preserving any existing query (append with & or ?) and keeping the #fragment at the end.
/// Otherwise the joinUrl is returned unchanged. Pure string manipulation — no URL round-tripping.
func withProfile(_ joinUrl: String, _ profile: Profile) -> String {
    guard profile.isSet else { return joinUrl }

    let base: String
    let fragment: String
    if let hashIndex = joinUrl.firstIndex(of: "#") {
        base = String(joinUrl[..<hashIndex])
        fragment = String(joinUrl[hashIndex...])
    } else {
        base = joinUrl
        fragment = ""
    }

    let separator = base.contains("?") ? "&" : "?"
    return base + separator + "cpv=1&cpName=" + androidUriEncode(profile.name) + fragment
}
