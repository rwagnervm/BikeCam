package com.bikecam.render

import com.bikecam.hud.ElevationProfile
import com.bikecam.hud.HudWidget
import com.bikecam.hud.TrackBuilder
import com.bikecam.hud.TrackPath
import com.bikecam.importer.ActivitySample
import com.bikecam.telemetry.SpeedSource
import com.bikecam.telemetry.TelemetrySnapshot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Atividade de um ciclocomputador processada para ser desenhada quadro a quadro.
 *
 * Os aparelhos gravam a cada 1–8 s (gravação “inteligente”) e pausam sozinhos nas paradas, então:
 * - tudo é **interpolado linearmente** entre amostras, para os números não pularem a cada ponto;
 * - um intervalo maior que [MAX_GAP_MS] entre amostras é tratado como **parado** (velocidade e
 *   cadência 0, posição, distância e altitude congeladas no ponto anterior).
 *
 * Distância, tempo em movimento, média e ganho de elevação são calculados uma vez, na construção.
 * A inclinação usa uma janela de distância **centrada** (dá para olhar para frente porque a trilha
 * inteira é conhecida). Imutável e seguro entre threads.
 */
class RideTimeline private constructor(
    private val t: LongArray,
    private val gapAfter: BooleanArray,
    private val dist: DoubleArray,
    private val movingMs: LongArray,
    private val segMoving: BooleanArray,
    private val gain: DoubleArray?,
    private val speed: Series,
    private val lat: Series,
    private val lon: Series,
    private val alt: Series,
    private val hr: Series,
    private val cad: Series,
    private val power: Series,
    private val temp: Series,
    private val altByDist: Series?,
    private val segHeading: FloatArray,
    private val routeCount: IntArray,
    val route: TrackPath,
    val elevationProfile: ElevationProfile?,
) {
    val startEpochMs: Long get() = t.first()
    val endEpochMs: Long get() = t.last()
    val totalDistanceM: Double get() = dist.last()
    val totalGainM: Double? get() = gain?.last()
    val hasPosition: Boolean get() = !lat.isEmpty
    val hasAltitude: Boolean get() = !alt.isEmpty
    val hasHeartRate: Boolean get() = !hr.isEmpty
    val hasCadence: Boolean get() = !cad.isEmpty
    val hasPower: Boolean get() = !power.isEmpty
    val hasTemperature: Boolean get() = !temp.isEmpty

    operator fun contains(epochMs: Long): Boolean = epochMs in startEpochMs..endEpochMs

    /** Mostradores que têm dado nesta trilha (os outros ficam escondidos na renderização). */
    fun availableWidgets(): Set<HudWidget> = HudWidget.entries.filterTo(HashSet()) { w ->
        when (w) {
            HudWidget.HEART_RATE -> hasHeartRate
            HudWidget.CADENCE -> hasCadence
            HudWidget.POWER -> hasPower
            HudWidget.TEMPERATURE -> hasTemperature
            HudWidget.MAP -> hasPosition
            HudWidget.ALTITUDE, HudWidget.GRADE, HudWidget.ELEVATION_GAIN -> hasAltitude
            HudWidget.ELEVATION_PROFILE -> elevationProfile != null
            HudWidget.SPEED, HudWidget.AVG_SPEED, HudWidget.DISTANCE, HudWidget.CLOCK -> true
        }
    }

    /** Estado interpolado no instante dado. Fora do intervalo da trilha, só a hora é preenchida. */
    fun snapshotAt(epochMs: Long): TelemetrySnapshot {
        if (epochMs !in this) {
            return TelemetrySnapshot(epochMs = epochMs, speedSource = SpeedSource.FILE, route = route, elevationProfile = elevationProfile)
        }
        val i = segment(epochMs)
        val stopped = i < t.size - 1 && gapAfter[i]
        // Parado: congela tudo no ponto anterior ao buraco.
        val q = if (stopped) t[i] else epochMs
        val frac = if (i < t.size - 1 && !stopped) (epochMs - t[i]).toDouble() / (t[i + 1] - t[i]) else 0.0

        val distance = if (i < t.size - 1) dist[i] + (dist[i + 1] - dist[i]) * frac else dist[i]
        val moving = movingMs[i] + if (i < t.size - 1 && !stopped && segMoving[i]) epochMs - t[i] else 0L
        val la = lat.at(q)
        val lo = lon.at(q)
        return TelemetrySnapshot(
            epochMs = epochMs,
            speedMps = if (stopped) 0f else speed.at(q)?.toFloat()?.coerceAtLeast(0f),
            speedSource = SpeedSource.FILE,
            avgSpeedMps = if (moving < 1_000L) null else (distance / (moving / 1000.0)).toFloat(),
            distanceM = distance,
            movingTimeMs = moving,
            heartRateBpm = hr.at(q)?.roundToInt(),
            cadenceRpm = if (stopped && !cad.isEmpty) 0 else cad.at(q)?.roundToInt(),
            headingDeg = if (la != null && lo != null) headingAt(epochMs, i, la, lo) else null,
            altitudeM = alt.at(q),
            latitude = la,
            longitude = lo,
            gradePercent = gradeAt(distance),
            elevationGainM = gain?.let { if (i < t.size - 1) it[i] + (it[i + 1] - it[i]) * frac else it[i] },
            track = route.prefix(routeCount[i]),
            route = route,
            elevationProfile = elevationProfile,
            powerW = if (stopped && !power.isEmpty) 0 else power.at(q)?.roundToInt(),
            temperatureC = temp.at(q)?.toFloat(),
            hrConnected = hasHeartRate,
            cscConnected = hasCadence,
            gpsFix = la != null,
        )
    }

    /** Índice da última amostra com hora <= [epochMs]. */
    private fun segment(epochMs: Long): Int {
        var lo = 0
        var hi = t.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (t[mid] <= epochMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun positionAt(epochMs: Long): Pair<Double, Double>? {
        val e = epochMs.coerceIn(startEpochMs, endEpochMs)
        val i = segment(e)
        val q = if (i < t.size - 1 && gapAfter[i]) t[i] else e
        val la = lat.at(q) ?: return null
        val lo = lon.at(q) ?: return null
        return la to lo
    }

    /** Rumo pela posição ±[HEADING_HALF_WINDOW_MS]; parado (ou quase), o último rumo conhecido. */
    private fun headingAt(epochMs: Long, i: Int, la: Double, lo: Double): Float? {
        val a = positionAt(epochMs - HEADING_HALF_WINDOW_MS) ?: (la to lo)
        val b = positionAt(epochMs + HEADING_HALF_WINDOW_MS) ?: (la to lo)
        if (distanceM(a.first, a.second, b.first, b.second) >= MIN_HEADING_DISTANCE_M) {
            return bearingDeg(a.first, a.second, b.first, b.second)
        }
        return segHeading[i].takeIf { !it.isNaN() }
    }

    private fun gradeAt(distance: Double): Float? {
        val s = altByDist ?: return null
        val lo = maxOf(distance - GRADE_WINDOW_M / 2, s.firstKey)
        val hi = minOf(distance + GRADE_WINDOW_M / 2, s.lastKey)
        if (hi - lo < GRADE_WINDOW_M * 0.5) return null
        val a0 = s.atKey(lo) ?: return null
        val a1 = s.atKey(hi) ?: return null
        return ((a1 - a0) / (hi - lo) * 100.0).coerceIn(-MAX_GRADE, MAX_GRADE).toFloat()
    }

    companion object {
        /** Intervalo entre amostras a partir do qual o ciclista é considerado parado. */
        const val MAX_GAP_MS = 20_000L
        const val MOVING_MPS = 0.6
        const val GAIN_HYSTERESIS_M = 2.0
        const val GRADE_WINDOW_M = 60.0
        private const val MAX_GRADE = 35.0
        private const val HEADING_HALF_WINDOW_MS = 3_000L
        private const val MIN_HEADING_DISTANCE_M = 3.0
        private const val ROUTE_SPACING_M = 5.0

        fun from(raw: List<ActivitySample>): RideTimeline {
            val s = raw.sortedBy { it.epochMs }.distinctBy { it.epochMs }
            require(s.isNotEmpty()) { "Trilha vazia" }
            val n = s.size
            val t = LongArray(n) { s[it].epochMs }
            val gapAfter = BooleanArray(n) { it < n - 1 && t[it + 1] - t[it] > MAX_GAP_MS }

            val dist = cumulativeDistance(s)
            val speed = if (s.count { it.speedMps != null } * 2 >= n) {
                Series.of(s) { it.speedMps?.toDouble() }
            } else {
                derivedSpeed(t, dist, gapAfter)
            }

            // Tempo em movimento acumulado; um trecho conta se a velocidade média nele passa do limiar.
            val segMoving = BooleanArray(n)
            val movingMs = LongArray(n)
            for (i in 0 until n - 1) {
                val dt = t[i + 1] - t[i]
                val byDist = (dist[i + 1] - dist[i]) / (dt / 1000.0)
                val bySpeed = ((s[i].speedMps ?: 0f) + (s[i + 1].speedMps ?: 0f)) / 2.0
                segMoving[i] = !gapAfter[i] && maxOf(byDist, bySpeed) >= MOVING_MPS
                movingMs[i + 1] = movingMs[i] + if (segMoving[i]) dt else 0L
            }

            val routeBuilder = TrackBuilder(ROUTE_SPACING_M)
            val routeCount = IntArray(n)
            val segHeading = FloatArray(n) { Float.NaN }
            var lastPos: ActivitySample? = null
            var heading = Float.NaN
            for (i in 0 until n) {
                val p = s[i]
                if (p.hasPosition) {
                    routeBuilder.add(p.latitude!!, p.longitude!!)
                    val prev = lastPos
                    if (prev != null && distanceM(prev.latitude!!, prev.longitude!!, p.latitude, p.longitude) >= MIN_HEADING_DISTANCE_M) {
                        heading = bearingDeg(prev.latitude, prev.longitude, p.latitude, p.longitude!!)
                    }
                    lastPos = p
                }
                routeCount[i] = routeBuilder.path.count
                segHeading[i] = heading
            }

            val altByDist = altitudeByDistance(s, dist)
            return RideTimeline(
                t = t,
                gapAfter = gapAfter,
                dist = dist,
                movingMs = movingMs,
                segMoving = segMoving,
                gain = elevationGain(s),
                speed = speed,
                lat = Series.of(s) { if (it.hasPosition) it.latitude else null },
                lon = Series.of(s) { if (it.hasPosition) it.longitude else null },
                alt = Series.of(s) { it.altitudeM },
                hr = Series.of(s) { it.heartRateBpm?.takeIf { v -> v > 0 }?.toDouble() },
                cad = Series.of(s) { it.cadenceRpm?.toDouble() },
                power = Series.of(s) { it.powerW?.toDouble() },
                temp = Series.of(s) { it.temperatureC?.toDouble() },
                altByDist = altByDist,
                segHeading = segHeading,
                routeCount = routeCount,
                route = routeBuilder.path,
                elevationProfile = altByDist?.let { ElevationProfile.from(it.keysCopy(), it.valuesCopy()) },
            )
        }

        /** Distância do aparelho (sensor de roda) quando existe; senão, soma das distâncias entre posições. */
        private fun cumulativeDistance(s: List<ActivitySample>): DoubleArray {
            val n = s.size
            val out = DoubleArray(n)
            if (s.count { it.distanceM != null } * 2 >= n) {
                var last = 0.0
                for (i in 0 until n) {
                    last = maxOf(last, s[i].distanceM ?: last)
                    out[i] = last
                }
                return out
            }
            var acc = 0.0
            var prev: ActivitySample? = null
            for (i in 0 until n) {
                val p = s[i]
                if (p.hasPosition) {
                    prev?.let { acc += distanceM(it.latitude!!, it.longitude!!, p.latitude!!, p.longitude!!) }
                    prev = p
                }
                out[i] = acc
            }
            return out
        }

        /** Velocidade por diferença central da distância, sem atravessar paradas. */
        private fun derivedSpeed(t: LongArray, dist: DoubleArray, gapAfter: BooleanArray): Series {
            val n = t.size
            val v = DoubleArray(n) { i ->
                val a = if (i > 0 && !gapAfter[i - 1]) i - 1 else i
                val b = if (i < n - 1 && !gapAfter[i]) i + 1 else i
                if (a == b) 0.0 else (dist[b] - dist[a]) / ((t[b] - t[a]) / 1000.0)
            }
            return Series(t, v)
        }

        /** Ganho acumulado por amostra, com histerese para o ruído da altitude não virar subida. */
        private fun elevationGain(s: List<ActivitySample>): DoubleArray? {
            if (s.none { it.altitudeM != null }) return null
            val out = DoubleArray(s.size)
            var ref: Double? = null
            var gain = 0.0
            for (i in s.indices) {
                val a = s[i].altitudeM
                if (a != null) {
                    val r = ref
                    when {
                        r == null -> ref = a
                        a - r >= GAIN_HYSTERESIS_M -> { gain += a - r; ref = a }
                        r - a >= GAIN_HYSTERESIS_M -> ref = a
                    }
                }
                out[i] = gain
            }
            return out
        }

        /** Altitude em função da distância (estritamente crescente), base da inclinação. */
        private fun altitudeByDistance(s: List<ActivitySample>, dist: DoubleArray): Series? {
            val keys = ArrayList<Double>()
            val values = ArrayList<Double>()
            for (i in s.indices) {
                val a = s[i].altitudeM ?: continue
                if (keys.isNotEmpty() && dist[i] < keys.last() + 0.5) continue
                keys.add(dist[i])
                values.add(a)
            }
            if (keys.size < 2) return null
            return Series(keys.toDoubleArray(), values.toDoubleArray())
        }
    }
}

/** Distância em metros entre dois pontos (haversine). */
internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_008.8
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/** Rumo inicial de 1 para 2, em graus (0 = norte), sentido horário. */
internal fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}

/**
 * Função linear por partes (chave crescente → valor). Entre duas amostras distantes mais que
 * [maxGap] (na unidade da chave) mantém o valor anterior em vez de interpolar.
 */
internal class Series(private val keys: DoubleArray, private val values: DoubleArray, private val maxGap: Double = Double.MAX_VALUE) {
    constructor(times: LongArray, values: DoubleArray) :
        this(DoubleArray(times.size) { times[it].toDouble() }, values, RideTimeline.MAX_GAP_MS.toDouble())

    val isEmpty: Boolean get() = keys.isEmpty()
    fun keysCopy(): DoubleArray = keys.copyOf()
    fun valuesCopy(): DoubleArray = values.copyOf()
    val firstKey: Double get() = keys.first()
    val lastKey: Double get() = keys.last()

    fun at(epochMs: Long): Double? = atKey(epochMs.toDouble())

    fun atKey(k: Double): Double? {
        if (keys.isEmpty() || k < keys[0] || k > keys[keys.size - 1]) return null
        var lo = 0
        var hi = keys.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (keys[mid] <= k) lo = mid else hi = mid - 1
        }
        if (lo == keys.size - 1) return values[lo]
        val span = keys[lo + 1] - keys[lo]
        if (span > maxGap) return values[lo]
        return values[lo] + (values[lo + 1] - values[lo]) * (k - keys[lo]) / span
    }

    companion object {
        fun of(samples: List<ActivitySample>, field: (ActivitySample) -> Double?): Series {
            val ks = ArrayList<Double>()
            val vs = ArrayList<Double>()
            for (p in samples) {
                val v = field(p) ?: continue
                if (!v.isFinite()) continue
                ks.add(p.epochMs.toDouble())
                vs.add(v)
            }
            return Series(ks.toDoubleArray(), vs.toDoubleArray(), RideTimeline.MAX_GAP_MS.toDouble())
        }
    }
}
