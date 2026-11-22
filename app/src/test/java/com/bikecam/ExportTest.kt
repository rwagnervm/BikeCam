package com.bikecam

import com.bikecam.export.FitWriter
import com.bikecam.export.GpxWriter
import com.bikecam.export.RideSummary
import com.bikecam.export.TrackPoint
import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesgListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter

class ExportTest {
    private val start = 1_763_000_000_000L
    private val points = (0 until 10).map { i ->
        TrackPoint(
            epochMs = start + i * 1000L,
            latitude = if (i == 3) null else -15.79 + i * 0.0001,
            longitude = -47.88,
            altitudeM = 1050.0 + i,
            speedMps = 6f,
            distanceM = i * 6.0,
            heartRateBpm = 130 + i,
            cadenceRpm = 85,
        )
    }

    @Test fun gpxContainsTrackPointsWithExtensions() {
        val sw = StringWriter()
        GpxWriter.write(sw, "ride_test", points)
        val xml = sw.toString()
        assertEquals(9, Regex("<trkpt ").findAll(xml).count()) // ponto sem posição é omitido
        assertTrue(xml.contains("<gpxtpx:hr>130</gpxtpx:hr>"))
        assertTrue(xml.contains("<gpxtpx:cad>85</gpxtpx:cad>"))
        assertTrue(xml.contains("lat=\"-15.7900000\""))
        assertFalse(xml.contains(",")) // nada de vírgula decimal por causa do Locale
    }

    @Test fun fitIsValidAndDecodable() {
        val f = File.createTempFile("ride", ".fit")
        try {
            val summary = RideSummary.of(points, movingTimeMs = 9_000, startEpochMs = start, endEpochMs = start + 9_000)
            FitWriter.write(f, points, summary)

            assertTrue(f.inputStream().use { Decode().checkFileIntegrity(it) })

            var records = 0
            var maxHr = 0
            var sessionDistance = 0f
            val decode = Decode()
            val b = MesgBroadcaster(decode)
            b.addListener(RecordMesgListener { m -> records++; maxHr = maxOf(maxHr, m.heartRate.toInt()) })
            b.addListener(SessionMesgListener { m -> sessionDistance = m.totalDistance })
            f.inputStream().use { decode.read(it, b, b) }

            assertEquals(10, records)
            assertEquals(139, maxHr)
            assertEquals(54f, sessionDistance, 0.01f)
        } finally {
            f.delete()
        }
    }
}
