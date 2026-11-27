package com.bikecam.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikecam.AppGraph
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudRenderer
import com.bikecam.hud.HudWidget
import com.bikecam.hud.TileSource
import com.bikecam.recording.RecordingService
import com.bikecam.settings.AppSettings
import com.bikecam.settings.MountOrientation
import com.bikecam.telemetry.SpeedSource
import com.bikecam.telemetry.TelemetrySnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

private val Amber = Color(0xFFFFC107)

/**
 * Editor do HUD da gravação ao vivo (RF04): arraste um mostrador para movê-lo, toque para
 * selecioná-lo e use −/+ para mudar o tamanho. Cada orientação de montagem tem o seu layout. O
 * resultado aparece igual na tela, no vídeo e na foto carimbada, porque todos usam o mesmo HudRenderer.
 */
@Composable
fun HudEditorScreen(graph: AppGraph, service: RecordingService?, onBack: () -> Unit) {
    // Espera o DataStore: começar com AppSettings() padrão perderia o layout salvo.
    val loaded by produceState<AppSettings?>(null) { value = graph.settings.settings.first() }
    val s = loaded
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val scope = rememberCoroutineScope()
    val orientation = s.orientation
    val live by graph.telemetry.snapshot.collectAsStateWithLifecycle()
    val sample = remember(live) { withSampleValues(live) }
    LayoutEditor(
        title = "Editando layout ${orientation.label.lowercase()}",
        initial = s.hudLayout(orientation),
        defaultLayout = HudLayout.default(orientation),
        aspectRatio = if (orientation == MountOrientation.LANDSCAPE) 16f / 9f else 9f / 16f,
        tiles = graph.tiles,
        snapshot = sample,
        widgets = HudWidget.entries.filterNot { it.renderOnly },
        onSave = { layout -> scope.launch { graph.settings.setHudLayout(orientation, layout); onBack() } },
        onCancel = onBack,
        background = { if (service != null && !s.ecoMode) CameraPreview(service) },
    )
}

/**
 * Editor genérico de layout: fundo qualquer (câmera ao vivo ou quadro de vídeo), HUD desenhado por
 * cima com [snapshot] e só os [widgets] da lista podem ser ligados. [extraButtons] entra na coluna de
 * botões (ex.: exportar/importar) e recebe o layout atual e um jeito de trocá-lo.
 */
@Composable
fun LayoutEditor(
    title: String,
    initial: HudLayout,
    defaultLayout: HudLayout,
    aspectRatio: Float,
    tiles: TileSource?,
    snapshot: TelemetrySnapshot,
    widgets: List<HudWidget>,
    onSave: (HudLayout) -> Unit,
    onCancel: () -> Unit,
    background: @Composable BoxScope.() -> Unit,
    extraButtons: @Composable ColumnScope.(layout: HudLayout, setLayout: (HudLayout) -> Unit) -> Unit = { _, _ -> },
) {
    var layout by remember { mutableStateOf(initial) }
    var selected by remember { mutableStateOf<HudWidget?>(null) }
    var showList by remember { mutableStateOf(false) }
    val bounds = remember { HashMap<HudWidget, RectF>() }
    val renderer = remember(tiles) { HudRenderer(tiles) }
    // Só os mostradores da lista são desenhados/selecionáveis.
    val drawn = layout.onlyAvailable(widgets.toSet())

    fun hit(x: Float, y: Float): HudWidget? =
        drawn.placements.asReversed().firstOrNull { it.enabled && bounds[it.widget]?.contains(x, y) == true }?.widget

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .aspectRatio(aspectRatio)
                .background(Color(0xFF263238)),
        ) {
            background()
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { pos -> selected = hit(pos.x, pos.y) })
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos -> selected = hit(pos.x, pos.y) },
                        ) { change, drag ->
                            val w = selected ?: return@detectDragGestures
                            change.consume()
                            layout = layout.update(w) {
                                it.copy(
                                    x = (it.x + drag.x / size.width).coerceIn(0f, 1f),
                                    y = (it.y + drag.y / size.height).coerceIn(0f, 1f),
                                )
                            }
                        }
                    },
            ) {
                drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, snapshot, drawn, bounds) }
                selected?.let { bounds[it] }?.let { r ->
                    val m = 3.dp.toPx()
                    drawRect(
                        Amber,
                        topLeft = Offset(r.left - m, r.top - m),
                        size = Size(r.width() + 2 * m, r.height() + 2 * m),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SmallButton("Salvar", highlighted = true) { onSave(layout) }
            SmallButton("Cancelar", onClick = onCancel)
            SmallButton("Mostradores") { showList = true }
            SmallButton("Padrão") {
                layout = defaultLayout
                selected = null
            }
            extraButtons(layout) { layout = it; selected = null }
        }

        val sel = selected?.let { layout[it] }
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sel == null) {
                Text("$title · toque num mostrador para selecionar, arraste para mover", color = Color.White, fontSize = 12.sp)
            } else {
                Text(sel.widget.label, color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(String.format(Locale.US, "%.0f%%", sel.scale * 100), color = Color.White, fontSize = 12.sp)
                SmallButton("−") { layout = layout.update(sel.widget) { it.copy(scale = stepScale(it.scale, -SCALE_STEP)) } }
                SmallButton("+") { layout = layout.update(sel.widget) { it.copy(scale = stepScale(it.scale, SCALE_STEP)) } }
                SmallButton("Ocultar") {
                    layout = layout.update(sel.widget) { it.copy(enabled = false) }
                    selected = null
                }
            }
        }
    }

    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            confirmButton = { TextButton(onClick = { showList = false }) { Text("OK") } },
            title = { Text("Mostradores") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    layout.placements.filter { it.widget in widgets }.sortedBy { it.widget.ordinal }.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { layout = layout.update(p.widget) { it.copy(enabled = !it.enabled) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = p.enabled,
                                onCheckedChange = { on -> layout = layout.update(p.widget) { it.copy(enabled = on) } },
                            )
                            Text(p.widget.label)
                        }
                    }
                }
            },
        )
    }
}

private fun stepScale(current: Float, delta: Float): Float =
    (Math.round((current + delta) * 10f) / 10f).coerceIn(HudLayout.MIN_SCALE, HudLayout.MAX_SCALE)

/** Preenche o que estiver vazio com valores típicos, para o tamanho das caixas ficar realista. */
private fun withSampleValues(live: TelemetrySnapshot) = live.copy(
    speedMps = live.speedMps ?: 8.4f,
    speedSource = if (live.speedSource == SpeedSource.NONE) SpeedSource.GPS else live.speedSource,
    avgSpeedMps = live.avgSpeedMps ?: 6.9f,
    distanceM = if (live.distanceM > 0) live.distanceM else 23_450.0,
    heartRateBpm = live.heartRateBpm ?: 142,
    cadenceRpm = live.cadenceRpm ?: 88,
    altitudeM = live.altitudeM ?: 1_042.0,
    gradePercent = live.gradePercent ?: 4.5f,
    elevationGainM = live.elevationGainM ?: 312.0,
    hrConnected = true,
    cscConnected = true,
)

private const val SCALE_STEP = 0.1f
