package com.bikecam.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.bikecam.hud.TileSource
import com.bikecam.settings.AppSettings
import com.bikecam.settings.MountOrientation
import com.bikecam.settings.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

data class CameraCapabilities(
    val qualities: List<VideoQuality> = emptyList(),
    val frameRates: List<Int> = listOf(30),
    val stabilization: Boolean = false,
    /** false se o aparelho não aceita foto junto com preview + vídeo na resolução escolhida. */
    val photos: Boolean = true,
)

/** Configuração efetivamente aplicada ao CameraX (para saber quando é preciso refazer o bind). */
private data class BindConfig(val quality: VideoQuality, val fps: Int, val orientation: MountOrientation)

/**
 * Pipeline de câmera: Preview (limpo) + VideoCapture (H.264 por hardware via Recorder) com o
 * [OsdOverlay] aplicado só ao vídeo + ImageCapture (fotos durante o passeio, sem efeito).
 */
class CameraController(private val context: Context, private val owner: LifecycleOwner, tiles: TileSource?) {
    val overlay = OsdOverlay(tiles)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private val photoExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "BikeCam-Photo") }
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var recording: Recording? = null
    private var bound: BindConfig? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val _capabilities = MutableStateFlow(CameraCapabilities())
    val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    val isRecording: Boolean get() = recording != null

    /** Faz (ou refaz) o bind se a configuração de vídeo mudou. Não mexe durante a gravação. */
    suspend fun bind(settings: AppSettings) {
        if (isRecording) return
        val cfg = BindConfig(settings.quality, settings.fps, settings.orientation)
        if (cfg == bound) return
        val p = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        // Descobre o que o aparelho suporta antes de montar os use cases.
        val info = p.getCameraInfo(selector)
        val videoCaps = Recorder.getVideoCapabilities(info)
        val supported = videoCaps.getSupportedQualities(DynamicRange.SDR)
        val ranges = info.supportedFrameRateRanges
        val fpsOptions = listOf(30, 60).filter { f -> ranges.any { it.upper >= f } }.ifEmpty { listOf(30) }
        _capabilities.value = CameraCapabilities(
            qualities = VideoQuality.entries.filter { it.toQuality() in supported },
            frameRates = fpsOptions,
            stabilization = videoCaps.isStabilizationSupported,
        )

        val rotation = if (cfg.orientation == MountOrientation.LANDSCAPE) Surface.ROTATION_90 else Surface.ROTATION_0
        val previewUseCase = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .setTargetRotation(rotation)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(cfg.quality.toQuality(), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
            )
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        val fpsRange = pickRange(ranges, cfg.fps)
        val videoUseCase = VideoCapture.Builder(recorder)
            .setTargetRotation(rotation)
            .apply { if (fpsRange != null) setTargetFrameRate(fpsRange) }
            .apply { if (videoCaps.isStabilizationSupported) setVideoStabilizationEnabled(true) }
            .build()

        val imageUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .setTargetRotation(rotation)
            .setJpegQuality(92)
            .build()

        fun group(withPhotos: Boolean) = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(videoUseCase)
            .apply { if (withPhotos) addUseCase(imageUseCase) }
            .addEffect(overlay.effect)
            .build()

        p.unbindAll()
        // Nem todo aparelho aceita foto + vídeo + preview juntos em 4K/60: nesse caso grava sem fotos.
        val photos = try {
            camera = p.bindToLifecycle(owner, selector, group(withPhotos = true))
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "combinação com ImageCapture não suportada; fotos desativadas", e)
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, group(withPhotos = false))
            false
        }
        _capabilities.value = _capabilities.value.copy(photos = photos)
        preview = previewUseCase
        videoCapture = videoUseCase
        imageCapture = if (photos) imageUseCase else null
        previewUseCase.surfaceProvider = surfaceProvider
        bound = cfg
        Log.i(TAG, "câmera configurada: $cfg, fps=$fpsRange, fotos=$photos")
    }

    /** Range com teto == fps e o maior piso possível (fps mais estável). */
    private fun pickRange(ranges: Set<Range<Int>>, fps: Int): Range<Int>? =
        ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper > fps && it.lower <= fps }.maxByOrNull { it.lower }?.let { Range(it.lower, fps) }

    fun setSurfaceProvider(sp: Preview.SurfaceProvider?) {
        if (sp === surfaceProvider) return
        surfaceProvider = sp
        preview?.surfaceProvider = sp
    }

    @SuppressLint("MissingPermission") // áudio só é habilitado se RECORD_AUDIO foi concedida
    fun startRecording(
        baseName: String, withAudio: Boolean, durationLimitMs: Long, listener: Consumer<VideoRecordEvent>,
    ): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BikeCam")
            }
        }
        val options = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .apply { if (durationLimitMs > 0) setDurationLimitMillis(durationLimitMs) }
            .build()
        val audioOk = withAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        recording = vc.output.prepareRecording(context, options)
            .apply { if (audioOk) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) recording = null
                listener.accept(event)
            }
        return true
    }

    fun stopRecording() {
        recording?.stop()
    }

    /** Foto limpa (sem HUD), direto da câmera para Pictures/BikeCam. [onResult] roda na main thread. */
    fun takeCleanPhoto(name: String, location: Location?, onResult: (Boolean) -> Unit) {
        val ic = imageCapture ?: return onResult(false)
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues(name, pending = false),
        ).setMetadata(ImageCapture.Metadata().apply { this.location = location }).build()
        ic.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onResult(true)
            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "falha na foto limpa", e)
                onResult(false)
            }
        })
    }

    /**
     * Foto carimbada: captura em memória, desenha o HUD com [stamp] (canvas, largura, altura) numa
     * thread própria e salva em Pictures/BikeCam. [onResult] roda na main thread.
     */
    fun takeStampedPhoto(name: String, stamp: (Canvas, Int, Int) -> Unit, onResult: (Boolean) -> Unit) {
        val ic = imageCapture ?: return onResult(false)
        ic.takePicture(photoExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val ok = try {
                    val bmp = upright(image)
                    stamp(Canvas(bmp), bmp.width, bmp.height)
                    saveJpeg(bmp, name)
                    bmp.recycle()
                    true
                } catch (e: Throwable) { // inclui OutOfMemoryError em fotos muito grandes
                    Log.e(TAG, "falha na foto carimbada", e)
                    false
                }
                mainExecutor.execute { onResult(ok) }
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "falha na captura", e)
                mainExecutor.execute { onResult(false) }
            }
        })
    }

    /** Bitmap "em pé" e mutável, limitado a [MAX_STAMPED_SIDE] px no lado maior. */
    private fun upright(image: ImageProxy): Bitmap {
        val rotation = image.imageInfo.rotationDegrees
        val src = image.use { it.toBitmap() }
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        val scale = min(1f, MAX_STAMPED_SIDE / max(r.width(), r.height()))
        m.postTranslate(-r.left, -r.top)
        m.postScale(scale, scale)
        val out = Bitmap.createBitmap((r.width() * scale).toInt(), (r.height() * scale).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    private fun saveJpeg(bmp: Bitmap, name: String) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues(name, pending = true))
            ?: error("MediaStore recusou a foto")
        try {
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private fun photoValues(name: String, pending: Boolean) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BikeCam")
            if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    fun release() {
        recording?.close()
        recording = null
        provider?.unbindAll()
        bound = null
        overlay.release()
        photoExecutor.shutdown()
    }

    private fun VideoQuality.toQuality(): Quality = when (this) {
        VideoQuality.SD -> Quality.SD
        VideoQuality.HD -> Quality.HD
        VideoQuality.FHD -> Quality.FHD
        VideoQuality.UHD -> Quality.UHD
    }

    private companion object {
        const val TAG = "BikeCam.Camera"
        const val MAX_STAMPED_SIDE = 3840f
    }
}
