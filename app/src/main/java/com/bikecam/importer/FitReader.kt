package com.bikecam.importer

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import java.io.InputStream

/** Mensagens `record` de uma atividade FIT, lidas com o Garmin FIT SDK. */
object FitReader {
    private const val DEGREES_PER_SEMICIRCLE = 180.0 / 2147483648.0

    fun read(input: InputStream): List<ActivitySample> {
        val samples = ArrayList<ActivitySample>()
        val broadcaster = MesgBroadcaster(Decode())
        broadcaster.addListener(RecordMesgListener { m ->
            val time = m.timestamp?.date?.time ?: return@RecordMesgListener
            val lat = m.positionLat
            val lon = m.positionLong
            samples.add(
                ActivitySample(
                    epochMs = time,
                    latitude = if (lat != null && lon != null) lat * DEGREES_PER_SEMICIRCLE else null,
                    longitude = if (lat != null && lon != null) lon * DEGREES_PER_SEMICIRCLE else null,
                    altitudeM = (m.enhancedAltitude ?: m.altitude)?.toDouble(),
                    speedMps = m.enhancedSpeed ?: m.speed,
                    distanceM = m.distance?.toDouble(),
                    heartRateBpm = m.heartRate?.toInt(),
                    cadenceRpm = m.cadence?.toInt(),
                    powerW = m.power,
                    temperatureC = m.temperature?.toFloat(),
                ),
            )
        })
        broadcaster.run(input)
        return samples
    }
}
