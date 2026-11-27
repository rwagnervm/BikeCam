package com.bikecam

import com.bikecam.hud.ElevationProfile
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudWidget
import com.bikecam.importer.ActivitySample
import com.bikecam.render.RideTimeline
import com.bikecam.settings.MountOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderLayoutTest {
    private val renderOnly = HudWidget.entries.filter { it.renderOnly }

    @Test
    fun liveDefaultsKeepRenderOnlyWidgetsOff() {
        for (o in MountOrientation.entries) {
            val live = HudLayout.default(o)
            assertEquals(HudWidget.entries.size, live.placements.size)
            for (w in renderOnly) assertFalse(live[w]!!.enabled)
            val render = HudLayout.defaultRender(o)
            for (w in renderOnly) assertTrue(render[w]!!.enabled)
            // Os demais mostradores ficam onde estavam no layout ao vivo.
            for (w in HudWidget.entries.filterNot { it.renderOnly }) assertEquals(live[w], render[w])
        }
    }

    @Test
    fun oldSavedLayoutGetsNewWidgetsFromTheGivenDefault() {
        val old = "SPEED,1,0.1,0.2,1.0;MAP,0,0.5,0.5,1.0"
        val live = HudLayout.decode(old, MountOrientation.LANDSCAPE)
        assertFalse(live[HudWidget.POWER]!!.enabled)
        val render = HudLayout.decode(old, HudLayout.defaultRender(MountOrientation.LANDSCAPE))
        assertTrue(render[HudWidget.POWER]!!.enabled)
        assertEquals(0.1f, render[HudWidget.SPEED]!!.x)
        assertFalse(render[HudWidget.MAP]!!.enabled)
    }

    @Test
    fun jsonRoundTrip() {
        val original = HudLayout.defaultRender(MountOrientation.LANDSCAPE)
            .update(HudWidget.SPEED) { it.copy(x = 0.25f, y = 0.75f, scale = 1.8f) }
            .update(HudWidget.CLOCK) { it.copy(enabled = false) }
        val json = original.toJson(MountOrientation.LANDSCAPE)
        assertTrue(json.contains("\"bikecam_layout\": 1"))
        val back = HudLayout.fromJson(json, HudLayout.defaultRender(MountOrientation.LANDSCAPE))!!
        assertEquals(original.placements.sortedBy { it.widget }, back.placements.sortedBy { it.widget })
    }

    @Test
    fun jsonImportIsTolerant() {
        val default = HudLayout.defaultRender(MountOrientation.LANDSCAPE)
        // Campos fora de ordem, mostrador desconhecido, valores fora da faixa e mostradores ausentes.
        val json = """{"bikecam_layout":1,"widgets":[
            {"x":2.0,"widget":"SPEED","scale":9,"y":-1,"enabled":true},
            {"widget":"COMPASS","enabled":true,"x":0.5,"y":0.5,"scale":1}]}"""
        val l = HudLayout.fromJson(json, default)!!
        val speed = l[HudWidget.SPEED]!!
        assertEquals(1f, speed.x)
        assertEquals(0f, speed.y)
        assertEquals(HudLayout.MAX_SCALE, speed.scale)
        assertEquals(default[HudWidget.MAP], l[HudWidget.MAP])
        assertEquals(HudWidget.entries.size, l.placements.size)
        assertNull(HudLayout.fromJson("""{"widgets":[]}""", default))
        assertNull(HudLayout.fromJson("""{"bikecam_layout":1,"widgets":[]}""", default))
    }

    @Test
    fun onlyAvailableHidesWidgetsWithoutData() {
        val l = HudLayout.defaultRender(MountOrientation.LANDSCAPE).onlyAvailable(setOf(HudWidget.SPEED, HudWidget.MAP))
        assertEquals(setOf(HudWidget.SPEED, HudWidget.MAP), l.placements.filter { it.enabled }.map { it.widget }.toSet())
    }

    @Test
    fun elevationProfileResamples() {
        val p = ElevationProfile.from(doubleArrayOf(100.0, 200.0, 1100.0), doubleArrayOf(10.0, 20.0, 20.0))!!
        assertEquals(ElevationProfile.POINTS, p.size)
        assertEquals(10f, p.minAltitudeM)
        assertEquals(20f, p.maxAltitudeM)
        assertEquals(10f, p.altitude(0))
        assertEquals(20f, p.altitude(p.size - 1))
        assertEquals(0f, p.fraction(50.0))
        assertEquals(0.5f, p.fraction(600.0), 1e-6f)
        assertEquals(1f, p.fraction(5000.0))
        assertNull(ElevationProfile.from(doubleArrayOf(1.0), doubleArrayOf(1.0)))
    }

    @Test
    fun timelineAvailability() {
        val t0 = 1_795_290_755_000L
        val samples = (0 until 10).map { i ->
            ActivitySample(t0 + i * 1000L, -15.79 + i * 5e-5, -47.88, 300.0 + i, 5f, i * 5.0, heartRateBpm = 120, powerW = 200)
        }
        val tl = RideTimeline.from(samples)
        val a = tl.availableWidgets()
        assertTrue(HudWidget.HEART_RATE in a && HudWidget.POWER in a && HudWidget.ELEVATION_PROFILE in a && HudWidget.MAP in a)
        assertFalse(HudWidget.CADENCE in a || HudWidget.TEMPERATURE in a)
        assertNotNull(tl.snapshotAt(t0 + 4_500).elevationProfile)
        assertEquals(200, tl.snapshotAt(t0 + 4_500).powerW)
    }
}
