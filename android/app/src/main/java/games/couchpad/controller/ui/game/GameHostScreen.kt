package games.couchpad.controller.ui.game

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import games.couchpad.controller.R
import games.couchpad.controller.BuildConfig
import games.couchpad.controller.data.LAUNCHER_HOST
import games.couchpad.controller.data.Profile
import games.couchpad.controller.data.ProfileStore
import games.couchpad.controller.data.NearbyAdvertiser
import games.couchpad.controller.data.RecentRoomStore
import games.couchpad.controller.data.clearLocalNetworkAsked
import games.couchpad.controller.data.localNetworkPermanentlyDenied
import games.couchpad.controller.data.localNetworkPermissionGranted
import games.couchpad.controller.data.markLocalNetworkAsked
import games.couchpad.controller.data.hostInDomain
import games.couchpad.controller.data.isPrivateHost
import games.couchpad.controller.theme.contentColorOn
import games.couchpad.controller.theme.CouchPadTheme
import games.couchpad.controller.ui.components.JoiningCover
import games.couchpad.controller.ui.components.PlayerChip
import games.couchpad.controller.ui.components.ServerUnreachableRetry
import games.couchpad.controller.ui.components.denyLocalFileAccess
import games.couchpad.controller.ui.components.findActivity
import games.couchpad.controller.ui.components.gestureNavEnabled
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
  // Bumped when the renderer dies — a WebView can't be reused after that, so the
  // key() below swaps in a fresh one that re-issues the join.
  var webViewKey by remember { mutableStateOf(0) }
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
  // Has the page asked for landscape (CONTRACT.md §10)? Default false — the launcher's
  // portrait. Reset on every navigation, like the §9 arming.
  var landscape by remember { mutableStateOf(false) }
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
  // First-join local network gate: games open a direct WebRTC path to their display
  // ("fastlane"), and Android 17 silently blocks LAN traffic without
  // ACCESS_LOCAL_NETWORK. Asked HERE, with the page load held until the dialog is
  // answered — a grant that lands after the page has started ICE is only picked up by
  // the game's own retry loop, so resolving first makes the first connection
  // deterministic. The join never blocks on the ANSWER: a deny loads the page anyway,
  // which falls back to its relay exactly as on an AP-isolated network. Granted,
  // locked (localNetworkPermanentlyDenied), or pre-enforcement SDKs skip straight
  // through, so the hold happens at most on the first join or two ever.
  var lanGateOpen by remember {
    mutableStateOf(
      localNetworkPermissionGranted(context) ||
        localNetworkPermanentlyDenied(context, context.findActivity()),
    )
  }
  val localNetworkPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    // A grant forgets the asked-once record — same rule as home's refreshDiscovery
    // (see clearLocalNetworkAsked).
    if (granted) clearLocalNetworkAsked(context)
    lanGateOpen = true
  }
  LaunchedEffect(Unit) {
    if (!lanGateOpen) {
      markLocalNetworkAsked(context)
      localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
  }
  val hostBridge = remember {
    CouchPadHostBridge(
      onGameEnded = { if (exited.compareAndSet(false, true)) currentOnGameEnd(it) },
      onThemeChanged = { currentOnPageTheme(it) },
      onSystemBackEnabled = { systemBackEnabled = it },
      onLandscape = { landscape = it },
    )
  }

  // The page's requested orientation (CONTRACT.md §10). SENSOR_LANDSCAPE, not a fixed
  // one: a controller held either way round must land right side up, and the launcher
  // has no idea which hand the player uses. Portrait stays locked — a controller that
  // hasn't asked for landscape must not rotate into one by accident mid-match.
  //
  // Safe because the activity handles `orientation` in configChanges (see the manifest):
  // this rotates the window WITHOUT recreating the activity, so the WebView — and the
  // player's live relay socket — survive it.
  LaunchedEffect(landscape) {
    context.findActivity()?.requestedOrientation =
      if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  }

  // Relay this room to the local network while we're in it, so the next player can tap
  // instead of scan — and so the room stays discoverable even if its display never
  // advertised. Publishes the room code only (§8); no URL, no device name. Re-keyed on
  // the gate so a grant made there starts the advert in this same session (start() is
  // a no-op while the permission is missing).
  DisposableEffect(joinUrl, lanGateOpen) {
    RecentRoomStore.current()?.let { NearbyAdvertiser.start(context, it.roomCode) }
    onDispose { NearbyAdvertiser.stop() }
  }

  // The nav bar: hidden in a game regardless of orientation. Hidden-nav +
  // transient-by-swipe is exactly the state that LIFTS the system's 200dp-per-edge
  // cap on gesture exclusion, so the WebView's exclusion rects (set below) can
  // cover the whole play area.
  //
  // Arming (CONTRACT.md §9) only changes that for a 3-BUTTON player: their back is a
  // button, and a hidden bar has no buttons — so the bar comes back for as long as
  // the page stays armed. Under gesture navigation the bar stays hidden: the system
  // still delivers the edge back swipe while it's hidden (the transient reveal is
  // the BOTTOM edge's gesture, not the sides'), and showing it would only re-grow
  // the safe zone the page just paid for.
  LaunchedEffect(systemBackEnabled) {
    val window = context.findActivity()?.window ?: return@LaunchedEffect
    if (systemBackEnabled && !gestureNavEnabled(context)) {
      WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.navigationBars())
    } else {
      hideNavigationBar(window, view)
    }
  }

  // The status bar: hidden only in landscape, where its height comes off the axis a
  // landscape controller has least of. iOS gets this for free (UIKit auto-hides the
  // status bar in a compact-height size class), so matching here keeps the same
  // controller the same size on both apps rather than handing Android players a
  // shorter screen.
  //
  // Deliberately NOT tied to systemBackEnabled, unlike the nav bar above: the
  // exclusion-cap lift is a nav-bar-only condition (see hideNavigationBar), and back
  // never starts from the top edge, so neither reason to un-hide on arming applies.
  // That keeps the top inset out of §9's arming story — it moves on rotation alone.
  // Games need no change either way: --cp-safe-top is already live and §10 already
  // tells them the zone reshapes when it turns.
  //
  // Sets the transient-by-swipe behavior itself rather than inheriting whatever
  // hideNavigationBar last set on this window: same value, but a hidden bar with no
  // behavior set is one the player cannot swipe back, and nothing orders these two
  // effects.
  LaunchedEffect(landscape) {
    val window = context.findActivity()?.window ?: return@LaunchedEffect
    WindowCompat.getInsetsController(window, view).run {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      if (landscape) hide(WindowInsetsCompat.Type.statusBars())
      else show(WindowInsetsCompat.Type.statusBars())
    }
  }

  // On leave BOTH bars come back (systemBars, not navigationBars — a landscape game
  // also hid the status bar) and the launcher's portrait is restored (§10 — home is
  // portrait, and an orientation the game asked for must not outlive it). The
  // status-icon appearance is NOT restored here: it has a single owner, the
  // page-theming effect below, which reverts it on its own teardown.
  DisposableEffect(Unit) {
    val activity = context.findActivity()
    val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
    onDispose {
      activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      controller?.show(WindowInsetsCompat.Type.systemBars())
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

  // Safe-zone geometry, measured off the real layout (window px). In PORTRAIT the
  // top is the chrome's full extent (inset + LEAVE bar) and the sides carry the
  // chip's gutter. In LANDSCAPE there is no bar — the chrome collapses to the two
  // stacked icons in a side strip, the top shrinks to the bare cutout (the
  // game gets the full height), and the sides carry the icon column instead. Both
  // sides always get ONE shared value (§5 levelling). Bottom is the cutout, or the
  // nav bar when a 3-button player's armed page brings it back — reported as
  // visible insets, so this is 0 again while it is hidden.
  var chromeHeightPx by remember { mutableStateOf(0) }
  var chromeWidthPx by remember { mutableStateOf(0) }
  var chipRightPx by remember { mutableStateOf(0) }
  // The landscape icon column's intrusion from its own screen edge, in window px.
  var railEndPx by remember { mutableStateOf(0) }
  val isLandscapeUi =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val cutout = WindowInsets.displayCutout
  val cutoutTop = cutout.getTop(density)
  val cutoutBottom = cutout.getBottom(density)
  val navBars = WindowInsets.navigationBars
  val navBottom = navBars.getBottom(density)
  // ONE side inset for the chrome's padding and both published sides: the larger
  // cutout — plus the nav bar's sides, because in landscape the 3-BUTTON bar sits on
  // a side, not the bottom, so a §9-armed page bringing it back would otherwise cover
  // "safe" game UI (and the icon column; the chip, in portrait). Visible insets: all
  // zero while the bars are hidden,
  // and the gesture pill lands in navBottom, so nothing changes outside that one case.
  val sideInsetPx = maxOf(
    cutout.getLeft(density, layoutDirection),
    cutout.getRight(density, layoutDirection),
    navBars.getLeft(density, layoutDirection),
    navBars.getRight(density, layoutDirection),
  )
  var safeTopPx by remember { mutableStateOf(0) }
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
        " s.setProperty('--cp-safe-top', '${cssPx(safeTopPx)}px');" +
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
    railEndPx,
    sideInsetPx,
    cutoutTop,
    cutoutBottom,
    navBottom,
    isLandscapeUi,
    webView,
  ) {
    // ONE horizontal inset for both sides, measured off the chrome's own content —
    // the chip's gutter in portrait, the icon column's extent in landscape. The
    // chrome is padded/placed inside the levelled strip, so this already carries the
    // larger side obstruction — no per-side mirroring left to do.
    //
    // Levelling is parity, not preference: UIKit reports the notch inset on BOTH sides
    // in landscape, so iOS hands the same page a symmetric box. Publishing the lopsided
    // pair here only offered detail a cross-platform controller has to throw away, at
    // the cost of the two apps disagreeing about the same page. In portrait the cutout
    // is on the top edge, so both sides were already equal and this changes nothing.
    val side =
      if (isLandscapeUi) maxOf(railEndPx, sideInsetPx)
      else if (chromeWidthPx > 0) (chromeWidthPx - chipRightPx).coerceAtLeast(sideInsetPx)
      else sideInsetPx
    safeLeftPx = side
    safeRightPx = side
    // Landscape has no bar (the icons live in the side strip), so the game gets the
    // full height back — top is the bare cutout, which is 0 on a mid-edge punch-hole.
    safeTopPx = if (isLandscapeUi) cutoutTop else chromeHeightPx
    safeBottomPx = maxOf(cutoutBottom, navBottom)
    pushSafeZone()
    webView?.requestApplyInsets()
  }

  // Keep status-bar icons contrasting against the (possibly game-colored) bar strip,
  // and hand the appearance back to the theme on the way out. ONE owner for both, and
  // deliberately a DisposableEffect: a LaunchedEffect body is POSTED through
  // AndroidUiDispatcher, so a re-assert scheduled on the way out can land a frame
  // AFTER the teardown has restored the theme value — leaving the launcher's dark UI
  // under a light bar's dark icons until the next configuration change. onDispose runs
  // inline while changes are applied, so apply and restore stay ordered by construction.
  //
  // Keyed on the whole Configuration, not uiMode + orientation: EVERY configuration
  // change re-runs MainActivity.applyEdgeToEdge, which stomps this, and a device
  // rotation within landscape (a §10 game is locked to SENSOR_LANDSCAPE) changes
  // neither of those two fields — so that stomp used to be permanent. Compose updates
  // LocalConfiguration from the same callback, and measurably after the activity's own,
  // so this always re-asserts on top.
  val lightStatusIcons = barTarget.luminance() > 0.5f
  val config = LocalConfiguration.current
  DisposableEffect(lightStatusIcons, config) {
    val window = context.findActivity()?.window
    fun setLightIcons(light: Boolean) {
      window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = light }
    }
    setLightIcons(lightStatusIcons)
    // Re-derived, never a captured value — uiMode no longer recreates the activity, so
    // a theme flipped mid-game must be honored here.
    onDispose { setLightIcons(themeLightBarIcons(context)) }
  }

  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    // The game surface spans the FULL physical screen — the chrome floats above it,
    // and the page keeps its interactive UI inside the published safe zone.
    Box(Modifier.fillMaxSize()) {
      // While the gate holds, the join cover below is the whole screen — the WebView
      // (and with it loadUrl) only comes into existence once the dialog is answered.
      if (lanGateOpen) {
      key(webViewKey) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        // Defined teardown ordering (the view is detached first), unlike a
        // DisposableEffect racing AndroidView's own disposal. Also runs for a
        // renderer-death swap, so the dead instance is destroyed too.
        onRelease = { it.destroy() },
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
            // Gate on the portrait chrome having measured once (the host always
            // enters in portrait), not on the published top — a landscape top is
            // legitimately 0.
            if (chromeHeightPx > 0) {
              val safe = Insets.of(safeLeftPx, safeTopPx, safeRightPx, safeBottomPx)
              val bounds = buildList {
                if (safeTopPx > 0) add(Rect(0, 0, v.width, safeTopPx))
                if (safeBottomPx > 0) add(Rect(0, v.height - safeBottomPx, v.width, v.height))
                if (safeLeftPx > 0) add(Rect(0, 0, safeLeftPx, v.height))
                if (safeRightPx > 0) add(Rect(v.width - safeRightPx, 0, v.width, v.height))
              }
              WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), safe)
                .setDisplayCutout(DisplayCutoutCompat(Rect(safeLeftPx, safeTopPx, safeRightPx, safeBottomPx), bounds))
                .build()
                .toWindowInsets()
                ?.let { v.onApplyWindowInsets(it) }
            }
            WindowInsetsCompat.CONSUMED
          }
          // Re-assert the name on each load (belt-and-suspenders with cpName).
          webViewClient = AllowListWebViewClient(
            allowed,
            // Neither the §9 arming nor the §10 orientation may outlive the page that
            // asked for it — both revert to the launcher's default on every navigation.
            onNavigationStart = {
              systemBackEnabled = false
              landscape = false
            },
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
            onRenderGone = {
              if (!exited.get()) {
                webView = null
                failed = false
                loading = true
                webViewKey++
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

            // JS dialogs are answered silently, matching iOS (which has no dialog
            // chrome at all without a WKUIDelegate): the launcher never shows UI the
            // page conjured, and a looping alert() must not wedge the shell.
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult) =
              true.also { result.confirm() }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult) =
              true.also { result.cancel() }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult) =
              true.also { result.cancel() }
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
      }
      }
      // "Joining…" cover that fades away once the controller has painted.
      // (Qualified: the ColumnScope overload would otherwise shadow this one.)
      androidx.compose.animation.AnimatedVisibility(
        visible = loading,
        enter = fadeIn(),
        exit = fadeOut(tween(300)),
        modifier = Modifier.fillMaxSize(),
      ) {
        JoiningCover(stringResource(R.string.joining_game, displayTitle))
      }
      // Load failed: an opaque cover over the dead page offering retry-in-place (so a
      // transient blip doesn't cost a re-scan). Above the join cover, below the floating
      // chrome. No Leave button — the Leave bar's X already exits. Surface-toned so it
      // sits over the live game page rather than reading as a full screen.
      if (failed) {
        ServerUnreachableRetry(onRetry = retry, background = MaterialTheme.colorScheme.surface)
      }
    }
    // The floating chrome. Landscape: no bar at all — the game keeps the full
    // height, and the two session controls stack in a side strip the levelled
    // safe zone reserves anyway (right when the cutout allows, else left).
    if (isLandscapeUi) {
      LandscapeChrome(
        playerName = profile.name,
        onLeave = leave,
        onEditName = { showProfile = true },
        barColor = barColor,
        contentColor = barContent,
        sideInsetPx = sideInsetPx,
        onRailEnd = { railEndPx = it },
      )
    } else {
      // Portrait: status-bar strip + LEAVE bar over a scrim. Top + horizontal
      // insets only, deliberately: when a keyboard opens the system re-marks the
      // (hidden) nav bar visible, and a nav-tracking inset would move the chrome.
      // The game surface never resizes for anything — the keyboard overlays it,
      // like a video player.
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
          // Top from the bars; horizontal SYMMETRIC rather than per-side. A landscape
          // cutout is on one side only, and padding the chrome by the raw per-side inset
          // put the X and the name chip on a different box than the (levelled) safe zone
          // we publish to the page — the chip sat nearer the edge than any game UI is
          // allowed to. Padding both sides by the larger keeps launcher chrome and page
          // content on the same margin, and makes the gutter measured off the chip below
          // symmetric by construction.
          .windowInsetsPadding(
            WindowInsets.statusBars.union(WindowInsets.displayCutout)
              .only(WindowInsetsSides.Top),
          )
          .padding(horizontal = with(density) { sideInsetPx.toDp() }),
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

// The landscape chrome: Close and the rename affordance stacked at the top-RIGHT
// corner, floating in the strip the levelled side inset (§5) reserves anyway — no
// bar, so the game keeps the full height. Physical sides, not start/end: the
// choice is driven by where the camera is, not by reading direction. Placement
// uses the DETAILED cutout geometry (boundingRects, not just the inset): a
// mid-edge punch-hole sits half way down the side and leaves the corner free, so
// the column stays top-right; a corner camera on the right flips the column to
// the left when that corner is free, and only when both corners are occupied
// does it stay right and drop below the rect. [onRailEnd] reports the column's
// intrusion from its own screen edge (window px) so the host can fold it into
// the levelled published side inset.
@Composable
private fun BoxScope.LandscapeChrome(
  playerName: String,
  onLeave: () -> Unit,
  onEditName: () -> Unit,
  barColor: Color,
  contentColor: Color?,
  sideInsetPx: Int,
  onRailEnd: (Int) -> Unit,
) {
  val view = LocalView.current
  val density = LocalDensity.current
  val buttonPx = with(density) { 48.dp.toPx() }
  val gapPx = with(density) { 4.dp.toPx() }
  // Center the buttons inside the strip when it's wide enough; hug the edge
  // otherwise — the published side inset grows to the column's extent either way,
  // so game UI never sits under a touch target.
  val edgePadPx = ((sideInsetPx - buttonPx) / 2).coerceAtLeast(gapPx)
  val colEndPx = edgePadPx + buttonPx
  val colHeightPx = buttonPx * 2 + gapPx * 2
  // Pick the side, then how far to drop below any cutout rect the column would
  // overlap (keyed on the inset so it re-reads after a rotation or a 180° flip
  // re-dispatches the insets). Right is preferred; a corner camera there flips
  // the column to the left unless the left corner is occupied too.
  val (onRight, dodgePx) = remember(view, sideInsetPx) {
    val rects = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
    fun dodgeFor(right: Boolean): Float {
      var top = 0f
      for (r in rects.sortedBy { it.top }) {
        val overlapsX = if (right) r.right > view.width - colEndPx else r.left < colEndPx
        if (overlapsX && r.top < top + colHeightPx && r.bottom > top) top = r.bottom + gapPx * 2
      }
      return top
    }
    val rightDodge = dodgeFor(right = true)
    if (rightDodge == 0f || dodgeFor(right = false) > 0f) true to rightDodge
    else false to 0f
  }
  val content = contentColor ?: MaterialTheme.colorScheme.onSurface
  val edgePad = with(density) { edgePadPx.toDp() }
  Column(
    Modifier
      .align(if (onRight) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft)
      .absolutePadding(
        left = if (onRight) 0.dp else edgePad,
        right = if (onRight) edgePad else 0.dp,
        top = with(density) { dodgePx.toDp() } + 4.dp,
      )
      .onGloballyPositioned {
        val b = it.boundsInWindow()
        onRailEnd((if (onRight) view.width - b.left else b.right).roundToInt())
      },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    val colors = IconButtonDefaults.iconButtonColors(
      containerColor = barColor.copy(alpha = 0.55f),
      contentColor = content,
    )
    // Provided OUTSIDE the IconButtons, for their ripples: ripple() resolves
    // LocalContentColor at the button's own node — the launcher theme's ambient,
    // not the button's colors.contentColor — so without this a dark-mode launcher
    // draws a white (invisible) ripple on a light game-theme scrim.
    CompositionLocalProvider(LocalContentColor provides content) {
      IconButton(onClick = onLeave, colors = colors) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.leave_game))
      }
      // Icon-only rename affordance; announces the name it edits, like the chip.
      IconButton(onClick = onEditName, colors = colors) {
        Icon(Icons.Filled.Person, contentDescription = playerName.ifBlank { stringResource(R.string.set_name) })
      }
    }
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
  private val onRenderGone: () -> Unit,
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
    // Off-list http(s) → browser, but only a main-frame navigation the player gestured
    // for: a page can mint subframe/scripted navigations at will, and each would
    // otherwise yank the player out of the match into the browser.
    if ((scheme == "http" || scheme == "https") && request.isForMainFrame && request.hasGesture()) {
      openExternally(view.context, url)
    }
    return true // everything not explicitly allowed is blocked from the WebView
  }

  // A network-level failure of the MAIN document (no connection, DNS/connect/timeout —
  // NOT a 4xx/5xx, which means the host answered and renders the server's own body,
  // same as iOS). Subresource failures are the page's own problem and ignored.
  override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
    if (request.isForMainFrame) onConnectionError()
  }

  // The renderer died (OOM kill while backgrounded, or a crash) — returning false
  // here would take the whole app down with it. The WebView instance can't be reused
  // after this; the host swaps in a fresh one and re-issues the join.
  override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
    onRenderGone()
    return true
  }

  // Fade the cover on the first DRAW of the loaded page, not on load: the page's JS
  // can paint noticeably after onPageFinished (seconds, on a cold start), which used
  // to reveal a blank WebView. postVisualStateCallback fires once this DOM state has
  // actually been rendered. Deliberately no time-based fallback — fading the cover
  // before content exists is the bug, not a safety net (a stalled page keeps the
  // honest spinner and Leave stays available; load failures surface the retry cover
  // via onReceivedError).
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
  private val onLandscape: (Boolean) -> Unit,
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

  // The orientation the controller wants right now (CONTRACT.md §10). Not fire-once:
  // a game may run its lobby portrait and its match landscape. Only the literal
  // "landscape" rotates; every other value — including a non-string, which the JS
  // bridge hands over as null — means portrait.
  @JavascriptInterface
  fun setOrientation(mode: String?) {
    val wantsLandscape = mode == "landscape"
    mainHandler.post { onLandscape(wantsLandscape) }
  }
}
