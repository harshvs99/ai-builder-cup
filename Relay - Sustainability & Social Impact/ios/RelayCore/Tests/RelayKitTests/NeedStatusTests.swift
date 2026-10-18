import XCTest
import RelayCore
@testable import RelayKit

final class NeedStatusTests: XCTestCase {
    private func need(sent: Bool = false) -> MyNeed {
        MyNeed(msgIdHex: "aa", createdAtMs: 0, category: .rescue, urgency: 3, people: 4, item: "rescue", landmark: "temple",
               summary: "s", lang: .en, needPayloadB64: "", sentToMesh: sent)
    }

    private func derive(_ n: MyNeed, _ gw: Bool, _ stored: Bool, _ up: Bool) -> NeedStatus {
        NeedStatusLogic.derive(n, isGateway: gw, storedLocally: stored, uploaded: up)
    }

    func testNonGatewayFollowsMeshStage() {
        XCTAssertEqual(.queued, derive(need(), false, true, false))
        XCTAssertEqual(.sent, derive(need(sent: true), false, true, false))
    }

    func testGatewayWithLocalNeedIsWaitingForUploadEvenIfSentToMesh() {
        XCTAssertEqual(.waitingUpload, derive(need(), true, true, false))
        XCTAssertEqual(.waitingUpload, derive(need(sent: true), true, true, false))
    }

    func testGatewayNeedNotYetOriginatedStaysQueued() {
        XCTAssertEqual(.queued, derive(need(), true, false, false))
    }

    func testUploadedWaitsForConfirmation() {
        XCTAssertEqual(.uploaded, derive(need(), true, true, true))
        XCTAssertEqual(.uploaded, derive(need(sent: true), false, true, true)) // gateway role lost after uploading
    }

    func testAcksOverrideEverything() {
        let received = need().withAck(.received, ticket: 12, text: "got it", eta: 0xFFFF)
        XCTAssertEqual(.received, derive(received, true, true, false))
        XCTAssertEqual(.received, derive(received, true, true, true))
        let dispatched = received.withAck(.dispatched, ticket: 12, text: "on the way", eta: 15)
        XCTAssertEqual(.dispatched, derive(dispatched, true, true, true))
    }
}
