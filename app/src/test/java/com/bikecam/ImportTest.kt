package com.bikecam

import com.bikecam.export.FitWriter
import com.bikecam.export.RideSummary
import com.bikecam.export.TrackPoint
import com.bikecam.importer.ActivityFormat
import com.bikecam.importer.ActivityReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class ImportTest {
    private val t0 = 1_795_290_755_000L // 2026-11-21T19:52:35Z

    @Test
    fun gpxWithBomAndGarminExtensions() {
        val gpx = "﻿" + """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <metadata><time>2026-11-21T19:00:00Z</time></metadata>
              <trk><trkseg>
                <trkpt lat="-15.7939" lon="-47.8828"><ele>311.8</ele><time>2026-11-21T19:52:35Z</time>
                  <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr><gpxtpx:cad>80</gpxtpx:cad>
                  <gpxtpx:atemp>31.5</gpxtpx:atemp></gpxtpx:TrackPointExtension><power>210</power></extensions>
                </trkpt>
                <trkpt lat="-15.7938" lon="-47.8829"><ele>312.2</ele><time>2026-11-21T19:52:37.500Z</time></trkpt>
                <trkpt lat="-15.7937" lon="-47.8830"><ele>313</ele></trkpt>
              </trkseg></trk>
            </gpx>"""
        val data = ActivityReader.read(gpx.byteInputStream())
        assertEquals(ActivityFormat.GPX, data.format)
        assertEquals(2, data.samples.size) // o ponto sem hora é descartado
        val a = data.samples[0]
        assertEquals(t0, a.epochMs)
        assertEquals(-15.7939, a.latitude!!, 1e-9)
        assertEquals(311.8, a.altitudeM!!, 1e-9)
        assertEquals(120, a.heartRateBpm)
        assertEquals(80, a.cadenceRpm)
        assertEquals(210, a.powerW)
        assertEquals(31.5f, a.temperatureC!!, 1e-6f)
        assertEquals(t0 + 2_500, data.samples[1].epochMs)
        assertNull(data.samples[1].heartRateBpm)
    }

    @Test
    fun tcxIgnoresLapAggregates() {
        val tcx = """<?xml version="1.0" encoding="UTF-8"?>
            <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
                xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
            <Activities><Activity Sport="Biking"><Id>2026-11-21T19:52:35Z</Id>
            <Lap StartTime="2026-11-21T19:52:35Z">
              <AverageHeartRateBpm><Value>143</Value></AverageHeartRateBpm><Cadence>59</Cadence>
              <Track>
                <Trackpoint><Time>2026-11-21T19:52:35Z</Time>
                  <Position><LatitudeDegrees>-15.7939288</LatitudeDegrees><LongitudeDegrees>-47.88281</LongitudeDegrees></Position>
                  <AltitudeMeters>311.8</AltitudeMeters><DistanceMeters>2.3</DistanceMeters>
                  <HeartRateBpm><Value>103</Value></HeartRateBpm><Cadence>24</Cadence>
                  <Extensions><ns3:TPX><ns3:Speed>1.658</ns3:Speed><ns3:Watts>150</ns3:Watts></ns3:TPX></Extensions>
                </Trackpoint>
                <Trackpoint><Time>2026-11-21T19:52:43Z</Time><DistanceMeters>20.0</DistanceMeters>
                  <HeartRateBpm><Value>110</Value></HeartRateBpm></Trackpoint>
              </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""
        val data = ActivityReader.read(tcx.byteInputStream())
        assertEquals(ActivityFormat.TCX, data.format)
        assertEquals(2, data.samples.size)
        val a = data.samples[0]
        assertEquals(t0, a.epochMs)
        assertEquals(103, a.heartRateBpm)
        assertEquals(24, a.cadenceRpm)
        assertEquals(2.3, a.distanceM!!, 1e-9)
        assertEquals(1.658f, a.speedMps!!, 1e-6f)
        assertEquals(150, a.powerW)
        val b = data.samples[1]
        assertNull(b.cadenceRpm)
        assertNull(b.latitude)
        assertEquals(110, b.heartRateBpm)
    }

    @Test
    fun fitRoundTrip() {
        val points = (0 until 5).map { i ->
            TrackPoint(t0 + i * 1000L, -15.79 + i * 1e-4, -47.88, 300.0 + i, 7f, i * 7.0, 140 + i, 85)
        }
        val f = File.createTempFile("import", ".fit")
        try {
            FitWriter.write(f, points, RideSummary.of(points, 4000, t0, t0 + 4000))
            val data = f.inputStream().use { ActivityReader.read(it) }
            assertEquals(ActivityFormat.FIT, data.format)
            assertEquals(5, data.samples.size)
            val s = data.samples[3]
            assertEquals(t0 + 3000, s.epochMs)
            assertEquals(-15.79 + 3e-4, s.latitude!!, 1e-6)
            assertEquals(303.0, s.altitudeM!!, 0.2)
            assertEquals(7f, s.speedMps!!, 0.01f)
            assertEquals(21.0, s.distanceM!!, 0.01)
            assertEquals(143, s.heartRateBpm)
            assertEquals(85, s.cadenceRpm)
        } finally {
            f.delete()
        }
    }

    @Test
    fun unknownFormatFails() {
        assertThrows(IOException::class.java) { ActivityReader.read("lat,lon\n1,2".byteInputStream()) }
    }
}
