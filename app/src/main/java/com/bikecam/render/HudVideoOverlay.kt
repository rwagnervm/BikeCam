package com.bikecam.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudRenderer
import com.bikecam.hud.HudWidget
import com.bikecam.hud.TileSource

/**
 * Desenha o HUD em cada quadro do vídeo renderizado. É aplicado como efeito da `Composition`, então
 * [onDraw] recebe o tempo da **saída** (0 = início do recorte, já somando os capítulos).
 */
@UnstableApi
class HudVideoOverlay(
    private val timeline: RideTimeline,
    private val layout: HudLayout,
    tiles: TileSource?,
    /** Hora (epoch ms) do primeiro quadro da saída: início do vídeo + início do recorte. */
    private val outputStartEpochMs: Long,
) : CanvasOverlay(/* useInputFrameSize= */ true) {
    private val renderer = HudRenderer(tiles)
    private var loggedFirst = false

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        if (!loggedFirst) {
            Log.i(TAG, "primeiro quadro: pts=$presentationTimeUs µs, canvas=${canvas.width}x${canvas.height}")
            loggedFirst = true
        }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val snapshot = timeline.snapshotAt(outputStartEpochMs + presentationTimeUs / 1000)
        renderer.draw(canvas, canvas.width.toFloat(), canvas.height.toFloat(), snapshot, layout, waitForTilesMs = TILE_WAIT_MS)
    }

    companion object {
        private const val TAG = "BikeCam.Render"
        /** Os tiles são pré-carregados; isto só cobre algum que tenha saído da memória. */
        private const val TILE_WAIT_MS = 500L
        private const val PREFETCH_STEP_MS = 2_000L
        private const val PREFETCH_WAIT_MS = 4_000L

        /**
         * Desenha o HUD fora da tela ao longo do trecho para baixar (e pôr em cache) todos os tiles
         * do mini-mapa antes da renderização, já que a rota é conhecida.
         */
        fun prefetchTiles(
            timeline: RideTimeline, layout: HudLayout, tiles: TileSource, width: Int, height: Int,
            startEpochMs: Long, durationMs: Long, onProgress: (Float) -> Unit,
        ) {
            val renderer = HudRenderer(tiles)
            // Só o mapa importa aqui. O quadro tem o tamanho da saída porque o zoom do mapa depende dele.
            val mapOnly = HudLayout(layout.placements.map { it.copy(enabled = it.enabled && it.widget == HudWidget.MAP) })
            if (mapOnly.placements.none { it.enabled }) return
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                var t = 0L
                while (t <= durationMs) {
                    val s = timeline.snapshotAt(startEpochMs + t)
                    renderer.draw(canvas, width.toFloat(), height.toFloat(), s, mapOnly, waitForTilesMs = PREFETCH_WAIT_MS)
                    onProgress((t.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))
                    if (Thread.interrupted()) return
                    t += PREFETCH_STEP_MS
                }
            } finally {
                bmp.recycle()
            }
        }
    }
}
