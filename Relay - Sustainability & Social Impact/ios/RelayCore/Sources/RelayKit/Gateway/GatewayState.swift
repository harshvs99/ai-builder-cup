import Foundation

/// Persistent gateway bookkeeping: which NEED msg_ids were already uploaded and the backend's ack_cursor.
/// `file` may be nil for an in-memory instance. A corrupt file loads as empty state. File format is Android's:
/// `{"cursor": n, "uploaded": ["hex", ...]}`.
public final class GatewayState: @unchecked Sendable {
    private struct Disk: Codable { var cursor: Int64; var uploaded: [String] }

    private let file: URL?
    private let maxRemembered: Int
    private let lock = NSLock()
    private var order: [String] = []
    private var uploaded = Set<String>()
    private var cursor: Int64 = 0

    public init(file: URL?, maxRemembered: Int = 10_000) {
        self.file = file
        self.maxRemembered = maxRemembered
        load()
    }

    public func ackCursor() -> Int64 { lock.lock(); defer { lock.unlock() }; return cursor }
    public func isUploaded(_ msgIdHex: String) -> Bool { lock.lock(); defer { lock.unlock() }; return uploaded.contains(msgIdHex) }
    public func uploadedCount() -> Int { lock.lock(); defer { lock.unlock() }; return uploaded.count }

    public func markUploaded(_ msgIds: [String]) {
        lock.lock(); defer { lock.unlock() }
        for id in msgIds where uploaded.insert(id).inserted { order.append(id) }
        while uploaded.count > maxRemembered {
            let oldest = order.removeFirst()
            uploaded.remove(oldest)
        }
        save()
    }

    public func setAckCursor(_ value: Int64) {
        lock.lock(); defer { lock.unlock() }
        if value == cursor { return }
        cursor = value
        save()
    }

    /// Forget uploaded ids (keeps the cursor, so old ACKs are not replayed).
    public func clearUploaded() {
        lock.lock(); defer { lock.unlock() }
        uploaded.removeAll(); order.removeAll()
        save()
    }

    private func save() {
        guard let f = file else { return }
        do {
            let e = JSONEncoder()
            e.outputFormatting = [.sortedKeys]
            try AtomicFiles.write(f, [UInt8](try e.encode(Disk(cursor: cursor, uploaded: order))))
        } catch {
            // retried on next change
        }
    }

    private func load() {
        guard let f = file, let bytes = AtomicFiles.readOrNull(f) else { return }
        guard let d = try? JSONDecoder().decode(Disk.self, from: Data(bytes)) else { return }
        cursor = d.cursor
        for id in d.uploaded where uploaded.insert(id).inserted { order.append(id) }
    }
}
