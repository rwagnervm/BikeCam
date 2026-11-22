package com.bikecam.telemetry.ble

import java.util.UUID

private fun uuid16(short: Int): UUID =
    UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

object BleUuids {
    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val CSC_SERVICE: UUID = uuid16(0x1816)
    val CSC_MEASUREMENT: UUID = uuid16(0x2A5B)
}

enum class SensorType(val label: String) { HEART_RATE("Frequência cardíaca"), CSC("Velocidade/Cadência") }
