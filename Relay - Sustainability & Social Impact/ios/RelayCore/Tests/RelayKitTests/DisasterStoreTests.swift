import XCTest
import RelayCore
@testable import RelayKit

/// Port of the Android `DisasterStoreTest.kt` plus the broadcast-to-store path.
final class DisasterStoreTests: XCTestCase {
    private let now: Int64 = 1_800_000_000
    private func d(_ t: DisasterType, _ ageH: Int64, _ area: DisasterContext.Area? = nil) -> DisasterContext {
        DisasterContext(type: t, issuedAtSec: now - ageH * 3600, area: area)
    }

    func testTheNewestDeclarationPerTypeWins() {
        let old = d(.flood, 5), newer = d(.flood, 1)
        XCTAssertEqual([newer], DisasterStore.withDeclaration([old], newer, nowSec: now))
        XCTAssertEqual([newer], DisasterStore.withDeclaration([newer], old, nowSec: now)) // a late-arriving older one changes nothing
    }

    func testDifferentDisastersCanBeActiveTogether() {
        let l = DisasterStore.withDeclaration([d(.flood, 2)], d(.landslide, 1), nowSec: now)
        XCTAssertEqual([.flood, .landslide], Set(l.map(\.type)))
    }

    func testAnythingPastTwentyFourHoursIsDroppedOnWrite() {
        let l = DisasterStore.withDeclaration([d(.cyclone, 23)], d(.flood, 1), nowSec: now + 3 * 3600)
        XCTAssertEqual([.flood], l.map(\.type))
    }

    func testClearedEndsEarlierDeclarationsOfThatTypeOnly() {
        let l = [d(.flood, 6), d(.cyclone, 6)]
        XCTAssertEqual([.cyclone], DisasterStore.afterCleared(l, .flood, clearedAtSec: now - 3600).map(\.type))
        // a declaration issued after the "cleared" survives it
        XCTAssertEqual(1, DisasterStore.afterCleared([d(.flood, 0)], .flood, clearedAtSec: now - 3600).count)
    }

    func testEncodingKeepsTheAreaAndRejectsGarbage() {
        let l = [d(.flood, 1, .init(latE5: 2_029_612, lonE5: 8_582_451, radiusKm: 40)), d(.cyclone, 2)]
        XCTAssertEqual(l, DisasterStore.decode(DisasterStore.encode(l)))
        XCTAssertTrue(DisasterStore.decode("flood:1:2:3").isEmpty) // wrong field count
        XCTAssertTrue(DisasterStore.decode("").isEmpty)
        XCTAssertTrue(DisasterStore.decode("garbage,flood:x,volcano:5").isEmpty)
    }

    func testBroadcastsDeclareAndClearAndPersist() {
        let kv = InMemoryKeyValueStore()
        let store = DisasterStore(store: kv)
        let id = [UInt8](repeating: 1, count: 8)
        store.apply(Broadcast(id: id, kind: .disaster, disaster: .flood, issuedAt: now - 3600, latE5: 2_029_612, lonE5: 8_582_451, radiusKm: 40), nowSec: now)
        XCTAssertEqual([.flood], store.active(nowSec: now).types)
        XCTAssertEqual(40, store.all().first?.area?.radiusKm)
        // survives a restart: a new store over the same key-value storage
        XCTAssertEqual([.flood], DisasterStore(store: kv).active(nowSec: now).types)
        XCTAssertEqual(["flood": 1], store.agesHours(nowSec: now))
        // an expired broadcast changes nothing
        store.apply(Broadcast(id: id, kind: .disaster, disaster: .cyclone, issuedAt: now - 25 * 3600), nowSec: now)
        XCTAssertEqual([.flood], store.active(nowSec: now).types)
        // cleared ends it, applying it twice is the same
        let clear = Broadcast(id: [UInt8](repeating: 2, count: 8), kind: .disasterCleared, disaster: .flood, issuedAt: now - 60)
        store.apply(clear, nowSec: now); store.apply(clear, nowSec: now)
        XCTAssertTrue(store.active(nowSec: now).types.isEmpty)
        store.apply(Broadcast(id: id, kind: .disaster, disaster: .flood, issuedAt: now), nowSec: now)
        store.clear()
        XCTAssertTrue(store.all().isEmpty)
    }
}
