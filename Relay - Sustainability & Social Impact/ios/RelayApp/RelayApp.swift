import SwiftUI

@main
struct RelayApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// Placeholder shell: four tabs, each a `NavigationStack` with a large title. Screens replace the contents.
struct RootView: View {
    var body: some View {
        TabView {
            placeholderTab("Relay", systemImage: "house", tabTitle: "Home")
            placeholderTab("Your requests", systemImage: "list.bullet", tabTitle: "Requests")
            placeholderTab("Network", systemImage: "point.3.connected.trianglepath.dotted", tabTitle: "Network")
            placeholderTab("Settings", systemImage: "gearshape", tabTitle: "Settings")
        }
        .relayTabBarMinimizeOnScroll()
    }

    private func placeholderTab(_ title: String, systemImage: String, tabTitle: String) -> some View {
        NavigationStack {
            Text(title)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.Palette.surface)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.large)
        }
        .tabItem { Label(tabTitle, systemImage: systemImage) }
    }
}

#Preview {
    RootView()
}
