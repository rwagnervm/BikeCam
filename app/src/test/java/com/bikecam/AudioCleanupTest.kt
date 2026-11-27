package com.bikecam

import com.bikecam.render.audio.WindowedSeparator
import com.bikecam.render.audio.WindFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioCleanupTest {
    private val rate = 48_000

    /** Sinal estéreo entrelaçado com o mesmo conteúdo nos dois canais. */
    private fun stereo(seconds: Double, vararg tones: Pair<Double, Double>): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n * 2) { i ->
            val t = (i / 2).toDouble() / rate
            (tones.sumOf { (hz, amp) -> amp * sin(2 * PI * hz * t) } * 32767).toInt().toShort()
        }
    }

    /** Amplitude de uma frequência (Goertzel) no canal 0, ignorando o início (transitório dos filtros). */
    private fun amplitude(pcm: ShortArray, hz: Double, skipSeconds: Double = 0.5): Double {
        val start = (skipSeconds * rate).toInt()
        val n = pcm.size / 2 - start
        val w = 2 * PI * hz / rate
        val coef = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = pcm[(start + i) * 2] / 32768.0 + coef * s1 - s2
            s2 = s1
            s1 = s0
        }
        return 2 * sqrt(s1 * s1 + s2 * s2 - coef * s1 * s2) / n
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    private fun wind(input: ShortArray): Pair<ShortArray, WindFilter> {
        val f = WindFilter(rate, 2)
        val out = ShortArray(input.size)
        // Em blocos de tamanhos variados, como chega do decodificador.
        var pos = 0
        var block = 777
        while (pos < input.size / 2) {
            val frames = minOf(block, input.size / 2 - pos)
            val inChunk = input.copyOfRange(pos * 2, (pos + frames) * 2)
            val outChunk = ShortArray(inChunk.size)
            f.process(inChunk, outChunk, frames)
            outChunk.copyInto(out, pos * 2)
            pos += frames
            block = if (block == 777) 1024 else 777
        }
        return out to f
    }

    @Test
    fun windFilterCutsRumbleAndKeepsMids() {
        val input = stereo(2.0, 40.0 to 0.3, 1000.0 to 0.3)
        val (out, _) = wind(input)
        assertTrue("40 Hz deveria cair > 30 dB", db(amplitude(out, 40.0), amplitude(input, 40.0)) < -30)
        assertEquals(0.0, db(amplitude(out, 1000.0), amplitude(input, 1000.0)), 1.0)
    }

    @Test
    fun windFilterAttenuatesLowBandOnlyWhenItDominates() {
        // Sem vento: graves moderados (200 Hz) e médios (1 kHz) parecidos → quase intactos.
        val calm = stereo(2.0, 200.0 to 0.1, 1000.0 to 0.1)
        val (calmOut, calmFilter) = wind(calm)
        assertEquals(1.0, calmFilter.currentGain, 0.01)
        assertEquals(0.0, db(amplitude(calmOut, 200.0), amplitude(calm, 200.0)), 1.5)

        // Rajada: graves 18 dB acima dos médios → graves atenuados, médios preservados.
        val gust = stereo(2.0, 200.0 to 0.8, 1000.0 to 0.1)
        val (gustOut, gustFilter) = wind(gust)
        assertTrue(gustFilter.currentGain < 0.5)
        assertTrue(db(amplitude(gustOut, 200.0), amplitude(gust, 200.0)) < -6)
        assertEquals(0.0, db(amplitude(gustOut, 1000.0), amplitude(gust, 1000.0)), 1.5)
    }

    @Test
    fun windFilterKeepsChannelsAndLength() {
        val input = stereo(0.3, 1000.0 to 0.5)
        val (out, _) = wind(input)
        assertEquals(input.size, out.size)
        for (i in 0 until out.size step 2) assertEquals(out[i], out[i + 1])
    }

    /** Separador que devolve a própria entrada como "fala": a saída deve reproduzir a entrada. */
    private fun runSeparator(sep: WindowedSeparator, input: ShortArray, channels: Int, chunk: Int): Pair<FloatArray, FloatArray> {
        val orig = ArrayList<Float>()
        val speech = ArrayList<Float>()
        fun take(list: List<WindowedSeparator.Chunk>) = list.forEach { c -> orig += c.original.toList(); speech += c.speech.toList() }
        var pos = 0
        val frames = input.size / channels
        while (pos < frames) {
            val n = minOf(chunk, frames - pos)
            take(sep.push(input.copyOfRange(pos * channels, (pos + n) * channels), n))
            pos += n
        }
        take(sep.finish())
        return orig.toFloatArray() to speech.toFloatArray()
    }

    @Test
    fun windowedSeparatorKeepsEveryFrameAligned() {
        for (frames in listOf(5, 16, 17, 40, 100, 1234)) {
            val input = ShortArray(frames * 2) { (it * 7 % 1000 - 500).toShort() }
            val sep = WindowedSeparator(2, window = 16, stride = 12, giveUp = 2) { it.copyOf() }
            val (orig, speech) = runSeparator(sep, input, 2, chunk = 7)
            assertEquals("quadros ($frames)", input.size, orig.size)
            for (i in input.indices) {
                assertEquals(input[i].toFloat(), orig[i], 0f)
                assertEquals("fala alinhada ($frames, $i)", input[i].toFloat(), speech[i], 0f)
            }
        }
    }

    @Test
    fun windowedSeparatorUsesTheMiddleOfEachWindow() {
        // A "fala" de cada janela é o número da janela: mostra de qual janela veio cada quadro.
        var calls = 0
        val sep = WindowedSeparator(1, window = 16, stride = 12, giveUp = 2) { w -> val k = calls++; FloatArray(w.size) { k.toFloat() } }
        val frames = 50 // janelas em 0, 12, 24 (completas) e 36 (completada com silêncio no fim)
        val (_, speech) = runSeparator(sep, ShortArray(frames), 1, chunk = 5)
        val expected = IntArray(frames) { p ->
            when {
                p < 14 -> 0 // começo: a 1ª janela vale desde o quadro 0
                p < 26 -> 1
                p < 38 -> 2
                else -> 3   // fim: a última janela vale até o último quadro
            }
        }
        assertEquals(4, calls)
        for (p in 0 until frames) assertEquals("quadro $p", expected[p].toFloat(), speech[p], 0f)
    }
}

/** Passa áudio pela cadeia real do Media3 (AudioProcessingPipeline) e confere que nada se perde. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPipelineTest {
    @Test
    fun pipelineKeepsEverySampleUntilTheEnd() {
        // "Fala" zero e só remover vozes: a saída tem de ser idêntica à entrada, amostra por amostra.
        val input = testSignal()
        assertArrayEquals(input, run(listOf(cleanup()), input))
    }

    @Test
    fun skipStartDropsOnlyTheFirstFrames() {
        // Como na primeira passada da renderização: limpeza + descarte do atraso do codificador AAC.
        val input = testSignal()
        val out = run(listOf(cleanup(), com.bikecam.render.audio.SkipStartProcessor(448)), input)
        assertArrayEquals(input.copyOfRange(448 * 2, input.size), out)
    }

    private fun testSignal(): ShortArray {
        val totalFrames = 48_000 * 2 + 123 // 2 s e um pedaço que não fecha janela
        return ShortArray(totalFrames * 2) { ((it % 2000) - 1000).toShort() }
    }

    private fun cleanup() =
        com.bikecam.render.audio.ModelCleanupProcessor(com.bikecam.render.audio.CleanupMode.REMOVE_VOICES, 4800) { x, _ -> FloatArray(x.size) }

    private fun run(processors: List<androidx.media3.common.audio.AudioProcessor>, input: ShortArray): ShortArray {
        val pipeline = androidx.media3.common.audio.AudioProcessingPipeline(com.google.common.collect.ImmutableList.copyOf(processors))
        pipeline.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(48_000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT))
        pipeline.flush()

        val totalFrames = input.size / 2
        val chunkFrames = 4096
        val produced = ArrayList<Short>()
        fun drain() {
            while (true) {
                val out = pipeline.output
                if (!out.hasRemaining()) break
                val sb = out.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                while (sb.hasRemaining()) produced += sb.get()
                out.position(out.limit())
            }
        }
        var sent = 0
        while (sent < totalFrames) {
            val frames = minOf(chunkFrames, totalFrames - sent)
            val buf = java.nio.ByteBuffer.allocateDirect(frames * 4).order(java.nio.ByteOrder.nativeOrder())
            repeat(frames * 2) { buf.putShort(input[sent * 2 + it]) }
            buf.flip()
            sent += frames
            if (sent > totalFrames - 2 * chunkFrames) {
                // Como no Transformer: nos dois últimos pedaços o codificador não lê a saída, então o
                // fim do fluxo chega com dados parados no meio da cadeia.
                while (buf.hasRemaining()) {
                    pipeline.queueInput(buf)
                    if (buf.hasRemaining()) drain()
                }
                continue
            }
            while (buf.hasRemaining()) {
                pipeline.queueInput(buf)
                drain()
            }
        }
        pipeline.queueEndOfStream()
        var guard = 0
        while (!pipeline.isEnded && guard++ < 1000) {
            pipeline.queueInput(java.nio.ByteBuffer.allocateDirect(0))
            drain()
        }
        assertTrue(pipeline.isEnded)
        return produced.toShortArray()
    }
}
