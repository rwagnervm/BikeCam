package com.bikecam

import com.bikecam.telemetry.ble.CscCalculator
import com.bikecam.telemetry.ble.CscMeasurement
import com.bikecam.telemetry.ble.CscParser
import com.bikecam.telemetry.ble.HeartRateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleParsersTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun heartRateUint8() = assertEquals(72, HeartRateParser.parse(bytes(0x00, 72)))

    @Test fun heartRateUint16() = assertEquals(300, HeartRateParser.parse(bytes(0x01, 0x2C, 0x01)))

    @Test fun heartRateTruncated() = assertNull(HeartRateParser.parse(bytes(0x01, 0x2C)))

    @Test fun cscWheelAndCrank() {
        // flags=3, wheel revs=0x00000100 (256), wheel time=0x0400 (1024), crank revs=10, crank time=2048
        val m = CscParser.parse(bytes(0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x0A, 0x00, 0x00, 0x08))!!
        assertEquals(256L, m.wheelRevs)
        assertEquals(1024, m.wheelEventTime)
        assertEquals(10, m.crankRevs)
        assertEquals(2048, m.crankEventTime)
    }

    @Test fun cscCrankOnly() {
        val m = CscParser.parse(bytes(0x02, 0x05, 0x00, 0x00, 0x04))!!
        assertNull(m.wheelRevs)
        assertEquals(5, m.crankRevs)
        assertEquals(1024, m.crankEventTime)
    }

    @Test fun cscWheelUint32IsUnsigned() {
        val m = CscParser.parse(bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00))!!
        assertEquals(0xFFFFFFFFL, m.wheelRevs)
    }

    @Test fun speedFromWheelRevs() {
        val c = CscCalculator()
        c.update(CscMeasurement(wheelRevs = 100, wheelEventTime = 0), 0, 2.0f)
        // 3 voltas em 1 s (1024 ticks) com roda de 2 m = 6 m/s
        c.update(CscMeasurement(wheelRevs = 103, wheelEventTime = 1024), 1000, 2.0f)
        assertEquals(6.0f, c.speedMps!!, 0.001f)
    }

    @Test fun speedHandlesEventTimeRollover() {
        val c = CscCalculator()
        c.update(CscMeasurement(wheelRevs = 10, wheelEventTime = 65_000), 0, 2.0f)
        // 65000 -> 488 atravessa o limite de 16 bits: delta = 1024
        c.update(CscMeasurement(wheelRevs = 12, wheelEventTime = 488), 1000, 2.0f)
        assertEquals(4.0f, c.speedMps!!, 0.001f)
    }

    @Test fun cadenceHandlesRevRollover() {
        val c = CscCalculator()
        c.update(CscMeasurement(crankRevs = 65_535, crankEventTime = 0), 0, 2.0f)
        // 65535 -> 1 = 2 voltas em 1 s = 120 rpm
        c.update(CscMeasurement(crankRevs = 1, crankEventTime = 1024), 1000, 2.0f)
        assertEquals(120, c.cadenceRpm)
    }

    @Test fun speedDropsToZeroWhenStopped() {
        val c = CscCalculator(stopTimeoutMs = 3_000)
        c.update(CscMeasurement(wheelRevs = 1, wheelEventTime = 0), 0, 2.0f)
        c.update(CscMeasurement(wheelRevs = 2, wheelEventTime = 1024), 1000, 2.0f)
        c.update(CscMeasurement(wheelRevs = 2, wheelEventTime = 1024), 2000, 2.0f)
        assertEquals(2.0f, c.speedMps!!, 0.001f) // ainda dentro do timeout
        c.update(CscMeasurement(wheelRevs = 2, wheelEventTime = 1024), 4500, 2.0f)
        assertEquals(0f, c.speedMps!!, 0.001f)
    }

    @Test fun ignoresSensorReset() {
        val c = CscCalculator()
        c.update(CscMeasurement(wheelRevs = 5000, wheelEventTime = 0), 0, 2.0f)
        c.update(CscMeasurement(wheelRevs = 5002, wheelEventTime = 1024), 1000, 2.0f)
        // contador zerado (troca de bateria): delta gigante por rollover não pode virar velocidade absurda
        c.update(CscMeasurement(wheelRevs = 1, wheelEventTime = 100), 2000, 2.0f)
        assertEquals(4.0f, c.speedMps!!, 0.001f)
    }
}
