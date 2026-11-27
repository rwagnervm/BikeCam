package com.bikecam.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.bikecam.BikeCamApp
import com.bikecam.MainActivity
import com.bikecam.render.audio.AudioCleanup
import com.bikecam.render.audio.SkipStartProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gera o vídeo com o HUD em segundo plano (Media3 Transformer), com notificação de progresso e
 * botão de cancelar. Um trabalho por vez; o pedido vem de [RenderJobs.pending].
 */
@UnstableApi
class RenderService : Service() {
    private val graph by lazy { (application as BikeCamApp).graph }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var transformer: Transformer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tempFile: File? = null
    private var audioFile: File? = null
    private var cleanup: AudioCleanup? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancel()
            else -> start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val request = graph.renderJobs.pending
        if (request == null || job?.isActive == true) {
            if (job?.isActive != true) stopSelf()
            return
        }
        graph.renderJobs.pending = null
        createChannel()
        goForeground(notification("Preparando…", null))
        acquireWakeLock()
        job = scope.launch { run(request) }
    }

    private fun cancel() {
        transformer?.cancel()
        transformer = null
        job?.cancel()
    }

    private suspend fun run(req: RenderRequest) {
        val jobs = graph.renderJobs
        val started = SystemClock.elapsedRealtime()
        try {
            // 1) Tiles do mini-mapa ao longo do trecho.
            jobs.update(RenderState.Preparing(0f))
            if (req.useMapTiles) {
                runInterruptible(Dispatchers.Default) {
                    HudVideoOverlay.prefetchTiles(
                        req.timeline, req.layout, graph.tiles, req.outputWidth, req.outputHeight,
                        req.outputStartEpochMs, req.settings.durationMs,
                    ) { jobs.update(RenderState.Preparing(it)) }
                }
            }

            // 2) Limpeza do áudio numa passada só de áudio, para um arquivo temporário. Junto com o
            // vídeo travava: o muxer não deixa o vídeo passar 0,5 s à frente do áudio, e a limpeza
            // segura o áudio ~3,5 s (janelas do modelo).
            val cleanAudio = if (req.settings.cleansAudio) cleanAudio(req, started) else null

            // 3) Codificação do vídeo com o HUD.
            val out = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "render_tmp.mp4")
            out.delete()
            tempFile = out
            val encoders = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(req.videoBitrate).build())
                .setEnableFallback(true)
                .build()
            export(buildComposition(req, cleanAudio), out, Transformer.Builder(this).setVideoMimeType(req.settings.codec.mime).setEncoderFactory(encoders)) { fraction ->
                jobs.update(RenderState.Encoding(fraction, SystemClock.elapsedRealtime() - started))
                "Renderizando…"
            }
            cleanAudio?.delete()

            // 4) Publica em Movies/BikeCam.
            jobs.update(RenderState.Saving)
            notify(notification("Salvando…", null))
            val name = "render_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val uri = withContext(Dispatchers.IO) { publish(out, name) }
            out.delete()
            val elapsed = SystemClock.elapsedRealtime() - started
            jobs.update(RenderState.Done(uri, "Movies/$FOLDER/$name", elapsed))
            notifyFinished("Vídeo pronto", "Movies/$FOLDER/$name", uri)
        } catch (e: CancellationException) {
            jobs.update(RenderState.Cancelled)
        } catch (e: Exception) {
            Log.e(TAG, "falha na renderização", e)
            val msg = (e as? ExportException)?.let { "${it.errorCodeName}: ${it.message}" } ?: (e.message ?: e.javaClass.simpleName)
            jobs.update(RenderState.Failed(msg))
            notifyFinished("Falha ao renderizar", msg, null)
        } finally {
            transformer?.cancel()
            transformer = null
            cleanup?.close()
            cleanup = null
            tempFile?.delete()
            tempFile = null
            audioFile?.delete()
            audioFile = null
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

    /** Os trechos do vídeo (partes da GoPro recortadas), na ordem. */
    private fun clipItems(req: RenderRequest, removeAudio: Boolean = false, removeVideo: Boolean = false): List<EditedMediaItem> =
        req.clips.map { clip ->
            val media = MediaItem.Builder()
                .setUri(req.chapters[clip.chapter].uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build(),
                )
                .build()
            EditedMediaItem.Builder(media).setRemoveAudio(removeAudio).setRemoveVideo(removeVideo).build()
        }

    /** Vídeo com o HUD; o áudio vem do próprio vídeo ou, se limpo, de [cleanAudio]. */
    private fun buildComposition(req: RenderRequest, cleanAudio: File?): Composition {
        val tiles = if (req.useMapTiles) graph.tiles else null
        val effects = buildList {
            if (req.resize) add(Presentation.createForShortSide(minOf(req.outputWidth, req.outputHeight)))
            add(OverlayEffect(listOf(HudVideoOverlay(req.timeline, req.layout, tiles, req.outputStartEpochMs))))
        }
        val sequences = if (cleanAudio != null) {
            listOf(
                EditedMediaItemSequence.withVideoFrom(clipItems(req, removeAudio = true)),
                EditedMediaItemSequence.withAudioFrom(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(cleanAudio))).build())),
            )
        } else {
            listOf(EditedMediaItemSequence.Builder(clipItems(req, removeAudio = !req.settings.includeAudio)).build())
        }
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), effects))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    /**
     * Primeira passada: só o áudio dos trechos, limpo pelo modelo, num arquivo temporário (AAC de
     * alta taxa, que a segunda passada recodifica).
     */
    private suspend fun cleanAudio(req: RenderRequest, started: Long): File {
        val jobs = graph.renderJobs
        jobs.update(RenderState.CleaningAudio(null, SystemClock.elapsedRealtime() - started))
        notify(notification("Carregando o modelo de áudio…", null))
        val c = AudioCleanup.create(this, req.settings.removeVoices, req.settings.removeWind)
        cleanup = c
        val file = File(cacheDir, "render_audio.mp4").apply { delete() }
        audioFile = file
        // O codificador AAC atrasa o som 448 amostras além do que marca no arquivo (medido); sem
        // descontar, a dupla passada deixava o som 19 ms atrás da imagem em vez dos 9 ms de uma
        // passada. (Recortar o início na segunda passada não serve: com o corte, o ExoPlayer deixa
        // de aplicar a marcação do arquivo.)
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioFrom(clipItems(req, removeVideo = true)))
            .setEffects(Effects(c.processors + SkipStartProcessor(AAC_UNMARKED_DELAY_FRAMES), emptyList()))
            .build()
        val encoders = DefaultEncoderFactory.Builder(this)
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(INTERMEDIATE_AUDIO_BITRATE).build())
            .build()
        // O modelo segura a saída por segundos (e mais se o celular esquentar); sem isto o
        // Transformer aborta quando fica 10 s sem amostra no muxer.
        export(composition, file, Transformer.Builder(this).setEncoderFactory(encoders).setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)) { fraction ->
            jobs.update(RenderState.CleaningAudio(fraction, SystemClock.elapsedRealtime() - started))
            "Limpando o áudio…"
        }
        c.close()
        cleanup = null
        return file
    }

    /**
     * Exporta [composition] para [out] e espera terminar, atualizando o progresso: [onProgress]
     * recebe a fração (ou null) e devolve o texto da notificação.
     */
    private suspend fun export(
        composition: Composition,
        out: File,
        builder: Transformer.Builder,
        onProgress: (Float?) -> String,
    ) {
        val done = CompletableDeferred<Unit>()
        val t = builder
            .setLooper(Looper.getMainLooper())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.i(TAG, "concluído ${out.name}: ${exportResult.width}x${exportResult.height}, ${exportResult.durationMs} ms, " +
                        "${exportResult.fileSizeBytes} bytes, vídeo=${exportResult.videoEncoderName}, áudio=${exportResult.audioEncoderName}")
                    done.complete(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        transformer = t
        t.start(composition, out.absolutePath)
        val holder = ProgressHolder()
        var lastNotified = -1
        while (!done.isCompleted && currentCoroutineContextActive()) {
            val fraction = if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null
            val text = onProgress(fraction)
            val pct = ((fraction ?: 0f) * 100).toInt()
            if (pct != lastNotified) {
                lastNotified = pct
                notify(notification("$text $pct%", pct))
            }
            delay(PROGRESS_INTERVAL_MS)
        }
        done.await()
        transformer = null
    }

    /** Copia o arquivo temporário para Movies/BikeCam (MediaStore no Android 10+). */
    private fun publish(file: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore recusou o vídeo")
            try {
                resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o, COPY_BUFFER) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
        return Uri.fromFile(target)
    }

    private fun goForeground(n: Notification) {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        Log.i(TAG, "foreground com tipo $type (SDK ${Build.VERSION.SDK_INT})")
        // Direto na API da plataforma: o ServiceCompat desta versão descartava o tipo mediaProcessing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, type) else startForeground(NOTIF_ID, n)
    }

    private fun notification(text: String, percent: Int?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, RenderService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Vídeo com HUD")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(openApp())
            .addAction(0, "Cancelar", cancel)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun notifyFinished(title: String, text: String, uri: Uri?) {
        val intent = if (uri != null) {
            PendingIntent.getActivity(
                this, 2,
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            openApp()
        }
        notify(
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build(),
            DONE_NOTIF_ID,
        )
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(n: Notification, id: Int = NOTIF_ID) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Renderização de vídeo", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BikeCam:render")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BikeCam.Render"
        private const val CHANNEL_ID = "render"
        private const val NOTIF_ID = 2
        private const val DONE_NOTIF_ID = 3
        private const val FOLDER = "BikeCam"
        private const val ACTION_CANCEL = "com.bikecam.render.CANCEL"
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val COPY_BUFFER = 1 shl 20
        private const val INTERMEDIATE_AUDIO_BITRATE = 320_000
        private const val AAC_UNMARKED_DELAY_FRAMES = 448

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RenderService::class.java))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, RenderService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
