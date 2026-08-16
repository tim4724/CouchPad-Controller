package games.couchpad.controller.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.net.ServerSocket
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
 * Which box a room is on, as [url] declares it (`cpp`, §6). A URL is the only carrier,
 * and it always comes from the relay — a §8 advertisement carries just a room code — so
 * this reads the same way whatever route the room arrived by. Null unless a value we know
 * was declared: the vocabulary is fixed, so a display can never put its own text on a
 * card. Reading it off a URL is a point-in-time answer; holding on to it once a room has
 * answered is [RecentRoomStore]'s job.
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
) {
  val target: JoinOutcome.Success get() = JoinOutcome.Success(game, roomCode, joinUrl)

  /** `cpp` off [joinUrl]: which box the room is on, or null when it declared nothing. */
  val platform: String? get() = devicePlatform(joinUrl)
}

/** The home screen's room cards. */
data class HomeRooms(
  /**
   * The rejoin room as the LAN currently advertises it, or null when nothing on the
   * network is offering that room. It is the better source for BOTH halves of the
   * card's locator: its own name, and a join URL that came straight from the relay's
   * §6 template — the one place `cpp` is guaranteed to be declared.
   */
  val rejoinAdvert: NearbyRoom?,
  /** Every other advertised room, deduped and in a stable order. */
  val nearby: List<NearbyRoom>,
)

/**
 * Turns resolved rooms into the card list in ONE pass: collapses a room advertised twice
 * (a display on two interfaces) into one card, sorts for a stable order, and folds the
 * rejoin room's advertisement into the rejoin card.
 *
 * [rejoin] is the room that card is currently offering. Leaving a game does not close the
 * room, so the display keeps advertising it — without this the player lands on home
 * looking at two cards for the same room. The rejoin card wins, because it carries the
 * captured page title, and it inherits the advertisement it displaced: dropping the
 * duplicate and handing back what it knew are the same match, asked once.
 */
fun homeRooms(rooms: List<NearbyRoom>, rejoin: RecentRoom? = null): HomeRooms {
  // Keyed on ROOM CODE, scoped by GAME: codes are minted per relay (a game may run its
  // own, Game.relayProbeBase), so two games can independently mint the same code and
  // must not collapse into one card. An UNRESOLVED game (a launcher preview subdomain
  // matching no id) collapses every such deployment onto one synthetic id, which
  // discriminates nothing — fall back to the host there, which does.
  var rejoinAdvert: NearbyRoom? = null
  val best = LinkedHashMap<String, NearbyRoom>()
  for (room in rooms) {
    if (room.isSameRoomAs(rejoin)) {
      if (rejoinAdvert == null) rejoinAdvert = room
      continue
    }
    val scope = if (room.game.id == SYNTHETIC_GAME_ID) {
      runCatching { room.joinUrl.toUri().host }.getOrNull().orEmpty()
    } else {
      room.game.id
    }
    val key = if (room.roomCode.isBlank()) room.joinUrl else scope + "/" + room.roomCode
    best.putIfAbsent(key, room)
  }
  return HomeRooms(rejoinAdvert, best.values.sortedWith(compareBy({ it.label }, { it.joinUrl })))
}

/**
 * Resolves one advertised code against the relay — the same probe a typed code takes,
 * so scan, type and nearby-tap all land on one path. The relay's `url` is what loads
 * (re-validated against the manifest allow-list by [resolveLookups]) and is also where
 * `cpp` comes from, so the launcher never trusts anything the LAN said beyond the code.
 *
 * Null when the room is gone, unreachable, off the allow-list, or FULL — a card that
 * can't be joined is worse than no card, since tapping it costs a whole page load only
 * to bounce back on `game_full`.
 */
suspend fun resolveNearby(advert: NearbyAdvert, games: List<Game>): NearbyRoom? {
  if (!validRoomCode(advert.code)) return null
  // One probe round serves both the fullness check and the URL resolution — this runs
  // on every 10s poll tick per advertised room, so a second identical round would
  // double the relay traffic for nothing.
  val results = probeRelays(advert.code, games)
  val founds = results.filterIsInstance<RoomLookup.Found>()
  if (founds.isEmpty() || founds.any { it.isFull }) return null
  val hit = resolveLookups(results, games) as? JoinOutcome.Success ?: return null
  return NearbyRoom(
    // A relayed record's instance name is the relaying phone's own, not the TV's.
    label = if (advert.relayed) "" else advert.label,
    game = hit.game,
    roomCode = hit.roomCode,
    joinUrl = hit.joinUrl,
  )
}

// Room code is the reliable identity (the resolved URL can differ in query/fragment);
// the URL match is the fallback for a code-less join URL.
private fun NearbyRoom.isSameRoomAs(recent: RecentRoom?): Boolean {
  if (recent == null) return false
  if (roomCode.isNotBlank() && roomCode == recent.roomCode && game.id == recent.game.id) return true
  return joinUrl == recent.joinUrl
}

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

private const val ASK_PREFS = "cp_nearby_ask"
private const val ASKED_KEY = "asked"
private const val OPTED_IN_KEY = "opted_in"

/**
 * Has the player asked for nearby discovery? On API 37+ the permission grant IS the
 * memory (ungranted → the ask button, granted → discovery just runs). Below
 * enforcement there is no permission to remember with — [localNetworkPermissionGranted]
 * is unconditionally true — so a stored flag stands in, mirroring iOS's NearbyOptIn.
 * Without it every pre-37 device would browse and relay-advertise on the LAN from
 * launch, which §8 forbids ("the launcher asks only when the player asks for it").
 */
fun nearbyOptedIn(context: Context): Boolean =
  if (Build.VERSION.SDK_INT >= 37) {
    localNetworkPermissionGranted(context)
  } else {
    context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE).getBoolean(OPTED_IN_KEY, false)
  }

/** The pre-enforcement opt-in: no permission to ask for, so the tap is the grant. */
fun setNearbyOptedIn(context: Context) {
  context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE).edit { putBoolean(OPTED_IN_KEY, true) }
}

/**
 * Record that the local-network request has been shown at least once. Call it at the
 * launch site, not in the result callback — a request the system refuses to prompt for
 * still counts as asked.
 *
 * The flag exists only to disambiguate [localNetworkPermanentlyDenied]; it is NOT an
 * opt-in memory (the permission itself is that, see MainScreen).
 */
fun markLocalNetworkAsked(context: Context) {
  context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE).edit { putBoolean(ASKED_KEY, true) }
}

/**
 * Forget that we ever asked. Called whenever the permission is seen granted, because a
 * grant that is later revoked from Settings brings the request dialog back — the next
 * refusal has to start counting from zero, or the stale flag would read a perfectly
 * askable permission as locked.
 */
fun clearLocalNetworkAsked(context: Context) {
  context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE).edit { remove(ASKED_KEY) }
}

/**
 * The ask button has become a dead control: Android 11+ locks a permission after two
 * denials, and every later request then returns denied without ever showing a dialog.
 * Only the app's own settings page can undo that, so this is what decides whether the
 * slot offers a request or a route to Settings.
 *
 * `shouldShowRequestPermissionRationale` alone can't answer it — it is false both for
 * "never asked" and for "locked". The scanner tells them apart in memory because it
 * requests on entry; discovery is asked for by hand, so the fact that we asked has to
 * survive the process ([markLocalNetworkAsked]).
 */
fun localNetworkPermanentlyDenied(context: Context, activity: Activity?): Boolean {
  if (activity == null || localNetworkPermissionGranted(context)) return false
  if (!context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_KEY, false)) {
    return false
  }
  return !ActivityCompat.shouldShowRequestPermissionRationale(
    activity,
    Manifest.permission.ACCESS_LOCAL_NETWORK,
  )
}

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
 * NEVER prompts. It advertises only if the player has ALREADY opted into nearby
 * discovery ([nearbyOptedIn] — the permission grant on API 37+, the stored opt-in
 * below) — so nobody is ever asked for, or silently given, a capability that benefits
 * someone else. Not opted in simply means no relay.
 */
object NearbyAdvertiser {

  private var manager: NsdManager? = null
  private var registration: NsdManager.RegistrationListener? = null
  private var socket: ServerSocket? = null

  @Synchronized
  fun start(context: Context, roomCode: String) {
    if (registration != null || roomCode.isBlank()) return
    if (!nearbyOptedIn(context)) return
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
      // Async failure: we aren't advertising after all — release the socket and the
      // stored state instead of believing we are until the room ends.
      override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = failed(this)
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

  // Guarded on identity: a stale callback from a listener a later start() replaced
  // must not tear down the current registration.
  @Synchronized
  private fun failed(listener: NsdManager.RegistrationListener) {
    if (registration === listener) stop()
  }
}

/**
 * Whether a network mDNS could possibly traverse — Wi-Fi or Ethernet — is up. On cellular
 * only, browsing "runs" and finds nothing, so the home slot's "Searching…"/"No rooms
 * found" would claim a search that never had anywhere to look; this is what swaps that
 * claim for the join-the-Wi-Fi hint. It only ever picks the slot's wording — discovery
 * itself keeps running: a phone hosting its own hotspot reports no Wi-Fi transport here,
 * yet rooms on that hotspot still resolve, and a found room simply replaces the hint.
 */
fun lanAvailable(context: Context): Flow<Boolean> = callbackFlow {
  val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
    as? ConnectivityManager
  if (cm == null) {
    trySend(true)
    awaitClose {}
    return@callbackFlow
  }
  // The callback replays onAvailable for already-up networks, but only asynchronously —
  // seed from the active network so a cellular-only launch doesn't sit on the optimistic
  // default until something changes.
  val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
  trySend(
    active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
      active?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
  )
  // Track the SET of matching networks: Wi-Fi and Ethernet can be up together, and
  // losing one of two must not read as losing the LAN.
  val lans = mutableSetOf<Network>()
  val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      synchronized(lans) { lans.add(network) }
      trySend(true)
    }

    override fun onLost(network: Network) {
      trySend(synchronized(lans) { lans.remove(network); lans.isNotEmpty() })
    }
  }
  val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
    // The default request also demands declared INTERNET, which an internet-less
    // hotspot/LAN — a network this feature explicitly serves — may never claim; the
    // question here is "is a LAN up", not "can it reach the WAN". NOT_VPN stays: a
    // VPN network re-declares its underlying Wi-Fi transport and would double-count.
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()
  runCatching { cm.registerNetworkCallback(request, callback) }
    .onFailure { trySend(true) } // can't watch — stay optimistic, never hide behind the hint
  awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
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
      val removed = synchronized(lock) {
        // Purge the resolve queue too, or a lost-while-queued service resolves later
        // and re-enters `found` as a ghost that never goes away.
        pending.removeAll { it.serviceName == info.serviceName }
        found.remove(info.serviceName) != null
      }
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
