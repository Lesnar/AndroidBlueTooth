package com.jemis.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener

class MainActivity : AppCompatActivity(), BluetoothClient.Callback {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvReceived: TextView
    private lateinit var listDevices: ListView
    private lateinit var etMessage: EditText
    private lateinit var etFilter: EditText
    private lateinit var btnScan: Button
    private lateinit var btnListen: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSend: Button

    // All devices found by the current scan (unfiltered).
    private val allDevices = ArrayList<BluetoothDevice>()

    // Subset of allDevices currently shown in the list (matches deviceLabels 1:1).
    private val displayedDevices = ArrayList<BluetoothDevice>()
    private val deviceLabels = ArrayList<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private val bluetoothClient = BluetoothClient(this)

    // Device we're currently waiting to bond with before connecting.
    private var pendingBondDevice: BluetoothDevice? = null

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startScan()
            } else {
                Toast.makeText(this, "需要开启蓝牙才能继续", Toast.LENGTH_SHORT).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                ensureBluetoothEnabledThenScan()
            } else {
                Toast.makeText(this, "缺少蓝牙权限，无法扫描/连接", Toast.LENGTH_SHORT).show()
            }
        }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = getDeviceExtra(intent) ?: return
                    if (allDevices.none { it.address == device.address }) {
                        allDevices.add(device)
                        applyFilter()
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    tvStatus.text = "正在扫描..."
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    tvStatus.text = "扫描完成，共发现 ${allDevices.size} 个设备"
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = getDeviceExtra(intent) ?: return
                    if (device.address != pendingBondDevice?.address) return

                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDING -> tvStatus.text = "正在配对 ${describeDevice(device)}..."
                        BluetoothDevice.BOND_BONDED -> {
                            pendingBondDevice = null
                            // Give the stack a moment to settle right after bonding — connecting
                            // immediately is a common cause of "socket might closed" failures.
                            tvStatus.text = "配对成功，准备连接..."
                            listDevices.postDelayed({ connectTo(device) }, 500)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            pendingBondDevice = null
                            tvStatus.text = "配对失败或已取消"
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            Toast.makeText(this, "此设备不支持蓝牙", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        bluetoothAdapter = adapter

        tvStatus = findViewById(R.id.tvStatus)
        tvReceived = findViewById(R.id.tvReceived)
        listDevices = findViewById(R.id.listDevices)
        etMessage = findViewById(R.id.etMessage)
        etFilter = findViewById(R.id.etFilter)
        btnScan = findViewById(R.id.btnScan)
        btnListen = findViewById(R.id.btnListen)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnSend = findViewById(R.id.btnSend)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceLabels)
        listDevices.adapter = deviceAdapter

        btnScan.setOnClickListener { requestPermissionsThenScan() }
        btnListen.setOnClickListener { toggleListen() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnSend.setOnClickListener { sendMessage() }
        listDevices.setOnItemClickListener { _, _, position, _ ->
            pairAndConnect(displayedDevices[position])
        }
        etFilter.addTextChangedListener { applyFilter() }

        registerReceiver(bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        bluetoothClient.disconnect()
        bluetoothClient.stopListening()
        if (hasPermission(scanPermission())) {
            cancelDiscoverySafely()
        }
    }

    // ---- Permissions & scanning ----------------------------------------------------------

    private fun requestPermissionsThenScan() {
        val required = requiredPermissions()
        val missing = required.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            ensureBluetoothEnabledThenScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensureBluetoothEnabledThenScan() {
        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        allDevices.clear()
        applyFilter()

        cancelDiscoverySafely()
        bluetoothAdapter.startDiscovery()
    }

    /** Rebuilds [displayedDevices]/[deviceLabels] from [allDevices] using the current filter text. */
    @SuppressLint("MissingPermission")
    private fun applyFilter() {
        val query = etFilter.text.toString().trim()

        displayedDevices.clear()
        deviceLabels.clear()
        allDevices
            .filter { query.isEmpty() || (it.name ?: "").contains(query, ignoreCase = true) }
            .forEach { device ->
                displayedDevices.add(device)
                deviceLabels.add(describeDevice(device))
            }
        deviceAdapter.notifyDataSetChanged()
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscoverySafely() {
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun describeDevice(device: BluetoothDevice): String {
        val name = device.name ?: "未知设备"
        return "$name\n${device.address}"
    }

    // ---- Pairing & connecting --------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun pairAndConnect(device: BluetoothDevice) {
        cancelDiscoverySafely()

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connectTo(device)
        } else {
            pendingBondDevice = device
            tvStatus.text = "发起配对 ${describeDevice(device)}..."
            device.createBond()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        tvStatus.text = "正在连接 ${describeDevice(device)}..."
        bluetoothClient.connect(device)
    }

    // ---- Server (listen) mode ---------------------------------------------------------------

    /** True while we are waiting for an incoming connection as the SPP server. */
    private var listenMode = false

    private fun toggleListen() {
        val required = requiredPermissions()
        val missing = required.filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        if (listenMode) {
            bluetoothClient.stopListening()
            listenMode = false
            onListenStopped()
        } else {
            if (!bluetoothAdapter.isEnabled) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return
            }
            listenMode = true
            bluetoothClient.startListening(bluetoothAdapter)
        }
    }

    private fun disconnect() {
        bluetoothClient.disconnect()
        onDisconnected()
    }

    private fun sendMessage() {
        val text = etMessage.text.toString()
        if (text.isNotEmpty()) {
            bluetoothClient.send(text)
            etMessage.text.clear()
        }
    }

    // ---- BluetoothClient.Callback (delivered on main thread) --------------------------------

    override fun onConnecting() {
        tvStatus.text = "正在连接..."
    }

    override fun onListening() {
        tvStatus.text = "等待对方连接..."
        btnListen.text = getString(R.string.action_stop_listen)
    }

    override fun onConnected() {
        listenMode = false
        tvStatus.text = "已连接"
        btnListen.text = getString(R.string.action_listen)
        btnDisconnect.isEnabled = true
        btnSend.isEnabled = true
    }

    override fun onConnectFailed(error: Exception) {
        tvStatus.text = "连接失败：${error.message}"
        onListenStopped()
        btnDisconnect.isEnabled = false
        btnSend.isEnabled = false
    }

    private fun onListenStopped() {
        btnListen.text = getString(R.string.action_listen)
    }

    override fun onMessageReceived(message: String) {
        tvReceived.append(message)
    }

    override fun onDisconnected() {
        listenMode = false
        tvStatus.text = "未连接"
        onListenStopped()
        btnDisconnect.isEnabled = false
        btnSend.isEnabled = false
    }
}
