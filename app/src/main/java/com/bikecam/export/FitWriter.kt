package com.bikecam.export

import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.File
import java.util.Date
import java.util.TimeZone

/** Atividade FIT (ciclismo) com registros a 1 Hz, gerada com o Garmin FIT SDK. */
object FitWriter {
    private const val SEMICIRCLES_PER_DEGREE = 2147483648.0 / 180.0

    fun write(file: File, points: List<TrackPoint>, summary: RideSummary) {
        val start = DateTime(Date(summary.startEpochMs))
        val end = DateTime(Date(summary.endEpochMs))
        val elapsedS = ((summary.endEpochMs - summary.startEpochMs) / 1000f).coerceAtLeast(0f)
        val timerS = summary.movingTimeMs / 1000f

        val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
        try {
            encoder.write(FileIdMesg().apply {
                type = com.garmin.fit.File.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 1
                serialNumber = 1L
                timeCreated = start
            })
            encoder.write(EventMesg().apply {
                timestamp = start
                event = Event.TIMER
                eventType = EventType.START
            })
            for (p in points) {
                encoder.write(RecordMesg().apply {
                    timestamp = DateTime(Date(p.epochMs))
                    if (p.latitude != null && p.longitude != null) {
                        positionLat = (p.latitude * SEMICIRCLES_PER_DEGREE).toInt()
                        positionLong = (p.longitude * SEMICIRCLES_PER_DEGREE).toInt()
                    }
                    p.altitudeM?.let { enhancedAltitude = it.toFloat() }
                    p.speedMps?.let { enhancedSpeed = it }
                    distance = p.distanceM.toFloat()
                    p.heartRateBpm?.let { heartRate = it.coerceIn(0, 255).toShort() }
                    p.cadenceRpm?.let { cadence = it.coerceIn(0, 255).toShort() }
                })
            }
            encoder.write(EventMesg().apply {
                timestamp = end
                event = Event.TIMER
                eventType = EventType.STOP_ALL
            })
            encoder.write(LapMesg().apply {
                messageIndex = 0
                timestamp = end
                startTime = start
                totalElapsedTime = elapsedS
                totalTimerTime = timerS
                totalDistance = summary.distanceM.toFloat()
                event = Event.LAP
                eventType = EventType.STOP
                sport = Sport.CYCLING
                summary.avgHeartRate?.let { avgHeartRate = it.toShort() }
                summary.maxHeartRate?.let { maxHeartRate = it.toShort() }
                summary.avgCadence?.let { avgCadence = it.toShort() }
                summary.totalAscentM?.let { totalAscent = it.toInt().coerceIn(0, 65_534) }
            })
            encoder.write(SessionMesg().apply {
                messageIndex = 0
                timestamp = end
                startTime = start
                totalElapsedTime = elapsedS
                totalTimerTime = timerS
                totalDistance = summary.distanceM.toFloat()
                sport = Sport.CYCLING
                subSport = SubSport.ROAD
                firstLapIndex = 0
                numLaps = 1
                event = Event.SESSION
                eventType = EventType.STOP
                summary.avgHeartRate?.let { avgHeartRate = it.toShort() }
                summary.maxHeartRate?.let { maxHeartRate = it.toShort() }
                summary.avgCadence?.let { avgCadence = it.toShort() }
                summary.maxSpeedMps?.let { enhancedMaxSpeed = it }
                summary.totalAscentM?.let { totalAscent = it.toInt().coerceIn(0, 65_534) }
                if (timerS > 0) enhancedAvgSpeed = (summary.distanceM / timerS).toFloat()
            })
            val tzOffsetS = TimeZone.getDefault().getOffset(summary.endEpochMs) / 1000L
            encoder.write(ActivityMesg().apply {
                timestamp = end
                numSessions = 1
                type = Activity.MANUAL
                event = Event.ACTIVITY
                eventType = EventType.STOP
                totalTimerTime = timerS
                localTimestamp = end.timestamp + tzOffsetS
            })
        } finally {
            encoder.close()
        }
    }
}
