import XCTest
import RelayCore
@testable import RelayKit

final class RoutesTests: XCTestCase {
    func testPathsMatchAndroid() {
        XCTAssertEqual("language", Route.language.path)
        XCTAssertEqual("home", Route.home.path)
        XCTAssertEqual("request/7f3a1c20", Route.request(msgId: "7f3a1c20").path)
        XCTAssertEqual("ask?mode=voice", Route.ask(mode: .voice, prefillFrom: nil).path)
        XCTAssertEqual("ask?mode=text&prefillFrom=7f3a1c20", Route.ask(mode: .text, prefillFrom: "7f3a1c20").path)
        XCTAssertEqual("check", Route.check.path)
        XCTAssertEqual("network/console", Route.console.path)
        XCTAssertEqual("settings/developer", Route.developer.path)
    }

    func testPathRoundTrips() {
        let all: [Route] = [
            .language, .home, .requests, .request(msgId: "ab12"), .ask(mode: .voice, prefillFrom: nil),
            .ask(mode: .text, prefillFrom: "ab12"), .check, .network, .console, .settings, .developer, .ready, .household, .shelters, .shelter(id: "s1"), .shelter(id: "a b/c"),
        ]
        for r in all { XCTAssertEqual(r, Route(path: r.path), r.path) }
        XCTAssertNil(Route(path: "nonsense"))
        XCTAssertNil(Route(path: "request/"))
        XCTAssertEqual(.ask(mode: .voice, prefillFrom: nil), Route(path: "ask"))
        XCTAssertEqual(.ask(mode: .voice, prefillFrom: nil), Route(path: "ask?mode=bogus"))
    }

    func testAskModeParsing() {
        XCTAssertEqual(.voice, AskMode(arg: nil))
        XCTAssertEqual(.voice, AskMode(arg: "voice"))
        XCTAssertEqual(.text, AskMode(arg: "text"))
        XCTAssertEqual(.voice, AskMode(arg: "other"))
    }

    func testTabBarIsHiddenOnFlowScreens() {
        XCTAssertTrue(Route.ask(mode: .voice, prefillFrom: nil).hidesTabBar)
        XCTAssertTrue(Route.check.hidesTabBar)
        XCTAssertTrue(Route.language.hidesTabBar)
        for r in [Route.home, .requests, .request(msgId: "a"), .network, .console, .settings, .developer] { XCTAssertFalse(r.hidesTabBar, r.path) }
    }

    func testRequestDetailCountsAsRequestsTab() {
        XCTAssertEqual(.home, Route.home.tab)
        XCTAssertEqual(.requests, Route.requests.tab)
        XCTAssertEqual(.requests, Route.request(msgId: "a").tab)
        XCTAssertEqual(.network, Route.network.tab)
        XCTAssertEqual(.network, Route.console.tab)
        XCTAssertEqual(.settings, Route.settings.tab)
        XCTAssertEqual(.settings, Route.developer.tab)
        XCTAssertEqual(.home, Route.ready.tab)
        XCTAssertEqual(.settings, Route.household.tab)
        XCTAssertFalse(Route.ready.hidesTabBar || Route.household.hidesTabBar)
        XCTAssertEqual(.home, Route.shelters.tab); XCTAssertEqual(.home, Route.shelter(id: "s1").tab)
        XCTAssertEqual("shelters", Route.shelters.path); XCTAssertEqual("shelter/s1", Route.shelter(id: "s1").path)
        XCTAssertNil(Route(path: "shelter/"))
        XCTAssertEqual("ready", Route.ready.path); XCTAssertEqual("settings/household", Route.household.path)
        XCTAssertNil(Route.check.tab)
        XCTAssertNil(Route.language.tab)
    }

    func testTabs() {
        XCTAssertEqual([AppTab.home, .requests, .network, .settings], AppTab.allCases)
        XCTAssertEqual([Route.home, .requests, .network, .settings], AppTab.allCases.map { $0.route })
        XCTAssertEqual(["house", "list.bullet", "point.3.connected.trianglepath.dotted", "gearshape"], AppTab.allCases.map { $0.symbol })
        XCTAssertEqual(["Home", "Requests", "Network", "Settings"], AppTab.allCases.map { $0.title(UI.strings(.en).common) })
        for t in AppTab.allCases { XCTAssertEqual(t, t.route.tab) }
    }

    func testStartRouteAndStackOperations() {
        XCTAssertEqual(.language, RouteStack.startRoute(languageConfirmed: false))
        XCTAssertEqual(.home, RouteStack.startRoute(languageConfirmed: true))
        XCTAssertTrue(RouteStack.languageDone().isEmpty)
        let ask = Route.ask(mode: .voice, prefillFrom: nil)
        XCTAssertEqual([ask, .check], RouteStack.toCheck([ask]))
        XCTAssertEqual([ask, .triage], RouteStack.toTriage([ask]))
        XCTAssertEqual([ask, .triage, .check], RouteStack.toCheck(RouteStack.toTriage([ask])))
        XCTAssertEqual([.request(msgId: "n1")], RouteStack.finishAsk([ask, .triage, .check], msgId: "n1"))
        XCTAssertEqual("triage", Route.triage.path)
        XCTAssertEqual(.triage, Route(path: "triage"))
        XCTAssertTrue(Route.triage.hidesTabBar && Route.triage.isAskFlow)
        // After Send, the ask/check screens go and the new request opens on top of whatever was below.
        XCTAssertEqual([.request(msgId: "n1")], RouteStack.finishAsk([ask, .check], msgId: "n1"))
        XCTAssertEqual([.request(msgId: "old"), .request(msgId: "n1")], RouteStack.finishAsk([.request(msgId: "old"), ask, .check], msgId: "n1"))
        XCTAssertEqual([.request(msgId: "n1")], RouteStack.finishAsk([], msgId: "n1"))
    }
}
