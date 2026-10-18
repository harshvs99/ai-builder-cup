import Foundation

/// The small slice of key-value persistence `AppSettings` needs (Android: `SharedPreferences`). The iOS app passes
/// `UserDefaultsKeyValueStore`; tests use `InMemoryKeyValueStore`; `FileKeyValueStore` is a UserDefaults-free option.
/// Reads return nil for an unset key so callers can apply their own default.
public protocol KeyValueStore: AnyObject, Sendable {
    func stringValue(forKey key: String) -> String?
    func boolValue(forKey key: String) -> Bool?
    func setString(_ value: String, forKey key: String)
    func setBool(_ value: Bool, forKey key: String)
    func removeValue(forKey key: String)
}

/// Thread-safe in-memory store, for tests.
public final class InMemoryKeyValueStore: KeyValueStore, @unchecked Sendable {
    private let lock = NSLock()
    private var strings: [String: String] = [:]
    private var bools: [String: Bool] = [:]

    public init() {}

    public func stringValue(forKey key: String) -> String? { lock.lock(); defer { lock.unlock() }; return strings[key] }
    public func boolValue(forKey key: String) -> Bool? { lock.lock(); defer { lock.unlock() }; return bools[key] }
    public func setString(_ value: String, forKey key: String) { lock.lock(); strings[key] = value; bools[key] = nil; lock.unlock() }
    public func setBool(_ value: Bool, forKey key: String) { lock.lock(); bools[key] = value; strings[key] = nil; lock.unlock() }
    public func removeValue(forKey key: String) { lock.lock(); strings[key] = nil; bools[key] = nil; lock.unlock() }
}

/// `UserDefaults`-backed store (the iOS default). Pass `UserDefaults(suiteName:)` to keep Relay's keys apart.
public final class UserDefaultsKeyValueStore: KeyValueStore, @unchecked Sendable {
    private let defaults: UserDefaults

    public init(_ defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func stringValue(forKey key: String) -> String? { defaults.string(forKey: key) }
    public func boolValue(forKey key: String) -> Bool? { defaults.object(forKey: key) as? Bool }
    public func setString(_ value: String, forKey key: String) { defaults.set(value, forKey: key) }
    public func setBool(_ value: Bool, forKey key: String) { defaults.set(value, forKey: key) }
    public func removeValue(forKey key: String) { defaults.removeObject(forKey: key) }
}

/// A JSON file (`{"strings": {...}, "bools": {...}}`) rewritten atomically on every change. A missing or corrupt
/// file loads as empty.
public final class FileKeyValueStore: KeyValueStore, @unchecked Sendable {
    private struct Contents: Codable { var strings: [String: String] = [:]; var bools: [String: Bool] = [:] }

    private let file: URL
    private let lock = NSLock()
    private var contents = Contents()

    public init(file: URL) {
        self.file = file
        if let bytes = AtomicFiles.readOrNull(file), let c = try? JSONDecoder().decode(Contents.self, from: Data(bytes)) {
            contents = c
        }
    }

    public func stringValue(forKey key: String) -> String? { lock.lock(); defer { lock.unlock() }; return contents.strings[key] }
    public func boolValue(forKey key: String) -> Bool? { lock.lock(); defer { lock.unlock() }; return contents.bools[key] }
    public func setString(_ value: String, forKey key: String) { mutate { $0.strings[key] = value; $0.bools[key] = nil } }
    public func setBool(_ value: Bool, forKey key: String) { mutate { $0.bools[key] = value; $0.strings[key] = nil } }
    public func removeValue(forKey key: String) { mutate { $0.strings[key] = nil; $0.bools[key] = nil } }

    private func mutate(_ f: (inout Contents) -> Void) {
        lock.lock(); defer { lock.unlock() }
        f(&contents)
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        if let data = try? e.encode(contents) { try? AtomicFiles.write(file, [UInt8](data)) }
    }
}
