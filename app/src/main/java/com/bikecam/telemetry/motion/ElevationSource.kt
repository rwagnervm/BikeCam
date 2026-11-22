package com.bikecam.telemetry.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Barômetro (altitude relativa) e acelerômetro (pitch do aparelho) para inclinação e ganho de
 * elevação. Só guarda os últimos valores filtrados; quem funde tudo é o ElevationEstimator.
 */
class ElevationSource(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val pressure: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val accelerometer: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val hasBarometer: Boolean get() = pressure != null

    @Volatile private var pressureHpa = 0f
    @Volatile private var pressureAtMs = 0L
    @Volatile private var tilt = 0.0
    @Volatile private var tiltAtMs = 0L

    // Passa-baixa do acelerômetro (vibração do guidão), na thread dos sensores.
    private var ax = 0f
    private var ay = 0f
    private var az = 0f
    private var accelInit = false
    private var running = false

    fun start() {
        if (running || sm == null) return
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        running = pressure != null || accelerometer != null
    }

    fun stop() {
        sm?.unregisterListener(this)
        running = false
        accelInit = false
    }

    /** Altitude barométrica (atmosfera padrão) se houver leitura recente. */
    fun baroAltitudeM(nowMs: Long = SystemClock.elapsedRealtime()): Double? {
        if (pressureAtMs == 0L || nowMs - pressureAtMs > STALE_MS) return null
        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa).toDouble()
    }

    /**
     * Componente "para frente" (direção da câmera, eixo -Z do aparelho) da força específica,
     * normalizada: ≈ seno do pitch. Vale para retrato e paisagem, porque os dois giram em torno de Z.
     */
    fun forwardTilt(nowMs: Long = SystemClock.elapsedRealtime()): Double? {
        if (tiltAtMs == 0L || nowMs - tiltAtMs > STALE_MS) return null
        return tilt
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                pressureHpa = event.values[0]
                pressureAtMs = SystemClock.elapsedRealtime()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                if (!accelInit) {
                    ax = x; ay = y; az = z; accelInit = true
                } else {
                    ax += ACCEL_ALPHA * (x - ax)
                    ay += ACCEL_ALPHA * (y - ay)
                    az += ACCEL_ALPHA * (z - az)
                }
                val norm = sqrt(ax * ax + ay * ay + az * az)
                if (norm > 1f) {
                    tilt = (-az / norm).toDouble()
                    tiltAtMs = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val STALE_MS = 3_000L
        const val ACCEL_ALPHA = 0.1f
    }
}
