import SwiftUI
import Observation
import UIKit

// MARK: - Routes

struct GameHostParams: Hashable {
    let joinUrl: String
    let title: String
    let allowedHosts: [String]
}

struct WebDocParams: Hashable {
    let url: String
    let title: String
}

enum Route: Hashable {
    case gameHost(GameHostParams)
    case about
    case webDoc(WebDocParams)
}

// MARK: - Router

@MainActor @Observable final class AppRouter {

    var path: [Route] = []
    var pendingDeepLink: String? = nil
    let messages = MessageCenter()

    func handleIncomingURL(_ url: URL) {
        // Deep link always lands on Main; MainScreen owns the resolve + name gate.
        path.removeAll()
        pendingDeepLink = url.absoluteString
    }

    func consumeDeepLink() {
        pendingDeepLink = nil
    }

    func push(_ route: Route) {
        path.append(route)
    }

    func pop() {
        if !path.isEmpty { path.removeLast() }
    }

    func gameEnded(reason: String?) {
        // The player didn't choose to leave — silence would read as a crash. Shown as a
        // banner in the home rejoin slot, not a bottom overlay. (A load failure isn't a
        // session end: the host shows a retry overlay in place.)
        pop()
        // A room that's gone can't be rejoined — drop the saved room now so the home
        // rejoin card clears immediately instead of lingering until the liveness poll's
        // relay record independently catches up (10s + relay lag).
        if reason == "room_not_found" { RecentRoomStore.clear() }
        messages.showGameEndBanner(gameEndMessage(reason))
    }
}

// MARK: - Transient messages

struct ToastItem: Identifiable, Equatable {
    let id: UUID
    let text: String
    let long: Bool
}

@MainActor @Observable final class MessageCenter {

    var toast: ToastItem? = nil
    // The game-end notice, rendered by MainScreen as a banner in the rejoin slot (not a
    // floating overlay). Auto-clears after 5s; a tap/swipe on the banner clears it early.
    var gameEndBanner: String? = nil

    @ObservationIgnored private var toastTask: Task<Void, Never>? = nil
    @ObservationIgnored private var gameEndTask: Task<Void, Never>? = nil

    func showToast(_ text: String, long: Bool = false) {
        toastTask?.cancel()
        let item = ToastItem(id: UUID(), text: text, long: long)
        toast = item
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(long ? 3.5 : 2.0))
            guard !Task.isCancelled, let self, self.toast?.id == item.id else { return }
            self.toast = nil
        }
    }

    func showGameEndBanner(_ text: String) {
        gameEndTask?.cancel()
        gameEndBanner = text
        gameEndTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5.0))
            guard !Task.isCancelled, let self else { return }
            self.gameEndBanner = nil
        }
    }

    func dismissGameEndBanner() {
        gameEndTask?.cancel()
        gameEndBanner = nil
    }
}

// MARK: - Root view

struct RootView: View {

    @Bindable private var router: AppRouter

    init(router: AppRouter) {
        self.router = router
    }

    var body: some View {
        ZStack {
            NavigationStack(path: $router.path) {
                MainScreen(
                    deepLink: router.pendingDeepLink,
                    isTopVisible: router.path.isEmpty,
                    onDeepLinkConsumed: { router.consumeDeepLink() },
                    onJoin: { url, title, hosts in
                        router.push(.gameHost(GameHostParams(joinUrl: url, title: title, allowedHosts: hosts)))
                    },
                    // A privacy/imprint App Link opens the in-app doc viewer over Main,
                    // rather than routing the URL through the join resolver.
                    onOpenLegalDoc: { url in
                        let title = CP.legalTitle(for: URL(string: url)) ?? String(localized: "Privacy Policy")
                        router.push(.webDoc(WebDocParams(url: url, title: title)))
                    },
                    onOpenAbout: { router.push(.about) }
                )
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .gameHost(let params):
                        GameHostScreen(
                            joinUrl: params.joinUrl,
                            title: params.title,
                            allowedHosts: params.allowedHosts,
                            onLeave: { router.pop() },
                            onGameEnd: { router.gameEnded(reason: $0) }
                        )
                        .navigationBarBackButtonHidden(true)
                        .toolbar(.hidden, for: .navigationBar)
                    case .about:
                        AboutScreen(
                            onOpenPrivacy: { router.push(.webDoc(WebDocParams(url: CP.privacyURL, title: String(localized: "Privacy Policy")))) },
                            onOpenImprint: { router.push(.webDoc(WebDocParams(url: CP.imprintURL, title: String(localized: "Impressum")))) }
                        )
                    case .webDoc(let params):
                        WebDocScreen(
                            url: params.url,
                            title: params.title,
                            onOpenDoc: { destination in
                                let title = CP.legalTitle(for: URL(string: destination)) ?? params.title
                                router.push(.webDoc(WebDocParams(url: destination, title: title)))
                            }
                        )
                    }
                }
            }
            // Above the NavigationStack so a toast floats over whatever screen is up.
            MessageOverlay()
        }
        .cpThemed()
        .environment(router.messages)
        // Keep-awake and status-bar style follow the ROUTE, not view lifecycle:
        // onAppear/onDisappear don't fire reliably across NavigationStack
        // push/pop. (Indicator/edge-gesture chrome is view-scoped SwiftUI on
        // GameHostScreen and needs no reset.)
        .onChange(of: router.path, initial: true) { _, path in
            let inGame = path.contains { if case .gameHost = $0 { return true } else { return false } }
            UIApplication.shared.isIdleTimerDisabled = inGame
            if !inGame {
                ChromeState.shared.reset()
            }
        }
        .task {
            // Warm the web engine for the first game join, once launch has settled.
            // A join can't realistically happen this early (it needs a scan or typed
            // code), and if one does, retire() makes this a no-op.
            try? await Task.sleep(for: .seconds(1))
            WebViewWarmer.warmUp()
        }
    }
}

// MARK: - Message overlay

struct MessageOverlay: View {

    @Environment(MessageCenter.self) private var messages

    init() {}

    var body: some View {
        VStack(spacing: 8) {
            if let toast = messages.toast {
                Text(toast.text)
                    .font(.cpBodyMedium)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(Color(white: 0.15, opacity: 0.92)))
                    .padding(.horizontal, 32)
                    .transition(.opacity)
            }
        }
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.2), value: messages.toast)
    }
}
