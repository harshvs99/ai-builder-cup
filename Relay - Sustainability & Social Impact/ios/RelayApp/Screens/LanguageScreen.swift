import SwiftUI
import RelayCore
import RelayKit

/// First run: "Choose your language". Picking a row switches the whole app at once; Continue confirms.
struct LanguageScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.strings) private var strings
    @State private var noVoiceFor: Lang?
    @State private var showAddable = false

    var body: some View {
        let screen = LanguageScreenModel.make(selected: model.settings.language, added: model.settings.extraLanguages, strings: strings)
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                SmallCapsLabel(screen.appName, color: Theme.Palette.accent)
                Text(screen.title).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                Text(screen.subtitle).font(.body).foregroundStyle(.secondary)

                let rows = screen.shownRows + (showAddable ? screen.addableRows : [])
                VStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                        if index > 0 { Divider().padding(.leading, Theme.Space.l) }
                        LanguageRow(row: row, speak: { speak(row) }) { select(row.lang) }
                    }
                    if !showAddable && !screen.addableRows.isEmpty {
                        Divider().padding(.leading, Theme.Space.l)
                        Button { showAddable = true } label: {
                            Label(screen.addLanguageLabel, systemImage: "plus.circle")
                                .font(.headline)
                                .frame(maxWidth: .infinity, minHeight: Theme.Size.minTouch + 8, alignment: .leading)
                                .padding(.horizontal, Theme.Space.l)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Theme.Palette.accent)
                    }
                }
                .relaySurfaceCard()

                if let lang = noVoiceFor {
                    Text(LanguageScreenModel.noVoiceMessage(lang, strings: strings))
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, Theme.Space.gutter)
            .padding(.top, Theme.Space.xxl)
        }
        .background(Theme.Palette.surface)
        .safeAreaInset(edge: .bottom) {
            Button(screen.continueLabel, action: confirm)
                .buttonStyle(.relayFilled(Theme.Palette.accent))
                .padding(.horizontal, Theme.Space.gutter)
                .padding(.vertical, Theme.Space.s)
        }
    }

    private func select(_ lang: Lang) {
        noVoiceFor = nil
        showAddable = false
        model.updateSettings { $0.withLanguage(lang) }
    }

    private func speak(_ row: LanguageScreenModel.Row) {
        noVoiceFor = model.speak(row.greeting, lang: row.lang) ? nil : row.lang
    }

    private func confirm() {
        model.updateSettings { var s = $0; s.languageConfirmed = true; return s }
    }
}

private struct LanguageRow: View {
    var row: LanguageScreenModel.Row
    var speak: () -> Void
    var select: () -> Void

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            Button(action: select) {
                HStack(spacing: Theme.Space.m) {
                    Image(systemName: row.isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(row.isSelected ? Theme.Palette.accent : Color.secondary)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.nativeName).font(.headline).foregroundStyle(Color.primary)
                        Text(row.englishName).font(.subheadline).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: Theme.Size.minTouch + 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(row.isSelected ? .isSelected : [])

            Button(action: speak) {
                Image(systemName: "speaker.wave.2.fill")
                    .font(.title3)
                    .frame(width: Theme.Size.minTouch, height: Theme.Size.minTouch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(Theme.Palette.accent)
            .accessibilityLabel(row.speakLabel)
        }
        .padding(.horizontal, Theme.Space.l)
    }
}
