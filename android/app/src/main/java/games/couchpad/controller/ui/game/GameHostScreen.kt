package games.couchpad.controller.ui.game

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import games.couchpad.controller.R
import games.couchpad.controller.BuildConfig
import games.couchpad.controller.data.LAUNCHER_HOST
import games.couchpad.controller.data.Profile
import games.couchpad.controller.data.ProfileStore
import games.couchpad.controller.data.NearbyAdvertiser
import games.couchpad.controller.data.RecentRoomStore
import games.couchpad.controller.data.hostInDomain
import games.couchpad.controller.data.isPrivateHost
import games.couchpad.controller.theme.contentColorOn
import games.couchpad.controller.theme.CouchPadTheme
import games.couchpad.controller.ui.components.PlayerChip
import games.couchpad.controller.ui.components.ServerUnreachableRetry
import games.couchpad.controller.ui.components.denyLocalFileAccess
import games.couchpad.controller.ui.components.findActivity
import games.couchpad.controller.ui.components.hideNavigationBar
import games.couchpad.controller.ui.components.themeLightBarIcons
import games.couchpad.controller.ui.main.ProfileSheet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Hosts a game's remote controller in a native WebView under a launcher-owned
 * "Leave" bar. As a TOP-LEVEL WebView (not an iframe), the game's `frame-ancestors`
 * CSP doesn't apply. [allowedHosts] is the navigation allow-list — the client-side
 * trust boundary, since the join URL can originate from an untrusted relay lookup.
 */
@Composable
fun GameHostScreen(
  joinUrl: String,
  title: String,
  allowedHosts: List<String>,
  onLeave: () -> Unit,
  onGameEnd: (reason: String?) -> Unit,
) {
  // Optional theming hints from the page's <head> (CONTRACT.md §4), pushed by the
  // launcher-injected observer at load and on every runtime change.
  var pageTheme by remember { mutableStateOf(PageTheme()) }
  // In-game chrome is always dark, like a video player — the games are dark and a
  // bright bar above them would be jarring.
  CouchPadTheme(darkTheme = true) {
    // A game-supplied accent flows through `primary`, so every launcher accent over
    // the game (chip, spinner, rename sheet) follows.
    val scheme = MaterialTheme.colorScheme
    val accented = pageTheme.accent?.let { scheme.copy(primary = it, onPrimary = contentColorOn(it)) } ?: scheme
    MaterialTheme(colorScheme = accented) {
      GameHostContent(joinUrl, title, allowedHosts, onLeave, onGameEnd, pageTheme, onPageTheme = { pageTheme = it })
    }
  }
}

// JavascriptInterface: CouchPadHostBridge's exposed methods ARE @JavascriptInterface-
// annotated (see the class below), but lint resolves hostBridge through remember()'s
// generic return and can't see the annotations, so it false-positives on the
// addJavascriptInterface call.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun GameHostContent(
  joinUrl: String,
  title: String,
  allowedHosts: List<String>,
  onLeave: () -> Unit,
  onGameEnd: (reason: String?) -> Unit,
  pageTheme: PageTheme,
  onPageTheme: (PageTheme) -> Unit,
) {
  val context = LocalContext.current
  val view = LocalView.current
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  var webView by remember { mutableStateOf<WebView?>(null) }
  val allowed = remember(allowedHosts) { (allowedHosts + LAUNCHER_HOST).map { it.lowercase() } }
  var profile by remember { mutableStateOf(ProfileStore.load(context)) }
  var showProfile by remember { mutableStateOf(false) }
  var loading by remember { mutableStateOf(true) }
  // The main document failed to load (no connection / host unreachable) — shows the
  // in-place retry overlay instead of a dead join spinner.
  var failed by remember { mutableStateOf(false) }
  // The page's own <title> supersedes the manifest name in the LEAVE bar once the
  // controller reports one, so games not (yet) in the bundled manifest still show a
  // real name instead of the generic "CouchPad" fallback. Null until the page
  // reports; the manifest name covers the join cover and any title-less page.
  var pageTitle by remember { mutableStateOf<String?>(null) }
  // Has the page armed the system back gesture (CONTRACT.md §9)? Default false —
  // the safe state: edges excluded, LEAVE the only exit. Reset on every navigation.
  var systemBackEnabled by remember { mutableStateOf(false) }
  val displayTitle = pageTitle ?: title
  val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
  // The bridge/WebView client outlive recompositions but must call the CURRENT
  // callbacks — hence rememberUpdatedState.
  val currentOnLeave by rememberUpdatedState(onLeave)
  val currentOnGameEnd by rememberUpdatedState(onGameEnd)
  val currentOnPageTheme by rememberUpdatedState(onPageTheme)
  // One-shot guard for the two TERMINAL exits — user LEAVE and a game-reported end.
  // Whoever fires first wins; the loser (incl. a stray gameEnded during teardown)
  // no-ops, so we never pop the back stack twice. A load failure is NOT terminal: it
  // shows the retry overlay in place, and only Leave from there trips this.
  val exited = remember { AtomicBoolean(false) }
  val leave = { if (exited.compareAndSet(false, true)) currentOnLeave() }
  // Retry the controller load in place (no re-scan): clear the error, bring the join
  // cover back, reload.
  val retry = {
    failed = false
    loading = true
    webView?.reload()
    Unit
  }
  val hostBridge = remember {
    CouchPadHostBridge(
      onGameEnded = { if (exited.compareAndSet(false, true)) currentOnGameEnd(it) },
      onThemeChanged = { currentOnPageTheme(it) },
      onSystemBackEnabled = { systemBackEnabled = it },
    )
  }

  // Relay this room to the local network while we're in it, so the next player can tap
  // instead of scan — and so the room stays discoverable even if its display never
  // advertised. Publishes the room code only (§8); no URL, no device name.
  DisposableEffect(joinUrl) {
    RecentRoomStore.current()?.let { NearbyAdvertiser.start(context, it.roomCode) }
    onDispose { NearbyAdvertiser.stop() }
  }

  // Hide ONLY the nav bar while in a game — the status bar stays. Hidden-nav +
  // transient-by-swipe is exactly the state that LIFTS the system's 200dp-per-edge
  // cap on gesture exclusion, so the WebView's exclusion rects (set below) can
  // cover the whole play area.
  //
  // But that state follows the ARMED state (CONTRACT.md §9), because both reasons to
  // hide the bar lapse the moment the page arms system back:
  //  - the lifted cap only matters while we exclude the whole surface; an armed page
  //    excludes nothing, so there is no cap left to lift.
  //  - while the bar is hidden, transient-by-swipe spends the FIRST edge swipe
  //    revealing it instead of going back — the page's first back would be eaten,
  //    and the player has to swipe twice.
  LaunchedEffect(systemBackEnabled) {
    val window = context.findActivity()?.window ?: return@LaunchedEffect
    if (systemBackEnabled) {
      WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.navigationBars())
    } else {
      hideNavigationBar(window, view)
    }
  }

  // On leave the nav bar comes back and the status-icon appearance (changed by page
  // theming below) is re-derived from the theme — a captured value would be stale if
  // the theme flipped mid-game (uiMode no longer recreates the activity).
  DisposableEffect(Unit) {
    val controller = context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
    onDispose {
      controller?.run {
        show(WindowInsetsCompat.Type.navigationBars())
        isAppearanceLightStatusBars = themeLightBarIcons(context)
      }
    }
  }

  fun watchPageTheme() {
    webView?.evaluateJavascript(WATCH_PAGE_THEME_JS, null)
  }

  // Push the current name into the running controller (CONTRACT.md §2). Guarded,
  // so a game that hasn't implemented setName is a harmless no-op.
  fun injectName(name: String) {
    if (name.isBlank()) return
    webView?.evaluateJavascript(
      "window.CouchPad && typeof window.CouchPad.setName === 'function' && " +
        "window.CouchPad.setName(${JSONObject.quote(name)});",
      null,
    )
  }

  // Opt the controller surface out of the system back-gesture so edge swipes reach
  // the game — unless the page has armed system back (CONTRACT.md §9), in which case
  // the edges go back to the system so the gesture can start at all (and draws the
  // system's own back affordance). Full-height exclusion only works because the nav
  // bar is hidden. Applied on layout and whenever the flag flips.
  fun applyGestureExclusion(target: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    target.systemGestureExclusionRects =
      if (systemBackEnabled) emptyList() else listOf(Rect(0, 0, target.width, target.height))
  }

  LaunchedEffect(systemBackEnabled, webView) {
    webView?.let(::applyGestureExclusion)
  }

  // Always handled, never passed to the activity: back must never finish the task
  // from inside a live match. Disarmed (the default) it's swallowed outright — a
  // stray press or edge swipe must not drop a player out. Armed, the page gets first
  // refusal via back(); anything but a literal `true` means it didn't consume the
  // press, and the launcher leaves. The evaluate is async, so the shell simply stays
  // put until the answer arrives.
  BackHandler {
    if (!systemBackEnabled) return@BackHandler
    val wv = webView
    if (wv == null) leave() else wv.evaluateJavascript(DELIVER_BACK_JS) { if (it != "true") leave() }
  }

  // Leaving the app (home/app switch/lock) synthesizes `pagehide` so the game
  // closes its relay socket immediately (CONTRACT.md §7) — see DISPATCH_PAGE_HIDE_JS.
  // Reconnect needs no help: the engine fires visibilitychange → visible on return.
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    webView?.evaluateJavascript(DISPATCH_PAGE_HIDE_JS, null)
  }

  // A game-supplied theme-color becomes the chrome's scrim tint; its content
  // color is luminance-picked since the page sends no pair.
  val barTarget = pageTheme.bar ?: MaterialTheme.colorScheme.surfaceContainer
  val barColor by animateColorAsState(barTarget, tween(300), label = "gameBarColor")
  val barContent = pageTheme.bar?.let(::contentColorOn)

  // Safe-zone geometry, measured off the real layout (window px). Top is the
  // chrome's full extent (inset + LEAVE bar). Right reaches the name chip's edge;
  // the gap beyond the cutout is the chrome's content gutter, mirrored onto the
  // left so the page lines up with the close icon — each side still adds its own
  // cutout overhang. Bottom is the cutout, or the nav bar once an armed page brings
  // it back — reported as visible insets, so this is 0 again while it is hidden.
  var chromeHeightPx by remember { mutableStateOf(0) }
  var chromeWidthPx by remember { mutableStateOf(0) }
  var chipRightPx by remember { mutableStateOf(0) }
  val cutout = WindowInsets.displayCutout
  val cutoutLeft = cutout.getLeft(density, layoutDirection)
  val cutoutRight = cutout.getRight(density, layoutDirection)
  val cutoutBottom = cutout.getBottom(density)
  val navBottom = WindowInsets.navigationBars.getBottom(density)
  var safeLeftPx by remember { mutableStateOf(0) }
  var safeRightPx by remember { mutableStateOf(0) }
  var safeBottomPx by remember { mutableStateOf(0) }

  // Publish the safe zone to the page as CSS vars on <html> (CONTRACT.md §5), in
  // CSS px. Reads state at CALL time — the WebView factory captures this closure once.
  //
  // ceil, not round: an inset that lands mid-pixel must cover the obstruction, never
  // stop half a pixel short of it. It also makes these agree with the same edges seen
  // through env(safe-area-inset-*), which Chromium rounds up off the synthetic cutout
  // below — rounding to nearest left the two channels 1px apart on fractional edges.
  fun pushSafeZone() {
    val d = density.density
    fun cssPx(px: Int) = ceil(px / d).toInt()
    webView?.evaluateJavascript(
      "(() => { const s = document.documentElement.style;" +
        " s.setProperty('--cp-safe-top', '${cssPx(chromeHeightPx)}px');" +
        " s.setProperty('--cp-safe-left', '${cssPx(safeLeftPx)}px');" +
        " s.setProperty('--cp-safe-right', '${cssPx(safeRightPx)}px');" +
        " s.setProperty('--cp-safe-bottom', '${cssPx(safeBottomPx)}px'); })()",
      null,
    )
  }

  // Recompute + re-push on any layout change; requestApplyInsets re-dispatches the
  // synthetic cutout (set up in the factory) so env(safe-area-inset-*) tracks the
  // same four edges as the vars.
  LaunchedEffect(
    chromeHeightPx,
    chromeWidthPx,
    chipRightPx,
    cutoutLeft,
    cutoutRight,
    cutoutBottom,
    navBottom,
    webView,
  ) {
    safeRightPx = if (chromeWidthPx > 0) (chromeWidthPx - chipRightPx).coerceAtLeast(cutoutRight) else cutoutRight
    val gutter = (safeRightPx - cutoutRight).coerceAtLeast(0)
    safeLeftPx = cutoutLeft + gutter
    safeBottomPx = maxOf(cutoutBottom, navBottom)
    pushSafeZone()
    webView?.requestApplyInsets()
  }

  // Keep status-bar icons contrasting against the (possibly game-colored) bar strip.
  // Also keyed on uiMode: a mid-game theme flip re-runs MainActivity.applyEdgeToEdge,
  // which stomps this, so re-assert after it.
  val lightStatusIcons = barTarget.luminance() > 0.5f
  val uiMode = LocalConfiguration.current.uiMode
  LaunchedEffect(lightStatusIcons, uiMode) {
    context.findActivity()?.window?.let {
      WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = lightStatusIcons
    }
  }

  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    // The game surface spans the FULL physical screen — the chrome floats above it,
    // and the page keeps its interactive UI inside the published safe zone.
    Box(Modifier.fillMaxSize()) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
        // Never expose a player's live game socket to chrome://inspect in production.
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        WebView(ctx).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true                  // the controller persists via localStorage
          settings.mediaPlaybackRequiresUserGesture = false
          // Harden: the remote controller has no business touching local files.
          denyLocalFileAccess()
          // Match the dark chrome while the page is blank — kills the white flash.
          setBackgroundColor(surfaceArgb)
          // Intercept insets, two jobs. (1) The REAL insets never reach WebView:
          // on targetSdk 35+ Chromium self-applies IME insets, and the game surface
          // must never resize for the keyboard (it overlays it). (2) Hand WebView a
          // SYNTHETIC display cutout equal to the full safe zone: viewport-fit=cover
          // pages then see the same four edges as --cp-safe-* through the standard
          // env(safe-area-inset-*). Chromium reads the DisplayCutout's safe insets;
          // the matching insets and per-edge bounding rects keep the object
          // self-consistent. Chromium only honors cutouts while the WebView spans
          // the whole display, so the --cp-safe-* vars stay the source of truth.
          ViewCompat.setOnApplyWindowInsetsListener(this) { v, _ ->
            if (chromeHeightPx > 0) {
              val safe = Insets.of(safeLeftPx, chromeHeightPx, safeRightPx, safeBottomPx)
              val bounds = buildList {
                add(Rect(0, 0, v.width, chromeHeightPx))
                if (safeBottomPx > 0) add(Rect(0, v.height - safeBottomPx, v.width, v.height))
                if (safeLeftPx > 0) add(Rect(0, 0, safeLeftPx, v.height))
                if (safeRightPx > 0) add(Rect(v.width - safeRightPx, 0, v.width, v.height))
              }
              WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), safe)
                .setDisplayCutout(DisplayCutoutCompat(Rect(safeLeftPx, chromeHeightPx, safeRightPx, safeBottomPx), bounds))
                .build()
                .toWindowInsets()
                ?.let { v.onApplyWindowInsets(it) }
            }
            WindowInsetsCompat.CONSUMED
          }
          // Re-assert the name on each load (belt-and-suspenders with cpName).
          webViewClient = AllowListWebViewClient(
            allowed,
            // Arming must not outlive the page that meant it (CONTRACT.md §9).
            onNavigationStart = { systemBackEnabled = false },
            onLoaded = {
              loading = false
              injectName(profile.name)
              watchPageTheme()
              pushSafeZone()
            },
            // The controller page itself couldn't load (no connection / host
            // unreachable) — show the retry overlay in place, not a dead spinner.
            // (Ignored once the player has left: the WebView is being torn down.)
            onConnectionError = {
              if (!exited.get()) {
                loading = false
                failed = true
              }
            },
          )
          webChromeClient = object : WebChromeClient() {
            // The page's own name (ground truth over the manifest): drives the LEAVE
            // bar live and feeds the home rejoin card. Fires on every document.title
            // change, so late SPA renames are picked up too.
            override fun onReceivedTitle(view: WebView?, title: String?) {
              // While the load has failed the title is WebView's own error page
              // ("Webpage not available") — it would pollute the Leave bar and the
              // room card, so ignore it until a real page loads.
              if (failed || title == null) return
              RecentRoomStore.putTitle(title)?.let { pageTitle = it }
            }
          }
          keepScreenOn = true
          addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyGestureExclusion(v) }
          // Must be attached before loadUrl or the page won't see it.
          addJavascriptInterface(hostBridge, "CouchPadHost")
          loadUrl(joinUrl)
          webView = this
        }
        },
      )
      // "Joining…" cover that fades away once the controller has painted.
      // (Qualified: the ColumnScope overload would otherwise shadow this one.)
      androidx.compose.animation.AnimatedVisibility(
        visible = loading,
        enter = fadeIn(),
        exit = fadeOut(tween(300)),
        modifier = Modifier.fillMaxSize(),
      ) {
        Box(
          Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            CircularProgressIndicator()
            Text(
              stringResource(R.string.joining_game, displayTitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      // Load failed: an opaque cover over the dead page offering retry-in-place (so a
      // transient blip doesn't cost a re-scan). Above the join cover, below the floating
      // chrome. No Leave button — the Leave bar's X already exits. Surface-toned so it
      // sits over the live game page rather than reading as a full screen.
      if (failed) {
        ServerUnreachableRetry(onRetry = retry, background = MaterialTheme.colorScheme.surface)
      }
    }
    // The floating chrome: status-bar strip + LEAVE bar over a scrim. Top +
    // horizontal insets only, deliberately: when a keyboard opens the system
    // re-marks the (hidden) nav bar visible, and a nav-tracking inset would move
    // the chrome. The game surface never resizes for anything — the keyboard
    // overlays it, like a video player.
    Column(
      Modifier
        .fillMaxWidth()
        .onGloballyPositioned {
          chromeHeightPx = it.size.height
          chromeWidthPx = it.size.width
        }
        .background(
          Brush.verticalGradient(
            0f to barColor.copy(alpha = 0.9f),
            0.65f to barColor.copy(alpha = 0.5f),
            1f to barColor.copy(alpha = 0f),
          ),
        )
        .windowInsetsPadding(
          WindowInsets.statusBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
      LeaveBar(
        title = displayTitle,
        playerName = profile.name,
        onLeave = leave,
        onEditName = { showProfile = true },
        contentColor = barContent,
        accented = pageTheme.accent != null,
        onChipRight = { chipRightPx = it.roundToInt() },
      )
    }
  }

  if (showProfile) {
    ProfileSheet(
      initial = profile,
      // Use the game's theme-color as the sheet surface, but only when it's dark
      // enough to keep the sheet's white text legible (white ≥ 4.5:1 needs luminance
      // < ~0.18); a lighter theme-color falls back to the neutral surface.
      surfaceTint = pageTheme.bar?.takeIf { it.luminance() < 0.18f },
      onDismiss = { showProfile = false },
      onSave = { saved ->
        ProfileStore.save(context, saved)
        profile = saved
        showProfile = false
        injectName(saved.name)                               // live-update the running controller
      },
    )
  }

  // Tear the WebView down on leave so the game's WebSocket/audio fully stop.
  DisposableEffect(Unit) {
    onDispose { webView?.destroy() }
  }
}

// The launcher-owned chrome floating over the game: Close (leaving a live game
// ends the session — it isn't navigation), the game's name, and the tappable name
// chip (the in-game rename affordance). [contentColor] is non-null only when the
// game supplied its own theme-color; [accented] when it supplied an accent.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveBar(
  title: String,
  playerName: String,
  onLeave: () -> Unit,
  onEditName: () -> Unit,
  contentColor: Color?,
  accented: Boolean,
  onChipRight: (Float) -> Unit,
) {
  // Route a game-supplied content color through the tokens the bar's children
  // actually read, so everything on the bar flips together.
  val scheme = MaterialTheme.colorScheme
  val onBar = contentColor?.let {
    scheme.copy(onSurface = it, onSurfaceVariant = it, outline = it.copy(alpha = 0.5f))
  } ?: scheme
  MaterialTheme(colorScheme = onBar) {
    TopAppBar(
      title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      navigationIcon = {
        IconButton(onClick = onLeave) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.leave_game))
        }
      },
      actions = {
        // Report the chip's window bounds up so the host can align the page's
        // horizontal safe zone with it.
        Box(Modifier.onGloballyPositioned { onChipRight(it.boundsInWindow().right) }) {
          PlayerChip(name = playerName, onClick = onEditName, accented = accented)
        }
        Spacer(Modifier.width(12.dp))
      },
      // The host pads status bar + cutout around the chrome — don't re-add them.
      windowInsets = WindowInsets(0),
      // Transparent: the host's fading scrim is the bar's backdrop.
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
  }
}

/**
 * Confines the WebView to the game's own domains (subdomains included). Only https
 * navigations to an allow-listed domain stay in-app; off-list http(s) links open in
 * the system browser, and any other scheme (javascript:, file:, intent:, …) is
 * refused outright. Governs top-level/frame navigations only — subresources and the
 * game's relay WebSocket are unaffected.
 */
private class AllowListWebViewClient(
  private val allowedDomains: List<String>,
  private val onNavigationStart: () -> Unit,
  private val onLoaded: () -> Unit,
  private val onConnectionError: () -> Unit,
) : WebViewClient() {
  // Main-frame only, and deliberately not fired for same-document navigations —
  // a page that pushes history mid-session keeps whatever it armed.
  override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onNavigationStart()

  override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
    val url = request.url
    val scheme = url.scheme?.lowercase()
    val host = url.host
    if (scheme == "https" && allowedDomains.any { hostInDomain(host, it) }) return false // load in-place
    // Debug only: keep http(s) navigations to a LAN dev host in-app (see [isPrivateHost]).
    if (BuildConfig.DEBUG && isPrivateHost(host) && (scheme == "http" || scheme == "https")) return false
    if (scheme == "http" || scheme == "https") openExternally(view.context, url) // off-list → browser
    return true // everything not explicitly allowed is blocked from the WebView
  }

  // A network-level failure of the MAIN document (no connection, DNS/connect/timeout —
  // NOT a 4xx/5xx, which arrives via onReceivedHttpError and means the host answered).
  // Subresource failures are the page's own problem and ignored.
  override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
    if (request.isForMainFrame) onConnectionError()
  }

  // Fade the cover on the first DRAW of the loaded page, not on load: the page's JS
  // can paint noticeably after onPageFinished (seconds, on a cold start), which used
  // to reveal a blank WebView. postVisualStateCallback fires once this DOM state has
  // actually been rendered. Deliberately no time-based fallback — fading the cover
  // before content exists is the bug, not a safety net (a stalled page keeps the
  // honest spinner and Leave stays available; load failures surface the retry cover
  // via onReceivedError/onReceivedHttpError).
  override fun onPageFinished(view: WebView, url: String) {
    view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
      override fun onComplete(requestId: Long) = onLoaded()
    })
  }

  private fun openExternally(context: Context, uri: Uri) {
    runCatching {
      context.startActivity(
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
  }
}

/**
 * The game→launcher half of the contract (v1), exposed as `window.CouchPadHost`.
 * Runs on WebView's JS bridge thread, so hop to main before touching Compose state.
 * gameEnded is fire-once — a queued second call (or a game spamming it) must not
 * pop extra nav entries. All arguments are untrusted page input.
 */
private class CouchPadHostBridge(
  private val onGameEnded: (String?) -> Unit,
  private val onThemeChanged: (PageTheme) -> Unit,
  private val onSystemBackEnabled: (Boolean) -> Unit,
) {
  private val fired = AtomicBoolean(false)
  private val mainHandler = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun gameEnded(reason: String?) {
    if (!fired.compareAndSet(false, true)) return
    mainHandler.post { onGameEnded(reason) }
  }

  // Fed by the launcher's OWN injected meta observer (WATCH_PAGE_THEME_JS) — it's
  // on this bridge only because the page needs some JS→native channel. Not
  // fire-once: themes change repeatedly. Parsed strictly (untrusted).
  @JavascriptInterface
  fun themeChanged(json: String?) {
    val theme = parsePageTheme(json)
    mainHandler.post { onThemeChanged(theme) }
  }

  // Whether the system back gesture may occur right now (CONTRACT.md §9). Not
  // fire-once: games arm and disarm repeatedly (a dialog opening and closing).
  // Declaring the parameter as Boolean gives the contract's strict `=== true` for
  // free — the JS bridge converts every non-boolean argument to false.
  @JavascriptInterface
  fun enableSystemBack(enabled: Boolean) {
    mainHandler.post { onSystemBackEnabled(enabled) }
  }
}
