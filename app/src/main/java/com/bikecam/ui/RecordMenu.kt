package com.bikecam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikecam.AppGraph
import com.bikecam.recording.CameraCapabilities
import com.bikecam.settings.AppSettings
import com.bikecam.settings.Stabilization
import kotlinx.coroutines.launch

/** Destinos do menu da tela de gravação. */
enum class MenuDestination { SENSORS, HUD_EDITOR, RENDER, SETTINGS }

/** Botão "sanduíche" (☰) que abre o menu. */
@Composable
fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xAA000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.5.dp.toPx()
            for (i in 0..2) {
                val y = size.height * (0.2f + 0.3f * i)
                drawLine(Color.White, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            }
        }
    }
}

/**
 * Conteúdo do menu lateral: cada destino com uma descrição e o estado atual, e alguns atalhos
 * que se mudam com frequência antes de sair para pedalar.
 */
@Composable
fun RecordMenu(
    graph: AppGraph,
    settings: AppSettings,
    caps: CameraCapabilities,
    onNavigate: (MenuDestination) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val st = graph.settings
    val snap by graph.telemetry.snapshot.collectAsStateWithLifecycle()

    ModalDrawerSheet(Modifier.widthIn(max = 360.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("BikeCam", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text(cameraSummary(settings, caps), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            val paired = settings.pairedSensors.size
            MenuItem(
                "Sensores",
                buildString {
                    append(if (paired == 0) "Nenhum sensor pareado" else "$paired pareado(s)")
                    append(" · FC ").append(if (snap.hrConnected) "conectado" else "—")
                    append(" · Vel./cad. ").append(if (snap.cscConnected) "conectado" else "—")
                    append(" · GPS ").append(if (snap.gpsFix) "ok" else "sem sinal")
                },
            ) { onNavigate(MenuDestination.SENSORS) }
            MenuItem("Editar HUD", "Quais widgets aparecem no vídeo gravado, posição e tamanho") {
                onNavigate(MenuDestination.HUD_EDITOR)
            }
            MenuItem("Vídeo externo com HUD", "Aplica o HUD de uma trilha GPX/TCX/FIT sobre o vídeo de outra câmera (GoPro…)") {
                onNavigate(MenuDestination.RENDER)
            }
            MenuItem("Ajustes", "Resolução, fps, estabilização, fotos, roda, divisão do vídeo, mapa") {
                onNavigate(MenuDestination.SETTINGS)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Atalhos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SwitchRow("Gravar áudio", settings.recordAudio) { scope.launch { st.setRecordAudio(it) } }
                SwitchRow("Botões de volume", settings.volumeKeys) { scope.launch { st.setVolumeKeys(it) } }
                if (settings.volumeKeys) {
                    Text(
                        "Vol+ inicia/para a gravação · Vol− tira foto " + if (settings.volumeDownStamped) "com HUD" else "limpa",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SwitchRow("Vibração e som de confirmação", settings.actionFeedback) { scope.launch { st.setActionFeedback(it) } }
                SwitchRow("Escurecer a tela ao gravar", settings.dimWhileRecording) { scope.launch { st.setDimWhileRecording(it) } }
            }
        }
    }
}

@Composable
private fun MenuItem(title: String, subtitle: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

private fun cameraSummary(s: AppSettings, caps: CameraCapabilities): String {
    val video = caps.videoSize?.let { "${it.width}×${it.height}" } ?: s.quality.label
    val stab = if (caps.activeStabilization == Stabilization.OFF) "sem estabilização" else "estabilização: " + caps.activeStabilization.label
    return "$video · ${s.fps} fps · ${s.orientation.label} · $stab"
}
