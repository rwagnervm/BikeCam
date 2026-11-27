package com.bikecam.render

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/** Um arquivo de vídeo escolhido pelo usuário (um capítulo, no caso da GoPro). */
data class VideoChapter(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    /** Tamanho já com a rotação aplicada (como o vídeo aparece). */
    val width: Int,
    val height: Int,
    val mime: String?,
    val fps: Float,
    val info: Mp4Info?,
)

object VideoProbe {
    fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"

    fun probe(context: Context, uri: Uri): VideoChapter {
        val info = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { Mp4Metadata.read(it) }
            }
        }.getOrNull()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun key(k: Int) = r.extractMetadata(k)
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: info?.durationMs ?: 0L
            val swap = rot == 90 || rot == 270
            val track = videoTrack(context, uri)
            return VideoChapter(
                uri = uri,
                name = displayName(context, uri),
                durationMs = duration,
                width = if (swap) h else w,
                height = if (swap) w else h,
                mime = track?.first,
                fps = track?.second ?: DEFAULT_FPS,
                info = info,
            )
        } finally {
            r.release()
        }
    }

    private const val DEFAULT_FPS = 30f

    /** MIME do codec da trilha de vídeo (`video/hevc`, `video/avc`…, não do contêiner) e quadros/s. */
    private fun videoTrack(context: Context, uri: Uri): Pair<String, Float>? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).firstNotNullOfOrNull { i ->
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("video/") } ?: return@firstNotNullOfOrNull null
                val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                        .recoverCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
                } else {
                    null
                }
                mime to (fps?.takeIf { it > 0f } ?: DEFAULT_FPS)
            }
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }
}

/**
 * Tira quadros de uma gravação (vários capítulos) para a prévia. Um [MediaMetadataRetriever] por
 * capítulo, aberto sob demanda. Não é thread-safe: use de uma coroutine por vez.
 */
class FrameGrabber(private val context: Context, private val chapters: List<VideoChapter>) {
    private val retrievers = arrayOfNulls<MediaMetadataRetriever>(chapters.size)
    private val timeline = ChapterTimeline(chapters.map { it.durationMs })

    /** Quadro exato na [positionMs] da gravação, reduzido para no máximo [maxWidth] de largura. */
    fun frameAt(positionMs: Long, maxWidth: Int): Bitmap? {
        val (i, offsetMs) = timeline.locate(positionMs)
        val r = retrievers[i] ?: MediaMetadataRetriever().also {
            it.setDataSource(context, chapters[i].uri)
            retrievers[i] = it
        }
        val c = chapters[i]
        val us = offsetMs * 1000
        val opt = MediaMetadataRetriever.OPTION_CLOSEST
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && c.width > maxWidth && c.width > 0) {
            r.getScaledFrameAtTime(us, opt, maxWidth, (maxWidth.toLong() * c.height / c.width).toInt())
        } else {
            r.getFrameAtTime(us, opt)
        }
    }

    fun release() {
        for (i in retrievers.indices) {
            runCatching { retrievers[i]?.release() }
            retrievers[i] = null
        }
    }
}
