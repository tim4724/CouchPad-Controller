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
    /// FULL-SCREEN — the keyboard is what fills the 220pt that would otherwise sit
    /// empty under the Save button, and the content clears it either way. Android
    /// doesn't mirror it: its sheet is content-height in both orientations, and
    /// focusing one that is still animating in opens and shuts the keyboard again
    /// (see the note in its ProfileSheet).
    @FocusState private var nameFocused: Bool
    @Environment(\.cpPalette) private var palette

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

    /// One layout, both orientations — unlike Android, which drops to a row when the
    /// keyboard leaves too little height. It isn't needed here: in a compact height the
    /// sheet is presented FULL-SCREEN (UIKit ignores `presentationDetents` there), and
    /// iOS's landscape keyboard is short enough that this content still clears it —
    /// checked on the smallest and largest phones we support.
    ///
    /// Choosing by measured space isn't open to us anyway: AppSheetContainer takes the
    /// content's IDEAL height for its detent, so a GeometryReader in here is proposed
    /// no height and collapses.
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(palette.onSurface)

            nameField
            saveButton
        }
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 28)
        .onAppear { nameFocused = true }
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

    private var saveButton: some View {
        Button(action: save) {
            Text(cta)
                .font(.cpTitleMedium)
                .foregroundStyle(palette.onPrimary)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 14))
        .controlSize(.large)
        .disabled(trimmedName.isEmpty)
    }
}
