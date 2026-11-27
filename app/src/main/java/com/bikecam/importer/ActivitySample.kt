package com.bikecam.importer

/**
 * Uma amostra de uma atividade gravada por outro aparelho (ciclocomputador, relógio). Só a hora é
 * obrigatória: cada formato traz um subconjunto dos campos (o GPX do iGPSport, por exemplo, não
 * tem FC nem cadência).
 */
data class ActivitySample(
    val epochMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    /** Distância acumulada calculada pelo aparelho (normalmente pelo sensor de roda). */
    val distanceM: Double? = null,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val powerW: Int? = null,
    val temperatureC: Float? = null,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null
}

enum class ActivityFormat { GPX, TCX, FIT }

data class ActivityData(val format: ActivityFormat, val samples: List<ActivitySample>)
