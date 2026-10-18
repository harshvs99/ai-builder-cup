import SwiftUI
import RelayKit

/// First-run Language screen until a language is chosen, then the four tabs. Also the one place that turns settings
/// into environment: strings, appearance and larger text.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var router = AppRouter()

    var body: some View {
        let settings = model.settings
        let strings = Strings(settings.language)
        Group {
            if settings.languageConfirmed {
                MainTabs()
            } else {
                LanguageScreen()
            }
        }
        .environment(\.strings, strings)
        .environment(router)
        .tint(Theme.Palette.accent)
        .modifier(LargerTextModifier(enabled: settings.largerText))
        .preferredColorScheme(settings.themeMode.colorScheme)
        .onChange(of: settings.language, initial: true) { _, lang in
            model.ackNotificationText = Self.ackText(Strings(lang))
        }
    }

    /// The local "the control room answered" notification in the app language: their own text when they wrote one,
    /// else the new step in words.
    private static func ackText(_ s: Strings) -> AckNotifier.TextProvider {
        return { need, status in
            let key: RequestTimeline.Key
            switch status {
            case .received: key = .seenByControlRoom
            case .dispatched: key = .helpComing
            case .onTheWay: key = .teamOnTheWay
            case .resolved: key = .delivered
            }
            let written = need.ackText.trimmingCharacters(in: .whitespacesAndNewlines)
            return (s.home.title, written.isEmpty ? s.common.stepTitle(key) : written)
        }
    }
}

/// The floating tab bar. Each tab is its own `NavigationStack` with a large title on its root.
private struct MainTabs: View {
    @Environment(AppRouter.self) private var router
    @Environment(\.strings) private var strings

    var body: some View {
        // Every tap on a tab goes through `select`, including the one on the tab already shown (the plain selection
        // binding would not call its setter for an unchanged value, but this one is always asked), so each tap pops
        // that tab to its root.
        let selection = Binding<AppTab>(get: { router.selected }, set: { router.select($0) })
        TabView(selection: selection) {
            tab(.home) { HomeScreen() }
            tab(.requests) { RequestsScreen() }
            tab(.network) { NetworkScreen() }
            tab(.settings) { SettingsScreen() }
        }
        .relayTabBarMinimizeOnScroll()
    }

    private func tab<Root: View>(_ tab: AppTab, @ViewBuilder root: @escaping () -> Root) -> some View {
        TabStack(navigator: router.navigator(tab), root: root)
            .tabItem { Label(tab.title(strings.common), systemImage: tab.symbol) }
            .tag(tab)
    }
}
