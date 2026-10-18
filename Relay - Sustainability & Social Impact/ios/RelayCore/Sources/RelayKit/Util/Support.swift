import Foundation

/// Wall-clock epoch milliseconds (Kotlin `System.currentTimeMillis()`).
public func currentTimeMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }

/// Monotonic milliseconds since boot (Kotlin `SystemClock.elapsedRealtime()`); for timeouts and backoff, never for display.
public func monotonicMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

extension String {
    /// Kotlin `String.take(n)`.
    func take(_ n: Int) -> String { String(prefix(n)) }

    /// Kotlin `substringAfter(delimiter, missingDelimiterValue)`.
    func substringAfter(_ delimiter: String, missing: String) -> String {
        guard let r = range(of: delimiter) else { return missing }
        return String(self[r.upperBound...])
    }

    /// Kotlin `substringBefore(delimiter)` (whole string when absent).
    func substringBefore(_ delimiter: Character) -> String {
        guard let i = firstIndex(of: delimiter) else { return self }
        return String(self[..<i])
    }

    /// Kotlin `substringAfterLast(delimiter)` (whole string when absent).
    func substringAfterLast(_ delimiter: Character) -> String {
        guard let i = lastIndex(of: delimiter) else { return self }
        return String(self[index(after: i)...])
    }

    /// Kotlin `trimEnd(c)`.
    func trimmingTrailing(_ c: Character) -> String {
        var s = self
        while s.last == c { s.removeLast() }
        return s
    }

    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
