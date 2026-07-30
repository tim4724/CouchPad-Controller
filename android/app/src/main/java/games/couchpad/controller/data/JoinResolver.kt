package games.couchpad.controller.data

import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.net.toUri
import games.couchpad.controller.BuildConfig
import games.couchpad.controller.R

/** The suite's canonical launcher domain (couchpad.games links, display fallback). */
const val LAUNCHER_HOST = "couchpad.games"

/**
 * Domains the launcher answered to before the couchpad.games rebrand. Still honoured
 * everywhere [LAUNCHER_HOST] is — canonical /<CODE> links, trusted preview subdomains,
 * the game-host navigation allow-list, hosted legal pages — so codes and links already
 * in the wild (printed, shared, indexed) keep opening in the app. Nothing is ever
 * *generated* on them: every URL the app builds or displays uses [LAUNCHER_HOST].
 */
val LEGACY_LAUNCHER_HOSTS = listOf("couch-games.com")

/** Every domain the launcher itself owns: [LAUNCHER_HOST] plus [LEGACY_LAUNCHER_HOSTS]. */
val LAUNCHER_HOSTS = listOf(LAUNCHER_HOST) + LEGACY_LAUNCHER_HOSTS

/** True when [host] is [domain] itself or any subdomain of it (case-insensitive). */
fun hostInDomain(host: String?, domain: String): Boolean {
  val h = host?.lowercase() ?: return false
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
 * The two code-first exceptions carry no origin of their own and resolve to the sole
 * live game's controllerBaseUrl: bare typed codes, and canonical couchpad.games/<code>
 * links (bare domain or www; [LEGACY_LAUNCHER_HOSTS] too).
 */
object JoinResolver {

  fun resolve(raw: String?, games: List<Game>): JoinOutcome {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return JoinOutcome.Failure(R.string.error_empty_code)

    val uri = runCatching { s.toUri() }.getOrNull()
    val host = uri?.host
    if (uri?.scheme == null || host == null) {
      // Bare code — no origin to load; the sole live game hosts it.
      return soleLiveGameJoin(games, roomCode = s, claim = null, instance = null)
    }

    val segs = uri.pathSegments
    val claim = uri.getQueryParameter("claim")
    val instance = uri.fragment?.let { f -> runCatching { Uri.decode(f) }.getOrDefault(f) }

    // Canonical couchpad.games/<CODE> links (bare domain or www, plus the legacy
    // domains) carry no controller origin of their own, so the sole live game hosts
    // them. The App Links filter claims exactly-6-char paths, so the marketing index
    // never reaches the app; a non-room 6-char path is rejected by validCode.
    // (Subdomains are preview deployments and load their own origin — below.)
    if (LAUNCHER_HOSTS.any { host.equals(it, true) || host.equals("www.$it", true) }) {
      return soleLiveGameJoin(games, segs.firstOrNull().orEmpty(), claim, instance)
    }

    // A game's own domain, or a launcher subdomain (preview/branch deployment, on
    // the canonical or a legacy domain). The subdomain prefix names the game when it
    // can ("tinytrack-…"), purely for title/metadata — the scanned URL itself is
    // what gets loaded.
    val game = games.firstOrNull { g -> g.hosts.any { hostInDomain(host, it) } }
      ?: when {
        LAUNCHER_HOSTS.any { hostInDomain(host, it) } ->
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
    uri.pathSegments.firstOrNull(::validCode)?.let { return it }
    for (name in uri.queryParameterNames) {
      uri.getQueryParameters(name).firstOrNull(::validCode)?.let { return it }
    }
    return ""
  }

  private fun soleLiveGameJoin(games: List<Game>, roomCode: String, claim: String?, instance: String?): JoinOutcome {
    val game = games.firstOrNull { it.isLive }
      ?: return JoinOutcome.Failure(R.string.error_no_live_game)
    val base = game.controllerBaseUrl
      ?: return JoinOutcome.Failure(R.string.error_no_controller_url)
    return joinAt(base, game, roomCode, claim, instance)
  }

  private fun joinAt(base: String, game: Game, roomCode: String, claim: String?, instance: String?): JoinOutcome {
    if (!validCode(roomCode)) return JoinOutcome.Failure(R.string.error_not_couchpad_room)
    val joinUrl = buildString {
      append(base.trimEnd('/')).append('/').append(roomCode)
      if (!claim.isNullOrEmpty()) append("?claim=").append(Uri.encode(claim))
      if (!instance.isNullOrEmpty()) append('#').append(Uri.encode(instance))
    }
    return JoinOutcome.Success(game, roomCode, joinUrl)
  }

  // Stand-in metadata for a launcher subdomain that doesn't map to any known
  // game — the deployment is still trusted and loads; only the title and
  // room-code format fall back to suite defaults.
  private fun launcherGame() = Game(
    id = "couchpad",
    name = "CouchPad",
    status = "live",
    accentColor = DefaultAccent,
    art = null,
    controllerBaseUrl = null,
    hosts = emptyList(),
  )

  private fun validCode(code: String): Boolean {
    if (code.length != ROOM_CODE_LENGTH) return false
    return code.all { it in BASE58 }
  }
}
