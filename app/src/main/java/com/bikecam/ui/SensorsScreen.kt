package com.bikecam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikecam.AppGraph
import com.bikecam.Permissions
import com.bikecam.settings.AppSettings
import com.bikecam.settings.PairedSensor
import com.bikecam.telemetry.ble.LinkState
import kotlinx.coroutines.launch

@Composable
fun SensorsScreen(graph: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val statuses by graph.ble.statuses.collectAsStateWithLifecycle()
    val results by graph.ble.scanResults.collectAsStateWithLifecycle()
    val scanning by graph.ble.scanning.collectAsStateWithLifecycle()
    val hasPerm = Permissions.bluetooth(ctx)

    DisposableEffect(Unit) { onDispose { graph.ble.stopScan() } }

    PageScaffold("Sensores BLE", onBack) {
        if (!hasPerm) {
            Text("Permissão de Bluetooth não concedida. Abra as configurações do app para liberar.")
            return@PageScaffold
        }

        SectionTitle("Pareados")
        if (settings.pairedSensors.isEmpty()) Text("Nenhum sensor. Procure abaixo.", style = MaterialTheme.typography.bodyMedium)
        settings.pairedSensors.sortedBy { it.name }.forEach { s ->
            val state = statuses.firstOrNull { it.address == s.address }?.state ?: LinkState.DISCONNECTED
            SensorRow(s.name, "${s.type.label} · ${stateLabel(state)}") {
                TextButton(onClick = { scope.launch { graph.settings.removeSensor(s.address) } }) { Text("Remover") }
            }
        }

        SectionTitle("Encontrados")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { graph.ble.startScan() }, enabled = !scanning) { Text("Procurar sensores") }
            if (scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        val paired = settings.pairedSensors.map { it.address }.toSet()
        results.filterNot { it.address in paired }.forEach { c ->
            SensorRow(c.name, "${c.type.label} · ${c.rssi} dBm") {
                OutlinedButton(onClick = {
                    scope.launch { graph.settings.addSensor(PairedSensor(c.address, c.name, c.type)) }
                }) { Text("Adicionar") }
            }
        }
        Text(
            "Dica: sensores separados de velocidade e de cadência (ambos 0x1816) podem ser adicionados juntos.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

private fun stateLabel(s: LinkState) = when (s) {
    LinkState.CONNECTED -> "conectado"
    LinkState.CONNECTING -> "conectando…"
    LinkState.RECONNECTING -> "reconectando…"
    LinkState.DISCONNECTED -> "desconectado"
}

@Composable
private fun SensorRow(title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        action()
    }
}
