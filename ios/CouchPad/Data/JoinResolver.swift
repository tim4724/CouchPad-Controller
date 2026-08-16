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

        // No controller origin: a parse failure, a bare code, or a canonical
        // couchpad.games/<code> link — that link is the launcher asking the directory
        // who owns the code, not an answer. (Launcher SUBdomains are preview
        // deployments and load their own origin — below.)
        guard let components = URLComponents(string: trimmed),
              components.scheme != nil,
              let host = components.host, !host.isEmpty,
              originlessCode(trimmed) == nil else {
            return .failure(message: String(localized: "That code isn’t a CouchPad room."))
        }

        let lowerHost = host.lowercased()

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
        if let code = segments.first(where: validRoomCode) { return code }
        for item in components.queryItems ?? [] {
            if let value = item.value, validRoomCode(value) { return value }
        }
        return ""
    }
}

/// The suite's room-code shape: exact length, Base58, case-SENSITIVE.
func validRoomCode(_ code: String) -> Bool {
    code.count == CP.roomCodeLength && code.allSatisfy { CP.base58.contains($0) }
}
