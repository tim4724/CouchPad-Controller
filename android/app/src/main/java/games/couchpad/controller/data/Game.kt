package games.couchpad.controller.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import org.json.JSONObject

// Room codes are minted by the shared relay, so the format is suite-wide, not
// per game: Base58 (case-SENSITIVE, excludes 0 O I l), six characters.
const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
const val ROOM_CODE_LENGTH = 6

// Illustrative code shown in the entry field's placeholder — never a real room, just
// the shape (Base58, six chars) a typed/scanned code takes. Passed into code_placeholder
// so the "e.g." copy stays localized while the sample lives in one place.
const val SAMPLE_ROOM_CODE = "A3KX9p"

/** Suite indigo — the accent for games with no (valid) accentColor of their own. */
val DefaultAccent = Color(0xFF6C5CE7)

/** One game from games-manifest.json (carousel + scan-resolution source). */
data class Game(
  val id: String,
  val name: String,
  val status: String,            // "live" | "soon"
  val minPlayers: Int? = null,   // player-count range endpoints, e.g. 1..8;
  val maxPlayers: Int? = null,   // rendered via the game_players_* plurals (GameSheets)
  val video: String? = null,     // https URL of a muted gameplay loop, cached on demand (TrailerCache)
  val accentColor: Color,
  val art: String?,              // asset-relative path, e.g. "artwork/hexstacker-16x9-v2.webp"
  // Square brand mark, same asset-path + cache rules as [art]. Distinct from the
  // 16:9 cover: a poster crop is unreadable at icon size (NearbyCard).
  val icon: String? = null,
  val controllerBaseUrl: String?,
  val hosts: List<String>,       // domains that resolve to this game (subdomains included)
  // The game's own relay (pre-unification) — where its rooms actually live, so
  // room-alive probes go here rather than the shared directory.
  val relayProbeBase: String? = null,
) {
  val isLive: Boolean get() = status == "live"

  /** Where this game's rooms live: its own relay, else the shared one. */
  val roomRelayBase: String get() = relayProbeBase ?: RELAY_BASE

  /**
   * Host to SHOW users, from the canonical controller URL — [hosts] is the
   * security allow-list, whose ordering must not become display copy.
   */
  val displayHost: String? get() = controllerBaseUrl?.let { runCatching { it.toUri().host }.getOrNull() }
}

/** Manifest parsing — shared by the bundled seed and the served copy (ManifestStore). */
object GamesManifest {
  /**
   * Parses manifest [text]. All-or-nothing: any structural failure or an empty
   * games list → null, so a bad fetch can never half-apply over a good list.
   * There is no in-band schema version — the latest served JSON is the truth,
   * and a breaking rework (if ever) ships under a new manifest URL.
   */
  fun parse(text: String): List<Game>? = runCatching {
    val games = JSONObject(text).getJSONArray("games")
    if (games.length() == 0) return@runCatching null
    (0 until games.length()).map { i ->
      val g = games.getJSONObject(i)
      val id = g.getString("id")
      val hostsArr = g.optJSONArray("hosts")
      Game(
        id = id,
        name = g.getString("name"),
        status = g.optString("status", "soon"),
        // Player counts are structural data; the display copy is rendered from them
        // via the shared game_players_* plurals (GameSheets), not stored per game.
        minPlayers = g.optIntOrNull("minPlayers"),
        maxPlayers = g.optIntOrNull("maxPlayers"),
        video = g.optHttpsUrl("video"),
        accentColor = parseHexColor(g.optString("accentColor", "")),
        art = g.optNonBlank("art")?.removePrefix("/"),
        icon = g.optNonBlank("icon")?.removePrefix("/"),
        controllerBaseUrl = g.optHttpsUrl("controllerBaseUrl"),
        // Strictly strings (getString would coerce a stray object/number into a junk
        // allow-list entry) — a malformed hosts array fails the whole parse, keeping
        // the documented all-or-nothing promise. iOS matches.
        hosts = if (hostsArr != null) {
          (0 until hostsArr.length()).map { hostsArr.get(it) as? String ?: error("hosts[$it]") }
        } else {
          emptyList()
        },
        relayProbeBase = g.optHttpsUrl("relayProbeBase")?.trimEnd('/'),
      )
    }
  }.getOrNull()

  /** The manifest shipped in assets — the seed every install can fall back to. Never throws. */
  fun loadBundled(context: Context): List<Game> = runCatching {
    context.assets.open("games-manifest.json").bufferedReader().use { it.readText() }
  }.getOrNull()?.let { parse(it) } ?: emptyList()
}

/**
 * Absolute https URL for a manifest art path: site-relative paths (the leading "/"
 * is already stripped at parse) resolve against the launcher host; absolute URLs
 * must be https or they're refused.
 */
fun remoteArtUrl(art: String): String? = when {
  art.startsWith("https://") -> art
  "://" in art -> null
  else -> "https://$LAUNCHER_HOST/$art"
}

private fun JSONObject.optNonBlank(name: String): String? = optString(name, "").ifBlank { null }

/**
 * The manifest may arrive over the network, and its URLs get loaded / probed —
 * https only, one policy for every URL field.
 */
private fun JSONObject.optHttpsUrl(name: String): String? =
  optNonBlank(name)?.takeIf { it.startsWith("https://") }

/** A positive integer field, or null if absent/non-numeric. Strictly a number —
 * optInt would coerce the string "8", which iOS's `as? Int` rejects. */
private fun JSONObject.optIntOrNull(name: String): Int? =
  (opt(name) as? Int)?.takeIf { it > 0 }

fun parseHexColor(hex: String): Color = runCatching {
  val c = hex.removePrefix("#")
  if (c.length >= 6) Color(c.substring(0, 2).toInt(16), c.substring(2, 4).toInt(16), c.substring(4, 6).toInt(16))
  else DefaultAccent
}.getOrElse { DefaultAccent }
