package com.bikecam.telemetry

import com.bikecam.hud.TrackPath

/** De onde veio a velocidade exibida. */
enum class SpeedSource(val tag: String) { BLE("BLE"), GPS("GPS"), SIMULATED("SIM"), NONE("--") }

/**
 * Estado consolidado da telemetria num instante. Imutável: é seguro passar entre threads
 * (UI, renderizador do OSD, gravador de sessão).
 */
data class TelemetrySnapshot(
    /** Hora de parede (epoch ms) em que o snapshot foi gerado. */
    val epochMs: Long = 0L,
    val speedMps: Float? = null,
    val speedSource: SpeedSource = SpeedSource.NONE,
    val avgSpeedMps: Float? = null,
    val distanceM: Double = 0.0,
    val movingTimeMs: Long = 0L,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    /** Rumo em graus (0 = norte verdadeiro), sentido horário. Orienta a seta do mini-mapa. */
    val headingDeg: Float? = null,
    val altitudeM: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsAccuracyM: Float? = null,
    /** Inclinação da via em % (barômetro + acelerômetro, ou GPS sem barômetro). */
    val gradePercent: Float? = null,
    /** Ganho de elevação acumulado desde o início da gravação, em metros. */
    val elevationGainM: Double? = null,
    /** Trilha percorrida desde o início da gravação (para o mini-mapa). */
    val track: TrackPath = TrackPath.EMPTY,
    val hrConnected: Boolean = false,
    val cscConnected: Boolean = false,
    val gpsFix: Boolean = false,
) {
    val speedKmh: Float? get() = speedMps?.let { it * 3.6f }
    val avgSpeedKmh: Float? get() = avgSpeedMps?.let { it * 3.6f }
}
