package games.couchpad.controller.data

/**
 * The room this phone is in or just left, with everything the home rejoin card needs.
 * [title] is captured from the controller page mid-session, so it's null until then; the
 * card's glyph comes from the manifest `icon`, not the page. [platform] is which box the room is on, off the
 * first URL that declared it — the join URL at [RecentRoomStore.remember], else the
 * relay's template at [RecentRoomStore.putPlatform].
 */
class RecentRoom(
  val game: Game,
  val joinUrl: String,
  val roomCode: String,
  val title: String?,
  val platform: String?,
)

/**
 * Single-slot, in-memory memory of the current room. Deliberately not persisted:
 * rejoin is a same-session convenience, so the slot dies with the process and ages out
 * [TTL_MS] after the player LEFT the room — a fresh launch simply shows no card.
 * [remember] sets the base at join, platform included when the join URL declares one;
 * the title arrives later, captured in-game, and so does the platform when that URL
 * named no box.
 */
object RecentRoomStore {
  private const val TTL_MS = 20L * 60 * 1000

  private var game: Game? = null
  private var joinUrl: String = ""
  private var roomCode: String = ""
  private var title: String? = null
  private var platform: String? = null
  private var savedAt: Long = 0

  /**
   * True while the game host is up. The slot must not age out under a player who is
   * still IN the room — a session outlasting [TTL_MS] would otherwise clear itself from
   * under the home poll and take the rejoin card (and the room's §8 advertisement, which
   * reads the slot) with it. [leave] restarts the clock, so the TTL measures time since
   * the player left.
   */
  private var inRoom = false

  @Synchronized
  fun remember(game: Game, joinUrl: String, roomCode: String) {
    this.game = game
    this.joinUrl = joinUrl
    this.roomCode = roomCode
    this.title = null
    this.platform = devicePlatform(joinUrl)
    this.savedAt = System.currentTimeMillis()
  }

  /**
   * Learns the room's `cpp` off [templateUrl] — the relay's §6 template, the one URL
   * guaranteed to declare it (the join URL the player arrived on is not: a display may
   * keep its QR clean). Sticky, because every other carrier is transient — a display's
   * advertisement dies with the display, and a browser room is advertised only by the
   * phones in it, so it stops naming its box moments after this one leaves.
   */
  @Synchronized
  fun putPlatform(templateUrl: String?) {
    if (game == null || platform != null) return
    platform = templateUrl?.let(::devicePlatform)
  }

  /** Sanitizes [raw] (trim, collapse whitespace, cap length) and stores it as the
   *  active room's title — unless there's no active room or nothing survives cleaning. */
  @Synchronized
  fun putTitle(raw: String) {
    if (game == null) return
    val clean = raw.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE_LEN)
    if (clean.isNotEmpty()) title = clean
  }

  /** Called by the game host for as long as it is on screen (see [inRoom]). */
  @Synchronized
  fun enter() {
    inRoom = true
  }

  /** The player is out of the room: age it from here. */
  @Synchronized
  fun leave() {
    inRoom = false
    savedAt = System.currentTimeMillis()
  }

  /** The current room while still fresh, else null (clearing an aged-out slot). */
  @Synchronized
  fun current(): RecentRoom? {
    val g = game ?: return null
    if (!inRoom && System.currentTimeMillis() - savedAt > TTL_MS) {
      clear()
      return null
    }
    return RecentRoom(g, joinUrl, roomCode, title, platform)
  }

  @Synchronized
  fun clear() {
    game = null
    joinUrl = ""
    roomCode = ""
    title = null
    platform = null
    savedAt = 0
  }

  private const val MAX_TITLE_LEN = 64
}
