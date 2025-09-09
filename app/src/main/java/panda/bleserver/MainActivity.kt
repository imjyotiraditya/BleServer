package panda.bleserver

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import panda.bleserver.ui.theme.BleServerTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BleServer"
        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("87654321-4321-4321-4321-cba987654321")
    }

    private var serverStatus by mutableStateOf("Stopped")
    private var connectedDevices by mutableStateOf<List<String>>(emptyList())
    private var receivedData by mutableStateOf("")

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeBluetooth()
        } else {
            serverStatus = "Permissions denied"
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.d(TAG, "onServiceAdded: status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startAdvertising()
                }, 100)
            } else {
                serverStatus = "Service add failed: $status"
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(TAG, "onConnectionStateChange: device=${device?.address}, status=$status, newState=$newState")

            device?.let {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Device connected: ${it.address}")
                        val currentDevices = connectedDevices.toMutableList()
                        if (!currentDevices.contains(it.address)) {
                            currentDevices.add(it.address)
                            connectedDevices = currentDevices
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Device disconnected: ${it.address}")
                        val currentDevices = connectedDevices.toMutableList()
                        currentDevices.remove(it.address)
                        connectedDevices = currentDevices
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            Log.d(TAG, "onCharacteristicWriteRequest: device=${device?.address}, requestId=$requestId, characteristic=${characteristic?.uuid}, responseNeeded=$responseNeeded, valueLength=${value?.size}")

            if (characteristic?.uuid == CHARACTERISTIC_UUID && value != null) {
                val receivedJson = String(value, Charsets.UTF_8)
                Log.d(TAG, "Received data: $receivedJson")
                receivedData = receivedJson

                if (responseNeeded) {
                    try {
                        val success = bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            null
                        )
                        Log.d(TAG, "Response sent: $success")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception sending response", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send response", e)
                    }
                } else {
                    Log.d(TAG, "No response needed")
                }
            } else {
                Log.w(TAG, "Write request for unknown characteristic or null value")
                if (responseNeeded) {
                    try {
                        bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            null
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send error response", e)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            Log.d(TAG, "onCharacteristicReadRequest: device=${device?.address}, characteristic=${characteristic?.uuid}")

            try {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    "OK".toByteArray()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read response", e)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: android.bluetooth.BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            Log.d(TAG, "onDescriptorWriteRequest")

            if (responseNeeded) {
                try {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send descriptor response", e)
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising started successfully")
            serverStatus = "Running"
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "Advertising failed: $errorMessage")
            serverStatus = "Failed: $errorMessage"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissions()

        setContent {
            BleServerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ServerScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasBluetoothAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
    }

    private fun startServer() {
        if (serverStatus == "Running") return

        if (!hasBluetoothAdvertisePermission()) {
            serverStatus = "No advertise permission"
            return
        }

        if (!hasBluetoothConnectPermission()) {
            serverStatus = "No connect permission"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            serverStatus = "Bluetooth disabled"
            return
        }

        serverStatus = "Starting..."

        try {
            bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(characteristic)

            val success = bluetoothGattServer?.addService(service)
            Log.d(TAG, "Add service result: $success")

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting server", e)
            serverStatus = "Permission error"
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting server", e)
            serverStatus = "Error"
        }
    }

    private fun stopServer() {
        try {
            if (hasBluetoothAdvertisePermission()) {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
            if (hasBluetoothConnectPermission()) {
                bluetoothGattServer?.close()
            }
            bluetoothGattServer = null
            characteristic = null
            serverStatus = "Stopped"
            connectedDevices = emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping server", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop server", e)
        }
    }

    private fun startAdvertising() {
        if (!hasBluetoothAdvertisePermission()) return

        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            Log.d(TAG, "Starting legacy advertising")
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting advertising", e)
            serverStatus = "Permission error"
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising", e)
            serverStatus = "Advertising error"
        }
    }

    @Composable
    fun ServerScreen(modifier: Modifier = Modifier) {
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
                        text = "Server Status: $serverStatus",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    if (serverStatus == "Stopped" || serverStatus.startsWith("Failed") || serverStatus == "Error" || serverStatus == "Permission error" || serverStatus == "Permissions denied") {
                        Button(
                            onClick = { startServer() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Server")
                        }
                    } else {
                        Button(
                            onClick = { stopServer() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop Server")
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
                        text = "Connected Devices: ${connectedDevices.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    if (connectedDevices.isEmpty()) {
                        Text(
                            text = "No devices connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        connectedDevices.forEach { device ->
                            Text(
                                text = device,
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                        text = "Received Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    if (receivedData.isEmpty()) {
                        Text(
                            text = "No data received",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = receivedData,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}