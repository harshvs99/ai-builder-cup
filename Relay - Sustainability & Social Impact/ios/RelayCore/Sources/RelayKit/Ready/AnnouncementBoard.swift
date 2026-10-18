import Foundation
import RelayCore

/// An ANNOUNCEMENT frame the phone holds, with when it first stored it.
public struct HeldFrame: Equatable, Sendable {
    public let announcement: Announcement
    public let receivedAtMs: Int64
    public init(announcement: Announcement, receivedAtMs: Int64) { self.announcement = announcement; self.receivedAtMs = receivedAtMs }
}

/// One active announcement from `GET /v1/announcements/active`: the full text per approved language.
public struct HttpAnnouncement: Equatable, Sendable {
    public let announcementId: String
    public let district: String
    public let approvedAt: Int64
    public let texts: [Lang: String]
    public let fetchedAtMs: Int64
    public init(announcementId: String, district: String, approvedAt: Int64, texts: [Lang: String], fetchedAtMs: Int64) {
        self.announcementId = announcementId; self.district = district; self.approvedAt = approvedAt
        self.texts = texts; self.fetchedAtMs = fetchedAtMs
    }
}

/// What the Home card shows: one announcement, in one language. `fromHttp` true = the full text rather than the mesh's short form.
public struct AnnouncementCard: Equatable, Sendable, Identifiable {
    public let announcementId: String
    public let lang: Lang
    public let text: String
    public let approvedAt: Int64
    public let fromHttp: Bool
    public var id: String { announcementId }
}

/// Which announcements a phone shows, and in which language (SPEC 5.7, contract v2 section 5.2); port of Kotlin `AnnouncementBoard`.
/// A phone relays every frame; it SHOWS one only when its district is the phone's stored district (or the phone has none) and its
/// language is the UI language, or the English one when no frame in the UI language exists for that `announcement_id` after
/// 10 minutes. WITHDRAWN removes the card and shows nothing.
public enum AnnouncementBoard {
    /// How long the phone waits for the UI-language frame before it shows the English one.
    public static let fallbackWaitMs: Int64 = 10 * 60 * 1000
    public static let maxCards = 3

    private struct Entry { let text: String; let fromHttp: Bool }
    private struct Group {
        var approvedAt: Int64 = 0
        var firstSeenMs: Int64 = Int64.max
        var active: [Lang: Entry] = [:]
        var present = Set<Lang>() // languages with any frame at all, active or withdrawn
        var withdrawn = Set<Lang>()
    }

    public static func visible(
        held: [HeldFrame], http: [HttpAnnouncement], district: String?, uiLang: Lang, nowMs: Int64, dismissed: Set<String> = []
    ) -> [AnnouncementCard] {
        let nowSec = nowMs / 1000
        var order: [String] = []
        var groups: [String: Group] = [:]
        func touch(_ id: String) { if groups[id] == nil { groups[id] = Group(); order.append(id) } }

        for h in held {
            let a = h.announcement
            if !a.isLive(nowSec: nowSec) { continue }
            if let district, AnnouncementDistricts.idOf(a.district) != district { continue }
            touch(a.idHex)
            groups[a.idHex]!.approvedAt = max(groups[a.idHex]!.approvedAt, a.approvedAt)
            groups[a.idHex]!.firstSeenMs = min(groups[a.idHex]!.firstSeenMs, h.receivedAtMs)
            groups[a.idHex]!.present.insert(a.lang)
            if a.status == .withdrawn { groups[a.idHex]!.withdrawn.insert(a.lang) }
            else if groups[a.idHex]!.active[a.lang] == nil { groups[a.idHex]!.active[a.lang] = Entry(text: a.text, fromHttp: false) }
        }
        for h in http {
            if nowSec - h.approvedAt > AnnouncementCodec.ttlSec { continue }
            if let district, h.district != district { continue }
            touch(h.announcementId)
            groups[h.announcementId]!.approvedAt = max(groups[h.announcementId]!.approvedAt, h.approvedAt)
            groups[h.announcementId]!.firstSeenMs = min(groups[h.announcementId]!.firstSeenMs, h.fetchedAtMs)
            for (lang, text) in h.texts {
                groups[h.announcementId]!.present.insert(lang)
                groups[h.announcementId]!.active[lang] = Entry(text: text, fromHttp: true) // the full text wins over the mesh's short form
            }
        }

        var cards: [AnnouncementCard] = []
        for id in order {
            let g = groups[id]!
            if dismissed.contains(id) { continue }
            let lang: Lang
            if g.present.contains(uiLang) {
                lang = uiLang // the UI-language frame decides; if it was withdrawn nothing shows
            } else if g.present.contains(.en) && nowMs - g.firstSeenMs >= fallbackWaitMs {
                lang = .en
            } else { continue }
            if g.withdrawn.contains(lang) { continue }
            guard let e = g.active[lang] else { continue }
            cards.append(AnnouncementCard(announcementId: id, lang: lang, text: e.text, approvedAt: g.approvedAt, fromHttp: e.fromHttp))
        }
        cards.sort { a, b in a.approvedAt != b.approvedAt ? a.approvedAt > b.approvedAt : a.announcementId < b.announcementId }
        return Array(cards.prefix(maxCards))
    }

    /// The ANNOUNCEMENT frames in a packet store (the gossip store, persisted by the app), as the board takes them. Undecodable ones are skipped.
    public static func heldFrom(_ store: any PacketStore) -> [HeldFrame] {
        store.all().filter { $0.type == FrameTypes.announcement }.compactMap { p in
            guard let f = FrameCodec.decode(p.raw), let a = AnnouncementCodec.decode(f.payload) else { return nil }
            return HeldFrame(announcement: a, receivedAtMs: p.expiryBasisMs)
        }
    }

    /// The cards the person has not been told about yet: one notification per announcement, on first receipt.
    public static func toNotify(_ cards: [AnnouncementCard], notified: Set<String>) -> [AnnouncementCard] {
        cards.filter { !notified.contains($0.announcementId) }
    }
}

public enum AnnouncementJson {
    /// Parses `GET /v1/announcements/active`. An entry without an `announcement_id` (approved before mesh delivery existed) gets `http-<id>`.
    public static func parseActive(_ json: String?, fetchedAtMs: Int64) -> [HttpAnnouncement]? {
        guard let json, let data = json.data(using: .utf8), let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = root["announcements"] as? [Any] else { return nil }
        return arr.compactMap { item -> HttpAnnouncement? in
            guard let o = item as? [String: Any], let texts = o["texts"] as? [String: Any] else { return nil }
            var map: [Lang: String] = [:]
            for (k, v) in texts {
                guard let lang = Lang.fromTag(k), let t = (v as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { continue }
                map[lang] = t
            }
            if map.isEmpty { return nil }
            let explicit = (o["announcement_id"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
            let id = explicit ?? "http-" + ((o["id"] as? NSNumber).map { String($0.int64Value) } ?? (o["id"] as? String) ?? "")
            return HttpAnnouncement(announcementId: id, district: o["district"] as? String ?? "",
                                    approvedAt: (o["approved_at"] as? NSNumber)?.int64Value ?? 0, texts: map, fetchedAtMs: fetchedAtMs)
        }
    }
}

/// What the announcement cards need to remember between runs: the last HTTP list (the phone's own copy of what it fetched), the ids the
/// person dismissed, and the ids it already notified about. Whole-file atomic writes; a damaged file reads as empty.
public final class AnnouncementStore: @unchecked Sendable {
    private let dir: URL
    private let lock = NSLock()
    private static let maxRemembered = 200

    public init(dir: URL) { self.dir = dir }

    private var httpFile: URL { dir.appendingPathComponent("announcements_http.json") }
    private var stateFile: URL { dir.appendingPathComponent("announcements_state.json") }

    public func saveHttp(_ raw: String, fetchedAtMs: Int64) throws {
        lock.lock(); defer { lock.unlock() }
        let o: [String: Any] = ["fetched_at_ms": fetchedAtMs, "body": raw]
        try AtomicFiles.write(httpFile, [UInt8](try JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])))
    }

    private func httpObject() -> [String: Any]? {
        guard let b = AtomicFiles.readOrNull(httpFile) else { return nil }
        return (try? JSONSerialization.jsonObject(with: Data(b))) as? [String: Any]
    }

    public func http() -> [HttpAnnouncement] {
        lock.lock(); defer { lock.unlock() }
        guard let o = httpObject(), let body = o["body"] as? String, let at = (o["fetched_at_ms"] as? NSNumber)?.int64Value else { return [] }
        return AnnouncementJson.parseActive(body, fetchedAtMs: at) ?? []
    }

    public func lastHttpFetchMs() -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return (httpObject()?["fetched_at_ms"] as? NSNumber)?.int64Value ?? 0
    }

    public func dismissed() -> Set<String> { lock.lock(); defer { lock.unlock() }; return Set(ids("dismissed")) }
    public func notified() -> Set<String> { lock.lock(); defer { lock.unlock() }; return Set(ids("notified")) }
    public func dismiss(_ id: String) { lock.lock(); defer { lock.unlock() }; addId("dismissed", id) }
    public func markNotified(_ id: String) { lock.lock(); defer { lock.unlock() }; addId("notified", id) }

    private func state() -> [String: Any] {
        guard let b = AtomicFiles.readOrNull(stateFile), let o = (try? JSONSerialization.jsonObject(with: Data(b))) as? [String: Any] else { return [:] }
        return o
    }

    private func ids(_ key: String) -> [String] { ((state()[key] as? [Any]) ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty } }

    private func addId(_ key: String, _ id: String) {
        var o = state()
        var list = ids(key)
        if !list.contains(id) { list.append(id) }
        o[key] = Array(list.suffix(Self.maxRemembered)) // old ids fall out; announcements die after 72 h anyway
        if let data = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) { try? AtomicFiles.write(stateFile, [UInt8](data)) }
    }
}
