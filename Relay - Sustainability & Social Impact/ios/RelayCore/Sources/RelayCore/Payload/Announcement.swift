import Foundation

/// Whether an `Announcement` frame shows a message or takes it back (SPEC 5.7).
public enum AnnouncementStatus: Int, Sendable, Hashable {
    case active = 0, withdrawn = 1

    public var code: Int { rawValue }
    public static func fromCode(_ c: Int) -> AnnouncementStatus? { AnnouncementStatus(rawValue: c) }
}

/// The wire index of a district (SPEC 5.7). Append only; the backend's `codec.DISTRICT_CODES` and the golden vectors pin it.
public enum AnnouncementDistricts {
    public static let ids: [String] = ["puri", "delhi"]

    public static func indexOf(_ id: String?) -> Int? {
        guard let id else { return nil }
        return ids.firstIndex(of: id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }
    /// The district id for a wire index, or nil for one this build does not know (it still relays such a frame).
    public static func idOf(_ index: Int) -> String? { ids.indices.contains(index) ? ids[index] : nil }
}

/// ANNOUNCEMENT payload (SPEC 5.7): a duty officer's words, translated and approved per language, to every phone in a
/// district. NOT the disaster declaration of SPEC 5.6 (`Broadcast`). `district` is the wire index; a decoder keeps any
/// value, a phone only shows the frames of its own district.
public struct Announcement: Equatable, Hashable, Sendable {
    public var announcementId: [UInt8]
    public var lang: Lang
    public var district: Int
    public var status: AnnouncementStatus
    /// unix seconds, u32
    public var approvedAt: Int64
    /// unix seconds, u32; 0 = none
    public var expiresAt: Int64
    /// <= 139 bytes of UTF-8, already in `lang`.
    public var text: String

    public init(announcementId: [UInt8], lang: Lang, district: Int, status: AnnouncementStatus, approvedAt: Int64,
                expiresAt: Int64, text: String) {
        self.announcementId = announcementId; self.lang = lang; self.district = district; self.status = status
        self.approvedAt = approvedAt; self.expiresAt = expiresAt; self.text = text
    }

    public var idHex: String { Hex.encode(announcementId) }
    /// Dedupe key in the gossip store: SHA-256(announcement_id || lang || status), first 8 bytes. A WITHDRAWN frame is a new frame.
    public var keyHex: String { Hex.encode(AnnouncementCodec.key(announcementId, lang, status)) }

    /// Inside its life: not past `expiresAt` and not older than 72 h. A phone's clock may be wrong, so a future `approvedAt` is not refused.
    public func isLive(nowSec: Int64) -> Bool {
        (expiresAt == 0 || nowSec < expiresAt) && nowSec - approvedAt <= AnnouncementCodec.ttlSec
    }
}

public enum AnnouncementCodec {
    public static let version = 1
    public static let maxText = 139
    public static let fixedSize = 20
    /// 20 + 1 + 139 = 160 bytes, so the frame (22 + 160) is exactly the 182-byte single-write limit.
    public static let maxPayload = fixedSize + 1 + maxText
    /// Stored like an ACK: dropped 72 h after approval (and 72 h after first storage by the gossip store).
    public static let ttlSec: Int64 = 72 * 3600

    public static func key(_ announcementId: [UInt8], _ lang: Lang, _ status: AnnouncementStatus) -> [UInt8] {
        Array(Sha256.digest(announcementId + [UInt8(lang.code), UInt8(status.code)]).prefix(8))
    }

    public static func encode(_ a: Announcement) throws -> [UInt8] {
        guard a.announcementId.count == 8 else { throw IllegalArgumentError("announcement_id must be 8 bytes") }
        guard (0...0xFF).contains(a.district) else { throw IllegalArgumentError("district out of u8 range") }
        guard (0...0xFFFF_FFFF).contains(a.approvedAt) else { throw IllegalArgumentError("approved_at out of u32 range") }
        guard (0...0xFFFF_FFFF).contains(a.expiresAt) else { throw IllegalArgumentError("expires_at out of u32 range") }
        let text = Utf8.truncateToBytes(a.text, maxText)
        var w = ByteWriter(capacity: fixedSize + 1 + text.count)
        w.u8(version)
        w.put(a.announcementId)
        w.u8(a.lang.code)
        w.u8(a.district)
        w.u8(a.status.code)
        w.u32(a.approvedAt)
        w.u32(a.expiresAt)
        w.u8(text.count)
        w.put(text)
        return w.bytes
    }

    /// Nil if invalid (SPEC 5.7 validation). Never traps.
    public static func decode(_ p: [UInt8]) -> Announcement? {
        if p.count < fixedSize + 1 || p.count > maxPayload { return nil }
        if Int(p[0]) != version { return nil }
        var r = ByteReader(p, at: 1)
        let id = r.take(8)
        guard let lang = Lang.fromCode(r.u8()) else { return nil }
        let district = r.u8()
        guard let status = AnnouncementStatus.fromCode(r.u8()) else { return nil }
        let approvedAt = r.u32()
        let expiresAt = r.u32()
        guard let text = NeedCodec.readString(&r, max: maxText) else { return nil }
        if r.hasRemaining || !r.ok { return nil }
        return Announcement(announcementId: id, lang: lang, district: district, status: status,
                            approvedAt: approvedAt, expiresAt: expiresAt, text: text)
    }
}
