import Foundation
import RelayCore

/// Pure decision logic for voice input: which recogniser flags to use and what to do with an error code.
/// Error codes are the Android `SpeechRecognizer.ERROR_*` values, duplicated here so this stays testable; the iOS
/// speech adapter maps `SFSpeechRecognizer` / `AVAudioEngine` failures onto the closest code and then asks
/// `onError` what to do.
public enum SpeechLogic {
    public static let errorNetworkTimeout = 1
    public static let errorNetwork = 2
    public static let errorAudio = 3
    public static let errorServer = 4
    public static let errorClient = 5
    public static let errorSpeechTimeout = 6
    public static let errorNoMatch = 7
    public static let errorRecognizerBusy = 8
    public static let errorInsufficientPermissions = 9
    public static let errorTooManyRequests = 10
    public static let errorServerDisconnected = 11
    public static let errorLanguageNotSupported = 12
    public static let errorLanguageUnavailable = 13
    public static let errorCannotCheckSupport = 14
    public static let errorCannotListenToDownloadEvents = 15

    // ---------------------------------------------------------------- Gemma as the speech recogniser

    /// Languages whose Gemma transcription has been scored against recordings (ml/voice-spike: English and Hindi only, one
    /// speaker, 34 clips). Google publishes no per-language speech-recognition list for the model, so every other language
    /// is "may work": its transcript is shown for the person to check rather than trusted.
    public static let gemmaSpeechMeasured: Set<Lang> = [.en, .hi]

    /// Whether to let Gemma's audio input do the listening after the phone's recogniser failed with `kind`. Only for a
    /// language problem (no pack, no offline model, unsupported), never a busy microphone or a denied permission, and only
    /// when the phone can run the model. The platform recogniser always goes first: it is fast, streaming, and already works.
    public static func useGemmaForSpeech(_ kind: Kind, gemmaUsable: Bool) -> Bool { kind == .languageUnavailable && gemmaUsable }

    /// Shown while Gemma is listening: asks for a check unless the language was measured.
    public static func gemmaNote(_ lang: Lang) -> String {
        gemmaSpeechMeasured.contains(lang)
            ? "Using the on-device assistant to listen."
            : "Using the on-device assistant to listen in \(languageName(lang)) (not fully tested) — check the text before sending."
    }

    /// Recogniser language tag for each Relay language; Waray and Cebuano fall back to Filipino.
    public static func recognizerTag(_ lang: Lang) -> String {
        switch lang {
        case .en: return "en-IN"
        case .hi: return "hi-IN"
        case .or: return "or-IN"
        case .tl, .war, .ceb: return "fil-PH"
        }
    }

    public static func languageName(_ lang: Lang) -> String {
        switch lang {
        case .en: return "English"
        case .hi: return "Hindi"
        case .or: return "Odia"
        case .tl: return "Tagalog"
        case .war: return "Waray"
        case .ceb: return "Cebuano"
        }
    }

    /// Note shown while listening when the recogniser is not using the language itself.
    public static func fallbackNote(_ lang: Lang) -> String? {
        switch lang {
        case .war, .ceb: return "\(languageName(lang)) voice uses the Filipino recogniser (fallback) — check the text before sending."
        default: return nil
        }
    }

    /// Offline mode is requested only when there is no validated internet; online it makes Google demand a pack.
    public static func preferOffline(online: Bool) -> Bool { !online }

    public enum Kind: Sendable, Hashable { case retryOnline, languageUnavailable, noSpeech, permission, busy, failed }

    public struct Decision: Equatable, Sendable {
        public let kind: Kind
        public let message: String?
        public let offerVoiceSettings: Bool
        public init(kind: Kind, message: String? = nil, offerVoiceSettings: Bool = false) {
            self.kind = kind; self.message = message; self.offerVoiceSettings = offerVoiceSettings
        }
    }

    private static func isLanguageOrNetworkError(_ code: Int) -> Bool {
        [errorNetworkTimeout, errorNetwork, errorServer, errorServerDisconnected, errorLanguageNotSupported,
         errorLanguageUnavailable, errorCannotCheckSupport, errorCannotListenToDownloadEvents].contains(code)
    }

    public static func unavailableMessage(_ lang: Lang, offlineAttempt: Bool) -> String {
        let n = languageName(lang)
        if offlineAttempt {
            return "Voice for \(n) isn't available on this phone offline — type your request, or download the \(n) voice pack (needs internet)."
        }
        return "Voice for \(n) isn't available right now — type your request, or download the \(n) voice pack (needs internet)."
    }

    /// What to do after the recogniser reported [code].
    /// - Parameters:
    ///   - offlineAttempt: the failed attempt required on-device recognition
    ///   - online: the phone currently has validated internet
    ///   - alreadyRetried: an online retry was already made for this utterance
    public static func onError(_ code: Int, lang: Lang, offlineAttempt: Bool, online: Bool, alreadyRetried: Bool) -> Decision {
        if code == errorNoMatch || code == errorSpeechTimeout {
            return Decision(kind: .noSpeech, message: "Didn't catch that — tap the mic and try again, or type your request.")
        }
        if code == errorInsufficientPermissions {
            return Decision(kind: .permission, message: "Microphone permission is needed for voice. Type your request, or allow the microphone in App settings.")
        }
        if code == errorRecognizerBusy || code == errorTooManyRequests {
            return Decision(kind: .busy, message: "Voice recogniser is busy — try again in a moment, or type your request.")
        }
        if offlineAttempt && online && !alreadyRetried && isLanguageOrNetworkError(code) {
            return Decision(kind: .retryOnline)
        }
        if isLanguageOrNetworkError(code) {
            return Decision(kind: .languageUnavailable, message: unavailableMessage(lang, offlineAttempt: offlineAttempt), offerVoiceSettings: true)
        }
        if code == errorAudio { return Decision(kind: .failed, message: "Couldn't use the microphone. Type your request instead.") }
        return Decision(kind: .failed, message: "Voice input failed (error \(code)). Type your request instead.")
    }
}
