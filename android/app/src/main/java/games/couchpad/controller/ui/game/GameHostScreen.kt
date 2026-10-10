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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import org.json.JSONObject
import games.couchpad.controller.R
import games.couchpad.controller.BuildConfig
import games.couchpad.controller.data.LAUNCHER_HOST
import games.couchpad.controller.data.ProfileStore
import games.couchpad.controller.data.NearbyAdvertiser
import games.couchpad.controller.data.RecentRoomStore
import games.couchpad.controller.data.clearLocalNetworkAsked
import games.couchpad.controller.data.localNetworkPermanentlyDenied
import games.couchpad.controller.data.localNetworkPermissionGranted
import games.couchpad.controller.data.markLocalNetworkAsked
import games.couchpad.controller.data.hostInDomain
import games.couchpad.controller.data.isPrivateHost
import games.couchpad.controller.theme.CouchPadTheme
import games.couchpad.controller.theme.contentColorOn
import games.couchpad.controller.ui.components.JoiningCover
import games.couchpad.controller.ui.main.ProfileSheet
import games.couchpad.controller.ui.components.ServerUnreachableRetry
import games.couchpad.controller.ui.components.denyLocalFileAccess
import games.couchpad.controller.ui.components.findActivity
import games.couchpad.controller.ui.components.gestureNavEnabled
import games.couchpad.controller.ui.components.hideNavigationBar
import games.couchpad.controller.ui.components.themeLightBarIcons
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts a game's remote controller in a native WebView spanning the whole screen,
 * with no launcher chrome over it: the page draws its own close (§3), and only its
 * rename button opens the launcher's name sheet (CONTRACT.md §2). As a TOP-LEVEL
 * WebView (not an iframe), the game's `frame-ancestors` CSP doesn't apply.
 * [allowedHosts] is the navigation allow-list — the client-side trust boundary, since
 * the join URL can originate from an untrusted relay lookup.
 */
@Composable
fun GameHostScreen(
  joinUrl: String,
  title: String,
  allowedHosts: List<String>,
  onLeave: () -> Unit,
  onGameEnd: (reason: String?) -> Unit,
) {
  // The launcher's own surfaces here (join cover, retry, the blank page behind them)
  // follow the system's light/dark mode, like home: the launcher can't know a game's
  // colors before its page has loaded, and not every game is in the manifest.
  CouchPadTheme {
    GameHostContent(joinUrl, title, allowedHosts, onLeave, onGameEnd)
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
) {
  val context = LocalContext.current
  val view = LocalView.current
  var webView by remember { mutableStateOf<WebView?>(null) }
  // Bumped when the renderer dies — a WebView can't be reused after that, so the
  // key() below swaps in a fresh one that re-issues the join.
  var webViewKey by remember { mutableStateOf(0) }
  val allowed = remember(allowedHosts) { (allowedHosts + LAUNCHER_HOST).map { it.lowercase() } }
  var loading by remember { mutableStateOf(true) }
  // The main document failed to load (no connection / host unreachable) — shows the
  // in-place retry overlay instead of a dead join spinner.
  var failed by remember { mutableStateOf(false) }
  // Is the page dark, per its color-scheme meta (CONTRACT.md §4)? Null until the
  // launcher's observer reports.
  var pageDark by remember { mutableStateOf<Boolean?>(null) }
  // The page's editName() requests (CONTRACT.md §2), oldest first: the head's sheet is
  // open, and it is answered when that sheet has slid out. A request made meanwhile —
  // the page is only tappable while a sheet slides out — opens the next sheet then, and
  // the page matches answers to its Promises by that order.
  val nameRequests = remember { mutableStateListOf<NameRequest>() }
  // The join has been loading longer than a normal one takes (see the cover's close).
  var slowLoad by remember { mutableStateOf(false) }
  LaunchedEffect(loading) {
    slowLoad = false
    if (loading) {
      delay(SLOW_LOAD_MS)
      slowLoad = true
    }
  }
  // Has the page armed the system back gesture (CONTRACT.md §9)? Default false —
  // the safe state: edges excluded, the page's own close the only exit. Reset on
  // every navigation.
  var systemBackEnabled by remember { mutableStateOf(false) }
  // Has the page asked for landscape (CONTRACT.md §10)? Default false — the launcher's
  // portrait.
  var landscape by remember { mutableStateOf(false) }
  // Has the document now loading exercised §10? Cleared when a new navigation starts —
  // it is what lets the launcher HOLD the orientation across that navigation instead of
  // snapping back to portrait while the next page is still on its way.
  var orientationAsked by remember { mutableStateOf(false) }
  // The page has painted its first frame. The join cover lifts on that — unless the page
  // asked for landscape and the window hasn't turned yet: the turn takes a few hundred
  // ms, and lifting earlier shows the page portrait-shaped mid-turn (a landscape-only
  // game's "turn your phone" overlay, say).
  var painted by remember { mutableStateOf(false) }
  val isLandscapeUi = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  LaunchedEffect(loading, painted, landscape, isLandscapeUi) {
    // Split-screen ignores orientation requests, so waiting there would never end.
    val turned = !landscape || isLandscapeUi || context.findActivity()?.isInMultiWindowMode == true
    if (loading && painted && turned) loading = false
  }
  val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
  // The bridge/WebView client outlive recompositions but must call the CURRENT
  // callbacks — hence rememberUpdatedState.
  val currentOnLeave by rememberUpdatedState(onLeave)
  val currentOnGameEnd by rememberUpdatedState(onGameEnd)
  // One-shot guard for the two TERMINAL exits — a leave and a game-reported end.
  // Whoever fires first wins; the loser (incl. a stray gameEnded during teardown)
  // no-ops, so we never pop the back stack twice. A load failure is NOT terminal: it
  // shows the retry overlay in place, and only its close trips this.
  val exited = remember { AtomicBoolean(false) }
  val leave = { if (exited.compareAndSet(false, true)) currentOnLeave() }
  // Retry the controller load in place (no re-scan): clear the error, bring the join
  // cover back, reload. A dead renderer lands here too, before its WebView is swapped.
  val retry = {
    failed = false
    loading = true
    painted = false
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
      onLeave = leave,
      onSystemBackEnabled = { systemBackEnabled = it },
      onLandscape = {
        orientationAsked = true
        landscape = it
      },
      haptics = GameHaptics(context),
    )
  }
  val nameBridge = remember {
    NameBridge(
      currentName = { ProfileStore.load(context).name },
      onEditName = {
        webView?.evaluateJavascript(READ_SHEET_COLORS_JS) { nameRequests.add(NameRequest(parseSheetColors(it))) }
      },
    )
  }
  val schemeBridge = remember { SchemeBridge { pageDark = it } }

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

  // The room is not idle while its player is looking at it: hold the recent-room slot
  // open for as long as the host is up, so a long session can't age out from under the
  // rejoin card (RecentRoomStore.inRoom).
  DisposableEffect(Unit) {
    RecentRoomStore.enter()
    onDispose { RecentRoomStore.leave() }
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
  // Arming (CONTRACT.md §9) brings the bar back for as long as the page stays armed —
  // see [hostShowsNavBar] for when.
  val hostShowsNavBar = hostShowsNavBar(
    armed = systemBackEnabled,
    landscape = isLandscapeUi,
    gestureNav = gestureNavEnabled(context),
  )
  LaunchedEffect(hostShowsNavBar) {
    val window = context.findActivity()?.window ?: return@LaunchedEffect
    if (hostShowsNavBar) {
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
  // Games need no change either way: env(safe-area-inset-top) is already live and §10
  // already tells them the zone reshapes when it turns.
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

  fun watchPageScheme() {
    webView?.evaluateJavascript(WATCH_PAGE_SCHEME_JS, null)
  }

  // Settle the page's pending editName() Promise (CONTRACT.md §2): the saved name, or
  // null when the sheet was dismissed.
  fun settleName(name: String?) {
    val arg = name?.let(JSONObject::quote) ?: "null"
    webView?.evaluateJavascript("window.__cpNameResult && window.__cpNameResult($arg);", null)
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

  // A join link (re-scan) pops this host, but the activity resumes on it first and the
  // page would reconnect just before the pop drops it — the display sees join, leave,
  // join. onNewIntent precedes onResume, so pause the WebView (reports the page hidden).
  // dataString != null is what MainActivity treats as a deep link.
  DisposableEffect(Unit) {
    val activity = context.findActivity() as? ComponentActivity
    val listener = Consumer<Intent> { if (it.dataString != null) webView?.onPause() }
    activity?.addOnNewIntentListener(listener)
    onDispose { activity?.removeOnNewIntentListener(listener) }
  }

  // Old WebViews map no system-bar insets into env(safe-area-inset-*) — M136 added
  // that for a full-screen WebView (Chromium's android_webview/docs/insets.md). Such a
  // WebView is kept out from under the bars instead, so the page needs no insets.
  val legacyInsets = remember { webViewMajorVersion(context) < 136 }

  // Keep status-bar icons contrasting against the page, and hand the appearance back to
  // the theme on the way out. While the join or retry cover is up the icons sit on it,
  // so they follow the cover's (system) mode — a server's error page under the retry
  // cover declares no color-scheme of its own. The page decides once it shows.
  //
  // ONE owner for both, and deliberately a DisposableEffect: a LaunchedEffect body is
  // POSTED through AndroidUiDispatcher, so a re-assert scheduled on the way out can land
  // a frame AFTER the teardown has restored the theme value — leaving home under the
  // game's icon color until the next configuration change. onDispose runs inline while
  // changes are applied, so apply and restore stay ordered by construction.
  //
  // Keyed on the whole Configuration, not uiMode + orientation: EVERY configuration
  // change re-runs MainActivity.applyEdgeToEdge, which stomps this, and a device
  // rotation within landscape (a §10 game is locked to SENSOR_LANDSCAPE) changes
  // neither of those two fields — so that stomp used to be permanent. Compose updates
  // LocalConfiguration from the same callback, and measurably after the activity's own,
  // so this always re-asserts on top.
  val coverDark = isSystemInDarkTheme()
  val lightStatusIcons = !(if (loading || failed) coverDark else pageDark ?: coverDark)
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
    // The game surface spans the FULL physical screen; the page keeps its interactive
    // UI inside env(safe-area-inset-*).
    Box(Modifier.fillMaxSize()) {
      // While the gate holds, the join cover below is the whole screen — the WebView
      // (and with it loadUrl) only comes into existence once the dialog is answered.
      if (lanGateOpen) {
      key(webViewKey) {
      AndroidView(
        modifier = Modifier
          .fillMaxSize()
          .then(if (legacyInsets) Modifier.windowInsetsPadding(barsInsets(hostShowsNavBar)) else Modifier),
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
          // Match the join cover while the page is blank — kills the flash.
          setBackgroundColor(surfaceArgb)
          // The REAL insets reach the page as env(safe-area-inset-*), with two edits,
          // zeroed rather than consumed (consumed insets never reach WebView at all):
          // - The nav bar only counts while the host itself shows it (armed, above). The
          //   system also brings a hidden bar back for as long as a keyboard is up, and
          //   the game must not squash and spring back with it.
          // - The keyboard only counts while it is the page's own: the name sheet is a
          //   separate window, so this one is unfocused while ITS keyboard is up, and
          //   the game under the sheet must not shrink with it.
          ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val nav = WindowInsetsCompat.Type.navigationBars()
            val ime = WindowInsetsCompat.Type.ime()
            val pageInsets = WindowInsetsCompat.Builder(insets).apply {
              val landscape = v.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
              if (!hostShowsNavBar(systemBackEnabled, landscape, gestureNavEnabled(ctx))) {
                setInsets(nav, Insets.NONE)
              }
              if (!(v.hasWindowFocus() && v.onCheckIsTextEditor())) {
                setInsets(ime, Insets.NONE)
                setVisible(ime, false)
              }
            }.build()
            ViewCompat.onApplyWindowInsets(v, pageInsets)
          }
          webViewClient = AllowListWebViewClient(
            allowed,
            // Neither the §9 arming nor the §10 orientation may outlive the page that
            // asked for it — but they expire differently, because one is input safety
            // and the other is cosmetic. Back DISARMS here: an unloaded page must never
            // inherit a live exit gesture. Orientation is only re-armed for the verdict
            // in onLoaded — the device HOLDS what it has until the incoming document has
            // had its say. Reverting here instead rotates the phone to portrait for the
            // length of a page load and straight back again the moment the new page's
            // §10 call lands — which is every self-reload a landscape game does.
            onNavigationStart = {
              systemBackEnabled = false
              orientationAsked = false
              pageDark = null
            },
            onLoaded = {
              painted = true
              // The §10 verdict for this document: a page that has said nothing by the
              // time it is loaded gets the launcher's portrait. A LATER call still
              // rotates — §10 supports deciding once the socket connects — this only
              // closes the window where a silent page could inherit its predecessor's
              // landscape.
              if (!orientationAsked) landscape = false
              // Chromium delivers orientation events only to a focused page, and
              // nothing focuses the WebView until the player's first touch.
              requestFocus()
              watchPageScheme()
            },
            // The controller page itself couldn't load (no connection / host
            // unreachable) — show the retry overlay in place, not a dead spinner.
            // (Ignored once the player has left: the WebView is being torn down.)
            onConnectionError = {
              if (!exited.get()) {
                loading = false
                // Nothing is going to ask now — the held orientation would otherwise
                // strand the retry cover sideways with no page behind it.
                if (!orientationAsked) landscape = false
                failed = true
              }
            },
            onRenderGone = {
              if (!exited.get()) {
                webView = null
                retry()
                webViewKey++
              }
            },
          )
          webChromeClient = object : WebChromeClient() {
            // The page's own name (ground truth over the manifest) feeds the home
            // rejoin card. Fires on every document.title change, so late SPA renames
            // are picked up too.
            override fun onReceivedTitle(view: WebView?, title: String?) {
              // While the load has failed the title is WebView's own error page
              // ("Webpage not available") — it would pollute the room card, so ignore
              // it until a real page loads.
              if (failed || title == null) return
              RecentRoomStore.putTitle(title)
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
          // `name` and a Promise-returning editName() need a JS wrapper, installed before
          // any page script. A WebView too old for document-start scripts gets the raw
          // interface as CouchPadHost, without those two — so a game feature-detecting
          // them falls back to its own name UI rather than a sheet it can't hear back from.
          if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            addJavascriptInterface(hostBridge, "__cpHost")
            addJavascriptInterface(nameBridge, "__cpName")
            WebViewCompat.addDocumentStartJavaScript(this, HOST_SHIM_JS, setOf("*"))
          } else {
            addJavascriptInterface(hostBridge, "CouchPadHost")
          }
          addJavascriptInterface(schemeBridge, "__cpScheme")
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
        JoiningCover(stringResource(R.string.joining_game, title))
      }
      // Load failed: an opaque cover over the dead page offering retry-in-place (so a
      // transient blip doesn't cost a re-scan). Surface-toned so it sits over the live
      // game page rather than reading as a full screen.
      if (failed) {
        ServerUnreachableRetry(onRetry = retry, background = MaterialTheme.colorScheme.surface)
      }
    }
    // Until a page is up there is no page close to tap, so the launcher offers its own:
    // a stalled join or a failed load must never trap the player. A normal join is over
    // in a second or two, so on the join cover it only fades in once loading runs long;
    // the retry cover has it at once.
    androidx.compose.animation.AnimatedVisibility(
      visible = failed || (loading && slowLoad),
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      IconButton(
        onClick = leave,
        modifier = Modifier
          .windowInsetsPadding(
            barsInsets(hostShowsNavBar).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
          )
          .padding(4.dp),
      ) {
        Icon(
          Icons.Filled.Close,
          contentDescription = stringResource(R.string.leave_game),
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }

  // The launcher's own name sheet, in the page's scheme, theme-color and accent-color so
  // it reads as part of the game.
  nameRequests.firstOrNull()?.let { request ->
    // Keyed per request, so the next sheet starts fresh rather than as the closed one.
    key(request) {
      val colors = request.colors
      CouchPadTheme(darkTheme = pageDark != false) {
        val scheme = MaterialTheme.colorScheme
        val accented = colors.accent?.let { scheme.copy(primary = it, onPrimary = contentColorOn(it)) } ?: scheme
        MaterialTheme(colorScheme = accented) {
          ProfileSheet(
            initial = ProfileStore.load(context),
            surfaceTint = colors.surface,
            onDismiss = {
              nameRequests.remove(request)
              settleName(null)
            },
            onSave = { saved ->
              ProfileStore.save(context, saved)
              nameRequests.remove(request)
              settleName(saved.name)
            },
          )
        }
      }
    }
  }
}

/** How long a join may load before its cover offers a close: past a normal join (a second
 * or two), well short of the web view's own timeout (half a minute or more). */
private const val SLOW_LOAD_MS = 3_000L

/** One editName() request: the page's colors for its sheet, read when it asked. Compared
 * by identity, so two requests with the same colors are still two sheets. */
private class NameRequest(val colors: SheetColors)

/**
 * Whether the host shows the nav bar: only while the page has armed back (CONTRACT.md §9).
 * Then always for a 3-BUTTON player, whose back is a button a hidden bar doesn't have.
 * Under gesture navigation, in portrait only: the visible handle tells the player back is
 * available and lets the first edge swipe go back (a hidden bar spends it on the transient
 * reveal), at the cost of a short bottom inset. In landscape it stays hidden — it would come
 * off the axis a landscape controller has least of, and the hidden bar's edge swipe still
 * works.
 */
private fun hostShowsNavBar(armed: Boolean, landscape: Boolean, gestureNav: Boolean): Boolean =
  armed && (!gestureNav || !landscape)

/** What covers the screen's edges: the status bar, the cutout, and the nav bar while the
 * host shows it. */
@Composable
private fun barsInsets(hostShowsNavBar: Boolean): WindowInsets =
  WindowInsets.statusBars
    .union(WindowInsets.displayCutout)
    .union(if (hostShowsNavBar) WindowInsets.navigationBars else WindowInsets(0))

/** The WebView's Chromium major version; 0 when it can't be read, which counts as old. */
private fun webViewMajorVersion(context: Context): Int =
  WebViewCompat.getCurrentWebViewPackage(context)?.versionName
    ?.substringBefore('.')?.toIntOrNull() ?: 0

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

  // A network-level failure of the MAIN document (no connection, DNS/connect/timeout).
  // Subresource failures are the page's own problem and ignored.
  override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
    if (request.isForMainFrame) onConnectionError()
  }

  // The MAIN document came back as an HTTP error (404, 500, 502…). The server's error
  // page has no close, and with no launcher chrome over the game it would strand the
  // player — so it gets the same retry cover, with its close, as a lost connection. A
  // failing favicon, script or image is the page's own business, same as above.
  override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
    if (request.isForMainFrame && errorResponse.statusCode >= 400) onConnectionError()
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
 * Launcher-internal, exposed as `window.__cpScheme`: fed only by the launcher's own
 * color-scheme observer (WATCH_PAGE_SCHEME_JS), kept off `CouchPadHost` so games never
 * see it. Not fire-once: a page may switch scheme. Typed Boolean, so anything else reads
 * false.
 */
private class SchemeBridge(private val onChanged: (Boolean) -> Unit) {
  private val mainHandler = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun changed(dark: Boolean) {
    mainHandler.post { onChanged(dark) }
  }
}

/**
 * Backs `CouchPadHost.name` and `editName()` (CONTRACT.md §1–2), exposed as `__cpName` for
 * HOST_SHIM_JS only — never on its own, since neither member works without the wrapper.
 */
private class NameBridge(
  private val currentName: () -> String,
  private val onEditName: () -> Unit,
) {
  private val mainHandler = Handler(Looper.getMainLooper())

  // Always the stored name, read when the page asks.
  @JavascriptInterface
  fun getName(): String = currentName()

  // Opens the launcher's name sheet; the shim's Promise is settled by settleName. Not
  // fire-once: the player may rename any number of times.
  @JavascriptInterface
  fun editName() {
    mainHandler.post { onEditName() }
  }
}

/**
 * The game→launcher half of the contract (v1), exposed as `__cpHost` and wrapped into
 * `window.CouchPadHost` by HOST_SHIM_JS.
 * Runs on WebView's JS bridge thread, so hop to main before touching Compose state.
 * gameEnded is fire-once — a queued second call (or a game spamming it) must not
 * pop extra nav entries. All arguments are untrusted page input.
 */
private class CouchPadHostBridge(
  private val onGameEnded: (String?) -> Unit,
  private val onLeave: () -> Unit,
  private val onSystemBackEnabled: (Boolean) -> Unit,
  private val onLandscape: (Boolean) -> Unit,
  private val haptics: GameHaptics,
) {
  private val fired = AtomicBoolean(false)
  private val mainHandler = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun gameEnded(reason: String?) {
    if (!fired.compareAndSet(false, true)) return
    mainHandler.post { onGameEnded(reason) }
  }

  // The page's own close (CONTRACT.md §3). The host's exit guard makes it fire-once
  // together with gameEnded.
  @JavascriptInterface
  fun leave() {
    mainHandler.post { onLeave() }
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

  // A named haptic primitive at a strength (CONTRACT.md §12). Not fire-once, and the
  // highest-rate call on the bridge — a controller buzzes on nearly every tap — so it
  // plays right here on the bridge thread instead of queueing behind the UI.
  @JavascriptInterface
  fun haptic(primitive: String?, scale: Double) {
    haptics.play(primitive, scale)
  }
}
