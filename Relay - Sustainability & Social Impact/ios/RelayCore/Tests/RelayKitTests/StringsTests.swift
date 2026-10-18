import XCTest
import RelayCore
@testable import RelayKit

final class StringsTests: XCTestCase {
    private typealias Bucket = RelativeTime.Bucket
    private let appLangs = UI.appLangs
    private func at(_ d: Int, _ h: Int, _ m: Int = 0, _ s: Int = 0) -> Int64 { UI.at(d, h, m, s) }

    func testPluralRules() {
        XCTAssertTrue(Plural.isOne(.en, 1))
        XCTAssertFalse(Plural.isOne(.en, 0))
        XCTAssertFalse(Plural.isOne(.en, 2))
        XCTAssertTrue(Plural.isOne(.hi, 0))
        XCTAssertTrue(Plural.isOne(.hi, 1))
        XCTAssertFalse(Plural.isOne(.hi, 2))
        XCTAssertEqual("one", Plural.pick(.tl, 1, one: "one", other: "other"))
        XCTAssertEqual("other", Plural.pick(.tl, 6, one: "one", other: "other"))
    }

    func testRelativeTimeBuckets() {
        let now = at(18, 14, 0)
        let utc = UI.utc
        XCTAssertEqual(Bucket.justNow, RelativeTime.bucket(now - 30_000, now, zone: utc))
        XCTAssertEqual(Bucket.justNow, RelativeTime.bucket(now + 5_000, now, zone: utc)) // clock skew
        XCTAssertEqual(Bucket.minutes(1), RelativeTime.bucket(now - 60_000, now, zone: utc))
        XCTAssertEqual(Bucket.minutes(59), RelativeTime.bucket(now - 59 * 60_000 - 59_000, now, zone: utc))
        XCTAssertEqual(Bucket.hours(1), RelativeTime.bucket(now - 3_600_000, now, zone: utc))
        XCTAssertEqual(Bucket.hours(10), RelativeTime.bucket(at(18, 4, 0), now, zone: utc))
        // Crossing midnight is "yesterday" even when under 24 hours ago.
        XCTAssertEqual(Bucket.yesterday, RelativeTime.bucket(at(17, 23, 0), now, zone: utc))
        XCTAssertEqual(Bucket.yesterday, RelativeTime.bucket(at(17, 1, 0), now, zone: utc))
        XCTAssertEqual(Bucket.days(3), RelativeTime.bucket(at(15, 23, 0), now, zone: utc))
    }

    func testCalendarDaysFollowTheZone() {
        // 23:30 UTC on the 17th is already the 18th in Kolkata (UTC+5:30), so 04:00 UTC on the 18th is the same day there.
        let kolkata = TimeZone(identifier: "Asia/Kolkata")!
        let now = at(18, 4, 0)
        XCTAssertEqual(Bucket.yesterday, RelativeTime.bucket(at(17, 23, 30), now, zone: UI.utc))
        XCTAssertEqual(Bucket.hours(4), RelativeTime.bucket(at(18, 0, 0), now, zone: kolkata))
        XCTAssertEqual(Bucket.hours(4), RelativeTime.bucket(at(18, 0, 0), now, zone: UI.utc))
        XCTAssertEqual(Bucket.yesterday, RelativeTime.bucket(at(17, 18, 0), now, zone: kolkata)) // 23:30 on the 17th there
    }

    func testTrPicksLanguageAndCebuanoFallsBackToEnglish() {
        XCTAssertEqual("a", tr(.en, "a", "b", "c", "d", "e"))
        XCTAssertEqual("b", tr(.hi, "a", "b", "c", "d", "e"))
        XCTAssertEqual("c", tr(.or, "a", "b", "c", "d", "e"))
        XCTAssertEqual("d", tr(.tl, "a", "b", "c", "d", "e"))
        XCTAssertEqual("e", tr(.war, "a", "b", "c", "d", "e"))
        XCTAssertEqual("a", tr(.ceb, "a", "b", "c", "d", "e"))
    }

    func testEnglishCommonTexts() {
        let c = UI.strings(.en).common
        XCTAssertEqual("1 person", c.people(1))
        XCTAssertEqual("6 people", c.people(6))
        XCTAssertEqual("1 phone", c.phones(1))
        XCTAssertEqual("3 phones", c.phones(3))
        XCTAssertEqual("1 hop", c.hops(1))
        XCTAssertEqual("2 hops", c.hops(2))
        let now = at(18, 14, 0)
        XCTAssertEqual("just now", c.ago(now - 10_000, now))
        XCTAssertEqual("2 min ago", c.ago(now - 120_000, now))
        XCTAssertEqual("3 h ago", c.ago(now - 3 * 3_600_000, now))
        XCTAssertEqual("yesterday", c.ago(at(17, 20, 0), now))
        XCTAssertEqual("4 days ago", c.ago(at(14, 9, 0), now))
        XCTAssertEqual("Urgent", c.urgencyWord(2))
        XCTAssertEqual("Life at risk", c.urgencyWord(3))
        XCTAssertEqual("Life at risk", c.urgencyWord(99)) // clamped
        XCTAssertEqual("Can wait", c.urgencyWord(-4))
        XCTAssertEqual("Soon: needed today.", c.urgencyHelp(1))
        XCTAssertEqual("Medicine", c.categoryName(.medical))
        XCTAssertEqual("elderly, children", c.vulnWords(VulnFlag.toMask([VulnFlag.child, VulnFlag.elderly])))
        XCTAssertEqual("", c.vulnWords(0))
        typealias Step = RequestTimeline.Step
        XCTAssertEqual("Delivered yesterday", c.rowStatusLine(Step(key: .delivered, state: .done, atMs: at(17, 20, 0)), now))
        XCTAssertEqual("Reached a gateway phone · 2 min ago", c.rowStatusLine(Step(key: .reachedGateway, state: .current, atMs: now - 120_000), now))
        XCTAssertEqual("Saved on this phone", c.rowStatusLine(Step(key: .saved, state: .current, atMs: 0), now))
    }

    func testClockFollowsLanguageLocaleAndZone() {
        let t = at(18, 14, 2)
        XCTAssertEqual("2:02 PM", UI.plain(UI.strings(.en).common.clock(t)))
        let kolkata = Strings(.en, timeZone: TimeZone(identifier: "Asia/Kolkata")!)
        XCTAssertEqual("7:32 PM", UI.plain(kolkata.common.clock(t)))
        for lang in appLangs { XCTAssertFalse(UI.strings(lang).common.clock(t).isEmpty, "\(lang)") }
        // 24-hour wording is the locale's call, not ours: only check Hindi uses its own digits or a different layout than English.
        XCTAssertTrue(UI.strings(.hi).common.clock(t).contains("2") || UI.strings(.hi).common.clock(t).contains("२"))
    }

    func testStepSubtitleAndExpectedBy() {
        let c = UI.strings(.en).common
        let t0 = at(18, 14, 2)
        typealias Step = RequestTimeline.Step
        XCTAssertEqual("2:02 PM · uploaded to the control room", UI.plain(c.stepSubtitle(Step(key: .reachedGateway, state: .done, atMs: t0))))
        XCTAssertEqual("2:02 PM · marked urgent", UI.plain(c.stepSubtitle(Step(key: .seenByControlRoom, state: .done, atMs: t0, urgency: 2))))
        XCTAssertEqual("2:02 PM · Expected by 2:40 PM", UI.plain(c.stepSubtitle(Step(key: .helpComing, state: .current, atMs: t0, etaAtMs: at(18, 14, 40)))))
        XCTAssertEqual("2:02 PM · Later than expected", UI.plain(c.stepSubtitle(Step(key: .teamOnTheWay, state: .current, atMs: t0, etaAtMs: at(18, 14, 40), live: true, etaOverdue: true))))
        XCTAssertEqual("", c.stepSubtitle(Step(key: .saved, state: .done, atMs: 0)))
    }

    func testVillagerTextsFollowTheSpecTable() {
        let c = UI.strings(.en).common
        let now = at(18, 14, 0)
        let reachable = VillagerStatus(kind: .reachable, dot: .green, peers: 2, hopsToGateway: 2, syncedAgoMs: nil)
        XCTAssertEqual("Your messages can reach help", c.villagerTitle(reachable))
        XCTAssertEqual("Gateway 2 hops away", c.villagerLine(reachable, now))
        XCTAssertEqual("Reachable", c.villagerWord(reachable))
        let reachableSynced = VillagerStatus(kind: .reachable, dot: .green, peers: 2, hopsToGateway: 1, syncedAgoMs: 120_000)
        XCTAssertEqual("Gateway 1 hop away · online 2 min ago", c.villagerLine(reachableSynced, now))
        let mine = VillagerStatus(kind: .thisPhoneGateway, dot: .green, peers: 0, hopsToGateway: nil, syncedAgoMs: 180_000)
        XCTAssertEqual("Connected to the internet · synced 3 min ago", c.villagerLine(mine, now))
        let waiting = VillagerStatus(kind: .waitingForGateway, dot: .amber, peers: 3, hopsToGateway: nil, syncedAgoMs: nil)
        XCTAssertEqual("Connected to 3 phones nearby. Your messages wait safely.", c.villagerLine(waiting, now))
        XCTAssertEqual("Waiting", c.villagerWord(waiting))
        let lonely = VillagerStatus(kind: .waitingForGateway, dot: .amber, peers: 0, hopsToGateway: nil, syncedAgoMs: nil)
        XCTAssertEqual("Your messages wait safely.", c.villagerLine(lonely, now))
        let none = VillagerStatus(kind: .searching, dot: .grey, peers: 0, hopsToGateway: nil, syncedAgoMs: nil)
        XCTAssertEqual("Looking for nearby phones", c.villagerTitle(none))
        XCTAssertEqual("Searching", c.villagerWord(none))
        let off = VillagerStatus(kind: .bluetoothOff, dot: .grey, peers: 0, hopsToGateway: nil, syncedAgoMs: nil)
        XCTAssertEqual("Off", c.villagerWord(off))
    }

    func testEveryLanguageHasEveryCommonString() {
        let now = at(18, 14, 0)
        for lang in appLangs {
            let c = UI.strings(lang).common
            var all: [String] = [
                c.navHome, c.navRequests, c.navNetwork, c.navSettings, c.change, c.continueAction, c.send, c.cancel, c.done, c.back,
                c.close, c.seeAll, c.turnOn, c.somethingElse, c.statusClosed, c.statusCancelled, c.statusActive, c.liveTag,
                c.runningLate, c.people(1), c.people(4), c.phones(2), c.hops(2), c.expectedBy(now),
                c.ago(now - 10_000, now), c.ago(now - 120_000, now), c.ago(now - 7_200_000, now), c.ago(at(17, 20), now), c.ago(at(10, 1), now),
            ]
            for u in 0...3 { all.append(c.urgencyWord(u)); all.append(c.urgencyHelp(u)) }
            for cat in Category.allCases { all.append(c.categoryName(cat)) }
            for f in VulnFlag.allCases { all.append(c.vulnFlag(f)) }
            for k in RequestTimeline.Key.allCases { all.append(c.stepTitle(k)) }
            for k in [VillagerStatus.Kind.reachable, .thisPhoneGateway, .waitingForGateway, .searching, .bluetoothOff] {
                let s = VillagerStatus(kind: k, dot: .green, peers: 2, hopsToGateway: 1, syncedAgoMs: 60_000)
                all.append(contentsOf: [c.villagerTitle(s), c.villagerLine(s, now), c.villagerWord(s)])
            }
            for (i, s) in all.enumerated() { XCTAssertFalse(s.trimmed.isEmpty, "blank common string #\(i) in \(lang)") }
            XCTAssertFalse(c.clock(now).isEmpty)
        }
    }

    func testEveryLanguageHasEveryScreenString() {
        for lang in appLangs {
            let s = UI.strings(lang)
            let h = s.home, a = s.ask, r = s.request, n = s.network
            var all: [String] = [
                h.title, h.chooseTitle, h.chooseSubtitle, h.speakLanguage("X"), h.noVoice("X"), h.helpLabel, h.helpLine1, h.helpLine2,
                h.helpLine3, h.statusOpensNetwork, h.pressAndSay, h.categoriesLine, h.typeInstead, h.yourRequests, h.noRequestsShort,
                h.bluetoothWhy, h.requestsTitle, h.emptyRequests,
                a.newRequestTitle, a.stepCounter(1, 3), a.newRequestStep, a.checkStep, a.headline, a.hearItAgain, a.noVoiceInstalled,
                a.listening, a.paused, a.sayItPrompt, a.understoodSoFar, a.nothingYet, a.nearLandmark("X"), a.orTapWhat,
                a.stopListening, a.startListening, a.doneCheckIt, a.typeInstead, a.useVoiceInstead, a.typeHint, a.typePlaceholder,
                a.voicePackMissing("X"), a.micDenied, a.didntCatch, a.voiceUnavailable, a.openSettings, a.checkTitle, a.isThisRight,
                a.rowNeed, a.rowPeople, a.rowWhere, a.rowNote, a.nothingChosen, a.noLandmark, a.noNote, a.locationAccuracy(40),
                a.locationAcquiring, a.locationOff, a.quickQuestions, a.hearQuestion("X"), a.howUrgent, a.changeSomething, a.footnote,
                a.sending, a.sendingGettingLocation, a.editNeedTitle, a.itemLabel, a.editPeopleTitle, a.morePeople, a.fewerPeople,
                a.whoIsWithYou, a.editWhereTitle, a.landmarkLabel, a.editNoteTitle, a.sentInEnglish, a.noteSentInEnglish, a.noteLabel,
                a.charsLeft(7),
                r.title, r.share, r.referenceLine("R-1", "2:00 PM", "Ward"), r.fromControlRoom, r.hearItIn("X"), r.noVoice,
                r.cancelRequest, r.addToRequest, r.addShort, r.cancelShort, r.cancelDialogTitle, r.cancelDialogBody, r.cancelConfirm,
                r.cancelKeep, r.gone, r.shareHeader("R-1"), r.shareStatus("S"), r.shareNear("X"), r.shareMessage("X"), r.shareChooser,
                n.settingsTitle, n.networkTitle, n.meshWord, n.consoleDescription, n.chainYou, n.chainThisPhone, n.chainGatewayItself,
                n.chainHop(2), n.chainGateway(2), n.chainUnknown, n.chainDescription(["a", "b"]), n.phonesNearby,
                n.carriedForNeighbours, n.waitingToPassOn, n.meshHoursLeft, n.hoursValue(5), n.hoursUnder1, n.charging, n.batteryLabel,
                n.unknownValue, n.gatewayToggle, n.gatewayToggleHelp, n.carryToggle, n.carryToggleHelp, n.carryToggleHelpIOS,
                n.iPhoneBestOpenNote, n.groupLanguageVoice, n.groupScreenBattery, n.groupNetworkPermissions, n.groupBeforeDisaster,
                n.language, n.chooseLanguage, n.readAloud, n.voiceSpeed, n.speedSlow, n.speedNormal, n.theme, n.themeLight, n.themeDark,
                n.themeAuto, n.largerText, n.darkNote, n.yourArea, n.areaNotSet, n.areaHelp, n.save, n.gatewayMode, n.gatewayAutomatic,
                n.gatewayNever, n.gatewayAutomaticHelp, n.gatewayNeverHelp, n.helpLocate, n.helpLocateHelp, n.permBluetooth, n.permLocation, n.permMicrophone,
                n.permAllowed, n.permWhileUsing, n.permAsk, n.permNotAllowed, n.permOpensSettings, n.householdPlan, n.householdPlanHelp,
                n.setUp, n.comingSoon, n.comingSoonTitle, n.comingSoonBody, n.developer,
            ]
            for l in appLangs { all.append(h.greeting(l)) }
            for q in [VillagerStatus.Kind.reachable, .thisPhoneGateway, .waitingForGateway, .searching, .bluetoothOff] {
                all.append(n.statusSentence(VillagerStatus(kind: q, dot: .green, peers: 1, hopsToGateway: 2, syncedAgoMs: nil)))
            }
            for (i, str) in all.enumerated() { XCTAssertFalse(str.trimmed.isEmpty, "blank string #\(i) in \(lang)") }
        }
    }

    /// Every parameterized string keeps its argument in every language (no dropped or mistyped placeholder).
    func testPlaceholdersSurviveInEveryLanguage() {
        for lang in appLangs {
            let s = UI.strings(lang)
            let x = "ZQX"
            let withArg: [(String, String)] = [
                ("speakLanguage", s.home.speakLanguage(x)), ("noVoice", s.home.noVoice(x)),
                ("nearLandmark", s.ask.nearLandmark(x)), ("voicePackMissing", s.ask.voicePackMissing(x)),
                ("hearQuestion", s.ask.hearQuestion(x)), ("hearItIn", s.request.hearItIn(x)),
                ("shareHeader", s.request.shareHeader(x)), ("shareStatus", s.request.shareStatus(x)),
                ("shareNear", s.request.shareNear(x)), ("shareMessage", s.request.shareMessage(x)),
                ("referenceLine/ref", s.request.referenceLine(x, "T", "A")), ("referenceLine/time", s.request.referenceLine("R", x, "A")),
                ("referenceLine/area", s.request.referenceLine("R", "T", x)), ("chainDescription", s.network.chainDescription([x, "b"])),
            ]
            for (name, text) in withArg { XCTAssertTrue(text.contains(x), "\(name) lost its argument in \(lang): \(text)") }
            // numbers
            XCTAssertTrue(s.ask.stepCounter(2, 3).contains("2") && s.ask.stepCounter(2, 3).contains("3"), "stepCounter \(lang)")
            XCTAssertTrue(s.ask.locationAccuracy(4321).contains("4321"), "locationAccuracy \(lang)")
            XCTAssertTrue(s.ask.charsLeft(4321).contains("4321"), "charsLeft \(lang)")
            XCTAssertTrue(s.network.hoursValue(4321).contains("4321"), "hoursValue \(lang)")
            XCTAssertTrue(s.network.chainGateway(4321).contains("4321"), "chainGateway \(lang)")
            XCTAssertTrue(s.common.people(4321).contains("4321"), "people \(lang)")
            XCTAssertTrue(s.common.phones(4321).contains("4321"), "phones \(lang)")
            XCTAssertTrue(s.common.hops(4321).contains("4321"), "hops \(lang)")
            // No Kotlin or Swift template syntax leaks into the text.
            for text in [s.ask.nearLandmark(x), s.ask.stepCounter(1, 3), s.request.hearItIn(x), s.common.people(3), s.network.hoursValue(2)] {
                XCTAssertFalse(text.contains("$"), "template leaked in \(lang): \(text)")
                XCTAssertFalse(text.contains("\\("), "template leaked in \(lang): \(text)")
            }
        }
    }

    func testNonEnglishDiffersFromEnglishForKeyTexts() {
        let en = UI.strings(.en).common
        for lang in [Lang.hi, .or, .tl, .war] {
            let c = UI.strings(lang).common
            XCTAssertNotEqual(en.send, c.send, "\(lang)")
            XCTAssertNotEqual(en.urgencyWord(3), c.urgencyWord(3), "\(lang)")
            XCTAssertNotEqual(en.stepTitle(.helpComing), c.stepTitle(.helpComing), "\(lang)")
            let n = UI.strings(lang).network
            XCTAssertNotEqual(UI.strings(.en).network.carryToggleHelpIOS, n.carryToggleHelpIOS, "\(lang)")
            XCTAssertNotEqual(UI.strings(.en).network.iPhoneBestOpenNote, n.iPhoneBestOpenNote, "\(lang)")
            XCTAssertNotEqual(UI.strings(.en).network.permWhileUsing, n.permWhileUsing, "\(lang)")
        }
    }

    func testCebuanoShowsEnglish() {
        XCTAssertEqual(UI.strings(.en).common.send, UI.strings(.ceb).common.send)
        XCTAssertEqual(UI.strings(.en).ask.headline, UI.strings(.ceb).ask.headline)
    }

    func testStringsForCarriesLanguageAndIsAValue() {
        XCTAssertEqual(.or, stringsFor(.or).lang)
        XCTAssertEqual(stringsFor(.hi, timeZone: UI.utc), Strings(.hi, timeZone: UI.utc))
        XCTAssertNotEqual(stringsFor(.hi, timeZone: UI.utc), stringsFor(.en, timeZone: UI.utc))
        XCTAssertEqual("Settings", Strings.english.common.navSettings)
    }

    func testAppLanguages() {
        XCTAssertEqual([Lang.en, .hi, .or, .tl, .war], AppLanguages.all.map { $0.lang })
        XCTAssertEqual("Hindi", AppLanguages.of(.hi).englishName)
        XCTAssertEqual("हिन्दी", AppLanguages.of(.hi).nativeName)
        XCTAssertEqual("Filipino", AppLanguages.of(.tl).englishName)
        XCTAssertEqual("English", AppLanguages.of(.ceb).englishName) // no UI text yet: English
    }

    // ---- iOS copy deltas (docs/design/ios-build-plan.md)

    func testIosCopyDeltasExistInEnglish() {
        let s = UI.strings(.en)
        XCTAssertEqual("Bluetooth only. Slower while the app is closed.", s.network.carryToggleHelpIOS)
        XCTAssertEqual("An iPhone carries messages best with Relay open.", s.network.iPhoneBestOpenNote)
        XCTAssertEqual("Ask each time", s.network.permAsk)
        XCTAssertEqual("Automatic", s.network.gatewayAutomatic)
        XCTAssertEqual("Allowed", s.network.permAllowed)
        XCTAssertEqual("While using", s.network.permWhileUsing)
        XCTAssertEqual("Set up", s.network.setUp)
        XCTAssertEqual("Coming soon", s.network.comingSoon)
        XCTAssertEqual("Hear it in Hindi", s.request.hearItIn("Hindi"))
        XCTAssertEqual("1 of 3", s.ask.newRequestStep)
        XCTAssertEqual("2 of 3", s.ask.checkStep)
        XCTAssertEqual("+ Add", s.request.addShort)
        XCTAssertEqual("× Cancel", s.request.cancelShort)
        XCTAssertEqual("Listening", s.ask.listening)
        XCTAssertEqual("LIVE", s.common.liveTag)
    }

    func testStepCounterIsLocalized() {
        XCTAssertEqual("3 में से 1", UI.strings(.hi).ask.newRequestStep)
        XCTAssertEqual("2 sa 3", UI.strings(.tl).ask.checkStep)
        XCTAssertEqual("2 han 3", UI.strings(.war).ask.checkStep)
    }

    /// The iOS-only additions are drafts: their non-English lines carry the review marker in the source.
    func testNewNonEnglishLinesAreMarkedForNativeReview() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/RelayKit/I18n")
        guard let text = try? String(contentsOf: dir.appendingPathComponent("NetworkSettingsStrings.swift"), encoding: .utf8),
              let req = try? String(contentsOf: dir.appendingPathComponent("RequestStrings.swift"), encoding: .utf8)
        else { throw XCTSkip("sources not next to the tests") }
        let all = (text + "\n" + req).components(separatedBy: "\n")
        for name in ["carryToggleHelpIOS", "iPhoneBestOpenNote", "permWhileUsing", "addShort", "cancelShort"] {
            guard let start = all.firstIndex(where: { $0.contains("var \(name):") }) else { XCTFail("\(name) not found"); continue }
            // the statement ends on the first line that closes the tr( call and braces
            var end = start
            while end < all.count - 1 && !all[end].contains("} //") && !all[end].hasSuffix("}") { end += 1 }
            XCTAssertTrue(all[end].contains("// needs native review"), "\(name) lacks the native review marker")
        }
    }
}
