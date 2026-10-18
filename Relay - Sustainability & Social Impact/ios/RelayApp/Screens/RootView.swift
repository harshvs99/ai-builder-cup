import SwiftUI
import RelayCore
import RelayKit

/// First-run Language screen until a language is chosen, then the four tabs. Also the one place that turns settings
/// into environment: strings, appearance and larger text.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var router = AppRouter()
    /// The "X is not available yet; showing English." line, shown once per disabled language.
    @State private var languageNotice: String?

    var body: some View {
        let settings = model.settings
        let strings = Strings(settings.effectiveLanguage)
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
        .onChange(of: settings.effectiveLanguage, initial: true) { _, lang in
            model.ackNotificationText = Self.ackText(Strings(lang))
        }
        .onChange(of: settings.languageResolution, initial: true) { _, resolution in
            // The stored language was switched off (backend or build): say so once, then keep showing English.
            guard LanguageCatalog.shouldAnnounce(resolution, alreadyAnnounced: Lang.fromTag(settings.languageNoticeFor)),
                  let gone = resolution.unavailable else { return }
            model.updateSettings { var s = $0; s.languageNoticeFor = gone.tag; return s }
            languageNotice = strings.home.languageUnavailable(AppLanguages.of(gone).englishName)
        }
        .onChange(of: model.ready.openRequested) { _, requested in
            guard requested else { return }
            model.ready.openRequested = false
            router.select(.home)
            router.navigator(.home).push(.ready)
        }
        .alert(languageNotice ?? "", isPresented: Binding(get: { languageNotice != nil }, set: { if !$0 { languageNotice = nil } })) {
            Button(strings.common.done) { languageNotice = nil }
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
