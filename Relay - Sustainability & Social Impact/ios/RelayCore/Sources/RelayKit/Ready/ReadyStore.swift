import Foundation
import RelayCore

/// What a stored plan was made for. The refresher fetches a new plan when any part differs from the current pack, household or language.
public struct PlanKey: Equatable, Sendable {
    public let packVersion: String
    public let householdKey: String
    public let lang: String
    public let warningId: Int64?
    public init(packVersion: String, householdKey: String, lang: String, warningId: Int64?) {
        self.packVersion = packVersion; self.householdKey = householdKey; self.lang = lang; self.warningId = warningId
    }
}

/// What the Ready screen renders: everything comes from storage, nothing from the network.
public struct ReadySnapshot: Equatable, Sendable {
    public let pack: ReadyPack?
    public let plan: ReadyPlan?
    public let planKey: PlanKey?
    /// The stored spoken plan (WAV), or nil.
    public let audio: URL?
    /// Last known district id (from a pack, or chosen by hand), or nil.
    public let district: String?
    public let notifiedWarningId: Int64?
    public let lastPackFetchMs: Int64

    /// The plan is shown only while it answers the pack's current warning.
    public var currentPlan: ReadyPlan? {
        guard let plan, let w = pack?.warning, plan.warningId == w.id else { return nil }
        return plan
    }
}

/// Pack, plan and spoken plan kept in app files (contract section 3.3); port of the Kotlin `ReadyStore`. Every file is written whole
/// through `AtomicFiles`; the `ready_state.json` pointer that names the current files is written LAST, so a crash at any point leaves
/// the previous copy readable, and files the pointer no longer names are deleted only after it was replaced.
///
/// `beforeCommit` runs between the data file and the pointer; tests use it to simulate a crash there.
public final class ReadyStore: @unchecked Sendable {
    public struct Crash: Error { public init() {} }

    private let dir: URL
    private let beforeCommit: @Sendable (String) throws -> Void
    private let lock = NSRecursiveLock()
    private static let ticksFile = "ready_ticks.json"

    private struct Pointer {
        var packFile: String?
        var planFile: String?
        var planKey: PlanKey?
        var audioFile: String?
        var district: String?
        var notifiedWarningId: Int64?
        var lastPackFetchMs: Int64 = 0

        func json() -> Data {
            var o: [String: Any] = ["last_pack_fetch_ms": lastPackFetchMs]
            if let packFile { o["pack_file"] = packFile }
            if let planFile { o["plan_file"] = planFile }
            if let k = planKey {
                var key: [String: Any] = ["pack_version": k.packVersion, "household": k.householdKey, "lang": k.lang]
                if let w = k.warningId { key["warning_id"] = w }
                o["plan_key"] = key
            }
            if let audioFile { o["audio_file"] = audioFile }
            if let district { o["district"] = district }
            if let notifiedWarningId { o["notified_warning_id"] = notifiedWarningId }
            return (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])) ?? Data()
        }
    }

    public init(dir: URL, beforeCommit: @escaping @Sendable (String) throws -> Void = { _ in }) {
        self.dir = dir; self.beforeCommit = beforeCommit
    }

    private var pointerFile: URL { dir.appendingPathComponent("ready_state.json") }

    private func file(_ name: String) -> URL { dir.appendingPathComponent(name) }

    private func pointer() -> Pointer {
        guard let bytes = AtomicFiles.readOrNull(pointerFile),
              let o = (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any] else { return Pointer() }
        func nonEmpty(_ k: String) -> String? { (o[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        var key: PlanKey?
        if let k = o["plan_key"] as? [String: Any] {
            key = PlanKey(packVersion: k["pack_version"] as? String ?? "", householdKey: k["household"] as? String ?? "",
                          lang: k["lang"] as? String ?? "", warningId: (k["warning_id"] as? NSNumber)?.int64Value)
        }
        return Pointer(packFile: nonEmpty("pack_file"), planFile: nonEmpty("plan_file"), planKey: key, audioFile: nonEmpty("audio_file"),
                       district: nonEmpty("district"), notifiedWarningId: (o["notified_warning_id"] as? NSNumber)?.int64Value,
                       lastPackFetchMs: (o["last_pack_fetch_ms"] as? NSNumber)?.int64Value ?? 0)
    }

    private func readText(_ name: String?) -> String? {
        guard let name, let b = AtomicFiles.readOrNull(file(name)) else { return nil }
        return String(decoding: b, as: UTF8.self)
    }

    public func load() -> ReadySnapshot {
        lock.lock(); defer { lock.unlock() }
        let p = pointer()
        let audio = p.audioFile.map { file($0) }.flatMap { FileManager.default.fileExists(atPath: $0.path) ? $0 : nil }
        return ReadySnapshot(pack: ReadyJson.parsePack(readText(p.packFile)), plan: ReadyJson.parsePlan(readText(p.planFile)), planKey: p.planKey,
                             audio: audio, district: p.district, notifiedWarningId: p.notifiedWarningId, lastPackFetchMs: p.lastPackFetchMs)
    }

    /// Stores a pack keyed by its `version`. `raw` is the body as the backend sent it, so fields this build does not know survive.
    public func savePack(_ raw: String, _ pack: ReadyPack, nowMs: Int64) throws {
        lock.lock(); defer { lock.unlock() }
        let name = "pack-\(safe(pack.version)).json"
        try AtomicFiles.write(file(name), [UInt8](raw.utf8))
        try beforeCommit("pack")
        var p = pointer()
        p.packFile = name; p.district = pack.district; p.lastPackFetchMs = nowMs
        try commit(p)
    }

    /// Stores a plan keyed by its `plan_id`; any spoken plan of an older plan is dropped.
    public func savePlan(_ raw: String, _ plan: ReadyPlan, key: PlanKey) throws {
        lock.lock(); defer { lock.unlock() }
        let name = "plan-\(safe(plan.planId)).json"
        try AtomicFiles.write(file(name), [UInt8](raw.utf8))
        try beforeCommit("plan")
        var p = pointer()
        if p.audioFile != audioName(plan.planId) { p.audioFile = nil }
        p.planFile = name; p.planKey = key
        try commit(p)
    }

    /// Stores the spoken plan of `planId`; ignored when that is no longer the stored plan.
    public func saveAudio(_ planId: String, _ wav: [UInt8]) throws {
        lock.lock(); defer { lock.unlock() }
        var p = pointer()
        if p.planFile != "plan-\(safe(planId)).json" { return }
        let name = audioName(planId)
        try AtomicFiles.write(file(name), wav)
        try beforeCommit("audio")
        p.audioFile = name
        try commit(p)
    }

    public func markNotified(_ warningId: Int64) throws {
        lock.lock(); defer { lock.unlock() }
        var p = pointer(); p.notifiedWarningId = warningId
        try commit(p)
    }

    /// The district the person chose by hand (no location, no pack yet). A pack later overwrites it with the backend's answer.
    public func setDistrict(_ district: String) throws {
        lock.lock(); defer { lock.unlock() }
        var p = pointer(); p.district = district
        try commit(p)
    }

    /// Remembers when a fetch was last tried (success or not), so the 3 h rule survives a restart.
    public func noteFetchAttempt(_ nowMs: Int64) throws {
        lock.lock(); defer { lock.unlock() }
        var p = pointer(); p.lastPackFetchMs = nowMs
        try commit(p)
    }

    private func commit(_ next: Pointer) throws {
        try AtomicFiles.write(pointerFile, [UInt8](next.json()))
        prune(next)
    }

    /// Deletes data files the pointer no longer names (and stray temp files). Runs only after the pointer was replaced.
    private func prune(_ p: Pointer) {
        let keep = Set([p.packFile, p.planFile, p.audioFile].compactMap { $0 } + [pointerFile.lastPathComponent, Self.ticksFile])
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for n in names {
            let ours = n.hasPrefix("pack-") || n.hasPrefix("plan-") || n.hasPrefix("audio-") || n.hasSuffix(".tmp")
            if ours && !keep.contains(n) { try? FileManager.default.removeItem(at: file(n)) }
        }
    }

    // ---- checklist ticks: kept per pack version and reset when the version changes
    public func ticks(_ packVersion: String) -> Set<Int> {
        lock.lock(); defer { lock.unlock() }
        guard let b = AtomicFiles.readOrNull(file(Self.ticksFile)),
              let o = (try? JSONSerialization.jsonObject(with: Data(b))) as? [String: Any],
              o["version"] as? String == packVersion, let a = o["done"] as? [Any] else { return [] }
        return Set(a.compactMap { ($0 as? NSNumber)?.intValue })
    }

    public func setTick(_ packVersion: String, _ index: Int, done: Bool) throws {
        lock.lock(); defer { lock.unlock() }
        var now = ticks(packVersion)
        if done { now.insert(index) } else { now.remove(index) }
        let o: [String: Any] = ["version": packVersion, "done": now.sorted()]
        try AtomicFiles.write(file(Self.ticksFile), [UInt8](try JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])))
    }

    private func audioName(_ planId: String) -> String { "audio-\(safe(planId)).wav" }

    /// A file-name-safe form of an id or version that came from the network.
    private func safe(_ s: String) -> String {
        let t = String(s.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }.prefix(64))
        return t.isEmpty ? "x" : t
    }
}
