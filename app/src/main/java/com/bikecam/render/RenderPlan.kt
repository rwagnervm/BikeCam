package com.bikecam.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class OutputResolution(val label: String, val shortSide: Int?) {
    P1080("1080p", 1080),
    P2160("4K", 2160),
    ORIGINAL("Original", null),
}

enum class OutputCodec(val label: String, val mime: String) {
    H264("H.264", "video/avc"),
    HEVC("HEVC", "video/hevc"),
}

data class RenderSettings(
    /** Recorte, em ms desde o início da gravação (todos os capítulos). */
    val trimStartMs: Long,
    val trimEndMs: Long,
    val resolution: OutputResolution = OutputResolution.P1080,
    val codec: OutputCodec = OutputCodec.H264,
    val includeAudio: Boolean = true,
    /** Tira a fala (MossFormer2) e mantém o som ambiente. */
    val removeVoices: Boolean = false,
    /** Sem vozes: corta o ronco grave. Só esta opção: fala + resto −12 dB (realça as vozes). Ver CleanupMode. */
    val removeWind: Boolean = false,
) {
    val cleansAudio: Boolean get() = includeAudio && (removeVoices || removeWind)

    val durationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
}

/** Trecho de um capítulo que entra na saída, em ms relativos ao início daquele capítulo. */
data class ChapterClip(val chapter: Int, val startMs: Long, val endMs: Long)

/** Contas da renderização, sem Android. */
object RenderPlan {
    /** Bits por pixel por quadro: ~15 Mbps em 1080p30 H.264. HEVC usa ~60 % disso. */
    private const val BITS_PER_PIXEL_H264 = 0.25
    private const val HEVC_FACTOR = 0.6
    private const val MIN_BITRATE = 2_000_000
    private const val MAX_BITRATE = 100_000_000
    const val AUDIO_BITRATE = 192_000

    /** Codificadores de hardware passam do bitrate pedido (medido no S23 Ultra: +25 %). */
    private const val ENCODER_OVERSHOOT = 1.25

    /** Os pedaços de cada capítulo que cobrem o recorte [trimStartMs, trimEndMs). */
    fun clips(durationsMs: List<Long>, trimStartMs: Long, trimEndMs: Long): List<ChapterClip> {
        val out = ArrayList<ChapterClip>()
        var chapterStart = 0L
        for ((i, d) in durationsMs.withIndex()) {
            val chapterEnd = chapterStart + d
            val s = max(trimStartMs, chapterStart)
            val e = min(trimEndMs, chapterEnd)
            if (e - s > 0) out += ChapterClip(i, s - chapterStart, e - chapterStart)
            chapterStart = chapterEnd
        }
        return out
    }

    /** Tamanho de saída: lado menor limitado pela opção (nunca amplia), dimensões pares. */
    fun outputSize(width: Int, height: Int, resolution: OutputResolution): Pair<Int, Int> {
        val short = min(width, height)
        val target = resolution.shortSide?.coerceAtMost(short) ?: short
        val k = target.toDouble() / short
        fun even(v: Double) = (v / 2).roundToInt() * 2
        return even(width * k) to even(height * k)
    }

    fun videoBitrate(width: Int, height: Int, fps: Float, codec: OutputCodec): Int {
        val bpp = BITS_PER_PIXEL_H264 * if (codec == OutputCodec.HEVC) HEVC_FACTOR else 1.0
        return (width.toLong() * height * fps.coerceIn(1f, 240f) * bpp).toLong()
            .coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }

    fun estimateBytes(durationMs: Long, videoBitrate: Int, audio: Boolean): Long {
        val bps = videoBitrate * ENCODER_OVERSHOOT + if (audio) AUDIO_BITRATE else 0
        return (bps * durationMs / 8_000.0).roundToLong()
    }
}
