package com.bikecam.render

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudRenderer
import com.bikecam.hud.TileSource
import com.bikecam.importer.ActivityFormat
import com.bikecam.importer.ActivityReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Gravação escolhida: capítulos em ordem e palpites de hora de início. */
class VideoInput(val chapters: List<VideoChapter>, val candidates: List<StartCandidate>, val notice: String?) {
    val durationMs: Long = chapters.sumOf { it.durationMs }
    val first: VideoChapter get() = chapters.first()
    val portrait: Boolean get() = first.height > first.width
}

/** Trilha escolhida, já processada, com a curva de velocidade para o gráfico da sincronização. */
class TrackInput(val name: String, val format: ActivityFormat, val timeline: RideTimeline) {
    /** Velocidade (km/h) em [PROFILE_POINTS] instantes igualmente espaçados da trilha. */
    val speedProfile: FloatArray = FloatArray(PROFILE_POINTS) { i ->
        val t = timeline.startEpochMs + (timeline.endEpochMs - timeline.startEpochMs) * i / (PROFILE_POINTS - 1)
        timeline.snapshotAt(t).speedKmh ?: 0f
    }

    companion object {
        const val PROFILE_POINTS = 400
    }
}

/**
 * Estado da tela “Vídeo com HUD”. Fica no AppGraph para sobreviver à recriação da Activity
 * (rotação). As propriedades são estado do Compose: leia e altere na thread principal.
 */
class RenderSession(context: Context, tiles: TileSource) {
    private val app = context.applicationContext
    val zone: ZoneId = ZoneId.systemDefault()

    var video by mutableStateOf<VideoInput?>(null)
        private set
    var track by mutableStateOf<TrackInput?>(null)
        private set
    var candidateIndex by mutableIntStateOf(0)
        private set

    /** Ajuste fino somado ao candidato escolhido. */
    var adjustMs by mutableLongStateOf(0L)
        private set
    var previewPositionMs by mutableLongStateOf(0L)
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    private val previewMutex = Mutex()
    private val renderer = HudRenderer(tiles)
    private var grabber: FrameGrabber? = null

    /** Candidatos do vídeo mais o início da trilha (sempre disponível como último recurso). */
    val candidates: List<StartCandidate>
        get() {
            val list = ArrayList(video?.candidates.orEmpty())
            track?.let { list += StartCandidate(StartSource.TRACK_START, it.timeline.startEpochMs, "Início da trilha") }
            return list
        }

    /** Hora (epoch ms) do primeiro quadro, ou null sem vídeo e trilha. */
    val videoStartEpochMs: Long?
        get() = candidates.getOrNull(candidateIndex)?.epochMs?.plus(adjustMs)

    val ready: Boolean get() = video != null && track != null

    // --- Recorte e saída ---

    var trimStartMs by mutableLongStateOf(0L)
        private set

    /** null = até o fim do vídeo. */
    var trimEndMs by mutableStateOf<Long?>(null)
        private set
    var resolution by mutableStateOf(OutputResolution.P1080)
    var codec by mutableStateOf(OutputCodec.H264)
    var includeAudio by mutableStateOf(true)
    var removeVoices by mutableStateOf(false)
    var removeWind by mutableStateOf(false)

    val effectiveTrimEndMs: Long get() = trimEndMs ?: video?.durationMs ?: 0L

    fun markTrimStart() {
        trimStartMs = previewPositionMs.coerceAtMost((effectiveTrimEndMs - MIN_TRIM_MS).coerceAtLeast(0L))
    }

    fun markTrimEnd() {
        trimEndMs = previewPositionMs.coerceIn(trimStartMs + MIN_TRIM_MS, video?.durationMs ?: Long.MAX_VALUE)
    }

    fun clearTrim() {
        trimStartMs = 0L
        trimEndMs = null
    }

    /** O que será gerado com as opções atuais, para mostrar antes de começar. */
    class OutputPlan(
        val width: Int,
        val height: Int,
        val videoBitrate: Int,
        val estimatedBytes: Long,
        val freeBytes: Long,
        val canDecode: Boolean,
        val canEncode: Boolean,
        val settings: RenderSettings,
    ) {
        /** Arquivo temporário + cópia final ficam no disco ao mesmo tempo. */
        val enoughSpace: Boolean get() = freeBytes > estimatedBytes * 2 + SPACE_MARGIN_BYTES
    }

    fun outputPlan(): OutputPlan? {
        val v = video ?: return null
        val first = v.first
        val settings = RenderSettings(
            trimStartMs, effectiveTrimEndMs, resolution, codec, includeAudio,
            removeVoices = includeAudio && removeVoices,
            removeWind = includeAudio && removeWind,
        )
        val (w, h) = RenderPlan.outputSize(first.width, first.height, resolution)
        val bitrate = RenderPlan.videoBitrate(w, h, first.fps, codec)
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir
        return OutputPlan(
            width = w,
            height = h,
            videoBitrate = bitrate,
            estimatedBytes = RenderPlan.estimateBytes(settings.durationMs, bitrate, includeAudio),
            freeBytes = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE),
            canDecode = first.mime?.let { CodecSupport.canDecode(it, first.width, first.height) } ?: false,
            canEncode = CodecSupport.canEncode(codec.mime, w, h),
            settings = settings,
        )
    }

    /** Monta o pedido para o serviço de renderização com o estado atual. */
    fun buildRequest(layout: HudLayout, useMapTiles: Boolean): RenderRequest? {
        val v = video ?: return null
        val t = track ?: return null
        val start = videoStartEpochMs ?: return null
        val plan = outputPlan() ?: return null
        val clips = RenderPlan.clips(v.chapters.map { it.durationMs }, plan.settings.trimStartMs, plan.settings.trimEndMs)
        if (clips.isEmpty()) return null
        return RenderRequest(
            chapters = v.chapters,
            clips = clips,
            timeline = t.timeline,
            outputStartEpochMs = start + plan.settings.trimStartMs,
            settings = plan.settings,
            layout = layout.onlyAvailable(t.timeline.availableWidgets()),
            useMapTiles = useMapTiles,
            outputWidth = plan.width,
            outputHeight = plan.height,
            videoBitrate = plan.videoBitrate,
            resize = plan.width < v.first.width || plan.height < v.first.height,
        )
    }

    suspend fun loadVideos(uris: List<Uri>) = load("Lendo vídeo…") {
        val byName = withContext(Dispatchers.IO) { uris.associateBy { VideoProbe.displayName(app, it) } }
        val groups = GoProChapters.group(byName.keys.toList())
        val chosen = groups.first()
        val chapters = withContext(Dispatchers.IO) { chosen.map { VideoProbe.probe(app, byName.getValue(it)) } }
        val bad = chapters.firstOrNull { it.durationMs <= 0 || it.width <= 0 }
        if (bad != null) throw IllegalArgumentException("Não consegui ler o vídeo ${bad.name}")
        uris.forEach { keepAccess(it) }
        val notice = if (groups.size > 1) "Foram escolhidas ${groups.size} gravações; usando só ${chosen.first()}." else null
        val first = chapters.first()
        replaceGrabber(FrameGrabber(app, chapters))
        video = VideoInput(chapters, VideoStartCandidates.from(first.info, first.name, zone), notice)
        resetSync()
    }

    suspend fun loadTrack(uri: Uri) = load("Lendo trilha…") {
        val name = VideoProbe.displayName(app, uri)
        val input = withContext(Dispatchers.Default) {
            val data = app.contentResolver.openInputStream(uri)?.use { ActivityReader.read(it) }
                ?: throw IllegalArgumentException("Não consegui abrir $name")
            TrackInput(name, data.format, RideTimeline.from(data.samples))
        }
        keepAccess(uri)
        track = input
        resetSync()
    }

    fun selectCandidate(i: Int) {
        candidateIndex = i.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        adjustMs = 0
    }

    fun nudge(deltaMs: Long) {
        adjustMs += deltaMs
    }

    /** O usuário leu [text] (hora local) num relógio que aparece no quadro atual da prévia. */
    fun applyVisibleClock(text: String): Boolean {
        val clock = SyncMath.parseClock(text) ?: return false
        val base = candidates.getOrNull(candidateIndex) ?: return false
        val start = SyncMath.startFromVisibleClock(clock, previewPositionMs, videoStartEpochMs ?: base.epochMs, zone)
        adjustMs = start - base.epochMs
        return true
    }

    /** Quadro da posição atual da prévia, sem HUD (fundo do editor de layout). */
    suspend fun rawFrame(maxWidth: Int): Bitmap? = previewMutex.withLock {
        val g = grabber ?: return null
        val pos = previewPositionMs
        withContext(Dispatchers.Default) { g.frameAt(pos, maxWidth) }
    }

    /** Snapshot da trilha no quadro atual da prévia. */
    fun previewSnapshot() = track?.timeline?.let { tl -> videoStartEpochMs?.let { tl.snapshotAt(it + previewPositionMs) } }

    /** Quadro da prévia com o HUD desenhado exatamente como sairá no vídeo final. */
    suspend fun renderPreview(layout: HudLayout, maxWidth: Int): Bitmap? = previewMutex.withLock {
        val g = grabber ?: return null
        val tl = track?.timeline ?: return null
        val start = videoStartEpochMs ?: return null
        val pos = previewPositionMs
        withContext(Dispatchers.Default) {
            val frame = g.frameAt(pos, maxWidth) ?: return@withContext null
            val out = if (frame.isMutable) frame else frame.copy(Bitmap.Config.ARGB_8888, true).also { frame.recycle() }
            val snapshot = tl.snapshotAt(start + pos)
            val visible = layout.onlyAvailable(tl.availableWidgets())
            renderer.draw(Canvas(out), out.width.toFloat(), out.height.toFloat(), snapshot, visible, waitForTilesMs = TILE_WAIT_MS)
            out
        }
    }

    private fun resetSync() {
        candidateIndex = 0
        adjustMs = 0
        previewPositionMs = 0
        clearTrim()
    }

    private fun replaceGrabber(g: FrameGrabber?) {
        grabber?.release()
        grabber = g
    }

    /** Mantém o acesso ao arquivo enquanto a renderização (outra fase) roda em segundo plano. */
    private fun keepAccess(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private suspend fun load(label: String, block: suspend () -> Unit) {
        busy = label
        error = null
        try {
            previewMutex.withLock { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            busy = null
        }
    }

    private companion object {
        const val TILE_WAIT_MS = 2_000L
        const val MIN_TRIM_MS = 1_000L
        const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
    }
}
