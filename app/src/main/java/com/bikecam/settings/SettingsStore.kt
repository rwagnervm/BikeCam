package com.bikecam.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bikecam.hud.HudLayout
import com.bikecam.telemetry.ble.SensorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class VideoQuality(val label: String) { SD("480p"), HD("720p"), FHD("1080p"), UHD("4K") }

/** Como o celular está preso no guidão. */
enum class MountOrientation(val label: String) { PORTRAIT("Retrato"), LANDSCAPE("Paisagem") }

data class PairedSensor(val address: String, val name: String, val type: SensorType) {
    fun encode() = "${type.name}|$address|$name"

    companion object {
        fun decode(s: String): PairedSensor? {
            val parts = s.split('|', limit = 3)
            if (parts.size != 3) return null
            val type = runCatching { SensorType.valueOf(parts[0]) }.getOrNull() ?: return null
            return PairedSensor(parts[1], parts[2], type)
        }
    }
}

data class AppSettings(
    val quality: VideoQuality = VideoQuality.FHD,
    val fps: Int = 30,
    val orientation: MountOrientation = MountOrientation.LANDSCAPE,
    val wheelCircumferenceMm: Int = 2105,
    val recordAudio: Boolean = false,
    val simulate: Boolean = false,
    val dimWhileRecording: Boolean = true,
    val pairedSensors: Set<PairedSensor> = emptySet(),
    /** Modo economia: sem imagem da câmera na tela, só números grandes em fundo preto. */
    val ecoMode: Boolean = false,
    /** Fundo de mapa do OpenStreetMap no mini-mapa (usa internet; sem ele, só a trilha). */
    val mapTiles: Boolean = true,
    /** Divide o vídeo em arquivos de N minutos (0 = um arquivo só). */
    val segmentMinutes: Int = 0,
    private val hudLandscape: String? = null,
    private val hudPortrait: String? = null,
) {
    /** Layout do HUD salvo para a orientação (ou o padrão). */
    fun hudLayout(o: MountOrientation = orientation): HudLayout =
        HudLayout.decode(if (o == MountOrientation.LANDSCAPE) hudLandscape else hudPortrait, o)

    companion object {
        val SEGMENT_OPTIONS = listOf(0, 5, 10, 15, 30)
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val QUALITY = stringPreferencesKey("quality")
        val FPS = intPreferencesKey("fps")
        val ORIENTATION = stringPreferencesKey("orientation")
        val WHEEL = intPreferencesKey("wheel_mm")
        val AUDIO = booleanPreferencesKey("audio")
        val SIMULATE = booleanPreferencesKey("simulate")
        val DIM = booleanPreferencesKey("dim")
        val SENSORS = stringSetPreferencesKey("sensors")
        val ECO = booleanPreferencesKey("eco")
        val MAP_TILES = booleanPreferencesKey("map_tiles")
        val SEGMENT_MIN = intPreferencesKey("segment_min")
        val HUD_LANDSCAPE = stringPreferencesKey("hud_landscape")
        val HUD_PORTRAIT = stringPreferencesKey("hud_portrait")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            quality = this[K.QUALITY]?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() } ?: d.quality,
            fps = this[K.FPS] ?: d.fps,
            orientation = this[K.ORIENTATION]?.let { runCatching { MountOrientation.valueOf(it) }.getOrNull() } ?: d.orientation,
            wheelCircumferenceMm = this[K.WHEEL] ?: d.wheelCircumferenceMm,
            recordAudio = this[K.AUDIO] ?: d.recordAudio,
            simulate = this[K.SIMULATE] ?: d.simulate,
            dimWhileRecording = this[K.DIM] ?: d.dimWhileRecording,
            pairedSensors = this[K.SENSORS].orEmpty().mapNotNull(PairedSensor::decode).toSet(),
            ecoMode = this[K.ECO] ?: d.ecoMode,
            mapTiles = this[K.MAP_TILES] ?: d.mapTiles,
            segmentMinutes = this[K.SEGMENT_MIN] ?: d.segmentMinutes,
            hudLandscape = this[K.HUD_LANDSCAPE],
            hudPortrait = this[K.HUD_PORTRAIT],
        )
    }

    suspend fun setQuality(q: VideoQuality) = context.dataStore.edit { it[K.QUALITY] = q.name }
    suspend fun setFps(fps: Int) = context.dataStore.edit { it[K.FPS] = fps }
    suspend fun setOrientation(o: MountOrientation) = context.dataStore.edit { it[K.ORIENTATION] = o.name }
    suspend fun setWheelCircumference(mm: Int) = context.dataStore.edit { it[K.WHEEL] = mm.coerceIn(1000, 3000) }
    suspend fun setRecordAudio(on: Boolean) = context.dataStore.edit { it[K.AUDIO] = on }
    suspend fun setSimulate(on: Boolean) = context.dataStore.edit { it[K.SIMULATE] = on }
    suspend fun setDimWhileRecording(on: Boolean) = context.dataStore.edit { it[K.DIM] = on }
    suspend fun setEcoMode(on: Boolean) = context.dataStore.edit { it[K.ECO] = on }
    suspend fun setMapTiles(on: Boolean) = context.dataStore.edit { it[K.MAP_TILES] = on }
    suspend fun setSegmentMinutes(min: Int) = context.dataStore.edit { it[K.SEGMENT_MIN] = min.coerceIn(0, 120) }

    /** null volta ao layout padrão da orientação. */
    suspend fun setHudLayout(o: MountOrientation, layout: HudLayout?) = context.dataStore.edit {
        val key = if (o == MountOrientation.LANDSCAPE) K.HUD_LANDSCAPE else K.HUD_PORTRAIT
        if (layout == null) it.remove(key) else it[key] = layout.encode()
    }

    suspend fun addSensor(s: PairedSensor) = context.dataStore.edit { p ->
        val current = p[K.SENSORS].orEmpty().mapNotNull(PairedSensor::decode).filterNot { it.address == s.address }
        p[K.SENSORS] = (current + s).map { it.encode() }.toSet()
    }

    suspend fun removeSensor(address: String) = context.dataStore.edit { p ->
        p[K.SENSORS] = p[K.SENSORS].orEmpty().mapNotNull(PairedSensor::decode).filterNot { it.address == address }
            .map { it.encode() }.toSet()
    }
}
