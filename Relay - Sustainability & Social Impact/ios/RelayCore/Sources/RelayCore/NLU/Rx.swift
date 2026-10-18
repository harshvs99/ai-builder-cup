import Foundation

/// Thin wrapper over `NSRegularExpression` that keeps the Kotlin/Java regex semantics the lexicons were written
/// against. All offsets are UTF-16 offsets, exactly like `String.length`/`MatchResult.range` on the JVM, so the
/// index arithmetic of the Kotlin original carries over unchanged.
///
/// Java's `\s`, `\S`, `\w`, `\d` are ASCII-only while ICU's are Unicode-aware; the pattern is rewritten so they
/// keep the Java meaning (otherwise `\w*` after a stem would swallow Devanagari/Odia letters and `\s` would match
/// NBSP).
struct Rx: @unchecked Sendable {
    let re: NSRegularExpression

    init(_ pattern: String) {
        do {
            re = try NSRegularExpression(pattern: Rx.javaToICU(pattern), options: [])
        } catch {
            preconditionFailure("bad regex \(pattern): \(error)")
        }
    }

    static func escape(_ s: String) -> String { NSRegularExpression.escapedPattern(for: s) }

    private static let ws = " \\t\\n\\x0B\\f\\r"

    static func javaToICU(_ p: String) -> String {
        var out = ""
        var inClass = 0
        let chars = Array(p)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c == "\\", i + 1 < chars.count {
                let n = chars[i + 1]
                switch n {
                case "s": out += inClass > 0 ? ws : "[\(ws)]"
                case "S": out += inClass > 0 ? "\\S" : "[^\(ws)]"
                case "w": out += inClass > 0 ? "A-Za-z0-9_" : "[A-Za-z0-9_]"
                case "d": out += inClass > 0 ? "0-9" : "[0-9]"
                default: out.append(c); out.append(n)
                }
                i += 2
                continue
            }
            if c == "[" { inClass += 1 } else if c == "]" && inClass > 0 { inClass -= 1 }
            out.append(c)
            i += 1
        }
        return out
    }

    func find(_ s: String) -> RxMatch? {
        let ns = NSString(string: s)
        guard let r = re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: ns.length)) else { return nil }
        return RxMatch(ns: ns, result: r)
    }

    func matches(_ s: String) -> Bool {
        re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: s.utf16.count)) != nil
    }

    func findAll(_ s: String) -> [RxMatch] {
        let ns = NSString(string: s)
        return re.matches(in: s, options: [], range: NSRange(location: 0, length: ns.length)).map { RxMatch(ns: ns, result: $0) }
    }

    func replace(_ s: String, with replacement: (RxMatch) -> String) -> String {
        let ns = NSString(string: s)
        var out = ""
        var last = 0
        for r in re.matches(in: s, options: [], range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: r.range.location - last))
            out += replacement(RxMatch(ns: ns, result: r))
            last = r.range.location + r.range.length
        }
        out += ns.substring(from: last)
        return out
    }

    func replace(_ s: String, with replacement: String) -> String { replace(s) { _ in replacement } }
}

struct RxMatch {
    let ns: NSString
    let result: NSTextCheckingResult

    /// UTF-16 range of the whole match.
    var range: NSRange { result.range }
    var value: String { ns.substring(with: result.range) }
    /// UTF-16 range of group [i], nil if the group did not participate.
    func groupRange(_ i: Int) -> NSRange? {
        let r = result.range(at: i)
        return r.location == NSNotFound ? nil : r
    }
    /// Text of group [i]; "" if it did not participate (Kotlin `groupValues`).
    func group(_ i: Int) -> String {
        guard let r = groupRange(i) else { return "" }
        return ns.substring(with: r)
    }
}

/// UTF-16 offset helpers mirroring Kotlin's `String.substring(from, to)`.
extension String {
    var utf16Length: Int { utf16.count }
    func substr(_ from: Int, _ to: Int) -> String {
        let ns = NSString(string: self)
        let a = max(0, min(from, ns.length)), b = max(a, min(to, ns.length))
        return ns.substring(with: NSRange(location: a, length: b - a))
    }
    func substr(from: Int) -> String { substr(from, utf16.count) }
}
