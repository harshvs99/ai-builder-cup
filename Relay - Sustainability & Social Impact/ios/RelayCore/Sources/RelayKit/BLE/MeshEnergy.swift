import Foundation

/// Mesh hours remaining from battery percentage: a deliberately simple drain model.
///
/// ASSUMPTIONS (not measured; replace with the 2-hour drain test in PLAN.md section 11):
///  - phone idle with screen off, radios quiet: `basePctPerHour` % of battery per hour
///  - connectable advertising always on: `advertisePctPerHour`
///  - scanning at full duty: `scanPctPerHourFull`, scaled linearly by the mode's duty fraction
/// Resulting drain: continuous 4.0 %/h (25 h from full), 3 s / 12 s 1.92 %/h (52 h), low-battery mode 1.66 %/h.
/// Connection traffic is not modelled (the mesh is idle most of the time).
public enum MeshEnergy {
    public static let basePctPerHour = 1.0
    public static let advertisePctPerHour = 0.4
    public static let scanPctPerHourFull = 2.6

    public static func drainPctPerHour(_ mode: DutyCycle.Mode) -> Double {
        basePctPerHour + advertisePctPerHour + scanPctPerHourFull * mode.dutyFraction
    }

    /// Hours until empty, or nil when charging or the battery level is unknown.
    public static func hoursRemaining(batteryPct: Int, charging: Bool, mode: DutyCycle.Mode) -> Double? {
        if charging || batteryPct < 0 { return nil }
        return Double(min(batteryPct, 100)) / drainPctPerHour(mode)
    }
}
