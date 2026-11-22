package com.bikecam.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bikecam.BikeCamApp
import com.bikecam.MainActivity
import com.bikecam.Permissions
import com.bikecam.R
import com.bikecam.export.SessionRecorder
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudRenderer
import com.bikecam.settings.AppSettings
import com.bikecam.telemetry.TelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecorderState(
    val recording: Boolean = false,
    /** SystemClock.elapsedRealtime() do início do passeio, para o cronômetro na tela. */
    val startedAtMs: Long = 0L,
    /** Número do arquivo de vídeo atual (gravação segmentada), começando em 1. */
    val segment: Int = 0,
    val lastSaved: List<String> = emptyList(),
    val error: String? = null,
)

/** Aviso curto para a tela (ex.: foto salva). O [id] muda a cada aviso, mesmo com texto igual. */
data class Notice(val text: String, val isError: Boolean = false, val id: Long = SystemClock.elapsedRealtime())

/**
 * Um passeio gravado: um ou mais arquivos de vídeo (segmentos) e uma única sessão de telemetria
 * (GPX/FIT/JSONL) cobrindo todos eles.
 */
private class Ride(val baseName: String, val startedAtMs: Long, val segmentLimitMs: Long) {
    var segment = 0
    var stopRequested = false
    var session: SessionRecorder? = null
    val videos = mutableListOf<String>()
}

/**
 * Dono da câmera, dos sensores e da sessão. Fica vinculado (bound) enquanto a Activity está
 * visível e vira foreground service durante a gravação, para continuar gravando com a tela
 * escurecida ou desligada (RNF01).
 */
class RecordingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()
    private val graph by lazy { (application as BikeCamApp).graph }
    private lateinit var camera: CameraController
    private var ride: Ride? = null
    @Volatile private var session: SessionRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var layout: HudLayout = HudLayout.default(AppSettings().orientation)
    private var settings = AppSettings()

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    val capabilities get() = camera.capabilities

    override fun onCreate() {
        super.onCreate()
        camera = CameraController(this, this, graph.tiles)
        createChannel()
        startSensors()

        lifecycleScope.launch {
            graph.settings.settings.collect { s ->
                settings = s
                layout = s.hudLayout(s.orientation)
                graph.tiles.enabled = s.mapTiles
                graph.telemetry.simulate = s.simulate
                graph.heading.orientation = s.orientation
                graph.ble.wheelCircumferenceM = s.wheelCircumferenceMm / 1000f
                if (Permissions.bluetooth(this@RecordingService)) graph.ble.syncWith(s.pairedSensors)
                try {
                    camera.bind(s)
                } catch (e: Exception) {
                    Log.e(TAG, "falha ao configurar câmera", e)
                    _state.value = _state.value.copy(error = "Câmera: ${e.message}")
                }
            }
        }
        // Telemetria -> OSD + arquivo de sessão. Roda fora da main thread; o vídeo nunca espera por isso.
        lifecycleScope.launch(Dispatchers.Default) {
            graph.telemetry.snapshot.collect { snap ->
                camera.overlay.update(snap, layout)
                session?.onSnapshot(snap)
            }
        }
    }

    /** Chamado de novo quando a Activity volta, para pegar permissões concedidas depois. */
    fun startSensors() {
        graph.telemetry.start(graph.scope)
        if (Permissions.location(this)) graph.location.start()
        graph.heading.start()
        graph.elevation.start()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground()
        return START_NOT_STICKY
    }

    fun setSurfaceProvider(sp: Preview.SurfaceProvider?) = camera.setSurfaceProvider(sp)

    /** Deve ser chamado com a Activity visível (exigência do Android 11+ para câmera em FGS). */
    fun startRecording() {
        if (ride != null || camera.isRecording) return
        val baseName = "ride_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java))
        graph.telemetry.resetTrip()
        val r = Ride(baseName, SystemClock.elapsedRealtime(), settings.segmentMinutes * 60_000L)
        ride = r
        if (!startSegment(r)) {
            ride = null
            _state.value = _state.value.copy(error = "Câmera não está pronta")
            stopForegroundCompat()
        }
    }

    fun stopRecording() {
        ride?.stopRequested = true
        camera.stopRecording()
    }

    /** Abre o próximo arquivo de vídeo do passeio. O primeiro leva o nome base; os demais, _02, _03… */
    private fun startSegment(r: Ride): Boolean {
        r.segment++
        val name = if (r.segment == 1) r.baseName else String.format(Locale.US, "%s_%02d", r.baseName, r.segment)
        return camera.startRecording(name, settings.recordAudio, r.segmentLimitMs) { event -> onRecordEvent(r, name, event) }
    }

    private fun onRecordEvent(r: Ride, name: String, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                val now = System.currentTimeMillis()
                val s = r.session ?: SessionRecorder(this, r.baseName, now).also { r.session = it }
                s.startSegment(r.segment, now)
                session = s
                acquireWakeLock()
                _state.value = RecorderState(recording = true, startedAtMs = r.startedAtMs, segment = r.segment)
            }
            is VideoRecordEvent.Finalize -> {
                r.videos += "$name.mp4"
                val limitReached = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                // Fim de segmento: emenda o próximo arquivo na hora (a lacuna é de uma fração de segundo).
                if (limitReached && !r.stopRequested && ride === r) {
                    if (startSegment(r)) return
                    Log.e(TAG, "não foi possível abrir o segmento ${r.segment}")
                }
                val videoError = if (event.hasError() && !limitReached) "Erro no vídeo (${event.error})" else null
                finishRide(r, videoError)
            }
            else -> Unit
        }
    }

    private fun finishRide(r: Ride, videoError: String?) {
        if (ride === r) ride = null
        val s = r.session
        r.session = null
        session = null
        graph.scope.launch {
            val files = withContext(Dispatchers.IO) { s?.finish().orEmpty() }
            _state.value = RecorderState(recording = false, lastSaved = r.videos + files, error = videoError)
        }
        releaseWakeLock()
        stopForegroundCompat()
    }

    /** Foto durante o passeio, sem interromper o vídeo: limpa ou com o HUD carimbado. */
    fun takePhoto(stamped: Boolean) {
        val snap = graph.telemetry.snapshot.value
        val hud = layout
        val name = "bikecam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + if (stamped) "_hud" else ""
        val done = { ok: Boolean ->
            _notice.value = when {
                !ok -> Notice("Não foi possível salvar a foto", isError = true)
                stamped -> Notice("Foto com HUD salva")
                else -> Notice("Foto salva")
            }
        }
        if (stamped) {
            camera.takeStampedPhoto(name, { c, w, h -> HudRenderer(graph.tiles).draw(c, w.toFloat(), h.toFloat(), snap, hud, waitForTilesMs = STAMP_TILE_WAIT_MS) }, done)
        } else {
            camera.takeCleanPhoto(name, snap.toLocation(), done)
        }
    }

    private fun TelemetrySnapshot.toLocation(): Location? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        return Location("bikecam").apply {
            latitude = lat
            longitude = lon
            altitudeM?.let { altitude = it }
            time = if (epochMs > 0) epochMs else System.currentTimeMillis()
        }
    }

    private fun goForeground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (settings.recordAudio && Permissions.audio(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Permissions.location(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Permissions.bluetooth(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
        } catch (e: Exception) {
            Log.e(TAG, "não foi possível entrar em foreground", e)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf() // se a Activity ainda estiver vinculada, o serviço continua vivo
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notif_recording))
            .setContentText(getString(R.string.notif_recording_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BikeCam:recording")
            .apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        // Se o sistema matar o serviço no meio da gravação, fecha a sessão para não perder a trilha.
        ride?.session?.let { s -> Thread { s.finish() }.start() }
        ride = null
        session = null
        camera.release()
        releaseWakeLock()
        graph.telemetry.stop()
        graph.location.stop()
        graph.heading.stop()
        graph.elevation.stop()
        graph.ble.disconnectAll()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BikeCam.Service"
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 1
        /** A foto carimbada usa um zoom de mapa maior que o vídeo; espera até isso pelos tiles. */
        const val STAMP_TILE_WAIT_MS = 4_000L
    }
}
