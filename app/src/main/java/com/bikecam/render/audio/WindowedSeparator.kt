package com.bikecam.render.audio

/**
 * Separa a fala de um fluxo de áudio em janelas, como o decode longo do ClearVoice: janelas de
 * [window] quadros a cada [stride], e de cada janela só se aproveita o miolo (descarta [giveUp]
 * quadros em cada borda, onde o modelo tem pouco contexto), exceto no começo e no fim do áudio.
 *
 * [separate] recebe uma janela em ordem canal-major (`[c * window + i]`, escala int16) e devolve a
 * fala estimada no mesmo formato. A saída de [push]/[finish] sai na ordem do áudio, com o original e
 * a fala lado a lado (entrelaçados), e no total tem exatamente os mesmos quadros da entrada.
 * Não é thread-safe.
 */
class WindowedSeparator(
    private val channels: Int,
    private val window: Int,
    private val stride: Int,
    private val giveUp: Int,
    private val separate: (FloatArray) -> FloatArray,
) {
    init {
        require(stride + 2 * giveUp == window) { "o miolo de cada janela deve cobrir exatamente o passo" }
    }

    /** Pedaço pronto: [original] e [speech] entrelaçados, [frames] quadros. */
    class Chunk(val original: FloatArray, val speech: FloatArray, val frames: Int)

    // Entrada guardada (por canal) a partir da posição absoluta [bufStart].
    private var buf = Array(channels) { FloatArray(window * 2) }
    private var bufStart = 0L
    private var bufLen = 0

    private var nextWindow = 0L
    private var emitted = 0L

    // Fala da última janela processada, por canal, a partir de [cacheStart].
    private val cache = Array(channels) { FloatArray(window) }
    private var cacheStart = -1L

    private val input = FloatArray(channels * window)

    /** Recebe [frames] quadros entrelaçados (escala int16) e devolve o que já pode sair. */
    fun push(interleaved: ShortArray, frames: Int): List<Chunk> {
        ensureCapacity(bufLen + frames)
        for (f in 0 until frames) {
            for (c in 0 until channels) buf[c][bufLen + f] = interleaved[f * channels + c].toFloat()
        }
        bufLen += frames
        val out = ArrayList<Chunk>()
        while (nextWindow + window <= bufStart + bufLen) {
            runWindow(nextWindow)
            val end = nextWindow + window - giveUp
            out += emit(end)
            nextWindow += stride
            compact()
        }
        return out
    }

    /** Fim do áudio: processa o que falta (janela final completada com silêncio). */
    fun finish(): List<Chunk> {
        val total = bufStart + bufLen
        val out = ArrayList<Chunk>()
        while (emitted < total) {
            // A última janela serve até o fim só se o alcançar; senão (mesmo que cubra um pedaço) roda
            // a próxima, completada com silêncio, para não usar a borda da anterior.
            if (cacheStart >= 0 && cacheStart + window >= total) {
                out += emit(total)
            } else {
                runWindow(nextWindow)
                nextWindow += stride
            }
        }
        return out
    }

    private fun runWindow(start: Long) {
        val offset = (start - bufStart).toInt()
        for (c in 0 until channels) {
            val available = (bufLen - offset).coerceIn(0, window)
            System.arraycopy(buf[c], offset, input, c * window, available)
            input.fill(0f, c * window + available, (c + 1) * window)
        }
        val speech = separate(input)
        for (c in 0 until channels) System.arraycopy(speech, c * window, cache[c], 0, window)
        cacheStart = start
    }

    /** Sai de [emitted] até [end] (absoluto), com a fala da janela em cache. */
    private fun emit(end: Long): Chunk {
        val frames = (end - emitted).toInt()
        val original = FloatArray(frames * channels)
        val speech = FloatArray(frames * channels)
        val bufOffset = (emitted - bufStart).toInt()
        val cacheOffset = (emitted - cacheStart).toInt()
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                original[f * channels + c] = buf[c][bufOffset + f]
                speech[f * channels + c] = cache[c][cacheOffset + f]
            }
        }
        emitted = end
        return Chunk(original, speech, frames)
    }

    /** Descarta a entrada anterior à próxima janela (já saiu e não será mais usada). */
    private fun compact() {
        val drop = (minOf(nextWindow, emitted) - bufStart).toInt()
        if (drop <= 0) return
        for (c in 0 until channels) System.arraycopy(buf[c], drop, buf[c], 0, bufLen - drop)
        bufLen -= drop
        bufStart += drop
    }

    private fun ensureCapacity(n: Int) {
        if (buf[0].size >= n) return
        val size = maxOf(n, buf[0].size * 2)
        buf = Array(channels) { buf[it].copyOf(size) }
    }
}
