package games.couchpad.controller.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.net.ServerSocket
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Contract §8: the DNS-SD service a native display app (tvOS / Android TV) advertises
 * its room under. NsdManager's convention includes the trailing dot; iOS's
 * Network.framework omits it — same wire protocol either way.
 */
private const val NEARBY_SERVICE_TYPE = "_couchpad._tcp."

/** Instance names are display-declared; cap so one can't blow out the card. */
private const val MAX_ADVERT_LABEL_LENGTH = 24

/** TXT `c` — the room code, the advertisement's payload (§8). */
private const val CODE_KEY = "c"

/**
 * TXT `cpr` — published by a controller relaying a room it is in, not by the display.
 * Its instance name is therefore NOT a room label (the phone may never have learned one),
 * so the launcher must not present it as a location.
 */
private const val RELAYED_KEY = "cpr"

/** `cpp` — the display's platform. A fixed vocabulary, so the launcher owns the wording. */
const val PLATFORM_TVOS = "tvos"
const val PLATFORM_ANDROID_TV = "androidtv"

/** A browser-based display: no mDNS advertisement possible, only a §6 template. */
const val PLATFORM_WEB = "web"

/**
 * Which box a room is on, as its join URL declares it (`cpp`, §6). The URL is the only
 * carrier, and it always comes from the relay — a §8 advertisement carries just a room
 * code — so this reads the same way whatever route the room arrived by, and survives
 * into the rejoin card once the advertisement is gone. Null unless a value we know was
 * declared: the vocabulary is fixed, so a display can never put its own text on a card.
 */
fun devicePlatform(url: String): String? {
  val uri = runCatching { url.toUri() }.getOrNull() ?: return null
  val raw = runCatching { uri.getQueryParameter("cpp") }.getOrNull()
  return raw?.trim()?.lowercase()
    ?.takeIf { it == PLATFORM_TVOS || it == PLATFORM_ANDROID_TV || it == PLATFORM_WEB }
}

/**
 * One `_couchpad._tcp` advertisement (§8): a room label and the room's code, nothing
 * else. The code is the whole payload — everything the card needs (join URL, `cpp`,
 * liveness, occupancy) comes from resolving it against the relay, which is authoritative
 * where the LAN is not. A record can therefore name a room but never propose an origin.
 */
data class NearbyAdvert(
  /** The DNS-SD instance name: the display's own label ("Living Room"). */
  val label: String,
  /** TXT `c` — the room code. */
  val code: String,
  /** TXT `cpr`: relayed by a controller in the room, so [label] is meaningless. */
  val relayed: Boolean,
)

/**
 * One advertisement per room code, preferring a display's own record over a relayed one —
 * the display's instance name is the room's label, a relaying phone has none to give.
 *
 * Collapsing BEFORE resolution is what keeps relaying cheap: the payload is a room code,
 * so two records carrying the same code are the same room by definition and one probe
 * answers for both. Without this, every phone in a room would cost its own relay probe on
 * every poll tick.
 */
fun distinctAdverts(adverts: List<NearbyAdvert>): List<NearbyAdvert> =
  adverts.sortedBy { it.relayed }.distinctBy { it.code }

/** An advertisement whose code resolved, through the relay, to a real join target. */
data class NearbyRoom(
  val label: String,
  val game: Game,
  val roomCode: String,
  val joinUrl: String,
  /** `cpp` off [joinUrl]: which box the room is on, or null when it declared nothing. */
  val platform: String?,
) {
  val target: JoinOutcome.Success get() = JoinOutcome.Success(game, roomCode, joinUrl)
}

/**
 * Turns resolved rooms into the card list: drops anything the rejoin card is already
 * offering, collapses a room advertised twice (a display on two interfaces) into one
 * card, and sorts for a stable order.
 *
 * [exclude] is the room the rejoin card is currently offering. Leaving a game does not
 * close the room, so the display keeps advertising it — without this the player lands
 * on home looking at two cards for the same room. The rejoin card wins: it carries the
 * captured page title.
 */
fun nearbyRooms(rooms: List<NearbyRoom>, exclude: RecentRoom? = null): List<NearbyRoom> {
  // Keyed on ROOM CODE, scoped by GAME: codes are minted per relay (a game may run its
  // own, Game.relayProbeBase), so two games can independently mint the same code and
  // must not collapse into one card. An UNRESOLVED game (a launcher preview subdomain
  // matching no id) collapses every such deployment onto one synthetic id, which
  // discriminates nothing — fall back to the host there, which does.
  val best = LinkedHashMap<String, NearbyRoom>()
  for (room in rooms) {
    if (room.isSameRoomAs(exclude)) continue
    val scope = if (room.game.id == SYNTHETIC_GAME_ID) {
      runCatching { room.joinUrl.toUri().host }.getOrNull().orEmpty()
    } else {
      room.game.id
    }
    val key = if (room.roomCode.isBlank()) room.joinUrl else scope + "/" + room.roomCode
    best.putIfAbsent(key, room)
  }
  return best.values.sortedWith(compareBy({ it.label }, { it.joinUrl }))
}

/**
 * Resolves one advertised code against the relay — the same probe a typed code takes,
 * so scan, type and nearby-tap all land on one path. The relay's `url` is what loads
 * (re-validated against the manifest allow-list by [resolveTypedCode]) and is also where
 * `cpp` comes from, so the launcher never trusts anything the LAN said beyond the code.
 *
 * Null when the room is gone, unreachable, off the allow-list, or FULL — a card that
 * can't be joined is worse than no card, since tapping it costs a whole page load only
 * to bounce back on `game_full`.
 */
suspend fun resolveNearby(advert: NearbyAdvert, games: List<Game>): NearbyRoom? {
  if (!validRoomCode(advert.code)) return null
  val sole = games.singleOrNull { it.isLive }
  val bases = buildList {
    sole?.relayProbeBase?.let(::add)
    if (RELAY_BASE !in this) add(RELAY_BASE)
  }
  val founds = coroutineScope {
    bases.map { async { RoomDirectory.lookup(advert.code, it) } }.awaitAll()
  }.filterIsInstance<RoomLookup.Found>()
  if (founds.isEmpty() || founds.any { it.isFull }) return null
  val hit = resolveTypedCode(advert.code, games) as? JoinOutcome.Success ?: return null
  return NearbyRoom(
    // A relayed record's instance name is the relaying phone's own, not the TV's.
    label = if (advert.relayed) "" else advert.label,
    game = hit.game,
    roomCode = hit.roomCode,
    joinUrl = hit.joinUrl,
    platform = devicePlatform(hit.joinUrl),
  )
}

// Room code is the reliable identity (the resolved URL can differ in query/fragment);
// the URL match is the fallback for a code-less join URL.
private fun NearbyRoom.isSameRoomAs(recent: RecentRoom?): Boolean {
  if (recent == null) return false
  if (roomCode.isNotBlank() && roomCode == recent.roomCode && game.id == recent.game.id) return true
  return joinUrl == recent.joinUrl
}

private fun validRoomCode(code: String): Boolean =
  code.length == ROOM_CODE_LENGTH && code.all { it in BASE58 }

/**
 * Contract §8 discovery needs `ACCESS_LOCAL_NETWORK` where Local Network Protections are
 * ENFORCED — Android 17 (API 37) for an app targeting 37 — because without the grant mDNS
 * is silently blocked.
 *
 * The cutoff tracks ENFORCEMENT, not the permission's existence. API 36 defines it but
 * only enforces it opt-in, and a stock Android 16 device lists no runtime entry for it at
 * all, so checkSelfPermission answers DENIED there (verified on a Pixel 7 Pro). Gating on
 * 36 would put an ask card in front of a device that needs nothing, and hand it a request
 * the system may refuse to prompt for — a dead control, discovery off for good.
 */
fun localNetworkPermissionGranted(context: Context): Boolean =
  Build.VERSION.SDK_INT < 37 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
    PackageManager.PERMISSION_GRANTED

/**
 * Re-advertises the room this phone is in, so the next player can tap instead of scan.
 * It is what makes discovery work at all for a browser-based display, which cannot
 * advertise for itself, and it makes discovery survive a native display whose own record
 * is missing or malformed.
 *
 * Publishes the room CODE and nothing else — the same payload a display publishes, so a
 * relayed record proposes no origin and grants a relaying phone no more trust than the
 * display has. `cpr=1` marks the instance name as not-a-room-label.
 *
 * NEVER prompts. It advertises only if the local-network permission is ALREADY held —
 * granted by this player for their own discovery — so nobody is ever asked for a
 * capability that benefits someone else. Ungranted simply means no relay.
 */
object NearbyAdvertiser {

  private var manager: NsdManager? = null
  private var registration: NsdManager.RegistrationListener? = null
  private var socket: ServerSocket? = null

  @Synchronized
  fun start(context: Context, roomCode: String) {
    if (registration != null || roomCode.isBlank()) return
    if (!localNetworkPermissionGranted(context)) return
    val app = context.applicationContext
    val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
    // NsdManager advertises a port, so bind a real (unused) one. Nothing ever dials it —
    // the record exists to carry TXT, exactly as in the display case.
    val server = runCatching { ServerSocket(0) }.getOrNull() ?: return
    val info = NsdServiceInfo().apply {
      // Our own name, never the device's — no phone name reaches the network.
      serviceName = "CouchPad-" + roomCode
      serviceType = NEARBY_SERVICE_TYPE
      port = server.localPort
      setAttribute(CODE_KEY, roomCode)
      setAttribute(RELAYED_KEY, "1")
    }
    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) {}
      override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
      override fun onServiceUnregistered(info: NsdServiceInfo) {}
      override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }
    val ok = runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
    if (!ok) {
      runCatching { server.close() }
      return
    }
    manager = nsd
    registration = listener
    socket = server
  }

  @Synchronized
  fun stop() {
    registration?.let { reg -> runCatching { manager?.unregisterService(reg) } }
    runCatching { socket?.close() }
    manager = null
    registration = null
    socket = null
  }
}

/**
 * Browses the LAN for displays advertising a room (contract §8). The launcher only ever
 * reads the TXT record — it never dials the advertised port. Gameplay still goes to the
 * relay over the internet, so this is purely a local side channel for a join URL.
 *
 * Collect under a STARTED lifecycle: cancelling the collection stops discovery and drops
 * the multicast lock.
 */
fun nearbyAdverts(context: Context): Flow<List<NearbyAdvert>> = callbackFlow {
  val appContext = context.applicationContext
  val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
  if (nsd == null) {
    awaitClose {}
    return@callbackFlow
  }

  // Several OEMs drop multicast to the app unless a lock is held, and discovery then
  // silently finds nothing. Best-effort: a failure here just means fewer rooms.
  val multicast = runCatching {
    (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
      .createMulticastLock("couchpad-nsd")
      .apply { setReferenceCounted(false); acquire() }
  }.getOrNull()

  // NsdManager delivers every callback on its own internal thread, and the legacy
  // resolveService handles exactly ONE resolve at a time (a concurrent one comes back
  // FAILURE_ALREADY_ACTIVE). So: one lock over the whole state machine, and found
  // services queue for a strictly serial resolve.
  val lock = Any()
  val found = LinkedHashMap<String, NearbyAdvert>()
  val pending = ArrayDeque<NsdServiceInfo>()
  var resolving = false

  fun emit() {
    trySend(synchronized(lock) { found.values.toList() })
  }

  fun resolveNext() {
    val next = synchronized(lock) {
      if (resolving) return
      val head = pending.removeFirstOrNull() ?: return
      resolving = true
      head
    }
    val onDone = {
      synchronized(lock) { resolving = false }
      resolveNext()
    }
    runCatching {
      nsd.resolveService(
        next,
        object : NsdManager.ResolveListener {
          override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = onDone()

          override fun onServiceResolved(info: NsdServiceInfo) {
            val advert = info.toAdvert()
            if (advert != null) {
              synchronized(lock) { found[info.serviceName] = advert }
              emit()
            }
            onDone()
          }
        },
      )
    }.onFailure { onDone() }
  }

  val listener = object : NsdManager.DiscoveryListener {
    override fun onDiscoveryStarted(serviceType: String) {}
    override fun onDiscoveryStopped(serviceType: String) {}
    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

    // Nothing to browse (no Wi-Fi, mDNS unavailable) — end the flow; the join card's
    // scan/code path is the fallback.
    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
      close()
    }

    override fun onServiceFound(info: NsdServiceInfo) {
      synchronized(lock) { pending.addLast(info) }
      resolveNext()
    }

    override fun onServiceLost(info: NsdServiceInfo) {
      val removed = synchronized(lock) { found.remove(info.serviceName) != null }
      if (removed) emit()
    }
  }

  trySend(emptyList())
  runCatching { nsd.discoverServices(NEARBY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    .onFailure { close(it) }

  awaitClose {
    runCatching { nsd.stopServiceDiscovery(listener) }
    runCatching { multicast?.release() }
  }
}

/**
 * TXT → advert, or null when the record carries no usable room code. The code is the
 * whole gate: the record has no version field, and a code that no relay knows is
 * dropped at resolution.
 */
private fun NsdServiceInfo.toAdvert(): NearbyAdvert? {
  val attrs = attributes ?: return null
  val code = attrs[CODE_KEY].decodeUtf8()?.trim()
  // A sanity cap on hostile input, not the format check — resolveNearby applies the real
  // BASE58/length rule. Any advert field gets the same bound; none is ever this long.
  if (code.isNullOrBlank() || code.length > MAX_ADVERT_LABEL_LENGTH) return null
  val relayed = attrs[RELAYED_KEY].decodeUtf8() == "1"
  return NearbyAdvert(sanitizeLabel(serviceName.orEmpty()), code, relayed)
}

private fun ByteArray?.decodeUtf8(): String? =
  this?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }

private fun sanitizeLabel(raw: String): String =
  raw.trim().replace(Regex("\\s+"), " ").take(MAX_ADVERT_LABEL_LENGTH)
