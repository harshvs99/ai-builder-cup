import XCTest
@testable import RelayCore

/// Odia-script (and romanised Odia) cases added with the rule-parser fixes, plus the negation / supply rules.
final class OdiaRulesTests: XCTestCase {
    private func x(_ t: String) -> NeedDraft { RuleExtractor.extract(t) }

    func testRoofAndRisingWaterIsRescueNotAWaterRequest() {
        let d = x("ଆମେ ପାଞ୍ଚ ଜଣ ଛାତ ଉପରେ ଫସି ରହିଛୁ, ପାଣି ବଢୁଛି")
        XCTAssertEqual(.rescue, d.category); XCTAssertEqual("rescue", d.item); XCTAssertEqual(5, d.people)
        XCTAssertEqual([.trapped], d.vulnFlags); XCTAssertEqual(.or, d.lang)
    }

    func testDrinkingWater() {
        let d = x("ପିଇବା ପାଣି ନାହିଁ, ଦୁଇ ଦିନ ହେଲା")
        XCTAssertEqual(.water, d.category); XCTAssertEqual("water", d.item); XCTAssertEqual(.or, d.lang)
    }

    func testHungryChildren() {
        let d = x("ଖାଇବାକୁ କିଛି ନାହିଁ, ପିଲାମାନେ ଭୋକିଲା")
        XCTAssertEqual(.food, d.category); XCTAssertTrue(d.vulnFlags.contains(.child))
    }

    func testUnconsciousFatherAsksForDoctor() {
        let d = x("ମୋ ବାପା ବେହୋସ ହୋଇଯାଇଛନ୍ତି, ଡାକ୍ତର ଦରକାର")
        XCTAssertEqual(.medical, d.category); XCTAssertEqual(3, d.urgency); XCTAssertEqual("doctor", d.item)
    }

    func testHouseCollapsedNeedsShelter() {
        let d = x("ଆମ ଘର ଭାଙ୍ଗିଗଲା, ଆଶ୍ରୟ ଦରକାର")
        XCTAssertEqual(.shelter, d.category); XCTAssertEqual("shelter", d.item)
    }

    func testMissingSonLastSeenAtBusStand() {
        let d = x("ମୋ ପୁଅ ନିଖୋଜ, ଶେଷଥର ବସ ଷ୍ଟାଣ୍ଡ ପାଖରେ ଦେଖିଥିଲି")
        XCTAssertEqual(.missingPerson, d.category); XCTAssertEqual("bus stand", d.landmark)
    }

    func testLabourWithAmbulance() {
        let d = x("ଗର୍ଭବତୀ ମହିଳା, ପ୍ରସବ ବେଦନା, ଆମ୍ବୁଲାନ୍ସ ପଠାନ୍ତୁ")
        XCTAssertEqual(.medical, d.category); XCTAssertEqual(3, d.urgency); XCTAssertEqual("ambulance", d.item)
        XCTAssertTrue(d.vulnFlags.contains(.pregnant))
    }

    func testSnakeBiteNearHospital() {
        let d = x("ସାପ କାମୁଡ଼ିଛି, ହସ୍ପିଟାଲ ନିକଟରେ")
        XCTAssertEqual("snake bite", d.item); XCTAssertEqual("hospital", d.landmark)
    }

    func testFloodRescueWithOdiaDigit() {
        let d = x("ବନ୍ୟାରେ ଆମେ ୬ ଜଣ ଫସିଯାଇଛୁ, ଉଦ୍ଧାର କରନ୍ତୁ")
        XCTAssertEqual(.rescue, d.category); XCTAssertEqual(6, d.people)
    }

    func testInsulinWithApostropheMother() {
        let d = x("ମୋ ମା'ଙ୍କୁ ଇନସୁଲିନ ଦରକାର, ସ୍କୁଲ ପାଖରେ")
        XCTAssertEqual("insulin", d.item); XCTAssertEqual("school", d.landmark)
    }

    func testRomanisedOdiaIsTaggedOdia() {
        let d = x("pina pani nahi, dui dina hela")
        XCTAssertEqual(.or, d.lang); XCTAssertEqual(.water, d.category)
        let f = x("amey 5 jane chhata upare phasi achhu, pani badhuchhi")
        XCTAssertEqual(.rescue, f.category); XCTAssertEqual(5, f.people)
    }

    // ---- supplies already in hand / negation
    func testHaveFoodButNoWater() {
        XCTAssertEqual(.water, x("We have food but we have no water, please send water.").category)
        XCTAssertEqual(.food, x("हमारे पास पानी है लेकिन खाना नहीं है").category)
        XCTAssertEqual(.water, x("Khana to hai lekin peene ka paani nahi hai").category)
    }

    func testFloodWaterIsNotASupply() {
        XCTAssertEqual(.rescue, x("we have water inside the house, stuck on the roof").category)
    }
}
