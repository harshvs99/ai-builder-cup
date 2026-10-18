import XCTest
import RelayCore
@testable import RelayKit

final class GemmaNeedParserTests: XCTestCase {
    func testParsesFencedReplyAndMapsToModelFields() {
        let raw = """
        Sure!
        ```json
        {"transcript":"4 trapped on roof","lang":"en","category":"RESCUE","urgency":3,"people":4,"vuln":["TRAPPED"],"item":"rescue","landmark":"clock tower","summary":"4 trapped on roof"}
        ```
        """
        let p = GemmaNeedParser.parse(raw)
        XCTAssertTrue(p.valid, "\(p.issues)")
        XCTAssertEqual(p.category, .rescue)
        XCTAssertEqual(p.vuln, [.trapped])
        XCTAssertEqual(p.modelFields?.people, 4)
        XCTAssertEqual(p.modelFields?.category, .rescue)
    }

    func testClampsAndTruncatesWithoutInvalidating() {
        let p = GemmaNeedParser.parse(#"{"transcript":"x","lang":"hi","category":"water","urgency":9,"people":0,"vuln":[],"item":"a very long item name here","landmark":"","summary":"s"}"#)
        XCTAssertTrue(p.valid)
        XCTAssertEqual(p.urgency, 3)
        XCTAssertEqual(Utf8.truncateToBytes(p.item, 99).count, 16)
        XCTAssertFalse(p.repairs.isEmpty)
        XCTAssertNil(p.modelFields?.people)
    }

    func testBadEnumAndMissingKeyAreInvalid() {
        let p = GemmaNeedParser.parse(#"{"transcript":"x","lang":"xx","category":"LOST","urgency":1}"#)
        XCTAssertFalse(p.valid)
        XCTAssertNil(p.modelFields)
        XCTAssertFalse(GemmaNeedParser.parse("no json here").jsonFound)
    }

    func testBracesInsideStringsDoNotBreakExtraction() {
        let p = GemmaNeedParser.parse(#"{"transcript":"a } b","lang":"en","category":"FOOD","urgency":1,"people":2,"vuln":[],"item":"food","landmark":"","summary":"s"} trailing"#)
        XCTAssertTrue(p.valid, "\(p.issues)")
        XCTAssertEqual(p.transcript, "a } b")
    }
}
