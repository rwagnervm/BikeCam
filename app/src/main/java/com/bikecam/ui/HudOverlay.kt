package com.bikecam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudRenderer
import com.bikecam.hud.TileSource
import com.bikecam.telemetry.TelemetrySnapshot

/** HUD na tela, desenhado pelo mesmo [HudRenderer] que queima o OSD no vídeo. */
@Composable
fun HudOverlay(snapshot: TelemetrySnapshot, layout: HudLayout, tiles: TileSource?, modifier: Modifier = Modifier) {
    val renderer = remember(tiles) { HudRenderer(tiles) }
    Canvas(modifier) {
        drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, snapshot, layout) }
    }
}
