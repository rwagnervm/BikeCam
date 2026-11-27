package com.bikecam

import com.bikecam.importer.ActivitySample
import com.bikecam.render.RideTimeline
import com.bikecam.telemetry.SpeedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTimelineTest {
    private val t0 = 1_795_290_755_000L
    private val metersPerDegLat = 111_195.0

    /** Rumo norte a 5 m/s, uma amostra a cada 8 s, subindo 1 m a cada 8 s (2,5 %). */
    private fun ride(count: Int, startMs: Long = t0, startDist: Double = 0.0, startAlt: Double = 300.0) =
        (0 until count).map { i ->
            val d = startDist + i * 40.0
            ActivitySample(
                epochMs = startMs + i * 8_000L,
                latitude = -15.79 + d / metersPerDegLat,
                longitude = -47.88,
                altitudeM = startAlt + i * 1.0,
                speedMps = 5f,
                distanceM = d,
                heartRateBpm = 120 + i,
                cadenceRpm = 80,
            )
        }

    @Test
    fun interpolatesBetweenSparseSamples() {
        val tl = RideTimeline.from(ride(10))
        val s = tl.snapshotAt(t0 + 4_000) // metade entre a 1ª e a 2ª amostra
        assertEquals(SpeedSource.FILE, s.speedSource)
        assertEquals(20.0, s.distanceM, 1e-6)
        assertEquals(120.5, s.heartRateBpm!!.toDouble(), 0.5)
        assertEquals(300.5, s.altitudeM!!, 1e-6)
        assertEquals(-15.79 + 20.0 / metersPerDegLat, s.latitude!!, 1e-9)
        assertEquals(4_000L, s.movingTimeMs)
        assertEquals(5f, s.avgSpeedMps!!, 1e-3f)
        assertEquals(0f, s.headingDeg!!, 0.5f)
        assertEquals(2.5f, s.gradePercent!!, 0.01f)
        assertTrue(s.hrConnected && s.cscConnected && s.gpsFix)
    }

    @Test
    fun longGapIsAStop() {
        // 10 amostras, parada de 2 min, mais 10 amostras saindo do mesmo lugar.
        val first = ride(10)
        val last = first.last()
        val second = ride(10, startMs = last.epochMs + 120_000, startDist = 360.0, startAlt = 309.0)
        val tl = RideTimeline.from(first + second)

        val stopped = tl.snapshotAt(last.epochMs + 60_000)
        assertEquals(0f, stopped.speedMps!!, 0f)
        assertEquals(0, stopped.cadenceRpm)
        assertEquals(360.0, stopped.distanceM, 1e-6)
        assertEquals(last.latitude!!, stopped.latitude!!, 1e-12)
        // O tempo parado não conta como movimento.
        assertEquals(72_000L, stopped.movingTimeMs)

        val after = tl.snapshotAt(second.last().epochMs)
        assertEquals(72_000L * 2, after.movingTimeMs)
        assertEquals(5f, after.avgSpeedMps!!, 1e-3f)
    }

    @Test
    fun elevationGainUsesHysteresis() {
        val alts = listOf(300.0, 301.0, 300.5, 301.5, 302.5, 302.0, 305.0, 303.0, 304.0)
        val samples = alts.mapIndexed { i, a -> ActivitySample(t0 + i * 1000L, altitudeM = a, distanceM = i * 5.0) }
        val tl = RideTimeline.from(samples)
        // 300 → 302,5 (+2,5) → 305 (+2,5); as oscilações menores que 2 m não somam.
        assertEquals(5.0, tl.totalGainM!!, 1e-9)
    }

    @Test
    fun speedDerivedFromPositionsWhenFileHasNone() {
        val samples = ride(6).map { it.copy(speedMps = null, distanceM = null) }
        val s = RideTimeline.from(samples).snapshotAt(t0 + 20_000)
        assertEquals(5f, s.speedMps!!, 0.01f)
        assertEquals(100.0, s.distanceM, 0.1)
    }

    @Test
    fun routeAndTravelledTrack() {
        val tl = RideTimeline.from(ride(10))
        assertTrue(tl.route.count >= 10)
        val s = tl.snapshotAt(t0 + 4 * 8_000)
        assertEquals(tl.route.count, s.route.count)
        assertTrue(s.track.count in 1 until tl.route.count)
    }

    @Test
    fun outsideTheRideOnlyHasTheClock() {
        val tl = RideTimeline.from(ride(3))
        val s = tl.snapshotAt(t0 - 1)
        assertEquals(t0 - 1, s.epochMs)
        assertNull(s.speedMps)
        assertNull(s.latitude)
        assertNotNull(s.route)
    }

    @Test
    fun missingFieldsStayNull() {
        val samples = ride(4).map { it.copy(heartRateBpm = null, cadenceRpm = null) }
        val s = RideTimeline.from(samples).snapshotAt(t0 + 1000)
        assertNull(s.heartRateBpm)
        assertNull(s.cadenceRpm)
        assertNull(s.powerW)
        assertTrue(!s.hrConnected && !s.cscConnected)
    }
}
