package com.bikecam.telemetry.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.bikecam.settings.PairedSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

data class SensorStatus(val address: String, val name: String, val type: SensorType, val state: LinkState)

data class ScanCandidate(val address: String, val name: String, val type: SensorType, val rssi: Int)

/** Valores agregados de todos os sensores BLE, prontos para a fusão de telemetria. */
data class BleReadings(
    val heartRateBpm: Int? = null,
    val hrUpdatedAtMs: Long = 0L,
    val speedMps: Float? = null,
    val speedUpdatedAtMs: Long = 0L,
    val cadenceRpm: Int? = null,
    val cadenceUpdatedAtMs: Long = 0L,
    val hrConnected: Boolean = false,
    val cscConnected: Boolean = false,
)

/**
 * Gerencia scan e conexões simultâneas a vários sensores (FC 0x180D e CSC 0x1816, inclusive
 * sensores separados de velocidade e de cadência). Cada sensor reconecta sozinho com backoff
 * quando a conexão cai, sem afetar os demais nem a gravação.
 */
@SuppressLint("MissingPermission") // o chamador garante BLUETOOTH_CONNECT/SCAN (ou localização em <= Android 11)
class BleSensorManager(private val context: Context, private val scope: CoroutineScope) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val connections = ConcurrentHashMap<String, SensorConnection>()

    private val _statuses = MutableStateFlow<List<SensorStatus>>(emptyList())
    val statuses: StateFlow<List<SensorStatus>> = _statuses.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScanCandidate>>(emptyList())
    val scanResults: StateFlow<List<ScanCandidate>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    @Volatile var wheelCircumferenceM: Float = 2.105f

    // ---------------- Scan ----------------

    private var scanJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
            val type = when {
                BleUuids.HEART_RATE_SERVICE in uuids -> SensorType.HEART_RATE
                BleUuids.CSC_SERVICE in uuids -> SensorType.CSC
                else -> return
            }
            val name = result.scanRecord?.deviceName ?: result.device.name ?: result.device.address
            val cand = ScanCandidate(result.device.address, name, type, result.rssi)
            _scanResults.update { list -> (list.filterNot { it.address == cand.address } + cand).sortedByDescending { it.rssi } }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan falhou: $errorCode")
            _scanning.value = false
        }
    }

    fun startScan(durationMs: Long = 15_000L) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stopScan()
        _scanResults.value = emptyList()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.HEART_RATE_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.CSC_SERVICE)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            _scanning.value = true
            scanJob = scope.launch { delay(durationMs); stopScan() }
        } catch (e: SecurityException) {
            Log.w(TAG, "sem permissão de scan", e)
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_scanning.value) {
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        }
        _scanning.value = false
    }

    // ---------------- Conexões ----------------

    /** Conecta todos os sensores da lista e desconecta os que saíram dela. */
    fun syncWith(paired: Set<PairedSensor>) {
        val wanted = paired.associateBy { it.address }
        connections.keys.filterNot { it in wanted }.forEach { disconnect(it) }
        wanted.values.forEach { if (!connections.containsKey(it.address)) connect(it) }
    }

    fun connect(sensor: PairedSensor) {
        val device = try { adapter?.getRemoteDevice(sensor.address) } catch (_: IllegalArgumentException) { null } ?: return
        val conn = SensorConnection(sensor, device)
        connections[sensor.address] = conn
        publishStatuses()
        conn.connect()
    }

    fun disconnect(address: String) {
        connections.remove(address)?.close()
        publishStatuses()
    }

    fun disconnectAll() {
        stopScan()
        connections.keys.toList().forEach { disconnect(it) }
    }

    /** Leitura agregada; chamada pela fusão de telemetria a 5 Hz (qualquer thread). */
    fun readings(nowMs: Long = SystemClock.elapsedRealtime()): BleReadings {
        var r = BleReadings()
        for (c in connections.values) {
            val ready = c.state == LinkState.CONNECTED
            when (c.sensor.type) {
                SensorType.HEART_RATE -> {
                    if (ready) r = r.copy(hrConnected = true)
                    val v = c.hr
                    if (v != null && c.hrUpdatedAtMs >= r.hrUpdatedAtMs) {
                        r = r.copy(heartRateBpm = v.first, hrUpdatedAtMs = c.hrUpdatedAtMs)
                    }
                }
                SensorType.CSC -> {
                    if (ready) r = r.copy(cscConnected = true)
                    val v = c.csc
                    // Sensor conectado, mas que parou de notificar com a bike parada: continua sendo a
                    // fonte de verdade (velocidade 0), por isso o "updatedAt" vira "agora".
                    if (v.speedMps != null) {
                        val stopped = nowMs - v.lastWheelMoveMs > STOP_TIMEOUT_MS
                        val at = if (ready) nowMs else v.speedUpdatedAtMs
                        if (at >= r.speedUpdatedAtMs) r = r.copy(speedMps = if (stopped) 0f else v.speedMps, speedUpdatedAtMs = at)
                    }
                    if (v.cadenceRpm != null) {
                        val stopped = nowMs - v.lastCrankMoveMs > STOP_TIMEOUT_MS
                        val at = if (ready) nowMs else v.cadenceUpdatedAtMs
                        if (at >= r.cadenceUpdatedAtMs) r = r.copy(cadenceRpm = if (stopped) 0 else v.cadenceRpm, cadenceUpdatedAtMs = at)
                    }
                }
            }
        }
        return r
    }

    private fun publishStatuses() {
        _statuses.value = connections.values.map { SensorStatus(it.sensor.address, it.sensor.name, it.sensor.type, it.state) }
            .sortedBy { it.name }
    }

    /** Estado imutável de um sensor CSC, publicado a cada notificação. */
    private data class CscState(
        val speedMps: Float? = null,
        val speedUpdatedAtMs: Long = 0L,
        val lastWheelMoveMs: Long = 0L,
        val cadenceRpm: Int? = null,
        val cadenceUpdatedAtMs: Long = 0L,
        val lastCrankMoveMs: Long = 0L,
    )

    private inner class SensorConnection(val sensor: PairedSensor, val device: BluetoothDevice) {
        @Volatile var state = LinkState.DISCONNECTED
        @Volatile var hr: Pair<Int, Long>? = null
        @Volatile var hrUpdatedAtMs = 0L
        @Volatile var csc = CscState()
        private val calculator = CscCalculator(STOP_TIMEOUT_MS)
        private var wanted = true
        private var backoffMs = INITIAL_BACKOFF_MS
        private var reconnectJob: Job? = null

        private val gatt = SensorGattManager(context, sensor.type) { data -> onData(data) }.apply {
            setConnectionObserver(object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) = updateState(if (state == LinkState.RECONNECTING) LinkState.RECONNECTING else LinkState.CONNECTING)
                override fun onDeviceConnected(device: BluetoothDevice) {}
                override fun onDeviceReady(device: BluetoothDevice) {
                    backoffMs = INITIAL_BACKOFF_MS
                    updateState(LinkState.CONNECTED)
                }
                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = scheduleReconnect()
                override fun onDeviceDisconnecting(device: BluetoothDevice) {}
                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = scheduleReconnect()
            })
        }

        private fun updateState(s: LinkState) {
            state = s
            publishStatuses()
        }

        fun connect() {
            if (!wanted) return
            gatt.connect(device)
                .useAutoConnect(false)
                .retry(2, 300)
                .timeout(15_000)
                .fail { _, status -> Log.d(TAG, "${sensor.name}: falha ao conectar ($status)") }
                .enqueue()
        }

        private fun scheduleReconnect() {
            if (!wanted) { updateState(LinkState.DISCONNECTED); return }
            updateState(LinkState.RECONNECTING)
            reconnectJob?.cancel()
            val wait = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            reconnectJob = scope.launch {
                delay(wait)
                connect()
            }
        }

        private fun onData(data: ByteArray) {
            val now = SystemClock.elapsedRealtime()
            when (sensor.type) {
                SensorType.HEART_RATE -> HeartRateParser.parse(data)?.let {
                    hr = it to now
                    hrUpdatedAtMs = now
                }
                SensorType.CSC -> CscParser.parse(data)?.let { m ->
                    calculator.update(m, now, wheelCircumferenceM)
                    val old = csc
                    csc = CscState(
                        speedMps = calculator.speedMps,
                        speedUpdatedAtMs = if (m.wheelRevs != null) now else old.speedUpdatedAtMs,
                        lastWheelMoveMs = calculator.lastWheelMoveMs,
                        cadenceRpm = calculator.cadenceRpm,
                        cadenceUpdatedAtMs = if (m.crankRevs != null) now else old.cadenceUpdatedAtMs,
                        lastCrankMoveMs = calculator.lastCrankMoveMs,
                    )
                }
            }
        }

        fun close() {
            wanted = false
            reconnectJob?.cancel()
            gatt.disconnect().enqueue()
            gatt.close()
            state = LinkState.DISCONNECTED
        }
    }

    companion object {
        private const val TAG = "BikeCam.BLE"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 10_000L
        const val STOP_TIMEOUT_MS = 3_000L
    }
}
