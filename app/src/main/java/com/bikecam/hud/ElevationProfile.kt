package com.bikecam.hud

/**
 * Altitude ao longo da distância da rota inteira, reamostrada em [POINTS] pontos igualmente
 * espaçados, para o mostrador de perfil. Imutável: uma instância serve a todos os quadros.
 */
class ElevationProfile private constructor(
    /** Distância acumulada (m) no início do perfil, na mesma escala de `TelemetrySnapshot.distanceM`. */
    val startDistanceM: Double,
    val totalDistanceM: Double,
    private val altitudes: FloatArray,
) {
    val minAltitudeM: Float = altitudes.min()
    val maxAltitudeM: Float = altitudes.max()
    val size: Int get() = altitudes.size

    fun altitude(i: Int): Float = altitudes[i]

    /** Posição (0..1) de uma distância acumulada ao longo do perfil. */
    fun fraction(distanceM: Double): Float = ((distanceM - startDistanceM) / totalDistanceM).toFloat().coerceIn(0f, 1f)

    companion object {
        const val POINTS = 240

        /**
         * [distances] crescentes (m) e [altitudes] correspondentes. Retorna null se a rota for curta
         * demais ou não tiver altitude.
         */
        fun from(distances: DoubleArray, altitudes: DoubleArray): ElevationProfile? {
            if (distances.size < 2 || distances.size != altitudes.size) return null
            val total = distances.last() - distances.first()
            if (total < 1.0) return null
            val out = FloatArray(POINTS)
            var j = 0
            for (i in 0 until POINTS) {
                val d = distances.first() + total * i / (POINTS - 1)
                while (j < distances.size - 2 && distances[j + 1] < d) j++
                val span = distances[j + 1] - distances[j]
                val f = if (span > 0) ((d - distances[j]) / span).coerceIn(0.0, 1.0) else 0.0
                out[i] = (altitudes[j] + (altitudes[j + 1] - altitudes[j]) * f).toFloat()
            }
            return ElevationProfile(distances.first(), total, out)
        }
    }
}
