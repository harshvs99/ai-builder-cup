import Foundation

/// Demo chain allowlist: comma-separated node-id hex prefixes. Empty list = accept everyone.
public enum Allowlist {
    public static func parse(_ csv: String) -> [String] {
        csv.split(whereSeparator: { ",; \n".contains($0) }).map { $0.trimmed.lowercased() }.filter { !$0.isEmpty }
    }

    public static func matches(_ csv: String, nodeIdHex: String) -> Bool {
        let prefixes = parse(csv)
        if prefixes.isEmpty { return true }
        let id = nodeIdHex.lowercased()
        return prefixes.contains { id.hasPrefix($0) }
    }
}

private extension Substring {
    var trimmed: String { String(self).trimmed }
}
