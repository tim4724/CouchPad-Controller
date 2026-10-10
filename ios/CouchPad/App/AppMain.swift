import UIKit
import SwiftUI

// MARK: - App delegate

@main final class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Audio-session setup lives in GameAudioSession (GameWebView.swift), paid on
        // first game join — the call talks to the audio server (tens of ms) and
        // nothing on the launch path plays sound.
        return true
    }
}

// MARK: - Scene delegate

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?
    let router = AppRouter()

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        let root = RootHostingController(rootView: RootView(router: router))
        ChromeState.shared.host = root
        // First frames before SwiftUI paints must already be the surface color
        // in the correct light/dark variant (no white flash in dark mode).
        // CPPalette background (#110F17 / #FAF9F7) — keep in sync with CPTheme.swift.
        window.backgroundColor = UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0x11 / 255.0, green: 0x0F / 255.0, blue: 0x17 / 255.0, alpha: 1)
                : UIColor(red: 0xFB / 255.0, green: 0xF9 / 255.0, blue: 0xF4 / 255.0, alpha: 1)
        }
        window.rootViewController = root
        self.window = window
        window.makeKeyAndVisible()

        // Posters dominate the first frame — start their off-main decodes now
        // rather than when each card's .task runs (after first paint).
        ArtCache.prewarm(ManifestStore.shared.games)

        // Cold-start deep link: connectionOptions only exist at scene connect,
        // so "consumed once, never replayed" holds by construction.
        if let url = connectionOptions.userActivities
            .first(where: { $0.activityType == NSUserActivityTypeBrowsingWeb })?.webpageURL {
            router.handleIncomingURL(url)
        }

        #if DEBUG
        // UI-test hooks (StoreScreenshotTests), read via the argument domain:
        // `-uitest.deepLink <url>` — Universal Links need a live AASA + Safari
        // round-trip, so simulator tests inject the join URL directly instead.
        // `-uitest.appearance dark|light` — forces the interface style at the window
        // (the `-UIUserInterfaceStyle` launch arg proved unreliable on CI simulators).
        if let link = UserDefaults.standard.string(forKey: "uitest.deepLink"),
           let url = URL(string: link) {
            router.handleIncomingURL(url)
        }
        if let style = UserDefaults.standard.string(forKey: "uitest.appearance") {
            window.overrideUserInterfaceStyle = style == "dark" ? .dark : .light
        }
        #endif
    }

    // Universal Links while running. A link replaces any live game page, but continue()
    // lands after willEnterForeground — the outgoing page would already have been told it's
    // visible and reconnected, so the display sees join, leave, join. willContinue comes
    // before the foreground, so the game host puts its page to sleep there.
    func scene(_ scene: UIScene, willContinueUserActivityWithType userActivityType: String) {
        guard userActivityType == NSUserActivityTypeBrowsingWeb else { return }
        NotificationCenter.default.post(name: .incomingLinkWillOpen, object: nil)
    }

    func scene(_ scene: UIScene, didFailToContinueUserActivityWithType userActivityType: String, error: Error) {
        guard userActivityType == NSUserActivityTypeBrowsingWeb else { return }
        NotificationCenter.default.post(name: .incomingLinkDidFail, object: nil)
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb else { return }
        guard let url = userActivity.webpageURL else {
            // willContinue already put the game page to sleep — nothing will replace it.
            NotificationCenter.default.post(name: .incomingLinkDidFail, object: nil)
            return
        }
        router.handleIncomingURL(url)
    }
}

extension Notification.Name {
    /// A Universal Link is on its way in; the game page it will replace must not wake.
    static let incomingLinkWillOpen = Notification.Name("games.couchpad.incomingLinkWillOpen")
    /// The announced link never arrived — the game page carries on.
    static let incomingLinkDidFail = Notification.Name("games.couchpad.incomingLinkDidFail")
}

// MARK: - Root hosting controller

final class RootHostingController: UIHostingController<RootView> {

    override var preferredStatusBarStyle: UIStatusBarStyle {
        ChromeState.shared.statusBarStyle ?? .default
    }

    /// The only view controller in the window, so this narrows the Info.plist's
    /// landscape-inclusive superset down to what the current screen actually allows
    /// (CONTRACT.md §10). Portrait everywhere except a game host whose page asked
    /// for landscape — home must not rotate just because the plist permits it.
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        ChromeState.shared.orientation
    }
}

// MARK: - Chrome state

/// Status-bar style and interface orientation for the game host (the page's color scheme
/// sets the one, the page's request the other). Home indicator and edge-gesture
/// deferral are NOT here — those use SwiftUI's view-scoped persistentSystemOverlays/
/// defersSystemGestures on GameHostScreen, which cannot leak past that view's lifetime.
/// Orientation can't work that way: it is a window-scene property, and the reset must
/// be driven by the ROUTE (see AppRouter) so a pop can't leave home stuck in landscape.
@MainActor final class ChromeState {

    static let shared = ChromeState()

    weak var host: RootHostingController?

    var statusBarStyle: UIStatusBarStyle? {
        didSet { host?.setNeedsStatusBarAppearanceUpdate() }
    }

    /// What the current screen allows (CONTRACT.md §10). `.landscape` covers both
    /// orientations rather than picking one: a controller held either way round must
    /// land right side up, and the launcher has no idea which hand the player uses.
    var orientation: UIInterfaceOrientationMask = .portrait {
        didSet {
            guard oldValue != orientation else { return }
            // Order matters. setNeedsUpdate… makes UIKit re-read the root VC's
            // supportedInterfaceOrientations (already the new mask — this is a didSet);
            // requestGeometryUpdate then actually turns the window. Without the first
            // call the geometry request is rejected against the stale mask; without the
            // second the device only rotates if the player physically turns the phone.
            host?.setNeedsUpdateOfSupportedInterfaceOrientations()
            host?.view.window?.windowScene?
                .requestGeometryUpdate(.iOS(interfaceOrientations: orientation))
        }
    }

    func reset() {
        statusBarStyle = nil
        orientation = .portrait
    }

    private init() {}
}
