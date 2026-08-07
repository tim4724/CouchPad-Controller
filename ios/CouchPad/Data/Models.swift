import SwiftUI
import Foundation

// MARK: - Global constants

enum CP {
    /// Room codes are minted by the shared relay, so the format is suite-wide, not
    /// per game: Base58 (case-sensitive, excludes 0 O I l), six characters.
    static let base58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    static let roomCodeLength = 6
    /// Illustrative code shown in the entry field's placeholder — never a real room, just
    /// the shape (Base58, six chars) a typed/scanned code takes. Kept in one place so the
    /// "e.g." copy stays localized while the sample doesn't repeat across translations.
    static let sampleRoomCode = "A3KX9p"
    /// Fallback accent ("suite indigo") for missing/invalid accent colors and the synthetic launcher.
    static let defaultAccent = Color(cpHex: 0x6C5CE7)
    /// Shared relay for all games; doubles as the room -> controller directory. No trailing slash.
    static let relayBase = "https://ws.couchpad.games"
    /// Canonical launcher domain.
    static let launcherHost = "couchpad.games"
    /// Hosted legal pages, shown in-app via WebDocScreen so they stay reachable from
    /// within the app (§5 DDG imprint requirement; GDPR privacy notice).
    static let privacyURL = "https://couchpad.games/privacy"
    static let imprintURL = "https://couchpad.games/imprint"
    /// Public marketing site. Opened in the system browser from About — a full site,
    /// not an in-app legal doc.
    static let websiteURL = "https://couchpad.games"

    /// The marketing site self-localizes, but opening it in the system browser drops the
    /// app's chosen language. German gets an explicit /de/ deep link so the localized site
    /// matches the app; other languages fall back to the root (which auto-detects).
    static var localizedWebsiteURL: String {
        let language = Locale.current.language.languageCode?.identifier
        return language == "de" ? "\(websiteURL)/de/" : websiteURL
    }

    /// Marketing version (CFBundleShortVersionString), surfaced read-only in the
    /// About footer for support/bug reports.
    static var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }

    /// The two legal pages cross-link to each other, so the in-app viewer can move
    /// from one to the other. Matches a loaded URL back to a document title (localized)
    /// so the nav bar shows the right one as the page changes; `nil` if unrecognized.
    static func legalTitle(for url: URL?) -> String? {
        let path = (url?.path ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if path.hasSuffix("imprint") { return String(localized: "Impressum") }
        if path.hasSuffix("privacy") { return String(localized: "Privacy Policy") }
        return nil
    }

    /// A scanned QR payload is arbitrary content — unlike a Universal Link, the OS
    /// hasn't vouched for its host, and `legalTitle` matches only the path. So before
    /// the doc viewer (which trusts its URL: no allow-list) may load a scanned value,
    /// require a launcher apex over https with no embedded credentials, mirroring
    /// the Universal Link routing. Returns the URL to open (locale variants like
    /// /en/privacy pass through as-is), or nil when it's not a legal page.
    static func scannedLegalUrl(_ raw: String) -> String? {
        guard let components = URLComponents(string: raw),
              components.scheme?.lowercased() == "https",
              (components.user ?? "").isEmpty,
              let host = components.host?.lowercased(), host == launcherHost,
              legalTitle(for: components.url) != nil else { return nil }
        return raw
    }
}

// MARK: - Game

struct Game: Identifiable, Hashable {
    let id: String
    let name: String
    let status: String
    let minPlayers: Int?     // player-count range endpoints, e.g. 1...8;
    let maxPlayers: Int?     // rendered via the game_players_* plurals (playersLabel)
    let video: String?
    let accentColor: Color
    let art: String?
    /// Square brand mark, same asset-path + cache rules as `art`. Distinct from the
    /// 16:9 cover: a poster crop is unreadable at icon size (NearbyCard).
    let icon: String?
    let controllerBaseUrl: String?
    let hosts: [String]
    let relayProbeBase: String?

    init(id: String, name: String, status: String = "soon",
         minPlayers: Int? = nil, maxPlayers: Int? = nil, video: String? = nil, accentColor: Color = CP.defaultAccent,
         art: String? = nil, icon: String? = nil, controllerBaseUrl: String? = nil, hosts: [String] = [],
         relayProbeBase: String? = nil) {
        self.id = id
        self.name = name
        self.status = status
        self.minPlayers = minPlayers
        self.maxPlayers = maxPlayers
        self.video = video
        self.accentColor = accentColor
        self.art = art
        self.icon = icon
        self.controllerBaseUrl = controllerBaseUrl
        self.hosts = hosts
        self.relayProbeBase = relayProbeBase
    }

    /// Exact, case-sensitive comparison against "live".
    var isLive: Bool { status == "live" }

    /// Where this game's rooms live.
    var roomRelayBase: String { relayProbeBase ?? CP.relayBase }

    /// The host component of controllerBaseUrl — what's shown to users as the game's domain.
    var displayHost: String? { controllerBaseUrl.flatMap { URL(string: $0)?.host } }

    /// The player-count line ("1–8 players"), rendered from the manifest's count range
    /// through the shared plurals so the noun agrees with the count (Russian: "1–4 игрока"
    /// vs "1–8 игроков"; the range agrees with the max). A single-count game (min == max)
    /// uses the plain-count plural. Nil when the manifest gives no counts.
    var playersLabel: String? {
        guard let lo = minPlayers, let hi = maxPlayers else { return nil }
        let key = lo == hi ? "game_players_count" : "game_players_range"
        let format = NSLocalizedString(key, comment: "")
        return lo == hi
            ? String.localizedStringWithFormat(format, lo)
            : String.localizedStringWithFormat(format, lo, hi)
    }

    /// Used only for unknown launcher subdomains.
    static let syntheticLauncher = Game(id: "couchpad", name: "CouchPad", status: "live")
}

// MARK: - Manifest loading

enum GamesManifest {

    private struct ParseError: Error {}

    /// Parses manifest bytes. All-or-nothing: ANY structural failure or an empty
    /// games list → nil, so a bad fetch can never half-apply over a good list.
    /// There is no in-band schema version — the latest served JSON is the truth,
    /// and a breaking rework (if ever) ships under a new manifest URL.
    static func parse(_ data: Data) -> [Game]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let entries = root["games"] as? [Any], !entries.isEmpty else {
            return nil
        }
        return try? entries.map { try parseGame($0) }
    }

    /// Loads "games-manifest.json" from the bundle — the seed every install can
    /// fall back to. Any failure → [].
    static func load(bundle: Bundle = .main) -> [Game] {
        guard let url = bundle.url(forResource: "games-manifest", withExtension: "json"),
              let data = try? Data(contentsOf: url) else {
            return []
        }
        return parse(data) ?? []
    }

    private static func parseGame(_ raw: Any) throws -> Game {
        guard let obj = raw as? [String: Any],
              let id = obj["id"] as? String,
              let name = obj["name"] as? String else {
            throw ParseError()
        }

        let status = (obj["status"] as? String) ?? "soon"
        // Player counts are structural data; the display copy is rendered from them
        // via the shared game_players_* plurals (Game.playersLabel), not stored per game.
        let minPlayers = optPositiveInt(obj["minPlayers"])
        let maxPlayers = optPositiveInt(obj["maxPlayers"])
        // A trailer is an https URL, fetched to cache on demand (TrailerCache).
        let video = httpsURL(obj["video"])
        let accentColor = parseHexColor((obj["accentColor"] as? String) ?? "")

        var art = blankToNil(obj["art"])
        if let a = art, a.hasPrefix("/") {
            art = String(a.dropFirst())
        }

        var icon = blankToNil(obj["icon"])
        if let i = icon, i.hasPrefix("/") {
            icon = String(i.dropFirst())
        }

        let controllerBaseUrl = httpsURL(obj["controllerBaseUrl"])

        let relayProbeBase = httpsURL(obj["relayProbeBase"])?.trimmingTrailingSlashes()

        // Strictly strings — a malformed hosts array fails the whole parse (Android
        // matches), keeping the documented all-or-nothing promise instead of silently
        // shipping a shorter allow-list than the other platform.
        let hosts = try ((obj["hosts"] as? [Any]) ?? []).map { element -> String in
            guard let host = element as? String else { throw ParseError() }
            return host
        }

        return Game(id: id, name: name, status: status,
                    minPlayers: minPlayers, maxPlayers: maxPlayers, video: video, accentColor: accentColor,
                    art: art, icon: icon, controllerBaseUrl: controllerBaseUrl, hosts: hosts,
                    relayProbeBase: relayProbeBase)
    }

    /// A positive Int from a JSON value, or nil if absent/non-numeric/≤0.
    private static func optPositiveInt(_ value: Any?) -> Int? {
        guard let n = value as? Int, n > 0 else { return nil }
        return n
    }

    /// Absent, non-string, empty, or whitespace-only → nil.
    private static func blankToNil(_ value: Any?) -> String? {
        guard let s = value as? String,
              !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return s
    }

    /// The manifest may arrive over the network, and its URLs get loaded /
    /// probed — https only, one policy for every URL field.
    private static func httpsURL(_ value: Any?) -> String? {
        blankToNil(value).flatMap { $0.hasPrefix("https://") ? $0 : nil }
    }
}

/// Absolute https URL for a manifest art path: site-relative paths (the leading "/"
/// is already stripped at parse) resolve against the launcher host; absolute URLs
/// must be https or they're refused.
func remoteArtURL(_ art: String) -> URL? {
    if art.hasPrefix("https://") { return URL(string: art) }
    if art.contains("://") { return nil }
    return URL(string: "https://\(CP.launcherHost)/\(art)")
}

// MARK: - Parsing / matching helpers

/// Strip one leading "#", parse first 6 chars as RRGGBB; length < 6 or invalid pair → CP.defaultAccent. Never throws.
func parseHexColor(_ s: String) -> Color {
    var hex = Substring(s)
    if hex.hasPrefix("#") { hex = hex.dropFirst() }
    guard hex.count >= 6 else { return CP.defaultAccent }
    let chars = Array(hex.prefix(6))
    var value: UInt32 = 0
    for i in 0..<3 {
        let pair = String(chars[i * 2 ... i * 2 + 1])
        guard let byte = UInt8(pair, radix: 16) else { return CP.defaultAccent }
        value = (value << 8) | UInt32(byte)
    }
    return Color(cpHex: value)
}

private let hostnameAlphabet = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-")

/// Case-insensitive: host == domain || host ends with "." + domain. nil host → false.
/// The host arrives percent-DECODED from URLComponents, so anything outside the
/// hostname alphabet (an embedded "/", say, from "evil.com%2f.example.com") must
/// never suffix-match — the raw string is what gets loaded.
func hostInDomain(_ host: String?, _ domain: String) -> Bool {
    guard let host else { return false }
    let h = host.lowercased()
    guard h.unicodeScalars.allSatisfy(hostnameAlphabet.contains) else { return false }
    let d = domain.lowercased()
    return h == d || h.hasSuffix("." + d)
}

/// A private/LAN host: an RFC-1918 IPv4 literal (10/8, 172.16/12, 192.168/16), loopback
/// (127/8), link-local (169.254/16), "localhost", or an mDNS ".local" name. Debug builds
/// relax the https-only join/navigation gates for these so a controller served off a dev
/// machine on the local network (http://192.168.x.y:PORT/…) can be scanned and loaded;
/// release builds ignore this entirely — public hosts are never matched here.
func isPrivateHost(_ host: String?) -> Bool {
    guard let host else { return false }
    let h = host.lowercased()
    if h == "localhost" || h.hasSuffix(".local") { return true }
    let octets = h.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
    guard octets.count == 4, octets.allSatisfy({ ($0 ?? -1) >= 0 && ($0 ?? -1) <= 255 }) else {
        return false
    }
    let a = octets[0]!, b = octets[1]!
    return a == 10
        || (a == 172 && (16...31).contains(b))
        || (a == 192 && b == 168)
        || a == 127
        || (a == 169 && b == 254)
}

/// Android Uri.encode semantics: percent-encode (UTF-8) everything EXCEPT A–Z a–z 0–9 _ - ! . ~ ' ( ) *
func androidUriEncode(_ s: String) -> String {
    var out = ""
    for byte in Array(s.utf8) {
        switch byte {
        case UInt8(ascii: "A")...UInt8(ascii: "Z"),
             UInt8(ascii: "a")...UInt8(ascii: "z"),
             UInt8(ascii: "0")...UInt8(ascii: "9"),
             UInt8(ascii: "_"), UInt8(ascii: "-"), UInt8(ascii: "!"),
             UInt8(ascii: "."), UInt8(ascii: "~"), UInt8(ascii: "'"),
             UInt8(ascii: "("), UInt8(ascii: ")"), UInt8(ascii: "*"):
            out.append(Character(UnicodeScalar(byte)))
        default:
            out += String(format: "%%%02X", byte)
        }
    }
    return out
}

extension String {
    /// Drop any trailing "/" so a base URL concatenates cleanly with "/" + code.
    func trimmingTrailingSlashes() -> String {
        var stripped = self
        while stripped.hasSuffix("/") { stripped.removeLast() }
        return stripped
    }
}

// MARK: - Profile

struct Profile: Equatable {
    var name: String = ""

    /// Non-blank after trimming whitespace.
    var isSet: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

// MARK: - FunnyName

/// Wholesome-funny fallback identity. Every device gets a random "Adjective Noun"
/// handle on first launch (see `ProfileStore.load`) so players jump straight into a
/// game instead of hitting a name wall — they can still rename or reroll (the 🎲 in
/// ProfileSheet). English-only by design (v1): gamer tags read as English across all
/// our locales, and it sidesteps a per-culture review of 500+ combos. Every word is
/// ≤7 chars so "Adjective Noun" always fits Contract v1's 16-char cpName. Keep this
/// list in sync with the Android FunnyName in Profile.kt.
enum FunnyName {
    private static let adjectives = [
        "Sneaky", "Turbo", "Wobbly", "Grumpy", "Sleepy", "Sparkly",
        "Chunky", "Zesty", "Feral", "Mighty", "Salty", "Cosmic",
        "Fluffy", "Rowdy", "Spicy", "Jolly", "Bouncy", "Cheeky",
        "Groovy", "Snazzy", "Wacky", "Zippy", "Plucky", "Silly",
    ]
    private static let nouns = [
        "Otter", "Pigeon", "Waffle", "Noodle", "Muffin", "Goblin",
        "Wizard", "Llama", "Gecko", "Taco", "Yeti", "Panda",
        "Nugget", "Pickle", "Walrus", "Cactus", "Toaster", "Raccoon",
        "Narwhal", "Penguin", "Hamster", "Biscuit", "Wombat", "Falcon",
    ]

    /// e.g. "Grumpy Waffle". Always non-blank and ≤16 chars.
    static func random() -> String {
        "\(adjectives.randomElement()!) \(nouns.randomElement()!)"
    }
}

// RecentRoom (and its single-slot in-memory store) live in Stores.swift.
