package games.couchpad.controller.data

import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.net.toUri
import games.couchpad.controller.BuildConfig
import games.couchpad.controller.R

/** Id of the stand-in game used when a launcher subdomain matches no real game. */
const val SYNTHETIC_GAME_ID = "couchpad"

/** The suite's canonical launcher domain (couchpad.games links, display fallback). */
const val LAUNCHER_HOST = "couchpad.games"

/** True when [host] is [domain] itself or any subdomain of it (case-insensitive).
 * The host arrives percent-DECODED from the URI parser, so anything outside the
 * hostname alphabet (an embedded '/', say, from "evil.com%2f.example.com") must
 * never suffix-match — the raw string is what gets loaded. */
fun hostInDomain(host: String?, domain: String): Boolean {
  val h = host?.lowercase() ?: return false
  if (h.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) return false
  val d = domain.lowercase()
  return h == d || h.endsWith(".$d")
}

/**
 * A private/LAN host: an RFC-1918 IPv4 literal (10/8, 172.16/12, 192.168/16), loopback
 * (127/8), link-local (169.254/16), "localhost", or an mDNS ".local" name. Debug builds
 * relax the https-only join/navigation gates for these so a controller served off a dev
 * machine on the local network (http://192.168.x.y:PORT/…) can be scanned and loaded;
 * release builds ignore this entirely — public hosts are never matched here.
 */
fun isPrivateHost(host: String?): Boolean {
  val h = host?.lowercase() ?: return false
  if (h == "localhost" || h.endsWith(".local")) return true
  val octets = h.split(".")
  if (octets.size != 4) return false
  val nums = octets.map { it.toIntOrNull() ?: return false }
  if (nums.any { it !in 0..255 }) return false
  val (a, b) = nums[0] to nums[1]
  return a == 10 ||
    (a == 172 && b in 16..31) ||
    (a == 192 && b == 168) ||
    a == 127 ||
    (a == 169 && b == 254)
}

/**
 * The room code an input names when it carries no controller origin of its own: a bare
 * typed code, or a canonical couchpad.games/<code> link (bare domain or www — a subdomain
 * is a preview deployment and IS its own origin). Null when the input has an origin, in
 * which case that URL is the controller and loads verbatim. "" for a launcher link with
 * no path segment.
 *
 * Such an input names a room but not a game — exactly what the relay directory answers —
 * so this is the one test [resolveJoin] needs, and the same test that tells a §6 `url`
 * that declares a controller apart from one that declares nothing.
 */
fun originlessCode(raw: String?): String? {
  val s = raw?.trim().orEmpty()
  if (s.isEmpty()) return null
  val uri = runCatching { s.toUri() }.getOrNull()
  val host = uri?.host
  if (uri?.scheme == null || host == null) return s
  if (!host.equals(LAUNCHER_HOST, true) && !host.equals("www.$LAUNCHER_HOST", true)) return null
  return uri.pathSegments.firstOrNull().orEmpty()
}

sealed interface JoinOutcome {
  data class Success(
    val game: Game,
    val roomCode: String,
    val joinUrl: String,
  ) : JoinOutcome

  data class Failure(@param:StringRes val messageRes: Int) : JoinOutcome
}

/**
 * Resolves a scanned/typed value into a controller join target. Never throws.
 *
 * Principle: a trusted host IS the controller. A scanned URL on a game's own domain
 * (or a launcher preview subdomain) loads VERBATIM — we vouch for the host and
 * the https scheme but never presume its path/query layout. The room code is then
 * best-effort ([extractRoomCode]), used only to label and liveness-poll the rejoin
 * card; a URL that hides it still joins fine.
 *
 * An input with no origin of its own — a bare code, a canonical couchpad.games/<code>
 * link — names a room but no controller, so it has nothing to resolve here and belongs
 * to the relay directory ([resolveJoin]).
 */
object JoinResolver {

  fun resolve(raw: String?, games: List<Game>): JoinOutcome {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return JoinOutcome.Failure(R.string.error_empty_code)

    val uri = runCatching { s.toUri() }.getOrNull()
    val host = uri?.host
    // No controller origin: a bare code, or a canonical couchpad.games/<code> link —
    // that link is the launcher asking the directory who owns the code, not an answer.
    // (Launcher SUBdomains are preview deployments and load their own origin — below.)
    // A null originlessCode already implies a parsed scheme and host; those two clauses
    // are what smart-casts them non-null below.
    if (originlessCode(s) != null || uri?.scheme == null || host == null) {
      return JoinOutcome.Failure(R.string.error_not_couchpad_room)
    }

    // A game's own domain, or a launcher subdomain (preview/branch deployment). The
    // subdomain prefix names the game when it can ("tinytrack-…"), purely for
    // title/metadata — the scanned URL itself is what gets loaded.
    val game = games.firstOrNull { g -> g.hosts.any { hostInDomain(host, it) } }
      ?: when {
        hostInDomain(host, LAUNCHER_HOST) ->
          games.firstOrNull { host.lowercase().startsWith(it.id) } ?: launcherGame()
        // Debug only: a LAN dev server isn't any known game's host — load it as its
        // own trusted controller so a locally served page can be tested end-to-end.
        BuildConfig.DEBUG && isPrivateHost(host) -> launcherGame()
        else -> return JoinOutcome.Failure(R.string.error_not_couchpad_room)
      }
    return joinVerbatim(s, uri, game)
  }

  // A trusted host's scanned URL IS its controller — load it exactly as scanned rather
  // than presuming its path/query layout. We vouch only for the host (already matched)
  // and an https scheme with no embedded credentials; the room code is best-effort. The
  // scanned URL carries its own claim/instance, so there's nothing to re-attach here.
  private fun joinVerbatim(url: String, uri: Uri, game: Game): JoinOutcome {
    val scheme = uri.scheme?.lowercase()
    // https always; plain http only for a LAN host in debug (see [isPrivateHost]).
    val schemeOk = scheme == "https" ||
      (BuildConfig.DEBUG && scheme == "http" && isPrivateHost(uri.host))
    if (!schemeOk || !uri.userInfo.isNullOrEmpty()) {
      return JoinOutcome.Failure(R.string.error_not_couchpad_room)
    }
    return JoinOutcome.Success(game, extractRoomCode(uri), url)
  }

  // Best-effort room code: the first room-code-shaped token (Base58, exact length) in a
  // path segment, else a query value. "" when the URL surfaces none — the join still
  // loads; the rejoin card just can't show or liveness-poll the room.
  private fun extractRoomCode(uri: Uri): String {
    uri.pathSegments.firstOrNull(::validRoomCode)?.let { return it }
    for (name in uri.queryParameterNames) {
      uri.getQueryParameters(name).firstOrNull(::validRoomCode)?.let { return it }
    }
    return ""
  }

  // Stand-in metadata for a launcher subdomain that doesn't map to any known
  // game — the deployment is still trusted and loads; only the title and
  // room-code format fall back to suite defaults.
  private fun launcherGame() = Game(
    id = SYNTHETIC_GAME_ID,
    name = "CouchPad",
    status = "live",
    accentColor = DefaultAccent,
    art = null,
    controllerBaseUrl = null,
    hosts = emptyList(),
  )
}

/** The suite's room-code shape: exact length, Base58, case-SENSITIVE. */
fun validRoomCode(code: String): Boolean =
  code.length == ROOM_CODE_LENGTH && code.all { it in BASE58 }
