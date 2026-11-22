package com.bikecam

import com.bikecam.telemetry.SpeedSelector
import com.bikecam.telemetry.SpeedSource
import com.bikecam.telemetry.TripComputer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryLogicTest {
    @Test fun blePreferredWhenFresh() {
        val (v, src) = SpeedSelector.select(10_000, 8f, 9_500, 7f, 9_800)
        assertEquals(8f, v)
        assertEquals(SpeedSource.BLE, src)
    }

    @Test fun gpsFallbackWhenBleStale() {
        val (v, src) = SpeedSelector.select(10_000, 8f, 5_000, 7f, 9_800)
        assertEquals(7f, v)
        assertEquals(SpeedSource.GPS, src)
    }

    @Test fun gpsFallbackWhenNoBle() {
        val (_, src) = SpeedSelector.select(10_000, null, 0, 7f, 9_800)
        assertEquals(SpeedSource.GPS, src)
    }

    @Test fun gpsNoiseAtRestIsZero() {
        val (v, _) = SpeedSelector.select(10_000, null, 0, 0.2f, 9_800)
        assertEquals(0f, v)
    }

    @Test fun nothingWhenAllStale() {
        val (v, src) = SpeedSelector.select(10_000, 8f, 1_000, 7f, 1_000)
        assertNull(v)
        assertEquals(SpeedSource.NONE, src)
    }

    @Test fun tripIntegratesDistanceAndMovingAverage() {
        val t = TripComputer()
        repeat(50) { t.update(200, 5f) }   // 10 s a 5 m/s = 50 m
        repeat(50) { t.update(200, 0f) }   // 10 s parado: não entra na média
        assertEquals(50.0, t.distanceM, 0.001)
        assertEquals(10_000L, t.movingTimeMs)
        assertEquals(5f, t.avgSpeedMps!!, 0.001f)
    }

    @Test fun tripIgnoresHugeGaps() {
        val t = TripComputer()
        t.update(60_000, 5f)
        assertEquals(0.0, t.distanceM, 0.0)
    }
}
