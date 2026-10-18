import Foundation
import Observation
@preconcurrency import CoreLocation
import RelayKit

/// One-shot position fixes for geotagging a NEED (Android `LocationProvider`), on `CLLocationManager` with
/// When-In-Use authorization. While started it listens for `windowMs`, keeps the best recent fix (`FixPolicy`) and
/// stops by itself once the fix is good enough (< 10 m). Feeds `FixWait` through `fixUpdates()`.
///
/// Main-actor only; CoreLocation delivers its delegate callbacks on the thread that created the manager (main).
@MainActor @Observable
final class LocationProvider {
    static let windowMs: Int64 = 30_000

    /// The best recent fix, nil until one arrives (kept after `stop()` until `reset()`).
    private(set) var fix: Fix?
    private(set) var isListening = false
    private(set) var authorization: CLAuthorizationStatus = .notDetermined
    /// The system Location Services switch. Refreshed off the main thread (the query can block).
    private(set) var systemEnabled = true

    /// Thread-safe mirror of `fix` for the mesh engine's LOC_HINT responder, which reads it off the main actor.
    @ObservationIgnored private let latestFix: LatestFix
    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var delegate: LocationDelegate?
    @ObservationIgnored private let broadcaster = Broadcaster<Fix?>(nil)
    @ObservationIgnored private var stopTask: Task<Void, Never>?
    @ObservationIgnored private var authContinuations: [CheckedContinuation<CLAuthorizationStatus, Never>] = []

    init(latestFix: LatestFix = LatestFix()) {
        self.latestFix = latestFix
        authorization = manager.authorizationStatus
        let d = LocationDelegate(
            onLocations: { [weak self] locs in self?.accept(locs) },
            onAuthorization: { [weak self] in self?.authorizationChanged() },
            onError: { _ in })
        delegate = d
        manager.delegate = d
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = true
        refreshSystemEnabled()
    }

    // ---------------------------------------------------------------- state

    var hasPermission: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }
    var isDenied: Bool { authorization == .denied || authorization == .restricted }

    /// What the confirm card shows, from `LocationState` (shared with Android).
    var ui: LocUi {
        LocationState.ui(hasPermission: hasPermission, systemEnabled: systemEnabled, fix: fix, nowMs: currentTimeMs())
    }

    /// Current fix now, then every change; for `FixWait.wait`.
    func fixUpdates() -> AsyncStream<Fix?> { broadcaster.updates() }

    // ---------------------------------------------------------------- authorization

    /// Shows the When-In-Use prompt if the user was never asked, and returns the resulting status. Returns at once
    /// when already decided.
    func requestWhenInUse() async -> CLAuthorizationStatus {
        guard manager.authorizationStatus == .notDetermined else { return manager.authorizationStatus }
        return await withCheckedContinuation { cont in
            authContinuations.append(cont)
            manager.requestWhenInUseAuthorization()
        }
    }

    private func authorizationChanged() {
        authorization = manager.authorizationStatus
        refreshSystemEnabled()
        if authorization != .notDetermined {
            let waiting = authContinuations
            authContinuations.removeAll()
            for c in waiting { c.resume(returning: authorization) }
        }
        if !hasPermission { stop() }
    }

    func refreshAuthorization() {
        authorization = manager.authorizationStatus
        refreshSystemEnabled()
    }

    private func refreshSystemEnabled() {
        DispatchQueue.global(qos: .utility).async {
            let on = CLLocationManager.locationServicesEnabled()
            Task { @MainActor in self.systemEnabled = on }
        }
    }

    // ---------------------------------------------------------------- listening

    /// Begins (or extends) a listening window and seeds `fix` from a recent last-known location. Returns false when
    /// there is no permission or Location Services are off (nothing is registered then).
    @discardableResult
    func start() -> Bool {
        refreshAuthorization()
        guard hasPermission, systemEnabled else { return false }
        let now = currentTimeMs()
        if let f = fix, !FixPolicy.isUsable(f, nowMs: now) { setFix(nil) }
        if !isListening {
            if let last = manager.location {
                let f = LocationProvider.toFix(last)
                if FixPolicy.isUsable(f, nowMs: now), FixPolicy.isBetter(current: fix, candidate: f, nowMs: now) { setFix(f) }
            }
            manager.startUpdatingLocation()
            isListening = true
        }
        stopTask?.cancel()
        stopTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(LocationProvider.windowMs) * 1_000_000)
            if !Task.isCancelled { self?.stop() }
        }
        return true
    }

    /// Stop listening (idempotent). The last fix is kept until `reset()`.
    func stop() {
        stopTask?.cancel()
        stopTask = nil
        if isListening { manager.stopUpdatingLocation() }
        isListening = false
    }

    /// Forget the kept fix (after a NEED was sent, so the next one does not reuse it).
    func reset() { setFix(nil) }

    private func accept(_ locations: [LocationSnapshot]) {
        let now = currentTimeMs()
        for l in locations {
            let candidate = LocationProvider.toFix(l)
            if FixPolicy.isBetter(current: fix, candidate: candidate, nowMs: now) { setFix(candidate) }
            if FixPolicy.isGoodEnough(candidate) { stop() }
        }
    }

    private func setFix(_ f: Fix?) {
        fix = f
        latestFix.set(f)
        broadcaster.send(f)
    }

    private static func toFix(_ l: LocationSnapshot) -> Fix {
        Fix(lat: l.latitude, lon: l.longitude, accuracyM: l.horizontalAccuracy >= 0 ? l.horizontalAccuracy : Double.nan,
            atMs: l.timestampMs > 0 ? l.timestampMs : currentTimeMs())
    }

    private static func toFix(_ l: CLLocation) -> Fix { toFix(LocationSnapshot(l)) }
}

/// The fields of a `CLLocation` the provider needs, as a Sendable value that can cross from the delegate callback.
struct LocationSnapshot: Sendable {
    let latitude: Double
    let longitude: Double
    let horizontalAccuracy: Double
    let timestampMs: Int64

    init(_ l: CLLocation) {
        latitude = l.coordinate.latitude
        longitude = l.coordinate.longitude
        horizontalAccuracy = l.horizontalAccuracy
        timestampMs = Int64((l.timestamp.timeIntervalSince1970 * 1000).rounded(.down))
    }
}

/// `CLLocationManagerDelegate` shim. Callbacks are forwarded to the main actor as Sendable values.
private final class LocationDelegate: NSObject, CLLocationManagerDelegate, @unchecked Sendable {
    private let onLocations: @MainActor ([LocationSnapshot]) -> Void
    private let onAuthorization: @MainActor () -> Void
    private let onError: @MainActor (Error) -> Void

    init(onLocations: @escaping @MainActor ([LocationSnapshot]) -> Void,
         onAuthorization: @escaping @MainActor () -> Void,
         onError: @escaping @MainActor (Error) -> Void) {
        self.onLocations = onLocations; self.onAuthorization = onAuthorization; self.onError = onError
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let snaps = locations.map(LocationSnapshot.init)
        let f = onLocations
        Task { @MainActor in f(snaps) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let f = onAuthorization
        Task { @MainActor in f() }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let f = onError
        Task { @MainActor in f(error) }
    }
}
