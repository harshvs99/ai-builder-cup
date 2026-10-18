import XCTest
import RelayCore
@testable import RelayKit

final class NetworkLogicTests: XCTestCase {
    private typealias Kind = ChainNode.Kind
    private func vs(_ kind: VillagerStatus.Kind, hops: Int? = nil) -> VillagerStatus {
        VillagerStatus(kind: kind, dot: .green, peers: 2, hopsToGateway: hops, syncedAgoMs: nil)
    }
    private func peers(_ n: Int) -> [PeerInfo] { (0..<n).map { PeerInfo(linkId: "l\($0)", nodeIdHex: "n\($0)", isGateway: false, hops: 1) } }
    private let allKinds: [VillagerStatus.Kind] = [.reachable, .thisPhoneGateway, .waitingForGateway, .searching, .bluetoothOff]

    // ---- chain collapse
    func testChainTwoHopsShowsIntermediate() {
        XCTAssertEqual([ChainNode(.you), ChainNode(.hop, hops: 1), ChainNode(.gateway, hops: 2)], ChainModel.from(vs(.reachable, hops: 2)))
    }

    func testChainOneHopCollapsesToYouAndGateway() {
        XCTAssertEqual([ChainNode(.you), ChainNode(.gateway, hops: 1)], ChainModel.from(vs(.reachable, hops: 1)))
        XCTAssertEqual([ChainNode(.you), ChainNode(.gateway, hops: 1)], ChainModel.from(vs(.reachable, hops: nil)))
        XCTAssertEqual([ChainNode(.you), ChainNode(.gateway, hops: 1)], ChainModel.from(vs(.reachable, hops: 0)))
    }

    func testChainFarGatewayUsesOneIntermediate() {
        let c = ChainModel.from(vs(.reachable, hops: 4))
        XCTAssertEqual(3, c.count)
        XCTAssertEqual(ChainNode(.hop, hops: 3), c[1])
        XCTAssertEqual(ChainNode(.gateway, hops: 4), c[2])
    }

    func testChainThisPhoneGatewayIsOnlyYou() {
        XCTAssertEqual([ChainNode(.youGateway)], ChainModel.from(vs(.thisPhoneGateway)))
    }

    func testChainNoGatewayShowsQuestionMark() {
        for k in [VillagerStatus.Kind.waitingForGateway, .searching, .bluetoothOff] {
            XCTAssertEqual([ChainNode(.you), ChainNode(.unknown)], ChainModel.from(vs(k)))
        }
    }

    func testChainFromRealStatus() {
        let st = MeshStatus(bluetoothOn: true, peers: peers(2), hopsToGateway: 2)
        XCTAssertEqual([Kind.you, .hop, .gateway], ChainModel.from(VillagerStatus.from(st, now: 0)).map { $0.kind })
    }

    func testChainCaptionsAndDescription() {
        let s = UI.strings(.en).network
        let nodes = ChainModel.from(vs(.reachable, hops: 2))
        XCTAssertEqual(["This phone", "1 hop", "Gateway · 2 hops"], ChainModel.captions(nodes, s))
        // VoiceOver says "You" for the first node, not "This phone".
        XCTAssertEqual("Path to help: You, then 1 hop, then Gateway · 2 hops", ChainModel.description(nodes, s))
        XCTAssertEqual("Path to help: Gateway · this phone", ChainModel.description(ChainModel.from(vs(.thisPhoneGateway)), s))
        XCTAssertEqual("Path to help: You, then No gateway known yet", ChainModel.description(ChainModel.from(vs(.searching)), s))
    }

    // ---- stat derivations
    func testStatsCarriedExcludesOwnFrames() {
        let st = MeshStatus(peers: peers(3), storedNeeds: 5, storedAcks: 4)
        let s = NetworkStats.from(st, ownStored: 2, ownDelivered: 0)
        XCTAssertEqual(3, s.phonesNearby)
        XCTAssertEqual(7, s.carriedForNeighbours)
    }

    func testStatsCarriedNeverNegative() {
        let st = MeshStatus(storedNeeds: 1, storedAcks: 0)
        XCTAssertEqual(0, NetworkStats.from(st, ownStored: 3, ownDelivered: 0).carriedForNeighbours)
    }

    func testStatsWaitingForGatewayIsPendingUploads() {
        let st = MeshStatus(isGateway: true, storedNeeds: 9, pendingUploads: 2)
        XCTAssertEqual(2, NetworkStats.from(st, ownStored: 0, ownDelivered: 0).waitingToPassOn)
    }

    func testStatsWaitingForOrdinaryPhoneSkipsDeliveredOwn() {
        let st = MeshStatus(isGateway: false, storedNeeds: 5, pendingUploads: 99)
        XCTAssertEqual(3, NetworkStats.from(st, ownStored: 2, ownDelivered: 2).waitingToPassOn)
        XCTAssertEqual(0, NetworkStats.from(MeshStatus(storedNeeds: 1), ownStored: 1, ownDelivered: 2).waitingToPassOn)
    }

    // ---- battery / mesh hours
    func testMeshHoursRoundDown() {
        let s = NetworkStats.from(MeshStatus(meshHoursRemaining: 38.9), ownStored: 0, ownDelivered: 0)
        XCTAssertEqual(38, s.meshHoursLeft)
        XCTAssertEqual(.hours(38), MeshHoursText.of(s))
    }

    func testMeshHoursStates() {
        func of(_ st: MeshStatus) -> MeshHoursText.Value { MeshHoursText.of(NetworkStats.from(st, ownStored: 0, ownDelivered: 0)) }
        XCTAssertEqual(.unknown, of(MeshStatus(meshHoursRemaining: nil)))
        XCTAssertEqual(.underOne, of(MeshStatus(meshHoursRemaining: 0.4)))
        // Charging wins even if an estimate were present.
        XCTAssertEqual(.charging, of(MeshStatus(charging: true, meshHoursRemaining: nil)))
    }

    func testBatteryTileLabelFollowsCharging() {
        func stats(_ st: MeshStatus) -> NetworkStats { NetworkStats.from(st, ownStored: 0, ownDelivered: 0) }
        let charging = stats(MeshStatus(charging: true, meshHoursRemaining: 12.0))
        XCTAssertTrue(MeshHoursText.labelIsBattery(charging))
        XCTAssertEqual(.charging, MeshHoursText.of(charging))
        let onBattery = stats(MeshStatus(charging: false, meshHoursRemaining: 12.0))
        XCTAssertFalse(MeshHoursText.labelIsBattery(onBattery))
        XCTAssertEqual(.hours(12), MeshHoursText.of(onBattery))
        let unknown = stats(MeshStatus(charging: false, meshHoursRemaining: nil))
        XCTAssertFalse(MeshHoursText.labelIsBattery(unknown))
        XCTAssertEqual(.unknown, MeshHoursText.of(unknown))
    }

    func testMeshHoursFromEnergyModel() {
        // 60 % battery at the NORMAL duty mode: 60 / (1.0 + 0.4 + 2.6 * 0.2) = 31.25 h.
        let h = MeshEnergy.hoursRemaining(batteryPct: 60, charging: false, mode: .normal)!
        XCTAssertEqual(31, NetworkStats.from(MeshStatus(meshHoursRemaining: h), ownStored: 0, ownDelivered: 0).meshHoursLeft)
    }

    func testMeshHoursTextIsLocalized() {
        let s = UI.strings(.en).network
        func text(_ st: MeshStatus) -> String { MeshHoursText.text(NetworkStats.from(st, ownStored: 0, ownDelivered: 0), s) }
        XCTAssertEqual("about 38 h", text(MeshStatus(meshHoursRemaining: 38.9)))
        XCTAssertEqual("< 1 h", text(MeshStatus(meshHoursRemaining: 0.2)))
        XCTAssertEqual("Charging", text(MeshStatus(charging: true)))
        XCTAssertEqual("—", text(MeshStatus()))
    }

    // ---- permission words
    func testPermissionWords() {
        XCTAssertEqual(.allowed, PermissionWord.of(granted: true, askedWhenNeeded: true))
        XCTAssertEqual(.allowed, PermissionWord.of(granted: true, askedWhenNeeded: false))
        XCTAssertEqual(.askEachTime, PermissionWord.of(granted: false, askedWhenNeeded: true))
        XCTAssertEqual(.notAllowed, PermissionWord.of(granted: false, askedWhenNeeded: false))
    }

    func testIosPermissionWordsAndTones() {
        XCTAssertEqual(.allowed, PermissionWord.of(.granted))
        XCTAssertEqual(.whileUsing, PermissionWord.of(.grantedWhileUsing))
        XCTAssertEqual(.askEachTime, PermissionWord.of(.notDetermined))
        XCTAssertEqual(.notAllowed, PermissionWord.of(.denied))
        XCTAssertEqual(.good, PermissionWord.allowed.tone)
        XCTAssertEqual(.good, PermissionWord.whileUsing.tone)
        XCTAssertEqual(.warning, PermissionWord.askEachTime.tone)
        XCTAssertEqual(.bad, PermissionWord.notAllowed.tone)
        let s = UI.strings(.en).network
        XCTAssertEqual(["Allowed", "While using", "Ask each time", "Not allowed"],
                       [PermissionWord.allowed, .whileUsing, .askEachTime, .notAllowed].map { $0.label(s) })
    }

    func testDotTones() {
        XCTAssertEqual(.good, VillagerStatus.Dot.green.tone)
        XCTAssertEqual(.warning, VillagerStatus.Dot.amber.tone)
        XCTAssertEqual(.neutral, VillagerStatus.Dot.grey.tone)
    }

    // ---- gateway and carry
    func testGatewayChoiceHidesAlways() {
        XCTAssertEqual([GatewayMode.automatic, .never], GatewayChoice.options)
        XCTAssertEqual(.automatic, GatewayChoice.effective(.always))
        XCTAssertTrue(GatewayChoice.isOn(.always))
        XCTAssertFalse(GatewayChoice.isOn(.never))
        XCTAssertEqual(.never, GatewayChoice.fromToggle(false))
        XCTAssertEqual(.automatic, GatewayChoice.fromToggle(true))
        let s = UI.strings(.en).network
        XCTAssertEqual("Automatic", GatewayChoice.name(.always, s))
        XCTAssertEqual("Never", GatewayChoice.name(.never, s))
        XCTAssertEqual(s.gatewayNeverHelp, GatewayChoice.help(.never, s))
        XCTAssertEqual(s.gatewayAutomaticHelp, GatewayChoice.help(.automatic, s))
    }

    func testCarryPolicy() {
        XCTAssertFalse(CarryPolicy.stopWhenBackgrounded(Settings(carryInBackground: true), changingConfigurations: false))
        XCTAssertTrue(CarryPolicy.stopWhenBackgrounded(Settings(carryInBackground: false), changingConfigurations: false))
        XCTAssertFalse(CarryPolicy.stopWhenBackgrounded(Settings(carryInBackground: false), changingConfigurations: true))
    }

    // ---- strings
    func testEveryStatusKindHasASentenceInEveryLanguage() {
        for lang in UI.appLangs {
            let s = UI.strings(lang).network
            for k in allKinds { XCTAssertFalse(s.statusSentence(vs(k, hops: 2)).trimmed.isEmpty, "\(lang) \(k)") }
            XCTAssertTrue(s.chainDescription(["a", "b"]).contains("a"))
        }
    }

    func testEnglishChainCaptionsMatchSpec() {
        let s = UI.strings(.en).network
        XCTAssertEqual("Gateway · 2 hops", s.chainGateway(2))
        XCTAssertEqual("1 hop", s.chainHop(1))
        XCTAssertEqual("The nearest gateway phone is 2 hops away. Relay never shows who owns a phone.", s.statusSentence(vs(.reachable, hops: 2)))
    }

    // ---- Network tab model
    func testNetworkModelForAReachableGateway() {
        let st = MeshStatus(bluetoothOn: true, peers: peers(3), hopsToGateway: 2, storedNeeds: 4, storedAcks: 9, pendingUploads: 1,
                            batteryPercent: 71, meshHoursRemaining: 38.4)
        let stats = NetworkStats.from(st, ownStored: 1, ownDelivered: 0)
        let m = NetworkModel.make(villager: VillagerStatus.from(st, now: 0), stats: stats, settings: Settings(), strings: UI.strings(.en))
        XCTAssertEqual("Network", m.title)
        XCTAssertEqual("Bluetooth mesh · 3 phones · ", m.subtitle)
        XCTAssertEqual("Reachable", m.statusWord)
        XCTAssertEqual(.good, m.statusTone)
        XCTAssertEqual("Your messages can reach help", m.statusTitle)
        XCTAssertEqual(["This phone", "1 hop", "Gateway · 2 hops"], m.chainCaptions)
        XCTAssertEqual("Path to help: You, then 1 hop, then Gateway · 2 hops", m.chainDescription)
        XCTAssertEqual(["Phones nearby", "Carried for neighbours", "Waiting to pass on", "Mesh hours left"], m.tiles.map { $0.label })
        XCTAssertEqual(["3", "12", "4", "about 38 h"], m.tiles.map { $0.value })
        XCTAssertEqual(.good, m.tiles[3].tone)
        XCTAssertTrue(m.gatewayOn)
        XCTAssertTrue(m.carryOn)
    }

    func testNetworkModelOffAndCharging() {
        let st = MeshStatus(bluetoothOn: false, charging: true)
        let m = NetworkModel.make(villager: VillagerStatus.from(st, now: 0), stats: NetworkStats.from(st, ownStored: 0, ownDelivered: 0),
                                  settings: Settings(gatewayMode: .never, carryInBackground: false), strings: UI.strings(.en))
        XCTAssertEqual("Bluetooth is off", m.statusTitle)
        XCTAssertEqual(.neutral, m.statusTone)
        XCTAssertEqual("Battery", m.tiles[3].label)
        XCTAssertEqual("Charging", m.tiles[3].value)
        XCTAssertFalse(m.gatewayOn)
        XCTAssertFalse(m.carryOn)
        XCTAssertEqual(["This phone", "No gateway known yet"], m.chainCaptions)
    }

    // ---- Settings model
    func testSettingsModel() {
        let perms = PermissionStates(bluetooth: .allowed, location: .whileUsing, microphone: .askEachTime)
        let m = SettingsModel.make(settings: Settings(language: .hi, areaLabel: " "), permissions: perms, strings: UI.strings(.en))
        XCTAssertEqual("हिन्दी", m.languageName)
        XCTAssertEqual("Not set", m.areaValue)
        XCTAssertEqual("Automatic", m.gatewayName)
        XCTAssertEqual(["Bluetooth", "Location", "Microphone"], m.permissions.map { $0.title })
        XCTAssertEqual(["Allowed", "While using", "Ask each time"], m.permissions.map { $0.value })
        XCTAssertEqual([.good, .good, .warning], m.permissions.map { $0.tone })
        XCTAssertEqual("Ward 4", SettingsModel.make(settings: Settings(areaLabel: "Ward 4"), permissions: perms, strings: UI.strings(.en)).areaValue)
        let s = UI.strings(.en).network
        XCTAssertEqual(["Light", "Dark", "Auto"], ThemeMode.allCases.map { SettingsModel.themeLabel($0, s) })
        XCTAssertEqual(["Slow", "Normal"], VoiceSpeed.allCases.map { SettingsModel.speedLabel($0, s) })
    }
}
