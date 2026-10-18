import Foundation

/// Decides when a recorded utterance is over, from loudness alone (no recogniser tells us: this is the path for languages
/// the phone cannot recognise). Learns the room's noise floor from the first `calibrateMs`, then calls anything clearly
/// above it speech. Ends `endAfterSpeechMs` after the last speech, gives up on an empty recording after `noSpeechMs`, and
/// never runs past `maxMs`. Pure: feed it (level, time) pairs. Port of Android `Endpointer`.
public struct Endpointer: Sendable {
    public enum Verdict: Sendable, Hashable { case listening, endSpeech, endNothing, endMax }

    public static let calibrateMs: Int64 = 400
    public static let minThreshold: Float = 0.03
    public static let maxFloor: Float = 0.2
    /// The model's clip limit (`Wav.maxSeconds`) minus a little.
    public static let defaultMaxMs: Int64 = Int64(Wav.maxSeconds) * 1000 - 300

    private let endAfterSpeechMs: Int64
    private let noSpeechMs: Int64
    private let maxMs: Int64
    private var startMs: Int64 = -1
    private var floor: Float = 0
    private var floorN = 0
    private var lastSpeechMs: Int64 = -1

    public init(endAfterSpeechMs: Int64, noSpeechMs: Int64 = 8_000, maxMs: Int64 = Endpointer.defaultMaxMs) {
        self.endAfterSpeechMs = endAfterSpeechMs; self.noSpeechMs = noSpeechMs; self.maxMs = maxMs
    }

    public var heardSpeech: Bool { lastSpeechMs >= 0 }

    public mutating func update(level: Float, nowMs: Int64) -> Verdict {
        if startMs < 0 { startMs = nowMs }
        let t = nowMs - startMs
        if t < Self.calibrateMs {
            // A word spoken straight away must not become the "noise": only quiet buffers set the floor.
            if level < Self.maxFloor { floor = (floor * Float(floorN) + level) / Float(floorN + 1); floorN += 1 }
        } else if level > threshold() {
            lastSpeechMs = nowMs
        }
        if t >= maxMs { return .endMax }
        if lastSpeechMs >= 0 && nowMs - lastSpeechMs >= endAfterSpeechMs { return .endSpeech }
        if lastSpeechMs < 0 && t >= noSpeechMs { return .endNothing }
        return .listening
    }

    public func threshold() -> Float { max(Self.minThreshold, floor * 3) }
}

/// 16 kHz mono PCM16 <-> canonical 44-byte-header WAV (the form the model takes).
public enum Wav {
    public static let sampleRate = 16_000
    public static let maxSeconds = 15

    public static func pcmToWav(_ pcm: [UInt8], sampleRate: Int = Wav.sampleRate, channels: Int = 1) -> [UInt8] {
        func le32(_ v: Int) -> [UInt8] { (0..<4).map { UInt8(truncatingIfNeeded: v >> (8 * $0)) } }
        func le16(_ v: Int) -> [UInt8] { (0..<2).map { UInt8(truncatingIfNeeded: v >> (8 * $0)) } }
        var out: [UInt8] = []
        out.reserveCapacity(44 + pcm.count)
        out += Array("RIFF".utf8) + le32(36 + pcm.count) + Array("WAVE".utf8)
        out += Array("fmt ".utf8) + le32(16) + le16(1) + le16(channels) + le32(sampleRate) + le32(sampleRate * channels * 2) + le16(channels * 2) + le16(16)
        out += Array("data".utf8) + le32(pcm.count) + pcm
        return out
    }
}
