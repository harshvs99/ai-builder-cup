import SwiftUI
import UIKit

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
                // The on-device model is the biggest thing the app can hold in RAM: free it when hidden or when iOS asks.
                .onChange(of: scenePhase) { _, phase in if phase == .background { Task { await GemmaRunner.shared.onBackgrounded() } } }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
                    Task { await GemmaRunner.shared.onMemoryWarning() }
                }
        }
    }
}
