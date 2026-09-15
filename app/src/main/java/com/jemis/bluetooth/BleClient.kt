package com.jemis.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Drives a single BLE central-role session: scan -> connectGatt -> discoverServices ->
 * pick a writable/notifiable characteristic -> read/write/notify. [Callback] methods are
 * always delivered on the main thread.
 */
class BleClient(private val callback: Callback) {

    interface Callback {
        fun onScanStarted()
        fun onScanStopped()
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
        fun onConnecting()
        fun onConnected()
        fun onServicesDiscovered(services: List<android.bluetooth.BluetoothGattService>)
        fun onReady(writable: Boolean, notifiable: Boolean)
        fun onMessageReceived(message: String)
        fun onConnectFailed(error: Exception)
        fun onDisconnected()
    }

    companion object {
        // Client Characteristic Configuration Descriptor, standard across all BLE devices.
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    @Volatile
    private var scanning = false

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            mainHandler.post { callback.onDeviceFound(result.device, result.rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.post {
                callback.onScanStopped()
                callback.onConnectFailed(Exception("扫描失败，错误码 $errorCode"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(adapter: BluetoothAdapter) {
        stopScan()
        scanner = adapter.bluetoothLeScanner
        scanning = true
        callback.onScanStarted()
        scanner?.startScan(scanCallback)

        // Never scan forever: stop automatically after a few seconds.
        mainHandler.postDelayed({ if (scanning) stopScan() }, 8000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (scanning) {
            scanning = false
            scanner?.stopScan(scanCallback)
            callback.onScanStopped()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        disconnect()
        callback.onConnecting()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        writeCharacteristic = null
        notifyCharacteristic = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    /** Writes [text] (UTF-8) to the characteristic picked in [onReady]. Returns false if not ready. */
    @SuppressLint("MissingPermission")
    fun send(text: String): Boolean {
        val activeGatt = gatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        val value = text.toByteArray(Charsets.UTF_8)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            activeGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(characteristic)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.post { callback.onConnected() }
                    activeGatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    mainHandler.post {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            callback.onConnectFailed(Exception("GATT 错误码 $status"))
                        }
                        callback.onDisconnected()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { callback.onConnectFailed(Exception("发现服务失败，状态码 $status")) }
                return
            }

            mainHandler.post { callback.onServicesDiscovered(activeGatt.services) }

            // Pick the first characteristic that supports write, and the first that supports
            // notify/indicate. Good enough for the common single-service UART-style BLE device;
            // a real integration would target the vendor-documented UUIDs instead.
            var write: BluetoothGattCharacteristic? = null
            var notify: BluetoothGattCharacteristic? = null
            for (service in activeGatt.services) {
                for (characteristic in service.characteristics) {
                    val props = characteristic.properties
                    if (write == null &&
                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                            props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    ) {
                        write = characteristic
                    }
                    if (notify == null &&
                        (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    ) {
                        notify = characteristic
                    }
                }
            }

            writeCharacteristic = write
            notifyCharacteristic = notify

            if (notify != null) {
                enableNotifications(activeGatt, notify)
            }

            mainHandler.post { callback.onReady(write != null, notify != null) }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(activeGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            activeGatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeDescriptor(descriptor, value)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                activeGatt.writeDescriptor(descriptor)
            }
        }

        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val message = String(value, Charsets.UTF_8)
            mainHandler.post { callback.onMessageReceived(message) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Only reached on API < 33, where the ByteArray overload above doesn't exist.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val message = String(characteristic.value ?: return, Charsets.UTF_8)
            mainHandler.post { callback.onMessageReceived(message) }
        }
    }
}
