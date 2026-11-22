package com.bikecam.export

/** Ponto de trilha a 1 Hz, independente de Android (testável na JVM). */
data class TrackPoint(
    val epochMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val speedMps: Float?,
    val distanceM: Double,
    val heartRateBpm: Int?,
    val cadenceRpm: Int?,
)

data class RideSummary(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val distanceM: Double,
    val movingTimeMs: Long,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgCadence: Int?,
    val maxSpeedMps: Float?,
    val totalAscentM: Double? = null,
) {
    companion object {
        fun of(
            points: List<TrackPoint>, movingTimeMs: Long, startEpochMs: Long, endEpochMs: Long, totalAscentM: Double? = null,
        ): RideSummary {
            val hrs = points.mapNotNull { it.heartRateBpm }.filter { it > 0 }
            val cads = points.mapNotNull { it.cadenceRpm }.filter { it > 0 }
            return RideSummary(
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                distanceM = points.lastOrNull()?.distanceM ?: 0.0,
                movingTimeMs = movingTimeMs,
                avgHeartRate = hrs.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                maxHeartRate = hrs.maxOrNull(),
                avgCadence = cads.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                maxSpeedMps = points.mapNotNull { it.speedMps }.maxOrNull(),
                totalAscentM = totalAscentM,
            )
        }
    }
}
