import SwiftUI
import WebKit
import UIKit
import AVFAudio

/// Configures the shared audio session. Deferred out of didFinishLaunching: the call
/// round-trips to the audio server (tens of ms) and nothing needs it until something
/// can actually play.
enum GameAudioSession {
    /// Set once a game host has claimed the session for playback — the trailer's
    /// weaker category must never downgrade it from under a live controller.
    private static var gameConfigured = false

    /// On first game host.
    static func configureOnce() {
        guard !gameConfigured else { return }
        gameConfigured = true
        // Off-main so the nav push into the game never drops frames on it; the page
        // needs seconds of network + boot before it can make any sound, so the
        // category is always set long before first playback.
        DispatchQueue.global(qos: .userInitiated).async {
            // .playback so WebView game sound is audible even with the silent switch
            // on; .mixWithOthers so it layers over the user's music/podcasts instead
            // of stopping them.
            try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
        }
    }

    /// Before the info sheet's gameplay loop plays. The clip is muted, but AVPlayer
    /// still activates the shared session, and the default `.soloAmbient` category is
    /// non-mixing — browsing the catalog would stop the player's music for a clip they
    /// can't hear. `.ambient` mixes by definition and follows the ringer switch. This
    /// is Android's `setAudioFocusRequest(AUDIOFOCUS_NONE)` on the trailer VideoView.
    ///
    /// Awaited (off-main), so the category is in force before a player exists.
    static func configureForMutedTrailer() async {
        guard !gameConfigured else { return }
        await Task.detached(priority: .userInitiated) {
            try? AVAudioSession.sharedInstance().setCategory(.ambient)
        }.value
    }
}

/// Warms the web engine so the first game join doesn't pay WebContent/network
/// process spawn (several hundred ms) on top of the network load. The blank view
/// is retained — dropping it immediately would let its processes die — and
/// released the moment a real game web view exists, since the engine (and
/// WebKit's process cache) stays warm from then on. Mirrors Android's
/// WebViewWarmer (ui/game/WebViewWarmer.kt).
@MainActor enum WebViewWarmer {
    private static var warmed: WKWebView?
    private static var retired = false

    /// Called post-launch, off the first-frame path (RootView.task).
    static func warmUp() {
        guard warmed == nil, !retired else { return }
        let webView = WKWebView(frame: .zero)
        webView.loadHTMLString("", baseURL: nil)
        warmed = webView
    }

    /// A real game web view exists — reclaim the blank view's memory for good.
    static func retire() {
        retired = true
        warmed = nil
    }
}

/// The launcher-published safe zone, in points (CSS px == points on iOS).
struct SafeZone: Equatable {
    var top: Int = 0
    var left: Int = 0
    var right: Int = 0
    var bottom: Int = 0

    var uiEdgeInsets: UIEdgeInsets {
        UIEdgeInsets(top: CGFloat(top), left: CGFloat(left), bottom: CGFloat(bottom), right: CGFloat(right))
    }
}

/// A navigation cancelled on purpose — external links and the cross-doc push cancel in
/// decidePolicyFor, which surfaces in didFail as NSURLErrorCancelled or WebKit's
/// frame-load-interrupted (102). Not a real load failure, so both web hosts ignore it.
func isDeliberateNavigationCancellation(_ error: Error) -> Bool {
    let ns = error as NSError
    if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorCancelled { return true }
    if ns.domain == "WebKitErrorDomain", ns.code == 102 { return true }
    return false
}

/// WKWebView whose safeAreaInsets are synthetic, so viewport-fit=cover pages see
/// the launcher's safe zone (chrome height on top, cutout/gutter elsewhere) via
/// standard `env(safe-area-inset-*)` — contract §7.5 mechanism 1. The `--cp-safe-*`
/// CSS vars stay the authoritative channel.
final class CPWebView: WKWebView {
    var syntheticSafeAreaInsets: UIEdgeInsets = .zero {
        didSet {
            if oldValue != syntheticSafeAreaInsets { safeAreaInsetsDidChange() }
        }
    }

    override var safeAreaInsets: UIEdgeInsets { syntheticSafeAreaInsets }
}

/// Hosts a game's remote controller as a TOP-LEVEL web view (the game's
/// `frame-ancestors` CSP doesn't apply) with the navigation allow-list as the
/// client-side trust boundary — the join URL can originate from an untrusted
/// relay lookup.
struct GameWebView: UIViewRepresentable {
    let joinUrl: String
    let allowedDomains: [String]       // already lowercased, includes CP.launcherHost
    let playerName: String             // "" = blank → never injected
    let safeZone: SafeZone             // points == CSS px
    let onLoaded: () -> Void           // first painted frame (the injected __firstFrame signal)
    let onGameEnd: (String?) -> Void   // fire-once
    let onLeave: () -> Void            // an armed back gesture the page didn't consume (§9)
    let onLandscape: (Bool) -> Void    // the orientation the page is asking for (§10)
    let onRendererGone: () -> Void     // web-content process died — re-issue the load
    @Binding var failed: Bool          // main-doc load failed — drives the retry overlay
    let reloadToken: Int               // bumped by Retry → re-issue the join request
    let onThemeChanged: (PageTheme) -> Void
    let onTitleChanged: (String) -> Void  // the page's <title>, trimmed & non-empty

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> CPWebView {
        let coordinator = context.coordinator

        GameAudioSession.configureOnce()
        WebViewWarmer.retire()

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []   // game sounds must autoplay
        config.websiteDataStore = .default()                   // localStorage parity
        // The bridge must exist before load or the page won't see it.
        config.userContentController.addUserScript(
            WKUserScript(source: GameHostJS.bridgeShim, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        )
        config.userContentController.addUserScript(
            WKUserScript(source: GameHostJS.firstFrameSignal, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        )
        config.userContentController.add(coordinator, name: "cpHost")

        let webView = CPWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        // Match the dark chrome while the page is blank — kills the white flash.
        // CPPalette.dark.surface (#110F17) — keep in sync with CPTheme.swift.
        let surface = UIColor(red: 0x11 / 255.0, green: 0x0F / 255.0, blue: 0x17 / 255.0, alpha: 1.0)
        webView.backgroundColor = surface
        webView.scrollView.backgroundColor = surface
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        webView.allowsBackForwardNavigationGestures = false
        // Never expose a player's live game socket to the Web Inspector in production.
        #if DEBUG
        webView.isInspectable = true
        #endif
        webView.navigationDelegate = coordinator
        webView.uiDelegate = coordinator
        webView.syntheticSafeAreaInsets = safeZone.uiEdgeInsets
        // The Leave bar and home rejoin card follow the page's own <title> — KVO, not
        // a didFinish sample, because a controller SPA typically sets its title well
        // after the load finishes (once the relay socket connects).
        coordinator.titleObservation = webView.observe(\.title, options: [.new]) { [weak coordinator] webView, _ in
            let title = webView.title
            DispatchQueue.main.async { coordinator?.titleChanged(title) }
        }
        // The system back gesture, off until the page arms it (CONTRACT.md §9). WebKit's
        // own allowsBackForwardNavigationGestures can't serve here: it drives web history
        // and never reaches back(), and the launcher — not the page's history — owns the
        // exit. Disabled, the recognizer is inert, so edge swipes are gameplay input;
        // that's the Android gesture-exclusion rects' counterpart. cancelsTouchesInView
        // (on by default) cancels the page's touch once the swipe is recognized.
        let backEdge = UIScreenEdgePanGestureRecognizer(
            target: coordinator, action: #selector(Coordinator.handleBackEdgePan(_:))
        )
        backEdge.edges = UIApplication.shared.userInterfaceLayoutDirection == .rightToLeft ? .right : .left
        backEdge.isEnabled = false
        webView.addGestureRecognizer(backEdge)
        coordinator.backEdgeGesture = backEdge
        coordinator.lastInjectedName = playerName
        coordinator.lastPushedZone = safeZone
        // Going home must drop the relay socket (Android gets this for free from the
        // process freezer; iOS keeps sockets alive in the out-of-process network
        // stack) — the page's own pagehide/visibilitychange handlers do the work.
        coordinator.observeBackgrounding(of: webView)
        if let url = URL(string: joinUrl) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ webView: CPWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self  // always-current closures

        if playerName != coordinator.lastInjectedName {
            coordinator.lastInjectedName = playerName
            // Live rename (contract §2) — blank names are never injected.
            if let js = GameHostJS.nameInjection(name: playerName) {
                webView.evaluateJavaScript(js, completionHandler: nil)
            }
        }

        if safeZone != coordinator.lastPushedZone {
            coordinator.lastPushedZone = safeZone
            // env() re-derives from the synthetic insets — the iOS analog of
            // requestApplyInsets; the vars remain the source of truth.
            webView.syntheticSafeAreaInsets = safeZone.uiEdgeInsets
            webView.evaluateJavaScript(GameHostJS.safeZonePush(safeZone), completionHandler: nil)
        }

        // A Retry tap bumps reloadToken. Reload the page the controller is actually on
        // when one ever committed (parity with Android's reload()); when nothing did
        // (offline at join — reload() is a no-op there) re-issue the join request.
        if reloadToken != coordinator.lastReloadToken {
            coordinator.lastReloadToken = reloadToken
            if webView.url != nil {
                webView.reload()
            } else if let url = URL(string: joinUrl) {
                webView.load(URLRequest(url: url))
            }
        }
    }

    /// Tear the web view down so the game's WebSocket/audio fully stop the moment we pop.
    static func dismantleUIView(_ webView: CPWebView, coordinator: Coordinator) {
        coordinator.isTearingDown = true
        coordinator.titleObservation = nil
        NotificationCenter.default.removeObserver(coordinator)
        webView.stopLoading()
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "cpHost")
        webView.configuration.userContentController.removeAllUserScripts()
        if let blank = URL(string: "about:blank") {
            webView.load(URLRequest(url: blank))
        }
    }

    @MainActor final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        var parent: GameWebView
        var isTearingDown = false
        var lastInjectedName = ""
        var lastPushedZone = SafeZone()
        var lastReloadToken = 0
        var titleObservation: NSKeyValueObservation?
        // Fire-once for the game-reported end — a game spamming gameEnded must pop
        // home only once. (A load failure is NOT terminal: it flips `failed` for the
        // retry overlay, so it doesn't gate on this.)
        private var didEnd = false

        // Weak: the coordinator must not extend the web view's life past dismantle.
        // Set once from makeUIView.
        private weak var hostedWebView: CPWebView?
        // The armed state IS the recognizer's isEnabled — no separate flag to drift.
        weak var backEdgeGesture: UIScreenEdgePanGestureRecognizer?
        private static let minBackTravel: CGFloat = 60     // pt
        private static let minBackFling: CGFloat = 300     // pt/s

        init(parent: GameWebView) {
            self.parent = parent
        }

        /// Synthesize `pagehide` into the page when the app is backgrounded, so the
        /// game closes its relay socket (CONTRACT.md §7; see GameHostJS.dispatchPageHide
        /// for the full why). No foreground counterpart: the engine fires the real
        /// `visibilitychange` → visible, which is the game's reconnect trigger.
        /// didEnterBackground, not willResignActive: Control Center / notification
        /// pulls and app-switcher peeks shouldn't churn the connection. The eval runs
        /// inside the background grace window, before the content process suspends.
        func observeBackgrounding(of webView: CPWebView) {
            hostedWebView = webView
            NotificationCenter.default.addObserver(
                self, selector: #selector(appDidEnterBackground),
                name: UIApplication.didEnterBackgroundNotification, object: nil
            )
        }

        @objc private func appDidEnterBackground() {
            hostedWebView?.evaluateJavaScript(GameHostJS.dispatchPageHide, completionHandler: nil)
        }

        /// A completed edge swipe while the page has armed system back (CONTRACT.md §9).
        /// The page gets first refusal via `back()`; anything but a literal `true` means
        /// it didn't consume the gesture, and the launcher leaves — the same exit the
        /// Leave bar's X takes. The evaluate is async, so the shell simply stays put
        /// until the answer arrives. Disarms on the way out so a second swipe landing
        /// during teardown can't pop twice.
        @objc func handleBackEdgePan(_ gesture: UIScreenEdgePanGestureRecognizer) {
            guard gesture.state == .ended, !isTearingDown else { return }
            let travel = abs(gesture.translation(in: gesture.view).x)
            let fling = abs(gesture.velocity(in: gesture.view).x)
            // A flinched swipe is not a back. UIKit exposes no threshold of its own for
            // this, so these are empirical: far enough that a thumb resting on the edge
            // can't trigger it, loose enough that a fast confident flick still counts.
            guard travel > Self.minBackTravel || fling > Self.minBackFling else { return }
            hostedWebView?.evaluateJavaScript(GameHostJS.deliverBack) { [weak self] result, _ in
                guard let self, result as? Bool != true else { return }
                self.backEdgeGesture?.isEnabled = false
                self.parent.onLeave()
            }
        }

        /// The trust boundary. Only https navigations to an allow-listed domain stay
        /// in-app; off-list http(s) links open in the system browser (silently doing
        /// nothing on failure), and any other scheme is refused outright. Governs
        /// frame navigations only — subresources and the relay WebSocket are unaffected.
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }
            if isTearingDown, url.absoluteString == "about:blank" {
                decisionHandler(.allow)
                return
            }
            let scheme = url.scheme?.lowercased()
            if scheme == "https", parent.allowedDomains.contains(where: { hostInDomain(url.host, $0) }) {
                decisionHandler(.allow)
                return
            }
            #if DEBUG
            // Debug only: keep http(s) navigations to a LAN dev host in-app (see isPrivateHost).
            if (scheme == "http" || scheme == "https"), isPrivateHost(url.host) {
                decisionHandler(.allow)
                return
            }
            #endif
            if scheme == "http" || scheme == "https" {
                // Off-list (plain http is never in-app, even on an allowed domain) →
                // browser, but only a main-frame link the player actually tapped: a
                // page can mint subframe/scripted navigations at will, and each would
                // otherwise yank the player out of the match. The rest cancel silently.
                decisionHandler(.cancel)
                if navigationAction.navigationType == .linkActivated,
                   navigationAction.targetFrame?.isMainFrame != false {
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                }
                return
            }
            // javascript:, file:, custom schemes, … — blocked entirely.
            decisionHandler(.cancel)
        }

        /// The main document failed to load at the network level — no connection, host
        /// unreachable, DNS/TLS/timeout. (An HTTP 4xx/5xx is a *successful* navigation
        /// to WebKit and shows the server's body, so it never lands here.) Provisional =
        /// the initial connection never committed (the offline-at-join case); the plain
        /// didFail = a committed load dropped. Both bail home with a message rather than
        /// leave a dead spinner up.
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
                     withError error: Error) {
            reportLoadFailure(error)
        }

        /// Neither the §9 arming nor the §10 orientation may outlive the page that asked
        /// for it — both revert to the launcher's default here. Main frame only, and not
        /// fired for same-document navigations, so a page that pushes history mid-session
        /// keeps both.
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            backEdgeGesture?.isEnabled = false
            parent.onLandscape(false)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            reportLoadFailure(error)
        }

        /// The web-content process died (OOM kill while backgrounded, or a crash).
        /// Recover on our own rather than parking on the retry cover: the fix is a
        /// reload, the player asked for nothing, and Android already re-creates its
        /// WebView and re-issues the join here. Without this the player would face a
        /// blank page whose join cover already faded.
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            guard !isTearingDown, !didEnd else { return }
            parent.onRendererGone()
        }

        /// No popups: a target=_blank / window.open navigation loads in THIS web view
        /// (vetted by the same decidePolicyFor allow-list), matching Android's
        /// single-window WebView instead of silently discarding the click.
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if navigationAction.targetFrame == nil {
                webView.load(navigationAction.request)
            }
            return nil
        }

        /// The page's own name (ground truth over the manifest): drives the Leave bar
        /// and feeds the home rejoin card, so games not in the bundled manifest show a
        /// real name instead of the generic fallback. putTitle returns the sanitized
        /// text so the bar shows exactly what the card stores.
        func titleChanged(_ raw: String?) {
            guard !isTearingDown, let raw, let clean = RecentRoomStore.putTitle(raw) else { return }
            parent.onTitleChanged(clean)
        }

        private func reportLoadFailure(_ error: Error) {
            // Ignore our own teardown and a game already ended.
            guard !isTearingDown, !didEnd else { return }
            if isDeliberateNavigationCancellation(error) { return }
            // Not terminal — surface the in-place retry overlay; Retry re-issues the load.
            DispatchQueue.main.async { self.parent.failed = true }
        }

        /// Every page finish: re-assert the name (belt-and-suspenders with cpName),
        /// re-install the theme observer (idempotent), re-push the safe zone.
        /// Deliberately does NOT touch the cover: its fade is driven solely by the
        /// injected __firstFrame signal, because didFinish means "loaded" and can
        /// precede first paint by seconds on a cold start. No time-based fallback
        /// either — one fading the cover before content exists is the bug, not a
        /// safety net (a stalled page keeps the honest spinner, and Leave stays
        /// available in the chrome above it; load failures surface the retry cover
        /// through the error callbacks). The page's <title> needs nothing here — the
        /// KVO observer set up in makeUIView tracks it continuously.
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            if let js = GameHostJS.nameInjection(name: parent.playerName) {
                webView.evaluateJavaScript(js, completionHandler: nil)
            }
            webView.evaluateJavaScript(GameHostJS.watchPageTheme, completionHandler: nil)
            webView.evaluateJavaScript(GameHostJS.safeZonePush(parent.safeZone), completionHandler: nil)
        }

        /// The game→launcher half of the contract (v1). All arguments are untrusted page input.
        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard message.name == "cpHost",
                  let body = message.body as? [String: Any],
                  let type = body["type"] as? String
            else { return }
            switch type {
            case "__firstFrame":
                // Launcher-injected (firstFrameSignal), not a contract message: the
                // page's first composited frame is up — fade the "Joining…" cover.
                parent.onLoaded()
            case "gameEnded":
                guard !didEnd else { return }
                didEnd = true
                parent.onGameEnd(body["value"] as? String)  // null tolerated → generic message
            case "themeChanged":
                // Not fire-once: themes change repeatedly. Parsed strictly.
                parent.onThemeChanged(parsePageTheme(body["value"] as? String))
            case "enableSystemBack":
                // Not fire-once: games arm and disarm repeatedly (a dialog opening and
                // closing). The shim already coerced to a boolean, so only "true" arms.
                backEdgeGesture?.isEnabled = (body["value"] as? String) == "true"
            case "setOrientation":
                // Not fire-once: a game may run its lobby portrait and its match
                // landscape. The shim already narrowed to the two legal keywords.
                parent.onLandscape((body["value"] as? String) == "landscape")
            default:
                break
            }
        }
    }
}
