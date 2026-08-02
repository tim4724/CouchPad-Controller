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
   * Room exists. [url] is the host-declared controller-URL template; [origin] is the
   * room's declared origin (e.g. a preview deployment host). BOTH are host-declared and
   * UNTRUSTED — resolve them through the manifest allow-list before loading. A host may
   * register an origin but no url template, so [url] can be null while [origin] is set.
   *
   * [clients]/[maxClients] are the room's live occupancy, as declared at create. Both
   * are 0 when the relay omitted them; [isFull] is false in that case rather than
   * guessing, so a relay that doesn't report occupancy never hides a joinable room.
   */
  data class Found(
    val url: String?,
    val origin: String?,
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
  suspend fun lookup(code: String, relayBase: String = RELAY_BASE): RoomLookup = withContext(Dispatchers.IO) {
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
            // The relay sends the literal string "unknown" when a room registered no
            // origin — not a URL, so treat it as absent.
            origin = json.optString("origin", "").ifBlank { null }?.takeIf { it != "unknown" },
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
 * Resolve a hand-typed room code to a join target. A relay tells us which game owns
 * the code (via its stored controller URL, or failing that the room's origin); we then
 * re-validate that host against the manifest allow-list — both are host-declared and
 * untrusted. The origin fallback matters for games whose display registers a room but
 * no url template (e.g. a preview deployment on a launcher subdomain): without
 * it the code would wrongly resolve to the sole *live* game instead of its real owner.
 *
 * The shared relay ([RELAY_BASE]) doesn't yet own every game's rooms, so we also
 * query the sole live game's own relay ([Game.relayProbeBase]) IN PARALLEL and take
 * whichever actually has the code — its host-declared `url` is what loads (e.g.
 * `main.hexstacker.com/<code>`), NOT the manifest's `controllerBaseUrl`. The game's
 * own relay is preferred when both answer. With more than one live game there is no
 * sole relay to fall back to, so only the shared relay is consulted.
 */
suspend fun resolveTypedCode(code: String, games: List<Game>): JoinOutcome = coroutineScope {
  val trimmed = code.trim()
  if (trimmed.isEmpty()) return@coroutineScope JoinOutcome.Failure(R.string.error_enter_room_code)
  val sole = games.singleOrNull { it.isLive }

  // Race the game's own relay (first, so it wins ties) and the shared directory.
  val relays = buildList {
    sole?.relayProbeBase?.let(::add)
    if (RELAY_BASE !in this) add(RELAY_BASE)
  }
  val results = relays.map { base -> async { RoomDirectory.lookup(trimmed, base) } }.awaitAll()

  val founds = results.filterIsInstance<RoomLookup.Found>()
  val foundUrl = founds.firstOrNull { it.url != null }?.url
  val foundOrigin = founds.firstOrNull { it.origin != null }?.origin
  when {
    // A relay knows the room and handed back the controller URL — load exactly that.
    foundUrl != null -> JoinResolver.resolve(foundUrl, games)
    // No URL template, but the room declared its origin (e.g. a preview deployment on a
    // launcher subdomain owned by a not-yet-"live" game). Load the code at that
    // origin. The origin is host-declared and untrusted; the resolver host-checks it.
    foundOrigin != null -> JoinResolver.resolve("${foundOrigin.trimEnd('/')}/$trimmed", games)
    // Room exists but the relay stored neither URL nor origin: the sole live game hosts it.
    founds.isNotEmpty() && sole != null -> JoinResolver.resolve(trimmed, games)
    founds.isNotEmpty() -> JoinOutcome.Failure(R.string.error_code_unmatched)
    // No relay had the room. If any errored we couldn't truly verify — with a sole
    // live game, resolve optimistically and let its own page decide.
    sole != null && results.any { it is RoomLookup.Error } -> JoinResolver.resolve(trimmed, games)
    results.any { it is RoomLookup.NotFound } -> JoinOutcome.Failure(R.string.error_room_not_found_or_expired)
    else -> JoinOutcome.Failure(R.string.error_server_unreachable)
  }
}
