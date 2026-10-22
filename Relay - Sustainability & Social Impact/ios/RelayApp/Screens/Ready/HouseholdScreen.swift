import SwiftUI
import RelayCore
import RelayKit

/// Settings → "My household" (contract v2 section 3.2): asked once, editable later, kept on the phone.
struct HouseholdScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.strings) private var strings
    @State private var h = Household()
    @State private var loaded = false
    @State private var saved = false

    var body: some View {
        let r = strings.ready
        List {
            Section { Text(r.householdHelp).font(.subheadline).foregroundStyle(.secondary) }
            Section {
                Stepper(value: Binding(get: { h.people }, set: { h.people = $0 }), in: Household.minPeople...Household.maxPeople) {
                    HStack { Text(r.people); Spacer(); Text("\(h.people)").font(.title3.weight(.semibold)) }
                }
                .accessibilityLabel("\(r.people): \(h.people)")
                Toggle(r.elderly, isOn: $h.elderly)
                Toggle(r.children, isOn: $h.children)
                Toggle(r.infants, isOn: $h.infants)
                Toggle(r.pregnant, isOn: $h.pregnant)
                Toggle(r.disability, isOn: $h.disability)
                Toggle(r.livestock, isOn: $h.livestock)
            }
            Section(r.medical) {
                TextField(r.medicalHint, text: Binding(get: { h.medicalNeeds }, set: { h.medicalNeeds = String($0.prefix(Household.maxMedical)) }))
                    .accessibilityLabel(r.medical)
                Text("\(h.medicalNeeds.count)/\(Household.maxMedical)").font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Button(r.saveHousehold) { save() }.buttonStyle(.relayFilled(Theme.Palette.accent))
                if saved { Text(r.savedHousehold).font(.subheadline).foregroundStyle(Theme.Palette.accent) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.Palette.surface.ignoresSafeArea())
        .navigationTitle(r.householdTitle)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if !loaded { h = model.settings.household; loaded = true } }
    }

    private func save() {
        let normalized = h.normalized()
        model.updateSettings { var s = $0; s.household = normalized; s.householdSet = true; return s }
        saved = true
        // the plan is made for this household: fetch the new one now if there is signal (a no-op without a warning)
        Task { await model.ready.refresh(.manual) }
    }
}
