import SwiftUI
import UIKit

/// Hosts a game's remote controller in a web view spanning the full physical screen,
/// with no launcher chrome over it: the page draws its own close (§3), and only its rename
/// button opens the launcher's name sheet (CONTRACT.md §2). The page reads the real safe
/// area through env(safe-area-inset-*). The launcher's own surfaces here (join cover,
/// retry, the blank page behind them) follow the system's light/dark mode, like home: the
/// launcher can't know a game's colors before its page has loaded, and not every game is
/// in the manifest. Leaving is explicit: the page's close is the only exit — iOS has no
/// system back (CONTRACT.md §9).
struct GameHostScreen: View {
    let joinUrl: String
    let title: String
    let allowedHosts: [String]
    let onLeave: () -> Void
    let onGameEnd: (String?) -> Void

    // Explicit: the synthesized memberwise init would be private (private @State below).
    init(joinUrl: String, title: String, allowedHosts: [String],
         onLeave: @escaping () -> Void, onGameEnd: @escaping (String?) -> Void) {
        self.joinUrl = joinUrl
        self.title = title
        self.allowedHosts = allowedHosts
        self.onLeave = onLeave
        self.onGameEnd = onGameEnd
    }

    @State private var loading = true
    // The page has painted its first frame. The join cover lifts on that — unless the
    // page asked for landscape and the window hasn't turned yet: the turn takes a few
    // hundred ms, and lifting earlier shows the page portrait-shaped mid-turn (a
    // landscape-only game's "turn your phone" overlay, say).
    @State private var painted = false
    @State private var wantsLandscape = false
    @State private var isLandscape = false
    // First-join local network gate: games open a direct WebRTC path to their display
    // ("fastlane"), and iOS blocks LAN traffic until Local Network is granted. The
    // prompt fires here, with the page load held until a verdict — a grant landing
    // after the page has started ICE is only picked up by the game's own retry loop.
    // The join never blocks on the ANSWER: a deny loads the page anyway, which falls
    // back to its relay exactly as on an AP-isolated network. Held at most once ever —
    // an earlier discovery opt-in or a remembered verdict skips straight through.
    @State private var lanGateOpen = NearbyOptIn.isSet || LocalNetworkPrompt.done
    // The main document failed to load (no connection / host unreachable) — drives the
    // in-place retry overlay. Retry bumps the token GameWebView observes to reload.
    @State private var failed = false
    @State private var reloadToken = 0
    // Is the page dark, per its color-scheme meta (CONTRACT.md §4)? Nil until the
    // launcher's observer reports.
    @State private var pageDark: Bool? = nil
    // The join has been loading longer than a normal one takes (see the cover's close).
    @State private var slowLoad = false
    @State private var profile = ProfileStore.load()
    // Item-based so the sheet always receives the CURRENT profile: @State read inside a
    // sheet content closure is not dependency-tracked and can be stale.
    @State private var renameRequest: RenameRequest? = nil
    // The request whose sheet is on screen, from presenting until it has fully gone. The
    // page can't be tapped while a sheet is up, so a new request arrives only while one
    // is sliding out — it waits in queuedRenames and opens once the old sheet is gone.
    @State private var shownRename: RenameRequest? = nil
    @State private var queuedRenames: [RenameRequest] = []
    // The last request whose editName() Promise was answered, so each is answered once
    // (Save answers it before the dismissal does). Answers go out in request order —
    // the page matches them to its Promises by that order.
    @State private var settledRename: UUID? = nil
    @State private var nameResult: NameResult? = nil

    @Environment(\.colorScheme) private var systemScheme
    private var palette: CPPalette { systemScheme == .dark ? .dark : .light }

    private func sheetPalette(_ colors: SheetColors) -> CPPalette {
        let base: CPPalette = pageDark == false ? .light : .dark
        return colors.accent.map { base.withAccent($0) } ?? base
    }

    private var allowed: [String] {
        (allowedHosts + [CP.launcherHost]).map { $0.lowercased() }
    }

    // MARK: - Body

    var body: some View {
        ZStack(alignment: .topLeading) {
            palette.surface
                .ignoresSafeArea()

            // While the gate holds, the join cover is the whole screen — the web view
            // (and with it the load) only comes into existence once the dialog is
            // answered.
            if lanGateOpen {
                GameWebView(
                    joinUrl: joinUrl,
                    allowedDomains: allowed,
                    onLoaded: { painted = true },
                    onGameEnd: onGameEnd,
                    onLeave: onLeave,
                    onEditName: { requestRename(RenameRequest(profile: profile, colors: $0)) },
                    playerName: profile.name,
                    nameResult: nameResult,
                    // The page's requested orientation (CONTRACT.md §10). Goes straight to
                    // ChromeState — it drives the window scene, not this view's layout, and
                    // the route-driven reset there is what guarantees home is portrait again.
                    onLandscape: {
                        wantsLandscape = $0
                        ChromeState.shared.orientation = $0 ? .landscape : .portrait
                    },
                    onRendererGone: reload,
                    failed: $failed,
                    reloadToken: reloadToken,
                    onSchemeChanged: { pageDark = $0 },
                    onNavigationStart: { pageDark = nil }
                )
                .ignoresSafeArea()
            }

            // "Joining…" cover that fades away once the controller has painted.
            // (Not while failed — the retry cover replaces it, like Android's
            // loading=false on failure.)
            if loading && !failed {
                JoiningCover(
                    message: String(localized: "Joining \(title)…"),
                    background: palette.surface,
                    foreground: palette.onSurfaceVariant
                )
                .zIndex(1)
                .transition(.opacity)
            }

            // Load failed: opaque cover offering retry-in-place (so a transient blip
            // doesn't cost a re-scan).
            if failed {
                RetryCover(background: palette.surface, foreground: palette.onSurface, onRetry: reload)
                    .zIndex(1)
            }

            // Until a page is up there is no page close to tap, so the launcher offers
            // its own: a stalled join or a failed load must never trap the player. A normal
            // join is over in a second or two, so on the join cover it only fades in once
            // loading runs long; the retry cover has it at once.
            if failed || (loading && slowLoad) {
                Button(action: onLeave) {
                    // Glyph and glass as the name sheet's close.
                    Image(systemName: "xmark")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(palette.onSurface)
                        .frame(width: 44, height: 44)
                        .modifier(ChromeGlass(shape: Circle(), fallback: Color(uiColor: .tertiarySystemFill)))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Leave game")
                .padding(.leading, 16)
                .zIndex(2)
                .transition(.opacity)
            }
        }
        // The game surface never resizes for the keyboard (the page sees its own
        // field's only through visualViewport).
        .ignoresSafeArea(.keyboard)
        // No back button and no interactive pop: the only way out of a live match is
        // the page's close, routed explicitly, never through NavigationStack's history.
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        // Immersive chrome, scoped to THIS view so it cannot leak onto home:
        // home indicator dims when idle, edge swipes need a second confirm.
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)
        // The launcher's own name sheet (CONTRACT.md §2), in the page's scheme,
        // theme-color and accent-color so it reads as part of the game; it overrides the
        // host's system-mode palette below for itself alone.
        .appSheet(item: $renameRequest, onDismiss: {
            if let shown = shownRename { settleRename(shown, name: nil) }
            shownRename = nil
            if !queuedRenames.isEmpty {
                requestRename(queuedRenames.removeFirst())
            }
        }, surfaceTint: renameRequest?.colors.surface) { request in
            ProfileSheet(initial: request.profile, onSave: { saved in
                ProfileStore.save(saved)
                profile = saved
                settleRename(request, name: saved.name)
                renameRequest = nil
            })
            .environment(\.cpPalette, sheetPalette(request.colors))
            .environment(\.colorScheme, pageDark == false ? .light : .dark)
            .tint(sheetPalette(request.colors).primary)
        }
        .environment(\.cpPalette, palette)
        .tint(palette.primary)
        .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { isLandscape = $0 }
        .onChange(of: loading && painted && (!wantsLandscape || isLandscape)) { _, ready in
            if ready { withAnimation(.easeOut(duration: 0.3)) { loading = false } }
        }
        .task(id: loading) {
            slowLoad = false
            guard loading else { return }
            // Past a normal join (a second or two), well short of the web view's own
            // timeout (a minute).
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { withAnimation { slowLoad = true } }
        }
        // Status-bar icons contrast against the page, or against the join or retry cover
        // while one is up — those follow the system mode, and a server's error page under
        // the retry cover declares no color-scheme of its own. The page decides once it
        // shows. Entering/leaving game chrome (indicator, gestures, idle timer) is driven by
        // the ROUTER, not view lifecycle — onAppear/onDisappear proved unreliable across
        // NavigationStack push/pop, leaking hidden-chrome state onto home.
        .onChange(of: (loading || failed) ? systemScheme == .dark : (pageDark ?? (systemScheme == .dark)),
                  initial: true) { _, dark in
            ChromeState.shared.statusBarStyle = dark ? .lightContent : .darkContent
        }
        .task {
            guard !lanGateOpen else { return }
            // A grant made here is the same authorization discovery uses, so light
            // discovery up too — matching Android 17+, where the permission IS the
            // opt-in memory (nearbyOptedIn).
            if await requestLocalNetworkAccess() { NearbyOptIn.set() }
            lanGateOpen = true
        }
        // The room is not idle while its player is looking at it: hold the recent-room
        // slot open for as long as the host is up, so a long session can't age out from
        // under the rejoin card (RecentRoomStore.inRoom).
        .task {
            RecentRoomStore.enter()
            defer { RecentRoomStore.leave() }
            // Park until the task is cancelled — i.e. until this screen goes away.
            while !Task.isCancelled { try? await Task.sleep(for: .seconds(3600)) }
        }
        // Relay this room to the local network while we're in it, so the next player can
        // tap instead of scan — and so the room stays discoverable even if its display
        // never advertised. Publishes the room code only (§8); no URL, no device name.
        // Re-keyed on the gate so a grant made there starts the advert in this same
        // session (start() is a no-op without the opt-in).
        .task(id: "\(lanGateOpen)|\(joinUrl)") {
            guard let room = RecentRoomStore.current() else { return }
            NearbyAdvertiser.shared.start(roomCode: room.roomCode)
            defer { NearbyAdvertiser.shared.stop() }
            // Park until the task is cancelled — i.e. until this screen goes away.
            // A loop rather than one capped sleep so no session length outlives the
            // advertisement; each hourly wake is a no-op.
            while !Task.isCancelled { try? await Task.sleep(for: .seconds(3600)) }
        }
    }

    private func requestRename(_ request: RenameRequest) {
        if shownRename == nil {
            shownRename = request
            renameRequest = request
        } else {
            queuedRenames.append(request)
        }
    }

    /// Answers the page's editName() for `request`: the saved name, or nil when dismissed.
    private func settleRename(_ request: RenameRequest, name: String?) {
        guard settledRename != request.id else { return }
        settledRename = request.id
        nameResult = NameResult(name: name)
    }

    /// Load the controller again in place (no re-scan): clear the error, bring the
    /// join cover back, re-issue. Both the Retry tap and a dead renderer land here.
    private func reload() {
        failed = false
        loading = true
        painted = false
        reloadToken += 1
    }
}

/// One answer to the page's editName(): the saved name, or nil for a dismissal.
struct NameResult: Equatable {
    let id = UUID()
    let name: String?
}

private struct RenameRequest: Identifiable {
    let id = UUID()
    let profile: Profile
    let colors: SheetColors
}
