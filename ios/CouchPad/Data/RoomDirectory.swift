import Foundation

// MARK: - Lookup result

/// How often a room on screen is re-checked against its relay. One cadence for both card
/// kinds — a rejoin card and a nearby card make the same promise ("you can enter this"),
/// so they go stale the same way and are refreshed by the same probe.
let roomPollInterval: Duration = .seconds(10)

enum RoomLookup: Equatable {
    /// Room exists. url = host-declared controller-URL template (nil if none/blank);
    /// origin = the room's declared origin (nil if none/blank). BOTH host-declared and
    /// UNTRUSTED — resolve through the allow-list. A host may register an origin but no url.
    ///
    /// clients/maxClients are the room's live occupancy. Both 0 when the relay omitted
    /// them, and `isFull` is false in that case rather than guessing, so a relay that
    /// doesn't report occupancy never hides a joinable room.
    case found(url: String?, origin: String?, clients: Int, maxClients: Int)
    case notFound
    case error
}

// MARK: - Directory probe

enum RoomDirectory {

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 10
        return URLSession(configuration: config)
    }()

    /// GET {relayBase}/room/{androidUriEncode(code)}. Never throws.
    /// 200 + JSON object → .found; 404 → .notFound; anything else → .error.
    /// Empty trimmed code → .notFound (no network).
    static func lookup(code: String, relayBase: String = CP.relayBase) async -> RoomLookup {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .notFound
        }
        guard let url = URL(string: relayBase + "/room/" + androidUriEncode(trimmed)) else {
            return .error
        }
        do {
            let (data, response) = try await session.data(for: URLRequest(url: url))
            guard let http = response as? HTTPURLResponse else { return .error }
            switch http.statusCode {
            case 200:
                guard let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    return .error
                }
                func nonBlank(_ key: String) -> String? {
                    guard let s = body[key] as? String,
                          !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
                    return s
                }
                // The relay sends the literal string "unknown" when a room registered
                // no origin — not a URL, so treat it as absent.
                let origin = nonBlank("origin").flatMap { $0 == "unknown" ? nil : $0 }
                return .found(url: nonBlank("url"), origin: origin,
                              clients: body["clients"] as? Int ?? 0,
                              maxClients: body["maxClients"] as? Int ?? 0)
            case 404:
                return .notFound
            default:
                return .error
            }
        } catch {
            return .error
        }
    }
}

// MARK: - Join resolution

/// The one entry point for every join input: typed code, scanned QR, Universal Link,
/// rejoin and nearby URLs. An input that carries its own origin IS the controller and
/// resolves offline; an origin-less one (`originlessCode`) names a room but not a game,
/// and only the relay directory knows which game owns that code. Routing them all through
/// here is what stops a canonical couchpad.games/<code> link from being guessed onto the
/// sole live game when the room belongs to another.
///
/// Probes ALL relays in parallel (the sole live game's own relayProbeBase first, then the
/// shared relay deduped), awaits every probe, then applies the decision table.
func resolveJoin(_ raw: String, games: [Game]) async -> JoinOutcome {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
        return .failure(message: String(localized: "Enter a room code."))
    }
    // Has an origin of its own (or is a launcher link with no code at all): nothing to
    // look up — the offline resolver already knows what to load, or why it can't.
    guard let code = originlessCode(trimmed), !code.isEmpty else {
        return JoinResolver.resolve(trimmed, games: games)
    }
    return resolveLookups(code, results: await probeRelays(code, games: games),
                          games: games, from: trimmed)
}

/// The relays a code is checked against: the game's own relay first (so it wins ties),
/// then the shared directory. Probed in parallel, relay order preserved in the results.
func probeRelays(_ code: String, games: [Game]) async -> [RoomLookup] {
    let liveGames = games.filter { $0.isLive }
    let sole: Game? = liveGames.count == 1 ? liveGames[0] : nil
    var relays: [String] = []
    if let probeBase = sole?.relayProbeBase {
        relays.append(probeBase)
    }
    if !relays.contains(CP.relayBase) {
        relays.append(CP.relayBase)
    }
    var results = [RoomLookup](repeating: .error, count: relays.count)
    await withTaskGroup(of: (Int, RoomLookup).self) { group in
        for (index, relay) in relays.enumerated() {
            group.addTask {
                (index, await RoomDirectory.lookup(code: code, relayBase: relay))
            }
        }
        for await (index, result) in group {
            results[index] = result
        }
    }
    return results
}

/// The decision table over already-fetched lookups — separate from `resolveJoin` so
/// a caller that probed for its own reasons (`resolveNearby`'s fullness check) resolves
/// from those results instead of probing the same relays a second time.
///
/// `from` is the input `code` was read out of, defaulting to the code itself. It matters
/// only on the sole-live-game rungs, which re-resolve it whole so a canonical link keeps
/// its query and fragment (`?cpp=`, `#instance`). The origin rung builds a fresh URL from
/// the code and deliberately carries nothing over.
func resolveLookups(_ code: String, results: [RoomLookup], games: [Game],
                    from: String? = nil) -> JoinOutcome {
    let source = from ?? code
    let liveGames = games.filter { $0.isLive }
    let sole: Game? = liveGames.count == 1 ? liveGames[0] : nil

    // Deliberately NOT refused when full, though the lookup reports it: a full room still
    // takes its own players back (the relay swaps a stored clientId into the slot it is
    // holding for them), and from a code alone we cannot tell that player from a stranger.
    // Refusing here locks someone out of the room they are already in. Let the load happen
    // and let the relay decide — a stranger bounces back on `game_full`, which the shell
    // already turns into a banner. Only the nearby list, which never offers a room the
    // player has a slot in, can safely act on `isFull`.

    // Rule 1: first Found with a non-nil url (relay-list order) → resolve that URL (untrusted; re-validated).
    // A url that is itself origin-less (a couchpad.games/<code> template) declares nothing
    // the directory hadn't already told us — skip it, so the room's own origin gets its
    // turn instead of the code being handed to the sole live game.
    for result in results {
        if case .found(let url, _, _, _) = result, let url, originlessCode(url) == nil {
            return JoinResolver.resolve(url, games: games)
        }
    }

    // Rule 1b: no url anywhere, but a Found declared an origin (e.g. a preview deployment
    // on a launcher subdomain owned by a not-yet-"live" game) → load the code at
    // that origin. Untrusted; the resolver host-checks it against the allow-list.
    for result in results {
        if case .found(_, let origin, _, _) = result, let origin {
            return JoinResolver.resolve(origin.trimmingTrailingSlashes() + "/" + code, games: games)
        }
    }

    let anyFound = results.contains { if case .found = $0 { return true } else { return false } }

    // Rule 2: any Found (all nil urls and origins) + sole live game → bare-code resolve.
    if anyFound, sole != nil {
        return JoinResolver.resolve(source, games: games)
    }
    // Rule 3: any Found, no sole live game.
    if anyFound {
        return .failure(message: String(localized: "This code can’t be matched to a game right now."))
    }

    // Rule 4: no Found; sole live game + at least one Error → optimistic bare-code resolve.
    let anyError = results.contains(.error)
    if sole != nil, anyError {
        return JoinResolver.resolve(source, games: games)
    }

    // Rule 5: at least one NotFound.
    if results.contains(.notFound) {
        return .failure(message: String(localized: "Room not found or expired."))
    }

    // Rule 6: everything errored, no sole live game.
    return .failure(message: String(localized: "Couldn’t reach the server. Try again."))
}

extension RoomLookup {
    /// The display occupies a slot too, so this is exact, not off by one.
    var isFull: Bool {
        guard case .found(_, _, let clients, let maxClients) = self else { return false }
        return maxClients > 0 && clients >= maxClients
    }
}
