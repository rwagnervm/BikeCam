package com.bikecam.recording

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudRenderer
import com.bikecam.hud.TileSource
import com.bikecam.telemetry.TelemetrySnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * Queima o HUD nos quadros do vídeo (somente VIDEO_CAPTURE; o preview fica limpo e recebe o
 * HUD por cima, em Compose).
 *
 * Desacoplamento (RNF03): a cada snapshot de telemetria (5 Hz) o HUD é *gravado* num [Picture]
 * em background, por [update]. O callback por quadro, na thread do OSD, apenas lê a última
 * Picture pronta (AtomicReference, sem lock) e a reproduz no canvas da GPU. Sensores lentos ou
 * travados nunca atrasam um quadro.
 */
class OsdOverlay(tiles: TileSource?) {
    private val thread = HandlerThread("BikeCam-OSD").apply { start() }
    private val handler = Handler(thread.looper)
    private val picture = AtomicReference<Picture?>(null)
    private val renderer = HudRenderer(tiles) // usado só em update(), sempre na mesma thread de telemetria

    // Tamanho do vídeo "em pé" (já considerando a rotação), aprendido no primeiro quadro.
    @Volatile private var displayW = 0
    @Volatile private var displayH = 0

    // Cache da transformação tela->buffer (só recalcula se tamanho/rotação mudarem).
    private var cachedW = -1
    private var cachedH = -1
    private var cachedRotation = -1
    private val displayToBuffer = Matrix()

    val effect: OverlayEffect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        0, // queueDepth 0: desenha imediatamente em cada quadro
        handler,
    ) { t -> Log.e(TAG, "erro no OverlayEffect", t) }.apply {
        setOnDrawListener { frame -> drawFrame(frame) }
    }

    private fun drawFrame(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val w = frame.size.width
        val h = frame.size.height
        val rot = frame.rotationDegrees
        if (w != cachedW || h != cachedH || rot != cachedRotation) {
            val bufferToDisplay = Matrix().apply { setRotate(rot.toFloat()) }
            val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
            bufferToDisplay.mapRect(r)
            bufferToDisplay.postTranslate(-r.left, -r.top)
            bufferToDisplay.invert(displayToBuffer)
            cachedW = w; cachedH = h; cachedRotation = rot
            displayW = r.width().toInt()
            displayH = r.height().toInt()
        }
        val p = picture.get() ?: return true
        canvas.save()
        canvas.concat(displayToBuffer)
        canvas.drawPicture(p)
        canvas.restore()
        return true
    }

    /** Chamado pela coleta de telemetria (5 Hz), fora da thread do OSD. */
    fun update(snapshot: TelemetrySnapshot, layout: HudLayout) {
        val w = displayW
        val h = displayH
        if (w <= 0 || h <= 0) return
        val p = Picture()
        val c = p.beginRecording(w, h)
        renderer.draw(c, w.toFloat(), h.toFloat(), snapshot, layout)
        p.endRecording()
        picture.set(p)
    }

    fun release() {
        effect.close()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "BikeCam.OSD"
    }
}
