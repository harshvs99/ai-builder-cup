import Foundation
import Observation
@preconcurrency import UserNotifications
import RelayCore
import RelayKit

/// Ready mode on this phone (contract v2 section 3): the stored pack, plan and spoken plan the screens read, and the refresh that
/// fills them. Screens render from `snapshot` (storage), so they work in airplane mode; the network only refreshes the copy.
@MainActor @Observable
final class ReadyCenter {
    private(set) var snapshot: ReadySnapshot
    /// The last refresh found neither a position nor a district: the Ready screen asks "Which district are you in?".
    private(set) var needsDistrict = false
    private(set) var refreshing = false
    /// A notification was tapped: RootView opens the Ready screen and clears it.
    var openRequested = false

    @ObservationIgnored let store: ReadyStore
    /// Set by `AppModel`: connectivity, position, language and household at the moment of a refresh, plus the backend to ask.
    @ObservationIgnored var context: (@MainActor () -> (input: RefreshInput, backend: String, key: String))?

    init(directory: URL) {
        let store = ReadyStore(dir: directory)
        self.store = store
        self.snapshot = store.load()
    }

    func reload() { snapshot = store.load() }

    func ticks(_ version: String) -> Set<Int> { store.ticks(version) }
    func setTick(_ version: String, _ index: Int, done: Bool) { try? store.setTick(version, index, done: done) }

    /// The person said which district they are in (nothing else is known yet).
    func chooseDistrict(_ id: String) {
        try? store.setDistrict(id)
        needsDistrict = false
        reload()
    }

    /// One refresh (pack, then plan, then audio) and, for a new warning, one local notification. Never throws.
    @discardableResult
    func refresh(_ trigger: RefreshTrigger) async -> RefreshResult {
        guard let ctx = context?() else { return RefreshResult(skipped: true) }
        if trigger == .manual { refreshing = true }
        defer { refreshing = false }
        let store = self.store
        let result = await Task.detached(priority: .utility) { () async -> RefreshResult in
            let refresher = ReadyRefresher(api: HttpReadyApi(baseUrl: ctx.backend, deviceKey: ctx.key), store: store)
            let r = refresher.refresh(trigger, ctx.input)
            if let w = r.newWarning {
                let district = store.load().pack?.district ?? ""
                if await ReadyNotifier.post(w, district: district, lang: ctx.input.lang) { refresher.noteNotified(w.id) }
            }
            return r
        }.value
        needsDistrict = result.needsDistrict
        if !result.skipped { reload() }
        return result
    }
}

/// The one local notification of Ready mode: "Cyclone warning for {district}. Your plan is ready." Tapping opens the Ready screen.
enum ReadyNotifier {
    static let identifier = "ready-warning"

    /// Returns true when the notification was posted (so the caller records the warning as told).
    static func post(_ w: ReadyWarning, district: String, lang: String) async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return false }
        let s = ReadyStrings(Lang.fromTag(lang) ?? .en)
        let content = UNMutableNotificationContent()
        content.title = s.notificationTitle(s.districtName(district))
        content.body = w.headline + "\n" + s.notificationText
        content.sound = .default
        content.threadIdentifier = identifier
        content.userInfo = ["open": "ready"]
        do {
            try await center.add(UNNotificationRequest(identifier: "\(identifier)-\(w.id)", content: content, trigger: nil))
            return true
        } catch {
            return false
        }
    }
}

/// Opens the Ready screen when its notification is tapped, and keeps a notification posted while the app is on screen quiet.
final class ReadyNotificationDelegate: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    var onOpenReady: @Sendable () -> Void = {}

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.notification.request.content.userInfo["open"] as? String == "ready" { onOpenReady() }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        notification.request.content.userInfo["open"] as? String == "ready" ? [.banner, .sound] : []
    }
}
