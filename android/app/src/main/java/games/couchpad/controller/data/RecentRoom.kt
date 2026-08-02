package games.couchpad.controller.data

/**
 * The room this phone is in or just left, with everything the home rejoin card needs.
 * [joinUrl] omits cpName — re-wrapped with the current name at rejoin. [title] is captured
 * from the controller page mid-session, so it's null until then; the card's glyph comes
 * from the manifest `icon`, not the page.
 */
class RecentRoom(
  val game: Game,
  val joinUrl: String,
  val roomCode: String,
  val title: String?,
)

/**
 * Single-slot, in-memory memory of the current room. Deliberately not persisted:
 * rejoin is a same-session convenience, so the slot dies with the process and ages out
 * after [TTL_MS] — a fresh launch simply shows no card. [remember] sets the base at
 * join; the title arrives later, captured in-game.
 */
object RecentRoomStore {
  private const val TTL_MS = 20L * 60 * 1000

  private var game: Game? = null
  private var joinUrl: String = ""
  private var roomCode: String = ""
  private var title: String? = null
  private var savedAt: Long = 0

  @Synchronized
  fun remember(game: Game, joinUrl: String, roomCode: String) {
    this.game = game
    this.joinUrl = joinUrl
    this.roomCode = roomCode
    this.title = null
    this.savedAt = System.currentTimeMillis()
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
    return RecentRoom(g, joinUrl, roomCode, title)
  }

  @Synchronized
  fun clear() {
    game = null
    joinUrl = ""
    roomCode = ""
    title = null
    savedAt = 0
  }

  private const val MAX_TITLE_LEN = 64
}
