import XCTest
import RelayCore
@testable import RelayKit

final class LocationLogicTests: XCTestCase {
    private let now: Int64 = 1_000_000
    private func fix(_ acc: Double, at: Int64? = nil) -> Fix { Fix(lat: 20.3, lon: 85.8, accuracyM: acc, atMs: at ?? now) }

    // ---- state machine
    func testUiStates() {
        XCTAssertEqual(.noPermission, LocationState.ui(hasPermission: false, systemEnabled: true, fix: nil, nowMs: now))
        XCTAssertEqual(.noPermission, LocationState.ui(hasPermission: false, systemEnabled: false, fix: nil, nowMs: now)) // permission is the first thing to fix
        XCTAssertEqual(.off, LocationState.ui(hasPermission: true, systemEnabled: false, fix: nil, nowMs: now))
        XCTAssertEqual(.acquiring, LocationState.ui(hasPermission: true, systemEnabled: true, fix: nil, nowMs: now))
        XCTAssertEqual(.ready, LocationState.ui(hasPermission: true, systemEnabled: true, fix: fix(8), nowMs: now))
    }

    func testStaleFixIsNotReady() {
        XCTAssertEqual(.acquiring, LocationState.ui(hasPermission: true, systemEnabled: true, fix: fix(8, at: now - FixPolicy.maxAgeMs - 1), nowMs: now))
        XCTAssertEqual(.ready, LocationState.ui(hasPermission: true, systemEnabled: true, fix: fix(8, at: now - FixPolicy.maxAgeMs), nowMs: now))
    }

    func testLabels() {
        XCTAssertEqual("📍 ±8 m", LocationState.label(.ready, fix: fix(8.4)))
        XCTAssertEqual("📍 ±9 m", LocationState.label(.ready, fix: fix(8.6)))
        XCTAssertEqual("📍 ±1.5 km", LocationState.label(.ready, fix: fix(1500)))
        XCTAssertEqual("📍 accuracy unknown", LocationState.label(.ready, fix: fix(.nan)))
        XCTAssertEqual("📍 accuracy unknown", LocationState.label(.ready, fix: fix(-1)))
        XCTAssertEqual("📍 Getting location…", LocationState.label(.acquiring, fix: nil))
        XCTAssertEqual("📍 Location is off", LocationState.label(.off, fix: nil))
        XCTAssertEqual("📍 No permission", LocationState.label(.noPermission, fix: nil))
    }

    func testFallbackNoteOnlyWithoutFix() {
        XCTAssertNil(LocationState.fallbackNote(.ready))
        for u in [LocUi.acquiring, .off, .noPermission] { XCTAssertEqual("Rescuers will rely on the landmark", LocationState.fallbackNote(u)) }
    }

    func testOnlyAcquiringIsWorthWaitingFor() {
        XCTAssertTrue(LocationState.shouldWaitForFix(.acquiring))
        XCTAssertFalse(LocationState.shouldWaitForFix(.off))
        XCTAssertFalse(LocationState.shouldWaitForFix(.noPermission))
        XCTAssertFalse(LocationState.shouldWaitForFix(.ready))
    }

    func testRestartRule() {
        XCTAssertTrue(LocationState.shouldRestart(.acquiring, listening: false, cardVisible: true))
        XCTAssertFalse(LocationState.shouldRestart(.acquiring, listening: true, cardVisible: true))
        XCTAssertFalse(LocationState.shouldRestart(.acquiring, listening: false, cardVisible: false))
        XCTAssertFalse(LocationState.shouldRestart(.off, listening: false, cardVisible: true))
    }

    // ---- best-fix selection
    func testFirstFixAlwaysWins() { XCTAssertTrue(FixPolicy.isBetter(current: nil, candidate: fix(5000), nowMs: now)) }

    func testMoreAccurateReplacesLessAccurate() {
        XCTAssertTrue(FixPolicy.isBetter(current: fix(300), candidate: fix(20), nowMs: now))
        XCTAssertTrue(FixPolicy.isBetter(current: fix(20), candidate: fix(20), nowMs: now)) // equal: newer wins
    }

    func testLessAccurateDoesNotReplaceRecentGoodFix() {
        XCTAssertFalse(FixPolicy.isBetter(current: fix(8, at: now - 10_000), candidate: fix(300), nowMs: now))
    }

    func testStaleFixIsReplacedByAnything() {
        XCTAssertTrue(FixPolicy.isBetter(current: fix(8, at: now - FixPolicy.staleAfterMs - 1), candidate: fix(300), nowMs: now))
    }

    func testUnknownAccuracyIsWorstCase() {
        XCTAssertTrue(FixPolicy.isBetter(current: fix(.nan), candidate: fix(900), nowMs: now))
        XCTAssertFalse(FixPolicy.isBetter(current: fix(900), candidate: fix(.nan), nowMs: now))
    }

    func testGoodEnoughIsTheBestBucket() {
        XCTAssertTrue(FixPolicy.isGoodEnough(fix(9.9)))
        XCTAssertFalse(FixPolicy.isGoodEnough(fix(10)))
        XCTAssertFalse(FixPolicy.isGoodEnough(fix(.nan)))
        XCTAssertEqual(1, fix(9.9).locAcc)
    }

    // ---- bounded wait
    func testWaitReturnsImmediatelyWhenFixAlreadyThere() async {
        let fixes = Broadcaster<Fix?>(fix(8)), skip = Broadcaster(false)
        let got = await FixWait.wait(fixes: fixes.updates(), skip: skip.updates(), timeoutMs: 2_000)
        XCTAssertEqual(8, got?.accuracyM)
    }

    func testWaitTimesOutWithNil() async {
        let fixes = Broadcaster<Fix?>(nil), skip = Broadcaster(false)
        let t0 = currentTimeMs()
        let got = await FixWait.wait(fixes: fixes.updates(), skip: skip.updates(), timeoutMs: 150)
        XCTAssertNil(got)
        XCTAssertLessThan(currentTimeMs() - t0, 1_500)
    }

    func testWaitReturnsFixThatArrivesLater() async {
        let fixes = Broadcaster<Fix?>(nil), skip = Broadcaster(false)
        let f = fix(15)
        Task { try? await Task.sleep(nanoseconds: 80_000_000); fixes.send(f) }
        let got = await FixWait.wait(fixes: fixes.updates(), skip: skip.updates(), timeoutMs: 3_000)
        XCTAssertEqual(15, got?.accuracyM)
    }

    func testSendNowSkipsTheWait() async {
        let fixes = Broadcaster<Fix?>(nil), skip = Broadcaster(false)
        Task { try? await Task.sleep(nanoseconds: 80_000_000); skip.send(true) }
        let t0 = currentTimeMs()
        let got = await FixWait.wait(fixes: fixes.updates(), skip: skip.updates(), timeoutMs: 10_000)
        XCTAssertNil(got)
        XCTAssertLessThan(currentTimeMs() - t0, 3_000)
    }

    func testDefaultWaitBudgetIsTenSeconds() { XCTAssertEqual(10_000, FixWait.defaultTimeoutMs) }

    // ---- LOC_HINT fix bridge
    func testHintFixConvertsAndKeepsOnlyUsableFixes() throws {
        let f = try XCTUnwrap(HintFix.current(fix(12, at: now - 5_000), nowMs: now))
        XCTAssertEqual(20.3, f.lat); XCTAssertEqual(85.8, f.lon)
        XCTAssertEqual(12, f.accuracyM); XCTAssertEqual(now - 5_000, f.atMs)
        XCTAssertNil(HintFix.current(nil, nowMs: now))
        XCTAssertNil(HintFix.current(fix(12, at: now - FixPolicy.maxAgeMs - 1), nowMs: now), "older than FixPolicy.maxAgeMs")
        XCTAssertEqual(now, HintFix.current(fix(12, at: 0), nowMs: now)?.atMs, "fix without a timestamp counts as taken now")
        // an unknown accuracy converts to NaN, which LocHintCodec.bucketFor refuses
        let unknown = try XCTUnwrap(HintFix.current(fix(.nan), nowMs: now))
        XCTAssertNil(LocHintCodec.bucketFor(unknown.accuracyM))
    }

    func testLatestFixHoldsTheFixForAnotherThread() {
        let box = LatestFix()
        XCTAssertNil(box.relayFix(nowMs: now))
        box.set(fix(20))
        XCTAssertEqual(20, box.relayFix(nowMs: now)?.accuracyM)
        XCTAssertNil(box.relayFix(nowMs: now + FixPolicy.maxAgeMs + 1))
        box.set(nil)
        XCTAssertNil(box.get())
    }
}
