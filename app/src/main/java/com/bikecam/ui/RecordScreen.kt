package com.bikecam.ui

import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikecam.AppGraph
import com.bikecam.hud.HudLayout
import com.bikecam.recording.CameraCapabilities
import com.bikecam.recording.Notice
import com.bikecam.recording.RecorderState
import com.bikecam.recording.RecordingService
import com.bikecam.settings.AppSettings
import com.bikecam.settings.MountOrientation
import com.bikecam.telemetry.TelemetrySnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF66BB6A)
private val Grey = Color(0xFF9E9E9E)
private val Red = Color(0xFFE53935)
private val Amber = Color(0xFFFFC107)
private val EcoText = Color(0xFFE0E0E0)

@Composable
fun RecordScreen(
    graph: AppGraph,
    service: RecordingService?,
    onSetBrightness: (Float?) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onOpenSensors: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHudEditor: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val recorder by (service?.state ?: remember { MutableStateFlow(RecorderState()) }).collectAsStateWithLifecycle()
    val caps by (service?.capabilities ?: remember { MutableStateFlow(CameraCapabilities()) }).collectAsStateWithLifecycle()
    val landscape = settings.orientation == MountOrientation.LANDSCAPE
    val layout = remember(settings) { settings.hudLayout() }
    val eco = settings.ecoMode

    // Escurece a tela durante a gravação; qualquer toque devolve o brilho por alguns segundos.
    var lastTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recorder.recording, settings.dimWhileRecording, lastTouch) {
        onKeepScreenOn(recorder.recording)
        onSetBrightness(null)
        if (recorder.recording && settings.dimWhileRecording) {
            delay(DIM_AFTER_MS)
            onSetBrightness(0.01f)
        }
    }
    // Modo economia: a câmera continua alimentando o vídeo, mas nada é exibido na tela.
    LaunchedEffect(eco, service) { if (eco) service?.setSurfaceProvider(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.elapsedRealtime()
                    }
                }
            },
    ) {
        if (eco) {
            EcoDisplay(graph, landscape, Modifier.align(Alignment.Center))
        } else {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .aspectRatio(if (landscape) 16f / 9f else 9f / 16f),
            ) {
                if (service != null) {
                    CameraPreview(service)
                } else {
                    Text(
                        "Conceda a permissão de câmera para começar",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                LiveHud(graph, layout)
            }
        }

        Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LiveStatusRow(graph)
            NoticeBanner(service)
        }

        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecordButton(recorder, enabled = service != null) {
                if (recorder.recording) service?.stopRecording() else service?.startRecording()
            }
            SmallButton(if (eco) "ECO ●" else "ECO", highlighted = eco) { scope.launch { graph.settings.setEcoMode(!eco) } }
            if (!recorder.recording) {
                SmallButton("Sensores", onClick = onOpenSensors)
                SmallButton("HUD", onClick = onOpenHudEditor)
                SmallButton("Ajustes", onClick = onOpenSettings)
            }
        }

        if (service != null && caps.photos) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PhotoButton("FOTO") { service.takePhoto(stamped = false) }
                PhotoButton("FOTO\n+HUD") { service.takePhoto(stamped = true) }
            }
        }

        val message = recorder.error ?: recorder.lastSaved.takeIf { it.isNotEmpty() }?.let { "Salvo: " + it.joinToString(", ") }
        if (message != null && !recorder.recording) {
            Text(
                message,
                color = if (recorder.error != null) Red else Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun CameraPreview(service: RecordingService, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        update = { view -> service.setSurfaceProvider(view.surfaceProvider) },
    )
}

@Composable
private fun LiveHud(graph: AppGraph, layout: HudLayout) {
    val snapshot by graph.telemetry.snapshot.collectAsStateWithLifecycle()
    HudOverlay(snapshot, layout, graph.tiles, Modifier.fillMaxSize())
}

@Composable
private fun LiveStatusRow(graph: AppGraph) {
    val snapshot by graph.telemetry.snapshot.collectAsStateWithLifecycle()
    StatusRow(snapshot)
}

@Composable
private fun StatusRow(s: TelemetrySnapshot) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("GPS", s.gpsFix)
        Chip("FC", s.hrConnected)
        Chip("CSC", s.cscConnected)
    }
}

/** Aviso que some sozinho depois de alguns segundos (foto salva etc.). */
@Composable
private fun NoticeBanner(service: RecordingService?) {
    val notice by (service?.notice ?: remember { MutableStateFlow<Notice?>(null) }).collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf<Notice?>(null) }
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        if (SystemClock.elapsedRealtime() - n.id > NOTICE_MS) return@LaunchedEffect // aviso antigo ao voltar para a tela
        visible = n
        delay(NOTICE_MS)
        visible = null
    }
    val n = visible ?: return
    Text(
        n.text,
        color = if (n.isError) Red else Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(top = 8.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Chip(label: String, ok: Boolean) {
    Text(
        label,
        color = if (ok) Green else Grey,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(Color(0x99000000), RoundedCornerShape(10.dp))
            .border(1.dp, if (ok) Green else Grey, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Modo economia: fundo preto (pixels apagados em telas OLED) e só os números essenciais.
 * A gravação continua normal, com o OSD completo no vídeo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EcoDisplay(graph: AppGraph, landscape: Boolean, modifier: Modifier) {
    val s by graph.telemetry.snapshot.collectAsStateWithLifecycle()
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        modifier.padding(horizontal = 96.dp).widthIn(max = 640.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                s.speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                color = EcoText,
                fontSize = if (landscape) 150.sp else 96.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("km/h", color = Grey, fontSize = 20.sp, modifier = Modifier.padding(start = 6.dp, bottom = 24.dp))
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val big = if (landscape) 40.sp else 32.sp
            EcoValue("DISTÂNCIA", String.format(Locale.US, "%.2f", s.distanceM / 1000.0), "km", big)
            EcoValue("FC", s.heartRateBpm?.toString() ?: "--", "bpm", big)
            EcoValue("CADÊNCIA", s.cadenceRpm?.toString() ?: "--", "rpm", big)
            EcoValue("INCLINAÇÃO", s.gradePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "--", "%", big)
            EcoValue("MÉDIA", s.avgSpeedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "--", "km/h", big)
            EcoValue("HORA", clock.format(Date(if (s.epochMs > 0) s.epochMs else System.currentTimeMillis())), "", big)
        }
    }
}

@Composable
private fun EcoValue(label: String, value: String, unit: String, size: TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Amber.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = EcoText, fontSize = size, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(unit, color = Grey, fontSize = 13.sp, modifier = Modifier.padding(start = 3.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun RecordButton(state: RecorderState, enabled: Boolean, onClick: () -> Unit) {
    val elapsed by produceState(0L, state.recording, state.startedAtMs) {
        while (state.recording) {
            value = (SystemClock.elapsedRealtime() - state.startedAtMs) / 1000
            delay(500)
        }
        value = 0
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.recording) {
            Text(
                String.format(Locale.US, "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60),
                color = Red, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
            if (state.segment > 1) Text("parte ${state.segment}", color = Grey, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(if (state.recording) 28.dp else 54.dp)
                    .clip(if (state.recording) RoundedCornerShape(4.dp) else CircleShape)
                    .background(if (enabled) Red else Grey),
            )
        }
    }
}

@Composable
private fun PhotoButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xAA000000))
            .border(3.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 13.sp)
    }
}

@Composable
fun SmallButton(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (highlighted) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) Amber else Color(0xAA000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private const val DIM_AFTER_MS = 5_000L
private const val NOTICE_MS = 2_500L
