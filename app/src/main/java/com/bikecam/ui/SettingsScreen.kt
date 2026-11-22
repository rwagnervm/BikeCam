package com.bikecam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikecam.AppGraph
import com.bikecam.recording.CameraCapabilities
import com.bikecam.recording.RecordingService
import com.bikecam.settings.AppSettings
import com.bikecam.settings.MountOrientation
import com.bikecam.settings.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val WHEEL_PRESETS = listOf(
    "700x25c" to 2105, "700x28c" to 2136, "700x32c" to 2155, "700x38c" to 2180,
    "29x2.1" to 2288, "27.5x2.1" to 2148, "26x1.95" to 2050,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(graph: AppGraph, service: RecordingService?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val caps by (service?.capabilities ?: remember { MutableStateFlow(CameraCapabilities()) }).collectAsStateWithLifecycle()
    val st = graph.settings

    PageScaffold("Ajustes", onBack) {
        SectionTitle("Montagem no guidão")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MountOrientation.entries.forEach { o ->
                FilterChip(selected = s.orientation == o, onClick = { scope.launch { st.setOrientation(o) } }, label = { Text(o.label) })
            }
        }

        SectionTitle("Resolução do vídeo")
        val qualities = caps.qualities.ifEmpty { VideoQuality.entries }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            qualities.forEach { q ->
                FilterChip(selected = s.quality == q, onClick = { scope.launch { st.setQuality(q) } }, label = { Text(q.label) })
            }
        }

        SectionTitle("Quadros por segundo")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            caps.frameRates.forEach { f ->
                FilterChip(selected = s.fps == f, onClick = { scope.launch { st.setFps(f) } }, label = { Text("$f fps") })
            }
        }
        if (caps.stabilization) Text("Estabilização de vídeo ativada.", style = MaterialTheme.typography.bodySmall)
        if (!caps.photos) {
            Text(
                "Este aparelho não grava vídeo e tira foto ao mesmo tempo nesta resolução/fps; os botões de foto ficam ocultos.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Codec: H.264. O CameraX não permite escolher H.265 para vídeo SDR com o OSD; reduza a resolução para economizar espaço.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Circunferência da roda (sensor de velocidade)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WHEEL_PRESETS.forEach { (label, mm) ->
                FilterChip(
                    selected = s.wheelCircumferenceMm == mm,
                    onClick = { scope.launch { st.setWheelCircumference(mm) } },
                    label = { Text(label) },
                )
            }
        }
        var wheelText by remember { mutableStateOf(s.wheelCircumferenceMm.toString()) }
        LaunchedEffect(s.wheelCircumferenceMm) { wheelText = s.wheelCircumferenceMm.toString() }
        OutlinedTextField(
            value = wheelText,
            onValueChange = { v ->
                wheelText = v.filter(Char::isDigit).take(4)
                wheelText.toIntOrNull()?.takeIf { it in 1000..3000 }?.let { mm -> scope.launch { st.setWheelCircumference(mm) } }
            },
            label = { Text("mm") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp).padding(top = 8.dp),
        )

        SectionTitle("Dividir o vídeo em partes")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.SEGMENT_OPTIONS.forEach { min ->
                FilterChip(
                    selected = s.segmentMinutes == min,
                    onClick = { scope.launch { st.setSegmentMinutes(min) } },
                    label = { Text(if (min == 0) "Arquivo único" else "$min min") },
                )
            }
        }
        Text(
            "Em partes, uma falha no meio de um passeio longo só afeta o arquivo atual. Entre as partes fica uma lacuna de uma fração de segundo.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Mini-mapa")
        SwitchRow("Fundo de mapa OpenStreetMap", s.mapTiles) { scope.launch { st.setMapTiles(it) } }
        Text(
            "Baixa os mapas da região pela internet e guarda em cache. Sem sinal, o mini-mapa mostra só a trilha.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Gravação")
        SwitchRow("Gravar áudio", s.recordAudio) { scope.launch { st.setRecordAudio(it) } }
        SwitchRow("Modo economia (tela preta, só números)", s.ecoMode) { scope.launch { st.setEcoMode(it) } }
        SwitchRow("Escurecer a tela durante a gravação", s.dimWhileRecording) { scope.launch { st.setDimWhileRecording(it) } }
        SwitchRow("Dados simulados (teste sem sensores)", s.simulate) { scope.launch { st.setSimulate(it) } }

        Text(
            "Vídeos em Movies/BikeCam; fotos em Pictures/BikeCam; trilhas GPX/FIT/JSONL em Documents/BikeCam.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
