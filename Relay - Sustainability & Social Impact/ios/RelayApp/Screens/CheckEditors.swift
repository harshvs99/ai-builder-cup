import SwiftUI
import RelayCore
import RelayKit

/// The bottom sheet that edits one field of the request (Need, People, Where, Note). Edits go straight into the flow.
struct CheckEditorSheet: View {
    var editor: CheckEditor

    @Environment(\.strings) private var strings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                switch editor {
                case .need: NeedEditor()
                case .people: PeopleEditor()
                case .location: WhereEditor()
                case .note: NoteEditor()
                }
                Button(strings.common.done) { dismiss() }.buttonStyle(.relayFilled(Theme.Palette.accent))
            }
            .padding(Theme.Space.gutter)
        }
        .scrollDismissesKeyboard(.interactively)
        .presentationDetents([.medium, .large])
    }
}

private struct NeedEditor: View {
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings

    var body: some View {
        let flow = nav.ensureFlow()
        Text(strings.ask.editNeedTitle).font(.title2.bold()).accessibilityAddTraits(.isHeader)
        ChipFlow {
            ForEach(CheckModel.needEditorChips(flow.flow.draft, strings: strings)) { chip in
                let category = RequestCategory(chip.category)
                TintedChip(chip.label, systemImage: category.systemImage, tint: category.tint, isSelected: chip.isSelected) {
                    if chip.isTransport { flow.flow.toggleTransport() } else { flow.flow.pickCategory(chip.category) }
                }
            }
        }
        LimitedField(
            label: strings.ask.itemLabel, maxBytes: AskLogic.maxItem, singleLine: true,
            text: Binding(get: { flow.flow.draft.item }, set: { flow.flow.setItem($0) }))
    }
}

private struct PeopleEditor: View {
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings

    var body: some View {
        let flow = nav.ensureFlow()
        let draft = flow.flow.draft
        Text(strings.ask.editPeopleTitle).font(.title2.bold()).accessibilityAddTraits(.isHeader)
        HStack(spacing: Theme.Space.xl) {
            Spacer()
            stepButton("minus", label: strings.ask.fewerPeople, enabled: draft.people > 1) { flow.flow.setPeople(draft.people - 1) }
            Text("\(draft.people)").font(.largeTitle.bold()).monospacedDigit().frame(minWidth: 64)
            stepButton("plus", label: strings.ask.morePeople, enabled: draft.people < AskLogic.maxPeople) {
                flow.flow.setPeople(draft.people + 1)
            }
            Spacer()
        }
        Text(strings.ask.whoIsWithYou).font(.headline)
        ChipFlow {
            ForEach(AskLogic.flagOrder, id: \.self) { flag in
                TintedChip(strings.common.vulnFlag(flag), isSelected: draft.vulnFlags.contains(flag)) {
                    flow.flow.toggleFlag(flag)
                }
            }
        }
    }

    private func stepButton(_ symbol: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title2.weight(.semibold))
                .frame(width: 56, height: 56)
                .relayGlassCircle(tint: nil)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}

private struct WhereEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings

    var body: some View {
        let flow = nav.ensureFlow()
        let ui = model.locationUi
        Text(strings.ask.editWhereTitle).font(.title2.bold()).accessibilityAddTraits(.isHeader)
        LimitedField(
            label: strings.ask.landmarkLabel, maxBytes: AskLogic.maxLandmark, singleLine: true,
            text: Binding(get: { flow.flow.draft.landmark }, set: { flow.flow.setLandmark($0) }))
        HStack {
            Text(AskLogic.locationText(strings.ask, ui, accuracyM: model.locationFix?.accuracyM))
                .font(.subheadline).foregroundStyle(.secondary)
            Spacer(minLength: 0)
            if AskLogic.offersTurnOnLocation(ui) {
                Button(strings.common.turnOn) { turnOn() }.buttonStyle(.borderless).frame(minHeight: Theme.Size.minTouch)
            }
        }
    }

    private func turnOn() {
        Task {
            if model.permissions.location == .notDetermined {
                if await model.ensureLocation() { _ = await model.requestLocationFix() }
            } else {
                model.openAppSettings()
            }
        }
    }
}

private struct NoteEditor: View {
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings

    var body: some View {
        let flow = nav.ensureFlow()
        Text(strings.ask.editNoteTitle).font(.title2.bold()).accessibilityAddTraits(.isHeader)
        if !strings.common.isEnglish {
            Text(strings.ask.noteSentInEnglish).font(.subheadline).foregroundStyle(.secondary)
        }
        LimitedField(
            label: strings.ask.noteLabel, maxBytes: AskLogic.maxSummary, singleLine: false,
            text: Binding(get: { flow.flow.draft.summary }, set: { flow.flow.setSummary($0) }))
    }
}

/// Text field that cannot pass [maxBytes] UTF-8 bytes (the wire limit); a quiet "N characters left" line shows the room.
private struct LimitedField: View {
    var label: String
    var maxBytes: Int
    var singleLine: Bool
    @Binding var text: String
    @Environment(\.strings) private var strings

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text(label).font(.footnote).foregroundStyle(.secondary)
            TextField(label, text: Binding(get: { text }, set: { text = AskLogic.fit($0, maxBytes) }), axis: singleLine ? .horizontal : .vertical)
                .lineLimit(singleLine ? 1...1 : 2...4)
                .textInputAutocapitalization(.sentences)
                .padding(Theme.Space.m)
                .background(Theme.Palette.surfaceCard, in: RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous))
            Text(strings.ask.charsLeft(AskLogic.charsLeft(text, maxBytes))).font(.caption).foregroundStyle(.secondary)
        }
    }
}
