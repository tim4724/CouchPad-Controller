package games.couchpad.controller.data

import android.net.Uri
import games.couchpad.controller.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// One relay for every game. It doubles as the room→controller directory: a room
// stores the controller-URL template its host declared on create, and the probe
// below hands it back filled in (see the Party-Sockets `url` field).
const val RELAY_BASE = "https://ws.couchpad.games"

/**
 * How often a room on screen is re-checked against its relay. One cadence for both card
 * kinds — a rejoin card and a nearby card make the same promise ("you can enter this"),
 * so they go stale the same way and are refreshed by the same probe.
 */
const val ROOM_POLL_MS = 10_000L

sealed interface RoomLookup {
  /**
   * Room exists. [url] is the host-declared controller-URL template (§6), and the only
   * thing that says where the room lives — it is UNTRUSTED, so resolve it through the
   * manifest allow-list before loading. Null when the display registered none, which
   * leaves nothing to resolve the code with.
   *
   * [clients]/[maxClients] are the room's live occupancy, as declared at create. Both
   * are 0 when the relay omitted them; [isFull] is false in that case rather than
   * guessing, so a relay that doesn't report occupancy never hides a joinable room.
   */
  data class Found(
    val url: String?,
    val clients: Int = 0,
    val maxClients: Int = 0,
  ) : RoomLookup {
    /** The display occupies a slot too, so this is exact, not off by one. */
    val isFull: Boolean get() = maxClients > 0 && clients >= maxClients
  }
  data object NotFound : RoomLookup
  data object Error : RoomLookup
}

object RoomDirectory {
  /** Probe the relay for a room code. Never throws; network failure → [RoomLookup.Error]. */
  suspend fun lookup(code: String, relayBase: String): RoomLookup = withContext(Dispatchers.IO) {
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return@withContext RoomLookup.NotFound
    val conn = runCatching {
      (URL("$relayBase/room/${Uri.encode(trimmed)}").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 5000
        readTimeout = 5000
      }
    }.getOrElse { return@withContext RoomLookup.Error }
    try {
      when (conn.responseCode) {
        200 -> {
          val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
          RoomLookup.Found(
            url = json.optString("url", "").ifBlank { null },
            clients = json.optInt("clients", 0),
            maxClients = json.optInt("maxClients", 0),
          )
        }
        404 -> RoomLookup.NotFound
        else -> RoomLookup.Error
      }
    } catch (_: Exception) {
      RoomLookup.Error
    } finally {
      conn.disconnect()
    }
  }
}

/**
 * The one entry point for every join input: typed code, scanned QR, App Link, rejoin
 * and nearby URLs. An input that carries its own origin IS the controller and resolves
 * offline; an origin-less one ([originlessCode]) names a room but not a game, and only
 * the relay directory knows which game owns that code — there is nothing to guess with,
 * so a code the directory can't place does not join.
 *
 * The relay names the owner through the §6 controller-URL template the display
 * registered at room create; that template is host-declared and UNTRUSTED, so it is
 * re-validated against the manifest allow-list before it loads.
 */
suspend fun resolveJoin(raw: String, games: List<Game>): JoinOutcome {
  val trimmed = raw.trim()
  if (trimmed.isEmpty()) return JoinOutcome.Failure(R.string.error_enter_room_code)
  // Has an origin of its own (or is a launcher link with no code at all): nothing to
  // look up — the offline resolver already knows what to load, or why it can't.
  val code = originlessCode(trimmed)
  if (code.isNullOrEmpty()) return JoinResolver.resolve(trimmed, games)
  return resolveLookups(probeRelays(code, games), games)
}

/** The relays a code could live on: every live game's own, then the shared directory.
 * Probed in parallel; a game's own relay comes first, so it wins ties. */
suspend fun probeRelays(code: String, games: List<Game>): List<RoomLookup> =
  probeAll(code, games.filter { it.isLive }.mapNotNull { it.relayProbeBase })

/**
 * The relays a room whose GAME is already known is checked against — the liveness poll
 * behind the rejoin card. Its own relay first, then the shared directory: the room may
 * have been minted on either, and a room must not be declared dead by a relay that
 * never held it.
 */
suspend fun probeRoom(code: String, game: Game): List<RoomLookup> =
  probeAll(code, listOfNotNull(game.relayProbeBase))

private suspend fun probeAll(code: String, preferred: List<String>): List<RoomLookup> = coroutineScope {
  (preferred + RELAY_BASE).distinct()
    .map { base -> async { RoomDirectory.lookup(code, base) } }
    .awaitAll()
}

/**
 * The decision table over already-fetched lookups — separate from [resolveJoin] so
 * a caller that probed for its own reasons ([resolveNearby]'s fullness check) resolves
 * from those results instead of probing the same relays a second time.
 */
fun resolveLookups(results: List<RoomLookup>, games: List<Game>): JoinOutcome {
  val founds = results.filterIsInstance<RoomLookup.Found>()
  // Deliberately NOT refused when full, though the lookup reports it: a full room still
  // takes its own players back (the relay swaps a stored clientId into the slot it is
  // holding for them), and from a code alone we cannot tell that player from a stranger.
  // Refusing here locks someone out of the room they are already in. Let the load happen
  // and let the relay decide — a stranger bounces back on `game_full`, which the shell
  // already turns into a banner. Only the nearby list, which never offers a room the
  // player has a slot in, can safely act on `isFull`.

  // A `url` that is itself origin-less (a couchpad.games/<code> template) declares nothing
  // the directory hadn't already told us, so it doesn't count as one.
  val foundUrl = founds.firstOrNull { it.url != null && originlessCode(it.url) == null }?.url
  return when {
    // A relay knows the room and handed back the controller URL — load exactly that.
    foundUrl != null -> JoinResolver.resolve(foundUrl, games)
    // The room is there but nothing says where it lives. §6: registering a usable
    // template is what makes a code joinable, so this is a display bug, and the honest
    // answer is to say the code can't be placed rather than guess a game for it.
    founds.isNotEmpty() -> JoinOutcome.Failure(R.string.error_code_unmatched)
    results.any { it is RoomLookup.NotFound } -> JoinOutcome.Failure(R.string.error_room_not_found_or_expired)
    else -> JoinOutcome.Failure(R.string.error_server_unreachable)
  }
}
