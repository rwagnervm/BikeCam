package com.bikecam.render.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Base dos processadores: PCM 16 bits entrelaçado → ShortArray → processamento → saída.
 *
 * O `replaceOutputBuffer` reaproveita o mesmo buffer, então só se escreve nele quando a cadeia pede
 * mais dados (a saída anterior já foi consumida). Por isso o que sobra no fim do fluxo ([finish])
 * fica guardado e sai na próxima chamada de [queueInput], e não dentro de `onQueueEndOfStream`
 * (escrever ali apagava o último pedaço ainda não lido: ~0,1 s a menos no fim do áudio).
 */
@UnstableApi
internal abstract class ShortPcmProcessor : BaseAudioProcessor() {
    private var scratch = ShortArray(0)
    private var tail: ShortArray? = null

    protected abstract fun processShorts(input: ShortArray, frames: Int): ShortArray
    protected open fun finish(): ShortArray = ShortArray(0)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pendingTail = tail
        if (pendingTail != null) {
            tail = null
            emit(pendingTail)
            return
        }
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val samples = bytes / 2
        if (scratch.size < samples) scratch = ShortArray(samples)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, samples)
        inputBuffer.position(inputBuffer.limit())
        emit(processShorts(scratch, samples / inputAudioFormat.channelCount))
    }

    override fun onQueueEndOfStream() {
        tail = finish().takeIf { it.isNotEmpty() }
    }

    override fun isEnded(): Boolean = super.isEnded() && tail == null

    override fun onFlush() {
        tail = null
    }

    override fun onReset() {
        tail = null
    }

    protected fun emit(out: ShortArray) {
        if (out.isEmpty()) return
        val buffer = replaceOutputBuffer(out.size * 2)
        buffer.asShortBuffer().put(out)
        buffer.position(buffer.position() + out.size * 2)
        buffer.flip()
    }
}

/** Descarta os primeiros [frames] quadros do fluxo (qualquer formato PCM); o resto passa igual. */
@UnstableApi
class SkipStartProcessor(private val frames: Int) : BaseAudioProcessor() {
    private var bytesToSkip = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat = inputAudioFormat

    override fun onFlush() {
        bytesToSkip = frames * inputAudioFormat.bytesPerFrame
    }

    override fun onReset() {
        bytesToSkip = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val skip = minOf(bytesToSkip, inputBuffer.remaining())
        inputBuffer.position(inputBuffer.position() + skip)
        bytesToSkip -= skip
        val n = inputBuffer.remaining()
        if (n == 0) return
        replaceOutputBuffer(n).put(inputBuffer).flip()
    }
}

/** O que fazer com a fala separada pelo modelo. */
enum class CleanupMode {
    /** Original − fala: fica o som ambiente (com o vento). */
    REMOVE_VOICES,

    /** Fala + resto rebaixado em [AMBIENT_GAIN] e sem o ronco grave: realça as vozes. */
    REMOVE_WIND,

    /** Só o resto, sem o ronco grave. */
    BOTH;

    companion object {
        fun of(removeVoices: Boolean, removeWind: Boolean): CleanupMode? = when {
            removeVoices && removeWind -> BOTH
            removeVoices -> REMOVE_VOICES
            removeWind -> REMOVE_WIND
            else -> null
        }

        /** −12 dB: o nível que soou bem no teste com vento forte. */
        const val AMBIENT_GAIN = 0.251f
    }
}

/**
 * Limpeza de áudio com a fala estimada pelo modelo (ver [WindowedSeparator]) e o [WindFilter] no
 * que não é fala. Atrasa a saída em ~3,5 s (uma janela) e a completa no fim do fluxo, então o total
 * de amostras e o sincronismo com o vídeo não mudam. Por causa do atraso, roda numa passada só de
 * áudio (ver `RenderService.cleanAudio`).
 */
@UnstableApi
internal class ModelCleanupProcessor(
    private val mode: CleanupMode,
    private val window: Int,
    private val separate: (FloatArray, Int) -> FloatArray,
) : ShortPcmProcessor() {
    private var separator: WindowedSeparator? = null
    private var wind: WindFilter? = null
    private var residual = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.sampleRate != SAMPLE_RATE) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        super.onFlush()
        separator = null
        wind = null
    }

    private fun separator(): WindowedSeparator = separator ?: run {
        val ch = inputAudioFormat.channelCount
        WindowedSeparator(ch, window, window * 3 / 4, window / 8) { separate(it, ch) }.also {
            separator = it
            wind = WindFilter(SAMPLE_RATE, ch)
        }
    }

    override fun processShorts(input: ShortArray, frames: Int): ShortArray = mix(separator().push(input, frames))

    override fun finish(): ShortArray = separator?.let { mix(it.finish()) } ?: ShortArray(0)

    private fun mix(chunks: List<WindowedSeparator.Chunk>): ShortArray {
        if (chunks.isEmpty()) return ShortArray(0)
        val total = chunks.sumOf { it.original.size }
        val out = ShortArray(total)
        var pos = 0
        val ch = inputAudioFormat.channelCount
        for (chunk in chunks) {
            val n = chunk.original.size
            if (residual.size < n) residual = ShortArray(n)
            for (i in 0 until n) residual[i] = clip(chunk.original[i] - chunk.speech[i])
            if (mode != CleanupMode.REMOVE_VOICES) wind!!.process(residual, residual, n / ch)
            for (i in 0 until n) {
                out[pos + i] = when (mode) {
                    CleanupMode.REMOVE_VOICES, CleanupMode.BOTH -> residual[i]
                    CleanupMode.REMOVE_WIND -> clip(chunk.speech[i] + CleanupMode.AMBIENT_GAIN * residual[i])
                }
            }
            pos += n
        }
        return out
    }

    private fun clip(v: Float): Short = v.coerceIn(-32768f, 32767f).toInt().toShort()
}

/**
 * MossFormer2_SE_48K (ClearerVoice-Studio, Apache-2.0) exportado para ONNX com o pré e o
 * pós-processamento dentro do grafo: entra uma janela de 4 s [canais, 192000] em escala int16 e sai a
 * fala estimada no mesmo formato (uma máscara por canal, como no ClearVoice).
 */
class MossFormerModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {
    private var windows = 0
    private var busyMs = 0L

    fun separate(input: FloatArray, channels: Int): FloatArray {
        val t0 = SystemClock.elapsedRealtime()
        val result = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(channels.toLong(), WINDOW.toLong())).use { tensor ->
            session.run(mapOf(INPUT to tensor)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        windows++
        busyMs += SystemClock.elapsedRealtime() - t0
        if (windows == 1 || windows % 20 == 0) {
            Log.i(TAG, "janela $windows: média ${busyMs / windows} ms por ${WINDOW / SAMPLE_RATE} s de áudio")
        }
        return result
    }

    override fun close() = session.close()

    companion object {
        private const val TAG = "BikeCam.Audio"
        const val FILE_NAME = "mossformer2_se_48k.onnx"
        const val WINDOW = 4 * SAMPLE_RATE
        private const val INPUT = "audio"

        /** Onde o app procura o modelo (pasta privada do app no armazenamento externo). */
        fun file(context: Context): File = File(context.getExternalFilesDir("models"), FILE_NAME)

        fun isAvailable(context: Context): Boolean = file(context).isFile

        /** Carrega a sessão (demora alguns segundos: otimiza o grafo e expande os pesos fp16). */
        suspend fun load(context: Context): MossFormerModel = runInterruptible(Dispatchers.Default) {
            val f = file(context)
            check(f.isFile) { "Modelo de áudio não encontrado (${f.name})" }
            val t0 = SystemClock.elapsedRealtime()
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(f.absolutePath, opts)
            Log.i(TAG, "MossFormer2 carregado em ${SystemClock.elapsedRealtime() - t0} ms ($THREADS threads)")
            MossFormerModel(env, session)
        }

        /** Núcleos rápidos do celular (os de eficiência atrasam as operações em paralelo). */
        private const val THREADS = 4
    }
}

/**
 * Monta a cadeia de limpeza de áudio da renderização. [close] libera o modelo; chame no fim.
 */
@UnstableApi
class AudioCleanup private constructor(
    val processors: List<AudioProcessor>,
    private val model: MossFormerModel?,
) : AutoCloseable {
    override fun close() {
        model?.close()
    }

    companion object {
        /** Carrega o modelo e devolve os processadores na ordem certa (vazio se não há o que limpar). */
        suspend fun create(context: Context, removeVoices: Boolean, removeWind: Boolean): AudioCleanup {
            val mode = CleanupMode.of(removeVoices, removeWind) ?: return AudioCleanup(emptyList(), null)
            val model = MossFormerModel.load(context)
            val list = listOf(
                SonicAudioProcessor().apply { setOutputSampleRateHz(SAMPLE_RATE) },
                ModelCleanupProcessor(mode, MossFormerModel.WINDOW, model::separate),
            )
            return AudioCleanup(list, model)
        }
    }
}

internal const val SAMPLE_RATE = 48_000
