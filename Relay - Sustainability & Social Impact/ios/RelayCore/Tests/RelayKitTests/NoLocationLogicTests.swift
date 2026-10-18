import XCTest
import RelayCore
@testable import RelayKit

final class NoLocationLogicTests: XCTestCase {
    private func prompt(_ perm: Bool, _ services: Bool, fix: Bool, waiting: Bool, empty: Bool) -> NoLocationPrompt? {
        NoLocationLogic.promptOnSend(permissionGranted: perm, servicesEnabled: services, fixPresent: fix, waiting: waiting, landmarkEmpty: empty)
    }

    func testReasonFollowsPermissionThenServicesThenFinding() {
        XCTAssertNil(NoLocationLogic.reason(permissionGranted: true, servicesEnabled: true, fixPresent: true))
        XCTAssertNil(NoLocationLogic.reason(permissionGranted: false, servicesEnabled: false, fixPresent: true))
        XCTAssertEqual(.permissionOff, NoLocationLogic.reason(permissionGranted: false, servicesEnabled: true, fixPresent: false))
        XCTAssertEqual(.permissionOff, NoLocationLogic.reason(permissionGranted: false, servicesEnabled: false, fixPresent: false))
        XCTAssertEqual(.servicesOff, NoLocationLogic.reason(permissionGranted: true, servicesEnabled: false, fixPresent: false))
        XCTAssertEqual(.finding, NoLocationLogic.reason(permissionGranted: true, servicesEnabled: true, fixPresent: false))
    }

    func testReasonForLocUi() {
        XCTAssertNil(NoLocationLogic.reason(for: .ready))
        XCTAssertEqual(.permissionOff, NoLocationLogic.reason(for: .noPermission))
        XCTAssertEqual(.servicesOff, NoLocationLogic.reason(for: .off))
        XCTAssertEqual(.finding, NoLocationLogic.reason(for: .acquiring))
    }

    func testNoDialogWithFixOrWhileWaiting() {
        XCTAssertNil(prompt(true, true, fix: true, waiting: false, empty: true))
        XCTAssertNil(prompt(true, true, fix: false, waiting: true, empty: true))
        XCTAssertNil(prompt(false, false, fix: true, waiting: true, empty: false))
    }

    func testPermissionMissingRequestsPermission() throws {
        let p = try XCTUnwrap(prompt(false, true, fix: false, waiting: false, empty: false))
        XCTAssertEqual(.permissionOff, p.reason)
        XCTAssertEqual(.requestPermission, p.turnOn)
    }

    func testServicesOffOpensLocationSettings() throws {
        XCTAssertEqual(.openLocationSettings, try XCTUnwrap(prompt(true, false, fix: false, waiting: false, empty: true)).turnOn)
    }

    func testPermissionAndServicesButNoFixRetries() throws {
        XCTAssertEqual(.retryFix, try XCTUnwrap(prompt(true, true, fix: false, waiting: false, empty: true)).turnOn)
    }

    func testLandmarkDecidesBodyLabelAndConfirmation() throws {
        let withLandmark = try XCTUnwrap(prompt(true, false, fix: false, waiting: false, empty: false))
        XCTAssertTrue(withLandmark.bodyWithLandmark)
        XCTAssertFalse(withLandmark.addLandmark)
        XCTAssertFalse(withLandmark.confirmSendAnyway)
        let without = try XCTUnwrap(prompt(true, false, fix: false, waiting: false, empty: true))
        XCTAssertFalse(without.bodyWithLandmark)
        XCTAssertTrue(without.addLandmark)
        XCTAssertTrue(without.confirmSendAnyway)
    }

    func testBodySpeechAndReasonText() throws {
        let en = UI.strings(.en).ask
        let p = try XCTUnwrap(prompt(true, false, fix: false, waiting: false, empty: false))
        XCTAssertEqual("They will rely on 'school'. Adding your location helps them find you faster.", NoLocationLogic.body(en, p, landmark: "  school "))
        XCTAssertEqual("Rescuers can't see where you are. They will rely on 'school'. Adding your location helps them find you faster.",
                       NoLocationLogic.speech(en, p, landmark: "school"))
        let q = try XCTUnwrap(prompt(true, false, fix: false, waiting: false, empty: true))
        XCTAssertEqual(en.noLocationBodyNoLandmark, NoLocationLogic.body(en, q, landmark: ""))
        XCTAssertEqual("Location permission is off", NoLocationLogic.reasonText(en, .permissionOff))
    }

    func testEveryNoLocationStringExistsInAllFiveLanguages() {
        let en = UI.strings(.en).ask
        XCTAssertEqual("Rescuers can't see where you are", en.noLocationTitle)
        XCTAssertEqual("Still finding your location…", en.whereReasonFinding)
        for l in [Lang.hi, .or, .tl, .war] {
            let a = UI.strings(l).ask
            let texts = [
                a.noLocationTitle, a.noLocationBodyNoLandmark, a.noLocationBodyWithLandmark("x"), a.turnOnLocation, a.addLandmark,
                a.changeLandmark, a.sendWithoutLocation, a.sendAnywayConfirm, a.whereReasonPermissionOff, a.whereReasonServicesOff,
                a.whereReasonFinding, a.noLocationWarningA11y,
            ]
            for t in texts { XCTAssertFalse(t.trimmed.isEmpty) }
            XCTAssertNotEqual(en.noLocationTitle, a.noLocationTitle)
            XCTAssertTrue(a.noLocationBodyWithLandmark("ZQX").contains("ZQX"), "landmark placeholder survives in \(l)")
        }
    }
}
