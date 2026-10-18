import XCTest
@testable import RelayCore

final class RuleExtractorTests: XCTestCase {

    private func x(_ text: String, _ hint: Lang? = nil) -> NeedDraft { RuleExtractor.extract(text, langHint: hint) }

    private func utf8Count(_ s: String) -> Int { s.utf8.count }

    // ------------------------------------------------------------------ the required case
    func testRequiredExample() {
        let d = x("my mother needs insulin, near the school")
        XCTAssertEqual(.medical, d.category)
        XCTAssertEqual(3, d.urgency)
        XCTAssertEqual("insulin", d.item)
        XCTAssertEqual("school", d.landmark)
        XCTAssertEqual(1, d.people)
        XCTAssertEqual(.en, d.lang)
        XCTAssertEqual("mother needs insulin", d.summary)
    }

    // ------------------------------------------------------------------ per-language cases
    func testEnglish1() {
        x("Please help! We are 5 people trapped on the roof, water rising fast, near the temple").check(category: .rescue, urgency: 3, item: "rescue", landmark: "temple", people: 5, lang: .en, flags: Set<VulnFlag>([.trapped]), text: "Please help! We are 5 people trapped on the roof, water rising fast, near the temple")
    }
    func testEnglish2() {
        x("My grandfather is bleeding badly, we are at the hospital gate").check(category: .medical, urgency: 3, item: "first aid", landmark: "hospital", people: 1, lang: .en, flags: Set<VulnFlag>([.elderly, .injured]), text: "My grandfather is bleeding badly, we are at the hospital gate")
    }
    func testEnglish3() {
        x("We need drinking water and food, 3 children and 2 adults, behind the market").check(category: .water, urgency: 1, item: "water", landmark: "market", people: 5, lang: .en, flags: Set<VulnFlag>([.child]), text: "We need drinking water and food, 3 children and 2 adults, behind the market")
    }
    func testEnglish4() {
        x("pregnant woman in labour, need ambulance by the bridge").check(category: .medical, urgency: 3, item: "ambulance", landmark: "bridge", people: 1, lang: .en, flags: Set<VulnFlag>([.pregnant]), text: "pregnant woman in labour, need ambulance by the bridge")
    }
    func testEnglish5() {
        x("My brother was bitten by a snake near the river").check(category: .medical, urgency: 3, item: "snake bite", landmark: "river", people: 1, lang: .en, text: "My brother was bitten by a snake near the river")
    }
    func testEnglish6() {
        x("our house collapsed, we have no shelter, 4 people").check(category: .shelter, urgency: 1, item: "shelter", landmark: "", people: 4, lang: .en, text: "our house collapsed, we have no shelter, 4 people")
    }
    func testCollapsedWithPeopleUnderDebrisStaysRescue() {
        x("house collapsed, two people under the debris").check(category: .rescue, urgency: 3, item: "rescue", landmark: "", people: 2, lang: .en, flags: Set<VulnFlag>([.trapped]), text: "house collapsed, two people under the debris")
    }
    func testEnglish7() {
        x("my son is missing since the flood, last seen at the bus stand").check(category: .missingPerson, urgency: 1, item: "missing person", landmark: "bus stand", people: 1, lang: .en, text: "my son is missing since the flood, last seen at the bus stand")
    }
    func testEnglish8() {
        x("my 80 year old grandmother has fever and needs medicine").check(category: .medical, urgency: 2, item: "medicine", landmark: "", people: 1, lang: .en, flags: Set<VulnFlag>([.elderly]), text: "my 80 year old grandmother has fever and needs medicine")
    }
    func testHindiDevanagari1() {
        x("मेरी माँ को इंसुलिन चाहिए, स्कूल के पास").check(category: .medical, urgency: 3, item: "insulin", landmark: "school", people: 1, lang: .hi, text: "मेरी माँ को इंसुलिन चाहिए, स्कूल के पास")
    }
    func testHindiDevanagari2() {
        x("हम 6 लोग छत पर फंसे हैं, बाढ़ का पानी बढ़ रहा है, मंदिर के पास").check(category: .rescue, urgency: 2, item: "rescue", landmark: "temple", people: 6, lang: .hi, flags: Set<VulnFlag>([.trapped]), text: "हम 6 लोग छत पर फंसे हैं, बाढ़ का पानी बढ़ रहा है, मंदिर के पास")
    }
    func testHindiDevanagari3() {
        x("बच्चा बुखार से बेहोश है, दवा चाहिए, अस्पताल के पीछे").check(category: .medical, urgency: 3, item: "medicine", landmark: "hospital", people: 1, lang: .hi, flags: Set<VulnFlag>([.child]), text: "बच्चा बुखार से बेहोश है, दवा चाहिए, अस्पताल के पीछे")
    }
    func testHindiHinglish1() {
        x("meri maa ko dawai chahiye, mandir ke paas").check(category: .medical, urgency: 2, item: "medicine", landmark: "temple", people: 1, lang: .hi, text: "meri maa ko dawai chahiye, mandir ke paas")
    }
    func testHindiHinglish2() {
        x("hum 5 log chhat par fase hain, baadh aa gayi, bachao").check(category: .rescue, urgency: 2, item: "rescue", landmark: "", people: 5, lang: .hi, flags: Set<VulnFlag>([.trapped]), text: "hum 5 log chhat par fase hain, baadh aa gayi, bachao")
    }
    func testHindiHinglish3() {
        x("dadi ko khoon aa raha hai, school ke paas").check(category: .medical, urgency: 3, item: "first aid", landmark: "school", people: 1, lang: .hi, flags: Set<VulnFlag>([.elderly, .injured]), text: "dadi ko khoon aa raha hai, school ke paas")
    }
    func testHindiHinglish4() {
        x("bachcha bhookha hai, khana chahiye, panchayat ke paas").check(category: .food, urgency: 1, item: "food", landmark: "panchayat office", people: 1, lang: .hi, flags: Set<VulnFlag>([.child]), text: "bachcha bhookha hai, khana chahiye, panchayat ke paas")
    }
    func testHindiElderlyWalkthrough() {
        x("paani chahiye hum 6 log hain bachche aur buzurg school ke paas kal se pump kharab hai").check(category: .water, people: 6, flags: Set<VulnFlag>([.child, .elderly]), text: "paani chahiye hum 6 log hain bachche aur buzurg school ke paas kal se pump kharab hai")
    }
    func testOdiaScript1() {
        x("ମୋ ମାଙ୍କୁ ଇନସୁଲିନ ଦରକାର, ସ୍କୁଲ ପାଖରେ").check(category: .medical, urgency: 3, item: "insulin", landmark: "school", people: 1, lang: .or, text: "ମୋ ମାଙ୍କୁ ଇନସୁଲିନ ଦରକାର, ସ୍କୁଲ ପାଖରେ")
    }
    func testOdiaScript2() {
        x("ଆମେ ୪ ଜଣ ଛାତ ଉପରେ ଫସି ରହିଛୁ, ବନ୍ୟା").check(category: .rescue, urgency: 2, item: "rescue", landmark: "", people: 4, lang: .or, flags: Set<VulnFlag>([.trapped]), text: "ଆମେ ୪ ଜଣ ଛାତ ଉପରେ ଫସି ରହିଛୁ, ବନ୍ୟା")
    }
    func testOdiaScript3() {
        x("ଝିଅ ଜ୍ୱର ହୋଇଛି, ଔଷଧ ଦରକାର, ମନ୍ଦିର ପାଖରେ").check(category: .medical, urgency: 2, item: "medicine", landmark: "temple", people: 1, lang: .or, text: "ଝିଅ ଜ୍ୱର ହୋଇଛି, ଔଷଧ ଦରକାର, ମନ୍ଦିର ପାଖରେ")
    }
    func testOdiaScript4() {
        x("ପାଣି ଓ ଖାଦ୍ୟ ଦରକାର").check(category: .water, urgency: 1, item: "water", landmark: "", lang: .or, text: "ପାଣି ଓ ଖାଦ୍ୟ ଦରକାର")
    }
    func testOdiaRoman1() {
        x("mo maa ku ausadha darkar, school pakhare").check(category: .medical, urgency: 2, item: "medicine", landmark: "school", people: 1, lang: .or, text: "mo maa ku ausadha darkar, school pakhare")
    }
    func testOdiaRoman2() {
        x("amaku pani darkar, 5 jane ghara bhasi gala, bapa ku bukhar").check(people: 5, lang: .or, text: "amaku pani darkar, 5 jane ghara bhasi gala, bapa ku bukhar")
    }
    func testOdiaRoman3() {
        x("jhia ku sapa kamudichhi, hospital nikate").check(category: .medical, urgency: 3, item: "snake bite", landmark: "hospital", people: 1, lang: .or, text: "jhia ku sapa kamudichhi, hospital nikate")
    }
    func testTagalog1() {
        x("Kailangan namin ng gamot, may sugatan, malapit sa barangay hall").check(category: .medical, urgency: 2, item: "medicine", landmark: "barangay hall", people: 2, lang: .tl, flags: Set<VulnFlag>([.injured]), text: "Kailangan namin ng gamot, may sugatan, malapit sa barangay hall")
    }
    func testTagalog2() {
        x("Naipit kami sa bubong, tulong po, malapit sa simbahan").check(category: .rescue, urgency: 3, item: "rescue", landmark: "church", people: 2, lang: .tl, flags: Set<VulnFlag>([.trapped]), text: "Naipit kami sa bubong, tulong po, malapit sa simbahan")
    }
    func testTagalog3() {
        x("Nagugutom na kami, walang pagkain, lima kami").check(category: .food, urgency: 1, item: "food", landmark: "", people: 5, lang: .tl, text: "Nagugutom na kami, walang pagkain, lima kami")
    }
    func testTagalog4() {
        x("Manganganak na ang nanay ko, tulong po, sa tabi ng ospital").check(category: .medical, urgency: 3, item: "delivery help", landmark: "hospital", lang: .tl, text: "Manganganak na ang nanay ko, tulong po, sa tabi ng ospital")
    }
    func testCebuano1() {
        x("Kinahanglan mi og tambal, naa mi sa atop, duol sa eskwelahan").check(category: .medical, urgency: 2, item: "medicine", landmark: "school", people: 2, lang: .ceb, text: "Kinahanglan mi og tambal, naa mi sa atop, duol sa eskwelahan")
    }
    func testCebuano2() {
        x("Palihug tabang, nalumos ang bata, duol sa suba").check(category: .rescue, urgency: 3, item: "rescue", landmark: "river", people: 1, lang: .ceb, flags: Set<VulnFlag>([.child]), text: "Palihug tabang, nalumos ang bata, duol sa suba")
    }
    func testCebuano3() {
        x("Gigutom mi, wala mi pagkaon, tulo ka bata").check(category: .food, urgency: 1, item: "food", landmark: "", people: 3, lang: .ceb, flags: Set<VulnFlag>([.child]), text: "Gigutom mi, wala mi pagkaon, tulo ka bata")
    }
    func testCebuano4() {
        x("ang akong lola nagdugo, dili makaginhawa, duol sa simbahan").check(category: .medical, urgency: 3, landmark: "church", people: 1, lang: .ceb, flags: Set<VulnFlag>([.elderly, .injured]), text: "ang akong lola nagdugo, dili makaginhawa, duol sa simbahan")
    }
    func testWaray1() {
        x("Kinahanglan kami hin tambal, harani ha eskwelahan, bulig waray").check(category: .medical, urgency: 2, item: "medicine", landmark: "school", people: 2, lang: .war, text: "Kinahanglan kami hin tambal, harani ha eskwelahan, bulig waray")
    }
    func testWaray2() {
        x("Nasagpan kami ha atop, nalunod an bata, bulig, harani han ilog").check(category: .rescue, urgency: 3, item: "rescue", landmark: "river", people: 2, lang: .war, text: "Nasagpan kami ha atop, nalunod an bata, bulig, harani han ilog")
    }
    func testWaray3() {
        x("Nagugutom kami, waray pagkaon, tulo kami, harani han simbahan, bulig").check(category: .food, urgency: 1, item: "food", landmark: "church", people: 3, lang: .war, text: "Nagugutom kami, waray pagkaon, tulo kami, harani han simbahan, bulig")
    }

    func testEnglishUnconsciousNotBreathing() {
        x("he is unconscious and not breathing").check(category: .medical, urgency: 3, item: "first aid", landmark: "", people: 1, lang: .en)
        x("drowning, please send a boat").check(category: .rescue, urgency: 3, item: "rescue", landmark: "", people: 1, lang: .en)
    }

    func testHospitalAsLandmarkIsNotAMedicalNeed() {
        x("no food since two days, we are near the hospital").check(category: .food, urgency: 1, item: "food", landmark: "hospital", people: 1, lang: .en)
    }

    // vulnerability-flag vocabulary: common Hindi/Hinglish/Odia spellings
    private func flags(_ text: String) -> Set<VulnFlag> { x(text).vulnFlags }

    func testElderlyWords() {
        for w in ["buzurg", "bujurg", "budhe", "budhi", "बुजुर्ग", "बूढ़े", "बूढ़ी", "बुज़ुर्ग", "ବୃଦ୍ଧ", "ବୁଢ଼ା", "ବୁଢ଼ୀ"] {
            XCTAssertTrue(flags("ghar mein \(w) hain, pani chahiye \(w)").contains(.elderly), "ELDERLY for '\(w)'")
        }
    }
    func testChildWords() {
        for w in ["bachche", "bacche", "baccha", "bachchi", "बच्चे", "शिशु", "ଶିଶୁ"] {
            XCTAssertTrue(flags("pani chahiye \(w) ke liye").contains(.child), "CHILD for '\(w)'")
        }
    }
    func testPregnantWords() {
        for w in ["garbhvati", "garbhavati", "pregnant", "गर्भवती"] {
            XCTAssertTrue(flags("ek \(w) mahila hai, dawai chahiye").contains(.pregnant), "PREGNANT for '\(w)'")
        }
    }
    func testInjuredWords() {
        for w in ["ghayal", "chot", "zakhmi", "घायल", "चोट"] {
            XCTAssertTrue(flags("mere bhai ko \(w) hai, madad chahiye").contains(.injured), "INJURED for '\(w)'")
        }
    }
    func testTrappedWords() {
        for w in ["phanse", "fase", "फंसे", "फँसे"] {
            XCTAssertTrue(flags("hum chhat par \(w) hain, bachao").contains(.trapped), "TRAPPED for '\(w)'")
        }
    }

    // ------------------------------------------------------------------ behaviour
    func testUnknownTextIsOtherUrgencyOneWithTruncatedInput() {
        let text = "hello this is just a normal sentence about the weather being very nice today"
        let d = x(text)
        d.check(category: .other, urgency: 1, item: "", landmark: "", people: 1)
        XCTAssertTrue(text.hasPrefix(d.summary))
        XCTAssertTrue(utf8Count(d.summary) <= 40 && !d.summary.isEmpty)
        x("").check(category: .other, urgency: 1)
        XCTAssertEqual("", x("   ").summary)
    }

    func testLangHintBreaksTiesButScriptWins() {
        XCTAssertEqual(.ceb, x("insulin", .ceb).lang)
        XCTAssertEqual(.hi, x("insulin chahiye", .en).lang)
        XCTAssertEqual(.hi, x("इंसुलिन", .ceb).lang)
        XCTAssertEqual(.en, x("insulin").lang)
        XCTAssertEqual(.en, x("my mother needs insulin, near the school", .or).lang)
    }

    func testPeopleParsing() {
        XCTAssertEqual(12, x("12 people on the roof").people)
        XCTAssertEqual(5, x("3 adults and 2 children need water").people)
        XCTAssertEqual(4, x("family of four needs food").people)
        XCTAssertEqual(7, x("hum saat log fase hain").people)
        XCTAssertEqual(1, x("80 years old woman needs medicine").people)
        XCTAssertEqual(2, x("we need water").people)
    }

    func testAgesSetFlags() {
        XCTAssertTrue(x("my father 75 years old needs medicine").vulnFlags.contains(.elderly))
        XCTAssertTrue(x("a 3 year old needs medicine").vulnFlags.contains(.child))
        XCTAssertTrue(x("a 30 year old man needs medicine").vulnFlags.isEmpty)
    }

    func testLandmarkFallbackKeepsRawPhrase() {
        x("need food near the old banyan tree by the river ghat").check(landmark: "tree")
        x("need food near Sharma uncle house, please").check(landmark: "sharma uncle house")
        let long = x("need food near the extraordinarily long named community gathering pavilion")
        XCTAssertTrue(utf8Count(long.landmark) <= 24)
    }

    func testNeverFailsOnGarbage() {
        var rnd = SeededRandom(seed: 2024)
        // Swift strings cannot hold the lone surrogate of the Kotlin alphabet; U+FFFD stands in for it.
        let alphabet = Array("abc xyz ,.!?\n\t१२३ଅକ😀\u{0}\u{FFFD}'-()[]{}\\^$*+|नमस्ते".unicodeScalars)
        for _ in 0..<5000 {
            let n = Int.random(in: 0..<200, using: &rnd)
            var s = ""
            for _ in 0..<n { s.unicodeScalars.append(alphabet[Int.random(in: 0..<alphabet.count, using: &rnd)]) }
            let hint: Lang? = Bool.random(using: &rnd) ? Lang.allCases[Int.random(in: 0..<6, using: &rnd)] : nil
            let d = RuleExtractor.extract(s, langHint: hint)
            XCTAssertTrue(utf8Count(d.summary) <= 40)
        }
        RuleExtractor.extract(String(repeating: "x", count: 100_000)).check()
        _ = RuleExtractor.extract("near the " + String(repeating: "a ", count: 50_000))
        _ = RuleExtractor.extract(String(repeating: "5 ", count: 30_000) + "people")
    }

    func testDraftBuildsAValidWireNeed() throws {
        let d = x("my mother needs insulin, near the school")
        let need = d.toNeed(msgId: [UInt8](repeating: 1, count: 8), originNode: [UInt8](repeating: 2, count: 8),
                            createdAtSec: 1_790_000_000, latE5: 2029612, lonE5: 8582451, locAcc: 1, lang: .or)
        let payload = try NeedCodec.encode(need)
        XCTAssertLessThanOrEqual(payload.count, 128)
        let back = try XCTUnwrap(NeedCodec.decode(payload))
        XCTAssertEqual(need, back)
        XCTAssertEqual(.or, back.lang)
        XCTAssertEqual("insulin", back.item)
    }
}

extension NeedDraft {
    func check(
        category: Category? = nil, urgency: Int? = nil, item: String? = nil, landmark: String? = nil,
        people: Int? = nil, lang: Lang? = nil, flags: Set<VulnFlag>? = nil, text: String = "",
        file: StaticString = #filePath, line: UInt = #line
    ) {
        if let category { XCTAssertEqual(category, self.category, "category of '\(text)' -> \(self)", file: file, line: line) }
        if let urgency { XCTAssertEqual(urgency, self.urgency, "urgency of '\(text)' -> \(self)", file: file, line: line) }
        if let item { XCTAssertEqual(item, self.item, "item of '\(text)' -> \(self)", file: file, line: line) }
        if let landmark { XCTAssertEqual(landmark, self.landmark, "landmark of '\(text)' -> \(self)", file: file, line: line) }
        if let people { XCTAssertEqual(people, self.people, "people of '\(text)' -> \(self)", file: file, line: line) }
        if let lang { XCTAssertEqual(lang, self.lang, "lang of '\(text)' -> \(self)", file: file, line: line) }
        if let flags { XCTAssertEqual(flags, self.vulnFlags, "flags of '\(text)' -> \(self)", file: file, line: line) }
        // universal invariants
        XCTAssertTrue(summary.utf8.count <= 40, "summary <= 40 bytes: \(summary)", file: file, line: line)
        XCTAssertTrue(self.item.utf8.count <= 16, "item <= 16 bytes: \(self.item)", file: file, line: line)
        XCTAssertTrue(self.landmark.utf8.count <= 24, "landmark <= 24 bytes: \(self.landmark)", file: file, line: line)
        XCTAssertTrue((0...3).contains(self.urgency) && (1...255).contains(self.people), file: file, line: line)
    }
}
