package com.bikecam.telemetry.motion

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.bikecam.settings.MountOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bússola a partir do vetor de rotação. Considera o celular "em pé" no guidão (câmera traseira
 * olhando para a frente), em retrato ou paisagem, e devolve o rumo da direção da câmera.
 */
class HeadingSource(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    /** Rumo magnético corrigido pela declinação (quando há posição), graus 0..360. */
    val heading: StateFlow<Float?> = _heading.asStateFlow()

    @Volatile var orientation: MountOrientation = MountOrientation.LANDSCAPE
    @Volatile private var declination = 0f

    private val r = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)
    private var sinAvg = 0.0
    private var cosAvg = 1.0
    private var initialized = false

    val available: Boolean get() = sensor != null

    private var running = false

    fun start() {
        if (running) return
        sensor ?: return
        running = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sm?.unregisterListener(this)
        running = false
        initialized = false
    }

    fun updateDeclination(lat: Double, lon: Double, altM: Double) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), altM.toFloat(), System.currentTimeMillis()).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // Câmera aponta para -Z do aparelho. Remapeia para que o "azimute" seja o da câmera.
        when (orientation) {
            MountOrientation.PORTRAIT -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            MountOrientation.LANDSCAPE -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
        }
        SensorManager.getOrientation(remapped, angles)
        val az = angles[0].toDouble()
        // Filtro passa-baixa circular (seno/cosseno) para não pular entre 359° e 0°.
        if (!initialized) {
            sinAvg = sin(az); cosAvg = cos(az); initialized = true
        } else {
            sinAvg += ALPHA * (sin(az) - sinAvg)
            cosAvg += ALPHA * (cos(az) - cosAvg)
        }
        val deg = Math.toDegrees(atan2(sinAvg, cosAvg)).toFloat() + declination
        _heading.value = ((deg % 360f) + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val ALPHA = 0.15
    }
}
