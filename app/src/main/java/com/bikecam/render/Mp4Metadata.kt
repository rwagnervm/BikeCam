package com.bikecam.render

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * Timecode de uma trilha `tmcd` (QuickTime): contador de quadros desde a meia-noite.
 *
 * A GoPro conta quadros reais (29,97 fps), mas o rótulo HH:MM:SS:FF usa a taxa nominal (30) sem
 * drop-frame, então o rótulo atrasa 0,1 % em relação ao relógio (~62 s às 17 h). A hora certa é a de
 * [secondsOfDay], que usa a duração real do quadro. No exemplo (HERO11 Mini) ela bate com o
 * `creation_time` em menos de 1 s.
 */
data class Timecode(
    val frameNumber: Long,
    val timescale: Int,
    val frameDuration: Int,
    /** Campo "number of frames" do `tmcd`. Não confiável: a GoPro grava 29 para 29,97 fps. */
    val nominalFps: Int,
    val dropFrame: Boolean,
) {
    val secondsOfDay: Double get() = frameNumber * frameDuration.toDouble() / timescale

    /** Rótulo como os editores mostram (taxa nominal arredondada, sem drop-frame). */
    val label: String get() {
        val fps = (timescale.toDouble() / frameDuration).roundToInt().coerceAtLeast(1)
        val totalS = frameNumber / fps
        return "%02d:%02d:%02d:%02d".format(totalS / 3600 % 24, totalS / 60 % 60, totalS % 60, frameNumber % fps)
    }
}

data class Mp4Info(
    /** `creation_time` do `mvhd` convertido como se fosse UTC (o formato não guarda fuso). */
    val creationEpochMs: Long?,
    val durationMs: Long,
    val timecode: Timecode?,
)

/**
 * Leitor mínimo de MP4/MOV: só o `moov` (mvhd e trilha tmcd). Não decodifica mídia e não depende
 * de Android, então funciona com um `FileChannel` de arquivo ou de `ParcelFileDescriptor`.
 */
object Mp4Metadata {
    private const val MAC_TO_UNIX_EPOCH_S = 2_082_844_800L
    private const val MAX_MOOV_BYTES = 64 * 1024 * 1024

    fun read(ch: FileChannel): Mp4Info {
        val moov = findTopLevel(ch, "moov") ?: throw IOException("Arquivo sem caixa moov (não é MP4/MOV?)")
        var creation: Long? = null
        var durationMs = 0L
        var timecode: Timecode? = null
        for (box in children(moov, 0, moov.limit())) {
            when (box.type) {
                "mvhd" -> {
                    val v = moov.get(box.start).toInt()
                    val p = box.start + 4
                    val created: Long
                    val timescale: Long
                    val duration: Long
                    if (v == 1) {
                        created = moov.getLong(p)
                        timescale = u32(moov, p + 16)
                        duration = moov.getLong(p + 20)
                    } else {
                        created = u32(moov, p)
                        timescale = u32(moov, p + 8)
                        duration = u32(moov, p + 12)
                    }
                    if (created > MAC_TO_UNIX_EPOCH_S) creation = (created - MAC_TO_UNIX_EPOCH_S) * 1000
                    if (timescale > 0) durationMs = duration * 1000 / timescale
                }
                "trak" -> if (timecode == null) timecode = readTimecode(ch, moov, box)
            }
        }
        return Mp4Info(creation, durationMs, timecode)
    }

    private fun readTimecode(ch: FileChannel, b: ByteBuffer, trak: Box): Timecode? {
        val mdia = child(b, trak, "mdia") ?: return null
        val hdlr = child(b, mdia, "hdlr") ?: return null
        if (fourcc(b, hdlr.start + 8) != "tmcd") return null
        val stbl = child(b, mdia, "minf")?.let { child(b, it, "stbl") } ?: return null
        val stsd = child(b, stbl, "stsd") ?: return null
        // stsd: versão/flags(4) + contagem(4) + entrada [tamanho(4) tipo(4) reservado(6) dataRef(2) ...]
        val e = stsd.start + 8
        if (e + 34 > stsd.end || fourcc(b, e + 4) != "tmcd") return null
        val flags = u32(b, e + 20)
        val timescale = u32(b, e + 24).toInt()
        val frameDuration = u32(b, e + 28).toInt()
        val nominalFps = b.get(e + 32).toInt() and 0xFF
        if (timescale <= 0 || frameDuration <= 0) return null
        val offset = child(b, stbl, "stco")?.let { if (u32(b, it.start + 4) > 0) u32(b, it.start + 8) else null }
            ?: child(b, stbl, "co64")?.let { if (u32(b, it.start + 4) > 0) b.getLong(it.start + 8) else null }
            ?: return null
        val sample = readAt(ch, offset, 4)
        if (sample.remaining() < 4) return null
        return Timecode(u32(sample, 0), timescale, frameDuration, nominalFps, (flags and 1L) != 0L)
    }

    private data class Box(val type: String, val start: Int, val end: Int)

    private fun children(b: ByteBuffer, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > end) break
                size = b.getLong(p + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < header || p + size > end) break
            out.add(Box(fourcc(b, p + 4), p + header, (p + size).toInt()))
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteBuffer, parent: Box, type: String): Box? =
        children(b, parent.start, parent.end).firstOrNull { it.type == type }

    /** Procura uma caixa de primeiro nível pulando as outras (o `mdat` pode ter vários GB). */
    private fun findTopLevel(ch: FileChannel, type: String): ByteBuffer? {
        val size = ch.size()
        var pos = 0L
        while (pos + 8 <= size) {
            val h = readAt(ch, pos, 16)
            if (h.remaining() < 8) return null
            var boxSize = u32(h, 0)
            var header = 8
            if (boxSize == 1L) {
                if (h.remaining() < 16) return null
                boxSize = h.getLong(8)
                header = 16
            } else if (boxSize == 0L) {
                boxSize = size - pos
            }
            if (boxSize < header) return null
            if (fourcc(h, 4) == type) {
                val payload = boxSize - header
                if (payload > MAX_MOOV_BYTES) throw IOException("Caixa $type grande demais")
                return readAt(ch, pos + header, payload.toInt())
            }
            pos += boxSize
        }
        return null
    }

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
        buf.flip()
        return buf
    }

    private fun u32(b: ByteBuffer, p: Int): Long = b.getInt(p).toLong() and 0xFFFF_FFFFL

    private fun fourcc(b: ByteBuffer, p: Int): String =
        String(ByteArray(4) { b.get(p + it) }, Charsets.ISO_8859_1)
}
