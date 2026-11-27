package com.bikecam.render

import android.media.MediaCodecList

/** Consulta os codecs do aparelho antes de começar (evita falhar no meio de uma renderização longa). */
object CodecSupport {
    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }

    fun canDecode(mime: String, width: Int, height: Int): Boolean = supports(encoder = false, mime, width, height)

    fun canEncode(mime: String, width: Int, height: Int): Boolean = supports(encoder = true, mime, width, height)

    private fun supports(encoder: Boolean, mime: String, width: Int, height: Int): Boolean =
        codecs.any { info ->
            info.isEncoder == encoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching {
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities
                    // Vídeo em retrato chega ao codec deitado (a rotação fica nos metadados).
                    caps.isSizeSupported(width, height) || caps.isSizeSupported(height, width)
                }.getOrDefault(false)
        }
}
