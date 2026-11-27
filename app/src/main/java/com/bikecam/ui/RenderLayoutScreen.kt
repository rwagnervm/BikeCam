package com.bikecam.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.bikecam.AppGraph
import com.bikecam.hud.HudLayout
import com.bikecam.hud.HudWidget
import com.bikecam.settings.AppSettings
import com.bikecam.settings.MountOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor do layout da renderização: o fundo é o quadro atual da prévia e os números vêm da trilha
 * naquele instante. Só os mostradores com dado na trilha aparecem. Exporta/importa JSON.
 */
@Composable
fun RenderLayoutScreen(graph: AppGraph, onBack: () -> Unit) {
    val session = graph.render
    val video = session.video
    val track = session.track
    val loaded by produceState<AppSettings?>(null) { value = graph.settings.settings.first() }
    val settings = loaded
    if (video == null || track == null || settings == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val orientation = if (video.portrait) MountOrientation.PORTRAIT else MountOrientation.LANDSCAPE
    val snapshot = remember { session.previewSnapshot() } ?: return
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) { frame = runCatching { session.rawFrame(FRAME_WIDTH) }.getOrNull() }

    // Exportar/importar: o editor passa o layout atual e um jeito de trocá-lo.
    var pendingExport by remember { mutableStateOf<HudLayout?>(null) }
    var applyImported by remember { mutableStateOf<((HudLayout) -> Unit)?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val layout = pendingExport
        if (uri != null && layout != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)!!.use { it.write(layout.toJson(orientation).toByteArray()) }
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "Layout exportado" else "Não consegui salvar o arquivo", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val apply = applyImported
        if (uri != null && apply != null) {
            scope.launch {
                val layout = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                        HudLayout.fromJson(text, HudLayout.defaultRender(orientation))
                    }.getOrNull()
                }
                if (layout != null) apply(layout)
                Toast.makeText(context, if (layout != null) "Layout importado" else "Arquivo não é um layout do BikeCam", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LayoutEditor(
        title = "Layout do vídeo (${orientation.label.lowercase()})",
        initial = settings.renderLayout(orientation),
        defaultLayout = HudLayout.defaultRender(orientation),
        aspectRatio = video.first.width.toFloat() / video.first.height,
        tiles = if (settings.mapTiles) graph.tiles else null,
        snapshot = snapshot,
        widgets = HudWidget.entries.filter { it in track.timeline.availableWidgets() },
        onSave = { layout -> scope.launch { graph.settings.setRenderLayout(orientation, layout); onBack() } },
        onCancel = onBack,
        background = {
            frame?.let {
                Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        },
        extraButtons = { layout, setLayout ->
            SmallButton("Exportar") {
                pendingExport = layout
                exportLauncher.launch("bikecam-layout.json")
            }
            SmallButton("Importar") {
                applyImported = setLayout
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        },
    )
}

private const val FRAME_WIDTH = 1920
