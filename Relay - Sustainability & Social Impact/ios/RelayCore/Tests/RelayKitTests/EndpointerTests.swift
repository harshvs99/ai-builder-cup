import XCTest
import RelayCore
@testable import RelayKit

/// Port of the Android `EndpointerTest.kt`.
final class EndpointerTests: XCTestCase {
    /// Feeds one level per 100 ms and returns the first non-listening verdict with its time.
    private func run(_ ep: inout Endpointer, _ levels: [Float]) -> (Endpointer.Verdict, Int64) {
        var t: Int64 = 0
        for l in levels { let v = ep.update(level: l, nowMs: t); if v != .listening { return (v, t) }; t += 100 }
        return (.listening, t)
    }
    private func quiet(_ n: Int) -> [Float] { Array(repeating: 0.01, count: n) }
    private func talk(_ n: Int) -> [Float] { Array(repeating: 0.3, count: n) }

    func testEndsAfterTheConfiguredQuietFollowingSpeech() {
        var ep = Endpointer(endAfterSpeechMs: 3_500)
        let (v, t) = run(&ep, quiet(5) + talk(20) + quiet(60))
        XCTAssertEqual(.endSpeech, v)
        XCTAssertTrue((5_500...6_200).contains(t), "ended at \(t) ms")
    }

    func testAPauseShorterThanTheLimitDoesNotEndIt() {
        var ep = Endpointer(endAfterSpeechMs: 6_000)
        let (v, _) = run(&ep, quiet(5) + talk(10) + quiet(50) + talk(10) + quiet(10))
        XCTAssertEqual(.listening, v)
        XCTAssertTrue(ep.heardSpeech)
    }

    func testNothingSaidGivesUp() {
        var ep = Endpointer(endAfterSpeechMs: 3_500, noSpeechMs: 8_000)
        let (v, t) = run(&ep, quiet(200))
        XCTAssertEqual(.endNothing, v)
        XCTAssertTrue((8_000...8_200).contains(t))
    }

    func testNeverRunsPastTheModelsClipLimit() {
        var ep = Endpointer(endAfterSpeechMs: 3_500)
        let (v, t) = run(&ep, talk(400))
        XCTAssertEqual(.endMax, v)
        XCTAssertLessThanOrEqual(t, 15_000)
    }

    func testANoisyRoomIsNotSpeech() {
        var ep = Endpointer(endAfterSpeechMs: 3_500, noSpeechMs: 8_000)
        let (v, _) = run(&ep, Array(repeating: 0.08, count: 200))
        XCTAssertEqual(.endNothing, v)
        XCTAssertFalse(ep.heardSpeech)
        XCTAssertGreaterThan(ep.threshold(), 0.08)
    }

    func testGemmaIsOnlyUsedForALanguageGapOnAPhoneThatCanRunIt() {
        XCTAssertTrue(SpeechLogic.useGemmaForSpeech(.languageUnavailable, gemmaUsable: true))
        XCTAssertFalse(SpeechLogic.useGemmaForSpeech(.languageUnavailable, gemmaUsable: false))
        for k in [SpeechLogic.Kind.retryOnline, .noSpeech, .permission, .busy, .failed] { XCTAssertFalse(SpeechLogic.useGemmaForSpeech(k, gemmaUsable: true), "\(k)") }
    }

    func testOnlyMeasuredLanguagesAreTrustedWithoutACheck() {
        XCTAssertFalse(SpeechLogic.gemmaNote(.en).contains("check"))
        XCTAssertFalse(SpeechLogic.gemmaNote(.hi).contains("check"))
        XCTAssertTrue(SpeechLogic.gemmaNote(.or).contains("check the text"))
        XCTAssertTrue(SpeechLogic.gemmaNote(.war).contains("Waray"))
    }

    func testWavHeaderIsCanonical() {
        let w = Wav.pcmToWav([1, 2, 3, 4])
        XCTAssertEqual(48, w.count)
        XCTAssertEqual(Array("RIFF".utf8), Array(w[0..<4]))
        XCTAssertEqual(Array("WAVE".utf8), Array(w[8..<12]))
        XCTAssertEqual([40, 0, 0, 0], Array(w[4..<8]))   // 36 + 4
        XCTAssertEqual([0x80, 0x3E, 0, 0], Array(w[24..<28])) // 16000 Hz
        XCTAssertEqual([4, 0, 0, 0], Array(w[40..<44]))
    }
}
