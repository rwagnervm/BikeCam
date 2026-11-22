package com.bikecam.telemetry

/**
 * Regra de prioridade de velocidade: sensor BLE (CSC) se estiver conectado e com dado recente;
 * senão o GPS do celular; senão nada.
 */
object SpeedSelector {
    const val FRESH_MS = 3_000L

    fun select(
        nowMs: Long,
        bleSpeedMps: Float?,
        bleUpdatedAtMs: Long,
        gpsSpeedMps: Float?,
        gpsUpdatedAtMs: Long,
    ): Pair<Float?, SpeedSource> {
        if (bleSpeedMps != null && nowMs - bleUpdatedAtMs <= FRESH_MS) {
            return bleSpeedMps to SpeedSource.BLE
        }
        if (gpsSpeedMps != null && nowMs - gpsUpdatedAtMs <= FRESH_MS) {
            // Ruído do GPS parado fica em ~0,3 m/s; abaixo de ~1 km/h consideramos parado.
            val s = if (gpsSpeedMps < 0.3f) 0f else gpsSpeedMps
            return s to SpeedSource.GPS
        }
        return null to SpeedSource.NONE
    }
}
