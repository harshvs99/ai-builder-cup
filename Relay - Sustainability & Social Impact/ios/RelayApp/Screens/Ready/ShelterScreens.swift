import SwiftUI
import MapKit
import UIKit
import RelayCore
import RelayKit

/// The shelters this build may show, nearest first when the phone has a fix. Production builds: only an officer-published list.
private func shelterRows(_ model: AppModel) -> (rows: [ShelterRow], pack: ReadyPack?) {
    let pack = model.ready.snapshot.pack
    let here = model.locationFix.map { GeoPoint(lat: $0.lat, lon: $0.lon) }
    return (ShelterLogic.sorted(ShelterLogic.visible(pack, demoBuild: model.demoBuild), from: here), pack)
}

struct ShelterListScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings

    var body: some View {
        let r = strings.ready
        let (rows, pack) = shelterRows(model)
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.s) {
                if rows.isEmpty {
                    Text(r.noSheltersList).font(.body)
                } else {
                    ForEach(rows, id: \.shelter.id) { row in
                        Button { nav.push(.shelter(id: row.shelter.id)) } label: {
                            HStack(alignment: .center, spacing: Theme.Space.m) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(row.shelter.name).font(.headline).foregroundStyle(Color.primary)
                                    Text(r.kindName(row.shelter.kind)).font(.subheadline).foregroundStyle(.secondary)
                                    if let c = row.shelter.capacity { Text(r.capacity(c)).font(.footnote).foregroundStyle(.secondary) }
                                }
                                Spacer(minLength: 0)
                                VStack(alignment: .trailing, spacing: 2) {
                                    if let d = row.distanceKm { Text(r.kmAway(d)).font(.headline).foregroundStyle(Color.primary) }
                                    if ShelterLogic.isDemo(pack, row.shelter) {
                                        Text(r.demoBadge).font(.caption.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 2)
                                            .background(Color.orange.opacity(0.25), in: Capsule())
                                    }
                                }
                            }
                            .padding(Theme.Space.l)
                            .frame(maxWidth: .infinity, minHeight: Theme.Size.minTouch, alignment: .leading)
                            .relaySurfaceCard()
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel([row.shelter.name, r.kindName(row.shelter.kind), row.distanceKm.map { r.kmAway($0) },
                                             row.shelter.capacity.map { r.capacity($0) }, ShelterLogic.isDemo(pack, row.shelter) ? r.demoBadge : nil]
                            .compactMap { $0 }.joined(separator: ". "))
                        .accessibilityAddTraits(.isButton)
                    }
                }
            }
            .padding(.horizontal, Theme.Space.gutter)
            .padding(.bottom, Theme.Space.xl)
        }
        .background(Theme.Palette.surface.ignoresSafeArea())
        .navigationTitle(r.sheltersTitle)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ShelterDetailScreen: View {
    var id: String
    @Environment(AppModel.self) private var model
    @Environment(\.strings) private var strings
    @State private var message: String?

    var body: some View {
        let r = strings.ready
        let (rows, pack) = shelterRows(model)
        let row = rows.first { $0.shelter.id == id }
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                if let row {
                    let s = row.shelter
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.name).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                        if s.nameEn != s.name { Text(s.nameEn).font(.headline).foregroundStyle(.secondary) }
                        Text(r.kindName(s.kind)).font(.body)
                        if ShelterLogic.isDemo(pack, s) {
                            Text(r.demoBadge).font(.caption.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 2)
                                .background(Color.orange.opacity(0.25), in: Capsule())
                        }
                    }
                    VStack(alignment: .leading, spacing: Theme.Space.xs) {
                        if let a = s.block { Text("\(r.address): \(a)").font(.body) }
                        if let c = s.capacity { Text(r.capacity(c)).font(.body) }
                        if let d = row.distanceKm { Text(r.kmAway(d)).font(.body) }
                        if !s.facilities.isEmpty { Text("\(r.facilities): " + s.facilities.map { r.facilityName($0) }.joined(separator: ", ")).font(.body) }
                        Text("\(r.coordinates): \(ShelterLogic.coordinatesText(s))").font(.subheadline).foregroundStyle(.secondary)
                    }
                    .padding(Theme.Space.l).frame(maxWidth: .infinity, alignment: .leading).relaySurfaceCard()

                    OfflineMap(all: rows.map(\.shelter), selected: s)

                    Button { openInMaps(s) } label: { Label(r.directions, systemImage: "arrow.triangle.turn.up.right.diamond").frame(maxWidth: .infinity) }
                        .buttonStyle(.relayFilled(Theme.Palette.accent))
                    Button {
                        UIPasteboard.general.string = ShelterLogic.coordinatesText(s)
                        message = r.copied
                    } label: { Label(r.copyCoordinates, systemImage: "doc.on.doc").frame(maxWidth: .infinity) }
                        .buttonStyle(.relayTinted(Theme.Palette.accent))
                    Button {
                        let lang = Lang.fromTag(pack?.lang ?? "en") ?? .en
                        let ok = model.speak(ShelterLogic.spokenLine(row) { r.kmAway($0) }, lang: lang)
                        message = ok ? nil : r.noVoicePlan
                    } label: { Label(r.speakShelter, systemImage: "speaker.wave.2.fill").frame(maxWidth: .infinity) }
                        .buttonStyle(.relayTinted(Theme.Palette.accent))
                    if let m = message { Text(m).font(.subheadline).foregroundStyle(.secondary) }
                } else {
                    Text(r.shelterNotFound).font(.body)
                }
            }
            .padding(.horizontal, Theme.Space.gutter)
            .padding(.bottom, Theme.Space.xl)
        }
        .background(Theme.Palette.surface.ignoresSafeArea())
        .navigationTitle(row?.shelter.name ?? r.sheltersTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// The system Maps app (works offline when it has offline maps).
    private func openInMaps(_ s: ReadyShelter) {
        let item = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lon)))
        item.name = s.nameEn
        item.openInMaps()
    }
}

/// A map that needs no network: the shelters of the list drawn as pins in the box that holds them (see `ShelterLogic.pins`), the open
/// one highlighted. There is no bundled district imagery yet; "Directions" opens the maps app, which may have offline data of its own.
private struct OfflineMap: View {
    var all: [ReadyShelter]
    var selected: ReadyShelter
    @Environment(\.strings) private var strings

    var body: some View {
        let r = strings.ready
        let pins = ShelterLogic.pins(all, selectedId: selected.id)
        VStack(alignment: .leading, spacing: 4) {
            Canvas { ctx, size in
                let step = size.width / 8
                var x: CGFloat = 0
                while x <= size.width { ctx.stroke(Path { $0.move(to: CGPoint(x: x, y: 0)); $0.addLine(to: CGPoint(x: x, y: size.height)) }, with: .color(.secondary.opacity(0.3))); x += step }
                var y: CGFloat = 0
                while y <= size.height { ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: y)); $0.addLine(to: CGPoint(x: size.width, y: y)) }, with: .color(.secondary.opacity(0.3))); y += step }
                for p in pins.sorted(by: { !$0.selected && $1.selected }) {
                    let radius: CGFloat = p.selected ? 11 : 7
                    let c = CGPoint(x: p.x * size.width, y: p.y * size.height)
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - radius, y: c.y - radius, width: radius * 2, height: radius * 2)), with: .color(p.selected ? .red : Theme.Palette.accent))
                }
            }
            .frame(maxWidth: .infinity).aspectRatio(1.6, contentMode: .fit).frame(minHeight: 160)
            .background(Theme.Palette.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.3)))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(r.mapDescription(pins.count))
            Text(r.mapNote).font(.footnote).foregroundStyle(.secondary)
        }
    }
}
