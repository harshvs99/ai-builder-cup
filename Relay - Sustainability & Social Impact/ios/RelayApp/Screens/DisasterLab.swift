import SwiftUI
import RelayCore
import RelayKit

/// Developer-only: declare a disaster as if the control room had, to try triage's disaster-specific questions. The real source
/// is a BROADCAST arriving over the mesh (SPEC 5.6); this one is put into the mesh by this phone, so it also tests the carrying.
/// Technical English on purpose, like the rest of the developer screens.
struct DisasterLab: View {
    @Environment(AppModel.self) private var model
    @State private var tick = 0
    @State private var note = ""

    var body: some View {
        Section {
            let now = Int64(Date().timeIntervalSince1970)
            let live = model.declaredDisasters().filter { now - $0.issuedAtSec <= DisasterGate.maxAgeSec }
            Text(verbatim: live.isEmpty
                 ? "None declared: triage asks no disaster-specific questions."
                 : live.map { "\($0.type.key) · \((now - $0.issuedAtSec) / 3600) h ago" }.joined(separator: "\n"))
                .font(.subheadline)
                .id(tick)
            ForEach(DisasterType.allCases, id: \.self) { t in
                Button { note = model.declareDisaster(t); tick += 1 } label: { Text(verbatim: "Declare \(t.key)") }
            }
            Button(role: .destructive) { model.clearDisastersHere(); note = "Cleared on this phone"; tick += 1 } label: { Text(verbatim: "Clear here") }
            if !note.isEmpty { Text(verbatim: note).font(.footnote).foregroundStyle(.secondary) }
        } header: {
            Text(verbatim: "Control-room disaster (developer)")
        }
    }
}
