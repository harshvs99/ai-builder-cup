import Foundation
import Observation
@preconcurrency import AVFoundation
import RelayCore
import RelayKit

/// Text-to-speech for "Hear it" buttons and read-aloud (Android `Speaker.kt`), on `AVSpeechSynthesizer`.
///
/// Voice choice per Relay language, first match wins; Waray and Cebuano have no iOS voice and fall back to the
/// Filipino one (`usesFallbackVoice`), Odia has none at all (`isVoiceAvailable` is false, so the screen hides the
/// button instead of reading Odia text in the wrong voice). Main-actor only.
@MainActor @Observable
final class Speaker {
    /// True while an utterance is being spoken.
    private(set) var isSpeaking = false

    /// Speed used by `speak` when none is given; `AppModel` keeps it equal to the Settings voice speed.
    @ObservationIgnored var speed: VoiceSpeed = .normal

    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private var delegate: SpeakerDelegate?
    /// The utterance in flight; end callbacks of an earlier (stopped) utterance arrive late and must be ignored.
    @ObservationIgnored private var current: ObjectIdentifier?

    init() {
        let d = SpeakerDelegate { [weak self] id in
            guard let self, self.current == id else { return }
            self.finished()
        }
        delegate = d
        synthesizer.delegate = d
    }

    // ---------------------------------------------------------------- voices

    /// Language tags to try for [lang], best first (BCP-47, as `AVSpeechSynthesisVoice.language` reports them).
    nonisolated static func voiceTags(_ lang: Lang) -> [String] {
        switch lang {
        case .en: return ["en-IN", "en-GB", "en-US", "en-AU"]
        case .hi: return ["hi-IN"]
        case .or: return ["or-IN"]
        case .tl: return ["fil-PH", "tl-PH"]
        case .war: return ["war-PH", "fil-PH"]
        case .ceb: return ["ceb-PH", "fil-PH"]
        }
    }

    /// The installed voice for [lang]: the first tag that has any voice, preferring higher quality (enhanced/premium).
    nonisolated static func voice(for lang: Lang) -> AVSpeechSynthesisVoice? {
        let all = AVSpeechSynthesisVoice.speechVoices()
        for tag in voiceTags(lang) {
            let matches = all.filter { $0.language.replacingOccurrences(of: "_", with: "-").lowercased() == tag.lowercased() }
            if let best = matches.max(by: { $0.quality.rawValue < $1.quality.rawValue }) { return best }
        }
        return nil
    }

    /// True when this phone has a voice for [lang] (so "Hear it" can work).
    func isVoiceAvailable(_ lang: Lang) -> Bool { Speaker.voice(for: lang) != nil }

    /// True when [lang] would be read with the Filipino voice instead of its own.
    func usesFallbackVoice(_ lang: Lang) -> Bool {
        guard let v = Speaker.voice(for: lang) else { return false }
        return v.language.lowercased().hasPrefix("fil") && lang != .tl
    }

    // ---------------------------------------------------------------- speaking

    /// Speaks [text] in [lang], replacing anything being spoken. Returns false when there is no voice for [lang].
    @discardableResult
    func speak(_ text: String, lang: Lang, speed: VoiceSpeed? = nil) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return false }
        guard let voice = Speaker.voice(for: lang) else { return false }
        stop()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try session.setActive(true)
        } catch {
            // Speak anyway: the default session usually works.
        }
        let utterance = AVSpeechUtterance(string: trimmed)
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * (speed ?? self.speed).rate
        isSpeaking = true
        current = ObjectIdentifier(utterance)
        synthesizer.speak(utterance)
        return true
    }

    func stop() {
        current = nil
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        finished()
    }

    private func finished() {
        guard isSpeaking else { return }
        isSpeaking = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

extension VoiceSpeed {
    /// Multiplier on the default speech rate (Android: 0.75 / 1.0).
    var rate: Float {
        switch self {
        case .slow: return 0.75
        case .normal: return 1.0
        }
    }
}

/// `AVSpeechSynthesizerDelegate` shim: reports the end (finish or cancel) of an utterance on the main actor.
private final class SpeakerDelegate: NSObject, AVSpeechSynthesizerDelegate, @unchecked Sendable {
    private let onEnd: @MainActor (ObjectIdentifier) -> Void

    init(onEnd: @escaping @MainActor (ObjectIdentifier) -> Void) { self.onEnd = onEnd }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let f = onEnd
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in f(id) }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        let f = onEnd
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in f(id) }
    }
}
