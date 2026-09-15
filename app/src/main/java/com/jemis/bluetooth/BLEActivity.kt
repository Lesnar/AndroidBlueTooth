package com.jemis.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
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

/**
 * BLE central role: scan -> connectGatt -> discoverServices -> pick a writable/notifiable
 * characteristic -> write/notify. See [BleClient] for the GATT state machine.
 */
class BLEActivity : AppCompatActivity(), BleClient.Callback {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvReceived: TextView
    private lateinit var listDevices: ListView
    private lateinit var etMessage: EditText
    private lateinit var etFilter: EditText
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSend: Button

    private val allDevices = LinkedHashMap<String, BluetoothDevice>()
    private val displayedDevices = ArrayList<BluetoothDevice>()
    private val deviceLabels = ArrayList<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private val bleClient = BleClient(this)

    private var writable = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_bleactivity)
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
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnSend = findViewById(R.id.btnSend)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceLabels)
        listDevices.adapter = deviceAdapter

        btnScan.setOnClickListener { requestPermissionsThenScan() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnSend.setOnClickListener { sendMessage() }
        listDevices.setOnItemClickListener { _, _, position, _ ->
            connectTo(displayedDevices[position])
        }
        etFilter.addTextChangedListener { applyFilter() }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient.disconnect()
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

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensureBluetoothEnabledThenScan() {
        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startScan()
        }
    }

    private fun startScan() {
        allDevices.clear()
        applyFilter()
        bleClient.startScan(bluetoothAdapter)
    }

    /** Rebuilds [displayedDevices]/[deviceLabels] from [allDevices] using the current filter text. */
    @SuppressLint("MissingPermission")
    private fun applyFilter() {
        val query = etFilter.text.toString().trim()

        displayedDevices.clear()
        deviceLabels.clear()
        allDevices.values
            .filter { query.isEmpty() || (it.name ?: "").contains(query, ignoreCase = true) }
            .forEach { device ->
                displayedDevices.add(device)
                deviceLabels.add(describeDevice(device))
            }
        deviceAdapter.notifyDataSetChanged()
    }

    @SuppressLint("MissingPermission")
    private fun describeDevice(device: BluetoothDevice): String {
        val name = device.name ?: "未知设备"
        return "$name\n${device.address}"
    }

    // ---- Connecting -------------------------------------------------------------------------

    private fun connectTo(device: BluetoothDevice) {
        bleClient.stopScan()
        tvStatus.text = "正在连接 ${describeDevice(device)}..."
        bleClient.connect(this, device)
    }

    private fun disconnect() {
        bleClient.disconnect()
        onDisconnected()
    }

    private fun sendMessage() {
        val text = etMessage.text.toString()
        if (text.isEmpty()) return

        if (bleClient.send(text)) {
            etMessage.text.clear()
        } else {
            Toast.makeText(this, "当前没有可写的 Characteristic", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- BleClient.Callback (delivered on main thread) --------------------------------------

    override fun onScanStarted() {
        tvStatus.text = "正在扫描..."
    }

    override fun onScanStopped() {
        if (tvStatus.text == "正在扫描...") {
            tvStatus.text = "扫描完成，共发现 ${allDevices.size} 个设备"
        }
    }

    override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
        if (allDevices.put(device.address, device) == null) {
            applyFilter()
        }
    }

    override fun onConnecting() {
        tvStatus.text = "正在建立 GATT 连接..."
    }

    override fun onConnected() {
        tvStatus.text = "已连接，正在发现服务..."
    }

    override fun onServicesDiscovered(services: List<BluetoothGattService>) {
        tvStatus.text = "发现 ${services.size} 个服务"
    }

    override fun onReady(writable: Boolean, notifiable: Boolean) {
        this.writable = writable
        tvStatus.text = buildString {
            append("已就绪")
            if (writable) append("，可写入") else append("，无可写特征")
            if (notifiable) append("，已订阅通知")
        }
        btnDisconnect.isEnabled = true
        btnSend.isEnabled = writable
    }

    override fun onMessageReceived(message: String) {
        tvReceived.append(message + "\n")
    }

    override fun onConnectFailed(error: Exception) {
        tvStatus.text = "连接失败：${error.message}"
        btnDisconnect.isEnabled = false
        btnSend.isEnabled = false
    }

    override fun onDisconnected() {
        writable = false
        tvStatus.text = "未连接"
        btnDisconnect.isEnabled = false
        btnSend.isEnabled = false
    }
}
