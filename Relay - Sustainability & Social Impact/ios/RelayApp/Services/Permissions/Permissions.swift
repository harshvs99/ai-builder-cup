import Foundation
import Observation
import UIKit
@preconcurrency import CoreBluetooth
@preconcurrency import CoreLocation
@preconcurrency import AVFAudio
@preconcurrency import Speech
@preconcurrency import UserNotifications
import RelayKit

/// One permission as the screens need it.
enum PermissionState: Equatable, Sendable {
    /// Never asked: the next `ensure*` shows the system prompt.
    case notDetermined
    case granted
    /// Denied or restricted: iOS will not prompt again; send the user to Settings (`openAppSettings()`).
    case denied

    var isGranted: Bool { self == .granted }
}

/// Permissions asked one at a time, when first needed (design spec, Navigation; same staging as Android's
/// `Permissions.kt`): Bluetooth + notifications when Home first shows (`ensureBluetooth`), location at the first Send
/// (`ensureLocation`), microphone (+ speech recognition) at the first mic tap (`ensureMicrophone`).
///
/// Every `ensure*` returns true straight away when already granted, otherwise shows the system prompt(s) and returns
/// the outcome. Read the state properties in a view body to refresh it; call `refresh()` when the app becomes active
/// again (the user may have changed something in Settings). iOS note: changing Bluetooth/location access in Settings
/// terminates and relaunches the app, so a stale state is rare.
@MainActor @Observable
final class Permissions {
    private(set) var bluetooth: PermissionState = .notDetermined
    private(set) var notifications: PermissionState = .notDetermined
    private(set) var location: PermissionState = .notDetermined
    private(set) var microphone: PermissionState = .notDetermined
    private(set) var speechRecognition: PermissionState = .notDetermined
    /// The radio itself (on/off/unauthorized), reported by the transport. `.unknown` until the transport has started.
    var radio: BluetoothRadioState = .unknown

    @ObservationIgnored private let locationProvider: LocationProvider
    /// Creating the CoreBluetooth managers is what shows the Bluetooth prompt; `AppModel` sets this to "start the mesh".
    @ObservationIgnored var startBluetooth: @MainActor () -> Void = {}

    init(location: LocationProvider) {
        self.locationProvider = location
        refreshSynchronous()
    }

    // ---------------------------------------------------------------- state

    /// Bluetooth allowed and the radio on (what the mesh needs).
    var bluetoothReady: Bool { bluetooth.isGranted && radio.isOn }
    /// Everything voice input needs.
    var voiceReady: Bool { microphone.isGranted && speechRecognition.isGranted }

    /// Re-reads every permission. Call on launch and whenever the scene becomes active.
    func refresh() async {
        refreshSynchronous()
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        notifications = Permissions.map(settings.authorizationStatus)
    }

    private func refreshSynchronous() {
        bluetooth = Permissions.map(CBManager.authorization)
        locationProvider.refreshAuthorization()
        location = Permissions.map(locationProvider.authorization)
        microphone = Permissions.map(AVAudioApplication.shared.recordPermission)
        speechRecognition = Permissions.map(SFSpeechRecognizer.authorizationStatus())
    }

    // ---------------------------------------------------------------- ensure*

    /// Bluetooth (the system prompt appears when the mesh starts), then notifications. Returns whether Bluetooth is
    /// allowed; notifications are optional.
    @discardableResult
    func ensureBluetooth() async -> Bool {
        if CBManager.authorization == .notDetermined {
            startBluetooth()
            // The prompt is up: wait for the answer (the user may take a while).
            var waited = 0
            while CBManager.authorization == .notDetermined && waited < 600 {
                try? await Task.sleep(nanoseconds: 250_000_000)
                waited += 1
            }
        }
        bluetooth = Permissions.map(CBManager.authorization)
        await ensureNotifications()
        return bluetooth.isGranted
    }

    @discardableResult
    func ensureNotifications() async -> Bool {
        let center = UNUserNotificationCenter.current()
        var status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            status = await center.notificationSettings().authorizationStatus
        }
        notifications = Permissions.map(status)
        return notifications.isGranted
    }

    /// When-In-Use location, at the first Send. Returns whether a fix can be taken.
    @discardableResult
    func ensureLocation() async -> Bool {
        let status = await locationProvider.requestWhenInUse()
        location = Permissions.map(status)
        return location.isGranted
    }

    /// Microphone, then speech recognition, at the first mic tap. Returns whether voice input can run.
    @discardableResult
    func ensureMicrophone() async -> Bool {
        if AVAudioApplication.shared.recordPermission == .undetermined {
            _ = await AVAudioApplication.requestRecordPermission()
        }
        microphone = Permissions.map(AVAudioApplication.shared.recordPermission)
        guard microphone.isGranted else { return false }
        if SFSpeechRecognizer.authorizationStatus() == .notDetermined {
            _ = await PlatformAsync.requestSpeechAuthorization()
        }
        speechRecognition = Permissions.map(SFSpeechRecognizer.authorizationStatus())
        return voiceReady
    }

    /// The Settings page of this app (for permissions denied for good, and for Bluetooth: there is no in-app switch).
    func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    // ---------------------------------------------------------------- mapping

    private static func map(_ s: CBManagerAuthorization) -> PermissionState {
        switch s {
        case .allowedAlways: return .granted
        case .notDetermined: return .notDetermined
        case .denied, .restricted: return .denied
        @unknown default: return .denied
        }
    }

    private static func map(_ s: CLAuthorizationStatus) -> PermissionState {
        switch s {
        case .authorizedWhenInUse, .authorizedAlways: return .granted
        case .notDetermined: return .notDetermined
        case .denied, .restricted: return .denied
        @unknown default: return .denied
        }
    }

    private static func map(_ s: AVAudioApplication.recordPermission) -> PermissionState {
        switch s {
        case .granted: return .granted
        case .undetermined: return .notDetermined
        case .denied: return .denied
        @unknown default: return .denied
        }
    }

    private static func map(_ s: SFSpeechRecognizerAuthorizationStatus) -> PermissionState {
        switch s {
        case .authorized: return .granted
        case .notDetermined: return .notDetermined
        case .denied, .restricted: return .denied
        @unknown default: return .denied
        }
    }

    private static func map(_ s: UNAuthorizationStatus) -> PermissionState {
        switch s {
        case .authorized, .provisional, .ephemeral: return .granted
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        @unknown default: return .denied
        }
    }
}

/// Callback-style system APIs wrapped as async. They live outside any actor on purpose: the completion handlers
/// are invoked on arbitrary threads, and a closure formed inside a `@MainActor` method would be assumed
/// main-actor-isolated and trap under Swift 6.
enum PlatformAsync {
    static func requestSpeechAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0) }
        }
    }
}
