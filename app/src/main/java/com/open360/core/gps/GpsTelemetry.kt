package com.open360.core.gps

data class GpsTelemetry(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    // Blended with the barometer when the phone has one; gpsAltitudeMeters is GPS alone
    val altitudeMeters: Double = 0.0,
    val gpsAltitudeMeters: Double = 0.0,
    val speedKmh: Float = 0f,
    val speedMph: Float = 0f,
    val bearingDegrees: Float = 0f,
    val accuracyMeters: Float = 0f,
    val satellitesInFix: Int = 0,
    val satellitesTotal: Int = 0,
    val hasFix: Boolean = false,
    val timestampEpochMs: Long = 0L,
    // SystemClock.elapsedRealtime() of the fix, for measuring its age without the wall clock
    val fixElapsedRealtimeMs: Long = 0L,
    val isStreamingToCamera: Boolean = false
) {
    val speedKnots: Double get() = (speedKmh / 1.852).toDouble()
}
