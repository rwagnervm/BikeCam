package com.bikecam.telemetry.ble

/** Leitura bruta da característica 0x2A5B (CSC Measurement). Tempos em 1/1024 s. */
data class CscMeasurement(
    val wheelRevs: Long? = null,
    val wheelEventTime: Int? = null,
    val crankRevs: Int? = null,
    val crankEventTime: Int? = null,
)

object CscParser {
    fun parse(data: ByteArray): CscMeasurement? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val hasWheel = flags and 0x01 != 0
        val hasCrank = flags and 0x02 != 0
        var i = 1
        var wheelRevs: Long? = null
        var wheelTime: Int? = null
        var crankRevs: Int? = null
        var crankTime: Int? = null
        if (hasWheel) {
            if (data.size < i + 6) return null
            wheelRevs = u32(data, i); i += 4
            wheelTime = u16(data, i); i += 2
        }
        if (hasCrank) {
            if (data.size < i + 4) return null
            crankRevs = u16(data, i); i += 2
            crankTime = u16(data, i)
        }
        return CscMeasurement(wheelRevs, wheelTime, crankRevs, crankTime)
    }

    private fun u16(d: ByteArray, i: Int): Int = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(d: ByteArray, i: Int): Long =
        (d[i].toLong() and 0xFF) or ((d[i + 1].toLong() and 0xFF) shl 8) or
            ((d[i + 2].toLong() and 0xFF) shl 16) or ((d[i + 3].toLong() and 0xFF) shl 24)
}

/**
 * Converte leituras CSC sucessivas em velocidade (m/s) e cadência (rpm), tratando
 * rollover dos contadores e o caso "parado" (sem revoluções novas por [stopTimeoutMs]).
 * Não é thread-safe.
 */
class CscCalculator(private val stopTimeoutMs: Long = 3_000L) {
    var speedMps: Float? = null
        private set
    var cadenceRpm: Int? = null
        private set
    /** Momento (relógio local) da última atualização de velocidade/cadência. */
    var speedUpdatedAtMs: Long = 0L
        private set
    var cadenceUpdatedAtMs: Long = 0L
        private set

    private var prevWheelRevs: Long? = null
    private var prevWheelTime = 0
    /** Último instante em que a roda girou (ou em que o calculador começou). */
    var lastWheelMoveMs = 0L
        private set
    private var prevCrankRevs: Int? = null
    private var prevCrankTime = 0
    var lastCrankMoveMs = 0L
        private set

    fun reset() {
        speedMps = null; cadenceRpm = null
        prevWheelRevs = null; prevCrankRevs = null
    }

    fun update(m: CscMeasurement, nowMs: Long, wheelCircumferenceM: Float) {
        if (m.wheelRevs != null && m.wheelEventTime != null) {
            val prev = prevWheelRevs
            if (prev != null) {
                val revDelta = (m.wheelRevs - prev) and 0xFFFFFFFFL
                val timeDelta = (m.wheelEventTime - prevWheelTime) and 0xFFFF
                when {
                    revDelta in 1..MAX_WHEEL_REVS_PER_EVENT && timeDelta > 0 -> {
                        speedMps = (revDelta * wheelCircumferenceM / (timeDelta / 1024.0)).toFloat()
                        lastWheelMoveMs = nowMs
                    }
                    revDelta == 0L && nowMs - lastWheelMoveMs > stopTimeoutMs -> speedMps = 0f
                    revDelta == 0L && speedMps == null -> speedMps = 0f
                }
            } else {
                lastWheelMoveMs = nowMs
            }
            speedUpdatedAtMs = nowMs
            prevWheelRevs = m.wheelRevs
            prevWheelTime = m.wheelEventTime
        }
        if (m.crankRevs != null && m.crankEventTime != null) {
            val prev = prevCrankRevs
            if (prev != null) {
                val revDelta = (m.crankRevs - prev) and 0xFFFF
                val timeDelta = (m.crankEventTime - prevCrankTime) and 0xFFFF
                when {
                    revDelta in 1..MAX_CRANK_REVS_PER_EVENT && timeDelta > 0 -> {
                        val rpm = revDelta * 60.0 * 1024.0 / timeDelta
                        if (rpm <= MAX_RPM) cadenceRpm = rpm.toInt()
                        lastCrankMoveMs = nowMs
                    }
                    revDelta == 0 && nowMs - lastCrankMoveMs > stopTimeoutMs -> cadenceRpm = 0
                    revDelta == 0 && cadenceRpm == null -> cadenceRpm = 0
                }
            } else {
                lastCrankMoveMs = nowMs
            }
            cadenceUpdatedAtMs = nowMs
            prevCrankRevs = m.crankRevs
            prevCrankTime = m.crankEventTime
        }
    }

    /** Para sensores que param de notificar quando a roda/pedivela para. */
    fun decay(nowMs: Long) {
        if (speedMps != null && nowMs - lastWheelMoveMs > stopTimeoutMs) speedMps = 0f
        if (cadenceRpm != null && nowMs - lastCrankMoveMs > stopTimeoutMs) cadenceRpm = 0
    }

    companion object {
        const val MAX_WHEEL_REVS_PER_EVENT = 50L
        const val MAX_CRANK_REVS_PER_EVENT = 20
        const val MAX_RPM = 250.0
    }
}
