package com.bikecam

import com.bikecam.importer.ActivityFormat
import com.bikecam.importer.ActivityReader
import com.bikecam.render.Mp4Metadata
import com.bikecam.render.RideTimeline
import com.bikecam.render.VideoStartCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId

/**
 * Confere os leitores com arquivos reais em `exemplos/` (fora do git: vídeo grande e trilhas com
 * localização). Sem a pasta, os testes são ignorados.
 */
class SampleFilesTest {
    private val root = listOf(File("exemplos"), File("../exemplos")).firstOrNull { it.isDirectory }
    private val zone = ZoneId.of("America/Sao_Paulo")

    private fun files(dir: String, ext: String): List<File> {
        assumeTrue("sem a pasta exemplos/", root != null)
        return File(root, dir).listFiles { f -> f.name.endsWith(ext, ignoreCase = true) }?.sorted().orEmpty()
            .also { assumeTrue("sem arquivos $ext em $dir", it.isNotEmpty()) }
    }

    @Test
    fun igpsportFormatsAgree() {
        val byFormat = listOf(".gpx", ".tcx", ".fit").associate { ext ->
            val data = files("igpsports", ext).first().inputStream().use { ActivityReader.read(it) }
            data.format to RideTimeline.from(data.samples)
        }
        for ((format, tl) in byFormat) {
            println(
                "%s: %s → %s, %.2f km, ganho %s m, FC=%b cad=%b pot=%b temp=%b, rota %d pts".format(
                    format, Instant.ofEpochMilli(tl.startEpochMs), Instant.ofEpochMilli(tl.endEpochMs),
                    tl.totalDistanceM / 1000, tl.totalGainM?.let { "%.0f".format(it) }, tl.hasHeartRate,
                    tl.hasCadence, tl.hasPower, tl.hasTemperature, tl.route.count,
                ),
            )
        }
        val tcx = byFormat.getValue(ActivityFormat.TCX)
        val fit = byFormat.getValue(ActivityFormat.FIT)
        val gpx = byFormat.getValue(ActivityFormat.GPX)
        assertEquals(tcx.startEpochMs, fit.startEpochMs)
        assertEquals(tcx.totalDistanceM, fit.totalDistanceM, 50.0)
        // GPX não tem distância do sensor: a soma das posições fica perto (pontos mais esparsos).
        assertEquals(tcx.totalDistanceM, gpx.totalDistanceM, tcx.totalDistanceM * 0.05)
        assertTrue(tcx.hasHeartRate && tcx.hasCadence)

        // Mesmo instante nos três formatos: posição a poucos metros e velocidade parecida.
        // Um instante em movimento (dentro do vídeo de exemplo).
        val instant = tcx.startEpochMs + 1_446_000 // 20:16:41Z
        for ((format, tl) in byFormat) {
            val s = tl.snapshotAt(instant)
            println(
                "%s em %s: %.1f km/h, média %.1f, FC %s, cad %s, %.2f km, alt %.0f m, incl %s %%, ganho %.0f m, temp %s".format(
                    format, Instant.ofEpochMilli(instant), s.speedKmh, s.avgSpeedKmh, s.heartRateBpm, s.cadenceRpm,
                    s.distanceM / 1000, s.altitudeM, s.gradePercent?.let { "%.1f".format(it) }, s.elevationGainM, s.temperatureC,
                ),
            )
        }
        val a = tcx.snapshotAt(instant)
        val b = fit.snapshotAt(instant)
        assertTrue(a.speedKmh!! > 10f)
        assertEquals(a.speedKmh!!, b.speedKmh!!, 1.5f)
        assertEquals(a.heartRateBpm!!, b.heartRateBpm!!)
    }

    @Test
    fun goproStartCandidates() {
        val video = files("gopro", ".mp4").first()
        val info = RandomAccessFile(video, "r").use { Mp4Metadata.read(it.channel) }
        println("vídeo: criação=${info.creationEpochMs?.let { Instant.ofEpochMilli(it) }} duração=${info.durationMs} ms timecode=${info.timecode?.label}")
        val tl = files("igpsports", ".tcx").first().inputStream().use { RideTimeline.from(ActivityReader.read(it).samples) }
        for (c in VideoStartCandidates.from(info, video.name, zone)) {
            val s = tl.snapshotAt(c.epochMs)
            println("  %-45s %s  dentro da trilha=%b  v=%s km/h".format(c.description, Instant.ofEpochMilli(c.epochMs), c.epochMs in tl, s.speedKmh))
        }
        assertTrue(info.durationMs > 0)
    }
}
