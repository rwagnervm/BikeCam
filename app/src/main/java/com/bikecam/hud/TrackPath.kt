package com.bikecam.hud

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/** Web Mercator normalizado: o mundo inteiro vai de 0 a 1 em x e em y (y cresce para o sul). */
object Mercator {
    private const val MAX_LAT = 85.05112878
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    fun x(lon: Double): Double = (lon + 180.0) / 360.0

    fun y(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    /** Quantos metros no chão equivalem a 1 unidade normalizada, na latitude dada. */
    fun metersPerUnit(lat: Double): Double = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat))
}

/**
 * Vista imutável de uma trilha em coordenadas Mercator normalizadas. Só os [count] primeiros
 * pontos valem. O [TrackBuilder] só escreve depois do fim de cada vista publicada, então uma vista
 * antiga continua válida (e pode ser lida de outra thread) mesmo enquanto a trilha cresce.
 */
class TrackPath internal constructor(private val xy: DoubleArray, val count: Int) {
    fun x(i: Int): Double = xy[2 * i]
    fun y(i: Int): Double = xy[2 * i + 1]

    /** Os [n] primeiros pontos, sem copiar (ex.: o trecho já percorrido de uma rota conhecida). */
    fun prefix(n: Int): TrackPath = if (n >= count) this else TrackPath(xy, n.coerceAtLeast(0))

    companion object {
        val EMPTY = TrackPath(DoubleArray(0), 0)
    }
}

/**
 * Acumula a trilha percorrida, guardando um ponto a cada [minSpacingM] metros (filtra o ruído do
 * GPS parado e mantém a trilha pequena: 100 km cabem em ~320 KB). Use de uma única thread.
 */
class TrackBuilder(private val minSpacingM: Double = 5.0) {
    private var xy = DoubleArray(4096)
    private var count = 0
    private var lastX = 0.0
    private var lastY = 0.0

    var path: TrackPath = TrackPath.EMPTY
        private set

    fun reset() {
        // Array novo: as vistas antigas, que podem estar sendo desenhadas, não são tocadas.
        xy = DoubleArray(4096)
        count = 0
        path = TrackPath.EMPTY
    }

    /** Acrescenta a posição se ela se afastou o bastante do último ponto. Retorna true se entrou. */
    fun add(lat: Double, lon: Double): Boolean {
        val x = Mercator.x(lon)
        val y = Mercator.y(lat)
        if (count > 0 && hypot(x - lastX, y - lastY) * Mercator.metersPerUnit(lat) < minSpacingM) return false
        if (2 * count + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
        xy[2 * count] = x
        xy[2 * count + 1] = y
        count++
        lastX = x
        lastY = y
        path = TrackPath(xy, count)
        return true
    }
}
