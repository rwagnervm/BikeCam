package com.bikecam.render.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Redução de ruído de vento sem modelo (DSP clássico), para áudio PCM entrelaçado.
 *
 * 1. Passa-altas Butterworth de 4ª ordem em [HIGH_PASS_HZ]: o “ronco” do vento num microfone
 *    exposto fica quase todo abaixo disso.
 * 2. O resultado é dividido por um crossover Linkwitz-Riley de 4ª ordem em [CROSSOVER_HZ] (as duas
 *    bandas somadas mantêm o volume de todas as frequências). Quando a energia dos graves passa
 *    [WIND_RATIO] vezes a dos agudos (típico de rajada, raro em voz ou som de rua), os graves são
 *    atenuados até [MIN_GAIN], com ataque rápido e soltura lenta. Sem vento, nada muda além do (1).
 *
 * O ganho é o mesmo em todos os canais, para não deslocar a imagem estéreo. Não é thread-safe.
 */
class WindFilter(sampleRate: Int, private val channels: Int) {
    private val hp1 = Array(channels) { Biquad.highPass(sampleRate, HIGH_PASS_HZ, 0.5412) }
    private val hp2 = Array(channels) { Biquad.highPass(sampleRate, HIGH_PASS_HZ, 1.3066) }
    private val lp1 = Array(channels) { Biquad.lowPass(sampleRate, CROSSOVER_HZ, BUTTERWORTH_Q) }
    private val lp2 = Array(channels) { Biquad.lowPass(sampleRate, CROSSOVER_HZ, BUTTERWORTH_Q) }
    private val xp1 = Array(channels) { Biquad.highPass(sampleRate, CROSSOVER_HZ, BUTTERWORTH_Q) }
    private val xp2 = Array(channels) { Biquad.highPass(sampleRate, CROSSOVER_HZ, BUTTERWORTH_Q) }
    private val low = DoubleArray(channels)
    private val high = DoubleArray(channels)

    private val envCoef = 1 - exp(-1.0 / (ENV_TAU_S * sampleRate))
    private val attackCoef = 1 - exp(-1.0 / (ATTACK_S * sampleRate))
    private val releaseCoef = 1 - exp(-1.0 / (RELEASE_S * sampleRate))
    private var lowEnv = 0.0
    private var highEnv = 0.0
    private var gain = 1.0

    /** Ganho atual dos graves (1 = sem vento detectado); exposto para testes e logs. */
    val currentGain: Double get() = gain

    /** Processa [frames] quadros entrelaçados de [input] para [output] (podem ser o mesmo array). */
    fun process(input: ShortArray, output: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            var lowE = 0.0
            var highE = 0.0
            for (c in 0 until channels) {
                val x = input[f * channels + c] / 32768.0
                val y = hp2[c].step(hp1[c].step(x))
                val l = lp2[c].step(lp1[c].step(y))
                val h = xp2[c].step(xp1[c].step(y))
                low[c] = l
                high[c] = h
                lowE += l * l
                highE += h * h
            }
            lowEnv += envCoef * (lowE - lowEnv)
            highEnv += envCoef * (highE - highEnv)
            val ratio = lowEnv / (highEnv + 1e-12)
            val target = if (ratio > WIND_RATIO) sqrt(WIND_RATIO / ratio).coerceAtLeast(MIN_GAIN) else 1.0
            gain += (if (target < gain) attackCoef else releaseCoef) * (target - gain)
            for (c in 0 until channels) {
                val v = (high[c] + gain * low[c]) * 32768.0
                output[f * channels + c] = v.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
        }
    }

    companion object {
        const val HIGH_PASS_HZ = 120.0
        const val CROSSOVER_HZ = 400.0
        /** Graves com 4× a energia do resto (≈ 6 dB) contam como vento. */
        const val WIND_RATIO = 4.0
        /** Atenuação máxima dos graves durante rajadas (≈ −18 dB). */
        const val MIN_GAIN = 0.125
        private const val BUTTERWORTH_Q = 0.7071
        private const val ENV_TAU_S = 0.05
        private const val ATTACK_S = 0.01
        private const val RELEASE_S = 0.25
    }
}

/** Filtro biquadrático (fórmulas do “Audio EQ Cookbook”), forma direta transposta II. */
internal class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun step(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private fun make(sampleRate: Int, hz: Double, q: Double, highPass: Boolean): Biquad {
            val w0 = 2 * PI * hz / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val (b0, b1, b2) = if (highPass) {
                Triple((1 + cw) / 2, -(1 + cw), (1 + cw) / 2)
            } else {
                Triple((1 - cw) / 2, 1 - cw, (1 - cw) / 2)
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, -2 * cw / a0, (1 - alpha) / a0)
        }

        fun highPass(sampleRate: Int, hz: Double, q: Double) = make(sampleRate, hz, q, highPass = true)
        fun lowPass(sampleRate: Int, hz: Double, q: Double) = make(sampleRate, hz, q, highPass = false)
    }
}
