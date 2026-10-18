import Foundation
import RelayCore

/// A validated model reply. `valid` = a JSON object with all nine keys and legal enum/numeric values. Clamps and byte
/// truncations go in `repairs` and do not make a reply invalid; `issues` says why it is not valid. Port of
/// `ml/voice-spike/.../NeedJson.kt`.
public struct ParsedNeed: Equatable, Sendable {
    public var valid: Bool
    public var jsonFound: Bool
    public var issues: [String]
    public var repairs: [String]
    public var transcript: String
    public var lang: Lang
    public var category: NeedCategory
    public var urgency: Int
    public var people: Int
    public var vuln: Set<VulnFlag>
    public var item: String
    public var landmark: String
    public var summary: String

    /// Fields for `FieldMerge`. People 0 means "unknown" and is passed as nil; an invalid reply gives nothing.
    public var modelFields: ModelFields? {
        guard jsonFound, valid else { return nil }
        return ModelFields(
            category: category == .other ? nil : category, item: item, landmark: landmark, urgency: urgency,
            people: people > 0 ? people : nil, vulnFlags: vuln, summary: summary)
    }
}

public enum GemmaNeedParser {
    private static let required = ["transcript", "lang", "category", "urgency", "people", "vuln", "item", "landmark", "summary"]

    /// First balanced `{...}` object in `raw` after stripping code fences; string and escape aware.
    public static func extractJsonObject(_ raw: String) -> String? {
        let s = Array(raw.replacingOccurrences(of: "```json", with: "").replacingOccurrences(of: "```JSON", with: "")
            .replacingOccurrences(of: "```", with: ""))
        guard let start = s.firstIndex(of: "{") else { return nil }
        var depth = 0, inStr = false, esc = false
        for i in start..<s.count {
            let c = s[i]
            if inStr {
                if esc { esc = false } else if c == "\\" { esc = true } else if c == "\"" { inStr = false }
            } else if c == "\"" { inStr = true
            } else if c == "{" { depth += 1
            } else if c == "}" { depth -= 1; if depth == 0 { return String(s[start...i]) } }
        }
        return nil
    }

    private static func empty(_ issues: [String], jsonFound: Bool) -> ParsedNeed {
        ParsedNeed(valid: false, jsonFound: jsonFound, issues: issues, repairs: [], transcript: "", lang: .en, category: .other,
                   urgency: 1, people: 0, vuln: [], item: "", landmark: "", summary: "")
    }

    private static func number(_ v: Any?) -> Double? {
        if let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { return n.doubleValue }
        if let s = v as? String { return Double(s.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    public static func parse(_ raw: String) -> ParsedNeed {
        guard let text = extractJsonObject(raw) else { return empty(["no JSON object in output"], jsonFound: false) }
        guard let obj = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else {
            return empty(["JSON parse error"], jsonFound: true)
        }
        var issues: [String] = [], repairs: [String] = []
        for k in required where obj[k] == nil || obj[k] is NSNull { issues.append("missing key \(k)") }
        func str(_ k: String) -> String { (obj[k] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "" }

        let langRaw = str("lang").lowercased()
        var lang = Lang.fromTag(langRaw)
        if lang == nil { if obj["lang"] != nil { issues.append("bad lang '\(langRaw)'") }; lang = .en }

        let catRaw = str("category").uppercased().replacingOccurrences(of: " ", with: "_")
        let cat = NeedCategory.allCases.first { $0.name == catRaw }
        if obj["category"] != nil && cat == nil { issues.append("bad category '\(catRaw)'") }

        let urgNum = number(obj["urgency"])
        if obj["urgency"] != nil && urgNum == nil { issues.append("urgency not numeric") }
        let urgRaw = Int(urgNum ?? 1), urgency = min(max(urgRaw, 0), 3)
        if urgNum != nil && urgency != urgRaw { repairs.append("urgency clamped \(urgRaw)->\(urgency)") }

        let pplNum = number(obj["people"])
        if obj["people"] != nil && pplNum == nil { issues.append("people not numeric") }
        let pplRaw = Int(pplNum ?? 0), people = min(max(pplRaw, 0), 255)
        if pplNum != nil && people != pplRaw { repairs.append("people clamped \(pplRaw)->\(people)") }

        var vuln = Set<VulnFlag>()
        if let arr = obj["vuln"] as? [Any] {
            for e in arr {
                let v = ((e as? String) ?? "").trimmingCharacters(in: .whitespaces).uppercased()
                if let f = VulnFlag.allCases.first(where: { $0.name == v }) { vuln.insert(f) } else { issues.append("bad vuln '\(v)'") }
            }
        } else if obj["vuln"] != nil { issues.append("vuln not an array") }

        func cap(_ key: String, _ maxBytes: Int) -> String {
            let s = str(key), t = Utf8.truncate(s, maxBytes)
            if t != s { repairs.append("\(key) truncated to \(maxBytes) bytes") }
            return t
        }
        return ParsedNeed(
            valid: issues.isEmpty, jsonFound: true, issues: issues, repairs: repairs, transcript: str("transcript"),
            lang: lang ?? .en, category: cat ?? .other, urgency: urgency, people: people, vuln: vuln,
            item: cap("item", 16), landmark: cap("landmark", 24), summary: cap("summary", 40))
    }
}
