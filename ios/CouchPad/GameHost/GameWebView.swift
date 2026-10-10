import SwiftUI
import WebKit
import UIKit
import AVFAudio

/// Configures the shared audio session. Deferred out of didFinishLaunching: the call
/// round-trips to the audio server (tens of ms) and nothing needs it until something
/// can actually play.
enum GameAudioSession {
    /// Every category write goes through this one serial queue, and both latches below
    /// are ITS state — read and written nowhere else. Two writers on two queues would
    /// order by whichever dispatch happened to finish first, so a trailer that passed
    /// its check just as a game host claimed the session could land `.ambient` last and
    /// leave a live controller muted by the ringer switch. Enqueue order decides here,
    /// and [gameConfigured] makes the game host's claim final however the race went.
    ///
    /// Off-main for the same reason it's deferred at all: the nav push into a game
    /// never drops a frame on the audio-server round trip.
    private static let queue = DispatchQueue(label: "games.couchpad.controller.audio-session",
                                             qos: .userInitiated)
    /// Set once the session is claimed for playback (a game host, or an unmuted
    /// trailer) — the muted trailer's weaker category must never downgrade it from
    /// under a live controller.
    private static var gameConfigured = false
    /// Set once the trailer category is in force; only [gameConfigured] outranks it.
    private static var trailerConfigured = false

    /// Claims the session for game audio, on the first game host (or when the user
    /// unmutes a trailer). A game page needs seconds of network + boot before it can
    /// make any sound, so for games the category is in force long before first playback.
    static func configureOnce() {
        queue.async {
            guard !gameConfigured else { return }
            gameConfigured = true
            // .playback so WebView game sound is audible even with the silent switch
            // on; .mixWithOthers so it layers over the user's music/podcasts instead
            // of stopping them.
            try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
        }
    }

    /// Before the info sheet's gameplay loop plays. The clip starts muted, but AVPlayer
    /// still activates the shared session, and the default `.soloAmbient` category is
    /// non-mixing — browsing the catalog would stop the player's music for a clip they
    /// can't hear. `.ambient` mixes by definition and follows the ringer switch. This
    /// is Android's `setAudioFocusRequest(AUDIOFOCUS_NONE)` on the trailer VideoView.
    ///
    /// Awaited, so the category is in force before a player exists — which is why it
    /// latches too: every sheet open would otherwise pay the audio-server round trip
    /// this enum exists to defer, to set the category it already holds.
    static func configureForMutedTrailer() async {
        await onQueue {
            guard !gameConfigured, !trailerConfigured else { return }
            trailerConfigured = true
            try? AVAudioSession.sharedInstance().setCategory(.ambient)
        }
    }

    /// The fullscreen trailer: the user asked to watch, so its sound interrupts theirs
    /// for the duration — `.playback` without mixing (Android: transient audio focus).
    /// Awaited, so the category is in force before the player starts.
    static func beginFullscreenTrailer() async {
        await onQueue {
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback)
            try? session.setActive(true)
        }
    }

    /// Hands the user's audio back — deactivating with notifyOthersOnDeactivation is
    /// what lets their music resume — and restores the category that was in force.
    /// Deactivating stops every player still running, so await it before resuming one.
    static func endFullscreenTrailer() async {
        await onQueue {
            let session = AVAudioSession.sharedInstance()
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            if gameConfigured {
                try? session.setCategory(.playback, options: [.mixWithOthers])
            } else {
                try? session.setCategory(.ambient)
            }
        }
    }

    private static func onQueue(_ work: @escaping () -> Void) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                work()
                continuation.resume()
            }
        }
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

/// A navigation cancelled on purpose — external links and the cross-doc push cancel in
/// decidePolicyFor, which surfaces in didFail as NSURLErrorCancelled or WebKit's
/// frame-load-interrupted (102). Not a real load failure, so both web hosts ignore it.
func isDeliberateNavigationCancellation(_ error: Error) -> Bool {
    let ns = error as NSError
    if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorCancelled { return true }
    if ns.domain == "WebKitErrorDomain", ns.code == 102 { return true }
    return false
}

/// Hosts a game's remote controller as a TOP-LEVEL web view (the game's
/// `frame-ancestors` CSP doesn't apply) with the navigation allow-list as the
/// client-side trust boundary — the join URL can originate from an untrusted
/// relay lookup.
struct GameWebView: UIViewRepresentable {
    let joinUrl: String
    let allowedDomains: [String]       // already lowercased, includes CP.launcherHost
    let onLoaded: () -> Void           // first painted frame (the injected __firstFrame signal)
    let onGameEnd: (String?) -> Void   // fire-once
    let onLeave: () -> Void            // the page's close (§3)
    let onEditName: (SheetColors) -> Void  // the page asked for the name sheet (§2), in its colors
    let playerName: String             // CouchPadHost.name, baked into the bridge shim (§1)
    let nameResult: NameResult?        // settles the page's editName() Promise (§2)
    let onLandscape: (Bool) -> Void    // the orientation the page is asking for (§10)
    let onRendererGone: () -> Void     // web-content process died — re-issue the load
    @Binding var failed: Bool          // main-doc load failed — drives the retry overlay
    let reloadToken: Int               // bumped by Retry → re-issue the join request
    let onSchemeChanged: (Bool) -> Void  // is the page dark, per its color-scheme meta (§4)
    let onNavigationStart: () -> Void  // a new main-frame document is on its way

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let coordinator = context.coordinator

        GameAudioSession.configureOnce()
        WebViewWarmer.retire()

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []   // game sounds must autoplay
        config.websiteDataStore = .default()                   // localStorage parity
        // The bridge must exist before load or the page won't see it.
        Self.installUserScripts(in: config.userContentController, name: playerName)
        config.userContentController.add(coordinator, name: "cpHost")

        // Pinned to the screen edges, so its own safeAreaInsets — status bar, notch, home
        // indicator — are exactly what the page reads through env(safe-area-inset-*).
        let webView = WKWebView(frame: .zero, configuration: config)
        // WebKit's did-change-frame handler, unlike will-show/will-change, doesn't check
        // that the web view owns the keyboard, so the name sheet's keyboard would shrink
        // the page's visualViewport. The page's own fields are still covered by
        // will-change-frame.
        NotificationCenter.default.removeObserver(webView, name: UIResponder.keyboardDidChangeFrameNotification, object: nil)
        coordinator.lastNameResult = nameResult?.id
        webView.isOpaque = false
        // Match the join cover while the page is blank — kills the flash.
        let surface = UIColor { traits in
            UIColor(traits.userInterfaceStyle == .dark ? CPPalette.dark.surface : CPPalette.light.surface)
        }
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
        // The home rejoin card follows the page's own <title> — KVO, not
        // a didFinish sample, because a controller SPA typically sets its title well
        // after the load finishes (once the relay socket connects).
        coordinator.titleObservation = webView.observe(\.title, options: [.new]) { [weak coordinator] webView, _ in
            let title = webView.title
            DispatchQueue.main.async { coordinator?.titleChanged(title) }
        }
        // Going home must drop the relay socket (Android gets this for free from the
        // process freezer; iOS keeps sockets alive in the out-of-process network
        // stack) — the page's own pagehide/visibilitychange handlers do the work.
        coordinator.observeBackgrounding(of: webView)
        coordinator.observeIncomingLinks()
        if let url = URL(string: joinUrl) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self  // always-current closures

        if let nameResult, nameResult.id != coordinator.lastNameResult {
            coordinator.lastNameResult = nameResult.id
            webView.evaluateJavaScript(GameHostJS.nameResult(nameResult.name), completionHandler: nil)
            // The shim bakes the name in, so the next page load needs a fresh one.
            if nameResult.name != nil {
                let controller = webView.configuration.userContentController
                controller.removeAllUserScripts()
                Self.installUserScripts(in: controller, name: playerName)
            }
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

    /// The bridge must exist before load or the page won't see it.
    private static func installUserScripts(in controller: WKUserContentController, name: String) {
        controller.addUserScript(
            WKUserScript(source: GameHostJS.bridgeShim(name: name), injectionTime: .atDocumentStart, forMainFrameOnly: false)
        )
        controller.addUserScript(
            WKUserScript(source: GameHostJS.firstFrameSignal, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        )
    }

    /// Tear the web view down so the game's WebSocket/audio fully stop the moment we pop.
    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.isTearingDown = true
        coordinator.titleObservation = nil
        coordinator.haptics.shutdown()
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
        var lastReloadToken = 0
        var lastNameResult: UUID?
        var titleObservation: NSKeyValueObservation?
        // Fire-once for the game-reported end — a game spamming gameEnded must pop
        // home only once. (A load failure is NOT terminal: it flips `failed` for the
        // retry overlay, so it doesn't gate on this.)
        private var didEnd = false
        // Has the document now loading exercised §10? Cleared when a new main-frame
        // navigation starts — it is what lets the launcher HOLD the orientation across
        // that navigation instead of snapping back to portrait while the next page is
        // still on its way.
        private var orientationAsked = false

        // The page's navigator.vibrate() and CouchPadHost.haptic() (CONTRACT.md §11,
        // §12). Lazy inside — no haptic engine is created until a game asks for one.
        let haptics = GameHaptics()

        // Weak: the coordinator must not extend the web view's life past dismantle.
        // Set once from makeUIView.
        private weak var hostedWebView: WKWebView?
        // The SwiftUI host view the web view was lifted out of while a link hands over.
        private weak var parkedIn: UIView?

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
        func observeBackgrounding(of webView: WKWebView) {
            hostedWebView = webView
            NotificationCenter.default.addObserver(
                self, selector: #selector(appDidEnterBackground),
                name: UIApplication.didEnterBackgroundNotification, object: nil
            )
        }

        @objc private func appDidEnterBackground() {
            hostedWebView?.evaluateJavaScript(GameHostJS.dispatchPageHide, completionHandler: nil)
        }

        /// Puts the page to sleep while an incoming link replaces it (SceneDelegate). Out of a
        /// window is the one state WebKit reports as hidden — `isHidden` doesn't count.
        func observeIncomingLinks() {
            NotificationCenter.default.addObserver(
                self, selector: #selector(incomingLinkWillOpen),
                name: .incomingLinkWillOpen, object: nil
            )
            NotificationCenter.default.addObserver(
                self, selector: #selector(incomingLinkDidFail),
                name: .incomingLinkDidFail, object: nil
            )
        }

        @objc private func incomingLinkWillOpen() {
            guard !isTearingDown, let webView = hostedWebView, let superview = webView.superview else { return }
            parkedIn = superview
            webView.removeFromSuperview()
        }

        @objc private func incomingLinkDidFail() {
            guard let webView = hostedWebView, let parkedIn else { return }
            parkedIn.addSubview(webView)
            self.parkedIn = nil
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
        /// to WebKit; the response check below catches those.) Provisional = the initial
        /// connection never committed (the offline-at-join case); the plain didFail = a
        /// committed load dropped. Both surface the retry cover rather than leave a dead
        /// spinner up.
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
                     withError error: Error) {
            reportLoadFailure(error)
        }

        /// The §10 orientation may not outlive the page that asked for it, but it is only
        /// re-armed for the verdict here — the device HOLDS what it has until the incoming
        /// document has had its say (didFinish below, or the failure path). Reverting here
        /// instead rotates the phone to portrait for the length of a page load and straight
        /// back again the moment the new page's §10 call lands — which is every self-reload
        /// a landscape game does. Main frame only, and not fired for same-document
        /// navigations, so a page that pushes history mid-session keeps it.
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            orientationAsked = false
            parent.onNavigationStart()
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

        /// Tilt controls. `DeviceOrientationEvent.requestPermission()` inside a
        /// WKWebView is answered by the HOST app, not by WebKit: with this delegate
        /// method unimplemented the request is denied without ever showing a dialog,
        /// so a game asking for the sensor concludes the phone hasn't got one.
        ///
        /// Granted rather than prompted, for the same reason the launcher adds no gate
        /// of its own anywhere else: Android's WebView hands the page orientation with
        /// no permission at all, and a phone whose entire job is to be a controller
        /// asking twice — once to join, once to tilt — is friction the games would have
        /// to design around on one platform only. The origin still has to be on the
        /// navigation allow-list (that is the trust boundary here), and WebKit requires
        /// the page to ask from a user gesture, so the sensor never opens unasked.
        func webView(_ webView: WKWebView,
                     requestDeviceOrientationAndMotionPermissionFor origin: WKSecurityOrigin,
                     initiatedByFrame frame: WKFrameInfo,
                     decisionHandler: @escaping (WKPermissionDecision) -> Void) {
            let allowed = parent.allowedDomains.contains { hostInDomain(origin.host, $0) }
            decisionHandler(allowed ? .grant : .deny)
        }

        /// The page's own name (ground truth over the manifest) feeds the home rejoin
        /// card, so games not in the bundled manifest show a real name instead of the
        /// generic fallback.
        func titleChanged(_ raw: String?) {
            guard !isTearingDown, let raw else { return }
            RecentRoomStore.putTitle(raw)
        }

        /// The MAIN document came back as an HTTP error (404, 500, 502…). WebKit counts
        /// that as a successful load and shows the server's error page — which has no
        /// close, and with no launcher chrome over the game would strand the player. It
        /// gets the same retry cover, with its close, as a lost connection. A failing
        /// favicon, script or image is the page's own business.
        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if navigationResponse.isForMainFrame,
               let http = navigationResponse.response as? HTTPURLResponse, http.statusCode >= 400 {
                showRetry()
            }
            decisionHandler(.allow)
        }

        private func reportLoadFailure(_ error: Error) {
            if isDeliberateNavigationCancellation(error) { return }
            showRetry()
        }

        private func showRetry() {
            // Ignore our own teardown and a game already ended.
            guard !isTearingDown, !didEnd else { return }
            // Nothing is going to ask now — the held orientation would otherwise strand
            // the retry cover sideways with no page behind it.
            if !orientationAsked { parent.onLandscape(false) }
            // Not terminal — surface the in-place retry overlay; Retry re-issues the load.
            DispatchQueue.main.async { self.parent.failed = true }
        }

        /// Every page finish: re-install the color-scheme observer (idempotent).
        /// Deliberately does NOT touch the cover: its fade is driven solely by the
        /// injected __firstFrame signal, because didFinish means "loaded" and can
        /// precede first paint by seconds on a cold start. No time-based fallback
        /// either — one fading the cover before content exists is the bug, not a
        /// safety net (a stalled page keeps the honest spinner, and the cover's own close
        /// stays available; load failures surface the retry cover
        /// through the error callbacks). The page's <title> needs nothing here — the
        /// KVO observer set up in makeUIView tracks it continuously.
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // The §10 verdict for this document: a page that has said nothing by the time
            // it is loaded gets the launcher's portrait. A LATER call still rotates — §10
            // supports deciding once the socket connects — this only closes the window
            // where a silent page could inherit its predecessor's landscape.
            if !isTearingDown, !orientationAsked { parent.onLandscape(false) }
            webView.evaluateJavaScript(GameHostJS.watchPageScheme, completionHandler: nil)
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
            case "leave":
                // The page's own close (§3). Shares gameEnded's fire-once latch:
                // whichever comes first exits, the other is ignored.
                guard !didEnd else { return }
                didEnd = true
                parent.onLeave()
            case "editName":
                // Not fire-once: the player may rename any number of times.
                hostedWebView?.evaluateJavaScript(GameHostJS.readSheetColors) { [weak self] result, _ in
                    self?.parent.onEditName(parseSheetColors(result))
                }
            case "__scheme":
                // Launcher-injected (watchPageScheme), not a contract message.
                parent.onSchemeChanged((body["value"] as? String) == "true")
            case "vibrate":
                // Not fire-once, and the highest-rate message on the bridge — a
                // controller buzzes on nearly every tap.
                haptics.play(parseVibrationPattern(body["value"] as? String))
            case "haptic":
                // Same rate as vibrate — the §12 path for the same taps.
                if let haptic = parseHaptic(body["value"] as? String) {
                    haptics.play(haptic.primitive, scale: haptic.scale)
                }
            case "setOrientation":
                // Not fire-once: a game may run its lobby portrait and its match
                // landscape. The shim already narrowed to the two legal keywords.
                orientationAsked = true
                parent.onLandscape((body["value"] as? String) == "landscape")
            default:
                break
            }
        }
    }
}
