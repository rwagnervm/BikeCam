package com.bikecam

import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudWidget
import com.bikecam.hud.Mercator
import com.bikecam.hud.TrackBuilder
import com.bikecam.settings.MountOrientation
import com.bikecam.telemetry.ElevationEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HudLayoutTest {
    @Test fun encodeDecodeRoundTrip() {
        val original = HudLayout.default(MountOrientation.LANDSCAPE)
            .update(HudWidget.SPEED) { it.copy(x = 0.3f, y = 0.4f, scale = 2f) }
            .update(HudWidget.CLOCK) { it.copy(enabled = false) }
        val decoded = HudLayout.decode(original.encode(), MountOrientation.LANDSCAPE)
        assertEquals(original, decoded)
    }

    @Test fun legacyCompassIsDroppedAndMapIsAdded() {
        val legacy = "SPEED,1,0.1,0.9,1.5;HEADING,1,0.5,0.1,0.9"
        val layout = HudLayout.decode(legacy, MountOrientation.PORTRAIT)
        assertNull(layout.placements.firstOrNull { it.widget.name == "HEADING" })
        assertEquals(0.1f, layout[HudWidget.SPEED]!!.x)
        assertNotNull(layout[HudWidget.MAP])
        assertEquals(HudWidget.entries.size, layout.placements.size)
    }

    @Test fun garbageFallsBackToDefault() {
        assertEquals(HudLayout.default(MountOrientation.LANDSCAPE), HudLayout.decode("lixo;;x,y", MountOrientation.LANDSCAPE))
        assertEquals(HudLayout.default(MountOrientation.LANDSCAPE), HudLayout.decode(null, MountOrientation.LANDSCAPE))
    }

    @Test fun valuesAreClamped() {
        val layout = HudLayout.decode("SPEED,1,7,-3,99", MountOrientation.LANDSCAPE)
        val p = layout[HudWidget.SPEED]!!
        assertEquals(1f, p.x)
        assertEquals(0f, p.y)
        assertEquals(HudLayout.MAX_SCALE, p.scale)
    }

    @Test fun defaultsHaveNoCompassAndShowMapAndGrade() {
        for (o in MountOrientation.entries) {
            val d = HudLayout.default(o)
            assertTrue(d[HudWidget.MAP]!!.enabled)
            assertTrue(d[HudWidget.GRADE]!!.enabled)
            assertTrue(d[HudWidget.ELEVATION_GAIN]!!.enabled)
            assertEquals(HudWidget.entries.size, d.placements.size)
        }
    }
}

class TrackTest {
    @Test fun mercatorBasics() {
        assertEquals(0.5, Mercator.x(0.0), 1e-12)
        assertEquals(0.5, Mercator.y(0.0), 1e-12)
        assertTrue(Mercator.y(45.0) < 0.5) // norte fica para cima (y menor)
        assertEquals(Mercator.EARTH_CIRCUMFERENCE_M, Mercator.metersPerUnit(0.0), 1e-6)
    }

    @Test fun pointsCloserThanSpacingAreSkipped() {
        val t = TrackBuilder(minSpacingM = 5.0)
        val metersToDeg = 1.0 / 111_320.0
        assertTrue(t.add(-15.0, -47.0))
        assertFalse(t.add(-15.0 + 2 * metersToDeg, -47.0))
        assertTrue(t.add(-15.0 + 10 * metersToDeg, -47.0))
        assertEquals(2, t.path.count)
    }

    @Test fun oldViewsStayValidWhileTrackGrows() {
        val t = TrackBuilder(minSpacingM = 0.0)
        repeat(10) { t.add(-15.0 + it * 0.001, -47.0) }
        val view = t.path
        val lastY = view.y(9)
        repeat(10_000) { t.add(-14.0 + it * 0.001, -47.0) } // força o array a crescer várias vezes
        assertEquals(10, view.count)
        assertEquals(lastY, view.y(9), 0.0)
        assertEquals(10_010, t.path.count)
        t.reset()
        assertEquals(0, t.path.count)
        assertEquals(lastY, view.y(9), 0.0)
    }
}

class ElevationEstimatorTest {
    private val dt = 200L

    @Test fun steadyClimbWithBarometer() {
        val e = ElevationEstimator()
        val speed = 6f
        var dist = 0.0
        var out = e.update(dt, 500.0, null, null, dist, speed, null)
        repeat(5 * 120) { // 2 min a 6 m/s numa rampa de 5%
            dist += speed * dt / 1000.0
            out = e.update(dt, 500.0 + dist * 0.05, null, null, dist, speed, null)
        }
        val climbed = dist * 0.05 // 36 m
        assertEquals(5f, out.gradePercent!!, 0.4f)
        assertEquals(climbed, out.gainM!!, 3.0)
    }

    @Test fun stoppedRiderGainsNothingFromPressureDrift() {
        val e = ElevationEstimator()
        var out = e.update(dt, 800.0, null, null, 0.0, 0f, null)
        repeat(5 * 600) { i -> // 10 min parado: clima sobe 15 m e o sensor oscila ±1,5 m
            val alt = 800.0 + 15.0 * i / 3000.0 + 1.5 * kotlin.math.sin(i / 7.0)
            out = e.update(dt, alt, null, null, 0.0, 0f, null)
        }
        assertEquals(0.0, out.gainM!!, 0.0)
    }

    @Test fun barometerIsCalibratedToGpsWithoutCreatingGain() {
        val e = ElevationEstimator()
        var out = e.update(dt, 900.0, null, null, 0.0, 0f, null)
        assertEquals(900.0, out.altitudeM!!, 0.01) // sem GPS: atmosfera padrão
        repeat(50) { out = e.update(dt, 900.0, 1_000.0, 5f, 0.0, 5f, null) }
        assertEquals(1_000.0, out.altitudeM!!, 1.0)
        assertEquals(0.0, out.gainM!!, 0.0)
    }

    @Test fun badGpsAccuracyIsIgnored() {
        val e = ElevationEstimator()
        val out = e.update(dt, null, 1_234.0, 80f, 0.0, 5f, null)
        assertNull(out.altitudeM)
    }

    @Test fun accelerometerAnticipatesRampThenBarometerWins() {
        val e = ElevationEstimator()
        val speed = 6f
        var dist = 0.0
        fun step(tilt: Double) = e.update(dt, 300.0, null, null, dist, speed, tilt).also { dist += speed * dt / 1000.0 }
        repeat(5 * 30) { step(0.0) } // plano, celular nivelado
        val before = step(0.0).gradePercent!!
        assertEquals(0f, before, 0.3f)
        // O celular inclina como numa rampa de ~8%, mas o barômetro continua plano.
        var out = step(0.08)
        repeat(5) { out = step(0.08) } // 1 s
        assertTrue("pitch deveria puxar a inclinação para cima: ${out.gradePercent}", out.gradePercent!! > 2f)
        repeat(5 * 30) { out = step(0.08) } // 30 s depois, vale o barômetro (o pitch fixo é o ângulo do suporte)
        assertEquals(0f, out.gradePercent!!, 0.5f)
    }

    @Test fun gpsNoiseOnFlatRoadAddsLittleGain() {
        val e = ElevationEstimator()
        val rnd = Random(42)
        val speed = 6f
        var dist = 0.0
        var out = e.update(dt, null, 50.0, 5f, dist, speed, null)
        repeat(5 * 600) { // 10 min no plano com GPS oscilando ±3 m
            dist += speed * dt / 1000.0
            out = e.update(dt, null, 50.0 + (rnd.nextDouble() * 6 - 3), 5f, dist, speed, null)
        }
        assertTrue("ganho ${out.gainM}", out.gainM!! < 3.0)
    }

    @Test fun resetClearsGainButKeepsAltitude() {
        val e = ElevationEstimator()
        var dist = 0.0
        repeat(5 * 60) {
            dist += 1.2
            e.update(dt, 100.0 + dist * 0.06, null, null, dist, 6f, null)
        }
        e.reset()
        val out = e.update(dt, 100.0 + dist * 0.06, null, null, 0.0, 6f, null)
        assertEquals(0.0, out.gainM!!, 0.0)
        assertNotNull(out.altitudeM)
    }
}
