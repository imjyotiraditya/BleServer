package panda.bleclient

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import panda.bleclient.ui.theme.BleClientTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BleClient"
        private const val SCAN_DURATION_MS = 30000L
        private const val MAX_MTU = 517
        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("87654321-4321-4321-4321-cba987654321")
    }

    data class BleDevice(
        val name: String?,
        val address: String,
        val rssi: Int,
        val bluetoothDevice: BluetoothDevice
    )

    private var isScanning by mutableStateOf(false)
    private var discoveredDevices by mutableStateOf<List<BleDevice>>(emptyList())
    private var connectionStatus by mutableStateOf("Disconnected")
    private var connectedDevice by mutableStateOf<BleDevice?>(null)
    private var dataInput by mutableStateOf("")

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private val scanHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeBluetooth()
        } else {
            connectionStatus = "Permissions denied"
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)

                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        if (hasBluetoothConnectPermission()) {
                            device?.let { connectGatt(it) }
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        connectionStatus = "Bonding failed"
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            result?.let { scanResult ->
                if (hasBluetoothConnectPermission()) {
                    try {
                        val device = BleDevice(
                            name = scanResult.device.name,
                            address = scanResult.device.address,
                            rssi = scanResult.rssi,
                            bluetoothDevice = scanResult.device
                        )

                        val currentDevices = discoveredDevices.toMutableList()
                        val existingDevice = currentDevices.find { it.address == device.address }

                        if (existingDevice == null) {
                            currentDevices.add(device)
                            discoveredDevices = currentDevices
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception accessing device info", e)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionStatus = "Connected"
                    if (hasBluetoothConnectPermission()) {
                        try {
                            gatt?.requestMtu(MAX_MTU)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception requesting MTU", e)
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionStatus = "Disconnected"
                    connectedDevice = null
                    if (hasBluetoothConnectPermission()) {
                        try {
                            gatt?.close()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception closing GATT", e)
                        }
                    }
                    bluetoothGatt = null
                    characteristic = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS && hasBluetoothConnectPermission()) {
                try {
                    gatt?.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception discovering services", e)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(SERVICE_UUID)
                characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondStateReceiver, filter)

        requestPermissions()

        setContent {
            BleClientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ClientScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        disconnectFromDevice()
        unregisterReceiver(bondStateReceiver)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        permissionLauncher.launch(permissions)
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    }

    private fun startScan() {
        if (isScanning || !bluetoothAdapter.isEnabled) {
            return
        }

        if (!hasBluetoothScanPermission()) {
            connectionStatus = "No scan permission"
            return
        }

        discoveredDevices = emptyList()
        isScanning = true

        try {
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

            scanHandler.postDelayed({
                stopScan()
            }, SCAN_DURATION_MS)

        } catch (e: SecurityException) {
            isScanning = false
            connectionStatus = "Permission error"
        } catch (e: Exception) {
            isScanning = false
            connectionStatus = "Scan error"
        }
    }

    private fun stopScan() {
        if (!isScanning) {
            return
        }

        try {
            if (hasBluetoothScanPermission()) {
                bluetoothLeScanner?.stopScan(scanCallback)
            }
            isScanning = false
            scanHandler.removeCallbacksAndMessages(null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping scan", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
    }

    private fun connectToDevice(device: BleDevice) {
        if (connectionStatus != "Disconnected") {
            return
        }

        if (!hasBluetoothConnectPermission()) {
            connectionStatus = "No connect permission"
            return
        }

        stopScan()
        connectedDevice = device

        try {
            when (device.bluetoothDevice.bondState) {
                BluetoothDevice.BOND_NONE -> {
                    connectionStatus = "Bonding..."
                    device.bluetoothDevice.createBond()
                }
                BluetoothDevice.BOND_BONDED -> {
                    connectGatt(device.bluetoothDevice)
                }
                BluetoothDevice.BOND_BONDING -> {
                    connectionStatus = "Bonding..."
                }
            }
        } catch (e: SecurityException) {
            connectionStatus = "Permission error"
        } catch (e: Exception) {
            connectionStatus = "Connection error"
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            connectionStatus = "No connect permission"
            return
        }

        try {
            connectionStatus = "Connecting..."

            bluetoothGatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            connectionStatus = "Permission error"
        } catch (e: Exception) {
            connectionStatus = "GATT error"
        }
    }

    private fun disconnectFromDevice() {
        try {
            if (hasBluetoothConnectPermission()) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
            bluetoothGatt = null
            characteristic = null
            connectionStatus = "Disconnected"
            connectedDevice = null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception disconnecting", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
        }
    }

    private fun sendData() {
        if (connectionStatus != "Connected" || characteristic == null) {
            return
        }

        if (!hasBluetoothConnectPermission()) {
            connectionStatus = "No connect permission"
            return
        }

        if (dataInput.isEmpty()) {
            return
        }

        try {
            val data = dataInput.toByteArray(Charsets.UTF_8)
            characteristic?.let { char ->
                char.value = data
                bluetoothGatt?.writeCharacteristic(char)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception sending data", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data", e)
        }
    }

    @Composable
    fun ClientScreen(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = if (isScanning) "Scanning..." else "Device Discovery",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isScanning) {
                        Button(
                            onClick = { stopScan() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop Scan")
                        }
                    } else {
                        Button(
                            onClick = { startScan() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Scan")
                        }
                    }
                }
            }

            if (discoveredDevices.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Found Devices: ${discoveredDevices.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        discoveredDevices.forEach { device ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = device.name ?: "Unknown",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = device.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Button(
                                        onClick = { connectToDevice(device) }
                                    ) {
                                        Text("Connect")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Status: $connectionStatus",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    connectedDevice?.let {
                        Text(
                            text = it.name ?: it.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (connectionStatus == "Connected") {
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { disconnectFromDevice() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Send Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = dataInput,
                        onValueChange = { dataInput = it },
                        label = { Text("Data") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { sendData() },
                        enabled = connectionStatus == "Connected" && dataInput.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}