package org.relay.app.location

/** SPEC 5.1 loc_acc buckets: 0 no fix, 1 <10 m, 2 <50 m, 3 <200 m, 4 <1 km, 5 >=1 km. */
object LocAccuracy {
    fun bucket(accuracyMeters: Float?): Int = when {
        accuracyMeters == null || accuracyMeters.isNaN() || accuracyMeters < 0f -> 5 // a fix with unknown accuracy
        accuracyMeters < 10f -> 1
        accuracyMeters < 50f -> 2
        accuracyMeters < 200f -> 3
        accuracyMeters < 1000f -> 4
        else -> 5
    }
}
