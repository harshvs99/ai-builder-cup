import Foundation

/// A position on the earth (the phone's last known fix, or a shelter).
public struct GeoPoint: Equatable, Sendable {
    public let lat: Double
    public let lon: Double
    public init(lat: Double, lon: Double) { self.lat = lat; self.lon = lon }
}

/// A shelter with its distance from the phone, when the phone knows where it is.
public struct ShelterRow: Equatable, Sendable {
    public let shelter: ReadyShelter
    public let distanceKm: Double?
    public init(shelter: ReadyShelter, distanceKm: Double?) { self.shelter = shelter; self.distanceKm = distanceKm }
}

/// A pin on the offline mini map, as fractions (0...1) of its width and height; y grows downwards.
public struct MapPin: Equatable, Sendable {
    public let shelterId: String
    public let x: Double
    public let y: Double
    public let selected: Bool
}

/// Which shelters the phone shows and how (contract v2 section 4); port of Kotlin `ShelterLogic`. Production builds show only a
/// list an officer verified (`shelters_source == "published"`); a demo list shows only in demo builds, with a "Demo" badge. Never
/// invents data: a list with no shelters is empty, and a phone with no pack shows nothing.
public enum ShelterLogic {
    /// The shelters this build may show from `pack`.
    public static func visible(_ pack: ReadyPack?, demoBuild: Bool) -> [ReadyShelter] {
        guard let pack else { return [] }
        if pack.isPublished { return pack.shelters.filter { !$0.demo } }
        return demoBuild ? pack.shelters : []
    }

    /// True for a row that needs the "Demo" badge.
    public static func isDemo(_ pack: ReadyPack?, _ s: ReadyShelter) -> Bool { s.demo || pack?.isPublished == false }

    /// Nearest first when `from` is known (ties and unknown positions by name), else by name.
    public static func sorted(_ shelters: [ReadyShelter], from: GeoPoint?) -> [ShelterRow] {
        let rows = shelters.map { s in ShelterRow(shelter: s, distanceKm: from.map { distanceKm($0.lat, $0.lon, s.lat, s.lon) }) }
        return rows.sorted { a, b in
            switch (a.distanceKm, b.distanceKm) {
            case let (x?, y?) where x != y: return x < y
            case (_?, nil): return true
            case (nil, _?): return false
            default: return a.shelter.nameEn.lowercased() < b.shelter.nameEn.lowercased()
            }
        }
    }

    /// Great-circle distance in km (haversine).
    public static func distanceKm(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6371.0088
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let dp = p2 - p1, dl = (lon2 - lon1) * .pi / 180
        let a = pow(sin(dp / 2), 2) + cos(p1) * cos(p2) * pow(sin(dl / 2), 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /// Coordinates as shown and copied: "19.81200, 85.82800".
    public static func coordinatesText(_ s: ReadyShelter) -> String {
        String(format: "%.5f, %.5f", locale: Locale(identifier: "en_US_POSIX"), s.lat, s.lon)
    }

    /// The sentence "Speak" reads: the name, and the distance when known.
    public static func spokenLine(_ row: ShelterRow, kmWord: (Double) -> String) -> String {
        row.shelter.name + (row.distanceKm.map { ", " + kmWord($0) } ?? "")
    }

    /// Pins for the mini map that is drawn without map tiles (no network, no bundled imagery): the shelters scaled into the box that
    /// holds them all, with a margin of `margin` on each side. One shelter sits in the middle.
    public static func pins(_ shelters: [ReadyShelter], selectedId: String?, margin: Double = 0.12) -> [MapPin] {
        guard !shelters.isEmpty else { return [] }
        let minLat = shelters.map(\.lat).min()!, maxLat = shelters.map(\.lat).max()!
        let minLon = shelters.map(\.lon).min()!, maxLon = shelters.map(\.lon).max()!
        let midLat = (minLat + maxLat) / 2
        let kx = cos(midLat * .pi / 180) // a degree of longitude is shorter away from the equator
        let w = max((maxLon - minLon) * kx, 1e-6), h = max(maxLat - minLat, 1e-6)
        let scale = (1 - 2 * margin) / max(w, h)
        return shelters.map { s in
            MapPin(shelterId: s.id, x: 0.5 + ((s.lon - (minLon + maxLon) / 2) * kx) * scale, y: 0.5 - (s.lat - midLat) * scale, selected: s.id == selectedId)
        }
    }
}

/// `GET /v1/public/shelters?district=` (no key): the district's published, officer-verified list, for a phone with signal and no pack.
public enum PublicShelters {
    /// Turns the response into a pack-shaped body (no warning, no checklist, source `published`) plus its parsed form, so one stored copy
    /// serves the list, the detail and the Ready footer. Nil for anything that is not a non-empty published list.
    public static func toPack(_ json: String?, nowSec: Int64) -> (raw: String, pack: ReadyPack)? {
        guard let json, let data = json.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let district = o["district"] as? String, let items = o["shelters"] as? [Any] else { return nil }
        let published = (o["published_at"] as? NSNumber)?.int64Value ?? 0
        var shelters: [[String: Any]] = []
        for item in items {
            guard let s = item as? [String: Any], let lat = (s["lat"] as? NSNumber)?.doubleValue, let lon = (s["lon"] as? NSNumber)?.doubleValue,
                  let name = s["name"] as? String, !name.trimmingCharacters(in: .whitespaces).isEmpty else { continue } // never plot a shelter without a position
            let local = (s["name_local"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
            let address = (s["address"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
            var entry: [String: Any] = [
                "id": s["id"] as? String ?? "", "name": local ?? name, "name_en": name, "kind": s["kind"] as? String ?? "other",
                "lat": lat, "lon": lon, "facilities": [String](), "demo": false,
            ]
            if let cap = (s["capacity"] as? NSNumber)?.intValue { entry["capacity"] = cap } else { entry["capacity"] = NSNull() }
            if let address { entry["block"] = address } else { entry["block"] = NSNull() }
            shelters.append(entry)
        }
        if shelters.isEmpty { return nil }
        let pack: [String: Any] = [
            "district": district, "lang": "en", "generated_at": nowSec, "version": "public-\(published)", "warning": NSNull(),
            "shelters": shelters, "shelters_source": "published", "shelters_published_at": published, "checklist": [String](), "checklist_lang": "en",
        ]
        guard let out = try? JSONSerialization.data(withJSONObject: pack, options: [.sortedKeys]) else { return nil }
        let raw = String(decoding: out, as: UTF8.self)
        return ReadyJson.parsePack(raw).map { (raw, $0) }
    }
}
