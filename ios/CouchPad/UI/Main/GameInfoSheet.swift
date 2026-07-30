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
            Text(game.name)
                .font(.title3.weight(.semibold))
                .foregroundStyle(palette.onSurface)

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

            if let players = game.playersLabel {
                Text(players)
                    .font(.cpTitleMedium)
                    .foregroundStyle(palette.primary)
            }

            // The app is the controller, so a first-timer who taps the card
            // learns they need the game running on a big screen first — then can
            // act right here.
            if game.isLive {
                VStack(alignment: .leading, spacing: 12) {
                    StepRow(number: 1, text: openStepText)
                    StepRow(number: 2, text: AttributedString(
                        String(localized: "Scan the room code it shows to play.")))
                }
                JoinButtons(onScan: onScan, onEnterCode: onEnterCode)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 28)
    }

    // Step 1 with the game's host given the semibold-accent run, wherever the
    // localized template places it.
    private var openStepText: AttributedString {
        let host = game.displayHost ?? CP.launcherHost
        let template = String(localized: "Open %@ on your TV or laptop.")
        let parts = template.components(separatedBy: "%@")
        var result = AttributedString(parts.first ?? "")
        var hostRun = AttributedString(host)
        hostRun.font = .cpBodyLarge.weight(.semibold)
        hostRun.foregroundColor = game.accentColor
        result += hostRun
        if parts.count > 1 { result += AttributedString(parts[1]) }
        return result
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
