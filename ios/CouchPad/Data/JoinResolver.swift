import Foundation

// MARK: - Outcome

enum JoinOutcome: Equatable {
    case success(game: Game, roomCode: String, joinUrl: String)
    case failure(message: String)
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
            return soleLiveGameJoin(games: games, roomCode: trimmed, claim: nil, instance: nil)
        }

        let claim = components.queryItems?.first(where: { $0.name == "claim" })?.value
        let instance: String? = components.percentEncodedFragment.map { $0.removingPercentEncoding ?? $0 }
        let lowerHost = host.lowercased()

        // Canonical launcher links (bare domain or www, legacy domains too): code-first,
        // sole live game hosts them.
        if CP.launcherHosts.contains(where: { lowerHost == $0 || lowerHost == "www." + $0 }) {
            let code = components.path.split(separator: "/").map(String.init).first ?? ""
            return soleLiveGameJoin(games: games, roomCode: code, claim: claim, instance: instance)
        }

        // A game's own domain (or a launcher preview subdomain): the scanned URL
        // IS the controller — load it verbatim, don't presume its layout.
        let game: Game
        if let matched = games.first(where: { g in g.hosts.contains(where: { hostInDomain(host, $0) }) }) {
            game = matched
        } else if CP.launcherHosts.contains(where: { hostInDomain(host, $0) }) {
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

    private static func soleLiveGameJoin(games: [Game], roomCode: String,
                                         claim: String?, instance: String?) -> JoinOutcome {
        guard let game = games.first(where: { $0.isLive }) else {
            return .failure(message: String(localized: "No live game configured."))
        }
        guard let base = game.controllerBaseUrl else {
            return .failure(message: String(localized: "That game has no controller URL."))
        }
        return joinAt(base: base, game: game, roomCode: roomCode, claim: claim, instance: instance)
    }

    private static func joinAt(base: String, game: Game, roomCode: String,
                               claim: String?, instance: String?) -> JoinOutcome {
        // Validate: exact length and every char in the suite charset (case-sensitive).
        guard isValidCode(roomCode) else {
            return .failure(message: String(localized: "That code isn’t a CouchPad room."))
        }

        var joinUrl = base.trimmingTrailingSlashes() + "/" + roomCode
        if let claim, !claim.isEmpty {
            joinUrl += "?claim=" + androidUriEncode(claim)
        }
        if let instance, !instance.isEmpty {
            joinUrl += "#" + androidUriEncode(instance)
        }
        return .success(game: game, roomCode: roomCode, joinUrl: joinUrl)
    }
}
