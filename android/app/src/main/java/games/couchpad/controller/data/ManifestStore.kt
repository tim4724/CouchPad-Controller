package games.couchpad.controller.data

import android.content.Context
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The games list the launcher renders: the last manifest fetched from
 * couchpad.games (persisted verbatim), seeded from the bundled copy until a
 * fetch has ever succeeded. [refresh] pulls at most once per process launch —
 * a launch-fresh list is fresh enough, and every failure silently keeps the
 * current list. Served art paths may name files this build didn't ship; GameArt
 * pulls those through ArtworkCache.
 */
object ManifestStore {
  private const val MANIFEST_URL = "https://$LAUNCHER_HOST/games-manifest.json"
  private const val PREFS = "cp_manifest"
  private const val KEY_JSON = "json"

  /** Sanity cap for a served manifest (ours is ~1 KB) — a deploy mistake must not balloon memory. */
  private const val MAX_BYTES = 1 shl 20

  private val flow = MutableStateFlow<List<Game>>(emptyList())

  // Composition-driven, so main-thread only — no locking needed.
  private var seeded = false
  private var refreshed = false

  /** Current games, seeded synchronously on first call (no empty first frame). Main thread only. */
  fun games(context: Context): StateFlow<List<Game>> {
    if (!seeded) {
      seeded = true
      val cached = prefs(context).getString(KEY_JSON, null)?.let { GamesManifest.parse(it) }
      flow.value = cached ?: GamesManifest.loadBundled(context)
    }
    return flow
  }

  /** Fetches the served manifest and, when it validates, persists + publishes it live. */
  suspend fun refresh(context: Context) {
    if (refreshed) return
    refreshed = true
    val text = withContext(Dispatchers.IO) { fetchText() } ?: return
    // Identical to the seeded copy (the common case every launch) — the current
    // list already reflects it, so skip the re-parse and the prefs rewrite.
    if (text == prefs(context).getString(KEY_JSON, null)) return
    val games = GamesManifest.parse(text) ?: return
    prefs(context).edit { putString(KEY_JSON, text) }
    flow.value = games
  }

  private fun fetchText(): String? = httpGet(MANIFEST_URL) { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      out.write(buf, 0, n)
      if (out.size() > MAX_BYTES) return@httpGet null
    }
    out.toString(Charsets.UTF_8.name())
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
