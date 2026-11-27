package com.bikecam.recording

import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Confirmação sem olhar para a tela (celular no guidão, acionado pelos botões de volume):
 * padrões de vibração diferentes para cada ação + os sons padrão de câmera do Android.
 *
 * - início da gravação: 1 vibração longa + som de início;
 * - fim da gravação: 2 vibrações curtas + som de fim;
 * - foto salva: 1 vibração curta + som de obturador;
 * - erro: 3 vibrações curtas.
 */
class ActionFeedback(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
        load(MediaActionSound.SHUTTER_CLICK)
    }

    var enabled = true

    fun recordingStarted() = play(longArrayOf(0, 400), MediaActionSound.START_VIDEO_RECORDING)
    fun recordingStopped() = play(longArrayOf(0, 120, 120, 120), MediaActionSound.STOP_VIDEO_RECORDING)
    fun photoSaved() = play(longArrayOf(0, 60), MediaActionSound.SHUTTER_CLICK)
    fun error() = play(longArrayOf(0, 80, 80, 80, 80, 80), null)

    private fun play(pattern: LongArray, soundId: Int?) {
        if (!enabled) return
        runCatching {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // "Resposta a botão físico": não é cortada quando a vibração ao toque está desligada.
                vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
            } else {
                vibrator?.vibrate(effect)
            }
        }
        if (soundId != null) runCatching { sound.play(soundId) }
    }

    fun release() = sound.release()
}
