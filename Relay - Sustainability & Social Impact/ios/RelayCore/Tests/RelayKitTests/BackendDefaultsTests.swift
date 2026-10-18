import XCTest
@testable import RelayKit

final class BackendDefaultsTests: XCTestCase {
    private let tailscale = "http://100.116.13.37:8080"
    private let emu = BackendDefaults.emulatorUrl

    func testDetectsEmulators() {
        XCTAssertTrue(BackendDefaults.isEmulator("generic_x86/sdk_gphone64_arm64/emu64a:16/BE2A/123:userdebug/dev-keys", "ranchu", "sdk_gphone64_arm64"))
        XCTAssertTrue(BackendDefaults.isEmulator("generic/vbox86p/vbox86p:7.0/NRD90M", "vbox86", "vbox86p"))
        XCTAssertTrue(BackendDefaults.isEmulator("google/sdk_gphone_x86/generic:11", "x", "x"))
        XCTAssertTrue(BackendDefaults.isEmulator("samsung/foo/bar", "goldfish", "foo"))
        XCTAssertTrue(BackendDefaults.isEmulator("samsung/foo/bar", "ranchu", "foo"))
        XCTAssertTrue(BackendDefaults.isEmulator("samsung/foo/bar", "qcom", "sdk_foo"))
        XCTAssertTrue(BackendDefaults.isEmulator("Google/Android-EMULATOR/x", "qcom", "foo"))
    }

    func testRealPhoneIsNotAnEmulator() {
        XCTAssertFalse(BackendDefaults.isEmulator("samsung/r11sxx/r11s:16/BP2A.250605.031.A3/S721BXXU1AYE1:user/release-keys", "s5e9945", "r11sxx"))
        XCTAssertFalse(BackendDefaults.isEmulator("google/husky/husky:15/AP3A:user/release-keys", "husky", "husky"))
        XCTAssertFalse(BackendDefaults.isEmulator(nil, nil, nil))
        XCTAssertFalse(BackendDefaults.isEmulator("", "", ""))
    }

    func testDefaultsPerEnvironment() {
        XCTAssertEqual(emu, BackendDefaults.defaultFor(emulator: true, buildDefault: tailscale))
        XCTAssertEqual(tailscale, BackendDefaults.defaultFor(emulator: false, buildDefault: tailscale))
        XCTAssertEqual(emu, BackendDefaults.defaultFor(emulator: false, buildDefault: "  ")) // blank build default falls back to the emulator alias
    }

    func testNothingStoredUsesDefault() {
        XCTAssertEqual(tailscale, BackendDefaults.resolve(stored: nil, emulator: false, buildDefault: tailscale))
        XCTAssertEqual(tailscale, BackendDefaults.resolve(stored: "", emulator: false, buildDefault: tailscale))
        XCTAssertEqual(tailscale, BackendDefaults.resolve(stored: "   ", emulator: false, buildDefault: tailscale))
        XCTAssertEqual(emu, BackendDefaults.resolve(stored: nil, emulator: true, buildDefault: tailscale))
    }

    func testLegacyEmulatorDefaultIsMigratedOnRealPhones() {
        XCTAssertEqual(tailscale, BackendDefaults.resolve(stored: emu, emulator: false, buildDefault: tailscale))
        XCTAssertEqual(tailscale, BackendDefaults.resolve(stored: "\(emu)/", emulator: false, buildDefault: tailscale))
        XCTAssertEqual(tailscale, BackendDefaults.resolve(stored: " \(emu) ", emulator: false, buildDefault: tailscale))
    }

    func testEmulatorKeepsItsStoredEmulatorUrl() {
        XCTAssertEqual(emu, BackendDefaults.resolve(stored: emu, emulator: true, buildDefault: tailscale))
    }

    func testExplicitUserChoicesAreKept() {
        XCTAssertEqual(tailscale, BackendDefaults.resolve(stored: tailscale, emulator: false, buildDefault: "https://relay.example.run.app"))
        XCTAssertEqual("http://192.168.1.20:8080", BackendDefaults.resolve(stored: "http://192.168.1.20:8080", emulator: false, buildDefault: tailscale))
        XCTAssertEqual("http://192.168.1.20:8080", BackendDefaults.resolve(stored: "http://192.168.1.20:8080", emulator: true, buildDefault: tailscale))
    }

    func testHostOfStripsSchemeAndPath() {
        XCTAssertEqual("100.116.13.37:8080", BackendDefaults.hostOf("http://100.116.13.37:8080"))
        XCTAssertEqual("100.116.13.37:8080", BackendDefaults.hostOf("http://100.116.13.37:8080/"))
        XCTAssertEqual("relay.run.app", BackendDefaults.hostOf("https://relay.run.app/v1/x?y=1"))
        XCTAssertEqual("10.0.2.2:8080", BackendDefaults.hostOf(" 10.0.2.2:8080 "))
    }

    // ---- iOS additions: the production default and where build-time values come from

    func testProductionDefaultIsTheDeployedBackend() {
        XCTAssertEqual("https://relay.harshvs.com", BackendDefaults.productionUrl)
        XCTAssertEqual("https://relay.harshvs.com", BuildConfig.standard.defaultBackend)
        XCTAssertEqual("dev-relay-key", BuildConfig.standard.defaultKey)
        XCTAssertFalse(BuildConfig.standard.lockConnection)
        XCTAssertEqual("relay.harshvs.com", BackendDefaults.hostOf(BuildConfig.standard.defaultBackend))
    }

    func testBuildConfigFromInfoDictionary() {
        let b = BuildConfig(infoDictionary: ["RelayBackendURL": " https://relay.example.run.app ", "RelayDeviceKey": "secret", "RelayLockConnection": true])
        XCTAssertEqual(BuildConfig(defaultBackend: "https://relay.example.run.app", defaultKey: "secret", lockConnection: true), b)
        XCTAssertEqual(BuildConfig.standard, BuildConfig(infoDictionary: nil))
        XCTAssertEqual(BuildConfig.standard, BuildConfig(infoDictionary: ["RelayBackendURL": "  ", "RelayDeviceKey": ""]))
        XCTAssertTrue(BuildConfig(infoDictionary: ["RelayLockConnection": "YES"]).lockConnection)
    }

    func testHelpLocateDefaultComesFromTheInfoDictionary() {
        XCTAssertTrue(BuildConfig.standard.helpLocateDefault)
        XCTAssertTrue(BuildConfig(infoDictionary: nil).helpLocateDefault)
        XCTAssertTrue(BuildConfig(infoDictionary: ["RelayHelpLocateDefault": ""]).helpLocateDefault)
        XCTAssertTrue(BuildConfig(infoDictionary: ["RelayHelpLocateDefault": "YES"]).helpLocateDefault)
        XCTAssertFalse(BuildConfig(infoDictionary: ["RelayHelpLocateDefault": "NO"]).helpLocateDefault)
        XCTAssertFalse(BuildConfig(infoDictionary: ["RelayHelpLocateDefault": "false"]).helpLocateDefault)
        XCTAssertFalse(BuildConfig(infoDictionary: ["RelayHelpLocateDefault": false]).helpLocateDefault)
    }
}
