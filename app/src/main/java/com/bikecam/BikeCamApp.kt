package com.bikecam

import android.app.Application
import com.bikecam.hud.OsmTileCache
import com.bikecam.render.RenderJobs
import com.bikecam.render.RenderSession
import com.bikecam.settings.SettingsStore
import com.bikecam.telemetry.TelemetryRepository
import com.bikecam.telemetry.ble.BleSensorManager
import com.bikecam.telemetry.gps.LocationSource
import com.bikecam.telemetry.motion.ElevationSource
import com.bikecam.telemetry.motion.HeadingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Injeção de dependências manual: um único grafo por processo. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app)
    val location = LocationSource(app)
    val heading = HeadingSource(app)
    val elevation = ElevationSource(app)
    val ble = BleSensorManager(app, scope)
    val telemetry = TelemetryRepository(location, heading, ble, elevation)
    val tiles = OsmTileCache(app)
    val render = RenderSession(app, tiles)
    val renderJobs = RenderJobs()
}

class BikeCamApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
