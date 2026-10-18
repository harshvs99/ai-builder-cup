import XCTest
import RelayCore
@testable import RelayKit

final class SpeechLogicTests: XCTestCase {
    private func err(_ code: Int, _ offline: Bool, _ online: Bool, retried: Bool = false, lang: Lang = .en) -> SpeechLogic.Decision {
        SpeechLogic.onError(code, lang: lang, offlineAttempt: offline, online: online, alreadyRetried: retried)
    }

    func testOfflineFlagOnlyWithoutInternet() {
        XCTAssertTrue(SpeechLogic.preferOffline(online: false))
        XCTAssertFalse(SpeechLogic.preferOffline(online: true))
    }

    func testLanguageTags() {
        XCTAssertEqual("en-IN", SpeechLogic.recognizerTag(.en))
        XCTAssertEqual("hi-IN", SpeechLogic.recognizerTag(.hi))
        XCTAssertEqual("or-IN", SpeechLogic.recognizerTag(.or))
        XCTAssertEqual("fil-PH", SpeechLogic.recognizerTag(.tl))
        XCTAssertEqual("fil-PH", SpeechLogic.recognizerTag(.war))
        XCTAssertEqual("fil-PH", SpeechLogic.recognizerTag(.ceb))
    }

    func testFallbackNoteOnlyForWarayAndCebuano() {
        XCTAssertNil(SpeechLogic.fallbackNote(.en))
        XCTAssertNil(SpeechLogic.fallbackNote(.tl))
        XCTAssertTrue(SpeechLogic.fallbackNote(.war)!.contains("Filipino"))
        XCTAssertTrue(SpeechLogic.fallbackNote(.ceb)!.contains("Cebuano"))
    }

    func testOfflinePackMissingWhileOnlineRetriesOnline() {
        for code in [12, 13, 1, 2] { XCTAssertEqual(.retryOnline, err(code, true, true).kind, "code \(code)") }
    }

    func testOnlyOneRetry() {
        let d = err(13, true, true, retried: true)
        XCTAssertEqual(.languageUnavailable, d.kind)
        XCTAssertTrue(d.offerVoiceSettings)
    }

    func testOfflineWithNoPackShowsOfflineMessage() {
        let d = err(13, true, false, lang: .hi)
        XCTAssertEqual(.languageUnavailable, d.kind)
        XCTAssertEqual("Voice for Hindi isn't available on this phone offline — type your request, or download the Hindi voice pack (needs internet).", d.message)
        XCTAssertTrue(d.offerVoiceSettings)
    }

    func testOnlineAttemptFailingShowsNonOfflineMessage() {
        let d = err(13, false, true)
        XCTAssertEqual(.languageUnavailable, d.kind)
        XCTAssertFalse(d.message!.contains("offline"))
        XCTAssertTrue(d.message!.contains("English"))
    }

    func testNoSpeechAndBusyAndPermission() {
        XCTAssertEqual(.noSpeech, err(7, true, false).kind)
        XCTAssertEqual(.noSpeech, err(6, false, true).kind)
        XCTAssertEqual(.busy, err(8, false, true).kind)
        XCTAssertEqual(.busy, err(10, false, true).kind)
        XCTAssertEqual(.permission, err(9, false, true).kind)
    }

    func testNoSpeechNeverRetriesOnline() {
        XCTAssertEqual(.noSpeech, err(7, true, true).kind)
    }

    func testOtherErrorsFailCleanlyWithMessage() {
        let a = err(3, false, true); XCTAssertEqual(.failed, a.kind); XCTAssertFalse(a.message!.isEmpty)
        let c = err(5, false, true); XCTAssertEqual(.failed, c.kind); XCTAssertTrue(c.message!.contains("5"))
    }

    func testEveryErrorCodeYieldsAUserVisibleOutcome() {
        for code in 1...15 { for off in [true, false] { for on in [true, false] {
            let d = err(code, off, on)
            XCTAssertTrue(d.kind == .retryOnline || !(d.message ?? "").trimmed.isEmpty, "\(code)/\(off)/\(on)")
        } } }
    }
}
