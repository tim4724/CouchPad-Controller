import SwiftUI
import UIKit

/// Hosts a game's remote controller under a launcher-owned "Leave" bar. In-game
/// chrome is always dark, like a video player — the games are dark and a bright
/// bar above them would be jarring. The game surface spans the full physical
/// screen; the chrome floats above it and the page is told where the safe zone
/// is (CSS vars + synthetic safe-area). Leaving is explicit: Leave is the only exit
/// unless the page arms the system back gesture (CONTRACT.md §9).
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

    @State private var profile: Profile = ProfileStore.load()
    // Item-based so the sheet always receives the CURRENT profile: @State read
    // inside a sheet content closure is not dependency-tracked and can be stale.
    @State private var renameRequest: RenameRequest? = nil
    @State private var loading = true
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
    @State private var pageTheme = PageTheme()
    // The page's own <title> supersedes the manifest name in the Leave bar once the
    // controller reports one, so games not (yet) in the bundled manifest still show a
    // real name instead of the generic "CouchPad" fallback. Nil until the page
    // reports; the manifest name covers the join cover and any title-less page.
    @State private var pageTitle: String? = nil
    @State private var chromeHeight: CGFloat = 0
    @State private var chromeWidth: CGFloat = 0
    @State private var chipRight: CGFloat = 0
    // The landscape icon rail's intrusion from the physical right edge (window points).
    @State private var railEnd: CGFloat = 0
    @State private var cutout = EdgeInsets()
    // Safe-area-bounded size from cutoutReader — orientation truth for the chrome.
    @State private var hostSize: CGSize = .zero

    @Environment(\.layoutDirection) private var layoutDirection

    private var isLandscape: Bool { hostSize.width > hostSize.height }

    // MARK: - Derived

    private var allowed: [String] {
        (allowedHosts + [CP.launcherHost]).map { $0.lowercased() }
    }

    /// The page's own title once reported, else the manifest name.
    private var displayTitle: String { pageTitle ?? title }

    /// A game-supplied accent flows through `primary`, so every launcher accent
    /// over the game (chip, spinner, rename sheet) follows.
    private var hostPalette: CPPalette {
        pageTheme.accent.map { CPPalette.dark.withAccent($0) } ?? CPPalette.dark
    }

    /// A game-supplied theme-color becomes the chrome's scrim tint.
    private var barTarget: Color {
        pageTheme.bar ?? hostPalette.surfaceContainer
    }

    /// Non-nil only when the game supplied its own theme-color; its content color
    /// is luminance-picked since the page sends no pair.
    private var barContent: Color? {
        pageTheme.bar.map { contentColorOn($0) }
    }

    /// The game's own theme-color (the page chrome color) becomes the rename sheet's
    /// surface, so the sheet reads as part of the game rather than the neutral app
    /// grey. Adopted only when it's dark enough to keep the sheet's white text legible
    /// (white ≥ 4.5:1 needs luminance < ~0.18); a lighter theme-color falls back to
    /// the neutral surface. Most game chrome is very dark, so this usually applies.
    private var sheetSurface: Color? {
        guard let bar = pageTheme.bar, relativeLuminance(bar) < 0.18 else { return nil }
        return bar
    }

    /// Safe-zone geometry (points, ints). In PORTRAIT the top is the chrome's full
    /// extent (inset + Leave bar) and the sides carry the chip's gutter. In
    /// LANDSCAPE there is no bar — the chrome collapses to the icon rail in the
    /// leading strip, the top shrinks to the bare cutout (the game gets the full
    /// height), and the sides carry the rail instead. Both sides always get ONE
    /// shared value (§5 levelling). Bottom is the bare cutout (no chrome there).
    private var computedSafeZone: SafeZone {
        // Level the two sides to the larger — see the matching note in Android's
        // GameHostScreen. The chrome is padded/placed inside this same strip, so its
        // own controls sit on the published box rather than beside it.
        let sideCutout = max(cutout.leading, cutout.trailing)
        let safeTop = isLandscape ? cutout.top : chromeHeight
        let side = isLandscape
            ? max(sideCutout, railEnd)
            : (chromeWidth > 0 ? max(chromeWidth - chipRight, sideCutout) : sideCutout)
        let safeBottom = cutout.bottom
        // Ceil, not round, matching Android: an inset that lands mid-point must cover
        // the obstruction, never stop short — and it keeps --cp-safe-* from losing to
        // the unrounded env() values the synthetic insets publish.
        return SafeZone(
            top: Int(safeTop.rounded(.up)),
            left: Int(side.rounded(.up)),
            right: Int(side.rounded(.up)),
            bottom: Int(safeBottom.rounded(.up))
        )
    }

    // MARK: - Body

    var body: some View {
        ZStack(alignment: .top) {
            hostPalette.surface
                .ignoresSafeArea()

            // The game surface spans the FULL physical screen — the chrome floats
            // above it, and the page keeps its interactive UI in the safe zone.
            // While the gate holds, the join cover is the whole screen — the web view
            // (and with it the load) only comes into existence once the dialog is
            // answered.
            if lanGateOpen {
                GameWebView(
                    joinUrl: joinUrl,
                    allowedDomains: allowed,
                    playerName: profile.name,
                    safeZone: computedSafeZone,
                    onLoaded: { withAnimation(.easeOut(duration: 0.3)) { loading = false } },
                    onGameEnd: onGameEnd,
                    onLeave: onLeave,
                    // The page's requested orientation (CONTRACT.md §10). Goes straight to
                    // ChromeState — it drives the window scene, not this view's layout, and
                    // the route-driven reset there is what guarantees home is portrait again.
                    onLandscape: { ChromeState.shared.orientation = $0 ? .landscape : .portrait },
                    onRendererGone: reload,
                    failed: $failed,
                    reloadToken: reloadToken,
                    onThemeChanged: { pageTheme = $0 },
                    onTitleChanged: { pageTitle = $0 }
                )
                .ignoresSafeArea()
            }

            // "Joining…" cover that fades away once the controller has painted.
            // (Not while failed — the retry cover replaces it, like Android's
            // loading=false on failure.)
            if loading && !failed {
                loadingCover
                    .zIndex(1)
                    .transition(.opacity)
            }

            // Load failed: opaque cover offering retry-in-place (so a transient blip
            // doesn't cost a re-scan) or Leave. Above the join cover, below the chrome.
            if failed {
                retryCover
                    .zIndex(1)
            }

            chrome
                .zIndex(2)
        }
        .background(cutoutReader)
        // The game surface never resizes for anything — the keyboard overlays it,
        // like a video player.
        .ignoresSafeArea(.keyboard)
        // No back button and no interactive pop: the only way out of a live match is
        // the Leave bar, or the §9 back gesture the page armed — both routed
        // explicitly, never through NavigationStack's own history.
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        // Immersive chrome, scoped to THIS view so it cannot leak onto home:
        // home indicator dims when idle, edge swipes need a second confirm.
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)
        .appSheet(item: $renameRequest, surfaceTint: sheetSurface) { request in
            ProfileSheet(initial: request.profile, onSave: { saved in
                ProfileStore.save(saved)
                profile = saved
                renameRequest = nil
                // Live injection happens via GameWebView.playerName → updateUIView.
            })
        }
        // Forced dark for the whole subtree AND the rename sheet. The tint must move
        // with the palette: cpThemed() sets it once from the SYSTEM scheme, so without
        // this a system-light device would fill prominent controls over the (dark) game
        // — e.g. the rename sheet's Save button — with the light palette's near-black
        // primary under the dark palette's near-black onPrimary label (black-on-black).
        .environment(\.cpPalette, hostPalette)
        .environment(\.colorScheme, .dark)
        .tint(hostPalette.primary)
        // Status-bar icons contrast against the (possibly game-colored) bar strip.
        // Entering/leaving game chrome (indicator, gestures, idle timer) is driven
        // by the ROUTER, not view lifecycle — onAppear/onDisappear proved unreliable
        // across NavigationStack push/pop, leaking hidden-chrome state onto home.
        .onChange(of: barTarget, initial: true) { _, target in
            ChromeState.shared.statusBarStyle =
                relativeLuminance(target) > 0.5 ? .darkContent : .lightContent
        }
        .task {
            guard !lanGateOpen else { return }
            // A grant made here is the same authorization discovery uses, so light
            // discovery up too — matching Android 17+, where the permission IS the
            // opt-in memory (nearbyOptedIn).
            if await requestLocalNetworkAccess() { NearbyOptIn.set() }
            lanGateOpen = true
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

    // MARK: - Pieces

    /// Captures the window's real safe-area insets (cutout/home indicator) —
    /// attached where the safe area is still intact.
    private var cutoutReader: some View {
        GeometryReader { proxy in
            Color.clear
                .onAppear {
                    cutout = proxy.safeAreaInsets
                    hostSize = proxy.size
                }
                .onChange(of: proxy.safeAreaInsets) { _, newValue in cutout = newValue }
                .onChange(of: proxy.size) { _, newValue in hostSize = newValue }
        }
    }

    private var loadingCover: some View {
        JoiningCover(
            message: String(localized: "Joining \(displayTitle)…"),
            background: hostPalette.surface,
            foreground: hostPalette.onSurfaceVariant,
            // Adopts the accent if the theme beat page-finish.
            tint: hostPalette.primary
        )
    }

    // No Leave button here — the Leave bar's X already exits.
    private var retryCover: some View {
        RetryCover(background: hostPalette.surface, foreground: hostPalette.onSurface, onRetry: reload)
    }

    /// Load the controller again in place (no re-scan): clear the error, bring the
    /// join cover back, re-issue. Both the Retry tap and a dead renderer land here.
    private func reload() {
        failed = false
        loading = true
        reloadToken += 1
    }

    /// The floating chrome. Landscape: no bar at all — the game keeps the full
    /// height, and the two session controls stack in a side strip the levelled
    /// side inset (§5) reserves anyway.
    @ViewBuilder
    private var chrome: some View {
        if isLandscape { landscapeChrome } else { portraitChrome }
    }

    /// Landscape chrome: Close and the rename affordance at the top-RIGHT corner —
    /// physical right, matching Android's camera-driven side pick rather than the
    /// reading direction. The iPhone's landscape cutout (notch/island) sits
    /// mid-edge, so that corner is always free — none of Android's dodge-or-flip
    /// geometry for corner cameras is needed here.
    private var landscapeChrome: some View {
        VStack(spacing: 4) {
            railButton("xmark", label: "Leave game", action: onLeave)
            railButton(
                "person.fill",
                // Icon-only rename affordance; announces the name it edits, like the chip.
                label: profile.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? String(localized: "Set name") : profile.name,
                action: { renameRequest = RenameRequest(profile: profile) }
            )
        }
        // Center the buttons inside the strip when it's wide enough; hug the edge
        // otherwise — the published side inset grows to the rail's extent either way.
        // The layoutDirection ternary here and on the alignment below KEEPS the rail
        // physically right: SwiftUI mirrors `.trailing`/`.topTrailing` under RTL, so
        // asking for the physical side means asking for the opposite semantic one.
        .padding(
            layoutDirection == .leftToRight ? .trailing : .leading,
            max((max(cutout.leading, cutout.trailing) - 44) / 2, 4)
        )
        .padding(.top, cutout.top + 4)
        .onGeometryChange(for: CGRect.self) { proxy in
            proxy.frame(in: .global)
        } action: { frame in
            // Intrusion from the physical right edge, in both layout directions and
            // with no ternary of its own: `.global` is window space, which SwiftUI
            // does NOT mirror, and the alignment above already lands the rail on the
            // physical right either way (see the ternaries — they exist to defeat
            // SwiftUI's mirroring of `.trailing`, not to follow it).
            let fullWidth = hostSize.width + cutout.leading + cutout.trailing
            railEnd = fullWidth - frame.minX
        }
        .frame(
            maxWidth: .infinity, maxHeight: .infinity,
            alignment: layoutDirection == .leftToRight ? .topTrailing : .topLeading
        )
        // Our explicit padding is the only inset — matching the portrait chrome.
        .ignoresSafeArea()
    }

    private func railButton(_ systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(barContent ?? hostPalette.onSurfaceVariant)
                .frame(width: 44, height: 44)
                .background(barTarget.opacity(0.55), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    /// Portrait chrome: status-bar strip + Leave bar over a fading scrim of the
    /// bar color. Padded INSIDE the gradient by the top inset + horizontal cutouts
    /// only, so the gradient paints under the status bar and never moves for the
    /// keyboard.
    private var portraitChrome: some View {
        VStack(spacing: 0) {
            LeaveBar(
                title: displayTitle,
                playerName: profile.name,
                barContent: barContent,
                onLeave: onLeave,
                onEditName: { renameRequest = RenameRequest(profile: profile) },
                onChipRight: { chipRight = $0 }
            )
            .padding(.top, cutout.top)
            // Symmetric, matching the safe zone we publish (§5): a landscape cutout is on
            // one side only, but levelling both keeps the chrome's own controls on the
            // same box the page is told to stay inside.
            .padding(.horizontal, max(cutout.leading, cutout.trailing))
        }
        .frame(maxWidth: .infinity)
        .background(
            LinearGradient(
                stops: [
                    .init(color: barTarget.opacity(0.90), location: 0.0),
                    .init(color: barTarget.opacity(0.50), location: 0.65),
                    .init(color: barTarget.opacity(0.0), location: 1.0),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .animation(.easeInOut(duration: 0.3), value: barTarget)
        .onGeometryChange(for: CGSize.self) { proxy in
            proxy.frame(in: .global).size
        } action: { size in
            chromeHeight = size.height
            chromeWidth = size.width
        }
        // Horizontal too, not just top: SwiftUI would otherwise inset this view by the
        // safe area AND we add `cutout` padding above it, double-counting the notch —
        // invisible in portrait (0 there), but in landscape it pushed the X and the name
        // chip twice the cutout off each edge. Our explicit padding is the only inset.
        .ignoresSafeArea(edges: [.top, .horizontal])
    }
}

private struct RenameRequest: Identifiable {
    let id = UUID()
    let profile: Profile
}

/// The launcher-owned chrome floating over the game: Close (leaving a live game
/// ends the session — it isn't navigation), the game's name, and the tappable name
/// chip (the in-game rename affordance). `barContent` is non-nil only when the game
/// supplied its own theme-color — then the whole bar's content flips together.
private struct LeaveBar: View {
    let title: String
    let playerName: String
    let barContent: Color?
    let onLeave: () -> Void
    let onEditName: () -> Void
    let onChipRight: (CGFloat) -> Void

    @Environment(\.cpPalette) private var palette

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onLeave) {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(barContent ?? palette.onSurfaceVariant)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Leave game")
            .padding(.leading, 4)

            Text(title)
                .font(.cpTitleMedium)
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(barContent ?? palette.onSurface)
                .padding(.leading, 4)

            Spacer(minLength: 12)

            // Report the chip's trailing edge (global coords) so the host can align
            // the page's horizontal safe zone with it.
            PlayerChip(name: playerName, action: onEditName)
                .environment(\.cpPalette, barContent.map { palette.flippedForBar($0) } ?? palette)
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .global).maxX
                } action: { maxX in
                    onChipRight(maxX)
                }
                .padding(.trailing, 12)
        }
        .frame(height: 56)
    }
}

private extension CPPalette {
    /// Route a game-supplied bar content color through the tokens the bar's chip
    /// actually reads (label/icon = onSurface, border = outline at 50%), so
    /// everything on the bar flips together. The chip's fill keeps reading
    /// `primary` — the game accent — untouched, matching Android.
    func flippedForBar(_ content: Color) -> CPPalette {
        var p = self
        p.onSurface = content
        p.onSurfaceVariant = content
        p.outline = content.opacity(0.5)
        return p
    }
}
