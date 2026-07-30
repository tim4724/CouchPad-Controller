import SwiftUI

/// The About hub. Lists the legal documents (privacy notice + imprint, kept
/// plainly labeled so the Impressum stays findable). Two taps from home
/// (info icon → here → document), satisfying the "reachable from within the
/// app" requirement for the Impressum.
struct AboutScreen: View {
    let onOpenPrivacy: () -> Void
    let onOpenImprint: () -> Void

    @Environment(\.cpPalette) private var palette
    @Environment(\.openURL) private var openURL

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            VStack(spacing: 0) {
                AboutRow(label: "Privacy Policy", action: onOpenPrivacy)
                Divider()
                AboutRow(label: "Impressum", action: onOpenImprint)
                Divider()
                // The marketing site is a full website, not a legal doc, so it opens
                // in the system browser rather than the in-app WebDocScreen viewer.
                AboutRow(label: "couchpad.games", icon: "arrow.up.forward") {
                    if let url = URL(string: CP.localizedWebsiteURL) { openURL(url) }
                }
                Divider()

                Spacer()

                Text("Version \(CP.appVersion)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 20)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AboutRow: View {
    let label: LocalizedStringKey
    // "chevron.right" = pushes an in-app screen; external links pass "arrow.up.forward".
    var icon: String = "chevron.right"
    let action: () -> Void

    @Environment(\.cpPalette) private var palette

    var body: some View {
        Button(action: action) {
            HStack {
                Text(label)
                    .font(.cpBodyLarge)
                    .foregroundStyle(palette.onSurface)
                Spacer()
                Image(systemName: icon)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 18)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
