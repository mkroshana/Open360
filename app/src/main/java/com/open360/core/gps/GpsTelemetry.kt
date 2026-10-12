package com.open360.core.gps

data class GpsTelemetry(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val speedKmh: Float = 0f,
    val speedMph: Float = 0f,
    val bearingDegrees: Float = 0f,
    val accuracyMeters: Float = 0f,
    val satellitesInFix: Int = 0,
    val satellitesTotal: Int = 0,
    val hasFix: Boolean = false,
    val timestampEpochMs: Long = 0L,
    val isStreamingToCamera: Boolean = false
)
