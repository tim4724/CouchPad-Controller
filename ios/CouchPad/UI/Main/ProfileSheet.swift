import SwiftUI
import UIKit

// MARK: - ProfileSheet

struct ProfileSheet: View {
    let initial: Profile
    var title: String = String(localized: "Name")
    var cta: String = String(localized: "Save")
    let onSave: (Profile) -> Void

    @State private var name: String
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

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(palette.onSurface)

            HStack(spacing: 8) {
                TextField("", text: $name)
                    .textInputAutocapitalization(.words)
                    .font(.cpBodyLarge)
                    .foregroundStyle(palette.onSurface)
                    .onChange(of: name) { _, newValue in
                        if newValue.count > 16 {
                            name = String(newValue.prefix(16))
                        }
                    }
                Button { name = FunnyName.random() } label: {
                    Text("🎲").font(.title3)
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

            Button {
                onSave(Profile(name: trimmedName))
            } label: {
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
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 28)
    }
}
