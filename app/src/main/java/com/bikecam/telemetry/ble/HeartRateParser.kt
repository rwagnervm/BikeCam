package com.bikecam.telemetry.ble

/** Característica 0x2A37 (Heart Rate Measurement). */
object HeartRateParser {
    /** Retorna o BPM, ou null se o pacote for inválido. */
    fun parse(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val uint16 = flags and 0x01 != 0
        return if (uint16) {
            if (data.size < 3) null
            else (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            if (data.size < 2) null else data[1].toInt() and 0xFF
        }
    }
}
