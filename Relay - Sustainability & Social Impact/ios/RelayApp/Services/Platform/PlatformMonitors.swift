import Foundation
import Observation
import UIKit
import Network

/// Reachability: feeds `MeshEngine.setInternetAvailable` (the phone becomes a gateway while this is true and
/// "Gateway mode" allows it). `NWPath.status == .satisfied` means a usable route exists; unlike Android's VALIDATED
/// flag it does not prove the internet is reachable (a captive-portal Wi-Fi counts), so the gateway's own sync
/// failures are what finally mark a bad path (`SyncHealth`).
///
/// The handler runs on the monitor's own queue and calls [onChange] there; [onChange] must be thread-safe
/// (`MeshEngine.setInternetAvailable` is).
final class NetworkMonitor: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "org.relay.network")
    private let lock = NSLock()
    private var available = false
    private var started = false

    /// The last reported state (thread-safe).
    var isAvailable: Bool { lock.lock(); defer { lock.unlock() }; return available }

    func start(onChange: @escaping @Sendable (Bool) -> Void) {
        lock.lock()
        if started { lock.unlock(); return }
        started = true
        lock.unlock()
        monitor.pathUpdateHandler = { [weak self] path in
            let ok = path.status == .satisfied
            self?.lock.lock(); self?.available = ok; self?.lock.unlock()
            onChange(ok)
        }
        monitor.start(queue: queue)
    }

    func stop() { monitor.cancel() }
}

/// Battery level and charging state for `MeshEngine.setBattery` (drives the scan duty mode and the mesh-hours
/// estimate). Main-actor; observes `UIDevice` battery notifications.
@MainActor
final class BatteryMonitor {
    private var tokens: [NSObjectProtocol] = []
    private var onChange: ((Int, Bool) -> Void)?

    /// Starts monitoring and reports the current values immediately. [onChange] gets (percent 0...100 or -1 when
    /// unknown, charging).
    func start(onChange: @escaping (Int, Bool) -> Void) {
        guard tokens.isEmpty else { return }
        self.onChange = onChange
        UIDevice.current.isBatteryMonitoringEnabled = true
        let center = NotificationCenter.default
        for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification] {
            tokens.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.report() }
            })
        }
        report()
    }

    func stop() {
        for t in tokens { NotificationCenter.default.removeObserver(t) }
        tokens.removeAll()
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    private func report() {
        let device = UIDevice.current
        let level = device.batteryLevel // -1 when unknown (Simulator, monitoring off)
        let percent = level < 0 ? -1 : Int((level * 100).rounded())
        let charging = device.batteryState == .charging || device.batteryState == .full
        onChange?(percent, charging)
    }
}
