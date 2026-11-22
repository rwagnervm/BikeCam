package com.bikecam.telemetry

/**
 * Acumula distância, tempo em movimento e velocidade média integrando a velocidade selecionada.
 * Não é thread-safe: use a partir de um único coroutine/thread.
 */
class TripComputer(private val movingThresholdMps: Float = 0.6f) {
    var distanceM: Double = 0.0
        private set
    var movingTimeMs: Long = 0L
        private set

    val avgSpeedMps: Float?
        get() = if (movingTimeMs < 1_000L) null else (distanceM / (movingTimeMs / 1000.0)).toFloat()

    fun reset() {
        distanceM = 0.0
        movingTimeMs = 0L
    }

    fun update(dtMs: Long, speedMps: Float?) {
        if (speedMps == null || dtMs <= 0 || dtMs > 5_000L) return
        if (speedMps >= movingThresholdMps) {
            distanceM += speedMps * dtMs / 1000.0
            movingTimeMs += dtMs
        }
    }
}
