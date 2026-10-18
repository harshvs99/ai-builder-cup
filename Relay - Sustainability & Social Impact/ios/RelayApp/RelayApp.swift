import SwiftUI
import UIKit
import BackgroundTasks
import RelayKit

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
        // Best-effort background refresh of the Ready pack (contract section 3.3): iOS decides when, about every 6 h at best.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.readyTaskId, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            Self.scheduleReadyRefresh()
            let work = Task { @MainActor in
                _ = await model.ready.refresh(.background)
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
        }
        Self.scheduleReadyRefresh()
    }

    static let readyTaskId = "org.relay.ios.ready-refresh"

    static func scheduleReadyRefresh() {
        let req = BGAppRefreshTaskRequest(identifier: readyTaskId)
        req.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600)
        try? BGTaskScheduler.shared.submit(req)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { model.start() } // idempotent
                .onChange(of: scenePhase, initial: true) { _, phase in model.setScenePhase(phase) }
                // The on-device model is the biggest thing the app can hold in RAM: free it when hidden or when iOS asks.
                .onChange(of: scenePhase) { _, phase in if phase == .background { Self.scheduleReadyRefresh(); Task { await GemmaRunner.shared.onBackgrounded() } } }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
                    Task { await GemmaRunner.shared.onMemoryWarning() }
                }
        }
    }
}
