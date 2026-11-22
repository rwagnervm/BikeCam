package com.bikecam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.bikecam.AppGraph
import com.bikecam.recording.RecordingService

enum class Screen { RECORD, SENSORS, SETTINGS, HUD_EDITOR }

private val Amber = Color(0xFFFFC107)

@Composable
fun BikeCamRoot(
    graph: AppGraph,
    service: RecordingService?,
    onSetBrightness: (Float?) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.RECORD) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, secondary = Amber)) {
        if (screen != Screen.RECORD) BackHandler { screen = Screen.RECORD }
        when (screen) {
            Screen.RECORD -> RecordScreen(
                graph = graph,
                service = service,
                onSetBrightness = onSetBrightness,
                onKeepScreenOn = onKeepScreenOn,
                onOpenSensors = { screen = Screen.SENSORS },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenHudEditor = { screen = Screen.HUD_EDITOR },
            )
            Screen.SENSORS -> SensorsScreen(graph = graph, onBack = { screen = Screen.RECORD })
            Screen.SETTINGS -> SettingsScreen(graph = graph, service = service, onBack = { screen = Screen.RECORD })
            Screen.HUD_EDITOR -> HudEditorScreen(graph = graph, service = service, onBack = { screen = Screen.RECORD })
        }
    }
}
