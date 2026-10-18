import Foundation

/// A word character: letter, combining mark (Devanagari / Odia matras are \p{M}) or number.
let W = "[\\p{L}\\p{M}\\p{N}]"

/// NFC + lowercase + apostrophe unification + whitespace collapse.
func normalizeText(_ s: String, maxChars: Int = 1200) -> String {
    let cut = s.utf16Length > maxChars ? s.substr(0, maxChars) : s
    let nfc = cut.precomposedStringWithCanonicalMapping
    let lowered = nfc.lowercased()
        .replacingOccurrences(of: "\u{2019}", with: "'")
        .replacingOccurrences(of: "\u{2018}", with: "'")
        .replacingOccurrences(of: "\u{02BC}", with: "'")
    return Lex.whitespaceRun.replace(lowered, with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
}

private func nfcLower(_ s: String) -> String { s.precomposedStringWithCanonicalMapping.lowercased() }

/// A set of lexicon terms compiled into one regex with word boundaries that understand combining
/// marks (Devanagari / Odia matras are \p{M}). Term syntax:
///  - plain: matched as a whole word / phrase (spaces match any whitespace run)
///  - trailing `*`: prefix match (`bleed*` matches bleeding, bleeds)
///  - leading `~`: raw regex fragment (still wrapped in the word-boundary guards)
struct Lex: Sendable {
    static let whitespaceRun = Rx("\\s+")

    let regex: Rx

    init(_ terms: String...) {
        self.init(terms: terms)
    }

    init(terms: [String]) {
        let alts = terms.map { Lex.toAlt($0) }.joined(separator: "|")
        regex = Rx("(?<!\(W))(?:\(alts))(?!\(W))")
    }

    func find(_ text: String) -> RxMatch? { regex.find(text) }
    func matches(_ text: String) -> Bool { regex.matches(text) }

    private static func toAlt(_ term: String) -> String {
        if term.hasPrefix("~") { return "(?:" + nfcLower(String(term.dropFirst())) + ")" }
        let trimmed = nfcLower(term).trimmingCharacters(in: .whitespacesAndNewlines)
        return whitespaceRun.splitString(trimmed).map { tok in
            tok.hasSuffix("*") ? Rx.escape(String(tok.dropLast())) + "\(W)*" : Rx.escape(tok)
        }.joined(separator: "\\s+")
    }
}

extension Rx {
    /// Kotlin `String.split(Regex)`: pieces between matches (empty pieces kept).
    func splitString(_ s: String) -> [String] {
        var parts: [String] = []
        var last = 0
        let ns = NSString(string: s)
        for m in findAll(s) {
            parts.append(ns.substring(with: NSRange(location: last, length: m.range.location - last)))
            last = m.range.location + m.range.length
        }
        parts.append(ns.substring(from: last))
        return parts
    }
}
