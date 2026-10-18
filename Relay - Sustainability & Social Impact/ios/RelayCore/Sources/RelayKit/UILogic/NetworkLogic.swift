import Foundation
import RelayCore

/// One node of the Network chain diagram. `hops` is the hop count to show under the node (nil for You / unknown).
public struct ChainNode: Equatable, Hashable, Sendable {
    public enum Kind: Sendable, Hashable {
        /// This phone, as an ordinary phone.
        case you
        /// This phone, and it is the gateway (green circle with a sync icon).
        case youGateway
        /// A phone in between.
        case hop
        /// A known gateway phone.
        case gateway
        /// "?": no gateway known.
        case unknown
    }

    public let kind: Kind
    public let hops: Int?
    public init(_ kind: Kind, hops: Int? = nil) { self.kind = kind; self.hops = hops }

    /// Caption under the node, in the words of [s].
    public func caption(_ s: NetworkSettingsStrings) -> String {
        switch kind {
        case .you: return s.chainThisPhone
        case .youGateway: return s.chainGatewayItself
        case .hop: return s.chainHop(hops ?? 1)
        case .gateway: return s.chainGateway(hops ?? 1)
        case .unknown: return s.chainUnknown
        }
    }
}

/// The chain diagram's nodes for a villager status (spec section 7). Pure.
public enum ChainModel {
    public static func from(_ s: VillagerStatus) -> [ChainNode] {
        switch s.kind {
        // Only You, as the gateway.
        case .thisPhoneGateway: return [ChainNode(.youGateway)]
        case .reachable:
            let h = max(s.hopsToGateway ?? 1, 1)
            if h == 1 { return [ChainNode(.you), ChainNode(.gateway, hops: 1)] }
            // One intermediate circle stands for every phone between; it is labelled with the hops to its far end.
            return [ChainNode(.you), ChainNode(.hop, hops: h - 1), ChainNode(.gateway, hops: h)]
        case .waitingForGateway, .searching, .bluetoothOff:
            return [ChainNode(.you), ChainNode(.unknown)]
        }
    }

    /// The captions under the nodes, in order ("This phone", "1 hop", "Gateway · 2 hops").
    public static func captions(_ nodes: [ChainNode], _ s: NetworkSettingsStrings) -> [String] { nodes.map { $0.caption(s) } }

    /// The whole diagram as one sentence for VoiceOver: "Path to help: You, then 1 hop, then Gateway · 2 hops".
    /// The first node reads "You" rather than "This phone".
    public static func description(_ nodes: [ChainNode], _ s: NetworkSettingsStrings) -> String {
        s.chainDescription(nodes.map { $0.kind == .you ? s.chainYou : $0.caption(s) })
    }
}

/// The 2x2 tile numbers. Pure; see `from` for each definition.
public struct NetworkStats: Equatable, Sendable {
    public let phonesNearby: Int
    public let carriedForNeighbours: Int
    public let waitingToPassOn: Int
    /// Rounded mesh hours left, or nil while charging / battery unknown.
    public let meshHoursLeft: Int?
    public let charging: Bool

    public init(phonesNearby: Int, carriedForNeighbours: Int, waitingToPassOn: Int, meshHoursLeft: Int?, charging: Bool) {
        self.phonesNearby = phonesNearby; self.carriedForNeighbours = carriedForNeighbours
        self.waitingToPassOn = waitingToPassOn; self.meshHoursLeft = meshHoursLeft; self.charging = charging
    }

    /// - Phones nearby: peers in range.
    /// - Carried for neighbours: stored NEED + ACK frames that this phone did not originate
    ///   (`stored - ownStored`, never below 0). [ownStored] = own non-cancelled NEEDs still in the packet store.
    /// - Waiting to pass on: as the gateway, the frames not yet uploaded (`MeshStatus.pendingUploads`); otherwise
    ///   every stored NEED not known to be delivered, because a non-gateway phone cannot see what others uploaded:
    ///   `storedNeeds - ownDelivered`. [ownDelivered] = own stored NEEDs already uploaded or acknowledged by the
    ///   control room.
    /// - Mesh hours left: `MeshStatus.meshHoursRemaining` rounded down.
    public static func from(_ s: MeshStatus, ownStored: Int, ownDelivered: Int) -> NetworkStats {
        let carried = max(s.storedNeeds + s.storedAcks - ownStored, 0)
        let waiting = s.isGateway ? s.pendingUploads : max(s.storedNeeds - ownDelivered, 0)
        return NetworkStats(
            phonesNearby: s.peers.count,
            carriedForNeighbours: carried,
            waitingToPassOn: waiting,
            meshHoursLeft: s.meshHoursRemaining.map { max(Int($0.rounded(.down)), 0) },
            charging: s.charging
        )
    }
}

/// How sure a permission row's colour is: green, orange, red or grey (the UI maps these to its palette).
public enum StatusTone: Sendable, Hashable { case good, warning, neutral, bad }

extension VillagerStatus.Dot {
    /// The colour family of the status dot beside the status word.
    public var tone: StatusTone {
        switch self {
        case .green: return .good
        case .amber: return .warning
        case .grey: return .neutral
        }
    }
}

/// What the system says about one permission; the iOS adapter maps `CBManagerAuthorization`, `CLAuthorizationStatus`
/// and the microphone record permission onto this.
public enum PermissionStatus: Sendable, Hashable {
    /// The system has not asked yet (it asks at the moment of use).
    case notDetermined
    case denied
    case granted
    /// Location only: allowed while the app is in use.
    case grantedWhileUsing
}

/// Real permission state as shown in Settings: green Allowed (or While using) / orange Ask each time / red Not allowed.
public enum PermissionWord: Sendable, Hashable {
    case allowed, whileUsing, askEachTime, notAllowed

    /// Android's rule. Granted is Allowed. Not granted: a permission the app asks for at the moment of use (location at
    /// Send, microphone at the mic tap) is "Ask each time"; one the mesh cannot run without (Bluetooth) is "Not allowed".
    /// Android does not tell an app "denied for good" from "never asked", so this is the honest middle word.
    public static func of(granted: Bool, askedWhenNeeded: Bool) -> PermissionWord {
        if granted { return .allowed }
        return askedWhenNeeded ? .askEachTime : .notAllowed
    }

    /// iOS rule. iOS does say "not asked yet" apart from "denied", so those become Ask each time and Not allowed.
    public static func of(_ status: PermissionStatus) -> PermissionWord {
        switch status {
        case .granted: return .allowed
        case .grantedWhileUsing: return .whileUsing
        case .notDetermined: return .askEachTime
        case .denied: return .notAllowed
        }
    }

    public var tone: StatusTone {
        switch self {
        case .allowed, .whileUsing: return .good
        case .askEachTime: return .warning
        case .notAllowed: return .bad
        }
    }

    /// The word in the app language.
    public func label(_ s: NetworkSettingsStrings) -> String {
        switch self {
        case .allowed: return s.permAllowed
        case .whileUsing: return s.permWhileUsing
        case .askEachTime: return s.permAsk
        case .notAllowed: return s.permNotAllowed
        }
    }
}

/// What the Settings permission rows show; read from the system by the Settings screen.
public struct PermissionStates: Equatable, Sendable {
    public let bluetooth: PermissionWord
    public let location: PermissionWord
    public let microphone: PermissionWord
    public init(bluetooth: PermissionWord, location: PermissionWord, microphone: PermissionWord) {
        self.bluetooth = bluetooth; self.location = location; self.microphone = microphone
    }
}

/// Gateway toggle and picker semantics. `.always` has no separate behaviour (see `GatewayManager`), so it reads as Automatic.
public enum GatewayChoice {
    /// The modes offered in the picker.
    public static let options: [GatewayMode] = [.automatic, .never]

    public static func effective(_ mode: GatewayMode) -> GatewayMode { mode == .never ? .never : .automatic }

    public static func isOn(_ mode: GatewayMode) -> Bool { effective(mode) == .automatic }
    public static func fromToggle(_ on: Bool) -> GatewayMode { on ? .automatic : .never }

    /// "Automatic" or "Never", for the Settings row and the picker.
    public static func name(_ mode: GatewayMode, _ s: NetworkSettingsStrings) -> String {
        effective(mode) == .never ? s.gatewayNever : s.gatewayAutomatic
    }

    /// The sentence under a picker option.
    public static func help(_ mode: GatewayMode, _ s: NetworkSettingsStrings) -> String {
        effective(mode) == .never ? s.gatewayNeverHelp : s.gatewayAutomaticHelp
    }
}

/// When the mesh runs relative to the app being visible ("Carry messages in the background"). Android stops its
/// foreground service; iOS has no service, so the iOS app only reads `carryInBackground` (best effort).
public enum CarryPolicy {
    /// Stop the service when the activity stops, unless the user wants background carrying, or the stop is only a
    /// configuration change (rotation recreates the activity and would otherwise bounce the service).
    public static func stopWhenBackgrounded(_ settings: Settings, changingConfigurations: Bool) -> Bool {
        !settings.carryInBackground && !changingConfigurations
    }
}

/// Tile text for "Mesh hours left".
public enum MeshHoursText {
    public enum Value: Equatable, Sendable {
        case unknown
        case charging
        case underOne
        case hours(Int)
    }

    /// While charging the tile reads "Charging" / "Battery"; otherwise "about N h" / "Mesh hours left" ("—" when unknown).
    public static func labelIsBattery(_ stats: NetworkStats) -> Bool { stats.charging }

    public static func of(_ stats: NetworkStats) -> Value {
        if stats.charging { return .charging }
        guard let h = stats.meshHoursLeft else { return .unknown }
        return h < 1 ? .underOne : .hours(h)
    }

    /// The value as the tile shows it in the app language.
    public static func text(_ stats: NetworkStats, _ s: NetworkSettingsStrings) -> String {
        switch of(stats) {
        case .unknown: return s.unknownValue
        case .charging: return s.charging
        case .underOne: return s.hoursUnder1
        case .hours(let n): return s.hoursValue(n)
        }
    }
}

/// One stat tile: label above a big value.
public struct StatTileModel: Equatable, Sendable {
    public let label: String
    public let value: String
    /// The fourth tile's value is drawn in the success colour.
    public let tone: StatusTone
    public init(label: String, value: String, tone: StatusTone = .neutral) { self.label = label; self.value = value; self.tone = tone }
}

/// Everything the Network tab shows, derived once from the mesh status (spec section 7). The view only lays it out.
public struct NetworkModel: Equatable, Sendable {
    /// "Network".
    public let title: String
    /// "Bluetooth mesh · 3 phones · " is `subtitle`; the status word follows with its dot.
    public let subtitle: String
    public let statusWord: String
    public let statusTone: StatusTone
    /// Card title, e.g. "Your messages can reach help".
    public let statusTitle: String
    public let statusSentence: String
    public let chain: [ChainNode]
    public let chainCaptions: [String]
    /// The chain read as one sentence for VoiceOver.
    public let chainDescription: String
    /// The 2x2 tiles in reading order: phones nearby, carried for neighbours, waiting to pass on, mesh hours / battery.
    public let tiles: [StatTileModel]
    public let gatewayOn: Bool
    public let carryOn: Bool

    public static func make(villager: VillagerStatus, stats: NetworkStats, settings: Settings, strings: Strings) -> NetworkModel {
        let s = strings.network, c = strings.common
        let chain = ChainModel.from(villager)
        let hoursLabel = MeshHoursText.labelIsBattery(stats) ? s.batteryLabel : s.meshHoursLeft
        return NetworkModel(
            title: s.networkTitle,
            subtitle: "\(s.meshWord) · \(c.phones(villager.peers)) · ",
            statusWord: c.villagerWord(villager),
            statusTone: villager.dot.tone,
            statusTitle: c.villagerTitle(villager),
            statusSentence: s.statusSentence(villager),
            chain: chain,
            chainCaptions: ChainModel.captions(chain, s),
            chainDescription: ChainModel.description(chain, s),
            tiles: [
                StatTileModel(label: s.phonesNearby, value: String(stats.phonesNearby)),
                StatTileModel(label: s.carriedForNeighbours, value: String(stats.carriedForNeighbours)),
                StatTileModel(label: s.waitingToPassOn, value: String(stats.waitingToPassOn)),
                StatTileModel(label: hoursLabel, value: MeshHoursText.text(stats, s), tone: .good),
            ],
            gatewayOn: GatewayChoice.isOn(settings.gatewayMode),
            carryOn: settings.carryInBackground
        )
    }
}

/// One permission row of Settings.
public struct PermissionRowModel: Equatable, Sendable {
    public let title: String
    public let value: String
    public let tone: StatusTone
}

/// The values shown on the Settings rows (spec section 8), derived from `Settings` and the permission states.
public struct SettingsModel: Equatable, Sendable {
    /// Native name of the app language ("हिन्दी").
    public let languageName: String
    public let areaValue: String
    public let gatewayName: String
    /// Bluetooth, Location, Microphone in that order.
    public let permissions: [PermissionRowModel]

    public static func make(settings: Settings, permissions p: PermissionStates, strings: Strings) -> SettingsModel {
        let s = strings.network
        return SettingsModel(
            languageName: AppLanguages.of(settings.effectiveLanguage).nativeName,
            areaValue: settings.areaLabel.trimmed.isEmpty ? s.areaNotSet : settings.areaLabel,
            gatewayName: GatewayChoice.name(settings.gatewayMode, s),
            permissions: [
                PermissionRowModel(title: s.permBluetooth, value: p.bluetooth.label(s), tone: p.bluetooth.tone),
                PermissionRowModel(title: s.permLocation, value: p.location.label(s), tone: p.location.tone),
                PermissionRowModel(title: s.permMicrophone, value: p.microphone.label(s), tone: p.microphone.tone),
            ]
        )
    }

    /// Longest "Your area" text the dialog accepts.
    public static let areaMax = 40

    public static func themeLabel(_ m: ThemeMode, _ s: NetworkSettingsStrings) -> String {
        switch m {
        case .light: return s.themeLight
        case .dark: return s.themeDark
        case .auto: return s.themeAuto
        }
    }

    public static func speedLabel(_ v: VoiceSpeed, _ s: NetworkSettingsStrings) -> String { v == .slow ? s.speedSlow : s.speedNormal }
}
