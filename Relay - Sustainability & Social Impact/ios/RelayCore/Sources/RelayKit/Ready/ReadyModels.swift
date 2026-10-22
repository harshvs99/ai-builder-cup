import Foundation

// The phone side of the Ready contract (`backend/app/ready.py`, contract v2 section 3); port of the Kotlin `ReadyModels.kt`.
// Parsing is lenient on purpose: a field the backend adds later is ignored, a missing optional field is nil or empty, and only a
// body without the fields the screens cannot do without (district, version, shelters list, steps, plan id) is refused.

/// `warning` of the pack. `severity` is `watch`, `warning` or `alert`; a value this build does not know is kept as is.
public struct ReadyWarning: Equatable, Sendable {
    public let id: Int64
    public let source: String
    public let severity: String
    public let headline: String
    public let issuedAt: Int64
    public let expectedLandfallAt: Int64?
    public let expiresAt: Int64?
    public init(id: Int64, source: String, severity: String, headline: String, issuedAt: Int64, expectedLandfallAt: Int64?, expiresAt: Int64?) {
        self.id = id; self.source = source; self.severity = severity; self.headline = headline
        self.issuedAt = issuedAt; self.expectedLandfallAt = expectedLandfallAt; self.expiresAt = expiresAt
    }
}

/// One shelter of the pack. `demo` true = a placeholder list that no officer has verified (demo builds only).
public struct ReadyShelter: Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let nameEn: String
    /// `cyclone_shelter`, `school`, `relief_camp`, `hospital` or `other`.
    public let kind: String
    public let lat: Double
    public let lon: Double
    public let capacity: Int?
    public let facilities: [String]
    public let block: String?
    public let demo: Bool
    public init(id: String, name: String, nameEn: String, kind: String, lat: Double, lon: Double, capacity: Int?,
                facilities: [String], block: String?, demo: Bool) {
        self.id = id; self.name = name; self.nameEn = nameEn; self.kind = kind; self.lat = lat; self.lon = lon
        self.capacity = capacity; self.facilities = facilities; self.block = block; self.demo = demo
    }
}

/// `GET /v1/ready/pack`.
public struct ReadyPack: Equatable, Sendable {
    public let district: String
    public let lang: String
    public let generatedAt: Int64
    /// Hash of the content except `generatedAt`: it changes exactly when the warning, the shelter list or the checklist does.
    public let version: String
    public let warning: ReadyWarning?
    public let shelters: [ReadyShelter]
    /// `published` (an officer verified the list) or `demo`.
    public let sheltersSource: String
    public let sheltersPublishedAt: Int64?
    public let checklist: [String]
    public let checklistLang: String
    public var isPublished: Bool { sheltersSource == "published" }
    public init(district: String, lang: String, generatedAt: Int64, version: String, warning: ReadyWarning?, shelters: [ReadyShelter],
                sheltersSource: String, sheltersPublishedAt: Int64?, checklist: [String], checklistLang: String) {
        self.district = district; self.lang = lang; self.generatedAt = generatedAt; self.version = version; self.warning = warning
        self.shelters = shelters; self.sheltersSource = sheltersSource; self.sheltersPublishedAt = sheltersPublishedAt
        self.checklist = checklist; self.checklistLang = checklistLang
    }
}

public struct NearestShelter: Equatable, Sendable {
    public let id: String
    public let name: String
    public let distanceKm: Double
    public init(id: String, name: String, distanceKm: Double) { self.id = id; self.name = name; self.distanceKm = distanceKm }
}

/// `POST /v1/ready/plan`. `lang` is what the phone asked for, `stepsLang` what the steps are written in (they differ when that language is off).
public struct ReadyPlan: Equatable, Sendable {
    public let planId: String
    public let lang: String
    public let stepsLang: String
    public let district: String
    public let steps: [String]
    /// `gemini` or `template`.
    public let source: String
    public let warningId: Int64?
    public let nearestShelters: [NearestShelter]
    /// Relative path of the spoken plan (`audio/wav`, PCM16 mono 24 kHz), or nil when the backend has no audio.
    public let audioUrl: String?
    public init(planId: String, lang: String, stepsLang: String, district: String, steps: [String], source: String, warningId: Int64?,
                nearestShelters: [NearestShelter], audioUrl: String?) {
        self.planId = planId; self.lang = lang; self.stepsLang = stepsLang; self.district = district; self.steps = steps
        self.source = source; self.warningId = warningId; self.nearestShelters = nearestShelters; self.audioUrl = audioUrl
    }
}

/// The household profile (contract section 3.2). Stays on the phone except as the anonymous flags sent to `/v1/ready/plan`.
/// `medicalNeeds` is free text typed by the person: at most 100 characters, never an identifier.
public struct Household: Equatable, Sendable {
    public static let minPeople = 1
    public static let maxPeople = 20
    public static let maxMedical = 100

    public var people: Int
    public var elderly: Bool
    public var children: Bool
    public var infants: Bool
    public var pregnant: Bool
    public var disability: Bool
    public var medicalNeeds: String
    public var livestock: Bool

    public init(people: Int = 1, elderly: Bool = false, children: Bool = false, infants: Bool = false, pregnant: Bool = false,
                disability: Bool = false, medicalNeeds: String = "", livestock: Bool = false) {
        self.people = people; self.elderly = elderly; self.children = children; self.infants = infants; self.pregnant = pregnant
        self.disability = disability; self.medicalNeeds = medicalNeeds; self.livestock = livestock
    }

    /// The profile with its limits applied: 1 to 20 people, medical text trimmed to 100 characters.
    public func normalized() -> Household {
        var h = self
        h.people = min(max(people, Household.minPeople), Household.maxPeople)
        h.medicalNeeds = String(medicalNeeds.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Household.maxMedical))
        return h
    }

    /// The `household` object of `POST /v1/ready/plan`.
    public func jsonObject() -> [String: Any] {
        let h = normalized()
        return ["people": h.people, "elderly": h.elderly, "children": h.children, "infants": h.infants, "pregnant": h.pregnant,
                "disability": h.disability, "medical_needs": h.medicalNeeds, "livestock": h.livestock]
    }

    /// Stable text that changes exactly when the plan would: the refresher fetches a new plan when it differs from the stored one.
    public var key: String {
        let h = normalized()
        func b(_ v: Bool) -> Int { v ? 1 : 0 }
        return "p\(h.people)|e\(b(h.elderly))|c\(b(h.children))|i\(b(h.infants))|g\(b(h.pregnant))|d\(b(h.disability))|l\(b(h.livestock))|m:\(h.medicalNeeds.lowercased())"
    }

    public static func fromJson(_ json: String?) -> Household {
        guard let json, let data = json.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return Household() }
        return Household(
            people: (o["people"] as? NSNumber)?.intValue ?? 1, elderly: o["elderly"] as? Bool ?? false, children: o["children"] as? Bool ?? false,
            infants: o["infants"] as? Bool ?? false, pregnant: o["pregnant"] as? Bool ?? false, disability: o["disability"] as? Bool ?? false,
            medicalNeeds: o["medical_needs"] as? String ?? "", livestock: o["livestock"] as? Bool ?? false).normalized()
    }

    public func toJson() -> String {
        let data = (try? JSONSerialization.data(withJSONObject: jsonObject(), options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }
}

public enum ReadyJson {
    private static func object(_ text: String?) -> [String: Any]? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let d = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
    }

    private static func int64(_ o: [String: Any], _ k: String) -> Int64? {
        guard let v = o[k], !(v is NSNull), let n = v as? NSNumber else { return nil }
        return n.int64Value
    }

    private static func string(_ o: [String: Any], _ k: String) -> String? {
        guard let s = o[k] as? String, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    private static func strings(_ a: Any?) -> [String] {
        ((a as? [Any]) ?? []).compactMap { ($0 as? String).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } }
    }

    public static func parsePack(_ json: String?) -> ReadyPack? {
        guard let o = object(json), let district = o["district"] as? String, let version = o["version"] as? String,
              let shelters = o["shelters"] as? [Any] else { return nil }
        return ReadyPack(
            district: district, lang: o["lang"] as? String ?? "en", generatedAt: int64(o, "generated_at") ?? 0, version: version,
            warning: (o["warning"] as? [String: Any]).flatMap(warning), shelters: shelters.compactMap { ($0 as? [String: Any]).flatMap(shelter) },
            sheltersSource: o["shelters_source"] as? String ?? "demo", sheltersPublishedAt: int64(o, "shelters_published_at"),
            checklist: strings(o["checklist"]), checklistLang: o["checklist_lang"] as? String ?? "en")
    }

    public static func parsePlan(_ json: String?) -> ReadyPlan? {
        guard let o = object(json), let planId = o["plan_id"] as? String else { return nil }
        let steps = strings(o["steps"])
        if steps.isEmpty { return nil }
        let near = ((o["nearest_shelters"] as? [Any]) ?? []).compactMap { item -> NearestShelter? in
            guard let n = item as? [String: Any] else { return nil }
            return NearestShelter(id: n["id"] as? String ?? "", name: n["name"] as? String ?? "", distanceKm: (n["distance_km"] as? NSNumber)?.doubleValue ?? 0)
        }
        let lang = o["lang"] as? String ?? "en"
        return ReadyPlan(planId: planId, lang: lang, stepsLang: o["steps_lang"] as? String ?? lang, district: o["district"] as? String ?? "",
                         steps: steps, source: o["source"] as? String ?? "template", warningId: int64(o, "warning_id"),
                         nearestShelters: near, audioUrl: string(o, "audio_url"))
    }

    private static func warning(_ o: [String: Any]) -> ReadyWarning? {
        guard let id = int64(o, "id") else { return nil }
        return ReadyWarning(id: id, source: o["source"] as? String ?? "", severity: o["severity"] as? String ?? "warning",
                            headline: o["headline"] as? String ?? "", issuedAt: int64(o, "issued_at") ?? 0,
                            expectedLandfallAt: int64(o, "expected_landfall_at"), expiresAt: int64(o, "expires_at"))
    }

    private static func shelter(_ o: [String: Any]) -> ReadyShelter? {
        guard let id = o["id"] as? String, let name = o["name"] as? String, let lat = (o["lat"] as? NSNumber)?.doubleValue,
              let lon = (o["lon"] as? NSNumber)?.doubleValue else { return nil }
        return ReadyShelter(id: id, name: name, nameEn: o["name_en"] as? String ?? name, kind: o["kind"] as? String ?? "other", lat: lat, lon: lon,
                            capacity: int64(o, "capacity").map { Int($0) }, facilities: strings(o["facilities"]),
                            block: string(o, "block"), demo: o["demo"] as? Bool ?? false)
    }
}
