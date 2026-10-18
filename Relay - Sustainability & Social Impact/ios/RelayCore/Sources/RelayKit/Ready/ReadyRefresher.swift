import Foundation

/// Why a refresh runs.
public enum RefreshTrigger: Sendable { case appOpen, networkRegained, periodic, background, manual }

/// The phone state a refresh needs: connectivity, where it is (if it knows), the language to ask for and the household.
public struct RefreshInput: Equatable, Sendable {
    public var connected: Bool
    public var lat: Double?
    public var lon: Double?
    /// UI language tag: pack checklist and plan steps are asked for in it.
    public var lang: String
    public var household: Household
    public init(connected: Bool, lat: Double?, lon: Double?, lang: String, household: Household) {
        self.connected = connected; self.lat = lat; self.lon = lon; self.lang = lang; self.household = household
    }
}

/// What a refresh did. `newWarning` is set when a warning arrived that this phone has not told the person about yet.
public struct RefreshResult: Equatable, Sendable {
    public var skipped = false
    public var packFetched = false
    public var planFetched = false
    public var audioFetched = false
    public var newWarning: ReadyWarning?
    /// True when there is neither a position nor a district: the Ready screen should ask "Which district are you in?".
    public var needsDistrict = false
    public var error: String?
    public init(skipped: Bool = false, packFetched: Bool = false, planFetched: Bool = false, audioFetched: Bool = false,
                newWarning: ReadyWarning? = nil, needsDistrict: Bool = false, error: String? = nil) {
        self.skipped = skipped; self.packFetched = packFetched; self.planFetched = planFetched; self.audioFetched = audioFetched
        self.newWarning = newWarning; self.needsDistrict = needsDistrict; self.error = error
    }
}

/// The refresh rules of contract section 3.3, as pure decisions plus an orchestrator over `ReadyApi` and `ReadyStore`.
/// Port of the Kotlin `ReadyPolicy` / `ReadyRefresher`, with the same tests.
public enum ReadyPolicy {
    /// At most one periodic fetch per 3 hours while the app runs.
    public static let periodicMs: Int64 = 3 * 3600 * 1000
    /// App open and network regained fetch at once, but not twice inside a minute (a flapping network must not hammer the backend).
    public static let debounceMs: Int64 = 60 * 1000

    public static func shouldFetchPack(_ trigger: RefreshTrigger, connected: Bool, lastFetchMs: Int64, nowMs: Int64) -> Bool {
        if !connected { return false }
        let since = nowMs - lastFetchMs
        switch trigger {
        case .manual: return true
        case .appOpen, .networkRegained: return lastFetchMs == 0 || since >= debounceMs || since < 0
        case .periodic, .background: return lastFetchMs == 0 || since >= periodicMs || since < 0
        }
    }

    /// A plan is fetched only while a warning is active, and only when the stored one is missing or made for something else.
    public static func shouldFetchPlan(_ pack: ReadyPack, stored: PlanKey?, hasPlan: Bool, household: Household, lang: String) -> Bool {
        guard let w = pack.warning else { return false }
        guard hasPlan, let stored else { return true }
        return stored.packVersion != pack.version || stored.householdKey != household.key || stored.lang != lang || stored.warningId != w.id
    }

    /// The spoken plan is fetched when the backend offers one and none is stored for this plan.
    public static func shouldFetchAudio(_ plan: ReadyPlan, hasAudio: Bool) -> Bool { plan.audioUrl != nil && !hasAudio }

    /// One local notification per new warning id.
    public static func shouldNotify(_ pack: ReadyPack, notifiedWarningId: Int64?) -> Bool {
        guard let w = pack.warning else { return false }
        return w.id != notifiedWarningId
    }
}

public final class ReadyRefresher: @unchecked Sendable {
    private let api: any ReadyApi
    private let store: ReadyStore
    private let nowMs: @Sendable () -> Int64

    public init(api: any ReadyApi, store: ReadyStore, nowMs: @escaping @Sendable () -> Int64 = { currentTimeMs() }) {
        self.api = api; self.store = store; self.nowMs = nowMs
    }

    /// Runs one refresh. Never throws: a failure is reported in `RefreshResult.error` and the stored copy stays as it was
    /// (a pack that fails to parse is not stored).
    public func refresh(_ trigger: RefreshTrigger, _ input: RefreshInput) -> RefreshResult {
        let before = store.load()
        let now = nowMs()
        if !ReadyPolicy.shouldFetchPack(trigger, connected: input.connected, lastFetchMs: before.lastPackFetchMs, nowMs: now) {
            return RefreshResult(skipped: true)
        }
        let query: PackQuery
        if let lat = input.lat, let lon = input.lon { query = .at(lat: lat, lon: lon) }
        else if let d = before.district { query = .district(d) }
        else { return RefreshResult(skipped: true, needsDistrict: true) }
        try? store.noteFetchAttempt(now) // before the call: a failing backend must not be retried in a tight loop
        let pack: ReadyPack
        do {
            let raw = try api.pack(query, lang: input.lang)
            guard let parsed = ReadyJson.parsePack(raw) else { return RefreshResult(error: "unreadable pack") }
            pack = parsed
            try store.savePack(raw, pack, nowMs: now)
        } catch {
            return RefreshResult(error: Self.describe(error))
        }
        var planFetched = false, audioFetched = false
        var err: String?
        do {
            let snap = store.load()
            if ReadyPolicy.shouldFetchPlan(pack, stored: snap.planKey, hasPlan: snap.plan != nil, household: input.household, lang: input.lang) {
                let raw = try api.plan(lang: input.lang, lat: input.lat, lon: input.lon,
                                       district: (input.lat == nil || input.lon == nil) ? pack.district : nil, household: input.household)
                if let plan = ReadyJson.parsePlan(raw) {
                    try store.savePlan(raw, plan, key: PlanKey(packVersion: pack.version, householdKey: input.household.key, lang: input.lang, warningId: pack.warning?.id))
                    planFetched = true
                } else {
                    err = "unreadable plan"
                }
            }
            let now2 = store.load()
            if let plan = now2.plan, ReadyPolicy.shouldFetchAudio(plan, hasAudio: now2.audio != nil), let url = plan.audioUrl {
                if let wav = try api.audio(url), !wav.isEmpty { try store.saveAudio(plan.planId, wav); audioFetched = true }
            }
        } catch {
            err = Self.describe(error) // the pack is already stored: the screen still works
        }
        let fresh = ReadyPolicy.shouldNotify(pack, notifiedWarningId: before.notifiedWarningId) ? pack.warning : nil
        return RefreshResult(packFetched: true, planFetched: planFetched, audioFetched: audioFetched, newWarning: fresh, error: err)
    }

    /// Call once the notification was posted, so the same warning never notifies twice.
    public func noteNotified(_ warningId: Int64) { try? store.markNotified(warningId) }

    private static func describe(_ error: Error) -> String {
        if let e = error as? ReadyApiError { return e.message }
        return "\(error)"
    }
}
