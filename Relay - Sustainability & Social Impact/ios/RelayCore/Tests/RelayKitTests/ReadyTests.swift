import XCTest
import RelayCore
@testable import RelayKit

/// Ready mode on the phone (contract v2 section 3.3): parsing, atomic storage, refresh decisions. Mirror of Kotlin `ReadyTest`.
final class ReadyTests: TempDirTestCase {
    private func packJson(version: String = "v1", warningId: Int64? = 4, source: String = "published", extra: String = "", district: String = "puri") -> String {
        let warning = warningId.map { "{\"id\":\($0),\"source\":\"IMD\",\"severity\":\"warning\",\"headline\":\"Cyclone warning\",\"issued_at\":1792330000,\"expected_landfall_at\":1792400000,\"expires_at\":null}" } ?? "null"
        return """
        {"district":"\(district)","lang":"hi","generated_at":1792336961,"version":"\(version)","warning":\(warning),
        "shelters":[{"id":"s1","name":"Zilla School","name_en":"Zilla School","kind":"school","lat":19.807,"lon":85.835,"capacity":600,"facilities":["water","toilets"],"block":"Puri Sadar","demo":false},
                    {"id":"s2","name":"Cyclone Shelter 4","kind":"cyclone_shelter","lat":19.812,"lon":85.828}],
        "shelters_source":"\(source)","shelters_published_at":1792300000,"checklist":["Pack a bag","Charge the phone"],"checklist_lang":"en"\(extra)}
        """
    }

    private func planJson(_ planId: String = "plan1", warningId: Int64? = 4, audio: Bool = true, extra: String = "") -> String {
        """
        {"plan_id":"\(planId)","lang":"hi","steps_lang":"en","district":"puri","steps":["Go to Zilla School","Pack a bag"],"source":"template",
        "warning_id":\(warningId.map { String($0) } ?? "null"),"nearest_shelters":[{"id":"s1","name":"Zilla School","distance_km":1.2}],
        "audio_url":\(audio ? "\"/v1/ready/plan/\(planId)/audio\"" : "null")\(extra)}
        """
    }

    private final class FakeApi: ReadyApi, @unchecked Sendable {
        var pack = ""
        var plan = ""
        var wav: [UInt8]? = [1, 2, 3]
        var failPack: ReadyApiError?
        var failAudio: ReadyApiError?
        var packQueries: [PackQuery] = []
        var planCalls: [(lang: String, lat: Double?, district: String?)] = []
        var audioCalls = 0
        func pack(_ query: PackQuery, lang: String) throws -> String { packQueries.append(query); if let f = failPack { throw f }; return pack }
        func plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household) throws -> String {
            planCalls.append((lang, lat, district)); return plan
        }
        func audio(_ audioUrl: String) throws -> [UInt8]? { audioCalls += 1; if let f = failAudio { throw f }; return wav }
    }

    private final class Clk: @unchecked Sendable { var now: Int64 = 1_792_340_000_000 }
    private let clk = Clk()
    private let here = RefreshInput(connected: true, lat: 19.8, lon: 85.8, lang: "hi", household: Household(people: 4))
    private let h: Int64 = 3_600_000

    private func fixture(_ api: FakeApi? = nil) -> (FakeApi, ReadyStore, ReadyRefresher) {
        let a = api ?? { let f = FakeApi(); f.pack = packJson(); f.plan = planJson(); return f }()
        let dir = tmp.appendingPathComponent(UUID().uuidString)
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = ReadyStore(dir: dir)
        let c = clk
        return (a, store, ReadyRefresher(api: a, store: store, nowMs: { c.now }))
    }

    // ------------------------------------------------------------ parsing
    func testPackParsingIgnoresUnknownFieldsAndKeepsWhatItNeeds() throws {
        let p = try XCTUnwrap(ReadyJson.parsePack(packJson(extra: ",\"future_field\":{\"a\":1},\"tiles_url\":\"https://x\"")))
        XCTAssertEqual("puri", p.district); XCTAssertEqual("v1", p.version); XCTAssertEqual(1792336961, p.generatedAt)
        XCTAssertEqual(4, p.warning?.id); XCTAssertEqual("IMD", p.warning?.source); XCTAssertEqual(1792400000, p.warning?.expectedLandfallAt)
        XCTAssertNil(p.warning?.expiresAt)
        XCTAssertEqual(2, p.shelters.count)
        XCTAssertEqual(["water", "toilets"], p.shelters[0].facilities)
        XCTAssertEqual(600, p.shelters[0].capacity); XCTAssertNil(p.shelters[1].capacity)
        XCTAssertEqual("Cyclone Shelter 4", p.shelters[1].nameEn) // name_en missing: falls back to name
        XCTAssertTrue(p.isPublished); XCTAssertEqual(1792300000, p.sheltersPublishedAt)
        XCTAssertEqual(["Pack a bag", "Charge the phone"], p.checklist)
        XCTAssertNil(ReadyJson.parsePack(packJson(warningId: nil))?.warning)
    }

    func testPackWithoutTheFieldsTheScreensNeedIsRefused() {
        XCTAssertNil(ReadyJson.parsePack(nil)); XCTAssertNil(ReadyJson.parsePack("")); XCTAssertNil(ReadyJson.parsePack("not json"))
        XCTAssertNil(ReadyJson.parsePack("{\"district\":\"puri\",\"version\":\"v\"}")) // no shelters list
        XCTAssertNil(ReadyJson.parsePack("{\"district\":\"puri\",\"shelters\":[]}")) // no version
        // a shelter without coordinates is skipped, not fatal
        let p = ReadyJson.parsePack("{\"district\":\"puri\",\"version\":\"v\",\"shelters\":[{\"id\":\"a\",\"name\":\"A\"},{\"id\":\"b\",\"name\":\"B\",\"lat\":1.0,\"lon\":2.0}]}")
        XCTAssertEqual(["b"], p?.shelters.map { $0.id })
    }

    func testPlanParsingIgnoresUnknownFields() throws {
        let p = try XCTUnwrap(ReadyJson.parsePlan(planJson(extra: ",\"future\":[1,2]")))
        XCTAssertEqual("plan1", p.planId); XCTAssertEqual("hi", p.lang); XCTAssertEqual("en", p.stepsLang)
        XCTAssertEqual(["Go to Zilla School", "Pack a bag"], p.steps)
        XCTAssertEqual(4, p.warningId); XCTAssertEqual("/v1/ready/plan/plan1/audio", p.audioUrl)
        XCTAssertEqual("Zilla School", p.nearestShelters.first?.name); XCTAssertEqual(1.2, p.nearestShelters.first?.distanceKm ?? 0, accuracy: 1e-9)
        XCTAssertNil(ReadyJson.parsePlan(planJson(audio: false))?.audioUrl)
        XCTAssertNil(ReadyJson.parsePlan("{\"plan_id\":\"x\",\"steps\":[]}")); XCTAssertNil(ReadyJson.parsePlan("{\"steps\":[\"a\"]}"))
    }

    func testHouseholdIsLimitedAndKeyedByContent() {
        XCTAssertEqual(20, Household(people: 99).normalized().people); XCTAssertEqual(1, Household(people: 0).normalized().people)
        XCTAssertEqual(String(repeating: "a", count: 100), Household(medicalNeeds: "  " + String(repeating: "a", count: 150)).normalized().medicalNeeds)
        XCTAssertEqual(Household(people: 4, elderly: true).key, Household(people: 4, elderly: true, medicalNeeds: "  ").key)
        XCTAssertNotEqual(Household(people: 4).key, Household(people: 5).key)
        XCTAssertNotEqual(Household().key, Household(livestock: true).key)
        XCTAssertEqual(Household(medicalNeeds: "Insulin").key, Household(medicalNeeds: "insulin").key)
        let j = Household(people: 3, children: true, medicalNeeds: "oxygen").jsonObject()
        XCTAssertEqual(3, j["people"] as? Int); XCTAssertEqual(true, j["children"] as? Bool); XCTAssertEqual("oxygen", j["medical_needs"] as? String)
        XCTAssertEqual(Household(people: 3, children: true, medicalNeeds: "oxygen"), Household.fromJson(Household(people: 3, children: true, medicalNeeds: "oxygen").toJson()))
        XCTAssertEqual(Household(), Household.fromJson("garbage"))
    }

    // ------------------------------------------------------------ decisions
    func testRefreshDoesNothingWithoutConnectivity() {
        let (api, store, r) = fixture()
        var offline = here; offline.connected = false
        let res = r.refresh(.appOpen, offline)
        XCTAssertTrue(res.skipped); XCTAssertTrue(api.packQueries.isEmpty); XCTAssertNil(store.load().pack)
        XCTAssertFalse(ReadyPolicy.shouldFetchPack(.manual, connected: false, lastFetchMs: 0, nowMs: clk.now))
    }

    func testAppOpenAndNetworkRegainedFetchAtOnceButNotTwiceInAMinute() {
        let now = clk.now
        XCTAssertTrue(ReadyPolicy.shouldFetchPack(.appOpen, connected: true, lastFetchMs: 0, nowMs: now))
        XCTAssertTrue(ReadyPolicy.shouldFetchPack(.networkRegained, connected: true, lastFetchMs: now - 61_000, nowMs: now))
        XCTAssertFalse(ReadyPolicy.shouldFetchPack(.appOpen, connected: true, lastFetchMs: now - 30_000, nowMs: now))
        XCTAssertFalse(ReadyPolicy.shouldFetchPack(.networkRegained, connected: true, lastFetchMs: now - 30_000, nowMs: now))
        XCTAssertTrue(ReadyPolicy.shouldFetchPack(.appOpen, connected: true, lastFetchMs: now - h, nowMs: now))
        XCTAssertTrue(ReadyPolicy.shouldFetchPack(.appOpen, connected: true, lastFetchMs: now + 5 * h, nowMs: now)) // the clock went backwards: do not wait for ever
    }

    func testPeriodicRefreshWaitsThreeHours() {
        let now = clk.now
        XCTAssertTrue(ReadyPolicy.shouldFetchPack(.periodic, connected: true, lastFetchMs: 0, nowMs: now))
        XCTAssertFalse(ReadyPolicy.shouldFetchPack(.periodic, connected: true, lastFetchMs: now - 2 * h, nowMs: now))
        XCTAssertFalse(ReadyPolicy.shouldFetchPack(.background, connected: true, lastFetchMs: now - 3 * h + 1, nowMs: now))
        XCTAssertTrue(ReadyPolicy.shouldFetchPack(.periodic, connected: true, lastFetchMs: now - 3 * h, nowMs: now))
        XCTAssertTrue(ReadyPolicy.shouldFetchPack(.manual, connected: true, lastFetchMs: now - 1000, nowMs: now))
    }

    func testAWarningFetchesPackThenPlanThenAudio() throws {
        let (api, store, r) = fixture()
        let res = r.refresh(.appOpen, here)
        XCTAssertTrue(res.packFetched && res.planFetched && res.audioFetched); XCTAssertNil(res.error)
        XCTAssertEqual([PackQuery.at(lat: 19.8, lon: 85.8)], api.packQueries)
        let s = store.load()
        XCTAssertEqual("v1", s.pack?.version); XCTAssertEqual("plan1", s.plan?.planId)
        XCTAssertEqual(PlanKey(packVersion: "v1", householdKey: here.household.key, lang: "hi", warningId: 4), s.planKey)
        XCTAssertEqual([1, 2, 3], try Data(contentsOf: XCTUnwrap(s.audio)).map { $0 })
        XCTAssertEqual("puri", s.district); XCTAssertEqual(clk.now, s.lastPackFetchMs)
        XCTAssertEqual(s.plan, s.currentPlan)
    }

    func testNoWarningMeansNoPlanAndTheOldPlanIsNotShown() {
        let (api, store, r) = fixture()
        _ = r.refresh(.appOpen, here)
        clk.now += 4 * h
        api.pack = packJson(version: "v2", warningId: nil)
        let res = r.refresh(.periodic, here)
        XCTAssertTrue(res.packFetched); XCTAssertFalse(res.planFetched); XCTAssertEqual(1, api.planCalls.count)
        XCTAssertNotNil(store.load().plan); XCTAssertNil(store.load().currentPlan) // kept on disk, but it answers a warning that is over
    }

    func testPlanIsFetchedAgainOnlyWhenVersionHouseholdLanguageOrWarningChange() {
        let (api, store, r) = fixture()
        _ = r.refresh(.appOpen, here); XCTAssertEqual(1, api.planCalls.count)
        clk.now += 4 * h
        _ = r.refresh(.periodic, here)                                                       // same everything
        XCTAssertEqual(1, api.planCalls.count)
        clk.now += 4 * h
        var five = here; five.household = Household(people: 5)
        _ = r.refresh(.periodic, five)                                                       // household changed
        XCTAssertEqual(2, api.planCalls.count)
        clk.now += 4 * h
        var en = five; en.lang = "en"
        _ = r.refresh(.periodic, en)                                                         // language changed
        XCTAssertEqual(3, api.planCalls.count)
        clk.now += 4 * h
        api.pack = packJson(version: "v2")
        _ = r.refresh(.periodic, en)                                                         // pack version changed
        XCTAssertEqual(4, api.planCalls.count)
        XCTAssertEqual("v2", store.load().planKey?.packVersion)
    }

    func testAudioIsFetchedOnlyWhenOfferedAndNotAlreadyStored() {
        let (api, _, r) = fixture()
        _ = r.refresh(.appOpen, here); XCTAssertEqual(1, api.audioCalls)
        clk.now += 4 * h
        _ = r.refresh(.periodic, here); XCTAssertEqual(1, api.audioCalls) // stored already
        let noAudio = FakeApi(); noAudio.pack = packJson(); noAudio.plan = planJson(audio: false)
        let none = fixture(noAudio)
        _ = none.2.refresh(.appOpen, here); XCTAssertEqual(0, none.0.audioCalls)
        let miss = FakeApi(); miss.pack = packJson(); miss.plan = planJson(); miss.wav = nil // 404 / 503: on-device speech
        let missing = fixture(miss)
        let res = missing.2.refresh(.appOpen, here)
        XCTAssertTrue(res.packFetched && res.planFetched); XCTAssertFalse(res.audioFetched); XCTAssertNil(res.error); XCTAssertNil(missing.1.load().audio)
    }

    func testANewWarningNotifiesOncePerWarningId() throws {
        let (api, store, r) = fixture()
        let first = r.refresh(.appOpen, here)
        XCTAssertEqual(4, first.newWarning?.id)
        r.noteNotified(4)
        clk.now += 4 * h
        XCTAssertNil(r.refresh(.periodic, here).newWarning)              // same warning, new fetch
        clk.now += 4 * h
        api.pack = packJson(version: "v2", warningId: 5)
        XCTAssertEqual(5, r.refresh(.periodic, here).newWarning?.id)      // a different warning id
        XCTAssertEqual(4, store.load().notifiedWarningId)                 // until the app says it told the person
        XCTAssertFalse(ReadyPolicy.shouldNotify(try XCTUnwrap(ReadyJson.parsePack(packJson(warningId: nil))), notifiedWarningId: nil))
    }

    func testNoPositionAndNoDistrictAsksForOneThenUsesIt() throws {
        let (api, store, r) = fixture()
        var blind = here; blind.lat = nil; blind.lon = nil
        let res = r.refresh(.appOpen, blind)
        XCTAssertTrue(res.skipped && res.needsDistrict); XCTAssertTrue(api.packQueries.isEmpty)
        try store.setDistrict("delhi")
        XCTAssertTrue(r.refresh(.appOpen, blind).packFetched)
        XCTAssertEqual([PackQuery.district("delhi")], api.packQueries)
        XCTAssertEqual(1, api.planCalls.count)
        XCTAssertEqual("hi", api.planCalls[0].lang); XCTAssertNil(api.planCalls[0].lat); XCTAssertEqual("puri", api.planCalls[0].district) // the plan names the pack's district when there is no position
    }

    func testTheDistrictIsLearnedFromThePack() {
        let api = FakeApi(); api.pack = packJson(district: "delhi"); api.plan = planJson()
        let (_, store, r) = fixture(api)
        _ = r.refresh(.appOpen, here)
        XCTAssertEqual("delhi", store.load().district)
    }

    func testAFailingBackendKeepsTheStoredCopyAndIsNotRetriedInATightLoop() {
        let (api, store, r) = fixture()
        _ = r.refresh(.appOpen, here)
        clk.now += 4 * h
        api.failPack = ReadyApiError("HTTP 503", status: 503)
        let res = r.refresh(.periodic, here)
        XCTAssertEqual("HTTP 503", res.error); XCTAssertFalse(res.packFetched)
        XCTAssertEqual("v1", store.load().pack?.version)
        XCTAssertTrue(r.refresh(.appOpen, here).skipped) // the attempt counts: not again within the minute
        api.pack = "garbage"; api.failPack = nil; clk.now += 4 * h
        XCTAssertEqual("unreadable pack", r.refresh(.periodic, here).error)
        XCTAssertEqual("v1", store.load().pack?.version)
    }

    func testAFailingPlanOrAudioStillLeavesTheNewPackStored() {
        let api = FakeApi(); api.pack = packJson(); api.plan = "garbage"
        let (_, store, r) = fixture(api)
        let res = r.refresh(.appOpen, here)
        XCTAssertTrue(res.packFetched); XCTAssertFalse(res.planFetched); XCTAssertEqual("unreadable plan", res.error)
        XCTAssertEqual("v1", store.load().pack?.version)
        api.plan = planJson(); api.failAudio = ReadyApiError("timeout")
        clk.now += 4 * h
        let res2 = r.refresh(.periodic, here)
        XCTAssertTrue(res2.planFetched); XCTAssertFalse(res2.audioFetched); XCTAssertEqual("timeout", res2.error)
        XCTAssertNotNil(store.load().plan)
    }

    // ------------------------------------------------------------ storage
    func testStorageKeepsThePreviousCopyUntilTheNewOneIsFullyWritten() throws {
        let dir = tmp.appendingPathComponent("crash"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let raw1 = packJson(version: "v1"), raw2 = packJson(version: "v2")
        try ReadyStore(dir: dir).savePack(raw1, try XCTUnwrap(ReadyJson.parsePack(raw1)), nowMs: 1)
        // crash after the new data file is written but before the pointer names it
        let crashing = ReadyStore(dir: dir) { stage in if stage == "pack" { throw ReadyStore.Crash() } }
        XCTAssertThrowsError(try crashing.savePack(raw2, try XCTUnwrap(ReadyJson.parsePack(raw2)), nowMs: 2))
        XCTAssertEqual("v1", ReadyStore(dir: dir).load().pack?.version)
        XCTAssertEqual(1, ReadyStore(dir: dir).load().lastPackFetchMs)
        // the next successful save prunes the orphan
        try ReadyStore(dir: dir).savePack(raw2, try XCTUnwrap(ReadyJson.parsePack(raw2)), nowMs: 3)
        XCTAssertEqual("v2", ReadyStore(dir: dir).load().pack?.version)
        XCTAssertEqual(["pack-v2.json", "ready_state.json"], try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted())
    }

    func testStorageIsKeyedByVersionAndPlanIdAndDropsWhatIsReplaced() throws {
        let dir = tmp.appendingPathComponent("keyed"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let s = ReadyStore(dir: dir)
        let p = packJson(version: "v1"); try s.savePack(p, try XCTUnwrap(ReadyJson.parsePack(p)), nowMs: 1)
        let pl1 = planJson("planA"); try s.savePlan(pl1, try XCTUnwrap(ReadyJson.parsePlan(pl1)), key: PlanKey(packVersion: "v1", householdKey: "h", lang: "hi", warningId: 4))
        try s.saveAudio("planA", [9])
        func exists(_ n: String) -> Bool { FileManager.default.fileExists(atPath: dir.appendingPathComponent(n).path) }
        XCTAssertTrue(exists("plan-planA.json") && exists("audio-planA.wav"))
        let pl2 = planJson("planB"); try s.savePlan(pl2, try XCTUnwrap(ReadyJson.parsePlan(pl2)), key: PlanKey(packVersion: "v1", householdKey: "h2", lang: "hi", warningId: 4))
        XCTAssertFalse(exists("plan-planA.json")); XCTAssertFalse(exists("audio-planA.wav"))
        XCTAssertNil(s.load().audio); XCTAssertEqual("planB", s.load().plan?.planId)
        try s.saveAudio("planA", [1]) // the audio of a plan that is no longer the stored one is ignored
        XCTAssertNil(s.load().audio)
        try s.saveAudio("planB", [7]); XCTAssertEqual([7], try Data(contentsOf: XCTUnwrap(s.load().audio)).map { $0 })
    }

    func testStoredBodiesKeepFieldsThisBuildDoesNotKnow() throws {
        let dir = tmp.appendingPathComponent("raw"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let s = ReadyStore(dir: dir)
        let p = packJson(extra: ",\"tiles_url\":\"https://x/tiles\""); try s.savePack(p, try XCTUnwrap(ReadyJson.parsePack(p)), nowMs: 1)
        XCTAssertTrue(try String(contentsOf: dir.appendingPathComponent("pack-v1.json"), encoding: .utf8).contains("tiles_url"))
    }

    func testADamagedPointerOrBodyReadsAsNothingStoredNotACrash() throws {
        let dir = tmp.appendingPathComponent("bad"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let s = ReadyStore(dir: dir)
        let p = packJson(); try s.savePack(p, try XCTUnwrap(ReadyJson.parsePack(p)), nowMs: 1)
        try "{ nope".write(to: dir.appendingPathComponent("ready_state.json"), atomically: true, encoding: .utf8)
        XCTAssertNil(ReadyStore(dir: dir).load().pack)
        try s.savePack(p, try XCTUnwrap(ReadyJson.parsePack(p)), nowMs: 2)
        try "garbage".write(to: dir.appendingPathComponent("pack-v1.json"), atomically: true, encoding: .utf8)
        XCTAssertNil(ReadyStore(dir: dir).load().pack)
    }

    func testChecklistTicksAreKeptPerVersionAndResetWhenItChanges() throws {
        let dir = tmp.appendingPathComponent("ticks"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let s = ReadyStore(dir: dir)
        try s.setTick("v1", 0, done: true); try s.setTick("v1", 2, done: true)
        XCTAssertEqual([0, 2], s.ticks("v1"))
        try s.setTick("v1", 0, done: false); XCTAssertEqual([2], s.ticks("v1"))
        XCTAssertEqual([], s.ticks("v2")) // a new version starts clean
        try s.setTick("v2", 1, done: true); XCTAssertEqual([1], s.ticks("v2")); XCTAssertEqual([], s.ticks("v1"))
    }

    func testPackFileNamesFromTheNetworkCannotEscapeTheFolder() throws {
        let dir = tmp.appendingPathComponent("esc"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let s = ReadyStore(dir: dir)
        let p = packJson(version: "../../evil"); try s.savePack(p, try XCTUnwrap(ReadyJson.parsePack(p)), nowMs: 1)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: dir.path).allSatisfy { !$0.contains("/") && !$0.contains("..") })
        XCTAssertEqual("../../evil", s.load().pack?.version)
    }

    // ------------------------------------------------------------ http
    func testHttpApiSendsTheDeviceKeyAndTheRightRequests() throws {
        final class Seen: HTTPTransport, @unchecked Sendable {
            var log: [HTTPRequest] = []
            let pack: String, plan: String
            init(pack: String, plan: String) { self.pack = pack; self.plan = plan }
            func send(_ r: HTTPRequest) throws -> HTTPResponse {
                log.append(r)
                switch r.url.path {
                case "/v1/ready/pack": return HTTPResponse(status: 200, body: [UInt8](pack.utf8))
                case "/v1/ready/plan": return HTTPResponse(status: 200, body: [UInt8](plan.utf8))
                case "/v1/ready/plan/plan1/audio": return HTTPResponse(status: 200, body: [5, 6])
                case "/v1/ready/plan/none/audio": return HTTPResponse(status: 404, body: [UInt8]("{\"detail\":\"server audio is off\"}".utf8))
                default: return HTTPResponse(status: 503)
                }
            }
        }
        let t = Seen(pack: packJson(), plan: planJson())
        let api = HttpReadyApi(baseUrl: "http://127.0.0.1:8080/", deviceKey: "k1", transport: t)
        XCTAssertNotNil(ReadyJson.parsePack(try api.pack(.at(lat: 19.8, lon: 85.8), lang: "hi")))
        XCTAssertNotNil(ReadyJson.parsePack(try api.pack(.district("puri"), lang: "en")))
        XCTAssertNotNil(ReadyJson.parsePlan(try api.plan(lang: "hi", lat: 19.8, lon: 85.8, district: nil, household: Household(people: 2, elderly: true))))
        XCTAssertNotNil(ReadyJson.parsePlan(try api.plan(lang: "en", lat: nil, lon: nil, district: "delhi", household: Household())))
        XCTAssertEqual([5, 6], try api.audio("/v1/ready/plan/plan1/audio"))
        XCTAssertNil(try api.audio("/v1/ready/plan/none/audio")) // 404: on-device speech
        XCTAssertNil(try api.audio("/v1/ready/plan/broken/audio")) // 503: synthesis failed, also "use on-device speech"
        XCTAssertEqual("GET", t.log[0].method); XCTAssertEqual("k1", t.log[0].headers["X-Relay-Key"])
        XCTAssertEqual("lat=19.8&lon=85.8&lang=hi", t.log[0].url.query)
        XCTAssertEqual("district=puri&lang=en", t.log[1].url.query)
        let b2 = String(decoding: t.log[2].body ?? [], as: UTF8.self)
        XCTAssertEqual("POST", t.log[2].method)
        XCTAssertTrue(b2.contains("\"lat\":19.8") && b2.contains("\"elderly\":true") && b2.contains("\"people\":2"))
        let b3 = String(decoding: t.log[3].body ?? [], as: UTF8.self)
        XCTAssertTrue(b3.contains("\"district\":\"delhi\"") && !b3.contains("\"lat\""))
    }

    func testHttpApiReportsAnErrorStatus() {
        final class Fail: HTTPTransport, @unchecked Sendable {
            func send(_ r: HTTPRequest) throws -> HTTPResponse { HTTPResponse(status: 404, body: [UInt8]("{\"detail\":\"unknown district 'zz'\"}".utf8)) }
        }
        let api = HttpReadyApi(baseUrl: "http://127.0.0.1:1", deviceKey: "k", transport: Fail())
        XCTAssertThrowsError(try api.pack(.district("zz"), lang: "en")) { e in
            XCTAssertEqual(404, (e as? ReadyApiError)?.status); XCTAssertTrue((e as? ReadyApiError)?.message.contains("unknown district") == true)
        }
    }
}
