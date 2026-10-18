import SwiftUI

/// One tile of the Network 2x2 grid: small label, big value, optional caption.
struct StatTile: View {
    var title: String
    var value: String
    var caption: String?
    var valueColor: Color

    init(title: String, value: String, caption: String? = nil, valueColor: Color = Theme.Palette.textPrimary) {
        self.title = title
        self.value = value
        self.caption = caption
        self.valueColor = valueColor
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title.weight(.semibold))
                .foregroundStyle(valueColor)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
            if let caption {
                Text(caption).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 88, alignment: .topLeading)
        .padding(Theme.Space.l)
        .relaySurfaceCard(cornerRadius: Theme.Radius.tile)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, value, caption].compactMap { $0 }.joined(separator: ", "))
    }
}

/// Lays out up to four tiles as a 2x2 grid.
struct StatTileGrid<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: Theme.Space.m), GridItem(.flexible(), spacing: Theme.Space.m)],
            spacing: Theme.Space.m
        ) { content() }
    }
}

#Preview("Tiles") {
    StatTileGrid {
        StatTile(title: "Phones nearby", value: "3")
        StatTile(title: "Carried for neighbours", value: "12")
        StatTile(title: "Waiting to pass on", value: "2")
        StatTile(title: "Mesh hours left", value: "about 9 h", valueColor: Theme.Palette.statusGreen)
    }
    .padding()
    .background(Theme.Palette.surface)
}
