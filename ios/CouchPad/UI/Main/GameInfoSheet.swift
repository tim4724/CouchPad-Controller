import SwiftUI
import AVFoundation

// MARK: - GameInfoSheet

struct GameInfoSheet: View {
    let game: Game
    let onScan: () -> Void
    let onEnterCode: () -> Void

    @Environment(\.cpPalette) private var palette

    init(game: Game, onScan: @escaping () -> Void, onEnterCode: @escaping () -> Void) {
        self.game = game
        self.onScan = onScan
        self.onEnterCode = onEnterCode
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(game.name)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(palette.onSurface)
                Spacer()
                if let range = game.playersRange {
                    playersChip(range)
                }
            }

            // A live game shows its gameplay loop, muted until the user unmutes
            // it; a not-yet-live game (no video) shows its cover art instead.
            Group {
                if game.video != nil {
                    GameplayLoopView(game: game)
                } else {
                    GameArt(game: game)
                }
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 16))

            if !game.tvApps.isEmpty || game.displayHost != nil {
                PlatformTiles(game: game)
            }

            // The app is the controller, so a first-timer who taps the card
            // learns they need the game running on a big screen first — then can
            // act right here. Deliberately path-free ("start it", not "open the
            // app / the site") — where the game runs is the platform-chip row's
            // job (PlatformTiles).
            if game.isLive {
                VStack(alignment: .leading, spacing: 16) {
                    StepRow(number: 1, text: AttributedString(
                        String(format: String(localized: "Start %@ on your TV."), game.name)))
                    StepRow(number: 2, text: AttributedString(
                        String(localized: "Scan the room code it shows.")))
                }
                .padding(.vertical, 4)
                JoinButtons(onScan: onScan, onEnterCode: onEnterCode)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 28)
    }

    // "1–8" + person glyph: the glyph stands in for the word "players", so the
    // count range needs no translation (Game.playersRange).
    private func playersChip(_ range: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: "person.2.fill")
                .font(.system(size: 13, weight: .medium))
            Text(range)
                .font(.cpLabelLarge)
        }
        .foregroundStyle(palette.onSurface)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(palette.surfaceContainerHighest))
    }
}

// MARK: - PlatformTiles

/// Where the game runs, as a row of equal device tiles: one per declared TV app
/// (dimmed, with the shared "Coming soon" copy, when not yet live) plus a tile
/// for the browser path. This row is the sheet's only mention of platforms and
/// host, so the play steps stay path-free.
///
/// Deliberately no brand logos: Apple licenses only its word marks to third
/// parties, and Google's Android TV guidance excludes the robot — the neutral
/// TV glyph + name is the compliant version of the same message (Android
/// matches).
private struct PlatformTiles: View {
    let game: Game

    @Environment(\.cpPalette) private var palette

    // Platforms with native TV apps (manifest tvApps): id -> the nearby-card
    // device label, reused. Unknown manifest ids simply have no tile.
    private static let platforms: [(String, LocalizedStringResource)] = [
        ("appletv", "Apple TV"),
        ("androidtv", "Android TV"),
    ]

    var body: some View {
        HStack(spacing: 9) {
            ForEach(Self.platforms, id: \.0) { id, name in
                if let status = game.tvApps[id] {
                    tile(icon: "tv", label: String(localized: name), soon: status != "live")
                }
            }
            if let host = game.displayHost {
                // The zero-width space before each dot is an invisible break hint:
                // a narrow tile wraps to "hexstacker" / ".com" instead of truncating.
                tile(icon: "globe", label: host.replacingOccurrences(of: ".", with: "\u{200B}."), soon: false)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    // A not-yet-live tile dims its icon and label to 45% — same state the
    // poster's "Coming soon" chip marks, signalled here by dimming instead of
    // a color swap.
    private func tile(icon: String, label: String, soon: Bool) -> some View {
        let base = palette.onSurface
        let content = soon ? base.opacity(0.45) : base
        return VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(content)
            Text(label)
                .font(.cpLabelMedium)
                .foregroundStyle(content)
                .multilineTextAlignment(.center)
                .lineLimit(2)
            if soon {
                Text(String(localized: "Coming soon"))
                    .font(.caption2)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .multilineTextAlignment(.center)
            }
        }
        // Top-aligned so icons and names line up across tiles even when one
        // tile carries the extra "Coming soon" line. Highest, not High — the
        // sheet surface itself is surfaceContainerHigh, so the tile needs the
        // next step to be visible on it.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.vertical, 12)
        .padding(.horizontal, 6)
        .background(RoundedRectangle(cornerRadius: 16).fill(palette.surfaceContainerHighest))
    }
}

// MARK: - GameplayLoopView

/// A gameplay loop, fetched to cache on demand (TrailerCache) and played from
/// disk. Cover art fills the slot immediately; the player sits on top and stays
/// transparent until frames render, so the art shows through while the trailer
/// downloads and simply disappears behind the first frame.
///
/// Every open starts muted; a clip with an audio track gets a mute toggle. A tap
/// plays the clip fullscreen (TrailerFullscreenView), pausing this one meanwhile.
/// Android matches throughout.
struct GameplayLoopView: View {
    let game: Game

    @State private var localURL: URL?
    @State private var hasAudio = false
    @State private var muted = true
    @State private var progress: Double?
    @State private var fullscreen = false

    var body: some View {
        ZStack {
            GameArt(game: game)
            if let localURL {
                LoopingPlayerView(url: localURL, muted: muted, paused: fullscreen)
                    .contentShape(Rectangle())
                    .onTapGesture { fullscreen = true }
                    .accessibilityElement()
                    .accessibilityLabel(String(localized: "Play fullscreen"))
                    .accessibilityAddTraits(.isButton)
            }
        }
        // A download in flight — never shown for a cached clip. Stays full from the
        // last byte until the player takes over. Inset as a pill on its own track,
        // clear of the rounded corners.
        .overlay(alignment: .bottom) {
            if let progress, localURL == nil {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.black.opacity(0.35))
                        Capsule().fill(game.accentColor).frame(width: geo.size.width * progress)
                    }
                }
                .frame(height: 4)
                .padding(.horizontal, 14)
                .padding(.bottom, 6)
            }
        }
        // The puck matches the scanner's flashlight toggle — the symbol shows the
        // state, the label names the action.
        .overlay(alignment: .topTrailing) {
            if hasAudio {
                Button {
                    muted.toggle()
                    // Unmuting is a request to hear it, so take the game category:
                    // audible through the silent switch, mixed over the user's music.
                    if !muted { GameAudioSession.configureOnce() }
                } label: {
                    Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Color.black.opacity(0.35), in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(muted ? String(localized: "Unmute") : String(localized: "Mute"))
                .padding(8)
            }
        }
        .fullScreenCover(isPresented: $fullscreen) {
            if let localURL { TrailerFullscreenView(url: localURL) }
        }
        .task {
            guard localURL == nil,
                  let remote = game.video.flatMap(URL.init(string:)) else { return }
            // Before any player exists: a muted AVPlayer still activates the shared
            // audio session, and the default category would stop the player's music.
            await GameAudioSession.configureForMutedTrailer()
            // Starts no sooner than 0.6s after the sheet opens, cached or not, so the
            // clip never starts under the sheet's slide-in. Any longer reads as loading.
            async let fetched = TrailerCache.fetch(remote) { fraction in
                Task { @MainActor in progress = fraction }
            }
            try? await Task.sleep(for: .seconds(0.6))
            guard let local = await fetched else { return }
            hasAudio = (try? await AVURLAsset(url: local).loadTracks(withMediaType: .audio))?.isEmpty == false
            localURL = local
        }
    }
}

// MARK: - TrailerFullscreenView

/// The clip fullscreen in landscape, with sound, played once from the start —
/// closing itself at the end. Unlike the inline loop it interrupts the user's
/// audio while it plays (GameAudioSession.beginFullscreenTrailer).
private struct TrailerFullscreenView: View {
    let url: URL

    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?

    var body: some View {
        ZStack {
            Color.black
            if let player {
                // The X sits on the video's corner rather than the screen's, where it
                // would straddle the pillarbox edge on a wider screen.
                PlayerLayerView(player: player)
                    .aspectRatio(16.0 / 9.0, contentMode: .fit)
                    .overlay(alignment: .topLeading) {
                        Button { dismiss() } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.black.opacity(0.35), in: Circle())
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(String(localized: "Close video"))
                        .padding(12)
                    }
            }
        }
        .ignoresSafeArea()
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear { ChromeState.shared.orientation = .landscape }
        .onDisappear {
            player?.pause()
            GameAudioSession.endFullscreenTrailer()
            ChromeState.shared.orientation = .portrait
        }
        .onReceive(NotificationCenter.default.publisher(for: AVPlayerItem.didPlayToEndTimeNotification)) { note in
            if let item = note.object as? AVPlayerItem, item === player?.currentItem { dismiss() }
        }
        .task {
            await GameAudioSession.beginFullscreenTrailer()
            let player = AVPlayer(url: url)
            self.player = player
            player.play()
        }
    }
}

private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerLayerUIView {
        let view = PlayerLayerUIView()
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ uiView: PlayerLayerUIView, context: Context) {}
}

/// A view whose backing layer is an AVPlayerLayer, so it resizes with the view.
private class PlayerLayerUIView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

// MARK: - Looping player (private)

private struct LoopingPlayerView: UIViewRepresentable {
    let url: URL
    let muted: Bool
    let paused: Bool

    final class PlayerUIView: PlayerLayerUIView {
        private var player: AVQueuePlayer?
        private var looper: AVPlayerLooper?

        func configure(url: URL, muted: Bool) {
            guard player == nil else { return }
            let item = AVPlayerItem(url: url)
            let queuePlayer = AVQueuePlayer()
            queuePlayer.isMuted = muted
            looper = AVPlayerLooper(player: queuePlayer, templateItem: item)
            player = queuePlayer
            playerLayer.player = queuePlayer
            playerLayer.videoGravity = .resizeAspectFill
            queuePlayer.play()
        }

        func update(muted: Bool, paused: Bool) {
            player?.isMuted = muted
            if paused { player?.pause() } else { player?.play() }
        }

        func teardown() {
            player?.pause()
            playerLayer.player = nil
            looper?.disableLooping()
            looper = nil
            player = nil
        }
    }

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.configure(url: url, muted: muted)
        return view
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        uiView.update(muted: muted, paused: paused)
    }

    static func dismantleUIView(_ uiView: PlayerUIView, coordinator: ()) {
        uiView.teardown()
    }
}
