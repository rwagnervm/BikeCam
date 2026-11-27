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

enum class Screen { RECORD, SENSORS, SETTINGS, HUD_EDITOR, RENDER, RENDER_LAYOUT }

private val Amber = Color(0xFFFFC107)

@Composable
fun BikeCamRoot(
    graph: AppGraph,
    service: RecordingService?,
    hardwareKeys: HardwareKeys,
    onSetBrightness: (Float?) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.RECORD) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, secondary = Amber)) {
        if (screen != Screen.RECORD) {
            BackHandler { screen = if (screen == Screen.RENDER_LAYOUT) Screen.RENDER else Screen.RECORD }
        }
        when (screen) {
            Screen.RECORD -> RecordScreen(
                graph = graph,
                service = service,
                hardwareKeys = hardwareKeys,
                onSetBrightness = onSetBrightness,
                onKeepScreenOn = onKeepScreenOn,
                onNavigate = { dest ->
                    screen = when (dest) {
                        MenuDestination.SENSORS -> Screen.SENSORS
                        MenuDestination.HUD_EDITOR -> Screen.HUD_EDITOR
                        MenuDestination.RENDER -> Screen.RENDER
                        MenuDestination.SETTINGS -> Screen.SETTINGS
                    }
                },
            )
            Screen.SENSORS -> SensorsScreen(graph = graph, onBack = { screen = Screen.RECORD })
            Screen.SETTINGS -> SettingsScreen(graph = graph, service = service, onBack = { screen = Screen.RECORD })
            Screen.HUD_EDITOR -> HudEditorScreen(graph = graph, service = service, onBack = { screen = Screen.RECORD })
            Screen.RENDER -> RenderScreen(
                graph = graph,
                onBack = { screen = Screen.RECORD },
                onEditLayout = { screen = Screen.RENDER_LAYOUT },
            )
            Screen.RENDER_LAYOUT -> RenderLayoutScreen(graph = graph, onBack = { screen = Screen.RENDER })
        }
    }
}
