package games.couchpad.controller.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.graphics.scale
import androidx.compose.ui.graphics.asImageBitmap

/** A captured favicon plus whether its opaque content reads as light. */
class Favicon(val image: ImageBitmap, val contentIsLight: Boolean)

/**
 * The room this phone is in or just left, with everything the home rejoin card needs.
 * [joinUrl] omits cpv/cpName — re-wrapped with the current name at rejoin. [favicon]
 * and [title] are captured from the controller page mid-session, so they're null until
 * then.
 */
class RecentRoom(
  val game: Game,
  val joinUrl: String,
  val roomCode: String,
  val favicon: Favicon?,
  val title: String?,
)

/**
 * Single-slot, in-memory memory of the current room. Deliberately not persisted:
 * rejoin is a same-session convenience, so the slot dies with the process and ages out
 * after [TTL_MS] — a fresh launch simply shows no card. [remember] sets the base at
 * join; the favicon and title arrive later, captured in-game.
 */
object RecentRoomStore {
  private const val TTL_MS = 20L * 60 * 1000

  private var game: Game? = null
  private var joinUrl: String = ""
  private var roomCode: String = ""
  private var favicon: Favicon? = null
  private var title: String? = null
  private var savedAt: Long = 0

  @Synchronized
  fun remember(game: Game, joinUrl: String, roomCode: String) {
    this.game = game
    this.joinUrl = joinUrl
    this.roomCode = roomCode
    this.favicon = null
    this.title = null
    this.savedAt = System.currentTimeMillis()
  }

  fun putFavicon(bitmap: Bitmap) {
    // Decode + luminance run off the lock; only the slot assignment is guarded.
    val captured = Favicon(bitmap.asImageBitmap(), bitmap.contentIsLight())
    synchronized(this) { if (game != null) favicon = captured }
  }

  /** Sanitizes [raw] (trim, collapse whitespace, cap length), stores it as the
   *  active room's title, and returns the cleaned value so callers can display the
   *  same text. Null when there's no active room or nothing survives cleaning. */
  @Synchronized
  fun putTitle(raw: String): String? {
    if (game == null) return null
    val clean = raw.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE_LEN)
    if (clean.isEmpty()) return null
    title = clean
    return clean
  }

  /** The current room while still fresh, else null (clearing an aged-out slot). */
  @Synchronized
  fun current(): RecentRoom? {
    val g = game ?: return null
    if (System.currentTimeMillis() - savedAt > TTL_MS) {
      clear()
      return null
    }
    return RecentRoom(g, joinUrl, roomCode, favicon, title)
  }

  @Synchronized
  fun clear() {
    game = null
    joinUrl = ""
    roomCode = ""
    favicon = null
    title = null
    savedAt = 0
  }

  private const val MAX_TITLE_LEN = 64
}

// Alpha-weighted mean perceptual luminance of the icon's opaque pixels > 0.6. Weighting
// by alpha keeps a transparent surround from dragging the mean toward black. Scaled to a
// fixed 16×16 first, so the cost is constant regardless of source size (a 180px
// apple-touch-icon included) and it's one getPixels() read, not hundreds of getPixel().
private fun Bitmap.contentIsLight(): Boolean {
  val side = 16
  val small = scale(side, side)
  val px = IntArray(side * side)
  small.getPixels(px, 0, side, 0, 0, side, side)
  if (small !== this) small.recycle()
  var lumSum = 0.0
  var alphaSum = 0.0
  for (p in px) {
    val a = (p ushr 24 and 0xFF) / 255.0
    if (a > 0.0) {
      val r = (p ushr 16 and 0xFF) / 255.0
      val g = (p ushr 8 and 0xFF) / 255.0
      val b = (p and 0xFF) / 255.0
      lumSum += (0.299 * r + 0.587 * g + 0.114 * b) * a
      alphaSum += a
    }
  }
  return alphaSum > 0.0 && lumSum / alphaSum > 0.6
}
