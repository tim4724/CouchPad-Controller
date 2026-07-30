import Foundation

// MARK: - Lookup result

enum RoomLookup: Equatable {
    /// Room exists. url = host-declared controller-URL template (nil if none/blank);
    /// origin = the room's declared origin (nil if none/blank). BOTH host-declared and
    /// UNTRUSTED — resolve through the allow-list. A host may register an origin but no url.
    case found(url: String?, origin: String?)
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
    /// 200 + JSON object → .found(url:origin:); 404 → .notFound; anything else → .error.
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
                return .found(url: nonBlank("url"), origin: nonBlank("origin"))
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

// MARK: - Typed-code resolution

/// Hand-typed code flow: probes ALL relays in parallel (the sole live game's own relayProbeBase first,
/// then the shared relay deduped), awaits every probe, then applies the decision table.
func resolveTypedCode(_ code: String, games: [Game]) async -> JoinOutcome {
    let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
        return .failure(message: String(localized: "Enter a room code."))
    }

    // The SINGLE live game (nil if zero or more than one is live).
    let liveGames = games.filter { $0.isLive }
    let sole: Game? = liveGames.count == 1 ? liveGames[0] : nil

    // Relay list, order matters: the sole game's own relay first (wins ties), shared relay deduped.
    var relays: [String] = []
    if let probeBase = sole?.relayProbeBase {
        relays.append(probeBase)
    }
    if !relays.contains(CP.relayBase) {
        relays.append(CP.relayBase)
    }

    // Probe all in parallel, preserving relay order in the results.
    var results = [RoomLookup](repeating: .error, count: relays.count)
    await withTaskGroup(of: (Int, RoomLookup).self) { group in
        for (index, relay) in relays.enumerated() {
            group.addTask {
                (index, await RoomDirectory.lookup(code: trimmed, relayBase: relay))
            }
        }
        for await (index, result) in group {
            results[index] = result
        }
    }

    // Rule 1: first Found with a non-nil url (relay-list order) → resolve that URL (untrusted; re-validated).
    for result in results {
        if case .found(let url, _) = result, let url {
            return JoinResolver.resolve(url, games: games)
        }
    }

    // Rule 1b: no url anywhere, but a Found declared an origin (e.g. a preview deployment
    // on a launcher subdomain owned by a not-yet-"live" game) → load the code at
    // that origin. Untrusted; the resolver host-checks it against the allow-list.
    for result in results {
        if case .found(_, let origin) = result, let origin {
            return JoinResolver.resolve(origin.trimmingTrailingSlashes() + "/" + trimmed, games: games)
        }
    }

    let anyFound = results.contains { if case .found = $0 { return true } else { return false } }

    // Rule 2: any Found (all nil urls and origins) + sole live game → bare-code resolve.
    if anyFound, sole != nil {
        return JoinResolver.resolve(trimmed, games: games)
    }
    // Rule 3: any Found, no sole live game.
    if anyFound {
        return .failure(message: String(localized: "This code can’t be matched to a game right now."))
    }

    // Rule 4: no Found; sole live game + at least one Error → optimistic bare-code resolve.
    let anyError = results.contains(.error)
    if sole != nil, anyError {
        return JoinResolver.resolve(trimmed, games: games)
    }

    // Rule 5: at least one NotFound.
    if results.contains(.notFound) {
        return .failure(message: String(localized: "Room not found or expired."))
    }

    // Rule 6: everything errored, no sole live game.
    return .failure(message: String(localized: "Couldn’t reach the server. Try again."))
}
