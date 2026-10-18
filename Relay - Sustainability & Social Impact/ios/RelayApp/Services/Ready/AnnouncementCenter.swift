import Foundation
import Observation
@preconcurrency import UserNotifications
import RelayCore
import RelayKit

/// "Message from the control room" (contract v2 section 5.2): the announcements this phone may show, from the mesh frames it holds and the
/// backend's list when it has signal. `cards(...)` is computed from storage with the board's rules; nothing here translates.
@MainActor @Observable
final class AnnouncementCenter {
    private(set) var held: [HeldFrame] = []
    private(set) var http: [HttpAnnouncement]
    private(set) var dismissed: Set<String>

    @ObservationIgnored let store: AnnouncementStore
    /// The ANNOUNCEMENT frames in the gossip store (set by `AppModel`).
    @ObservationIgnored var heldProvider: (@MainActor () -> [HeldFrame])?
    /// District, UI language, backend and connectivity at the moment of a fetch or notification (set by `AppModel`).
    @ObservationIgnored var context: (@MainActor () -> (district: String?, lang: Lang, backend: String, key: String, connected: Bool))?

    init(directory: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let store = AnnouncementStore(dir: directory)
        self.store = store
        self.http = store.http()
        self.dismissed = store.dismissed()
    }

    /// Re-reads the announcement frames from the gossip store (called when one arrives).
    func reloadHeld() { held = heldProvider?() ?? [] }

    func dismiss(_ id: String) { store.dismiss(id); dismissed = store.dismissed() }

    func cards(nowMs: Int64, district: String?, lang: Lang) -> [AnnouncementCard] {
        AnnouncementBoard.visible(held: held, http: http, district: district, uiLang: lang, nowMs: nowMs, dismissed: dismissed)
    }

    /// `GET /v1/announcements/active` for the phone's district (best effort). The Ready refresh calls it.
    func fetch(_ trigger: RefreshTrigger) async {
        guard let ctx = context?(), let district = ctx.district else { return }
        let now = currentTimeMs()
        guard ReadyPolicy.shouldFetchPack(trigger, connected: ctx.connected, lastFetchMs: store.lastHttpFetchMs(), nowMs: now) else { return }
        let store = self.store
        let body = await Task.detached(priority: .utility) { () -> String? in
            guard let body = try? HttpReadyApi(baseUrl: ctx.backend, deviceKey: ctx.key).activeAnnouncements(district),
                  AnnouncementJson.parseActive(body, fetchedAtMs: now) != nil else { return nil }
            try? store.saveHttp(body, fetchedAtMs: now)
            return body
        }.value
        if body != nil { http = store.http() }
    }

    /// One notification per announcement, the first time it can be shown (contract section 5.2).
    func notifyNew() async {
        guard let ctx = context?() else { return }
        let fresh = AnnouncementBoard.toNotify(cards(nowMs: currentTimeMs(), district: ctx.district, lang: ctx.lang), notified: store.notified())
        for c in fresh where await AnnouncementNotifier.post(c, lang: ctx.lang) { store.markNotified(c.announcementId) }
    }
}

enum AnnouncementNotifier {
    static func post(_ c: AnnouncementCard, lang: Lang) async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return false }
        let content = UNMutableNotificationContent()
        content.title = ReadyStrings(lang).announcementTitle
        content.body = c.text
        content.sound = .default
        content.threadIdentifier = "relay-announcements"
        do {
            try await center.add(UNNotificationRequest(identifier: "announcement-\(c.announcementId)", content: content, trigger: nil))
            return true
        } catch {
            return false
        }
    }
}
