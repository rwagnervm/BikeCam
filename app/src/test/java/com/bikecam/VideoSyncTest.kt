package com.bikecam

import com.bikecam.render.GoProChapters
import com.bikecam.render.Mp4Info
import com.bikecam.render.Mp4Metadata
import com.bikecam.render.StartSource
import com.bikecam.render.Timecode
import com.bikecam.render.VideoStartCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.time.ZoneId

class VideoSyncTest {
    private val zone = ZoneId.of("America/Sao_Paulo") // −03:00, sem horário de verão
    private val created = 1_795_292_196_000L // 2026-11-21T20:16:36Z

    // --- Mp4Metadata (arquivo sintético) ---

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }

    private fun ints(vararg v: Int) = ByteBuffer.allocate(4 * v.size).apply { v.forEach { putInt(it) } }.array()

    private fun syntheticMp4(frameNumber: Int): File {
        val ftyp = box("ftyp", "mp41".toByteArray(), ints(0))
        // mdat: 8 bytes de lixo + a amostra tmcd (contador de quadros).
        val mdatPayload = ByteArray(8) + ints(frameNumber)
        val mdat = box("mdat", mdatPayload)
        val sampleOffset = ftyp.size + 8 + 8
        val macCreated = (created / 1000 + 2_082_844_800L).toInt()
        val mvhd = box("mvhd", ints(0, macCreated, macCreated, 30000, 30000 * 128) + ByteArray(80))
        val hdlr = box("hdlr", ints(0, 0) + "tmcd".toByteArray() + ints(0, 0, 0) + byteArrayOf(0))
        val tmcdEntry = box(
            "tmcd",
            ByteArray(6) + byteArrayOf(0, 1) + ints(0, 0, 30000, 1001) + byteArrayOf(30, 0),
        )
        val stsd = box("stsd", ints(0, 1), tmcdEntry)
        val stco = box("stco", ints(0, 1, sampleOffset))
        val trak = box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd, stco))))
        val moov = box("moov", mvhd, trak)
        return File.createTempFile("sync", ".mp4").apply { writeBytes(ftyp + mdat + moov) }
    }

    @Test
    fun readsCreationDurationAndTimecode() {
        val frames = 1_864_039 // rótulo 17:15:34:19 a 30 fps nominais
        val f = syntheticMp4(frames)
        try {
            val info = RandomAccessFile(f, "r").use { Mp4Metadata.read(it.channel) }
            assertEquals(created, info.creationEpochMs)
            assertEquals(128_000L, info.durationMs)
            val tc = info.timecode
            assertNotNull(tc)
            tc!!
            assertEquals(frames.toLong(), tc.frameNumber)
            assertEquals("17:15:34:19", tc.label)
            // Hora real: 1.864.039 × 1001/30000 s = 62.196,77 s = 17:16:36,77.
            assertEquals(62_196.77, tc.secondsOfDay, 0.01)
        } finally {
            f.delete()
        }
    }

    // --- Candidatos de hora de início ---

    @Test
    fun candidatesFromGoProMetadata() {
        // Como no exemplo real: nominal 29 no tmcd, timecode com fração de segundo.
        val tc = Timecode(1_864_039, 30000, 1001, 29, false)
        val list = VideoStartCandidates.from(Mp4Info(created, 128_161, tc), "GX010127_1795371688674.MP4", zone)
        assertEquals(StartSource.TIMECODE, list.first().source)
        val bySource = list.associateBy { it.source }
        // 17:16:36,77 local = 20:16:36,77Z
        assertNear(created + 768, bySource[StartSource.TIMECODE]!!.epochMs, 2.0)
        assertTrue(bySource[StartSource.TIMECODE]!!.description.contains("17:15:34:19"))
        assertNull(bySource[StartSource.CREATION_UTC]) // a menos de 1 s do timecode: omitido
        assertEquals(created + 3 * 3_600_000L, bySource[StartSource.CREATION_LOCAL]!!.epochMs)
        assertEquals(created - 128_161, bySource[StartSource.CREATION_END]!!.epochMs)
        assertNull(bySource[StartSource.FILE_NAME]) // sufixo do Quik não é data
    }

    @Test
    fun candidatesWithoutTimecode() {
        val list = VideoStartCandidates.from(Mp4Info(created, 60_000, null), "VID_20261121_171636.mp4", zone)
        assertEquals(
            listOf(StartSource.CREATION_UTC, StartSource.CREATION_LOCAL, StartSource.CREATION_END),
            list.map { it.source }, // o nome do arquivo dá a mesma hora do creation_time
        )
        assertEquals(created, list.first().epochMs)
    }

    private fun assertNear(expected: Long, actual: Long, delta: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta)

    @Test
    fun candidatesFromFileName() {
        val local = VideoStartCandidates.fromName("VID_20261121_171636.mp4", zone)!!
        assertEquals(created, local.epochMs)
        val pixel = VideoStartCandidates.fromName("PXL_20261121_201636123.mp4", zone)!!
        assertEquals(created + 123, pixel.epochMs)
        assertEquals(created, VideoStartCandidates.fromName("20261121_171636.mp4", zone)!!.epochMs)
        assertNull(VideoStartCandidates.fromName("GX010127.MP4", zone))
        assertNull(VideoStartCandidates.fromName("VID_20261341_171636.mp4", zone)) // mês inválido
    }

    // --- Capítulos da GoPro ---

    @Test
    fun groupsGoProChapters() {
        val groups = GoProChapters.group(
            listOf("GX020127.MP4", "phone.mp4", "GX010127_1795371688674.MP4", "GX010128.MP4", "GP010042.MP4", "GOPR0042.MP4"),
        )
        assertEquals(
            listOf(
                listOf("GX010127_1795371688674.MP4", "GX020127.MP4"),
                listOf("phone.mp4"),
                listOf("GX010128.MP4"),
                listOf("GOPR0042.MP4", "GP010042.MP4"),
            ),
            groups,
        )
    }
}
