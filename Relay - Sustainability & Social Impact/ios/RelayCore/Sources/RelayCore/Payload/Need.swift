import Foundation

/// One answered intake question (SPEC 5.1): [questionId] and [value] are the `QuestionBank` wire values.
public struct NeedAnswer: Equatable, Hashable, Sendable {
    public let questionId: Int
    public let value: Int

    public init(questionId: Int, value: Int) { self.questionId = questionId; self.value = value }
    public init(_ questionId: Int, _ value: Int) { self.init(questionId: questionId, value: value) }

    /// Wire byte: question id in the high 5 bits, answer value in the low 3. Never 0 for a valid answer.
    public var wireByte: Int { (questionId << 3) | value }
}

/// NEED payload (SPEC 5.1). Encoded as payload_version 1, or 2 when [answers] is not empty.
///
/// Integer fields are plain `Int` (range-checked by the encoder); [createdAt] is `Int64`.
public struct Need: Equatable, Hashable, Sendable {
    public var msgId: [UInt8]
    public var originNode: [UInt8]
    /// unix seconds, u32
    public var createdAt: Int64
    public var category: Category
    public var urgency: Int
    /// 1..255, 0 = unknown
    public var people: Int
    /// bitmask of `VulnFlag` bits
    public var vulnFlags: Int
    /// i32, degrees x 1e5
    public var latE5: Int
    public var lonE5: Int
    /// 0 no fix, 1 <10 m, 2 <50 m, 3 <200 m, 4 <1 km, 5 >=1 km
    public var locAcc: Int
    public var lang: Lang
    public var item: String
    public var landmark: String
    public var summary: String
    /// At most `QuestionBank.maxAnswers`, distinct question ids; empty = a v1 payload on the wire.
    public var answers: [NeedAnswer]

    public init(
        msgId: [UInt8], originNode: [UInt8], createdAt: Int64, category: Category, urgency: Int, people: Int,
        vulnFlags: Int, latE5: Int, lonE5: Int, locAcc: Int, lang: Lang, item: String, landmark: String,
        summary: String, answers: [NeedAnswer] = []
    ) {
        self.msgId = msgId; self.originNode = originNode; self.createdAt = createdAt; self.category = category
        self.urgency = urgency; self.people = people; self.vulnFlags = vulnFlags; self.latE5 = latE5
        self.lonE5 = lonE5; self.locAcc = locAcc; self.lang = lang; self.item = item; self.landmark = landmark
        self.summary = summary; self.answers = answers
    }

    public var lat: Double { Geo.fromE5(latE5) }
    public var lon: Double { Geo.fromE5(lonE5) }
    public var vulnSet: Set<VulnFlag> { VulnFlag.fromMask(vulnFlags) }
    public var msgIdHex: String { Hex.encode(msgId) }
}

public enum NeedCodec {
    public static let version = 1
    /// v1 layout followed by exactly [answerBytes] answer bytes.
    public static let versionWithAnswers = 2
    public static let answerBytes = 3
    public static let maxItem = 16
    public static let maxLandmark = 24
    public static let maxSummary = 40
    public static let fixedSize = 35
    /// Fixed part + 3 length bytes + 80 string bytes.
    public static let maxPayload = fixedSize + 3 + maxItem + maxLandmark + maxSummary
    public static let maxPayloadV2 = maxPayload + answerBytes

    /// Encode a NEED. Strings are truncated on a code point boundary to their limits. Throws
    /// `IllegalArgumentError` if a numeric field is out of range or an id has the wrong length.
    public static func encode(_ need: Need) throws -> [UInt8] {
        guard need.msgId.count == 8 else { throw IllegalArgumentError("msg_id must be 8 bytes") }
        guard need.originNode.count == 8 else { throw IllegalArgumentError("origin_node must be 8 bytes") }
        guard (0...0xFFFF_FFFF).contains(need.createdAt) else { throw IllegalArgumentError("created_at out of u32 range") }
        guard (0...3).contains(need.urgency) else { throw IllegalArgumentError("urgency out of range") }
        guard (0...255).contains(need.people) else { throw IllegalArgumentError("people out of range") }
        guard (0...255).contains(need.vulnFlags) else { throw IllegalArgumentError("vuln_flags out of range") }
        guard (0...5).contains(need.locAcc) else { throw IllegalArgumentError("loc_acc out of range") }
        guard (Int(Int32.min)...Int(Int32.max)).contains(need.latE5), (Int(Int32.min)...Int(Int32.max)).contains(need.lonE5) else {
            throw IllegalArgumentError("lat/lon out of i32 range")
        }
        guard need.answers.count <= answerBytes else { throw IllegalArgumentError("at most \(answerBytes) answers") }
        guard need.answers.allSatisfy({ QuestionBank.isValid($0.questionId, $0.value) }) else {
            throw IllegalArgumentError("unknown question id or answer value")
        }
        guard Set(need.answers.map { $0.questionId }).count == need.answers.count else {
            throw IllegalArgumentError("duplicate question id")
        }
        let v2 = !need.answers.isEmpty
        let item = Utf8.truncateToBytes(need.item, maxItem)
        let landmark = Utf8.truncateToBytes(need.landmark, maxLandmark)
        let summary = Utf8.truncateToBytes(need.summary, maxSummary)
        var w = ByteWriter(capacity: fixedSize + 3 + item.count + landmark.count + summary.count + (v2 ? answerBytes : 0))
        w.u8(v2 ? versionWithAnswers : version)
        w.put(need.msgId)
        w.put(need.originNode)
        w.u32(need.createdAt)
        w.u8(need.category.code)
        w.u8(need.urgency)
        w.u8(need.people)
        w.u8(need.vulnFlags)
        w.i32(need.latE5)
        w.i32(need.lonE5)
        w.u8(need.locAcc)
        w.u8(need.lang.code)
        w.u8(item.count); w.put(item)
        w.u8(landmark.count); w.put(landmark)
        w.u8(summary.count); w.put(summary)
        if v2 {
            for i in 0..<answerBytes { w.u8(i < need.answers.count ? need.answers[i].wireByte : 0) }
        }
        return w.bytes
    }

    /// Nil if invalid (SPEC 5.1 validation) or `payload_version` is not 1 or 2. Never traps.
    public static func decode(_ p: [UInt8]) -> Need? {
        if p.count < fixedSize + 3 || p.count > maxPayloadV2 { return nil }
        let ver = Int(p[0])
        if ver != version && ver != versionWithAnswers { return nil }
        var r = ByteReader(p, at: 1)
        let msgId = r.take(8)
        let origin = r.take(8)
        let createdAt = r.u32()
        guard let category = Category.fromCode(r.u8()) else { return nil }
        let urgency = r.u8()
        if urgency > 3 { return nil }
        let people = r.u8()
        let vuln = r.u8()
        let lat = r.i32()
        let lon = r.i32()
        let locAcc = r.u8()
        if locAcc > 5 { return nil }
        guard let lang = Lang.fromCode(r.u8()) else { return nil }
        guard let item = readString(&r, max: maxItem) else { return nil }
        guard let landmark = readString(&r, max: maxLandmark) else { return nil }
        guard let summary = readString(&r, max: maxSummary) else { return nil }
        var answers: [NeedAnswer] = []
        if ver == versionWithAnswers {
            if r.remaining != answerBytes { return nil }
            var empty = false
            for _ in 0..<answerBytes {
                let b = r.u8()
                if b == 0 { empty = true; continue }
                if empty { return nil } // occupied slot after an empty one: not canonical
                let a = NeedAnswer(questionId: b >> 3, value: b & 7)
                if !QuestionBank.isValid(a.questionId, a.value) || answers.contains(where: { $0.questionId == a.questionId }) {
                    return nil
                }
                answers.append(a)
            }
            if answers.isEmpty { return nil } // v2 means "has answers"; encoders emit v1 otherwise
        }
        if r.hasRemaining || !r.ok { return nil }
        return Need(msgId: msgId, originNode: origin, createdAt: createdAt, category: category, urgency: urgency,
                    people: people, vulnFlags: vuln, latE5: lat, lonE5: lon, locAcc: locAcc, lang: lang,
                    item: item, landmark: landmark, summary: summary, answers: answers)
    }

    /// Length-prefixed (1 byte) strict UTF-8 string of at most [max] bytes; nil if malformed or truncated.
    static func readString(_ r: inout ByteReader, max: Int) -> String? {
        if !r.hasRemaining { return nil }
        let len = r.u8()
        if len > max || r.remaining < len { return nil }
        guard let s = Utf8.decodeStrict(r.bytes, offset: r.pos, length: len) else { return nil }
        r.pos += len
        return s
    }
}
