import SwiftUI
import UIKit

// MARK: - ProfileSheet

struct ProfileSheet: View {
    let initial: Profile
    var title: String = String(localized: "Name")
    var cta: String = String(localized: "Save")
    let onSave: (Profile) -> Void

    @State private var name: String
    /// The sheet exists to type one name, so it opens with the field live. It also
    /// earns the height: in a compact height (landscape, in-game) UIKit presents this
    /// FULL-SCREEN, and the keyboard is what fills the space under the row. Android
    /// opens with the keyboard too, in both orientations (see its ProfileSheet).
    @FocusState private var nameFocused: Bool
    @Environment(\.cpPalette) private var palette
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.dismiss) private var dismiss

    init(initial: Profile, title: String = String(localized: "Name"),
         cta: String = String(localized: "Save"),
         onSave: @escaping (Profile) -> Void) {
        self.initial = initial
        self.title = title
        self.cta = cta
        self.onSave = onSave
        _name = State(initialValue: initial.name)
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func save() {
        guard !trimmedName.isEmpty else { return }
        onSave(Profile(name: trimmedName))
    }

    /// Two layouts, like Android's: stacked, and in a compact height (a phone in
    /// landscape, in-game) the field and Save sharing a row with the title dropped —
    /// the title carries no information, since the field always opens showing the
    /// name in force. AnyLayout switches without rebuilding the field, so a page
    /// turning the screen mid-rename keeps its focus and keyboard.
    ///
    /// The compact row leads with a close button. UIKit presents the sheet
    /// full-screen there, so there is no backdrop to tap away and the keyboard hides
    /// most of the sheet — swipe-down still dismisses, but nothing says so. Keeping it
    /// a real sheet (`presentationCompactAdaptation(.none)`) crashes inside UIKit's
    /// sheet interaction on iOS 26.
    ///
    /// Picked by size class, not measured space: AppSheetContainer takes the
    /// content's IDEAL height for its detent, so a GeometryReader in here is proposed
    /// no height and collapses.
    var body: some View {
        let compact = verticalSizeClass == .compact
        let layout = compact
            ? AnyLayout(HStackLayout(spacing: 12))
            : AnyLayout(VStackLayout(alignment: .leading, spacing: 16))
        VStack(alignment: .leading, spacing: 16) {
            if !compact {
                Text(title)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(palette.onSurface)
            }
            layout {
                if compact { closeButton }
                nameField
                saveButton(fullWidth: !compact)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, compact ? 16 : 24)
        .padding(.bottom, compact ? 20 : 28)
        .onAppear { nameFocused = true }
    }

    private var closeButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "xmark")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(palette.onSurface)
                .frame(width: 44, height: 44)
                .modifier(ChromeGlass(shape: Circle(), fallback: Color(uiColor: .tertiarySystemFill)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Cancel"))
    }

    private var nameField: some View {
        HStack(spacing: 8) {
            TextField("", text: $name)
                .focused($nameFocused)
                .textInputAutocapitalization(.words)
                // A player name is a proper noun — correcting it is always wrong. It
                // also drops the QuickType bar, which is height this sheet can't spare
                // in landscape.
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onSubmit(save)
                .font(.cpBodyLarge)
                .foregroundStyle(palette.onSurface)
                .onChange(of: name) { _, newValue in
                    if newValue.count > 16 {
                        name = String(newValue.prefix(16))
                    }
                }
            // The glyph is the whole label, and a `.plain` button is tappable only
            // where its label draws — so a bare emoji is a ~22pt target. The frame is
            // the touch area, not the art: Android's dice sits in an IconButton and
            // gets the 48dp minimum for free.
            Button { name = FunnyName.random() } label: {
                Text("🎲")
                    .font(.title3)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .frame(maxWidth: .infinity)
        .background(
            Color(uiColor: .tertiarySystemFill),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
    }

    private func saveButton(fullWidth: Bool) -> some View {
        Button(action: save) {
            Text(cta)
                .font(.cpTitleMedium)
                .foregroundStyle(palette.onPrimary)
                .frame(maxWidth: fullWidth ? .infinity : nil)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 14))
        .controlSize(.large)
        .disabled(trimmedName.isEmpty)
    }
}
