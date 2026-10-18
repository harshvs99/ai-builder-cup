import Foundation
import Observation
@preconcurrency import AVFAudio
@preconcurrency import Speech
import RelayCore
import RelayKit

/// What the voice panel shows (Android `VoiceState`).
enum VoiceState: Equatable, Sendable {
    case idle
    /// [level] is 0...1 mic loudness; [note] is an optional fallback-language hint ("Waray voice uses Filipino").
    case listening(partial: String, level: Float, note: String?)
    case problem(text: String, offerVoiceSettings: Bool, offerAppSettings: Bool)

    var isListening: Bool { if case .listening = self { return true } else { return false } }
}

/// Speech-to-text with `SFSpeechRecognizer` + `AVAudioEngine`, always ON DEVICE (`requiresOnDeviceRecognition`): Relay
/// is for places without a network, and nothing the user says leaves the phone. A language with no on-device model
/// (Odia) is reported as a `.problem` so the screen falls back to typing; ask `isOnDeviceAvailable(_:)` up front to
/// hide the mic instead. Errors go through `SpeechLogic.onError` exactly like Android.
///
/// Main-actor only. Audio and recogniser callbacks arrive on other threads; they are built in `nonisolated` helpers
/// (so Swift 6 does not assume they run on the main actor) and hop back with a `Task { @MainActor in }`.
@MainActor @Observable
final class SpeechInput {
    private(set) var state: VoiceState = .idle

    /// Receives the final transcript. Set by `AppModel`.
    @ObservationIgnored var onText: ((String) -> Void)?
    @ObservationIgnored private let log: @Sendable (String) -> Void

    /// Stop by itself this long after the user went quiet (iOS never ends dictation on its own).
    private static let silenceAfterSpeechSec = 2.5
    /// Give up when nothing was heard at all.
    private static let noSpeechSec = 9.0
    /// Hard cap on one utterance.
    private static let maxUtteranceSec = 60.0
    /// How long to wait for the final result after `endAudio()` before using the last partial.
    private static let finalGraceSec = 2.0

    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var lang: Lang = .en
    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    @ObservationIgnored private var recognizer: SFSpeechRecognizer?
    @ObservationIgnored private var lastPartial = ""
    @ObservationIgnored private var lastLevelAt: TimeInterval = 0
    @ObservationIgnored private var timers: [Task<Void, Never>] = []

    init(log: @escaping @Sendable (String) -> Void) { self.log = log }

    // ---------------------------------------------------------------- availability

    /// True when this phone can recognise [lang] without the network.
    static func isOnDeviceAvailable(_ lang: Lang) -> Bool {
        guard let r = SFSpeechRecognizer(locale: Locale(identifier: SpeechLogic.recognizerTag(lang))) else { return false }
        return r.supportsOnDeviceRecognition
    }

    // ---------------------------------------------------------------- control

    /// Begins listening. Microphone and speech-recognition permission must already be granted (see `Permissions`).
    func start(lang: Lang) {
        cancel()
        self.lang = lang
        generation += 1
        let gen = generation
        let tag = SpeechLogic.recognizerTag(lang)

        guard SFSpeechRecognizer.authorizationStatus() == .authorized,
              AVAudioApplication.shared.recordPermission == .granted else {
            fail(SpeechLogic.errorInsufficientPermissions, offlineAttempt: true)
            return
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: tag)), recognizer.supportsOnDeviceRecognition else {
            log("voice: no on-device recogniser for \(tag)")
            fail(SpeechLogic.errorLanguageNotSupported, offlineAttempt: true)
            return
        }
        guard recognizer.isAvailable else {
            log("voice: recogniser for \(tag) not available right now")
            fail(SpeechLogic.errorLanguageUnavailable, offlineAttempt: true)
            return
        }
        recognizer.defaultTaskHint = .dictation

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.requiresOnDeviceRecognition = true
        req.taskHint = .dictation
        req.addsPunctuation = false

        let engine = AVAudioEngine()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else { throw SpeechInputError.noInput }
            input.installTap(onBus: 0, bufferSize: 1024, format: format,
                             block: SpeechInput.makeTap(request: req) { [weak self] level in
                                 Task { @MainActor in self?.audioLevel(level, gen: gen) }
                             })
            engine.prepare()
            try engine.start()
        } catch {
            log("voice: audio engine failed: \(error)")
            engine.inputNode.removeTap(onBus: 0)
            deactivateSession()
            fail(SpeechLogic.errorAudio, offlineAttempt: true)
            return
        }

        self.recognizer = recognizer
        self.engine = engine
        self.request = req
        lastPartial = ""
        state = .listening(partial: "", level: 0, note: SpeechLogic.fallbackNote(lang))
        log("voice: start lang=\(tag) onDevice=true")

        task = SpeechInput.makeTask(recognizer: recognizer, request: req) { [weak self] update in
            Task { @MainActor in self?.handle(update, gen: gen) }
        }

        schedule(after: SpeechInput.noSpeechSec) { [weak self] in
            guard let self, self.generation == gen, self.lastPartial.isEmpty, self.state.isListening else { return }
            self.finishNoSpeech()
        }
        schedule(after: SpeechInput.maxUtteranceSec) { [weak self] in
            guard let self, self.generation == gen, self.state.isListening else { return }
            self.stop()
        }
    }

    /// Stop recording and deliver what was heard so far.
    func stop() {
        guard state.isListening, let request else { return }
        let gen = generation
        stopAudio()
        request.endAudio()
        schedule(after: SpeechInput.finalGraceSec) { [weak self] in
            guard let self, self.generation == gen, self.state.isListening else { return }
            self.deliver(self.lastPartial)
        }
    }

    /// Abort without a result and return to idle.
    func cancel() {
        generation += 1
        release()
        state = .idle
    }

    func dismiss() { if !state.isListening { state = .idle } }

    /// Show a problem that happened before recording could start (e.g. the microphone permission was denied).
    func fail(text: String, offerAppSettings: Bool = false) {
        cancel()
        state = .problem(text: text, offerVoiceSettings: false, offerAppSettings: offerAppSettings)
    }

    // ---------------------------------------------------------------- internals

    private enum SpeechInputError: Error { case noInput }

    /// What the recogniser said, reduced to Sendable values on the recogniser's thread.
    private struct Update: Sendable {
        let text: String?
        let isFinal: Bool
        let errorCode: Int?
    }

    private func fail(_ code: Int, offlineAttempt: Bool) {
        let d = SpeechLogic.onError(code, lang: lang, offlineAttempt: offlineAttempt, online: false, alreadyRetried: true)
        log("voice: error \(code) -> \(d.kind)")
        release()
        state = .problem(text: d.message ?? "", offerVoiceSettings: d.offerVoiceSettings, offerAppSettings: d.kind == .permission)
    }

    private func finishNoSpeech() {
        let d = SpeechLogic.onError(SpeechLogic.errorNoMatch, lang: lang, offlineAttempt: true, online: false, alreadyRetried: true)
        release()
        state = .problem(text: d.message ?? "", offerVoiceSettings: false, offerAppSettings: false)
    }

    private func audioLevel(_ level: Float, gen: Int) {
        guard gen == generation, case .listening(let partial, _, let note) = state else { return }
        let t = Date.timeIntervalSinceReferenceDate
        if t - lastLevelAt < 0.08 { return }
        lastLevelAt = t
        state = .listening(partial: partial, level: level, note: note)
    }

    private func handle(_ u: Update, gen: Int) {
        guard gen == generation else { return }
        if let text = u.text, !text.isEmpty {
            lastPartial = text
            if case .listening(_, let level, let note) = state {
                state = .listening(partial: text, level: level, note: note)
            }
            if !u.isFinal { armSilenceTimer(gen) }
        }
        if u.isFinal {
            deliver(u.text ?? lastPartial)
            return
        }
        if let code = u.errorCode {
            // A failure after speech was heard: use what we have (cancellation also lands here after stop()).
            if !lastPartial.isEmpty { deliver(lastPartial); return }
            if code == Self.cancelledCode { return }
            fail(code, offlineAttempt: true)
        }
    }

    private static let cancelledCode = -1

    private func deliver(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        release()
        if t.isEmpty {
            let d = SpeechLogic.onError(SpeechLogic.errorNoMatch, lang: lang, offlineAttempt: true, online: false, alreadyRetried: true)
            state = .problem(text: d.message ?? "", offerVoiceSettings: false, offerAppSettings: false)
        } else {
            log("voice: result \"\(t)\"")
            state = .idle
            onText?(t)
        }
    }

    @ObservationIgnored private var silenceTask: Task<Void, Never>?

    private func armSilenceTimer(_ gen: Int) {
        silenceTask?.cancel()
        silenceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(SpeechInput.silenceAfterSpeechSec * 1_000_000_000))
            guard !Task.isCancelled, let self, self.generation == gen, self.state.isListening else { return }
            self.stop()
        }
    }

    private func schedule(after seconds: Double, _ body: @escaping @MainActor () -> Void) {
        let t = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            if !Task.isCancelled { body() }
        }
        timers.append(t)
    }

    private func stopAudio() {
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        engine = nil
    }

    private func release() {
        for t in timers { t.cancel() }
        timers.removeAll()
        silenceTask?.cancel()
        silenceTask = nil
        stopAudio()
        task?.cancel()
        task = nil
        request = nil
        recognizer = nil
        deactivateSession()
    }

    private func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // ---------------------------------------------------------------- nonisolated builders (off-main callbacks)

    /// The audio tap runs on a real-time audio thread: feeds the recogniser and reports a 0...1 level.
    private nonisolated static func makeTap(
        request: SFSpeechAudioBufferRecognitionRequest, onLevel: @escaping @Sendable (Float) -> Void
    ) -> AVAudioNodeTapBlock {
        return { buffer, _ in
            request.append(buffer)
            onLevel(SpeechInput.level(of: buffer))
        }
    }

    private nonisolated static func level(of buffer: AVAudioPCMBuffer) -> Float {
        guard let data = buffer.floatChannelData?[0] else { return 0 }
        let n = Int(buffer.frameLength)
        if n == 0 { return 0 }
        var sum: Float = 0
        for i in 0..<n { sum += data[i] * data[i] }
        let rms = (sum / Float(n)).squareRoot()
        let db = 20 * log10(max(rms, 1e-7))
        return min(1, max(0, (db + 50) / 40))
    }

    private nonisolated static func makeTask(
        recognizer: SFSpeechRecognizer, request: SFSpeechAudioBufferRecognitionRequest,
        onUpdate: @escaping @Sendable (Update) -> Void
    ) -> SFSpeechRecognitionTask {
        recognizer.recognitionTask(with: request) { result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            onUpdate(Update(text: text, isFinal: isFinal, errorCode: error.map(SpeechInput.mapError)))
        }
    }

    /// Maps a recogniser failure onto the Android `SpeechRecognizer.ERROR_*` codes `SpeechLogic` understands.
    /// Best effort: the Speech framework documents few codes. Known ones: `kAFAssistantErrorDomain` 1110 "No speech
    /// detected", 1101/1107 local recogniser or model unavailable; `kLSRErrorDomain` 301 "Recognition request was
    /// canceled" (returned as `cancelledCode`, ignored).
    private nonisolated static func mapError(_ error: Error) -> Int {
        let ns = error as NSError
        switch (ns.domain, ns.code) {
        case ("kLSRErrorDomain", 301), ("kAFAssistantErrorDomain", 216), ("kAFAssistantErrorDomain", 209):
            return -1
        case ("kAFAssistantErrorDomain", 1110), ("kAFAssistantErrorDomain", 203):
            return SpeechLogic.errorNoMatch
        case ("kAFAssistantErrorDomain", 1101), ("kAFAssistantErrorDomain", 1107), ("kAFAssistantErrorDomain", 1700):
            return SpeechLogic.errorLanguageUnavailable
        default:
            let msg = ns.localizedDescription.lowercased()
            if msg.contains("network") || msg.contains("internet") { return SpeechLogic.errorNetwork }
            if msg.contains("permission") || msg.contains("authoriz") { return SpeechLogic.errorInsufficientPermissions }
            return SpeechLogic.errorClient
        }
    }
}
