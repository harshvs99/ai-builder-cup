import SwiftUI
import RelayKit

/// Navigation state of one tab: the pushed screens (the tab root is not in the path) and the ask flow in progress.
@MainActor @Observable
final class TabNavigator {
    var path: [Route] = []
    /// Not observed: screens observe the controller itself, and `ensureFlow()` may run while a view body is evaluated.
    @ObservationIgnored private(set) var flow: AskFlowController?

    func push(_ route: Route) { path.append(route) }

    /// Back to the tab's root screen, dropping any ask flow in progress.
    func popToRoot() {
        path = []
        flow = nil
    }
    func pop() { if !path.isEmpty { path.removeLast() } }

    /// New request (voice or text), optionally prefilled from an existing request ("+ Add").
    func startAsk(mode: AskMode, prefillFrom: String? = nil) {
        flow = AskFlowController()
        path.append(.ask(mode: mode, prefillFrom: prefillFrom))
    }

    func toCheck() { path = RouteStack.toCheck(path) }

    /// New request -> the triage questions (medical and rescue requests).
    func toTriage() { path = RouteStack.toTriage(path) }

    /// After Send: open the new request and drop New request / Check from the stack.
    func finishAsk(msgId: String) {
        path = RouteStack.finishAsk(path, msgId: msgId)
        flow = nil
    }

    /// "Cancel" on New request: back to wherever the flow started.
    func cancelAsk() {
        path.removeAll { $0.isAskFlow }
        flow = nil
    }

    /// The flow the ask screens share; created on demand if the stack was restored without one.
    func ensureFlow() -> AskFlowController {
        if let flow { return flow }
        let created = AskFlowController()
        flow = created
        return created
    }
}

/// The tab selection and one navigator per tab.
@MainActor @Observable
final class AppRouter {
    var selected: AppTab = .home
    let navigators: [AppTab: TabNavigator] = Dictionary(uniqueKeysWithValues: AppTab.allCases.map { ($0, TabNavigator()) })

    /// Switches tab and lands on its root screen (Android `navigateTab`): a tab never reopens on the last pushed
    /// screen, and choosing the tab you are already on pops it to its root.
    func select(_ tab: AppTab) {
        navigator(tab).popToRoot()
        selected = tab
    }

    func navigator(_ tab: AppTab) -> TabNavigator { navigators[tab] ?? TabNavigator() }
}

/// The ask flow's state shared by New request and Check and send (Android `AskViewModel`).
@MainActor @Observable
final class AskFlowController {
    var flow = AskFlow()
    /// "Something else" was opened on New request.
    var moreOpened = false
    /// "Done, check it" was tapped and the recogniser is finishing.
    var busy = false
    /// The triage conversation between New request and Check and send; kept here so coming back from Check resumes it.
    let triage = TriageController()
}

/// Destinations pushed on a tab's `NavigationStack`. Roots are tabs and never pushed.
struct RouteDestination: View {
    var route: Route

    var body: some View {
        switch route {
        case .request(let id): RequestDetailScreen(msgId: id)
        case .ask(let mode, let from): NewRequestScreen(mode: mode, prefillFrom: from)
        case .triage: TriageScreen()
        case .check: CheckScreen()
        case .console: DeveloperScreen(isConsole: true)
        case .developer: DeveloperScreen(isConsole: false)
        case .ready: ReadyScreen()
        case .household: HouseholdScreen()
        case .language, .home, .requests, .network, .settings: EmptyView()
        }
    }
}

/// A tab: a `NavigationStack` bound to the tab's navigator, with `Route` destinations.
struct TabStack<Root: View>: View {
    var navigator: TabNavigator
    @ViewBuilder var root: () -> Root

    var body: some View {
        @Bindable var nav = navigator
        NavigationStack(path: $nav.path) {
            root()
                .navigationDestination(for: Route.self) { RouteDestination(route: $0) }
        }
        .environment(navigator)
    }
}
