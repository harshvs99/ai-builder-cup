import XCTest
import RelayCore
@testable import RelayKit

/// Shelter list rules (contract v2 section 4). Mirror of Kotlin `ShelterTest`.
final class ShelterTests: TempDirTestCase {
    private func sh(_ id: String, _ name: String, _ lat: Double, _ lon: Double, demo: Bool = false) -> ReadyShelter {
        ReadyShelter(id: id, name: name, nameEn: name, kind: "school", lat: lat, lon: lon, capacity: 100, facilities: [], block: nil, demo: demo)
    }

    private func pack(_ source: String, _ s: [ReadyShelter]) -> ReadyPack {
        ReadyPack(district: "puri", lang: "en", generatedAt: 1, version: "v", warning: nil, shelters: s, sheltersSource: source,
                  sheltersPublishedAt: nil, checklist: [], checklistLang: "en")
    }

    private var near: ReadyShelter { sh("n", "Near School", 19.812, 85.828) }
    private var far: ReadyShelter { sh("f", "Far Camp", 19.9, 85.9) }

    func testProductionBuildsShowOnlyAPublishedList() {
        let published = pack("published", [near, far])
        XCTAssertEqual(2, ShelterLogic.visible(published, demoBuild: false).count)
        XCTAssertEqual(2, ShelterLogic.visible(published, demoBuild: true).count)
        let demo = pack("demo", [sh("n", "Near School", 19.812, 85.828, demo: true), sh("f", "Far Camp", 19.9, 85.9, demo: true)])
        XCTAssertTrue(ShelterLogic.visible(demo, demoBuild: false).isEmpty)
        XCTAssertEqual(2, ShelterLogic.visible(demo, demoBuild: true).count)
        XCTAssertTrue(ShelterLogic.visible(nil, demoBuild: true).isEmpty) // no pack, nothing invented
        // a demo-flagged entry inside a published list is not shown in a production build
        XCTAssertEqual(["f"], ShelterLogic.visible(pack("published", [sh("n", "Near School", 19.812, 85.828, demo: true), far]), demoBuild: false).map(\.id))
    }

    func testDemoRowsGetTheBadge() {
        XCTAssertTrue(ShelterLogic.isDemo(pack("demo", [near]), near))
        XCTAssertTrue(ShelterLogic.isDemo(pack("published", [near]), sh("n", "Near School", 19.812, 85.828, demo: true)))
        XCTAssertFalse(ShelterLogic.isDemo(pack("published", [near]), near))
    }

    func testListIsNearestFirstWhenThePhoneKnowsWhereItIsElseByName() {
        let here = GeoPoint(lat: 19.81, lon: 85.83)
        let rows = ShelterLogic.sorted([far, near], from: here)
        XCTAssertEqual(["n", "f"], rows.map { $0.shelter.id })
        XCTAssertLessThan(rows[0].distanceKm ?? 99, 1.0); XCTAssertGreaterThan(rows[1].distanceKm ?? 0, 10.0)
        let byName = ShelterLogic.sorted([near, far], from: nil)
        XCTAssertEqual(["f", "n"], byName.map { $0.shelter.id }) // "Far Camp" < "Near School"
        XCTAssertTrue(byName.allSatisfy { $0.distanceKm == nil })
    }

    func testDistanceIsGreatCircleKilometres() {
        XCTAssertEqual(0.0, ShelterLogic.distanceKm(19.8, 85.8, 19.8, 85.8), accuracy: 1e-9)
        XCTAssertEqual(111.19, ShelterLogic.distanceKm(0, 0, 1, 0), accuracy: 0.05)
        XCTAssertEqual(1311.5, ShelterLogic.distanceKm(19.81, 85.83, 28.614, 77.209), accuracy: 5.0) // Puri to Delhi
    }

    func testCoordinatesAreBuiltFromTheShelter() {
        let s = sh("n", "Zilla School (Puri)", 19.812, 85.828)
        XCTAssertEqual("19.81200, 85.82800", ShelterLogic.coordinatesText(s))
        let row = ShelterLogic.sorted([near], from: nil)[0]
        XCTAssertEqual("Near School, 1.2 km", ShelterLogic.spokenLine(ShelterRow(shelter: row.shelter, distanceKm: 1.2)) { String(format: "%.1f km", $0) })
        XCTAssertEqual("Near School", ShelterLogic.spokenLine(row) { _ in "x" })
    }

    func testMiniMapPinsFitInsideTheMarginAndKeepTheirRelativePositions() {
        XCTAssertTrue(ShelterLogic.pins([], selectedId: nil).isEmpty)
        let one = ShelterLogic.pins([near], selectedId: "n")[0]
        XCTAssertEqual(0.5, one.x, accuracy: 1e-9); XCTAssertEqual(0.5, one.y, accuracy: 1e-9); XCTAssertTrue(one.selected)
        let pins = ShelterLogic.pins([near, far], selectedId: "f")
        XCTAssertTrue(pins.allSatisfy { (0.11...0.89).contains($0.x) && (0.11...0.89).contains($0.y) })
        XCTAssertGreaterThan(pins[1].x, pins[0].x) // further east is further right
        XCTAssertLessThan(pins[1].y, pins[0].y)    // further north is higher up
        XCTAssertFalse(pins[0].selected); XCTAssertTrue(pins[1].selected)
    }

    // ------------------------------------------------------------ the public list
    private let publicJson = """
    {"district":"puri","published_at":1792300000,"shelters":[
      {"id":"a","name":"Zilla School","name_local":"ଜିଲ୍ଲା ସ୍କୁଲ","kind":"school","address":"Station Rd","lat":19.807,"lon":85.835,"capacity":600,"source_url":"https://x"},
      {"id":"b","name":"No position","kind":"school","lat":null,"lon":null},
      {"id":"c","name":"Hospital","kind":"hospital","lat":19.818,"lon":85.824}],"source_note":"Verified"}
    """

    func testThePublicListBecomesAPackWithoutAWarningAndSkipsEntriesWithoutAPosition() throws {
        let got = try XCTUnwrap(PublicShelters.toPack(publicJson, nowSec: 1792340000))
        let p = got.pack
        XCTAssertEqual("puri", p.district); XCTAssertNil(p.warning); XCTAssertTrue(p.isPublished); XCTAssertEqual(1792300000, p.sheltersPublishedAt)
        XCTAssertEqual(["a", "c"], p.shelters.map(\.id))
        XCTAssertEqual("ଜିଲ୍ଲା ସ୍କୁଲ", p.shelters[0].name); XCTAssertEqual("Zilla School", p.shelters[0].nameEn); XCTAssertEqual("Station Rd", p.shelters[0].block)
        XCTAssertEqual(600, p.shelters[0].capacity); XCTAssertNil(p.shelters[1].capacity)
        XCTAssertEqual("public-1792300000", p.version)
        XCTAssertNotNil(ReadyJson.parsePack(got.raw))
        XCTAssertNil(PublicShelters.toPack(nil, nowSec: 0)); XCTAssertNil(PublicShelters.toPack("garbage", nowSec: 0))
        XCTAssertNil(PublicShelters.toPack("{\"district\":\"puri\",\"published_at\":1,\"shelters\":[]}", nowSec: 0))
    }

    private final class Api: ReadyApi, @unchecked Sendable {
        var publicBody: String?
        var asked: [String] = []
        init(_ body: String?) { publicBody = body }
        func pack(_ query: PackQuery, lang: String) throws -> String { fatalError("not used") }
        func plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household) throws -> String { fatalError("not used") }
        func audio(_ audioUrl: String) throws -> [UInt8]? { nil }
        func publicShelters(_ district: String) throws -> String? { asked.append(district); return publicBody }
    }

    private let input = RefreshInput(connected: true, lat: nil, lon: nil, lang: "en", household: Household())

    private func freshStore() -> ReadyStore {
        let dir = tmp.appendingPathComponent(UUID().uuidString)
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return ReadyStore(dir: dir)
    }

    func testAPhoneWithSignalAndADistrictButNoPackStoresThePublishedList() throws {
        let store = freshStore()
        let api = Api(publicJson)
        let r = ReadyRefresher(api: api, store: store, nowMs: { 1_792_340_000_000 })
        XCTAssertFalse(r.refreshPublicShelters(input)) // no district known yet
        try store.setDistrict("puri")
        var offline = input; offline.connected = false
        XCTAssertFalse(r.refreshPublicShelters(offline))
        XCTAssertTrue(r.refreshPublicShelters(input))
        XCTAssertEqual(["puri"], api.asked)
        let snap = store.load()
        XCTAssertEqual(2, snap.pack?.shelters.count); XCTAssertEqual(0, snap.lastPackFetchMs) // not counted as a pack fetch
        XCTAssertFalse(r.refreshPublicShelters(input)) // a pack is stored now: nothing to do
    }

    func testNothingPublishedYetStoresNothing() throws {
        let store = freshStore(); try store.setDistrict("puri")
        XCTAssertFalse(ReadyRefresher(api: Api(nil), store: store).refreshPublicShelters(input))
        XCTAssertNil(store.load().pack)
    }
}
