import SwiftUI
import UIKit

// MARK: - MainScreen (home / launcher)

struct MainScreen: View {
    let deepLink: String?
    /// True while this screen is the visible top of the stack. Drives the rejoin
    /// poll: onAppear does NOT re-fire on NavigationStack pop-back, and scenePhase
    /// proved unreliable as a task trigger here (stuck .inactive at launch), so the
    /// router's path emptiness is the signal — mirroring Android's STARTED gating.
    let isTopVisible: Bool
    let onDeepLinkConsumed: () -> Void
    let onJoin: (String, String, [String]) -> Void   // (joinUrl, title, allowedHosts)
    let onOpenLegalDoc: (String) -> Void
    let onOpenAbout: () -> Void

    init(deepLink: String?, isTopVisible: Bool, onDeepLinkConsumed: @escaping () -> Void,
         onJoin: @escaping (String, String, [String]) -> Void,
         onOpenLegalDoc: @escaping (String) -> Void,
         onOpenAbout: @escaping () -> Void) {
        self.deepLink = deepLink
        self.isTopVisible = isTopVisible
        self.onDeepLinkConsumed = onDeepLinkConsumed
        self.onJoin = onJoin
        self.onOpenLegalDoc = onOpenLegalDoc
        self.onOpenAbout = onOpenAbout
    }

    // MARK: State

    // Seeded synchronously (cached fetch, else bundled); the once-per-launch
    // refresh task below updates it live when the served manifest differs.
    @ObservedObject private var manifest = ManifestStore.shared
    private var games: [Game] { manifest.games }
    @State private var profile: Profile = ProfileStore.load()
    // Item-based presentation: values are snapshotted into the request at
    // present time. @State read inside a sheet/cover content closure is not
    // dependency-tracked and can be stale (verified on-device), so the sheets
    // must receive their inputs through the item.
    @State private var profileRequest: ProfileSheetRequest? = nil
    @State private var afterName: AfterName? = nil
    @State private var showCodeEntry = false
    @State private var codeText = ""
    @State private var codeLoading = false
    @State private var codeError: String? = nil
    @State private var scanRequest: ScanRequest? = nil
    @State private var rejoin: RecentRoom? = nil
    @State private var infoGame: Game? = nil
    // A scan/enter action tapped inside the info sheet, run once it dismisses.
    @State private var pendingSheetAction: AfterName? = nil
    @State private var joinCardHeight: CGFloat = 0
    // Haptic triggers (any change fires the paired sensoryFeedback).
    @State private var successTick = 0
    @State private var errorTick = 0

    @Environment(MessageCenter.self) private var messages
    @Environment(\.cpPalette) private var palette

    // MARK: Body

    var body: some View {
        ZStack(alignment: .bottom) {
            palette.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 20) {
                    // The game-end notice sits atop the content, above the rejoin card
                    // (present only while the room is alive) — a high-contrast strip
                    // where the player's eye already is, not a bottom overlay.
                    if let banner = messages.gameEndBanner {
                        GameEndBanner(message: banner) { messages.dismissGameEndBanner() }
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    if let rejoin {
                        RejoinCard(room: rejoin) {
                            resolveAndJoin(rejoin.joinUrl)
                        }
                        .transition(.opacity.combined(with: .scale(scale: 0.96)))
                    }

                    VStack(spacing: 12) {
                        ForEach(games) { game in
                            GameCard(game: game) { infoGame = game }
                        }
                    }

                    Color.clear
                        .frame(height: joinCardHeight + 12)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .animation(.spring(duration: 0.45), value: messages.gameEndBanner)
            }

            // Bottom protection, mirroring Android's nav-area fade: posters dissolve
            // into the background before the home-indicator zone instead of ending in
            // a hard cut beneath the join card. Fades from the background's own hue at
            // zero alpha (not .clear) so the mid-ramp doesn't gray out light posters.
            LinearGradient(
                stops: [
                    .init(color: palette.background.opacity(0), location: 0),
                    .init(color: palette.background, location: 1)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 96)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .ignoresSafeArea(edges: .bottom)
            .allowsHitTesting(false)

            JoinCard(
                host: joinHost,
                onScan: { requireName(.scan) },
                onEnterCode: { requireName(.enterCode) }
            )
            .onGeometryChange(for: CGFloat.self) { proxy in
                proxy.size.height
            } action: { height in
                joinCardHeight = height
            }
            .padding(.horizontal, 12)
            // The safe area already clears the home indicator — anything more
            // reads as a floating gap.
            .padding(.bottom, 4)

            if codeLoading {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Joining…")
                        .font(.cpLabelLarge)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .background(.regularMaterial, in: Capsule())
                .padding(.bottom, joinCardHeight + 28)
                .transition(.opacity)
            }
        }
        .navigationTitle("CouchPad")
        // "Controller" subtitle under the app name, matching Android's HomeTopBar.
        // Native subtitle slot is iOS 26+; on earlier releases the bar shows the
        // title alone (no subtitle affordance existed pre-26).
        .modifier(HomeNavigationSubtitle())
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            // The name capsule and the About button are unrelated actions, so they
            // read as two distinct glass pills rather than one merged container. On
            // iOS 26 a ToolbarSpacer(.fixed) is what splits the shared glass into two
            // pills with the system's standard gap — and the system keeps them the
            // same height (the icon-only About item becomes a circle on its own).
            // Labels are .primary (scheme-adaptive) so the glass legibility flip works
            // over poster art.
            ToolbarItem(placement: .topBarTrailing) {
                chipButton()
            }
            if #available(iOS 26.0, *) {
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
            }
            ToolbarItem(placement: .topBarTrailing) {
                aboutButton()
            }
        }
        .sensoryFeedback(.success, trigger: successTick)
        .sensoryFeedback(.error, trigger: errorTick)
        .ignoresSafeArea(.keyboard)
        .onChange(of: deepLink, initial: true) { _, newValue in
            guard let link = newValue else { return }
            // A legal-page App Link opens the in-app doc viewer; anything else is a
            // join. The URL keeps its locale segment (/en/privacy vs /privacy), so
            // the viewer loads the right variant.
            if CP.legalTitle(for: URL(string: link)) != nil {
                onOpenLegalDoc(link)
            } else {
                resolveAndJoin(link)
            }
            onDeepLinkConsumed()
        }
        .onChange(of: codeText) { _, newValue in
            if newValue.count > 16 {
                codeText = String(newValue.prefix(16))
            }
        }
        .task(id: isTopVisible) {
            guard isTopVisible else { return }
            await runRejoinPoll()
        }
        // Pull the served manifest once per launch (ManifestStore guards
        // re-entry, so push/pop re-creating this screen doesn't refetch).
        .task { await manifest.refresh() }
        .onChange(of: messages.gameEndBanner) { _, banner in
            // A just-ended game (banner appeared) may have cleared the saved room — a
            // room_not_found end drops it. Reflect the store the instant the banner
            // lands so the rejoin card clears immediately, rather than lingering up to a
            // poll tick (or being re-shown by a stale relay 'Found' while it catches up).
            if banner != nil {
                withAnimation(.spring(duration: 0.45)) { rejoin = RecentRoomStore.current() }
            }
        }
        .alert("Enter room code", isPresented: $showCodeEntry) {
            TextField("e.g. \(CP.sampleRoomCode)", text: $codeText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Cancel", role: .cancel) { codeError = nil }
            Button("Play") { submitCode(codeText) }
                .disabled(codeText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } message: {
            if let codeError {
                Text(codeError)
            } else {
                Text("The code is on your TV.")
            }
        }
        .fullScreenCover(item: $scanRequest) { request in
            QRScannerScreen { result in
                scanRequest = nil
                switch result {
                case .code(let raw):
                    // The launcher's own privacy/imprint URL isn't a room — route it
                    // to the doc viewer (host-validated: the payload is untrusted)
                    // instead of failing it, same as the Universal Link path.
                    if let legal = CP.scannedLegalUrl(raw) {
                        successTick += 1
                        onOpenLegalDoc(legal)
                        return
                    }
                    let outcome = JoinResolver.resolve(raw, games: request.games)
                    switch outcome {
                    case .success:
                        launchJoin(outcome, request.profile)
                    case .failure(let message):
                        errorTick += 1
                        messages.showToast(message)
                    }
                case .failure(let message):
                    errorTick += 1
                    messages.showToast(String(localized: "Scanner unavailable: \(message)"), long: true)
                case .cancelled:
                    break
                }
            }
        }
        .appSheet(item: $profileRequest, onDismiss: { afterName = nil }) { request in
            ProfileSheet(
                initial: request.profile,
                title: String(localized: request.gated ? "Enter your name" : "Name"),
                cta: String(localized: request.gated ? "Save & continue" : "Save"),
                onSave: { p in
                    ProfileStore.save(p)
                    profile = p
                    // Clear the pending action BEFORE dismissal so onDismiss
                    // doesn't cancel the just-run action.
                    let pending = afterName
                    afterName = nil
                    profileRequest = nil
                    if let pending {
                        perform(pending, p)
                    }
                }
            )
        }
        .appSheet(item: $infoGame, onDismiss: {
            // Fire the funnel only after the sheet is fully gone — presenting the
            // scanner/alert mid-dismiss would be dropped by UIKit.
            if let action = pendingSheetAction {
                pendingSheetAction = nil
                requireName(action)
            }
        }) { game in
            GameInfoSheet(
                game: game,
                onScan: { pendingSheetAction = .scan; infoGame = nil },
                onEnterCode: { pendingSheetAction = .enterCode; infoGame = nil }
            )
        }
    }

    // MARK: Derived

    private var joinHost: String {
        games.first(where: { $0.isLive })?.displayHost ?? CP.launcherHost
    }

    private var profileDisplayName: String {
        let trimmed = profile.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? String(localized: "Set name") : trimmed
    }

    private func chipButton() -> some View {
        Button {
            profileRequest = ProfileSheetRequest(gated: false, profile: profile)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "person.crop.circle.fill")
                    .symbolRenderingMode(.hierarchical)
                Text(profileDisplayName)
                    .font(.cpLabelLarge)
                    .lineLimit(1)
            }
            // .primary is scheme-adaptive, so the glass legibility flip works over posters.
            .foregroundStyle(Color.primary)
        }
    }

    private func aboutButton() -> some View {
        Button {
            onOpenAbout()
        } label: {
            // .primary rides the glass legibility adaptation; the default accent
            // tint would pin the color and ignore the poster art beneath the bar.
            Image(systemName: "info.circle")
                .foregroundStyle(Color.primary)
        }
        .accessibilityLabel("About")
    }

    // MARK: Join funnel

    @MainActor
    private func launchJoin(_ target: JoinOutcome, _ p: Profile) {
        guard case .success(let game, let roomCode, let joinUrl) = target else { return }
        successTick += 1
        RecentRoomStore.remember(game: game, joinUrl: joinUrl, roomCode: roomCode)
        onJoin(withProfile(joinUrl, p), game.name, game.hosts)
    }

    @MainActor
    private func perform(_ action: AfterName, _ p: Profile) {
        switch action {
        case .scan:
            scanRequest = ScanRequest(games: games, profile: p)
        case .enterCode:
            codeError = nil
            showCodeEntry = true
        case .join(let target):
            launchJoin(target, p)
        }
    }

    @MainActor
    private func requireName(_ action: AfterName) {
        if !profile.isSet {
            afterName = action
            profileRequest = ProfileSheetRequest(gated: true, profile: profile)
        } else {
            perform(action, profile)
        }
    }

    @MainActor
    private func resolveAndJoin(_ raw: String) {
        let outcome = JoinResolver.resolve(raw, games: games)
        switch outcome {
        case .success:
            requireName(.join(outcome))
        case .failure(let message):
            errorTick += 1
            messages.showToast(message)
        }
    }

    @MainActor
    private func submitCode(_ text: String) {
        codeError = nil
        codeLoading = true
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        Task { @MainActor in
            let outcome = await resolveTypedCode(trimmed, games: games)
            codeLoading = false
            switch outcome {
            case .success:
                codeText = ""
                launchJoin(outcome, profile)
            case .failure(let message):
                // Alerts auto-dismiss on any action; re-present with the error
                // as the message so the player can correct the code.
                errorTick += 1
                codeError = message
                showCodeEntry = true
            }
        }
    }

    // MARK: Rejoin polling

    @MainActor
    private func runRejoinPoll() async {
        // Re-read the saved room EVERY iteration — never capture it once. A game that
        // ended with room_not_found clears the store from under this still-running poll;
        // the next tick must honor that and drop the card, not re-show the dead room off
        // a stale captured value + a relay record that hasn't 404'd yet.
        while !Task.isCancelled {
            // No saved room (never joined, aged out, or cleared on a room_not_found end).
            guard let recent = RecentRoomStore.current() else {
                withAnimation(.spring(duration: 0.45)) { rejoin = nil }
                return
            }
            // No room code (the scanned URL didn't surface one) → we can't liveness-poll,
            // so surface the card unverified. A dead room is handled by gameEnded.
            if recent.roomCode.isEmpty {
                withAnimation(.spring(duration: 0.45)) { rejoin = recent }
                return
            }
            let result = await RoomDirectory.lookup(code: recent.roomCode, relayBase: recent.game.roomRelayBase)
            if Task.isCancelled { return }
            switch result {
            case .found:
                withAnimation(.spring(duration: 0.45)) { rejoin = recent }
            case .notFound:
                RecentRoomStore.clear()
                withAnimation(.spring(duration: 0.45)) {
                    rejoin = nil
                }
                return
            case .error:
                break // transient failure: keep last state
            }
            do {
                try await Task.sleep(for: .seconds(10))
            } catch {
                return
            }
        }
    }
}

// MARK: - Private types

/// Adds the "Controller" navigation subtitle where the OS supports it (iOS 26+).
/// A modifier rather than an inline `if #available` so the SDK-17 build compiles.
private struct HomeNavigationSubtitle: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.navigationSubtitle(Text("Controller"))
        } else {
            content
        }
    }
}

private enum AfterName {
    case scan
    case enterCode
    case join(JoinOutcome)
}

private struct ProfileSheetRequest: Identifiable {
    let id = UUID()
    let gated: Bool
    let profile: Profile
}

private struct ScanRequest: Identifiable {
    let id = UUID()
    let games: [Game]
    let profile: Profile
}

// MARK: - GameEndBanner

/// The game-end notice shown in the rejoin slot: a high-contrast strip that
/// auto-dismisses (timer lives in MessageCenter) and clears early on a tap or a
/// horizontal swipe.
private struct GameEndBanner: View {
    let message: String
    let onDismiss: () -> Void

    @Environment(\.cpPalette) private var palette
    @State private var dragX: CGFloat = 0

    var body: some View {
        Text(message)
            .font(.cpBodyMedium)
            .foregroundStyle(palette.inverseOnSurface)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.inverseSurface))
            .contentShape(Rectangle())
            .offset(x: dragX)
            .gesture(
                DragGesture()
                    .onChanged { dragX = $0.translation.width }
                    .onEnded { value in
                        if abs(value.translation.width) > 96 {
                            withAnimation(.easeOut(duration: 0.2)) {
                                dragX = value.translation.width > 0 ? 600 : -600
                            }
                            onDismiss()
                        } else {
                            withAnimation(.spring) { dragX = 0 }
                        }
                    }
            )
            .onTapGesture { onDismiss() }
    }
}

// MARK: - RejoinCard

private struct RejoinCard: View {
    let room: RecentRoom
    let onTap: () -> Void

    @Environment(\.cpPalette) private var palette

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                RejoinIcon(favicon: room.favicon)
                VStack(alignment: .leading, spacing: 2) {
                    // Just the controller's own page title (captured this session),
                    // falling back to the manifest's curated name until it's captured.
                    Text(room.title ?? room.game.name)
                        .font(.cpTitleMedium)
                        .foregroundStyle(palette.onSecondaryContainer)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if !room.roomCode.isEmpty {
                        Text("Room \(room.roomCode)")
                            .font(.cpBodyMedium)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(
                palette.secondaryContainer,
                in: RoundedRectangle(cornerRadius: 20, style: .continuous)
            )
        }
        .buttonStyle(PressableCardButtonStyle())
    }
}

// MARK: - RejoinIcon

/// The rejoin card's leading glyph: the game's captured controller favicon on a tile
/// whose shade is chosen from the icon's OWN content — a dark plate under a
/// light/transparent icon, a white plate under a dark one — so it never washes out
/// against the light card or a same-toned plate. Falls back to a play symbol when
/// nothing's been captured this session.
private struct RejoinIcon: View {
    let favicon: Favicon?

    @Environment(\.cpPalette) private var palette

    var body: some View {
        if let favicon {
            let plate: Color = favicon.contentIsLight ? Color(white: 0.13) : .white
            // ~10pt of breathing room inside the tile so the icon never touches the edge.
            Image(uiImage: favicon.image)
                .resizable()
                .scaledToFit()
                .frame(width: 28, height: 28)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .frame(width: 48, height: 48)
                .background(plate, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(palette.outlineVariant, lineWidth: 1)
                )
        } else {
            Image(systemName: "play.circle.fill")
                .font(.system(size: 40))
                .symbolRenderingMode(.hierarchical)
        }
    }
}

// MARK: - GameCard

private struct GameCard: View {
    let game: Game
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottom) {
                GameArt(game: game)
                    .aspectRatio(16.0 / 9.0, contentMode: .fit)
                    .frame(maxWidth: .infinity)

                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0.0),
                        .init(color: .clear, location: 0.55),
                        .init(color: Color.black.opacity(0.86), location: 1.0)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )

                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(game.name)
                            .font(.cpTitleMedium)
                            .foregroundStyle(.white)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    PosterStatusChip(game: game)
                }
                .padding(14)
            }
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: Color.black.opacity(0.15), radius: 4, y: 2)
        }
        .buttonStyle(PressableCardButtonStyle())
    }
}

// MARK: - JoinCard (floating, material blur over the poster list)

private struct JoinCard: View {
    let host: String
    let onScan: () -> Void
    let onEnterCode: () -> Void

    @Environment(\.cpPalette) private var palette

    private var openHostText: Text {
        let template = String(localized: "Open %@ on your TV or laptop.")
        let parts = template.components(separatedBy: "%@")
        return Text(parts.first ?? "")
            + Text(host).fontWeight(.semibold)
            + Text(parts.count > 1 ? parts[1] : "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Play")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(palette.onSurface)

                // The localized template positions the host; the host itself gets
                // the semibold run wherever the language puts it.
                openHostText
                    .font(.cpBodyMedium)
                    .foregroundStyle(.secondary)
            }

            JoinButtons(onScan: onScan, onEnterCode: onEnterCode)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .strokeBorder(.quaternary, lineWidth: 0.5)
        )
        .shadow(color: Color.black.opacity(0.15), radius: 18, y: 6)
    }
}
