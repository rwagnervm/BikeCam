package com.bikecam.hud

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.graphics.Typeface
import com.bikecam.telemetry.SpeedSource
import com.bikecam.telemetry.TelemetrySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Desenha o HUD num [Canvas] qualquer: o mesmo código serve para a tela (Compose), para os
 * quadros do vídeo (OverlayEffect), para a foto carimbada e para o editor de layout.
 *
 * As medidas são relativas a min(largura, altura), então o HUD tem a mesma aparência em
 * qualquer resolução. NÃO é thread-safe (os Paints são reutilizados): use uma instância por thread.
 * [tiles] pode ser compartilhado entre instâncias.
 */
class HudRenderer(private val tiles: TileSource? = null) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        typeface = Typeface.DEFAULT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 193, 7)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 255, 255, 255)
    }
    private val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 24, 28, 32) }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Mapa um pouco escurecido e dessaturado para não brigar com a imagem da câmera.
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0.6f)
                postConcat(ColorMatrix().apply { setScale(0.82f, 0.82f, 0.82f, 1f) })
            },
        )
        alpha = 235
    }
    private val trackOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(190, 0, 0, 0)
    }
    private val trackPaint = Paint(trackOutlinePaint).apply { color = Color.rgb(255, 152, 0) }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val rect = RectF()
    private val tileDst = RectF()
    private val tileSrc = Rect()
    private val path = Path()
    private val clipPath = Path()
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val date = Date()

    /** Instante (elapsedRealtime) até quando [drawTiles] pode esperar pelos tiles; 0 = não espera. */
    private var tileDeadlineMs = 0L

    /**
     * Desenha o HUD. Se [bounds] for informado, recebe o retângulo de cada mostrador desenhado
     * (usado pelo editor para saber em qual mostrador o dedo tocou).
     *
     * [waitForTilesMs] > 0 faz o mini-mapa esperar até esse tempo pelos tiles que ainda não estão
     * em memória. Só para desenhos únicos fora das threads de vídeo/UI (foto carimbada).
     */
    fun draw(
        canvas: Canvas, width: Float, height: Float, s: TelemetrySnapshot, layout: HudLayout,
        bounds: MutableMap<HudWidget, RectF>? = null,
        waitForTilesMs: Long = 0L,
    ) {
        tileDeadlineMs = if (waitForTilesMs > 0) SystemClock.elapsedRealtime() + waitForTilesMs else 0L
        bounds?.clear()
        val u = minOf(width, height) / 100f
        for (p in layout.placements) {
            if (!p.enabled) continue
            when (p.widget) {
                HudWidget.SPEED -> valueBox(canvas, width, height, u, p, "VELOCIDADE",
                    s.speedKmh?.let { fmt1(it) } ?: "--", "km/h", sourceTag(s.speedSource))
                HudWidget.AVG_SPEED -> valueBox(canvas, width, height, u, p, "MÉDIA",
                    s.avgSpeedKmh?.let { fmt1(it) } ?: "--", "km/h")
                HudWidget.HEART_RATE -> valueBox(canvas, width, height, u, p, "♥ FC",
                    s.heartRateBpm?.toString() ?: "--", "bpm", if (s.hrConnected) null else "×", Color.rgb(255, 82, 82))
                HudWidget.CADENCE -> valueBox(canvas, width, height, u, p, "CADÊNCIA",
                    s.cadenceRpm?.toString() ?: "--", "rpm", if (s.cscConnected) null else "×")
                HudWidget.DISTANCE -> valueBox(canvas, width, height, u, p, "DISTÂNCIA",
                    String.format(Locale.US, "%.2f", s.distanceM / 1000.0), "km")
                HudWidget.ALTITUDE -> valueBox(canvas, width, height, u, p, "ALTITUDE",
                    s.altitudeM?.let { String.format(Locale.US, "%.0f", it) } ?: "--", "m")
                HudWidget.GRADE -> valueBox(canvas, width, height, u, p, "INCLINAÇÃO",
                    s.gradePercent?.let { gradeText(it) } ?: "--", "%", valueColor = gradeColor(s.gradePercent))
                HudWidget.ELEVATION_GAIN -> valueBox(canvas, width, height, u, p, "GANHO ▲",
                    s.elevationGainM?.let { String.format(Locale.US, "%.0f", it) } ?: "--", "m")
                HudWidget.CLOCK -> {
                    date.time = if (s.epochMs > 0) s.epochMs else System.currentTimeMillis()
                    valueBox(canvas, width, height, u, p, "HORA", clockFormat.format(date), "")
                }
                HudWidget.MAP -> miniMap(canvas, width, height, u, p, s)
            }
            bounds?.put(p.widget, RectF(rect))
        }
    }

    private fun sourceTag(src: SpeedSource) = if (src == SpeedSource.NONE) null else src.tag

    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

    // "-0.0" fica feio: abaixo de 0.05 em módulo mostra só "0.0".
    private fun gradeText(g: Float) = if (abs(g) < 0.05f) "0.0" else String.format(Locale.US, "%+.1f", g)

    private fun gradeColor(g: Float?): Int = when {
        g == null || g < 4f -> Color.WHITE
        g < 8f -> Color.rgb(255, 213, 79)
        else -> Color.rgb(255, 112, 67)
    }

    private fun valueBox(
        c: Canvas, w: Float, h: Float, u: Float, p: WidgetPlacement,
        label: String, value: String, unit: String, tag: String? = null, valueColor: Int = Color.WHITE,
    ) {
        val sc = p.scale
        valuePaint.textSize = 7f * u * sc
        unitPaint.textSize = 3f * u * sc
        labelPaint.textSize = 2.4f * u * sc
        valuePaint.color = valueColor
        val pad = 1.4f * u * sc
        val valueW = valuePaint.measureText(value)
        val unitW = if (unit.isEmpty()) 0f else unitPaint.measureText(unit) + 0.8f * u * sc
        val labelText = if (tag != null) "$label · $tag" else label
        val labelW = labelPaint.measureText(labelText)
        val boxW = maxOf(valueW + unitW, labelW) + 2 * pad
        val boxH = labelPaint.textSize + valuePaint.textSize + 2.2f * pad
        val (left, top) = place(w, h, u, p, boxW, boxH)
        rect.set(left, top, left + boxW, top + boxH)
        c.drawRoundRect(rect, 1.5f * u * sc, 1.5f * u * sc, boxPaint)
        val labelBase = top + pad + labelPaint.textSize * 0.8f
        c.drawText(labelText, left + pad, labelBase, labelPaint)
        val valueBase = top + boxH - pad - valuePaint.descent() * 0.3f
        c.drawText(value, left + pad, valueBase, valuePaint)
        if (unit.isNotEmpty()) c.drawText(unit, left + pad + valueW + 0.8f * u * sc, valueBase, unitPaint)
    }

    /**
     * Mini-mapa com o norte para cima, centrado na posição atual: tiles do OSM (quando houver),
     * a trilha percorrida e uma seta na direção do movimento.
     */
    private fun miniMap(c: Canvas, w: Float, h: Float, u: Float, p: WidgetPlacement, s: TelemetrySnapshot) {
        val k = u * p.scale
        val size = MAP_SIZE_U * k
        val (left, top) = place(w, h, u, p, size, size)
        rect.set(left, top, left + size, top + size)
        val radius = 1.5f * k
        c.drawRoundRect(rect, radius, radius, mapBgPaint)

        val lat = s.latitude
        val lon = s.longitude
        var drewTiles = false
        val cxPx = rect.centerX()
        val cyPx = rect.centerY()
        if (lat != null && lon != null) {
            val cx = Mercator.x(lon)
            val cy = Mercator.y(lat)
            // Mesma área no chão (MAP_SPAN_M de lado) em qualquer resolução.
            val unitsPerPx = MAP_SPAN_M / Mercator.metersPerUnit(lat) / size
            c.save()
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
            c.clipPath(clipPath)
            drewTiles = drawTiles(c, cx, cy, unitsPerPx, cxPx, cyPx)
            drawTrack(c, s.track, cx, cy, unitsPerPx, cxPx, cyPx, k)
            c.restore()
            drawMarker(c, cxPx, cyPx, k, s.headingDeg)
        } else {
            unitPaint.textSize = 3f * k
            unitPaint.textAlign = Paint.Align.CENTER
            c.drawText("sem GPS", cxPx, cyPx + unitPaint.textSize * 0.35f, unitPaint)
            unitPaint.textAlign = Paint.Align.LEFT
        }

        borderPaint.strokeWidth = 0.25f * k
        c.drawRoundRect(rect, radius, radius, borderPaint)
        labelPaint.textSize = 2.2f * k
        c.drawText("MAPA", left + 1.2f * k, top + 1.2f * k + labelPaint.textSize * 0.8f, labelPaint)
        if (drewTiles) {
            unitPaint.textSize = 1.6f * k
            unitPaint.textAlign = Paint.Align.RIGHT
            c.drawText("© OpenStreetMap", rect.right - 0.8f * k, rect.bottom - 0.8f * k, unitPaint)
            unitPaint.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * Desenha os tiles do zoom ideal que já estão em memória (os que faltam são pedidos em segundo
     * plano). Tile ausente é coberto pelo pedaço correspondente de um tile de zoom menor que já
     * esteja em memória (mais borrado, mas nunca um buraco cinza). Com [tileDeadlineMs] definido,
     * espera pelos tiles antes de cair no fallback.
     */
    private fun drawTiles(c: Canvas, cx: Double, cy: Double, unitsPerPx: Double, cxPx: Float, cyPx: Float): Boolean {
        val t = tiles ?: return false
        val z = (ln(1.0 / (unitsPerPx * TILE_PX)) / ln(2.0)).roundToInt().coerceIn(1, MAX_ZOOM)
        val n = 1 shl z
        val tilePx = (1.0 / n / unitsPerPx).toFloat()
        val halfW = rect.width() / 2.0 * unitsPerPx
        val halfH = rect.height() / 2.0 * unitsPerPx
        val x0 = floor((cx - halfW) * n).toInt()
        val x1 = floor((cx + halfW) * n).toInt()
        val y0 = floor((cy - halfH) * n).toInt().coerceAtLeast(0)
        val y1 = floor((cy + halfH) * n).toInt().coerceAtMost(n - 1)
        val waiting = tileDeadlineMs > 0
        if (waiting) {
            // Primeiro enfileira todos (carregam em paralelo), depois espera cada um.
            for (ty in y0..y1) for (tx in x0..x1) t.tile(z, tx, ty)
        }
        var any = false
        for (ty in y0..y1) {
            for (tx in x0..x1) {
                val l = cxPx + ((tx.toDouble() / n - cx) / unitsPerPx).toFloat()
                val tp = cyPx + ((ty.toDouble() / n - cy) / unitsPerPx).toFloat()
                tileDst.set(l, tp, l + tilePx + 0.5f, tp + tilePx + 0.5f) // +0.5 px evita frestas entre tiles
                val bmp = if (waiting) {
                    t.await(z, tx, ty, (tileDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                } else {
                    t.tile(z, tx, ty)
                }
                if (bmp != null) {
                    c.drawBitmap(bmp, null, tileDst, tilePaint)
                    any = true
                } else if (drawParentTile(c, t, z, tx, ty)) {
                    any = true
                }
            }
        }
        return any
    }

    /** Usa a parte certa de um tile de zoom z-1..z-[MAX_PARENT_LEVELS] em memória no lugar de (z, x, y). */
    private fun drawParentTile(c: Canvas, t: TileSource, z: Int, x: Int, y: Int): Boolean {
        for (d in 1..MAX_PARENT_LEVELS) {
            val pz = z - d
            if (pz < 0) break
            val parent = t.peek(pz, x shr d, y shr d) ?: continue
            val sub = parent.width shr d
            if (sub <= 0) break
            val ox = (x - ((x shr d) shl d)) * sub
            val oy = (y - ((y shr d) shl d)) * sub
            tileSrc.set(ox, oy, ox + sub, oy + sub)
            c.drawBitmap(parent, tileSrc, tileDst, tilePaint)
            return true
        }
        return false
    }

    private fun drawTrack(
        c: Canvas, track: TrackPath, cx: Double, cy: Double, unitsPerPx: Double, cxPx: Float, cyPx: Float, k: Float,
    ) {
        if (track.count == 0) return
        path.reset()
        var lastX = 0f
        var lastY = 0f
        for (i in 0 until track.count) {
            val x = cxPx + ((track.x(i) - cx) / unitsPerPx).toFloat()
            val y = cyPx + ((track.y(i) - cy) / unitsPerPx).toFloat()
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                // Pontos a menos de ~1,5 px do anterior não mudam o desenho.
                if (abs(x - lastX) + abs(y - lastY) < 1.5f) continue
                path.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }
        path.lineTo(cxPx, cyPx) // liga o último ponto gravado à posição atual
        trackOutlinePaint.strokeWidth = 1.3f * k
        trackPaint.strokeWidth = 0.7f * k
        c.drawPath(path, trackOutlinePaint)
        c.drawPath(path, trackPaint)
    }

    private fun drawMarker(c: Canvas, x: Float, y: Float, k: Float, heading: Float?) {
        val r = 1.8f * k
        markerStrokePaint.strokeWidth = 0.35f * k
        if (heading == null) {
            c.drawCircle(x, y, r * 0.8f, markerPaint)
            c.drawCircle(x, y, r * 0.8f, markerStrokePaint)
            return
        }
        c.save()
        c.rotate(heading, x, y) // mapa com norte para cima: a seta gira com o rumo
        path.reset()
        path.moveTo(x, y - r * 1.4f)
        path.lineTo(x + r, y + r)
        path.lineTo(x, y + r * 0.4f)
        path.lineTo(x - r, y + r)
        path.close()
        c.drawPath(path, markerPaint)
        c.drawPath(path, markerStrokePaint)
        c.restore()
    }

    /** Converte o centro normalizado em canto superior esquerdo, mantendo a caixa dentro do quadro. */
    private fun place(w: Float, h: Float, u: Float, p: WidgetPlacement, boxW: Float, boxH: Float): Pair<Float, Float> {
        val margin = 1.5f * u
        val left = (p.x * w - boxW / 2f).coerceIn(margin, maxOf(margin, w - boxW - margin))
        val top = (p.y * h - boxH / 2f).coerceIn(margin, maxOf(margin, h - boxH - margin))
        return left to top
    }

    private companion object {
        /** Lado do mini-mapa, em unidades de 1% do menor lado do quadro (antes da escala). */
        const val MAP_SIZE_U = 30f
        /** Quantos metros no chão o lado do mini-mapa mostra. */
        const val MAP_SPAN_M = 800.0
        const val TILE_PX = 256.0
        const val MAX_ZOOM = 18
        /** Até quantos níveis de zoom acima procurar um tile para cobrir um que falta. */
        const val MAX_PARENT_LEVELS = 3
    }
}
