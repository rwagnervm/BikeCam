package com.bikecam.telemetry

import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.tan

/**
 * Altitude, ganho de elevação acumulado e inclinação da via (grade %).
 *
 * - **Altitude relativa**: barômetro suavizado (preciso para variações, ~0,1 m), ou GPS suavizado
 *   quando o aparelho não tem barômetro.
 * - **Altitude absoluta**: barômetro + um offset que converge devagar para a altitude do GPS. O
 *   offset só afeta o valor exibido; ganho e inclinação usam a altitude relativa, então a
 *   calibração (e a deriva do clima com o ciclista parado) não vira subida falsa.
 * - **Ganho**: histerese de [BARO_HYSTERESIS_M] (barômetro) ou [GPS_HYSTERESIS_M] (GPS), só em
 *   movimento.
 * - **Inclinação**: Δaltitude/Δdistância numa janela de distância (baixa frequência, sem deriva)
 *   combinada, por filtro complementar, com a *variação* do pitch do acelerômetro (alta frequência,
 *   responde na hora a uma rampa). Usar só a variação cancela o ângulo fixo do suporte no guidão;
 *   a aceleração para frente (dv/dt) é descontada para frear/acelerar não parecer subida.
 *
 * Não é thread-safe: use a partir de um único coroutine.
 */
class ElevationEstimator {
    data class Output(val altitudeM: Double?, val gainM: Double?, val gradePercent: Float?)

    private var useBaro: Boolean? = null
    private var relAlt: Double? = null
    private var baroOffset: Double? = null
    private var gainRef: Double? = null
    private var gain = 0.0
    private var hasGain = false

    // Janela de (distância, altitude relativa) para a inclinação de baixa frequência.
    private val winDist = DoubleArray(WINDOW_CAPACITY)
    private val winAlt = DoubleArray(WINDOW_CAPACITY)
    private var winStart = 0
    private var winSize = 0

    private var grade: Double? = null
    private var tilt: Double? = null       // seno do pitch aparente, suavizado
    private var lastPitchGrade: Double? = null
    private var lastSpeed: Float? = null
    private var accel = 0.0                // dv/dt suavizado, m/s²

    /** Zera ganho e inclinação (início de gravação). A calibração da altitude é mantida. */
    fun reset() {
        gain = 0.0
        hasGain = false
        gainRef = null
        winSize = 0
        grade = null
        lastPitchGrade = null
    }

    /**
     * @param baroAltM altitude barométrica (atmosfera padrão), ou null sem barômetro
     * @param gpsAltM altitude do GPS (só é usada se [gpsAccuracyM] for boa)
     * @param distanceM distância acumulada da viagem
     * @param forwardTilt componente da força específica no eixo para frente da câmera, dividida
     *   pelo módulo (≈ seno do pitch com o aparelho parado), ou null sem acelerômetro
     */
    fun update(
        dtMs: Long,
        baroAltM: Double?,
        gpsAltM: Double?,
        gpsAccuracyM: Float?,
        distanceM: Double,
        speedMps: Float?,
        forwardTilt: Double?,
    ): Output {
        val dt = (dtMs / 1000.0).coerceIn(0.0, 2.0)
        val gpsGood = gpsAltM != null && (gpsAccuracyM ?: Float.MAX_VALUE) <= GPS_MAX_ACCURACY_M
        val baro = baroAltM != null
        if (useBaro != baro) {
            // Fonte mudou (ou primeira amostra): recomeça a suavização sem zerar o ganho.
            useBaro = baro
            relAlt = null
            gainRef = null
            winSize = 0
        }

        val raw = if (baro) baroAltM else gpsAltM?.takeIf { gpsGood }
        if (raw != null) {
            val tau = if (baro) BARO_TAU_S else GPS_TAU_S
            relAlt = relAlt?.let { it + alpha(dt, tau) * (raw - it) } ?: raw
        }
        if (baro && gpsGood) {
            val target = gpsAltM!! - relAlt!!
            baroOffset = baroOffset?.let { it + alpha(dt, OFFSET_TAU_S) * (target - it) } ?: target
        }

        val alt = relAlt
        val moving = (speedMps ?: 0f) >= MOVING_MPS
        if (alt != null) {
            updateGain(alt, moving, if (baro) BARO_HYSTERESIS_M else GPS_HYSTERESIS_M)
            updateGrade(alt, distanceM, moving, if (baro) BARO_WINDOW_M else GPS_WINDOW_M, dt, speedMps, forwardTilt)
        }

        val absolute = when {
            alt == null -> null
            baro -> alt + (baroOffset ?: 0.0)
            else -> alt
        }
        return Output(absolute, if (hasGain) gain else null, grade?.toFloat())
    }

    private fun updateGain(alt: Double, moving: Boolean, hysteresis: Double) {
        hasGain = true
        val ref = gainRef
        when {
            // Parado: acompanha a altitude sem somar nada (o clima muda a pressão).
            ref == null || !moving -> gainRef = alt
            alt - ref >= hysteresis -> { gain += alt - ref; gainRef = alt }
            ref - alt >= hysteresis -> gainRef = alt
        }
    }

    private fun updateGrade(
        alt: Double, distanceM: Double, moving: Boolean, windowM: Double, dt: Double, speedMps: Float?, forwardTilt: Double?,
    ) {
        // dv/dt para descontar a aceleração do pitch medido.
        val v = speedMps
        val lv = lastSpeed
        if (v != null && lv != null && dt > 0) accel += alpha(dt, ACCEL_TAU_S) * ((v - lv) / dt - accel)
        lastSpeed = v

        val pitchGrade = forwardTilt?.let { t ->
            tilt = tilt?.let { it + alpha(dt, TILT_TAU_S) * (t - it) } ?: t
            val sinPitch = (tilt!! - accel / GRAVITY).coerceIn(-0.5, 0.5)
            tan(asin(sinPitch)) * 100.0
        }

        // Janela por distância: guarda uma amostra a cada ~2 m percorridos.
        if (winSize == 0 || distanceM < lastWinDist()) {
            winStart = 0; winSize = 0
            push(distanceM, alt)
        } else if (distanceM - lastWinDist() >= WINDOW_STEP_M) {
            push(distanceM, alt)
        }
        while (winSize > 1 && distanceM - winDist[(winStart + 1) % WINDOW_CAPACITY] >= windowM) {
            winStart = (winStart + 1) % WINDOW_CAPACITY
            winSize--
        }
        val d0 = winDist[winStart]
        val a0 = winAlt[winStart]
        val span = distanceM - d0
        val lowFreq = if (span >= windowM * 0.6) (alt - a0) / span * 100.0 else null

        if (!moving) {
            // Parado não há Δdistância; mantém o último valor.
            lastPitchGrade = pitchGrade
            return
        }
        val prev = grade
        val lastPitch = lastPitchGrade
        grade = when {
            lowFreq == null -> prev
            prev == null -> lowFreq
            pitchGrade != null && lastPitch != null -> {
                val k = COMPLEMENTARY_TAU_S / (COMPLEMENTARY_TAU_S + dt)
                k * (prev + (pitchGrade - lastPitch)) + (1 - k) * lowFreq
            }
            else -> prev + alpha(dt, GRADE_TAU_S) * (lowFreq - prev)
        }?.coerceIn(-MAX_GRADE, MAX_GRADE)
        lastPitchGrade = pitchGrade
    }

    private fun lastWinDist() = winDist[(winStart + winSize - 1) % WINDOW_CAPACITY]

    private fun push(d: Double, a: Double) {
        if (winSize == WINDOW_CAPACITY) {
            winStart = (winStart + 1) % WINDOW_CAPACITY
            winSize--
        }
        val i = (winStart + winSize) % WINDOW_CAPACITY
        winDist[i] = d
        winAlt[i] = a
        winSize++
    }

    private fun alpha(dt: Double, tau: Double) = 1.0 - exp(-dt / tau)

    companion object {
        private const val GRAVITY = 9.80665
        private const val GPS_MAX_ACCURACY_M = 25f
        private const val MOVING_MPS = 1.0f
        private const val BARO_TAU_S = 2.0
        private const val GPS_TAU_S = 5.0
        private const val OFFSET_TAU_S = 120.0
        const val BARO_HYSTERESIS_M = 2.0
        const val GPS_HYSTERESIS_M = 5.0
        private const val BARO_WINDOW_M = 40.0
        private const val GPS_WINDOW_M = 120.0
        private const val WINDOW_STEP_M = 2.0
        private const val WINDOW_CAPACITY = 128
        private const val TILT_TAU_S = 0.6
        private const val ACCEL_TAU_S = 1.0
        private const val COMPLEMENTARY_TAU_S = 2.0
        private const val GRADE_TAU_S = 1.5
        private const val MAX_GRADE = 35.0
    }
}
