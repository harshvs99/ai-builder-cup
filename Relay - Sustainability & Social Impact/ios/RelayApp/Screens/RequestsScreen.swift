import SwiftUI
import RelayKit

/// Requests tab: Active and Closed sections, or the empty message.
struct RequestsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.strings) private var strings

    var body: some View {
        NowClock { now in
            let tab = RequestsTabModel.make(needs: model.myNeeds, now: now, strings: strings)
            Group {
                if let empty = tab.emptyMessage {
                    Text(empty).font(.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        .padding(Theme.Space.gutter)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(tab.sections, id: \.title) { section in
                            Section(section.title) {
                                ForEach(section.rows) { row in RequestRowButton(row: row) }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                    .scrollContentBackground(.hidden)
                }
            }
        }
        .navigationTitle(strings.home.requestsTitle)
        .navigationBarTitleDisplayMode(.large)
        .background(Theme.Palette.surface.ignoresSafeArea())
    }
}
