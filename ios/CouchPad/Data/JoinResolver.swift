import Foundation

// MARK: - Outcome

enum JoinOutcome: Equatable {
    case success(game: Game, roomCode: String, joinUrl: String)
    case failure(message: String)
}

/// The room code an input names when it carries no controller origin of its own: a bare
/// typed code, or a canonical couchpad.games/<code> link (bare domain or www — a subdomain
/// is a preview deployment and IS its own origin). Nil when the input has an origin, in
/// which case that URL is the controller and loads verbatim. "" for a launcher link with
/// no path segment.
///
/// Such an input names a room but not a game — exactly what the relay directory answers —
/// so this is the one test `resolveJoin` needs, and the same test that tells a §6 `url`
/// that declares a controller apart from one that declares nothing.
func originlessCode(_ raw: String?) -> String? {
    let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return nil }
    guard let components = URLComponents(string: trimmed),
          components.scheme != nil,
          let host = components.host, !host.isEmpty else {
        return trimmed
    }
    let lowerHost = host.lowercased()
    guard lowerHost == CP.launcherHost || lowerHost == "www." + CP.launcherHost else { return nil }
    return components.path.split(separator: "/").map(String.init).first ?? ""
}

// MARK: - Resolver

enum JoinResolver {

    /// Turns a scanned QR value or typed string into a join target. Never throws.
    static func resolve(_ raw: String?, games: [Game]) -> JoinOutcome {
        let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .failure(message: String(localized: "Empty code."))
        }

        // Parse failure, or no scheme / no host → bare code path (sole live game).
        guard let components = URLComponents(string: trimmed),
              components.scheme != nil,
              let host = components.host, !host.isEmpty else {
            // Bare code — no origin to load, and nothing riding along.
            return soleLiveGameJoin(games: games, roomCode: trimmed, source: nil)
        }

        let lowerHost = host.lowercased()

        // Canonical launcher links (bare domain or www) carry no controller origin of
        // their own: code-first, sole live game hosts them — the offline answer, and the
        // fallback when `resolveJoin`'s directory probe can't name the owner.
        if let code = originlessCode(trimmed) {
            return soleLiveGameJoin(games: games, roomCode: code, source: components)
        }

        // A game's own domain (or a launcher preview subdomain): the scanned URL
        // IS the controller — load it verbatim, don't presume its layout.
        let game: Game
        if let matched = games.first(where: { g in g.hosts.contains(where: { hostInDomain(host, $0) }) }) {
            game = matched
        } else if hostInDomain(host, CP.launcherHost) {
            game = games.first(where: { lowerHost.hasPrefix($0.id) }) ?? Game.syntheticLauncher
        } else {
            #if DEBUG
            // Debug only: a LAN dev server isn't any known game's host — load it as its
            // own trusted controller so a locally served page can be tested end-to-end.
            if isPrivateHost(host) {
                return joinVerbatim(url: trimmed, components: components, game: .syntheticLauncher)
            }
            #endif
            return .failure(message: String(localized: "That code isn’t a CouchPad room."))
        }
        return joinVerbatim(url: trimmed, components: components, game: game)
    }

    // MARK: Internals

    /// A trusted host's scanned URL IS its controller — load it exactly as scanned rather
    /// than presuming its path/query layout. We vouch only for the host (already matched)
    /// and an https scheme with no embedded credentials; the room code is best-effort. The
    /// scanned URL carries its own claim/instance, so there's nothing to re-attach here.
    private static func joinVerbatim(url: String, components: URLComponents, game: Game) -> JoinOutcome {
        let scheme = components.scheme?.lowercased()
        var schemeOk = scheme == "https"
        #if DEBUG
        // Plain http only for a LAN host in debug (see isPrivateHost).
        schemeOk = schemeOk || (scheme == "http" && isPrivateHost(components.host))
        #endif
        guard schemeOk, (components.user ?? "").isEmpty else {
            return .failure(message: String(localized: "That code isn’t a CouchPad room."))
        }
        return .success(game: game, roomCode: extractRoomCode(components), joinUrl: url)
    }

    /// Best-effort room code: the first room-code-shaped token (Base58, exact length) in a
    /// path segment, else a query value. "" when the URL surfaces none — the join still
    /// loads; the rejoin card just can't show or liveness-poll the room.
    private static func extractRoomCode(_ components: URLComponents) -> String {
        let segments = components.path.split(separator: "/").map(String.init)
        if let code = segments.first(where: isValidCode) { return code }
        for item in components.queryItems ?? [] {
            if let value = item.value, isValidCode(value) { return value }
        }
        return ""
    }

    private static func isValidCode(_ code: String) -> Bool {
        code.count == CP.roomCodeLength && code.allSatisfy { CP.base58.contains($0) }
    }

    /// Hosts an origin-less input on the sole live game. `source` is the URL it came from,
    /// or nil for a bare code, which has no URL to keep.
    ///
    /// Only the ORIGIN is wrong on a canonical link — couchpad.games serves no controller —
    /// so swap that and pass the query and fragment through untouched. Re-attaching params
    /// by name is what silently dropped `cpp` (§6: the join URL is the only place a display
    /// ever declares its platform), and it would drop the next one too.
    private static func soleLiveGameJoin(games: [Game], roomCode: String,
                                         source: URLComponents?) -> JoinOutcome {
        guard let game = games.first(where: { $0.isLive }) else {
            return .failure(message: String(localized: "No live game configured."))
        }
        guard let base = game.controllerBaseUrl else {
            return .failure(message: String(localized: "That game has no controller URL."))
        }
        // Validate: exact length and every char in the suite charset (case-sensitive).
        guard isValidCode(roomCode) else {
            return .failure(message: String(localized: "That code isn’t a CouchPad room."))
        }

        var joinUrl = base.trimmingTrailingSlashes() + "/" + roomCode
        if let query = source?.percentEncodedQuery, !query.isEmpty { joinUrl += "?" + query }
        if let fragment = source?.percentEncodedFragment, !fragment.isEmpty {
            joinUrl += "#" + fragment
        }
        return .success(game: game, roomCode: roomCode, joinUrl: joinUrl)
    }
}
