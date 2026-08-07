package games.couchpad.controller.ui.main

import android.Manifest
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import games.couchpad.controller.data.Game
import games.couchpad.controller.data.ManifestStore
import games.couchpad.controller.data.JoinOutcome
import games.couchpad.controller.data.JoinResolver
import games.couchpad.controller.data.LAUNCHER_HOST
import games.couchpad.controller.data.NearbyAdvert
import games.couchpad.controller.data.NearbyRoom
import games.couchpad.controller.data.distinctAdverts
import games.couchpad.controller.data.nearbyAdverts
import games.couchpad.controller.data.homeRooms
import games.couchpad.controller.data.resolveNearby
import games.couchpad.controller.data.localNetworkPermissionGranted
import games.couchpad.controller.data.Profile
import games.couchpad.controller.data.ProfileStore
import games.couchpad.controller.data.RecentRoom
import games.couchpad.controller.data.RecentRoomStore
import games.couchpad.controller.data.SAMPLE_ROOM_CODE
import games.couchpad.controller.data.ROOM_POLL_MS
import games.couchpad.controller.data.RoomDirectory
import games.couchpad.controller.data.RoomLookup
import games.couchpad.controller.data.resolveTypedCode
import games.couchpad.controller.data.withProfile
import androidx.compose.ui.tooling.preview.Preview
import games.couchpad.controller.theme.CouchPadTheme
import games.couchpad.controller.ui.preview.CardSamples
import games.couchpad.controller.ui.legal.LegalLinks
import games.couchpad.controller.R
import games.couchpad.controller.ui.components.GameArt
import games.couchpad.controller.ui.components.GameIcon
import games.couchpad.controller.ui.components.deviceName
import games.couchpad.controller.ui.components.JoinButtons
import games.couchpad.controller.ui.components.annotatedHostLine
import games.couchpad.controller.ui.components.MirrorHostSystemBars
import games.couchpad.controller.ui.components.PlayerChip
import games.couchpad.controller.ui.components.PosterStatusChip
import games.couchpad.controller.ui.components.stableScreenInsets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  deepLink: String? = null,
  onDeepLinkConsumed: () -> Unit = {},
  onJoin: (joinUrl: String, title: String, allowedHosts: List<String>) -> Unit = { _, _, _ -> },
  onOpenLegalDoc: (url: String) -> Unit = {},
  onOpenAbout: () -> Unit = {},
  // Set when a game reports it ended and the host popped back here — shown as a banner
  // in the rejoin slot. Cleared on dismiss (auto after a few seconds, or tap/swipe).
  gameEndBanner: String? = null,
  onDismissGameEndBanner: () -> Unit = {},
) {
  val context = LocalContext.current
  // Config-aware resources for string lookups in callbacks (context.getString on
  // LocalContext.current is flagged by lint as not tracking config changes).
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val lifecycleOwner = LocalLifecycleOwner.current
  // Seeded synchronously (cached fetch, else bundled); the once-per-launch
  // refresh below updates it live when the served manifest differs.
  val games by ManifestStore.games(context).collectAsState()
  var profile by remember { mutableStateOf(ProfileStore.load(context)) }
  var showProfile by remember { mutableStateOf(false) }
  var afterName by remember { mutableStateOf<AfterName?>(null) }
  var showScanner by remember { mutableStateOf(false) }
  var showCodeEntry by remember { mutableStateOf(false) }
  var codeLoading by remember { mutableStateOf(false) }
  var codeError by remember { mutableStateOf<String?>(null) }
  var rejoin by remember { mutableStateOf<RecentRoom?>(null) }
  var infoGame by remember { mutableStateOf<Game?>(null) }
  // Rooms advertised on the LAN by native display apps (contract §8). Resolved against
  // `games` on every change, so a manifest refresh re-admits (or drops) an advert
  // without restarting discovery.
  var adverts by remember { mutableStateOf<List<NearbyAdvert>>(emptyList()) }
  // The permission IS the opt-in memory: ungranted → the "Show rooms nearby" button,
  // granted → discovery runs on every later launch and the rooms are simply there. No
  // prompt at first launch; the user asks for it once.
  var canDiscover by remember { mutableStateOf(localNetworkPermissionGranted(context)) }
  val localNetworkPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted -> canDiscover = granted }
  // An advertised code becomes a card only once the relay has resolved it — that call is
  // what supplies the join URL, `cpp` and the occupancy check. Re-checked on the same
  // cadence as the rejoin card: both promise "you can enter this", so both have to notice
  // when the room dies or fills. Lifecycle-gated — no polling while backgrounded.
  val resolved = remember { mutableStateMapOf<String, NearbyRoom>() }
  LaunchedEffect(adverts, games) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        // One probe per ROOM, not per record: a room announced by its display and by
        // every phone in it is still one code.
        val distinct = distinctAdverts(adverts)
        resolved.keys.retainAll(distinct.map { it.code }.toSet())
        coroutineScope {
          distinct.map { advert ->
            async {
              val room = resolveNearby(advert, games)
              if (room == null) resolved.remove(advert.code) else resolved[advert.code] = room
            }
          }.awaitAll()
        }
        delay(ROOM_POLL_MS)
      }
    }
  }
  // The rejoin room's own advertisement folds INTO the rejoin card, name and all, rather
  // than standing up a second card for the room the player is already being offered.
  val home = remember(resolved.toMap(), rejoin) { homeRooms(resolved.values.toList(), rejoin) }
  val nearby = home.nearby
  // mDNS discovery never "completes" — it just keeps listening — so a spinner would spin
  // forever with the TV off. Settle to a plain "not found" after a grace period.
  var searchSettled by remember { mutableStateOf(false) }
  LaunchedEffect(canDiscover) {
    searchSettled = false
    if (canDiscover) {
      delay(8_000)
      searchSettled = true
    }
  }

  // Every successful join funnels through here: remember the room for one-tap
  // rejoin and open the game host. Closes the scanner too, so leaving the game
  // lands back on home, not on a live camera.
  fun launchJoin(target: JoinOutcome.Success, p: Profile) {
    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    showScanner = false
    RecentRoomStore.remember(target.game, target.joinUrl, target.roomCode)
    onJoin(withProfile(target.joinUrl, p), target.game.name, target.game.hosts)
  }

  // Every failure surface — a bad link, a dead room — pairs the toast with a
  // rejection buzz, mirroring iOS's error haptic.
  fun fail(messageRes: Int) {
    haptics.performHapticFeedback(HapticFeedbackType.Reject)
    Toast.makeText(context, resources.getString(messageRes), Toast.LENGTH_SHORT).show()
  }

  fun perform(action: AfterName, p: Profile) {
    when (action) {
      AfterName.Scan -> showScanner = true
      AfterName.EnterCode -> { codeError = null; showCodeEntry = true }
      is AfterName.Join -> launchJoin(action.target, p)
    }
  }

  // Gate any join behind a non-blank name: prompt first if missing, else act now.
  fun requireName(action: AfterName) {
    if (!profile.isSet) {
      afterName = action
      showProfile = true
    } else {
      perform(action, profile)
    }
  }

  fun resolveAndJoin(raw: String) {
    when (val r = JoinResolver.resolve(raw, games)) {
      is JoinOutcome.Success -> requireName(AfterName.Join(r))
      is JoinOutcome.Failure -> fail(r.messageRes)
    }
  }

  // Pull the served manifest once per launch (ManifestStore guards re-entry, so
  // nav pop-backs recomposing this screen don't refetch).
  LaunchedEffect(Unit) { ManifestStore.refresh(context) }

  // Browse for nearby rooms only while home is STARTED and the permission is held —
  // cancelling the collection stops discovery and drops the multicast lock, so nothing
  // runs in the background or behind the game host.
  LaunchedEffect(canDiscover) {
    if (!canDiscover) {
      adverts = emptyList()
      return@LaunchedEffect
    }
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      try {
        nearbyAdverts(context).collect { adverts = it }
      } finally {
        adverts = emptyList()
      }
    }
  }

  // An incoming App Link: a legal-page link opens the in-app doc viewer; anything
  // else is a join and goes through the same name gate as a scan. The URL keeps its
  // locale segment (/en/privacy vs /privacy), so the viewer loads the right variant.
  LaunchedEffect(deepLink) {
    if (deepLink != null) {
      if (LegalLinks.isPrivacy(deepLink) || LegalLinks.isImprint(deepLink)) {
        onOpenLegalDoc(deepLink)
      } else {
        resolveAndJoin(deepLink)
      }
      onDeepLinkConsumed()
    }
  }

  // Offer one-tap rejoin while the last-joined room is still alive: keep probing
  // the game's relay so the card clears itself when the room dies. Lifecycle-gated —
  // no polling while backgrounded.
  LaunchedEffect(Unit) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      // Re-read the saved room EVERY iteration — never capture it once. A game that
      // ended with room_not_found clears the store from under this still-running poll;
      // the next tick must honor that and drop the card, not re-show the dead room off
      // a stale captured value + a relay record that hasn't 404'd yet.
      while (true) {
        val recent = RecentRoomStore.current()
        if (recent == null) {
          // No saved room (never joined, aged out, or cleared on a room_not_found end).
          rejoin = null
          return@repeatOnLifecycle
        }
        if (recent.roomCode.isBlank()) {
          // No room code (the scanned URL didn't surface one) → we can't liveness-poll,
          // so surface the card unverified. A dead room is handled by gameEnded.
          rejoin = recent
          return@repeatOnLifecycle
        }
        when (val lookup = RoomDirectory.lookup(recent.roomCode, recent.game.roomRelayBase)) {
          // Offered even when the room reads FULL. One of those slots is very likely this
          // player's own, held for them by the relay, which takes a stored clientId back
          // into it — the game only treats full as fatal for a FRESH joiner. Hiding the
          // card here locks someone out of the room they were just in; a rejoin that
          // genuinely bounces costs one page load and lands on the `game_full` banner.
          //
          // The probe also carries the §6 template, so the room names its box here even
          // when nothing on the LAN is advertising it — re-read the slot to pick that up.
          is RoomLookup.Found -> {
            RecentRoomStore.putPlatform(lookup.url)
            rejoin = RecentRoomStore.current() ?: recent
          }
          RoomLookup.NotFound -> {
            RecentRoomStore.clear()
            rejoin = null
            return@repeatOnLifecycle
          }
          RoomLookup.Error -> {} // transient — keep whatever we showed last
        }
        delay(ROOM_POLL_MS)
      }
    }
  }

  // A just-ended game (banner appeared) may have cleared the saved room — a
  // room_not_found end drops it. Reflect the store the instant the banner lands so
  // the rejoin card clears immediately, rather than lingering up to a poll tick (or
  // being re-shown by a stale relay 'Found' while the record catches up).
  LaunchedEffect(gameEndBanner) {
    if (gameEndBanner != null) rejoin = RecentRoomStore.current()
  }

  // Games scroll the full screen; the join card floats over the list at the bottom.
  // The trailing Spacer, sized off the card's measured height, keeps the last
  // poster reachable. The scanner overlay sits in the same root Box but OUTSIDE
  // the inset padding — the camera runs edge to edge and pads its own controls.
  var joinCardHeightPx by remember { mutableStateOf(0) }
  Box(modifier.fillMaxSize()) {
    // Only the horizontal (cutout) inset pads the container. The status bar and nav
    // bar are applied INSIDE the scroll (top spacer + card bottom inset) so the
    // catalog scrolls edge to edge UNDER the transparent bars instead of being
    // clipped in a box below them.
    Box(
      Modifier
        .fillMaxSize()
        .windowInsetsPadding(stableScreenInsets.only(WindowInsetsSides.Horizontal)),
    ) {
      Column(
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
      ) {
        // Clears the status bar so the header starts below it, but scrolls away with
        // the content — posters slide under the transparent bar, not into a hard cut.
        Spacer(Modifier.windowInsetsTopHeight(stableScreenInsets))
        // Sits OUTSIDE the 16dp content margin (owns its own padding, like the
        // in-game LeaveBar) so the title and name chip align across screens.
        HomeTopBar(profile = profile, onEditProfile = { showProfile = true }, onOpenAbout = onOpenAbout)
        // One rhythm for the whole stack: the banner, every room card and every poster
        // are cards in a single list, so they all sit 12dp apart. The one real break is
        // rooms-you-can-enter → the catalog, which GamesSection pads for.
        Column(
          Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // The game-end notice sits at the top of the content, above the rejoin card
          // (which is only present when the room is still alive). A high-contrast strip
          // rather than a bottom snackbar — the player was returned here, so the notice
          // needs to land where their eye already is.
          GameEndBanner(message = gameEndBanner, onDismiss = onDismissGameEndBanner)
          // A relay-confirmed room springs the rejoin card in; when the room dies it
          // springs back out. Retain the last target so the exit animation still has
          // content to render after `rejoin` clears (mirrors iOS's scale+fade).
          var lastRejoin by remember { mutableStateOf<RecentRoom?>(null) }
          LaunchedEffect(rejoin) { rejoin?.let { lastRejoin = it } }
          AnimatedVisibility(
            visible = rejoin != null,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
          ) {
            lastRejoin?.let { room ->
              RejoinCard(room, home.rejoinAdvert) { resolveAndJoin(room.joinUrl) }
            }
          }
          // "Searching…" / "No rooms found" are claims about having looked and come up
          // empty — false while a room card is already on screen, rejoin included. The
          // ask is an offer rather than a claim, so it stays regardless: hiding it behind
          // a rejoin card would make discovery invisible for the rest of the session.
          // (No denied state here: on Android a refusal simply leaves the permission
          // ungranted, so the slot falls back to the ask on its own.)
          if (!canDiscover || (nearby.isEmpty() && rejoin == null)) {
            NearbyStatusCard(
              granted = canDiscover,
              settled = searchSettled,
              onAsk = { localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
            )
          }
          // Same retain-the-last-value trick as the rejoin card, so the exit
          // animation still has content to render after the list empties.
          var lastNearby by remember { mutableStateOf<List<NearbyRoom>>(emptyList()) }
          LaunchedEffect(nearby) { if (nearby.isNotEmpty()) lastNearby = nearby }
          AnimatedVisibility(
            visible = nearby.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              lastNearby.forEach { room ->
                NearbyCard(room) { requireName(AfterName.Join(room.target)) }
              }
            }
          }
          GamesSection(games, Modifier.padding(top = 8.dp), onOpen = { infoGame = it })
          Spacer(Modifier.height(with(LocalDensity.current) { joinCardHeightPx.toDp() } + 12.dp))
        }
      }
      // Nav-area protection, the bottom counterpart of the status-bar strip:
      // posters dissolve into the background before they reach the gesture zone,
      // instead of ending in a hard cut under the pill. Drawn behind the (opaque)
      // join card, so only the band below and beside the card actually shows.
      val bottomInset = with(LocalDensity.current) { stableScreenInsets.getBottom(this).toDp() }
      val fadeBase = MaterialTheme.colorScheme.background
      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height(bottomInset + 56.dp)
          // Fade from the background's own hue at zero alpha (not transparent
          // black) so the mid-ramp doesn't darken light-theme posters.
          .background(Brush.verticalGradient(0f to fadeBase.copy(alpha = 0f), 1f to fadeBase)),
      )
      JoinCard(
        host = games.firstOrNull { it.isLive }?.displayHost ?: LAUNCHER_HOST,
        onScan = { requireName(AfterName.Scan) },
        onEnterCode = { requireName(AfterName.EnterCode) },
        modifier = Modifier
          .align(Alignment.BottomCenter)
          // Measured OUTSIDE the margins + nav-bar inset so the spacer clears the
          // whole floating card.
          .onGloballyPositioned { joinCardHeightPx = it.size.height }
          // The container no longer reserves the nav bar; lift the card above it here.
          .windowInsetsPadding(stableScreenInsets.only(WindowInsetsSides.Bottom))
          .padding(horizontal = 12.dp)
          .padding(bottom = 12.dp),
      )
    }
    // Status-bar protection: the window background at 50% alpha, one status-bar tall.
    // At rest it sits over the same background — a no-op, invisible. When a poster
    // scrolls under the bar it tints that strip back toward the background, keeping
    // the theme-colored status icons legible. No icon flipping, no poster-color
    // assumption. Sits above the catalog but below the full-screen scanner overlay.
    Box(
      Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .windowInsetsTopHeight(stableScreenInsets)
        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f)),
    )
    if (showScanner) {
      ScanScreen(
        games = games,
        onJoin = { launchJoin(it, profile) },
        // A scanned legal-page QR: close the camera and open the doc viewer, with
        // the same confirm haptic as a successful join scan.
        onOpenLegalDoc = { url ->
          haptics.performHapticFeedback(HapticFeedbackType.Confirm)
          showScanner = false
          onOpenLegalDoc(url)
        },
        onEnterCode = { codeError = null; showCodeEntry = true },
        onClose = { showScanner = false },
      )
    }
  }

  if (showProfile) {
    val gating = afterName != null
    ProfileSheet(
      initial = profile,
      title = stringResource(if (gating) R.string.enter_your_name else R.string.name),
      cta = stringResource(if (gating) R.string.save_and_continue else R.string.save),
      onDismiss = {
        showProfile = false
        afterName = null
      },
      onSave = { saved ->
        ProfileStore.save(context, saved)
        profile = saved
        showProfile = false
        val a = afterName
        afterName = null
        if (a != null) perform(a, saved)
      },
    )
  }

  if (showCodeEntry) {
    CodeEntryDialog(
      loading = codeLoading,
      error = codeError,
      onDismiss = {
        if (!codeLoading) {
          showCodeEntry = false
          codeError = null
        }
      },
      onSubmit = { code ->
        codeError = null
        codeLoading = true
        scope.launch {
          val outcome = resolveTypedCode(code.trim(), games)
          codeLoading = false
          when (outcome) {
            is JoinOutcome.Success -> {
              showCodeEntry = false
              launchJoin(outcome, profile)
            }
            is JoinOutcome.Failure -> {
              haptics.performHapticFeedback(HapticFeedbackType.Reject)
              codeError = resources.getString(outcome.messageRes)
            }
          }
        }
      },
    )
  }

  infoGame?.let { game ->
    GameInfoSheet(
      game = game,
      onDismiss = { infoGame = null },
      onScan = { infoGame = null; requireName(AfterName.Scan) },
      onEnterCode = { infoGame = null; requireName(AfterName.EnterCode) },
    )
  }
}

// A join action deferred until the player has a name (see requireName).
private sealed interface AfterName {
  data object Scan : AfterName
  data object EnterCode : AfterName
  data class Join(val target: JoinOutcome.Success) : AfterName
}

// Home chrome, structurally identical to the in-game LeaveBar so the title and
// name chip land in the same place across screens (GameHostScreen.kt).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(profile: Profile, onEditProfile: () -> Unit, onOpenAbout: () -> Unit) {
  TopAppBar(
    title = {
      Column {
        Text(
          stringResource(R.string.app_name),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          stringResource(R.string.home_subtitle),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    actions = {
      PlayerChip(name = profile.name, onClick = onEditProfile)
      IconButton(onClick = onOpenAbout) {
        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about))
      }
      Spacer(Modifier.width(8.dp))
    },
    // The host Box already pads status bar + cutout; don't let the bar re-add them.
    windowInsets = WindowInsets(0),
    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
  )
}

// Tactile press feedback shared by the tappable cards: a subtle spring scale-down
// while held, matching iOS's PressableCardButtonStyle.
@Composable
private fun rememberPressScale(interaction: MutableInteractionSource): Float {
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) 0.97f else 1f,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "cardPress",
  )
  return scale
}

// The game-end notice: a high-contrast strip in the rejoin slot. Auto-dismisses after
// 5s; a tap or a horizontal swipe dismisses it early. Enter/exit mirror the rejoin card
// so the two stack cleanly when both are present.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameEndBanner(message: String?, onDismiss: () -> Unit) {
  // Retain the last text so the exit animation still has content after `message` clears.
  var lastMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(message) {
    if (message != null) {
      lastMessage = message
      delay(5_000)
      onDismiss()
    }
  }

  AnimatedVisibility(
    visible = message != null,
    enter = fadeIn() + scaleIn(initialScale = 0.96f),
    exit = fadeOut() + scaleOut(targetScale = 0.96f),
  ) {
    // Swipe-off is handled by the framework's SwipeToDismissBox. Its state is scoped to
    // this content, which is disposed on exit and recomposed on the next enter, so the
    // swipe resets on its own without any manual reset.
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
      if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) onDismiss()
    }
    SwipeToDismissBox(
      state = dismissState,
      backgroundContent = {},   // nothing revealed — the strip just slides away
    ) {
      Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .fillMaxWidth()
          .clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
          ) { onDismiss() },
      ) {
        Text(
          text = lastMessage.orEmpty(),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

/**
 * A room the player can enter right now — the one they just left, or one a display is
 * advertising. The two are the same object to a player, so they are one card; only where
 * the pieces come from differs, which is what [RejoinCard] and [NearbyCard] supply.
 *
 * Ordinary chrome — the same secondaryContainer the tonal buttons use, so it adapts per
 * theme instead of sitting as a dark slab on a light screen. The game's colour lives on
 * the icon and nowhere else: routing brand through the border made every HexStacker card
 * near-CTA red (its accent is #FF6B6B against coral #F04A50), which both read as an alert
 * and spent the one colour that's supposed to mean "this is how you play".
 */
@Composable
private fun RoomCard(
  game: Game,
  title: String,
  roomCode: String,
  /** The display's own label ("Wohnzimmer"). Blank when nothing on the LAN names the room. */
  label: String,
  /** `cpp` — which box the room is on (§6). Null when the URL declared nothing. */
  platform: String?,
  onClick: () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val scale = rememberPressScale(interaction)
  val onCard = MaterialTheme.colorScheme.onSecondaryContainer
  // "Wohnzimmer · Apple TV" — which box, on its own line. Either half may be missing.
  val locator = listOfNotNull(label.ifBlank { null }, deviceName(platform)).joinToString(" · ")
  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { scaleX = scale; scaleY = scale },
    interactionSource = interaction,
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = onCard,
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      GameIcon(game, tint = onCard)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          cardTitle(title, roomCode, onCard.copy(alpha = 0.55f)),
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (locator.isNotBlank()) {
          Text(
            locator,
            style = MaterialTheme.typography.bodyMedium,
            color = onCard.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = onCard.copy(alpha = 0.65f),
      )
    }
  }
}

/**
 * The room just left: its title comes from the controller page itself once captured, so
 * it beats the manifest's curated name.
 *
 * [advert] is the same room as the LAN is currently advertising it, when it still is
 * (`HomeRooms.rejoinAdvert`) — the only source of the room's own NAME, and the freshest
 * source of its platform. Never the only source of that, though: an advertisement is
 * transient — a browser room is advertised only by the phones in it, so it stops naming
 * its box moments after this one leaves — so the platform lives on the room itself.
 */
@Composable
private fun RejoinCard(room: RecentRoom, advert: NearbyRoom? = null, onClick: () -> Unit) = RoomCard(
  game = room.game,
  title = room.title ?: room.game.name,
  roomCode = room.roomCode,
  label = advert?.label.orEmpty(),
  platform = advert?.platform ?: room.platform,
  onClick = onClick,
)

/** A room a display on this network is advertising (contract §8). */
@Composable
private fun NearbyCard(room: NearbyRoom, onClick: () -> Unit) = RoomCard(
  game = room.game,
  title = room.game.name,
  roomCode = room.roomCode,
  label = room.label,
  platform = room.platform,
  onClick = onClick,
)

// "HexStacker A3KX9p" — the room code sits with the thing it belongs to, demoted in
// color so the name still leads. One annotated string, so a long name truncates the
// pair as a unit.
private fun cardTitle(name: String, roomCode: String, codeColor: Color) = buildAnnotatedString {
  append(name)
  if (roomCode.isNotBlank()) {
    withStyle(SpanStyle(color = codeColor)) {
      append("  ")
      append(roomCode)
    }
  }
}

// What the TV slot shows when there are no rooms to show.
//
// The ask is an action, so it takes a button — the room cards are objects you pick from,
// and giving the ask their shape blurs the two. Tonal, not coral: this is one-time setup
// that vanishes for good once granted, and it must not outshout the scan CTA you use every
// session. Metrics match JoinButtons so the three buttons on this screen are one family.
//
// Once granted, searching and not-found collapse to a muted line — an idle home screen
// shouldn't carry a box announcing that a TV simply isn't switched on.
@Composable
private fun NearbyStatusCard(granted: Boolean, settled: Boolean, onAsk: () -> Unit) {
  if (!granted) {
    FilledTonalButton(onClick = onAsk, modifier = Modifier.fillMaxWidth().height(56.dp)) {
      Icon(painterResource(R.drawable.ic_nearby), contentDescription = null, Modifier.size(22.dp))
      Spacer(Modifier.width(10.dp))
      Text(stringResource(R.string.nearby_find), style = MaterialTheme.typography.titleMedium)
    }
    return
  }
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (settled) {
      Icon(painterResource(R.drawable.ic_nearby), contentDescription = null, Modifier.size(16.dp))
    } else {
      CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    Text(
      stringResource(if (settled) R.string.nearby_none else R.string.nearby_searching),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// Every game — live or coming soon — gets the same full-width poster card.
@Composable
private fun GamesSection(games: List<Game>, modifier: Modifier = Modifier, onOpen: (Game) -> Unit) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    games.forEach { game -> GameCard(game, onOpen) }
  }
}

// Poster tile: name/tagline/status sit ON the art over a bottom scrim, so the
// scrim colors are fixed (white text on black gradient) in both themes.
@Composable
private fun GameCard(game: Game, onOpen: (Game) -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val scale = rememberPressScale(interaction)
  ElevatedCard(
    onClick = { onOpen(game) },
    modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
    interactionSource = interaction,
  ) {
    Box {
      // 16:9 matches the screenshots — a shorter crop would slice the per-player
      // HUD chips in the corners of each quadrant.
      GameArt(game, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
      Box(
        Modifier.matchParentSize().background(
          Brush.verticalGradient(
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.86f),
          ),
        ),
      )
      Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          game.name,
          style = MaterialTheme.typography.titleMedium,
          color = Color.White,
          modifier = Modifier.weight(1f),
        )
        PosterStatusChip(game)
      }
    }
  }
}

// The floating join card pinned over the scrolling catalog: browse first, act at
// the thumb. Order and copy per design.
@Composable
private fun JoinCard(
  host: String,
  onScan: () -> Unit,
  onEnterCode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    // A hairline border does the "lift" in light mode; a heavy drop-shadow just
    // reads as a gray blob on the near-white base.
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    shadowElevation = 2.dp,
  ) {
    Column(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(stringResource(R.string.join_title), style = MaterialTheme.typography.titleLarge)
      Text(
        annotatedHostLine(stringResource(R.string.join_open_host), host, MaterialTheme.colorScheme.primary),
        style = MaterialTheme.typography.bodyLarge,
      )
      JoinButtons(onScan = onScan, onEnterCode = onEnterCode)
    }
  }
}

// Typed codes resolve via the relay directory (a bare code carries no domain).
// Never auto-uppercases — room codes are case-sensitive base58.
@Composable
private fun CodeEntryDialog(
  loading: Boolean,
  error: String?,
  onSubmit: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var code by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.enter_room_code)) },
    text = {
      MirrorHostSystemBars()
      OutlinedTextField(
        value = code,
        onValueChange = { if (it.length <= 16) code = it },
        placeholder = { Text(stringResource(R.string.code_placeholder, SAMPLE_ROOM_CODE)) },
        singleLine = true,
        isError = error != null,
        supportingText = { if (error != null) Text(error) },
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(onClick = { onSubmit(code) }, enabled = code.isNotBlank() && !loading) {
        Text(stringResource(if (loading) R.string.joining else R.string.join))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
    },
  )
}

// ---------------------------------------------------------------------------
// Previews
//
// The home screen's room-card states in one strip, fed by CardSamples so the ones that
// otherwise need a live room — an Android TV display, a game with no manifest icon, a
// name long enough to truncate — are reachable while editing. The cards are theme
// chrome, so the light/dark pair is checking that they adapt rather than sitting as a
// slab. Mirrored by iOS `MainScreen.swift`; a card change wants both.
//
// GameIcon decodes from assets, which the IDE renderer supplies; a remote-only icon has
// no cache here and falls back to the TV glyph.
// ---------------------------------------------------------------------------

@Preview(name = "Nearby — light", showBackground = true, widthDp = 400)
@Preview(name = "Nearby — dark", showBackground = true, widthDp = 400, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun NearbyCardsPreview() {
  PreviewStrip {
    NearbyStatusCard(granted = false, settled = false, onAsk = {})
    NearbyStatusCard(granted = true, settled = false, onAsk = {})
    NearbyStatusCard(granted = true, settled = true, onAsk = {})
    NearbyCard(CardSamples.nearbyFull) {}
    NearbyCard(CardSamples.nearbyDeviceOnly) {}
    NearbyCard(CardSamples.nearbyLabelOnly) {}
    NearbyCard(CardSamples.nearbyBare) {}
    NearbyCard(CardSamples.nearbyAndroidTv) {}
    NearbyCard(CardSamples.nearbyIconless) {}
    NearbyCard(CardSamples.nearbyLongName) {}
  }
}

@Preview(name = "Rejoin — light", showBackground = true, widthDp = 400)
@Preview(name = "Rejoin — dark", showBackground = true, widthDp = 400, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun RejoinCardsPreview() {
  PreviewStrip {
    RejoinCard(CardSamples.rejoinPlain) {}
    RejoinCard(CardSamples.rejoinIconless) {}
    RejoinCard(CardSamples.rejoinPageTitle) {}
    RejoinCard(CardSamples.rejoinNoCode) {}
    RejoinCard(CardSamples.rejoinWithDevice) {}
    // The room's display is still advertising: its name joins the locator, and its
    // relay-declared platform stands in for a remembered URL that named no box.
    RejoinCard(CardSamples.rejoinPlain, CardSamples.nearbyFull) {}
    RejoinCard(CardSamples.rejoinWeb) {}
  }
}

// Home's own card spacing and padding, so the previews show the gaps the real screen has.
@Composable
private fun PreviewStrip(content: @Composable ColumnScope.() -> Unit) {
  CouchPadTheme {
    Surface {
      Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
      )
    }
  }
}
