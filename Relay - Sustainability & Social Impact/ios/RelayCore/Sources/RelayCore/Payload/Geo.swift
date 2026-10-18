import Foundation

/// Degrees <-> 1e-5 degree integers.
public enum Geo {
    /// `floor(abs(v * 100000.0) + 0.5) * sign`, in Double arithmetic, exactly as the Python reference
    /// encoder (`int(floor(abs(v) + 0.5)) * (1 if v >= 0 else -1)`). Deliberately not `rounded()`.
    /// NaN maps to 0; values outside the i32 range are clamped.
    public static func toE5(_ deg: Double) -> Int {
        if deg.isNaN { return 0 }
        let v = deg * 100000.0
        let mag = (abs(v) + 0.5).rounded(.down)
        let signed = v >= 0 ? mag : -mag
        if signed >= Double(Int32.max) { return Int(Int32.max) }
        if signed <= Double(Int32.min) { return Int(Int32.min) }
        return Int(signed)
    }

    public static func fromE5(_ e5: Int) -> Double { Double(e5) / 100000.0 }
}
