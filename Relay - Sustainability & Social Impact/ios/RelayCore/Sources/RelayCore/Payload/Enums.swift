import Foundation

public enum NeedCategory: Int, CaseIterable, Sendable, Hashable {
    case medical = 0, rescue = 1, water = 2, food = 3, shelter = 4, missingPerson = 5, other = 6

    public var code: Int { rawValue }
    public static func fromCode(_ code: Int) -> NeedCategory? { NeedCategory(rawValue: code) }

    /// The Kotlin constant name (`MISSING_PERSON`), as used by the backend / dashboard JSON and the eval labels.
    public var name: String {
        switch self {
        case .medical: return "MEDICAL"
        case .rescue: return "RESCUE"
        case .water: return "WATER"
        case .food: return "FOOD"
        case .shelter: return "SHELTER"
        case .missingPerson: return "MISSING_PERSON"
        case .other: return "OTHER"
        }
    }
}

public enum AckStatus: Int, CaseIterable, Sendable, Hashable {
    case received = 0, dispatched = 1, onTheWay = 2, resolved = 3

    public var code: Int { rawValue }
    public static func fromCode(_ code: Int) -> AckStatus? { AckStatus(rawValue: code) }

    /// The Kotlin constant name (`ON_THE_WAY`).
    public var name: String {
        switch self {
        case .received: return "RECEIVED"
        case .dispatched: return "DISPATCHED"
        case .onTheWay: return "ON_THE_WAY"
        case .resolved: return "RESOLVED"
        }
    }
}

/// Language of the requester's text. Wire code plus ISO-ish string code.
public enum Lang: Int, CaseIterable, Sendable, Hashable {
    case en = 0, hi = 1, or = 2, tl = 3, war = 4, ceb = 5

    public var code: Int { rawValue }
    public var tag: String {
        switch self {
        case .en: return "en"
        case .hi: return "hi"
        case .or: return "or"
        case .tl: return "tl"
        case .war: return "war"
        case .ceb: return "ceb"
        }
    }
    public static func fromCode(_ code: Int) -> Lang? { Lang(rawValue: code) }
    /// Case-insensitive tag lookup.
    public static func fromTag(_ tag: String) -> Lang? {
        let t = tag.lowercased()
        return allCases.first { $0.tag == t }
    }
}

public enum VulnFlag: Int, CaseIterable, Sendable, Hashable, Comparable {
    case elderly = 1, child = 2, pregnant = 4, injured = 8, trapped = 16

    public var bit: Int { rawValue }

    /// The Kotlin constant name (`ELDERLY`).
    public var name: String {
        switch self {
        case .elderly: return "ELDERLY"
        case .child: return "CHILD"
        case .pregnant: return "PREGNANT"
        case .injured: return "INJURED"
        case .trapped: return "TRAPPED"
        }
    }

    public static func < (a: VulnFlag, b: VulnFlag) -> Bool { a.rawValue < b.rawValue }

    public static func toMask<C: Collection>(_ flags: C) -> Int where C.Element == VulnFlag {
        flags.reduce(0) { $0 | $1.bit }
    }
    public static func fromMask(_ mask: Int) -> Set<VulnFlag> {
        Set(allCases.filter { mask & $0.bit != 0 })
    }
}
