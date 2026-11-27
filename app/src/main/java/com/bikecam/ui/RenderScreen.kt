package com.bikecam.ui

import com.bikecam.render.audio.ModelDownload
import com.bikecam.render.audio.MossFormerModel
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikecam.AppGraph
import com.bikecam.render.RenderSession
import com.bikecam.render.OutputCodec
import com.bikecam.render.OutputResolution
import com.bikecam.render.RenderService
import com.bikecam.render.RenderState
import com.bikecam.render.SyncMath
import com.bikecam.render.TrackInput
import com.bikecam.render.VideoInput
import com.bikecam.settings.AppSettings
import com.bikecam.settings.MountOrientation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val Amber = Color(0xFFFFC107)
private val Red = Color(0xFFFF5252)
private val Muted = Color(0xFFB0BEC5)

/**
 * “Vídeo com HUD”: escolher a gravação (vídeo de outra câmera) e a trilha do ciclocomputador,
 * sincronizar as duas com prévia do quadro e, na próxima fase, renderizar.
 */
@Composable
fun RenderScreen(graph: AppGraph, onBack: () -> Unit, onEditLayout: () -> Unit) {
    val session = graph.render
    val scope = rememberCoroutineScope()
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { session.loadVideos(uris) }
    }
    val pickTrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { session.loadTrack(uri) }
    }

    PageScaffold("Vídeo com HUD", onBack) {
        val busy = session.busy
        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.height(20.dp).aspectRatio(1f), strokeWidth = 2.dp)
                Text(busy, Modifier.padding(start = 8.dp))
            }
        }
        session.error?.let { Text(it, color = Red, modifier = Modifier.padding(vertical = 6.dp)) }

        SectionTitle("1. Vídeo")
        OutlinedButton(onClick = { pickVideos.launch(arrayOf("video/*")) }, enabled = busy == null) {
            Text(if (session.video == null) "Escolher vídeo…" else "Trocar vídeo…")
        }
        Text(
            "Para gravações da GoPro divididas em partes (GX01…, GX02…), escolha todas as partes juntas.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
        session.video?.let { VideoSummary(it) }

        SectionTitle("2. Trilha do ciclocomputador")
        OutlinedButton(onClick = { pickTrack.launch(arrayOf("*/*")) }, enabled = busy == null) {
            Text(if (session.track == null) "Escolher trilha (FIT, TCX ou GPX)…" else "Trocar trilha…")
        }
        session.track?.let { TrackSummary(it, session.zone) }

        if (session.ready) {
            SectionTitle("3. Sincronização")
            SyncSection(graph, session, onEditLayout)
        }

        if (session.ready) {
            SectionTitle("4. Renderizar")
            RenderSection(graph, session)
        }
    }
}

@Composable
private fun VideoSummary(v: VideoInput) {
    Column(Modifier.padding(top = 8.dp)) {
        val first = v.first
        Text(v.chapters.joinToString(" + ") { it.name }, fontSize = 14.sp)
        Text(
            "%d×%d · %s · %s".format(first.width, first.height, formatDuration(v.durationMs), codecName(first.mime)) +
                if (v.chapters.size > 1) " · ${v.chapters.size} partes" else "",
            color = Muted, fontSize = 13.sp,
        )
        v.notice?.let { Text(it, color = Amber, fontSize = 13.sp) }
    }
}

@Composable
private fun TrackSummary(t: TrackInput, zone: ZoneId) {
    val tl = t.timeline
    val fields = buildList {
        if (tl.hasPosition) add("posição")
        if (tl.hasAltitude) add("altitude")
        if (tl.hasHeartRate) add("FC")
        if (tl.hasCadence) add("cadência")
        if (tl.hasPower) add("potência")
        if (tl.hasTemperature) add("temperatura")
    }
    Column(Modifier.padding(top = 8.dp)) {
        Text(t.name, fontSize = 14.sp)
        Text(
            "%s · %s → %s · %.2f km".format(
                t.format.name, formatDateTime(tl.startEpochMs, zone), formatClock(tl.endEpochMs, zone, tenths = false),
                tl.totalDistanceM / 1000,
            ),
            color = Muted, fontSize = 13.sp,
        )
        Text("Dados: " + fields.joinToString(", "), color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SyncSection(graph: AppGraph, session: RenderSession, onEditLayout: () -> Unit) {
    val video = session.video ?: return
    val track = session.track ?: return
    val tl = track.timeline
    val zone = session.zone
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val layout = settings.renderLayout(if (video.portrait) MountOrientation.PORTRAIT else MountOrientation.LANDSCAPE)
    val start = session.videoStartEpochMs ?: return

    Text("Hora do primeiro quadro do vídeo:", fontSize = 14.sp)
    session.candidates.forEachIndexed { i, c ->
        val inside = c.epochMs in tl
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { session.selectCandidate(i) },
        ) {
            RadioButton(selected = i == session.candidateIndex, onClick = { session.selectCandidate(i) })
            Column {
                Text(formatClock(c.epochMs, zone), fontSize = 15.sp)
                Text(
                    c.description + if (inside) "" else " · fora da trilha",
                    color = if (inside) Muted else Red, fontSize = 12.sp,
                )
            }
        }
    }

    Text("Ajuste fino", fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, ms) in listOf("−10s" to -10_000L, "−1s" to -1_000L, "−0,1s" to -100L)) {
            NudgeButton(label) { session.nudge(ms) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, ms) in listOf("+0,1s" to 100L, "+1s" to 1_000L, "+10s" to 10_000L)) {
            NudgeButton(label) { session.nudge(ms) }
        }
    }
    Text(
        "Ajuste %s → início às %s".format(formatSignedSeconds(session.adjustMs), formatClock(start, zone)),
        color = Muted, fontSize = 13.sp,
    )
    val coverage = SyncMath.coverage(start, video.durationMs, tl.startEpochMs, tl.endEpochMs)
    when (coverage) {
        SyncMath.Coverage.FULL -> Unit
        SyncMath.Coverage.PARTIAL -> Text("Parte do vídeo fica fora da trilha (o HUD fica sem dados nesse trecho).", color = Amber, fontSize = 13.sp)
        SyncMath.Coverage.NONE -> Text("O vídeo inteiro fica fora da trilha: confira a hora de início.", color = Red, fontSize = 13.sp)
    }

    SpeedChart(track, start, video.durationMs, start + session.previewPositionMs)

    // Prévia: quadro + HUD, refeita (com um pequeno atraso) a cada mudança de posição ou de sincronia.
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewError by remember { mutableStateOf(false) }
    LaunchedEffect(video, track, start, session.previewPositionMs, layout) {
        delay(150)
        val bmp = runCatching { session.renderPreview(layout, PREVIEW_WIDTH) }.getOrNull()
        previewError = bmp == null
        if (bmp != null) preview = bmp
    }
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .aspectRatio(video.first.width.toFloat() / video.first.height)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = preview
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), contentDescription = "Prévia do quadro com o HUD",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
        } else {
            Text(if (previewError) "Não consegui extrair o quadro" else "Gerando prévia…", color = Muted)
        }
    }
    Slider(
        value = session.previewPositionMs.toFloat(),
        onValueChange = { session.previewPositionMs = it.toLong() },
        valueRange = 0f..video.durationMs.toFloat().coerceAtLeast(1f),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((label, ms) in listOf("−1 quadro" to -FRAME_MS, "+1 quadro" to FRAME_MS)) {
            NudgeButton(label) {
                session.previewPositionMs = (session.previewPositionMs + ms).coerceIn(0L, video.durationMs)
            }
        }
        Text(
            "%s / %s · %s".format(
                formatDuration(session.previewPositionMs), formatDuration(video.durationMs),
                formatClock(start + session.previewPositionMs, zone),
            ),
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp),
        )
    }

    OutlinedButton(onClick = onEditLayout, modifier = Modifier.padding(top = 6.dp)) { Text("Editar layout do vídeo…") }

    // Atalho: um relógio (ou a tela do ciclocomputador) aparece no quadro da prévia.
    var clockText by remember { mutableStateOf("") }
    var clockError by remember { mutableStateOf(false) }
    Text(
        "Se aparecer um relógio ou a tela do ciclocomputador no quadro acima, digite a hora que ele mostra:",
        fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = clockText,
            onValueChange = { clockText = it; clockError = false },
            label = { Text("HH:MM:SS") },
            singleLine = true,
            isError = clockError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.widthIn(max = 180.dp),
        )
        TextButton(onClick = { clockError = !session.applyVisibleClock(clockText) }) { Text("Aplicar") }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Text(label, fontSize = 13.sp)
    }
}

/** Velocidade da trilha inteira, com o trecho do vídeo destacado e a posição da prévia. */
@Composable
private fun SpeedChart(track: TrackInput, videoStart: Long, videoDurationMs: Long, cursor: Long) {
    val tl = track.timeline
    val profile = track.speedProfile
    val max = (profile.maxOrNull() ?: 0f).coerceAtLeast(10f)
    Text("Velocidade na trilha (faixa = vídeo)", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(Color(0xFF1E272C)),
    ) {
        val span = (tl.endEpochMs - tl.startEpochMs).toFloat().coerceAtLeast(1f)
        fun x(t: Long) = (t - tl.startEpochMs) / span * size.width
        val x0 = x(videoStart).coerceIn(0f, size.width)
        val x1 = x(videoStart + videoDurationMs).coerceIn(0f, size.width)
        drawRect(Amber.copy(alpha = 0.25f), topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(2f), size.height))
        val path = Path()
        profile.forEachIndexed { i, v ->
            val px = i / (profile.size - 1f) * size.width
            val py = size.height - v / max * size.height * 0.95f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, Color(0xFF80CBC4), style = Stroke(width = 1.5f))
        val cx = x(cursor)
        if (cx in 0f..size.width) drawLine(Red, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
    }
}

private const val PREVIEW_WIDTH = 1280
private const val FRAME_MS = 33L

private fun formatClock(epochMs: Long, zone: ZoneId, tenths: Boolean = true): String {
    val f = if (tenths) "HH:mm:ss.S" else "HH:mm:ss"
    return DateTimeFormatter.ofPattern(f, Locale.US).format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

private fun formatDateTime(epochMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.US).format(Instant.ofEpochMilli(epochMs).atZone(zone))

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun formatSignedSeconds(ms: Long): String =
    (if (ms < 0) "−" else "+") + String.format(Locale.forLanguageTag("pt-BR"), "%.1f s", abs(ms) / 1000.0)

private fun codecName(mime: String?): String = when {
    mime == null -> "?"
    "hevc" in mime -> "HEVC"
    "avc" in mime -> "H.264"
    else -> mime.substringAfter('/')
}

/** Recorte, opções de saída, estimativas e o botão de renderizar (ou o progresso, se já começou). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun RenderSection(graph: AppGraph, session: RenderSession) {
    val context = LocalContext.current
    val video = session.video ?: return
    val state by graph.renderJobs.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val running = state.running

    val start = session.trimStartMs
    val end = session.effectiveTrimEndMs
    Text(
        "Trecho: %s – %s (%s)".format(formatDuration(start), formatDuration(end), formatDuration(end - start)),
        fontSize = 14.sp,
    )
    Text("Use a barra da prévia para escolher a posição.", color = Muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NudgeButton("Início aqui") { if (!running) session.markTrimStart() }
        NudgeButton("Fim aqui") { if (!running) session.markTrimEnd() }
        TextButton(onClick = { if (!running) session.clearTrim() }) { Text("Vídeo inteiro") }
    }

    Text("Resolução", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (r in OutputResolution.entries) {
            RadioButton(selected = session.resolution == r, onClick = { if (!running) session.resolution = r })
            Text(r.label, Modifier.padding(end = 12.dp))
        }
    }
    Text("Codec", fontSize = 14.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (c in OutputCodec.entries) {
            RadioButton(selected = session.codec == c, onClick = { if (!running) session.codec = c })
            Text(c.label, Modifier.padding(end = 12.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = session.includeAudio, onCheckedChange = { if (!running) session.includeAudio = it })
        Text("Incluir o áudio do vídeo")
    }
    if (session.includeAudio) {
        val context = LocalContext.current
        val download by ModelDownload.state.collectAsStateWithLifecycle()
        val modelReady = remember(download) { MossFormerModel.isAvailable(context) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
            Checkbox(
                checked = session.removeVoices,
                enabled = modelReady,
                onCheckedChange = { if (!running) session.removeVoices = it },
            )
            Column {
                Text("Remover vozes")
                Text("Tira as conversas e mantém o som ambiente.", color = Muted, fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
            Checkbox(
                checked = session.removeWind,
                enabled = modelReady,
                onCheckedChange = { if (!running) session.removeWind = it },
            )
            Column {
                Text("Remover ruído do vento")
                Text(
                    if (session.removeVoices) "Corta também o ronco grave do vento." else "Realça as vozes e abaixa o vento e o som ambiente.",
                    color = Muted, fontSize = 12.sp,
                )
            }
        }
        if (modelReady) {
            Text(
                "A limpeza usa o modelo MossFormer2 e roda antes do vídeo: leva mais ou menos a duração do trecho.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp),
            )
        } else {
            ModelDownloadRow(download) { ModelDownload.start(context) }
        }
    }

    val plan = session.outputPlan() ?: return
    Text(
        "Saída: %d×%d · %s · %.1f Mbps · ~%s".format(
            plan.width, plan.height, session.codec.label, plan.videoBitrate / 1e6, formatBytes(plan.estimatedBytes),
        ),
        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
    )
    if (!plan.canDecode) {
        Text(
            "Este aparelho pode não conseguir decodificar vídeo ${video.first.width}×${video.first.height} " +
                "(${codecName(video.first.mime)}).",
            color = Red, fontSize = 13.sp,
        )
    }
    if (!plan.canEncode) {
        Text(
            "O codificador ${session.codec.label} não aceita ${plan.width}×${plan.height}; o vídeo sairá numa resolução menor.",
            color = Amber, fontSize = 13.sp,
        )
    }
    if (!plan.enoughSpace) {
        Text(
            "Espaço livre insuficiente: precisa de ~${formatBytes(plan.estimatedBytes * 2)}, há ${formatBytes(plan.freeBytes)}.",
            color = Red, fontSize = 13.sp,
        )
    }

    when (val s = state) {
        is RenderState.Preparing -> Progress("Preparando o mapa do trecho…", s.fraction) { RenderService.cancel(context) }
        is RenderState.CleaningAudio -> Progress(
            (if (s.fraction == null) "Carregando o modelo de áudio…" else "Limpando o áudio… %d%%".format((s.fraction * 100).toInt())) +
                " · " + formatDuration(s.elapsedMs),
            s.fraction,
        ) { RenderService.cancel(context) }
        is RenderState.Encoding -> Progress(
            "Renderizando… " + (s.fraction?.let { "%d%%".format((it * 100).toInt()) } ?: "") + " · " + formatDuration(s.elapsedMs),
            s.fraction,
        ) { RenderService.cancel(context) }
        RenderState.Saving -> Progress("Salvando em Movies/BikeCam…", null) { RenderService.cancel(context) }
        else -> {
            Button(
                onClick = {
                    val layout = settings.renderLayout(if (video.portrait) MountOrientation.PORTRAIT else MountOrientation.LANDSCAPE)
                    val request = session.buildRequest(layout, settings.mapTiles)
                    if (request != null) {
                        graph.renderJobs.pending = request
                        RenderService.start(context)
                    }
                },
                enabled = plan.canDecode && plan.enoughSpace && plan.settings.durationMs > 0,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Renderizar vídeo") }
            when (s) {
                is RenderState.Done -> {
                    Text("Pronto em ${formatDuration(s.elapsedMs)}: ${s.displayPath}", color = Color(0xFF81C784), fontSize = 13.sp)
                    s.uri?.let { uri ->
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Abrir vídeo") }
                    }
                }
                is RenderState.Failed -> Text("Falhou: ${s.message}", color = Red, fontSize = 13.sp)
                RenderState.Cancelled -> Text("Cancelado.", color = Muted, fontSize = 13.sp)
                else -> Unit
            }
        }
    }
}

@Composable
private fun Progress(label: String, fraction: Float?, onCancel: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, fontSize = 14.sp)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        Text("Pode sair desta tela; o progresso também aparece na notificação.", color = Muted, fontSize = 12.sp)
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.forLanguageTag("pt-BR"), "%.1f GB", b / (1024.0 * 1024 * 1024))
    else -> String.format(Locale.forLanguageTag("pt-BR"), "%.0f MB", b / (1024.0 * 1024))
}

/** Modelo de áudio ausente: botão para baixar (~120 MB) e o progresso. */
@Composable
private fun ModelDownloadRow(state: ModelDownload.State, onStart: () -> Unit) {
    Column(Modifier.padding(start = 24.dp, top = 4.dp)) {
        when (state) {
            is ModelDownload.State.Running -> {
                Text(
                    "Baixando o modelo de áudio… ${formatBytes(state.bytes)} de ${formatBytes(state.total)}",
                    color = Muted, fontSize = 12.sp,
                )
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(vertical = 4.dp))
                TextButton(onClick = { ModelDownload.cancel() }) { Text("Cancelar download") }
            }
            else -> {
                Text(
                    "A limpeza usa o modelo MossFormer2, baixado uma vez do repositório do BikeCam no GitHub " +
                        "(${formatBytes(ModelDownload.SIZE)}; prefira o Wi-Fi).",
                    color = Muted, fontSize = 12.sp,
                )
                if (state is ModelDownload.State.Failed) {
                    Text("Falhou: ${state.message}", color = Red, fontSize = 12.sp)
                }
                OutlinedButton(onClick = onStart, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (state is ModelDownload.State.Failed) "Tentar de novo" else "Baixar modelo de áudio")
                }
            }
        }
    }
}
