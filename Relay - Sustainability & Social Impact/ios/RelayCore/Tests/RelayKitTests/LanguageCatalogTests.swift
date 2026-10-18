import XCTest
import RelayCore
@testable import RelayKit

/// Mirror of Kotlin `LanguageCatalogTest`: same names, same cases.
final class LanguageCatalogTests: XCTestCase {
    private let everyLang = Lang.allCases

    // what the backend serves by default: English and Hindi on, the rest registered but off (backend/app/languages.py)
    private let serverDefault = """
    [
        {"code":"en","english_name":"English","native_name":"English","enabled":true,"reviewed":true},
        {"code":"hi","english_name":"Hindi","native_name":"हिन्दी","enabled":true,"reviewed":true},
        {"code":"or","english_name":"Odia","native_name":"ଓଡ଼ିଆ","enabled":false,"reviewed":false},
        {"code":"tl","english_name":"Filipino (Tagalog)","native_name":"Filipino","enabled":false,"reviewed":false},
        {"code":"war","english_name":"Waray","native_name":"Winaray","enabled":false,"reviewed":false},
        {"code":"ceb","english_name":"Cebuano","native_name":"Cebuano","enabled":false,"reviewed":false}]
    """

    func testLanguagePickerShowsOnlyLanguagesEnabledInBuildAndServer() {
        let server = LanguageCatalog.parseServer(serverDefault)
        XCTAssertEqual([Lang.en, .hi], LanguageCatalog.offered(build: everyLang, server: server))
        // enabled on the server but not in this build: hidden
        let odiaOn = LanguageCatalog.parseServer(serverDefault.replacingOccurrences(
            of: "\"code\":\"or\",\"english_name\":\"Odia\",\"native_name\":\"ଓଡ଼ିଆ\",\"enabled\":false",
            with: "\"code\":\"or\",\"english_name\":\"Odia\",\"native_name\":\"ଓଡ଼ିଆ\",\"enabled\":true"))
        XCTAssertEqual([Lang.en, .hi, .or], LanguageCatalog.offered(build: everyLang, server: odiaOn))
        XCTAssertEqual([Lang.en, .hi], LanguageCatalog.offered(build: [.en, .hi], server: odiaOn))
        // enabled in the build but off on the server: hidden
        XCTAssertEqual([Lang.en, .hi], LanguageCatalog.offered(build: everyLang, server: server))
    }

    func testLanguagePickerUsesBuildDefaultWithoutAServerResponse() {
        XCTAssertEqual([Lang.en, .hi], LanguageCatalog.offered(build: everyLang, server: nil))
        XCTAssertEqual([Lang.en, .hi], LanguageCatalog.offered(build: LanguageCatalog.parseBuildLanguages("en,hi"), server: nil))
        XCTAssertEqual([Lang.en], LanguageCatalog.offered(build: [.en], server: nil))
    }

    func testEnglishIsAlwaysOffered() {
        XCTAssertTrue(LanguageCatalog.offered(build: [], server: nil).contains(.en))
        XCTAssertTrue(LanguageCatalog.offered(build: everyLang, server: LanguageCatalog.parseServer("[{\"code\":\"hi\",\"enabled\":true}]")).contains(.en))
        XCTAssertTrue(LanguageCatalog.parseBuildLanguages("hi").contains(.en))
        XCTAssertEqual(LanguageCatalog.buildDefault, LanguageCatalog.parseBuildLanguages(""))
        XCTAssertEqual([Lang.en, .hi, .war], LanguageCatalog.parseBuildLanguages(" hi , war, xx"))
    }

    func testMalformedOrUnknownServerDataIsIgnored() {
        XCTAssertNil(LanguageCatalog.parseServer(nil))
        XCTAssertNil(LanguageCatalog.parseServer(""))
        XCTAssertNil(LanguageCatalog.parseServer("{\"error\":1}"))
        XCTAssertNil(LanguageCatalog.parseServer("not json"))
        // unknown fields, unknown codes and entries without a code are skipped, not fatal
        let s = LanguageCatalog.parseServer("[{\"code\":\"hi\",\"enabled\":true,\"future\":1},{\"code\":\"xx\",\"enabled\":true},{\"enabled\":true},7]")!
        XCTAssertEqual(["hi", "xx"], s.map { $0.code })
        XCTAssertEqual([Lang.en, .hi], LanguageCatalog.offered(build: everyLang, server: s))
        // a response that lists nothing leaves English only
        XCTAssertEqual([Lang.en], LanguageCatalog.offered(build: everyLang, server: LanguageCatalog.parseServer("[]")))
    }

    func testStoredDisabledLanguageFallsBackToEnglishAndAnnouncesOnce() {
        let offered: [Lang] = [.en, .hi]
        let ok = LanguageCatalog.resolve(stored: .hi, offered: offered)
        XCTAssertEqual(.hi, ok.effective); XCTAssertNil(ok.unavailable)
        XCTAssertFalse(LanguageCatalog.shouldAnnounce(ok, alreadyAnnounced: nil))

        let odia = LanguageCatalog.resolve(stored: .or, offered: offered)
        XCTAssertEqual(.en, odia.effective); XCTAssertEqual(.or, odia.unavailable)
        XCTAssertTrue(LanguageCatalog.shouldAnnounce(odia, alreadyAnnounced: nil))
        XCTAssertFalse(LanguageCatalog.shouldAnnounce(odia, alreadyAnnounced: .or)) // once
        XCTAssertTrue(LanguageCatalog.shouldAnnounce(LanguageCatalog.resolve(stored: .war, offered: offered), alreadyAnnounced: .or)) // a different one is new
        // the stored choice is untouched: when Odia is switched on again it is used again
        XCTAssertEqual(.or, LanguageCatalog.resolve(stored: .or, offered: [.en, .hi, .or]).effective)
    }

    // ---- settings
    func testPreferredLanguagesAreEnglishAndHindiWhileOthersAreHidden() {
        XCTAssertEqual([Lang.en, .hi], Settings().preferredLanguages)
        // A stored Odia choice (an install from before the freeze) is kept but not offered: speech stays en-IN and hi-IN.
        let odia = Settings(language: .or)
        XCTAssertEqual([Lang.en, .hi], odia.preferredLanguages)
        XCTAssertEqual(.or, odia.language)
        XCTAssertEqual(.en, odia.effectiveLanguage)
        XCTAssertEqual(.or, odia.languageResolution.unavailable)
    }

    func testAServerListThatSwitchesOdiaOnDoesNotOfferItInABuildWithoutIt() {
        let on = "[{\"code\":\"en\",\"enabled\":true},{\"code\":\"hi\",\"enabled\":true},{\"code\":\"or\",\"enabled\":true}]"
        XCTAssertEqual([Lang.en, .hi], Settings(serverLanguagesJson: on).offeredLanguages)
        XCTAssertEqual([Lang.en, .hi, .or], Settings(serverLanguagesJson: on, buildLanguages: Lang.allCases).offeredLanguages)
    }

    func testBuildConfigReadsTheEnabledLanguagesFromInfoPlist() {
        XCTAssertEqual([Lang.en, .hi], BuildConfig(infoDictionary: nil).enabledLanguages)
        XCTAssertEqual([Lang.en, .hi, .or], BuildConfig(infoDictionary: ["RelayEnabledLangs": "en,hi,or"]).enabledLanguages)
    }
}
