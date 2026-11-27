package com.bikecam.render

import android.net.Uri
import com.bikecam.hud.HudLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tudo o que o [RenderService] precisa para gerar o vídeo; montado pela tela a partir da sessão. */
data class RenderRequest(
    val chapters: List<VideoChapter>,
    val clips: List<ChapterClip>,
    val timeline: RideTimeline,
    /** Hora (epoch ms) do primeiro quadro da saída: início do vídeo + início do recorte. */
    val outputStartEpochMs: Long,
    val settings: RenderSettings,
    val layout: HudLayout,
    val useMapTiles: Boolean,
    val outputWidth: Int,
    val outputHeight: Int,
    val videoBitrate: Int,
    /** Precisa redimensionar (saída menor que a entrada). */
    val resize: Boolean,
)

sealed interface RenderState {
    data object Idle : RenderState
    data class Preparing(val fraction: Float) : RenderState
    /** Primeira passada: limpeza do áudio (fração null enquanto carrega o modelo). */
    data class CleaningAudio(val fraction: Float?, val elapsedMs: Long) : RenderState
    data class Encoding(val fraction: Float?, val elapsedMs: Long) : RenderState
    data object Saving : RenderState
    data class Done(val uri: Uri?, val displayPath: String, val elapsedMs: Long) : RenderState
    data class Failed(val message: String) : RenderState
    data object Cancelled : RenderState

    val running: Boolean get() = this is Preparing || this is CleaningAudio || this is Encoding || this is Saving
}

/** Fila de um trabalho só, compartilhada entre a tela e o serviço (mesmo processo). */
class RenderJobs {
    private val _state = MutableStateFlow<RenderState>(RenderState.Idle)
    val state: StateFlow<RenderState> = _state.asStateFlow()

    @Volatile
    var pending: RenderRequest? = null

    fun update(s: RenderState) {
        _state.value = s
    }
}
