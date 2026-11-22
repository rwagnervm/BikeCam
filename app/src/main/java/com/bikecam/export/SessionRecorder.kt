package com.bikecam.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.bikecam.telemetry.TelemetrySnapshot
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Registra a telemetria bruta de uma gravação: JSONL a 5 Hz (com o segmento de vídeo `seg` e o
 * deslocamento `v` em ms em relação ao início desse segmento, para sincronizar overlay/mini-mapa
 * depois) e pontos a 1 Hz para GPX/FIT. Uma sessão cobre todos os segmentos de um passeio.
 * Ao terminar, publica os arquivos em Documents/BikeCam com o mesmo nome base do primeiro vídeo.
 */
class SessionRecorder(
    private val context: Context,
    val baseName: String,
    private val videoStartEpochMs: Long,
) {
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions").apply { mkdirs() }
    private val jsonlFile = File(dir, "$baseName.jsonl")
    private val jsonl: BufferedWriter = jsonlFile.bufferedWriter()
    private val points = ArrayList<TrackPoint>(4096)
    private var lastPointEpochMs = Long.MIN_VALUE
    private var movingTimeMs = 0L
    private var lastEpochMs = videoStartEpochMs
    private var segment = 1
    private var segmentStartEpochMs = videoStartEpochMs
    private var elevationGainM: Double? = null
    private val lock = Any()
    private var closed = false

    fun onSnapshot(s: TelemetrySnapshot) = synchronized(lock) {
        if (closed || s.epochMs < videoStartEpochMs) return
        lastEpochMs = s.epochMs
        movingTimeMs = s.movingTimeMs
        elevationGainM = s.elevationGainM ?: elevationGainM
        jsonl.write(toJson(s))
        jsonl.write("\n")
        if (s.epochMs - lastPointEpochMs >= 1_000L) {
            lastPointEpochMs = s.epochMs
            points += TrackPoint(
                epochMs = s.epochMs,
                latitude = s.latitude,
                longitude = s.longitude,
                altitudeM = s.altitudeM,
                speedMps = s.speedMps,
                distanceM = s.distanceM,
                heartRateBpm = s.heartRateBpm,
                cadenceRpm = s.cadenceRpm,
            )
        }
    }

    /** Marca o início de um novo arquivo de vídeo (gravação segmentada). */
    fun startSegment(index: Int, epochMs: Long) = synchronized(lock) {
        segment = index
        segmentStartEpochMs = epochMs
    }

    /** Fecha os arquivos e publica GPX, FIT e JSONL. Chamar fora da thread principal. */
    fun finish(): List<String> {
        val snapshot: List<TrackPoint>
        val moving: Long
        val end: Long
        val gain: Double?
        synchronized(lock) {
            if (closed) return emptyList()
            closed = true
            jsonl.close()
            snapshot = points.toList()
            moving = movingTimeMs
            end = lastEpochMs
            gain = elevationGainM
        }
        val outputs = mutableListOf<File>(jsonlFile)
        try {
            val gpx = File(dir, "$baseName.gpx")
            gpx.bufferedWriter().use { GpxWriter.write(it, baseName, snapshot) }
            outputs += gpx
            if (snapshot.isNotEmpty()) {
                val fit = File(dir, "$baseName.fit")
                FitWriter.write(fit, snapshot, RideSummary.of(snapshot, moving, videoStartEpochMs, end, gain))
                outputs += fit
            }
        } catch (e: Exception) {
            Log.e(TAG, "falha ao gerar GPX/FIT", e)
        }
        return outputs.mapNotNull { f -> if (publish(f)) f.name.also { f.delete() } else null }
    }

    private fun publish(file: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: error("insert retornou null")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } else {
            @Suppress("DEPRECATION")
            val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)
            target.mkdirs()
            file.copyTo(File(target, file.name), overwrite = true)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "falha ao publicar ${file.name}; mantido em ${file.absolutePath}", e)
        false
    }

    private fun toJson(s: TelemetrySnapshot): String {
        val sb = StringBuilder(200)
        sb.append("{\"t\":").append(s.epochMs)
        sb.append(",\"seg\":").append(segment)
        sb.append(",\"v\":").append(s.epochMs - segmentStartEpochMs)
        fun num(k: String, v: Number?) { if (v != null) sb.append(",\"").append(k).append("\":").append(v) }
        fun dec(k: String, v: Double?, digits: Int) {
            if (v != null) sb.append(",\"").append(k).append("\":").append(String.format(Locale.US, "%.${digits}f", v))
        }
        dec("spd", s.speedMps?.toDouble(), 2)
        sb.append(",\"src\":\"").append(s.speedSource.name).append('"')
        dec("dist", s.distanceM, 1)
        num("hr", s.heartRateBpm)
        num("cad", s.cadenceRpm)
        dec("hdg", s.headingDeg?.toDouble(), 1)
        dec("lat", s.latitude, 7)
        dec("lon", s.longitude, 7)
        dec("alt", s.altitudeM, 1)
        dec("acc", s.gpsAccuracyM?.toDouble(), 1)
        dec("grade", s.gradePercent?.toDouble(), 1)
        dec("gain", s.elevationGainM, 1)
        sb.append('}')
        return sb.toString()
    }

    companion object {
        private const val TAG = "BikeCam.Session"
        const val FOLDER = "BikeCam"
    }
}
