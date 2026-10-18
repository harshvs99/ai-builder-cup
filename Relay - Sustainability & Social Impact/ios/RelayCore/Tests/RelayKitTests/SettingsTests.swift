import XCTest
import RelayCore
@testable import RelayKit

final class SettingsTests: TempDirTestCase {
    private func settings(_ store: any KeyValueStore = InMemoryKeyValueStore(), locked: Bool = false, backend: String = "https://relay.harshvs.com") -> AppSettings {
        AppSettings(store: store, buildConfig: BuildConfig(defaultBackend: backend, defaultKey: "build-key", lockConnection: locked))
    }

    func testDefaultsFollowTheBuildConfig() {
        let s = settings().current
        XCTAssertEqual("https://relay.harshvs.com", s.backendUrl)
        XCTAssertEqual("build-key", s.deviceKey)
        XCTAssertEqual(.en, s.language)
        XCTAssertEqual(.auto, s.themeMode)
        XCTAssertEqual(.normal, s.voiceSpeed)
        XCTAssertEqual(.automatic, s.gatewayMode)
        XCTAssertTrue(s.carryInBackground)
        XCTAssertFalse(s.languageConfirmed); XCTAssertFalse(s.largerText); XCTAssertFalse(s.readAloud)
        XCTAssertFalse(s.pretendOffline)
        XCTAssertEqual("", s.areaLabel); XCTAssertEqual("", s.allowlist)
    }

    func testUpdatePersistsEveryFieldAndReloads() {
        let store = InMemoryKeyValueStore()
        let a = settings(store)
        a.update {
            var s = $0
            s.backendUrl = "http://192.168.1.20:8080"; s.deviceKey = "k2"; s.allowlist = "a1b2"; s.language = .war
            s.themeMode = .dark; s.largerText = true; s.readAloud = true; s.voiceSpeed = .slow; s.languageConfirmed = true
            s.areaLabel = "Ward 4, Puri district"; s.gatewayMode = .never; s.carryInBackground = false
            return s
        }
        let b = settings(store).current
        XCTAssertEqual(a.current, b)
        XCTAssertEqual("http://192.168.1.20:8080", b.backendUrl)
        XCTAssertEqual(.war, b.language)
        XCTAssertEqual(.never, b.gatewayMode)
        XCTAssertTrue(b.pretendOffline)
        XCTAssertEqual("DARK", store.stringValue(forKey: "theme"))
        XCTAssertEqual("war", store.stringValue(forKey: "lang"))
        XCTAssertEqual("NEVER", store.stringValue(forKey: "gateway_mode"))
        XCTAssertEqual(true, store.boolValue(forKey: "offline"))
    }

    func testDefaultBackendIsStoredAsUnsetSoItKeepsFollowingTheBuild() {
        let store = InMemoryKeyValueStore()
        let a = settings(store)
        a.update { var s = $0; s.backendUrl = "http://x:1"; return s }
        XCTAssertEqual("http://x:1", store.stringValue(forKey: "backend"))
        a.update { var s = $0; s.backendUrl = "https://relay.harshvs.com"; return s }
        XCTAssertNil(store.stringValue(forKey: "backend"))
        XCTAssertEqual("https://new.example", settings(store, backend: "https://new.example").current.backendUrl)
    }

    func testLegacyEmulatorBackendIsMigratedAndLegacyOfflineFlagRead() {
        let store = InMemoryKeyValueStore()
        store.setString(BackendDefaults.emulatorUrl, forKey: "backend")
        store.setBool(true, forKey: "offline") // older installs: boolean "Pretend offline", no gateway_mode
        let s = settings(store).current
        XCTAssertEqual("https://relay.harshvs.com", s.backendUrl)
        XCTAssertEqual(.never, s.gatewayMode)
    }

    func testUnknownStoredEnumNamesFallBackToDefaults() {
        let store = InMemoryKeyValueStore()
        store.setString("PURPLE", forKey: "theme"); store.setString("xx", forKey: "lang"); store.setString("???", forKey: "gateway_mode")
        let s = settings(store).current
        XCTAssertEqual(.auto, s.themeMode); XCTAssertEqual(.en, s.language); XCTAssertEqual(.automatic, s.gatewayMode)
    }

    func testLockedConnectionIgnoresStoredAndEditedValues() {
        let store = InMemoryKeyValueStore()
        store.setString("http://stale:1", forKey: "backend"); store.setString("stale-key", forKey: "key")
        let a = settings(store, locked: true)
        XCTAssertTrue(a.connectionLocked)
        XCTAssertEqual("https://relay.harshvs.com", a.current.backendUrl)
        XCTAssertEqual("build-key", a.current.deviceKey)
        a.update { var s = $0; s.backendUrl = "http://evil:1"; s.deviceKey = "x"; s.areaLabel = "A"; return s }
        XCTAssertEqual("https://relay.harshvs.com", a.current.backendUrl)
        XCTAssertEqual("build-key", a.current.deviceKey)
        XCTAssertEqual("A", a.current.areaLabel)
    }

    func testListenersFireOnlyOnChangeAndCanBeRemoved() {
        let a = settings()
        let seen = Box([Lang]())
        let token = a.addListener { s in seen.mutate { $0.append(s.language) } }
        a.update { var s = $0; s.language = .hi; return s }
        a.update { $0 } // no change: no callback
        a.update { var s = $0; s.language = .or; return s }
        a.removeListener(token)
        a.update { var s = $0; s.language = .tl; return s }
        XCTAssertEqual([.hi, .or], seen.value)
    }

    func testRecentPeersAreStoredOutsideSettingsAndFireNoListener() {
        let a = settings()
        let fired = Box(0)
        a.addListener { _ in fired.mutate { $0 += 1 } }
        a.recentPeers = [RecentPeers.Peer(mac: "AA", nodeIdHex: "0011223344556677")]
        XCTAssertEqual([RecentPeers.Peer(mac: "AA", nodeIdHex: "0011223344556677")], a.recentPeers)
        XCTAssertEqual(0, fired.value)
    }

    func testUpdatesStreamYieldsCurrentThenChanges() async {
        let a = settings()
        var it = a.updates().makeAsyncIterator()
        let first = await it.next()
        XCTAssertEqual(.en, first?.language)
        a.update { var s = $0; s.language = .hi; return s }
        let second = await it.next()
        XCTAssertEqual(.hi, second?.language)
    }

    func testFileBackedStoreSurvivesRestartAndCorruption() throws {
        let f = file("settings.json")
        let a = FileKeyValueStore(file: f)
        a.setString("v", forKey: "s"); a.setBool(true, forKey: "b")
        let b = FileKeyValueStore(file: f)
        XCTAssertEqual("v", b.stringValue(forKey: "s")); XCTAssertEqual(true, b.boolValue(forKey: "b"))
        XCTAssertNil(b.boolValue(forKey: "s")); XCTAssertNil(b.stringValue(forKey: "missing"))
        b.removeValue(forKey: "s")
        XCTAssertNil(FileKeyValueStore(file: f).stringValue(forKey: "s"))
        try Data("garbage".utf8).write(to: f)
        XCTAssertNil(FileKeyValueStore(file: f).boolValue(forKey: "b"))
    }

    func testUserDefaultsBackedStore() {
        let suite = "relaykit.test.\(UUID().uuidString)"
        guard let d = UserDefaults(suiteName: suite) else { return XCTFail("no suite") }
        defer { d.removePersistentDomain(forName: suite) }
        let store = UserDefaultsKeyValueStore(d)
        XCTAssertNil(store.boolValue(forKey: "flag"))
        store.setBool(false, forKey: "flag")
        XCTAssertEqual(false, store.boolValue(forKey: "flag"))
        store.setString("x", forKey: "k")
        XCTAssertEqual("x", store.stringValue(forKey: "k"))
        store.removeValue(forKey: "k")
        XCTAssertNil(store.stringValue(forKey: "k"))
        let s = AppSettings(store: store)
        s.update { var n = $0; n.language = .hi; return n }
        XCTAssertEqual(.hi, AppSettings(store: store).current.language)
    }
}
