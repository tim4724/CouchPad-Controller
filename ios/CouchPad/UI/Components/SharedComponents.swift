import SwiftUI
import UIKit

// MARK: - PlayerChip

/// Tappable player-identity chip (home header + in-game bar).
struct PlayerChip: View {
    let name: String
    let action: () -> Void

    @Environment(\.cpPalette) private var palette

    init(name: String, action: @escaping () -> Void) {
        self.name = name
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "person.crop.circle.fill")
                    .symbolRenderingMode(.hierarchical)
                Text(displayName)
                    .font(.cpLabelLarge)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        // Home inherits the root mono tint; the game host's remapped palette
        // makes this the game accent there.
        .tint(palette.primary)
    }

    private var displayName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? String(localized: "Set name") : name
    }
}

// MARK: - RetryCover

/// Opaque "couldn't reach the server / try again" cover shown over a dead WebView
/// (host unreachable) — same copy and shape in the game host and the legal doc
/// viewer. The fill differs (a live game's surface vs the screen background), so the
/// caller passes both it and its content color; the button follows the palette accent.
struct RetryCover: View {
    let background: Color
    let foreground: Color
    let onRetry: () -> Void

    @Environment(\.cpPalette) private var palette

    var body: some View {
        ZStack {
            background.ignoresSafeArea()
            VStack(spacing: 24) {
                // Short form — the Retry button already says "try again".
                Text(String(localized: "Couldn’t reach the server."))
                    .font(.cpBodyLarge)
                    .foregroundStyle(foreground)
                    .multilineTextAlignment(.center)
                Button(action: onRetry) {
                    Text(String(localized: "Try again")).font(.cpTitleMedium)
                }
                .buttonStyle(.borderedProminent)
                .tint(palette.primary)
            }
            .padding(.horizontal, 32)
        }
    }
}

// MARK: - JoiningCover

/// "Joining…" cover over a page that hasn't painted yet: home shows it while the relay
/// resolves a code, the game host while the controller loads, so one join reads as a
/// single loading screen. The fill differs (a themed game surface vs the screen
/// background), so the caller passes it and its content color — as does the message:
/// home hasn't discovered the game yet, the host names it.
struct JoiningCover: View {
    let message: String
    let background: Color
    let foreground: Color
    /// The game's accent, once the page has sent its §4 theme metas. Nil on home, which
    /// has no game yet.
    var tint: Color? = nil

    var body: some View {
        background
            .ignoresSafeArea()
            .overlay {
                VStack(spacing: 16) {
                    ProgressView()
                        .tint(tint)
                    Text(message)
                        .font(.cpBodyMedium)
                        .foregroundStyle(foreground)
                }
            }
    }
}

// MARK: - ArtCache

/// Process-wide memoized async decode of artwork, keyed by manifest art path:
/// the bundled copy when one ships (assets are flattened into the bundle root, so
/// lookup uses lastPathComponent only), else the ArtworkCache copy downloaded from
/// the manifest URL. Both lookups key on the URL/name, never on content, so a
/// changed image must ship under a new file name or ?v= — see ArtworkCache.
enum ArtCache {
    private static let lock = NSLock()
    private static var images: [String: UIImage] = [:]
    private static var failures: Set<String> = []
    private static var inFlight: [String: Task<UIImage?, Never>] = [:]

    /// Synchronous accessor: non-nil only after a successful decode has been memoized.
    static func cached(forArt path: String) -> UIImage? {
        lock.lock()
        defer { lock.unlock() }
        return images[path]
    }

    /// Decode once per art path, off-main, memoized forever.
    static func uiImage(forArt path: String) async -> UIImage? {
        let pending: Task<UIImage?, Never>? = locked {
            if images[path] != nil || failures.contains(path) { return nil }
            if let task = inFlight[path] { return task }
            let task = Task<UIImage?, Never>.detached(priority: .userInitiated) {
                let result = await decode(artPath: path)
                locked {
                    if let result {
                        images[path] = result
                    } else {
                        failures.insert(path)
                    }
                    inFlight[path] = nil
                }
                return result
            }
            inFlight[path] = task
            return task
        }
        if let pending {
            return await pending.value
        }
        return locked { images[path] }
    }

    /// Fire-and-forget decodes for every game's art. Called at scene connect so the
    /// posters are ready at (or a beat after) first paint instead of popping in when
    /// each GameArt's .task gets around to them; those tasks then join the in-flight
    /// decode or hit the memo.
    static func prewarm(_ games: [Game]) {
        for game in games {
            guard let art = game.art else { continue }
            Task { _ = await uiImage(forArt: art) }
        }
    }

    private static func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Decoded-bitmap ceiling. Cards render at most ~410pt wide (~1230px at 3x),
    /// so 720p covers every device while costing a quarter of the memory of the
    /// shipped 1080p sources.
    private static let maxPixelSize = CGSize(width: 1280, height: 720)

    private static func decode(artPath: String) async -> UIImage? {
        if let data = bundledData(artPath: artPath) {
            return decode(data: data)
        }
        // Not shipped in this build — the served manifest introduced or
        // re-versioned it. Pull it through the URL-keyed disk cache.
        guard let url = remoteArtURL(artPath),
              let file = await ArtworkCache.fetch(url),
              let data = try? Data(contentsOf: file)
        else { return nil }
        return decode(data: data)
    }

    private static func bundledData(artPath: String) -> Data? {
        let file = (artPath as NSString).lastPathComponent
        let name = (file as NSString).deletingPathExtension
        let ext = (file as NSString).pathExtension
        guard
            !name.isEmpty,
            let url = Bundle.main.url(forResource: name, withExtension: ext.isEmpty ? nil : ext)
        else { return nil }
        return try? Data(contentsOf: url)
    }

    private static func decode(data: Data) -> UIImage? {
        guard let image = UIImage(data: data) else { return nil }   // WebP decodes natively on iOS 17
        // UIImage(data:) only parses the header — without forcing it here, the
        // bitmap decode happens lazily inside the main thread's first CA commit,
        // defeating this off-main task. preparingThumbnail also downsamples
        // (aspect-preserving) in the same pass.
        if image.size.width > maxPixelSize.width || image.size.height > maxPixelSize.height,
           let thumbnail = image.preparingThumbnail(of: maxPixelSize) {
            return thumbnail
        }
        return image.preparingForDisplay() ?? image
    }
}

// MARK: - GameArt

/// Cover art for a game, or an accent-gradient fallback when art is missing / fails to decode.
struct GameArt: View {
    let game: Game

    @Environment(\.cpPalette) private var palette
    @State private var image: UIImage?
    @State private var decodeFailed = false

    init(game: Game) {
        self.game = game
        if let art = game.art {
            _image = State(initialValue: ArtCache.cached(forArt: art))
        }
    }

    var body: some View {
        Rectangle()
            .fill(palette.surfaceVariant)
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else if game.art == nil || decodeFailed {
                    fallbackGradient
                }
            }
            .clipped()
            .accessibilityLabel(game.name)
            .task(id: game.art) {
                guard image == nil, let art = game.art else { return }
                if let decoded = await ArtCache.uiImage(forArt: art) {
                    image = decoded
                } else {
                    decodeFailed = true
                }
            }
    }

    private var fallbackGradient: LinearGradient {
        LinearGradient(
            colors: [game.accentColor.opacity(0.50), game.accentColor.opacity(0.14)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

// MARK: - GameIcon

/// A game's square brand mark — the manifest `icon`, not the 16:9 cover (a poster crop is
/// unreadable this small). Falls back to the TV glyph for a game with no icon, or an
/// advert that resolved to an unknown launcher subdomain. The mark fills the tile and is
/// clipped to it: icons ship in their own app-icon frame (rounded square, own backdrop),
/// so a plate underneath would only show as a ring around a frame.
struct GameIcon: View {
    let game: Game
    let tint: Color

    @State private var image: UIImage?

    init(game: Game, tint: Color) {
        self.game = game
        self.tint = tint
        if let icon = game.icon {
            _image = State(initialValue: ArtCache.cached(forArt: icon))
        }
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 48, height: 48)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                Image(systemName: "tv")
                    .font(.system(size: 26))
                    .foregroundStyle(tint)
                    .frame(width: 48, height: 48)
            }
        }
        .task(id: game.icon) {
            guard image == nil, let icon = game.icon else { return }
            image = await ArtCache.uiImage(forArt: icon)
        }
    }
}

// MARK: - StepRow

/// Numbered instruction row: 28pt solid-primary circle + attributed body text.
struct StepRow: View {
    let number: Int
    let text: AttributedString

    @Environment(\.cpPalette) private var palette

    init(number: Int, text: AttributedString) {
        self.number = number
        self.text = text
    }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                // Solid primary, not primaryContainer — the pale container tint is
                // nearly invisible against the sheet's surface in light mode.
                Circle()
                    .fill(palette.primary)
                    .frame(width: 28, height: 28)
                Text("\(number)")
                    .font(.cpLabelLarge)
                    .foregroundStyle(palette.onPrimary)
            }
            Text(text)
                .font(.cpBodyLarge)
                .foregroundStyle(palette.onSurface)
        }
    }
}

// MARK: - JoinButtons

/// The two join actions — shared by the home Join card and a live game's info sheet.
struct JoinButtons: View {
    let onScan: () -> Void
    let onEnterCode: () -> Void

    @Environment(\.cpPalette) private var palette

    var body: some View {
        VStack(spacing: 10) {
            // Coral is reserved for this one CTA (site --action rule): large/bold only,
            // everything else stays on the neutral chrome.
            Button(action: onScan) {
                Label("Scan code", systemImage: "qrcode.viewfinder")
                    .font(.cpTitleMedium)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(CPPalette.actionCoral)
            .buttonBorderShape(.roundedRectangle(radius: 14))
            .controlSize(.large)

            // Explicit tonal fill: `.bordered` would derive a translucent fill from
            // the ambient tint and never read the palette's secondaryContainer, so
            // the fill would drift from Android's FilledTonalButton.
            Button(action: onEnterCode) {
                Text("Enter code manually")
                    .font(.cpTitleMedium)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
            }
            .buttonStyle(.plain)
            .background(
                palette.secondaryContainer,
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .foregroundStyle(palette.onSecondaryContainer)
        }
    }
}

// MARK: - PosterStatusChip

/// Solid accent = live, accent-tinted dark = coming soon. The chip can land on
/// bright art (the scrim thins toward its top), so the soon-variant needs its own
/// dark base rather than a bare translucent tint. Shared by the home poster cards
/// and the info sheet's art overlay.
struct PosterStatusChip: View {
    let game: Game

    var body: some View {
        if game.isLive {
            Text("Live")
                .font(.cpLabelMedium)
                .foregroundStyle(Color.black.opacity(0.85))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Capsule().fill(game.accentColor))
        } else {
            Text("Coming soon")
                .font(.cpLabelMedium)
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(
                    ZStack {
                        Capsule().fill(Color.black.opacity(0.55))
                        Capsule().fill(game.accentColor.opacity(0.32))
                    }
                )
        }
    }
}

// MARK: - PressableCardButtonStyle

/// Card press micro-interaction: spring scale to 0.97 while pressed.
struct PressableCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .animation(.spring(response: 0.35, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

// MARK: - AppSheetContainer

/// House-style sheet: opens fully expanded at its content height, no grabber,
/// 28pt corners, surfaceContainerHigh background.
struct AppSheetContainer<Content: View>: View {
    @Environment(\.cpPalette) private var palette
    @State private var measuredHeight: CGFloat = 0
    /// A game's theme-color, used as the sheet surface so an in-game sheet reads as
    /// part of the game. Nil (the default) keeps the neutral surface. Callers gate on
    /// luminance so the surface stays dark enough for the sheet's light text.
    private let surfaceTint: Color?
    private let content: () -> Content

    init(surfaceTint: Color? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.surfaceTint = surfaceTint
        self.content = content
    }

    var body: some View {
        // The ScrollView engages only when the detent gets clamped below the
        // content height (small displays, large text) — .basedOnSize keeps a
        // fitting sheet from bouncing.
        ScrollView {
            content()
                .frame(maxWidth: .infinity, alignment: .top)
                // Take the content's IDEAL height: the detent starts near zero, and
                // without this the compressed first layout (truncated text, collapsed
                // aspect-ratio views) self-consistently measures as the final height.
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.size.height
                } action: { height in
                    measuredHeight = height
                }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .presentationDetents([.height(max(measuredHeight, 1))])
        .presentationDragIndicator(.hidden)
        .presentationCornerRadius(28)
        .presentationBackground(surfaceTint ?? palette.surfaceContainerHigh)
    }
}

extension View {
    func appSheet<Item: Identifiable, Content: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        surfaceTint: Color? = nil,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        sheet(item: item, onDismiss: onDismiss) { item in
            AppSheetContainer(surfaceTint: surfaceTint) { content(item) }
        }
    }
}
