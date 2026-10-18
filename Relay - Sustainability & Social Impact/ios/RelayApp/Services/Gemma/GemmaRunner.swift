import Foundation
import os
import LiteRTLM
import RelayKit

struct GemmaLoad: Sendable { var ok: Bool; var backend: String; var loadMs: Int; var note: String }

struct GemmaRun: Sendable {
    var raw: String
    var error: String?
    var firstTokenMs: Int
    var totalMs: Int
    var backend: String
    var fellBack = false
    var parsed: ParsedNeed { GemmaNeedParser.parse(raw) }
}

/// Gemma 4 E2B on device through LiteRT-LM (audio + text). The model file is not bundled (2.6 GB): it lives at
/// `Documents/gemma-4-E2B-it.litertlm`, put there with Finder file sharing, the Files app, or the lab's Import button.
/// Each request gets a fresh Conversation (no history). Port of the Android `GemmaRunner`.
///
/// Lifecycle is automatic: `runText`/`runAudio` load the model on first use (`ensureLoaded`, gated on device RAM), the
/// model unloads itself `idleSeconds` after the last request, and `onBackgrounded` / `onMemoryWarning` (wired in
/// `RelayApp`) unload it at once. Callers never load or unload by hand; a failed load returns an error result and the
/// caller keeps using the rule extractor.
actor GemmaRunner {
    static let shared = GemmaRunner()
    static let idleSeconds = 45
    // Estimates (E2B is ~2.6 GB on disk): tune after measuring on real phones.
    private static let minTotalRAM: UInt64 = 5_000_000_000
    private static let minFreeRAM: Int = 3_000_000_000

    static let modelFileName = "gemma-4-E2B-it.litertlm"
    static var modelURL: URL { URL.documentsDirectory.appending(path: modelFileName) }

    private var engine: Engine?
    private(set) var backend = "none"

    var isLoaded: Bool { engine != nil }

    private var inFlight = 0
    private var idleTask: Task<Void, Never>?

    /// A cheap, synchronous guess for UI gating (is the mic worth showing for a language the recogniser lacks): the model
    /// file is there and the phone has the RAM. `blockedReason()` is the authoritative check, made when it is about to load.
    nonisolated static func canRunHere() -> Bool {
        FileManager.default.fileExists(atPath: modelURL.path) && ProcessInfo.processInfo.physicalMemory >= minTotalRAM
    }

    /// Why the model must not be loaded right now (too little RAM, no file), or nil when it may be.
    func blockedReason() -> String? {
        guard FileManager.default.fileExists(atPath: Self.modelURL.path) else { return "model not found at \(Self.modelURL.path)" }
        let total = ProcessInfo.processInfo.physicalMemory
        if total < Self.minTotalRAM { return "device has \(total / 1_000_000) MB RAM, needs about \(Self.minTotalRAM / 1_000_000) MB" }
        let free = os_proc_available_memory()
        if engine == nil, free < Self.minFreeRAM { return "only \(free / 1_000_000) MB available to this app right now, needs \(Self.minFreeRAM / 1_000_000) MB" }
        return nil
    }

    /// Loads if needed, unless `blockedReason` says no. Cheap when already loaded.
    func ensureLoaded() async -> GemmaLoad {
        if engine != nil { return GemmaLoad(ok: true, backend: backend, loadMs: 0, note: "already loaded") }
        if let why = blockedReason() { return GemmaLoad(ok: false, backend: "none", loadMs: 0, note: why) }
        return await load()
    }

    /// The app went to the background: give the memory back unless a request is running.
    func onBackgrounded() { if inFlight == 0 { close() } }

    /// The system is short of memory: drop the model even mid-request (that request fails and the rules take over).
    func onMemoryWarning() { close() }

    private func armIdleTimer() {
        idleTask?.cancel()
        idleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.idleSeconds))
            guard !Task.isCancelled else { return }
            await self?.closeIfIdle()
        }
    }

    private func closeIfIdle() { if inFlight == 0 { close() } }

    /// Load on demand, count the request as in flight (so the idle timer cannot unload mid-run), re-arm the idle timer.
    private func tracked(_ block: () async -> GemmaRun) async -> GemmaRun {
        inFlight += 1
        idleTask?.cancel()
        defer { inFlight -= 1; armIdleTimer() }
        let l = await ensureLoaded()
        guard l.ok else { return GemmaRun(raw: "", error: "model unavailable: \(l.note)", firstTokenMs: 0, totalMs: 0, backend: backend) }
        return await block()
    }

    /// `preferGPU` tries Metal first and falls back to CPU.
    func load(preferGPU: Bool = true) async -> GemmaLoad {
        if engine != nil { return GemmaLoad(ok: true, backend: backend, loadMs: 0, note: "already loaded") }
        guard FileManager.default.fileExists(atPath: Self.modelURL.path) else {
            return GemmaLoad(ok: false, backend: "none", loadMs: 0, note: "model not found at \(Self.modelURL.path)")
        }
        var notes = ""
        for useGPU in preferGPU ? [true, false] : [false] {
            let t0 = ContinuousClock.now
            do {
                let config = try EngineConfig(
                    modelPath: Self.modelURL.path, backend: useGPU ? .gpu : .cpu(), audioBackend: .cpu(),
                    maxNumTokens: 3072, cacheDir: NSTemporaryDirectory())
                let e = Engine(engineConfig: config)
                try await e.initialize()
                engine = e
                backend = useGPU ? "GPU" : "CPU"
                return GemmaLoad(ok: true, backend: backend, loadMs: Self.ms(since: t0), note: notes)
            } catch {
                notes += "backend \(useGPU ? "gpu" : "cpu") failed: \(error); "
            }
        }
        return GemmaLoad(ok: false, backend: "none", loadMs: 0, note: notes)
    }

    func close() { engine = nil; backend = "none" }

    func runText(_ text: String, langHint: String = "en") async -> GemmaRun {
        await tracked { await withFallback { [self] in await once([.text(GemmaPrompt.textInstruction(text, langHint: langHint))]) } }
    }

    /// `wavPath`: 16 kHz mono PCM16 WAV file.
    func runAudio(wavPath: String, langHint: String = "en") async -> GemmaRun {
        await tracked {
            await withFallback { [self] in
                await once([.audioFile(wavPath), .text(GemmaPrompt.audioInstruction(langHint: langHint))])
            }
        }
    }

    /// A request with its own `system` prompt and the `instruction` sent as is (triage turns). A conversation cannot wait the
    /// minutes a batch intake can: callers race it against their own short timeout and fall back to the rules.
    func runCustom(system: String, instruction: String) async -> GemmaRun {
        await tracked { await withFallback { [self] in await once([.text(instruction)], system: system) } }
    }

    private func withFallback(_ block: () async -> GemmaRun) async -> GemmaRun {
        let r = await block()
        if r.error != nil, backend == "GPU" {
            close()
            if await load(preferGPU: false).ok {
                var again = await block()
                again.fellBack = true
                return again
            }
        }
        return r
    }

    private func once(_ parts: [Content], system: String = GemmaPrompt.system) async -> GemmaRun {
        guard let engine else { return GemmaRun(raw: "", error: "engine not loaded", firstTokenMs: 0, totalMs: 0, backend: backend) }
        let t0 = ContinuousClock.now
        do {
            let sampler = try SamplerConfig(topK: 1, topP: 1.0, temperature: 0.0)
            let conversation = try await engine.createConversation(
                with: ConversationConfig(systemMessage: Message(system), samplerConfig: sampler))
            var out = ""
            var first = -1
            for try await chunk in conversation.sendMessageStream(Message(contents: parts)) {
                if first < 0 { first = Self.ms(since: t0) }
                out += chunk.toString
            }
            let total = Self.ms(since: t0)
            return GemmaRun(raw: out, error: nil, firstTokenMs: first < 0 ? total : first, totalMs: total, backend: backend)
        } catch {
            return GemmaRun(raw: "", error: "\(error)", firstTokenMs: 0, totalMs: Self.ms(since: t0), backend: backend)
        }
    }

    private static func ms(since t0: ContinuousClock.Instant) -> Int {
        let d = ContinuousClock.now - t0
        return Int(d.components.seconds) * 1000 + Int(d.components.attoseconds / 1_000_000_000_000_000)
    }
}
