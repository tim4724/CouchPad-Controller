import SwiftUI
import UIKit

// MARK: - Hex color init

extension Color {
    /// Opaque sRGB color from a 0xRRGGBB literal.
    init(cpHex: UInt32) {
        self.init(
            .sRGB,
            red: Double((cpHex >> 16) & 0xFF) / 255.0,
            green: Double((cpHex >> 8) & 0xFF) / 255.0,
            blue: Double(cpHex & 0xFF) / 255.0,
            opacity: 1.0
        )
    }
}

// MARK: - Palette

/// MONO graphite chrome palette (Material 3 roles, pre-baked hex — never re-derive lerps).
/// Members are `var` only so the copy helpers can mutate a copy; `.light`/`.dark` are the
/// sole instances handed out.
struct CPPalette: Equatable {
    var primary, onPrimary, primaryContainer, onPrimaryContainer: Color
    var secondary, onSecondary, secondaryContainer, onSecondaryContainer: Color
    var background, onBackground, surface, onSurface: Color
    var surfaceVariant, onSurfaceVariant, outline, outlineVariant: Color
    var surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceBright: Color
    var error, onError, inverseSurface, inverseOnSurface, scrim: Color

    static let light = CPPalette(
        primary: Color(cpHex: 0x202024),
        onPrimary: Color(cpHex: 0xFFFFFF),
        primaryContainer: Color(cpHex: 0xE5E1E9),
        onPrimaryContainer: Color(cpHex: 0x1B1B1F),
        secondary: Color(cpHex: 0x625B71),
        onSecondary: Color(cpHex: 0xFFFFFF),
        secondaryContainer: Color(cpHex: 0xD7D6DD),
        onSecondaryContainer: Color(cpHex: 0x1B1B1F),
        background: Color(cpHex: 0xFAFAFB),
        onBackground: Color(cpHex: 0x1C1B1F),
        surface: Color(cpHex: 0xFAFAFB),
        onSurface: Color(cpHex: 0x1C1B1F),
        surfaceVariant: Color(cpHex: 0xE4E3E7),
        onSurfaceVariant: Color(cpHex: 0x46464B),
        outline: Color(cpHex: 0x77767C),
        outlineVariant: Color(cpHex: 0xCAC9CE),
        surfaceContainerLowest: Color(cpHex: 0xFFFFFF),
        surfaceContainerLow: Color(cpHex: 0xF0F0F1),
        surfaceContainer: Color(cpHex: 0xEBEBEC),
        surfaceContainerHigh: Color(cpHex: 0xE4E4E5),
        surfaceContainerHighest: Color(cpHex: 0xDDDDDE),
        surfaceBright: Color(cpHex: 0xFEF7FF),
        error: Color(cpHex: 0xB3261E),
        onError: Color(cpHex: 0xFFFFFF),
        inverseSurface: Color(cpHex: 0x313033),
        inverseOnSurface: Color(cpHex: 0xF4EFF4),
        scrim: Color(cpHex: 0x000000)
    )

    static let dark = CPPalette(
        primary: Color(cpHex: 0xE6E1E9),
        onPrimary: Color(cpHex: 0x1C1B20),
        primaryContainer: Color(cpHex: 0x47444D),
        onPrimaryContainer: Color(cpHex: 0xE6E1E9),
        secondary: Color(cpHex: 0xCCC2DC),
        onSecondary: Color(cpHex: 0x332D41),
        secondaryContainer: Color(cpHex: 0x35353B),
        onSecondaryContainer: Color(cpHex: 0xE6E1E9),
        background: Color(cpHex: 0x0F0F11),
        onBackground: Color(cpHex: 0xE6E1E5),
        surface: Color(cpHex: 0x0F0F11),
        onSurface: Color(cpHex: 0xE6E1E5),
        surfaceVariant: Color(cpHex: 0x46464B),
        onSurfaceVariant: Color(cpHex: 0xC7C6CC),
        outline: Color(cpHex: 0x90909A),
        outlineVariant: Color(cpHex: 0x44444A),
        surfaceContainerLowest: Color(cpHex: 0x040405),
        surfaceContainerLow: Color(cpHex: 0x151517),
        surfaceContainer: Color(cpHex: 0x19191B),
        surfaceContainerHigh: Color(cpHex: 0x222224),
        surfaceContainerHighest: Color(cpHex: 0x2A2A2C),
        surfaceBright: Color(cpHex: 0x363638),
        error: Color(cpHex: 0xF2B8B5),
        onError: Color(cpHex: 0x601410),
        inverseSurface: Color(cpHex: 0xE6E1E5),
        inverseOnSurface: Color(cpHex: 0x313033),
        scrim: Color(cpHex: 0x000000)
    )

    /// Copy with primary = accent, onPrimary = contentColorOn(accent). (Game-host accent remap.)
    func withAccent(_ accent: Color) -> CPPalette {
        var p = self
        p.primary = accent
        p.onPrimary = contentColorOn(accent)
        return p
    }
}

// MARK: - Luminance / contrast

/// WCAG relative luminance of a color's sRGB components.
func relativeLuminance(_ color: Color) -> Double {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    let uiColor = UIColor(color)
    if !uiColor.getRed(&r, green: &g, blue: &b, alpha: &a) {
        // Not an RGB-compatible color space — convert via CGColor to sRGB.
        guard
            let space = CGColorSpace(name: CGColorSpace.sRGB),
            let converted = uiColor.cgColor.converted(to: space, intent: .defaultIntent, options: nil),
            let components = converted.components, components.count >= 3
        else { return 0 }
        r = components[0]
        g = components[1]
        b = components[2]
    }
    func linearize(_ c: CGFloat) -> Double {
        let v = min(max(Double(c), 0), 1)
        return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
}

/// Black or white over an arbitrary background, whichever wins on WCAG contrast.
/// A naive luminance > 0.5 split picks white on saturated mid-tones (a coral like
/// #FF6B6B sits at ~0.33) even though black reads far better there — the real
/// black/white crossover is at luminance ≈ 0.179, so compare the two contrasts.
func contentColorOn(_ color: Color) -> Color {
    let l = relativeLuminance(color)
    let blackContrast = (l + 0.05) / 0.05   // black L = 0
    let whiteContrast = 1.05 / (l + 0.05)   // white L = 1
    return blackContrast >= whiteContrast ? .black : .white
}

// MARK: - Environment

private struct CPPaletteKey: EnvironmentKey {
    static let defaultValue = CPPalette.light
}

extension EnvironmentValues {
    var cpPalette: CPPalette {
        get { self[CPPaletteKey.self] }
        set { self[CPPaletteKey.self] = newValue }
    }
}

private struct CPThemedModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let palette: CPPalette = colorScheme == .dark ? .dark : .light
        content
            .environment(\.cpPalette, palette)
            // Mono brand accent for every system control (buttons, alerts, cursors).
            .tint(palette.primary)
    }
}

extension View {
    /// Injects `\.cpPalette` from the current system color scheme. Applied once at RootView.
    func cpThemed() -> some View {
        modifier(CPThemedModifier())
    }
}

// MARK: - Typography (Dynamic Type text styles; M3-role names kept at call sites)

extension Font {
    static let cpDisplayLarge = Font.largeTitle
    static let cpHeadlineLarge = Font.largeTitle
    static let cpHeadlineMedium = Font.title
    static let cpHeadlineSmall = Font.title2
    static let cpTitleLarge = Font.title2
    static let cpTitleMedium = Font.headline
    static let cpTitleSmall = Font.subheadline.weight(.medium)
    static let cpBodyLarge = Font.body
    static let cpBodyMedium = Font.subheadline
    static let cpBodySmall = Font.footnote
    static let cpLabelLarge = Font.subheadline.weight(.medium)
    static let cpLabelMedium = Font.footnote.weight(.medium)
    static let cpLabelSmall = Font.caption.weight(.medium)
}
