import Foundation

/// Whether New request opens on the listening card or the text field.
public enum AskMode: String, Sendable, Hashable {
    case voice, text

    /// Parses the route argument; anything unknown is voice.
    public init(arg: String?) { self = AskMode(rawValue: arg ?? "") ?? .voice }
    public var arg: String { rawValue }
}

/// The four root tabs, in tab-bar order.
public enum AppTab: Int, CaseIterable, Sendable, Hashable {
    case home, requests, network, settings

    /// The tab's root screen.
    public var route: Route {
        switch self {
        case .home: return .home
        case .requests: return .requests
        case .network: return .network
        case .settings: return .settings
        }
    }

    /// The tab-bar label in the app language.
    public func title(_ c: CommonStrings) -> String {
        switch self {
        case .home: return c.navHome
        case .requests: return c.navRequests
        case .network: return c.navNetwork
        case .settings: return c.navSettings
        }
    }

    /// SF Symbol of the floating tab bar (docs/design/ios-build-plan.md).
    public var symbol: String {
        switch self {
        case .home: return "house"
        case .requests: return "list.bullet"
        case .network: return "point.3.connected.trianglepath.dotted"
        case .settings: return "gearshape"
        }
    }
}

/// Every destination of the app's navigation (Android `Routes`). `Hashable` so a SwiftUI `NavigationStack` path can
/// hold them; `path` gives the Android route string for logs and tests.
public enum Route: Hashable, Sendable {
    case language
    case home
    case requests
    case request(msgId: String)
    /// New request (step 1 of 3). `prefillFrom` is the msg id of the request being added to.
    case ask(mode: AskMode, prefillFrom: String?)
    /// The triage questions between New request and Check and send (medical and rescue requests only).
    case triage
    /// Check and send (step 2 of 3).
    case check
    case network
    /// Developer console (every number kept), pushed from Network.
    case console
    case settings
    /// Developer settings, pushed from Settings (last row).
    case developer
    /// "Get ready": warning, plan, shelters, checklist (pushed from the Home banner or Settings).
    case ready
    /// "My household", pushed from Settings.
    case household
    /// The shelter list, and one shelter's detail (pushed from Home or the Ready screen).
    case shelters
    case shelter(id: String)

    /// Routes that hide the tab bar: the ask flow and the first-run language screen.
    public var hidesTabBar: Bool {
        switch self {
        case .ask, .triage, .check, .language: return true
        default: return false
        }
    }

    /// True for the two screens that share one `AskFlow`.
    public var isAskFlow: Bool {
        switch self {
        case .ask, .triage, .check: return true
        default: return false
        }
    }

    /// Which tab a route belongs to (Request detail counts as Requests). Nil = none.
    public var tab: AppTab? {
        switch self {
        case .home, .ready, .shelters, .shelter: return .home
        case .requests, .request: return .requests
        case .network, .console: return .network
        case .settings, .developer, .household: return .settings
        default: return nil
        }
    }

    /// The Android route string: "home", "request/7f3a1c20", "ask?mode=voice&prefillFrom=7f3a1c20", "network/console".
    public var path: String {
        switch self {
        case .language: return "language"
        case .home: return "home"
        case .requests: return "requests"
        case .request(let id): return "request/\(id)"
        case .ask(let mode, let from): return "ask?mode=\(mode.arg)" + (from.map { "&prefillFrom=\($0)" } ?? "")
        case .triage: return "triage"
        case .check: return "check"
        case .network: return "network"
        case .console: return "network/console"
        case .settings: return "settings"
        case .developer: return "settings/developer"
        case .ready: return "ready"
        case .household: return "settings/household"
        case .shelters: return "shelters"
        case .shelter(let id): return "shelter/" + (id.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? id)
        }
    }

    /// Parses `path` back; nil for anything that is not a route.
    public init?(path: String) {
        let base = path.substringBefore("?")
        switch base {
        case "language": self = .language
        case "home": self = .home
        case "requests": self = .requests
        case "triage": self = .triage
        case "check": self = .check
        case "network": self = .network
        case "network/console": self = .console
        case "settings": self = .settings
        case "settings/developer": self = .developer
        case "ready": self = .ready
        case "settings/household": self = .household
        case "shelters": self = .shelters
        case "ask":
            var mode: String?
            var from: String?
            if let q = path.range(of: "?") {
                for pair in path[q.upperBound...].split(separator: "&") {
                    let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
                    guard kv.count == 2 else { continue }
                    if kv[0] == "mode" { mode = kv[1] } else if kv[0] == "prefillFrom" { from = kv[1] }
                }
            }
            self = .ask(mode: AskMode(arg: mode), prefillFrom: from)
        default:
            if base.hasPrefix("shelter/"), base.count > "shelter/".count {
                let raw = String(base.dropFirst("shelter/".count))
                self = .shelter(id: raw.removingPercentEncoding ?? raw)
                return
            }
            guard base.hasPrefix("request/"), base.count > "request/".count else { return nil }
            self = .request(msgId: String(base.dropFirst("request/".count)))
        }
    }
}

/// Stack operations that Android did with `popUpTo`: pure functions on the path of a `NavigationStack`
/// (the tab root is not part of the path).
public enum RouteStack {
    /// The first screen: Language until a language has been confirmed, then Home.
    public static func startRoute(languageConfirmed: Bool) -> Route { languageConfirmed ? .home : .language }

    /// First run: the language is confirmed, go to Home and drop the language screen.
    public static func languageDone() -> [Route] { [] }

    /// After Send: open the new request's detail and drop the ask/check flow from the stack.
    public static func finishAsk(_ stack: [Route], msgId: String) -> [Route] {
        var s = stack
        while let last = s.last, last.isAskFlow { s.removeLast() }
        s.append(.request(msgId: msgId))
        return s
    }

    /// New request -> the triage questions.
    public static func toTriage(_ stack: [Route]) -> [Route] { stack + [.triage] }

    /// New request (or the triage questions) -> Check and send.
    public static func toCheck(_ stack: [Route]) -> [Route] { stack + [.check] }
}
