import SwiftUI

@main
struct RelayApp: App {
    /// The one `AppModel`; screens read it with `@Environment(AppModel.self)`.
    @State private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Started here, not in a view's `.task`: when CoreBluetooth relaunches the app in the background (state
        // restoration) no scene or view is created, but the mesh must still come back.
        let model = AppModel()
        model.start()
        _model = State(initialValue: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { model.start() } // idempotent
                .onChange(of: scenePhase, initial: true) { _, phase in model.setScenePhase(phase) }
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
