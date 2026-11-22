package com.bikecam.telemetry.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager

/**
 * Conexão GATT de um único sensor (FC ou CSC), baseada na Nordic Android BLE Library.
 * Só habilita notificações da característica de medição e repassa os bytes crus.
 */
internal class SensorGattManager(
    context: Context,
    private val type: SensorType,
    private val onMeasurement: (ByteArray) -> Unit,
) : BleManager(context) {

    private var measurement: BluetoothGattCharacteristic? = null

    private val serviceUuid get() = if (type == SensorType.HEART_RATE) BleUuids.HEART_RATE_SERVICE else BleUuids.CSC_SERVICE
    private val charUuid get() = if (type == SensorType.HEART_RATE) BleUuids.HEART_RATE_MEASUREMENT else BleUuids.CSC_MEASUREMENT

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val c = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) return false
        measurement = c
        return true
    }

    override fun initialize() {
        setNotificationCallback(measurement).with { _, data ->
            data.value?.let(onMeasurement)
        }
        enableNotifications(measurement).enqueue()
    }

    override fun onServicesInvalidated() {
        measurement = null
    }

    override fun getMinLogPriority(): Int = Log.WARN

    override fun log(priority: Int, message: String) {
        Log.println(priority, "BikeCam.BLE", message)
    }
}
