import Foundation

/// SPEC section 2 transport constants. UUIDs are lower-case strings; hand them to `CBUUID(string:)`.
public enum BleConstants {
    public static let serviceUuid = "5b9b5c02-f1b0-4ea1-9e8a-e08da2708c44"
    public static let charUuid = "77175a01-40b4-447c-b66e-0cae864e64d5"
    /// Client Characteristic Configuration descriptor (Android subscribes by writing it; CoreBluetooth does it in
    /// `setNotifyValue`).
    public static let cccdUuid = "00002902-0000-1000-8000-00805f9b34fb"

    /// Scan-response manufacturer data id (0xFFFF = reserved for testing); the payload is our 8-byte node id.
    /// iOS cannot read it for a backgrounded advertiser and cannot set it when advertising, so treat it as optional.
    public static let manufacturerId = 0xFFFF

    public static let requestedMtu = 517
    public static let minMtu = 185
    public static let attOverhead = 3
    public static let defaultMtu = 23

    public static let maxCentralLinks = 4
    public static let maxTotalLinks = 7

    public static let opTimeoutMs: Int64 = 5_000
    public static let setupTimeoutMs: Int64 = 25_000
    public static let peripheralSubscribeTimeoutMs: Int64 = 12_000

    public static let backoffMinMs: Int64 = 2_000
    public static let backoffMaxMs: Int64 = 60_000
    public static let allowlistIgnoreMs: Int64 = 60_000
    public static let duplicateIgnoreMs: Int64 = 10_000

    /// Android allows 5 scan starts per 30 s window per app (iOS has no such limit; the throttle is kept for parity).
    public static let scanStartsPerWindow = 5
    public static let scanWindowMs: Int64 = 30_000
}

/// Link ids are `c:<address>` for a link where we are the central and `p:<address>` where we are the peripheral.
/// `<address>` is a MAC on Android; on iOS use the `CBPeripheral` / `CBCentral` identifier UUID string.
public enum LinkId {
    public static func central(_ address: String) -> String { "c:" + address }
    public static func peripheral(_ address: String) -> String { "p:" + address }
    public static func isCentral(_ linkId: String) -> Bool { linkId.hasPrefix("c:") }
    public static func address(_ linkId: String) -> String { String(linkId.dropFirst(2)) }
}
