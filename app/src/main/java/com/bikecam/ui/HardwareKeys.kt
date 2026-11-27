package com.bikecam.ui

import android.view.KeyEvent

/** Ações disparadas por botões físicos (volume do celular ou controle remoto Bluetooth de selfie). */
enum class HardwareAction { TOGGLE_RECORDING, PHOTO }

/**
 * Ponte entre a Activity (que recebe as teclas) e a tela de gravação (que sabe o que fazer).
 * Só há [handler] enquanto a tela de gravação está aberta e a opção está ligada; nas outras telas
 * os botões de volume continuam mudando o volume normalmente.
 */
class HardwareKeys {
    @Volatile var handler: ((HardwareAction) -> Unit)? = null

    /** Devolve true se a tecla foi consumida (não muda o volume). Ignora a repetição de tecla segurada. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val action = actionFor(event.keyCode) ?: return false
        val h = handler ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) h(action)
        return true
    }

    companion object {
        fun actionFor(keyCode: Int): HardwareAction? = when (keyCode) {
            // Controles remotos de selfie Bluetooth também enviam "volume +".
            KeyEvent.KEYCODE_VOLUME_UP -> HardwareAction.TOGGLE_RECORDING
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA -> HardwareAction.PHOTO
            else -> null
        }
    }
}
