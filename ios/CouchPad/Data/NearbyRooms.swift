import Foundation
import Network

// MARK: - Wire model

/// Contract §8: the DNS-SD service a native display app advertises its room under.
/// No trailing dot — that's Network.framework's convention (Android's NsdManager
/// wants one; same wire protocol either way).
let nearbyServiceType = "_couchpad._tcp"

/// kDNSServiceErr_PolicyDenied: Local Network was refused. iOS has no query API, so
/// this DNS error surfacing on a browse is the only "you said no" signal there is.
private let dnsPolicyDeniedCode: Int32 = -65570

private func isPolicyDenied(_ error: NWError) -> Bool {
    if case .dns(let code) = error { return code == dnsPolicyDeniedCode }
    return false
}

/// TXT `c` — the room code, the advertisement's payload (§8).
private let codeKey = "c"

/// TXT `cpr` — published by a controller relaying a room it is in, not by the display.
/// Its instance name is therefore NOT a room label (the phone may never have learned one),
/// so the launcher must not present it as a location.
private let relayedKey = "cpr"
/// Instance names are display-declared; cap so one can't blow out the card.
private let maxAdvertLabelLength = 24

/// `cpp` — the display's platform. A fixed vocabulary, so the launcher owns the wording.
let platformTvOS = "tvos"
let platformAndroidTV = "androidtv"

/// A browser-based display: no mDNS advertisement possible, only a §6 template.
let platformWeb = "web"

/// Which box a room is on, as `url` declares it (`cpp`, §6). A URL is the only carrier,
/// and always comes from the relay — a §8 advertisement carries just a code — so this
/// reads the same way whatever route the room arrived by. Nil unless the URL declared a
/// value we know: the wire sends a code and nothing else, so a display can never put its
/// own text on a card. Reading it off a URL is a point-in-time answer; holding on to it
/// once a room has answered is `RecentRoomStore`'s job.
func devicePlatform(fromUrl url: String) -> String? {
    let raw = URLComponents(string: url)?.queryItems?.first { $0.name == "cpp" }?.value
    let p = raw?.trimmingCharacters(in: .whitespaces).lowercased()
    return (p == platformTvOS || p == platformAndroidTV || p == platformWeb) ? p : nil
}

/// The launcher owns the platform wording — the wire only ever sends the machine-readable
/// code, so this stays localized and consistent without a display update.
func deviceName(_ platform: String?) -> String? {
    switch platform {
    case platformTvOS: return String(localized: "Apple TV")
    case platformAndroidTV: return String(localized: "Android TV")
    case platformWeb: return String(localized: "Browser")
    default: return nil
    }
}

func sanitizeAdvertLabel(_ raw: String) -> String {
    let clean = raw
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    return String(clean.prefix(maxAdvertLabelLength))
}

/// One `_couchpad._tcp` advertisement (§8): a room label and the room's code, nothing
/// else. The code is the whole payload — everything the card needs (join URL, `cpp`,
/// liveness, occupancy) comes from resolving it against the relay, which is authoritative
/// where the LAN is not. A record can therefore name a room but never propose an origin.
struct NearbyAdvert: Equatable, Hashable {
    /// The DNS-SD instance name: the display's own label ("Living Room").
    let label: String
    /// TXT `c` — the room code.
    let code: String
    /// TXT `cpr`: relayed by a controller in the room, so `label` is meaningless.
    let relayed: Bool
}

/// One advertisement per room code, preferring a display's own record over a relayed one —
/// the display's instance name is the room's label, a relaying phone has none to give.
///
/// Collapsing BEFORE resolution is what keeps relaying cheap: the payload is a room code,
/// so two records carrying the same code are the same room by definition and one probe
/// answers for both. Without this, every phone in a room would cost its own relay probe on
/// every poll tick.
func distinctAdverts(_ adverts: [NearbyAdvert]) -> [NearbyAdvert] {
    var seen: Set<String> = []
    return adverts.sorted { !$0.relayed && $1.relayed }
        .filter { seen.insert($0.code).inserted }
}

/// An advertisement whose code resolved, through the relay, to a real join target.
struct NearbyRoom: Identifiable, Equatable {
    let label: String
    let game: Game
    let roomCode: String
    let joinUrl: String

    var id: String { joinUrl }

    var target: JoinOutcome { .success(game: game, roomCode: roomCode, joinUrl: joinUrl) }

    /// `cpp` off `joinUrl`: which box the room is on, or nil when it declared nothing.
    var platform: String? { devicePlatform(fromUrl: joinUrl) }
}

/// The home screen's room cards.
struct HomeRooms {
    /// The rejoin room as the LAN currently advertises it, or nil when nothing on the
    /// network is offering that room. It is the better source for BOTH halves of the
    /// card's locator: its own name, and a join URL that came straight from the relay's
    /// §6 template — the one place `cpp` is guaranteed to be declared.
    let rejoinAdvert: NearbyRoom?
    /// Every other advertised room, deduped and in a stable order.
    let nearby: [NearbyRoom]
}

/// Turns resolved rooms into the card list in ONE pass: collapses a room advertised twice
/// (a display on two interfaces) into one card, sorts for a stable order, and folds the
/// rejoin room's advertisement into the rejoin card.
///
/// `rejoin` is the room that card is currently offering. Leaving a game does not close the
/// room, so the display keeps advertising it — without this the player lands on home
/// looking at two cards for the same room. The rejoin card wins, because it carries the
/// captured page title, and it inherits the advertisement it displaced: dropping the
/// duplicate and handing back what it knew are the same match, asked once.
func homeRooms(_ rooms: [NearbyRoom], rejoin: RecentRoom? = nil) -> HomeRooms {
    // Keyed on ROOM CODE, scoped by GAME: codes are minted per relay (a game may run its
    // own, Game.relayProbeBase), so two games can independently mint the same code and
    // must not collapse into one card. An UNRESOLVED game (a launcher preview subdomain
    // matching no id) collapses every such deployment onto one synthetic id, which
    // discriminates nothing — fall back to the host there, which does.
    var rejoinAdvert: NearbyRoom?
    var best: [String: NearbyRoom] = [:]
    for room in rooms {
        if room.isSameRoom(as: rejoin) {
            if rejoinAdvert == nil { rejoinAdvert = room }
            continue
        }
        let scope = room.game.id == Game.syntheticLauncher.id
            ? (URLComponents(string: room.joinUrl)?.host ?? "")
            : room.game.id
        let key = room.roomCode.isEmpty ? room.joinUrl : "\(scope)/\(room.roomCode)"
        if best[key] == nil { best[key] = room }
    }
    return HomeRooms(rejoinAdvert: rejoinAdvert,
                     nearby: best.values.sorted { ($0.label, $0.joinUrl) < ($1.label, $1.joinUrl) })
}

/// Resolves one advertised code against the relay — the same probe a typed code takes,
/// so scan, type and nearby-tap all land on one path. The relay's `url` is what loads
/// (re-validated against the manifest allow-list by `resolveTypedCode`) and is also where
/// `cpp` comes from, so the launcher never trusts anything the LAN said beyond the code.
///
/// Nil when the room is gone, unreachable, off the allow-list, or FULL — a card that
/// can't be joined is worse than no card, since tapping it costs a whole page load only
/// to bounce back on `game_full`.
func resolveNearby(_ advert: NearbyAdvert, games: [Game]) async -> NearbyRoom? {
    guard advert.code.count == CP.roomCodeLength,
          advert.code.allSatisfy({ CP.base58.contains($0) }) else { return nil }
    // One probe round serves both the fullness check and the URL resolution — this runs
    // on every 10s poll tick per advertised room, so a second identical round would
    // double the relay traffic for nothing.
    let lookups = await probeRelays(advert.code, games: games)
    let founds = lookups.filter { if case .found = $0 { return true } else { return false } }
    guard !founds.isEmpty, !founds.contains(where: { $0.isFull }) else { return nil }

    guard case .success(let game, let roomCode, let joinUrl) =
            resolveLookups(advert.code, results: lookups, games: games) else { return nil }
    // A relayed record's instance name is the relaying phone's own, not the TV's.
    return NearbyRoom(label: advert.relayed ? "" : advert.label, game: game, roomCode: roomCode,
                      joinUrl: joinUrl)
}

extension NearbyRoom {
    // Room code is the reliable identity (the advertised URL and the one we joined with
    // can differ in query/fragment); the URL match is the fallback for a code-less URL.
    func isSameRoom(as recent: RecentRoom?) -> Bool {
        guard let recent else { return false }
        if !roomCode.isEmpty, roomCode == recent.roomCode, game.id == recent.game.id { return true }
        return joinUrl == recent.joinUrl
    }
}

/// Whether the player has ever asked for TV discovery. iOS has no queryable Local
/// Network authorization, so the opt-in is ours to remember: until it's set, home shows
/// the "Show rooms nearby" button and nothing touches the network; the first tap starts the
/// browse (and with it the system prompt), and every later launch discovers on its own.
enum NearbyOptIn {
    private static let key = "cp_nearby.opted_in"

    static var isSet: Bool { UserDefaults.standard.bool(forKey: key) }

    static func set() { UserDefaults.standard.set(true, forKey: key) }
}

/// Remembers that the first-join Local Network gate (GameHostScreen) reached a verdict —
/// granted or denied — so no later join ever holds the page load again. Distinct from
/// `NearbyOptIn`: a deny must be remembered too, and iOS never re-prompts — only the
/// Settings toggle changes the answer after that.
enum LocalNetworkPrompt {
    private static let key = "cp_lan.prompted"

    static var done: Bool { UserDefaults.standard.bool(forKey: key) }

    static func markDone() { UserDefaults.standard.set(true, forKey: key) }
}

/// Fires the system Local Network prompt — iOS has no ask API, so starting a Bonjour
/// browse IS the ask — and waits for a verdict: `.ready` is a grant, PolicyDenied a
/// denial (the same signals NearbyBrowser reads). Returns whether access was granted.
///
/// Best effort by construction: if the OS surfaces PolicyDenied while the dialog is
/// still pending rather than after a "Don't Allow", the gate opens early and a
/// mid-load grant is recovered by the game's own fastlane retry — the relay path
/// works throughout either way. The timeout covers verdict-less states (no network
/// interface at all); it deliberately does NOT mark the prompt done, so a join on a
/// healthy network gets to ask again.
@MainActor
func requestLocalNetworkAccess() async -> Bool {
    let browser = NWBrowser(
        for: .bonjourWithTXTRecord(type: nearbyServiceType, domain: nil),
        using: NWParameters.tcp
    )
    // nil = no verdict (failed/timeout): don't remember, don't claim a grant.
    let granted = await withCheckedContinuation { (cont: CheckedContinuation<Bool?, Never>) in
        let settle = OneShot(cont)
        browser.stateUpdateHandler = { state in
            switch state {
            case .ready:
                Task { @MainActor in settle.resolve(true) }
            case .waiting(let error):
                // Any non-denied .waiting is transient.
                if isPolicyDenied(error) { Task { @MainActor in settle.resolve(false) } }
            case .failed:
                Task { @MainActor in settle.resolve(nil) }
            default:
                break
            }
        }
        browser.start(queue: .main)
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(15))
            settle.resolve(nil)
        }
    }
    browser.cancel()
    if granted != nil { LocalNetworkPrompt.markDone() }
    return granted ?? false
}

/// First verdict wins; late state changes and the timeout no-op. Every path hops to
/// the main actor before resolving — the same idiom as NearbyBrowser's handlers — so
/// main-actor isolation is the whole synchronization story.
@MainActor
private final class OneShot {
    private var cont: CheckedContinuation<Bool?, Never>?

    init(_ cont: CheckedContinuation<Bool?, Never>) { self.cont = cont }

    func resolve(_ value: Bool?) {
        cont?.resume(returning: value)
        cont = nil
    }
}

// MARK: - Advertiser

/// Re-advertises the room this phone is in, so the next player can tap instead of scan.
/// It is what makes discovery work at all for a browser-based display, which cannot
/// advertise for itself, and it makes discovery survive a native display whose own record
/// is missing or malformed.
///
/// Publishes the room CODE and nothing else — the same payload a display publishes, so a
/// relayed record proposes no origin and grants a relaying phone no more trust than the
/// display has. `cpr=1` marks the instance name as not-a-room-label.
///
/// NEVER prompts. It advertises only if the player already opted into discovery — the
/// same Local Network authorization, already granted for their own benefit — so nobody
/// is ever asked for a capability that helps someone else.
@MainActor final class NearbyAdvertiser {

    static let shared = NearbyAdvertiser()

    private var listener: NWListener?

    func start(roomCode: String) {
        guard listener == nil, !roomCode.isEmpty, NearbyOptIn.isSet else { return }
        var txt = NWTXTRecord()
        txt[codeKey] = roomCode
        txt[relayedKey] = "1"
        guard let listener = try? NWListener(using: NWParameters.tcp) else { return }
        listener.service = NWListener.Service(
            // Our own name, never the device's — no phone name reaches the network.
            name: "CouchPad-" + roomCode,
            type: nearbyServiceType,
            domain: nil,
            txtRecord: txt
        )
        // Nothing ever dials us; the port exists only so the record can carry TXT.
        // A listener with no handler is invalid, so accept and drop immediately.
        listener.newConnectionHandler = { $0.cancel() }
        self.listener = listener
        listener.start(queue: .main)
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }
}

// MARK: - LAN monitor

/// Whether a network mDNS could possibly traverse — Wi-Fi or wired Ethernet — is up. On
/// cellular only, browsing "runs" and finds nothing, so the home slot's
/// "Searching…"/"No rooms found" would claim a search that never had anywhere to look;
/// this is what swaps that claim for the join-the-Wi-Fi hint. It only ever picks the
/// slot's wording — browsing itself keeps running: a phone hosting its own hotspot has no
/// Wi-Fi path here, yet rooms on that hotspot still resolve, and a found room simply
/// replaces the hint.
@MainActor final class LanMonitor: ObservableObject {

    /// Optimistic until the first path update — a launch must not flash the hint.
    @Published private(set) var hasLan = true

    private var monitor: NWPathMonitor?

    func start() {
        guard monitor == nil else { return }
        // "Any path that isn't cellular" — Wi-Fi or Ethernet satisfies, cellular-only
        // (or nothing at all) doesn't.
        let monitor = NWPathMonitor(prohibitedInterfaceTypes: [.cellular])
        monitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in self?.hasLan = up }
        }
        self.monitor = monitor
        monitor.start(queue: .main)
    }

    func stop() {
        monitor?.cancel()
        monitor = nil
        hasLan = true
    }
}

// MARK: - Browser

/// Browses the LAN for displays advertising a room (contract §8). The launcher only
/// ever reads the TXT record — it never dials the advertised port. Gameplay still goes
/// to the relay over the internet, so this is purely a local side channel for a join URL.
@MainActor final class NearbyBrowser: ObservableObject {

    @Published private(set) var adverts: [NearbyAdvert] = []
    /// Local Network was denied. iOS has no API to ask, but NWBrowser parks in
    /// `.waiting(PolicyDenied)` — the only signal that separates "you said no" from
    /// "nothing is on this network", and without it the user is stranded with no
    /// explanation and no way back except the Settings app.
    @Published private(set) var permissionDenied = false

    private var browser: NWBrowser?

    /// Idempotent. This is what triggers the Local Network permission prompt: on a
    /// denial the browser parks in `.waiting(PolicyDenied)` and we simply never see a
    /// room, which is the same as an empty network — the join card's scan/code path
    /// is the fallback either way, so there's nothing to report.
    func start() {
        guard browser == nil else { return }
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: nearbyServiceType, domain: nil),
            using: NWParameters.tcp
        )
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let parsed = Self.parse(results)
            Task { @MainActor in self?.adverts = parsed }
        }
        browser.stateUpdateHandler = { [weak self] state in
            switch state {
            case .waiting(let error):
                // Any non-denied .waiting is transient (no network yet) and resolves
                // itself, so leave the browser running.
                if isPolicyDenied(error) {
                    Task { @MainActor in self?.permissionDenied = true }
                }
            case .ready:
                Task { @MainActor in self?.permissionDenied = false }
            case .failed:
                // Terminal — the browser won't recover on its own.
                Task { @MainActor in self?.stop() }
            default:
                break
            }
        }
        self.browser = browser
        browser.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        adverts = []
        permissionDenied = false
    }

    // Runs on the browser's callback, off the main actor.
    private nonisolated static func parse(_ results: Set<NWBrowser.Result>) -> [NearbyAdvert] {
        var adverts: [NearbyAdvert] = []
        for result in results {
            guard case .service(let name, _, _, _) = result.endpoint,
                  case .bonjour(let txt) = result.metadata else { continue }
            // The code is the whole gate: the record has no version field, and a code no
            // relay knows is dropped at resolution.
            let code = txt[codeKey]?.trimmingCharacters(in: .whitespaces)
            // A sanity cap on hostile input, not the format check — resolveNearby applies
            // the real base58/length rule. Any advert field gets the same bound.
            guard let code, !code.isEmpty, code.count <= maxAdvertLabelLength else { continue }
            adverts.append(NearbyAdvert(label: sanitizeAdvertLabel(name), code: code,
                                        relayed: txt[relayedKey] == "1"))
        }
        return adverts
    }

}
