import XCTest
@testable import RelayCore

/// Scores `RuleExtractor` per field against the voice spike's labelled prompts
/// (`ml/voice-spike/eval/guided_prompts.json`, 34 prompts, and `sentences.csv`, 8 rows), with the same
/// metric as `ml/voice-spike/eval/summarize.py`:
///  - category: exact; urgency: within +-1; people: exact; vuln: micro F1 over flags
///  - item / landmark: normalised (lowercase, punctuation to space, leading near/by/at/behind/the/a/an and
///    trailing Hindi "के पास" dropped) then exact or difflib ratio >= 0.8
/// Prints the table and every miss (and writes it to the temp directory) and fails if any field drops below the
/// floor recorded after the lexicon work, so a lexicon edit cannot silently regress. The floors are identical to
/// the Android `RuleScoreTest`; do not lower them. Rows in a language the extractor has no lexicon for (Bengali,
/// Bhojpuri: labelled ahead of the work) are scored and listed under their own heading, but the floors apply to the
/// shipped languages only, so a missing lexicon is visible without turning every unrelated lexicon edit red.
final class RuleScoreTests: XCTestCase {

    private struct Exp {
        let category: String, urgency: Int, people: Int, vuln: Set<String>, item: String, landmark: String
    }
    private struct Row { let id: String, lang: String, text: String, exp: Exp }

    private struct Tally {
        var n = 0, cat = 0, item = 0, lmk = 0, urg = 0, ppl = 0
        var tp = 0, fp = 0, fn = 0
        var f1: Double { 2 * tp + fp + fn == 0 ? Double.nan : 2.0 * Double(tp) / Double(2 * tp + fp + fn) }
    }

    private var dir: URL { repoRoot.appendingPathComponent("ml/voice-spike/eval") }

    private func guided() throws -> [Row] {
        let data = try Data(contentsOf: dir.appendingPathComponent("guided_prompts.json"))
        let arr = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
        return arr.map { o in
            let x = o["expected"] as! [String: Any]
            return Row(id: o["id"] as! String, lang: o["lang"] as! String, text: o["text"] as! String,
                       exp: Exp(category: x["category"] as! String, urgency: (x["urgency"] as! NSNumber).intValue,
                                people: (x["people"] as! NSNumber).intValue,
                                vuln: Set((x["vuln"] as! [String]).map { $0.uppercased() }),
                                item: x["item"] as! String, landmark: x["landmark"] as! String))
        }
    }

    private func csvRows(_ text: String) -> [[String]] {
        var rows: [[String]] = []
        var cur: [String] = []
        var sb = ""
        var q = false
        let c = Array(text.unicodeScalars)
        var i = 0
        while i < c.count {
            let ch = c[i]
            let next: Unicode.Scalar? = i + 1 < c.count ? c[i + 1] : nil
            if q && ch == "\"" && next == "\"" {
                sb.unicodeScalars.append("\""); i += 1
            } else if ch == "\"" {
                q.toggle()
            } else if !q && ch == "," {
                cur.append(sb); sb = ""
            } else if !q && (ch == "\n" || ch == "\r") {
                if ch == "\r" && next == "\n" { i += 1 }
                cur.append(sb); sb = ""
                if cur.contains(where: { !$0.isEmpty }) { rows.append(cur) }
                cur = []
            } else {
                sb.unicodeScalars.append(ch)
            }
            i += 1
        }
        if !sb.isEmpty || !cur.isEmpty { cur.append(sb); rows.append(cur) }
        return rows
    }

    private func sentences() throws -> [Row] {
        let rows = csvRows(try String(contentsOf: dir.appendingPathComponent("sentences.csv"), encoding: .utf8))
        let head = rows[0]
        func col(_ r: [String], _ n: String) -> String { r[head.firstIndex(of: n)!] }
        return rows.dropFirst().map { r in
            let vulnTokens = col(r, "vuln").split(whereSeparator: { $0 == "|" || $0 == " " }).map { $0.uppercased() }
            return Row(id: col(r, "id"), lang: col(r, "lang"), text: col(r, "text"),
                       exp: Exp(category: col(r, "category"), urgency: Int(col(r, "urgency"))!, people: Int(col(r, "people"))!,
                                vuln: Set(vulnTokens.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }),
                                item: col(r, "item"), landmark: col(r, "landmark")))
        }
    }

    // ---- metric, mirrors summarize.py
    private func isLetterNumberMark(_ u: Unicode.Scalar) -> Bool {
        switch u.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .decimalNumber, .letterNumber, .otherNumber, .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    private func norm(_ s: String) -> String {
        let n = s.precomposedStringWithCanonicalMapping.lowercased()
        var sb = String.UnicodeScalarView()
        for u in n.unicodeScalars { sb.append(isLetterNumberMark(u) ? u : " ") }
        var toks = String(sb).split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" || $0 == "\r" }).map(String.init)
        let leading: Set<String> = ["near", "by", "at", "behind", "the", "a", "an"]
        var changed = true
        while changed {
            changed = false
            if let f = toks.first, leading.contains(f) { toks.removeFirst(); changed = true }
            if toks.count >= 2 && toks[toks.count - 2] == "के" && toks.last == "पास" {
                toks.removeLast(); toks.removeLast(); changed = true
            }
        }
        return toks.joined(separator: " ")
    }

    private func similar(_ a0: String, _ b0: String) -> Bool {
        let a = norm(a0), b = norm(b0)
        if a == b { return true }
        if a.isEmpty || b.isEmpty { return false }
        return ratio(a, b) >= 0.8
    }

    /// difflib.SequenceMatcher(None, a, b).ratio() (no junk; the strings here are far below the autojunk size).
    private func ratio(_ a0: String, _ b0: String) -> Double {
        let a = Array(a0.utf16), b = Array(b0.utf16)
        func matched(_ alo: Int, _ ahi: Int, _ blo: Int, _ bhi: Int) -> Int {
            if alo >= ahi || blo >= bhi { return 0 }
            var bi = alo, bj = blo, bk = 0
            var prev = [Int](repeating: 0, count: bhi - blo + 1)
            for i in alo..<ahi {
                var cur = [Int](repeating: 0, count: bhi - blo + 1)
                for j in blo..<bhi where a[i] == b[j] {
                    let k = prev[j - blo] + 1
                    cur[j - blo + 1] = k
                    if k > bk { bk = k; bi = i - k + 1; bj = j - k + 1 }
                }
                prev = cur
            }
            if bk == 0 { return 0 }
            return bk + matched(alo, bi, blo, bj) + matched(bi + bk, ahi, bj + bk, bhi)
        }
        return 2.0 * Double(matched(0, a.count, 0, b.count)) / Double(a.count + b.count)
    }

    private func score(_ rows: [Row], _ name: String, _ report: inout String) -> Tally {
        var t = Tally()
        var misses: [String] = []
        for r in rows {
            let d = RuleExtractor.extract(r.text)
            t.n += 1
            var miss: [String] = []
            if d.category.name == r.exp.category { t.cat += 1 } else { miss.append("cat \(r.exp.category)->\(d.category.name)") }
            if similar(d.item, r.exp.item) { t.item += 1 } else { miss.append("item '\(r.exp.item)'->'\(d.item)'") }
            if similar(d.landmark, r.exp.landmark) { t.lmk += 1 } else { miss.append("lmk '\(r.exp.landmark)'->'\(d.landmark)'") }
            if abs(d.urgency - r.exp.urgency) <= 1 { t.urg += 1 } else { miss.append("urg \(r.exp.urgency)->\(d.urgency)") }
            if d.people == r.exp.people { t.ppl += 1 } else { miss.append("ppl \(r.exp.people)->\(d.people)") }
            let pv = Set(d.vulnFlags.map { $0.name })
            t.tp += pv.intersection(r.exp.vuln).count
            t.fp += pv.subtracting(r.exp.vuln).count
            t.fn += r.exp.vuln.subtracting(pv).count
            if pv != r.exp.vuln { miss.append("vuln \(r.exp.vuln.sorted())->\(pv.sorted())") }
            if !miss.isEmpty { misses.append("  \(r.id): " + miss.joined(separator: " | ")) }
        }
        report += "### \(name)\n"
        report += "n=\(t.n) cat \(t.cat)/\(t.n) item \(t.item)/\(t.n) landmark \(t.lmk)/\(t.n) urgency+-1 \(t.urg)/\(t.n) "
            + "people \(t.ppl)/\(t.n) vulnF1 \(String(format: "%.2f", t.f1))\n"
        report += "misses: \(misses.count)/\(t.n) rows\n"
        for m in misses { report += m + "\n" }
        report += "\n"
        return t
    }

    func testScoreRuleExtractorPerField() throws {
        var report = ""
        let all = try guided()
        let shipped = all.filter { !Self.noLexicon.contains($0.lang) }
        let ahead = all.filter { Self.noLexicon.contains($0.lang) }
        let g = score(shipped, "guided_prompts.json, shipped languages (34)", &report)
        _ = score(ahead, "guided_prompts.json, languages without a lexicon yet (not part of the floors)", &report)
        let s = score(try sentences(), "sentences.csv (8)", &report)
        try? report.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("rule-eval.txt"),
                          atomically: true, encoding: .utf8)
        print(report)

        XCTAssertTrue(g.n == 34 && s.n == 8, "row counts \(g.n)/\(s.n)")
        // Floors = the numbers after the lexicon fixes. BEFORE: cat 23, item 13, landmark 28, urgency 34, people 26, vuln F1 0.83.
        XCTAssertGreaterThanOrEqual(g.cat, Self.floorCat, "category \(g.cat)/34")
        XCTAssertGreaterThanOrEqual(g.item, Self.floorItem, "item \(g.item)/34")
        XCTAssertGreaterThanOrEqual(g.lmk, Self.floorLmk, "landmark \(g.lmk)/34")
        XCTAssertGreaterThanOrEqual(g.urg, Self.floorUrg, "urgency \(g.urg)/34")
        XCTAssertGreaterThanOrEqual(g.ppl, Self.floorPpl, "people \(g.ppl)/34")
        XCTAssertGreaterThanOrEqual(g.f1, Self.floorF1, "vuln F1 \(g.f1)")
    }

    private static let noLexicon: Set<String> = ["bn", "bho"]
    private static let floorCat = 34
    private static let floorItem = 34
    private static let floorLmk = 34
    private static let floorUrg = 34
    private static let floorPpl = 33
    private static let floorF1 = 0.97
}
