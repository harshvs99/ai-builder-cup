import Foundation
@preconcurrency import AVFAudio
import QuartzCore
import RelayCore
import RelayKit

/// Speech to text through Gemma's audio input, for a language the phone's recogniser cannot do (Odia has no on-device
/// `SFSpeechRecognizer` model). One utterance (up to `Wav.maxSeconds`): records 16 kHz mono PCM until `Endpointer` says
/// the person stopped, then asks the model for the transcript. Port of Android `GemmaTranscriber`. Main-actor only; the
/// audio tap runs on the audio thread and only touches the lock-guarded `Capture`.
@MainActor
final class GemmaTranscriber {
    private var engine: AVAudioEngine?
    private var capture: Capture?
    private var token = 0
    private var finishing = false

    /// Records and endpoints on the audio thread. `Endpointer` is a value type; everything sits behind one lock.
    private final class Capture: @unchecked Sendable {
        private let lock = NSLock()
        private let converter: AVAudioConverter
        private let target: AVAudioFormat
        private var endpointer: Endpointer
        private var pcm = [UInt8]()
        private let limit = Wav.maxSeconds * Wav.sampleRate * 2
        private let startedAt = CACurrentMediaTime()
        private(set) var finished = false
        let onLevel: @Sendable (Float) -> Void
        let onVerdict: @Sendable (Endpointer.Verdict) -> Void

        init?(input: AVAudioFormat, endAfterSpeechMs: Int64, onLevel: @escaping @Sendable (Float) -> Void,
              onVerdict: @escaping @Sendable (Endpointer.Verdict) -> Void) {
            guard let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(Wav.sampleRate), channels: 1, interleaved: true),
                  let converter = AVAudioConverter(from: input, to: target) else { return nil }
            self.target = target; self.converter = converter
            endpointer = Endpointer(endAfterSpeechMs: endAfterSpeechMs)
            self.onLevel = onLevel; self.onVerdict = onVerdict
        }

        var heardSpeech: Bool { lock.lock(); defer { lock.unlock() }; return endpointer.heardSpeech }

        func takePCM() -> [UInt8] {
            lock.lock(); defer { lock.unlock() }
            finished = true
            return Array(pcm.prefix(limit))
        }

        func feed(_ buffer: AVAudioPCMBuffer) {
            let ratio = target.sampleRate / buffer.format.sampleRate
            let cap = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: cap) else { return }
            var consumed = false
            var err: NSError?
            converter.convert(to: out, error: &err) { _, status in
                if consumed { status.pointee = .noDataNow; return nil }
                consumed = true
                status.pointee = .haveData
                return buffer
            }
            guard err == nil, let ch = out.int16ChannelData?[0], out.frameLength > 0 else { return }
            let n = Int(out.frameLength)
            var peak: Int32 = 0
            var bytes = [UInt8](); bytes.reserveCapacity(n * 2)
            for i in 0..<n {
                let v = ch[i]
                peak = max(peak, abs(Int32(v)))
                bytes.append(UInt8(truncatingIfNeeded: v)); bytes.append(UInt8(truncatingIfNeeded: v >> 8))
            }
            let level = Float(peak) / 32768
            lock.lock()
            if finished { lock.unlock(); return }
            pcm.append(contentsOf: bytes)
            let full = pcm.count >= limit
            let verdict = endpointer.update(level: level, nowMs: Int64((CACurrentMediaTime() - startedAt) * 1000))
            lock.unlock()
            onLevel(min(1, level * 3))
            if full { onVerdict(.endMax) } else if verdict != .listening { onVerdict(verdict) }
        }
    }

    /// Starts recording. `onResult(text, error)` fires once: the transcript, or nil when nothing usable was heard.
    func start(lang: Lang, endAfterSpeechSec: Double, onLevel: @escaping @MainActor (Float) -> Void,
               onResult: @escaping @MainActor (String?, String?) -> Void) throws {
        cancel()
        token += 1
        let my = token
        finishing = false
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw NSError(domain: "GemmaTranscriber", code: 1) }
        guard let cap = Capture(
            input: format, endAfterSpeechMs: Int64(endAfterSpeechSec * 1000),
            onLevel: { lvl in Task { @MainActor in onLevel(lvl) } },
            onVerdict: { [weak self] _ in Task { @MainActor in self?.finish(my, lang: lang, onResult) } }
        ) else { throw NSError(domain: "GemmaTranscriber", code: 2) }
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { buffer, _ in cap.feed(buffer) }
        engine.prepare()
        try engine.start()
        self.engine = engine
        self.capture = cap
    }

    /// The person pressed stop: transcribe what was recorded.
    func stopNow(lang: Lang, onResult: @escaping @MainActor (String?, String?) -> Void) {
        if capture != nil { finish(token, lang: lang, onResult) }
    }

    func cancel() {
        token += 1
        finishing = false
        teardown()
        capture = nil
    }

    private func teardown() {
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        engine = nil
    }

    private func finish(_ my: Int, lang: Lang, _ onResult: @escaping @MainActor (String?, String?) -> Void) {
        guard my == token, !finishing, let cap = capture else { return }
        finishing = true
        teardown()
        let pcm = cap.takePCM()
        let heard = cap.heardSpeech
        capture = nil
        guard !pcm.isEmpty, heard else { onResult(nil, nil); return }
        let url = URL.temporaryDirectory.appending(path: "relay-speech-\(my).wav")
        do { try Data(Wav.pcmToWav(pcm)).write(to: url, options: .atomic) } catch {
            onResult(nil, "could not save the recording: \(error.localizedDescription)"); return
        }
        Task { @MainActor in
            let run = await GemmaRunner.shared.runAudio(wavPath: url.path, langHint: SpeechLogic.recognizerTag(lang))
            try? FileManager.default.removeItem(at: url)
            guard my == self.token else { return }
            let parsed = run.parsed
            let text: String?
            if run.error != nil { text = nil }
            else if parsed.jsonFound, !parsed.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { text = parsed.transcript.trimmingCharacters(in: .whitespacesAndNewlines) }
            else if !parsed.jsonFound { let r = run.raw.trimmingCharacters(in: .whitespacesAndNewlines); text = (r.isEmpty || r.hasPrefix("{")) ? nil : r }
            else { text = nil }
            onResult(text, run.error)
        }
    }
}
