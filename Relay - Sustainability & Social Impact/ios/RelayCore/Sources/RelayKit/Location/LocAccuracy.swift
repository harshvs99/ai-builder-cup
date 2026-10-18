import Foundation

/// SPEC 5.1 loc_acc buckets: 0 no fix, 1 <10 m, 2 <50 m, 3 <200 m, 4 <1 km, 5 >=1 km.
public enum LocAccuracy {
    public static func bucket(_ accuracyMeters: Double?) -> Int {
        guard let a = accuracyMeters, !a.isNaN, a >= 0 else { return 5 } // a fix with unknown accuracy
        if a < 10 { return 1 }
        if a < 50 { return 2 }
        if a < 200 { return 3 }
        if a < 1000 { return 4 }
        return 5
    }
}
