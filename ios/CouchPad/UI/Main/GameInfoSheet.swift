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

            // A live game shows its muted gameplay loop; a not-yet-live game
            // (no video) shows its cover art instead.
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
            .overlay(alignment: .bottomTrailing) {
                if !game.isLive {
                    PosterStatusChip(game: game)
                        .padding(14)
                }
            }

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

/// A muted gameplay loop, fetched to cache on demand (TrailerCache) and played
/// from disk. Cover art fills the slot immediately; the player sits on top and
/// stays transparent until frames render, so the art shows through while the
/// trailer downloads and simply disappears behind the first frame.
struct GameplayLoopView: View {
    let game: Game

    @State private var localURL: URL?

    var body: some View {
        ZStack {
            GameArt(game: game)
            if let localURL {
                LoopingPlayerView(url: localURL)
            }
        }
        .task {
            guard localURL == nil,
                  let remote = game.video.flatMap(URL.init(string:)) else { return }
            // Before any player exists: a muted AVPlayer still activates the shared
            // audio session, and the default category would stop the player's music.
            await GameAudioSession.configureForMutedTrailer()
            localURL = await TrailerCache.fetch(remote)
        }
    }
}

// MARK: - Looping player (private)

private struct LoopingPlayerView: UIViewRepresentable {
    let url: URL

    final class PlayerUIView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }

        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

        private var player: AVQueuePlayer?
        private var looper: AVPlayerLooper?

        func configure(url: URL) {
            guard player == nil else { return }
            let item = AVPlayerItem(url: url)
            let queuePlayer = AVQueuePlayer()
            queuePlayer.isMuted = true
            looper = AVPlayerLooper(player: queuePlayer, templateItem: item)
            player = queuePlayer
            playerLayer.player = queuePlayer
            playerLayer.videoGravity = .resizeAspectFill
            queuePlayer.play()
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
        view.configure(url: url)
        return view
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {}

    static func dismantleUIView(_ uiView: PlayerUIView, coordinator: ()) {
        uiView.teardown()
    }
}
