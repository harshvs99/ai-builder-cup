import Foundation

/// Crash-safe whole-file replacement: write a sibling temp file, fsync it, then rename over the target.
public enum AtomicFiles {
    /// The sibling temp file used while rewriting [target] (`<name>.tmp`).
    public static func tmpFor(_ target: URL) -> URL {
        target.deletingLastPathComponent().appendingPathComponent(target.lastPathComponent + ".tmp")
    }

    public static func write(_ target: URL, _ bytes: [UInt8]) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = tmpFor(target)
        try? fm.removeItem(at: tmp)
        guard fm.createFile(atPath: tmp.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: tmp.path])
        }
        let handle = try FileHandle(forWritingTo: tmp)
        do {
            try handle.write(contentsOf: Data(bytes))
            try? handle.synchronize() // best effort
            try handle.close()
        } catch {
            try? handle.close()
            throw error
        }
        // rename(2) replaces the target atomically on every supported platform.
        if rename(tmp.path, target.path) != 0 {
            let code = errno
            try? fm.removeItem(at: tmp)
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(code))
        }
    }

    /// The file's bytes, or nil when it does not exist or cannot be read.
    public static func readOrNull(_ file: URL) -> [UInt8]? {
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: file.path, isDirectory: &isDir), !isDir.boolValue else { return nil }
        return (try? Data(contentsOf: file)).map { [UInt8]($0) }
    }
}
