package com.jemis.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID

/**
 * Wraps a single RFCOMM connection to a classic-Bluetooth (SPP) peer, acting either as the
 * client ([connect]) or the server ([startListening]). All I/O runs on a background thread;
 * [Callback] methods are delivered on the main thread.
 */
class BluetoothClient(private val callback: Callback) {

    interface Callback {
        fun onConnecting()
        fun onListening()
        fun onConnected()
        fun onConnectFailed(error: Exception)
        fun onMessageReceived(message: String)
        fun onDisconnected()
    }

    companion object {
        // Standard Serial Port Profile UUID, used by most classic-Bluetooth serial devices.
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SPP_NAME = "AndroidBlueTooth"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var connectThread: Thread? = null
    private var readThread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var listening = false

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        mainHandler.post { callback.onConnecting() }

        connectThread = Thread {
            var newSocket: BluetoothSocket? = null
            try {
                newSocket = openSocket(device, fallback = false)
                newSocket.connect()
            } catch (primary: IOException) {
                closeQuietly(newSocket)
                // Many OEM stacks reject the very first connect() right after createBond().
                // Retry once with the reflection-based fallback socket (fixed RFCOMM channel 1).
                try {
                    newSocket = openSocket(device, fallback = true)
                    newSocket.connect()
                } catch (fallback: IOException) {
                    mainHandler.post { callback.onConnectFailed(fallback) }
                    closeQuietly(newSocket)
                    return@Thread
                }
            }

            socket = newSocket
            running = true
            mainHandler.post { callback.onConnected() }
            startReadLoop(newSocket)
        }.also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice, fallback: Boolean): BluetoothSocket =
        if (!fallback) {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } else {
            // device.createRfcommSocket(int) is @hide but reachable via reflection; it bypasses
            // SDP lookup and opens a fixed channel, which works around the "socket might closed
            // or timeout" failure some Android builds hit right after pairing.
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }

    /**
     * Opens an SPP server socket and blocks in the background waiting for an incoming
     * connection (e.g. from another phone running this same app). Call [stopListening] to
     * cancel while waiting.
     */
    @SuppressLint("MissingPermission")
    fun startListening(adapter: BluetoothAdapter) {
        disconnect()
        listening = true
        mainHandler.post { callback.onListening() }

        connectThread = Thread {
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord(SPP_NAME, SPP_UUID)
                serverSocket = server
                val newSocket = server.accept()
                closeServerSocket()

                if (!listening) {
                    // stopListening() was called while we were blocked in accept().
                    try {
                        newSocket.close()
                    } catch (e: IOException) {
                        // already closed, nothing to do
                    }
                    return@Thread
                }

                listening = false
                socket = newSocket
                running = true
                mainHandler.post { callback.onConnected() }
                startReadLoop(newSocket)
            } catch (e: IOException) {
                closeServerSocket()
                if (listening) {
                    listening = false
                    mainHandler.post { callback.onConnectFailed(e) }
                }
            }
        }.also { it.start() }
    }

    fun stopListening() {
        listening = false
        closeServerSocket()
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // already closed, nothing to do
        }
        serverSocket = null
    }

    fun send(message: String) {
        val outputStream = socket?.outputStream
        if (outputStream == null) {
            mainHandler.post { callback.onConnectFailed(IOException("尚未建立连接，无法发送")) }
            return
        }
        Thread {
            try {
                outputStream.write(message.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            } catch (e: IOException) {
                mainHandler.post { callback.onConnectFailed(e) }
                disconnect()
            }
        }.start()
    }

    fun disconnect() {
        stopListening()
        running = false
        connectThread?.interrupt()
        readThread?.interrupt()
        closeQuietly()
    }

    private fun startReadLoop(activeSocket: BluetoothSocket) {
        readThread = Thread {
            val buffer = ByteArray(1024)
            val inputStream = activeSocket.inputStream

            while (running) {
                val length = try {
                    inputStream.read(buffer)
                } catch (e: IOException) {
                    -1
                }

                if (length <= 0) {
                    break
                }

                val message = String(buffer, 0, length, Charsets.UTF_8)
                mainHandler.post { callback.onMessageReceived(message) }
            }

            if (running) {
                running = false
                mainHandler.post { callback.onDisconnected() }
            }
            closeQuietly()
        }.also { it.start() }
    }

    private fun closeQuietly(target: BluetoothSocket? = socket) {
        try {
            target?.close()
        } catch (e: IOException) {
            // socket already closed, nothing to do
        }
        if (target === socket) {
            socket = null
        }
    }
}
