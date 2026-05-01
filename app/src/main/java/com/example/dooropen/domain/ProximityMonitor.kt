package com.example.dooropen.domain

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.dooropen.data.DoorPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ProximityMonitor {

    sealed class ProximityState {
        data object Unknown : ProximityState()
        data object Scanning : ProximityState()
        data class Far(val rssi: Int) : ProximityState()
        data class Near(val rssi: Int, val deviceName: String?) : ProximityState()
        data class VeryNear(val rssi: Int, val deviceName: String?) : ProximityState()
        data object NotDetected : ProximityState()
        data class Error(val message: String) : ProximityState()
    }

    // Callback for auto-open feature
    interface AutoOpenCallback {
        fun onAutoOpenTrigger()
    }
    private var autoOpenCallback: AutoOpenCallback? = null
    private var autoOpenEnabled = false
    private var lastAutoOpenTime = 0L
    private const val AUTO_OPEN_COOLDOWN_MS = 30_000L // 30 seconds between auto-opens

    private val _state = MutableStateFlow<ProximityState>(ProximityState.Unknown)
    val state: StateFlow<ProximityState> = _state.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastAnnounceTime = 0L
    private var hasAnnouncedVeryNear = false
    private var hasAnnouncedNear = false

    // RSSI thresholds (closer = higher number, -30 is touching, -100 is far)
    private const val VERY_NEAR_THRESHOLD = -45  // 1-2 feet - auto-open zone
    private const val NEAR_THRESHOLD = -60       // 6-10 feet
    private const val FAR_THRESHOLD = -80        // 15+ feet
    private const val ANNOUNCE_COOLDOWN_MS = 8000  // Don't announce more than every 8 seconds

    fun setAutoOpenEnabled(enabled: Boolean) {
        autoOpenEnabled = enabled
    }

    fun setAutoOpenCallback(callback: AutoOpenCallback?) {
        autoOpenCallback = callback
    }

    fun startMonitoring(context: Context) {
        if (!DoorPrefs.getBleEnabled(context)) {
            _state.value = ProximityState.Error("Bluetooth mode not enabled")
            return
        }

        val mac = DoorPrefs.getBleMac(context)
        if (mac.isBlank()) {
            _state.value = ProximityState.Error("Bluetooth MAC not configured")
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return
        val adapter = bluetoothManager.adapter ?: return

        if (!adapter.isEnabled) {
            _state.value = ProximityState.Error("Bluetooth is off")
            return
        }

        // Try classic Bluetooth first (faster for paired devices)
        try {
            val device = adapter.getRemoteDevice(mac)
            checkClassicBluetooth(device)
        } catch (_: Exception) {
        }

        // Start BLE scanning for proximity
        startBleScan(context, adapter, mac)
    }

    private fun checkClassicBluetooth(device: BluetoothDevice) {
        try {
            // For bonded devices, we can try to read RSSI
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                // Use reflection to get RSSI (not ideal but works for paired devices)
                val method = device.javaClass.getMethod("getRssi")
                val rssi = method.invoke(device) as? Int
                if (rssi != null && rssi != 0) {
                    updateProximity(rssi, device.name ?: "SwitchBot")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startBleScan(context: Context, adapter: BluetoothAdapter, targetMac: String) {
        val scanner = adapter.bluetoothLeScanner ?: return

        _state.value = ProximityState.Scanning

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val deviceMac = device.address?.uppercase() ?: return@let
                    val targetMacUpper = targetMac.uppercase()

                    if (deviceMac == targetMacUpper || deviceMac.replace(":", "") == targetMacUpper.replace(":", "")) {
                        val rssi = result.rssi
                        if (rssi != 0) {
                            updateProximity(rssi, device.name ?: "SwitchBot")
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = ProximityState.Error("Scan failed: $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            // Stop scan after 10 seconds to save battery, then restart
            handler.postDelayed({
                stopMonitoring(context)
                handler.postDelayed({
                    startMonitoring(context)
                }, 2000)
            }, 10000)
        } catch (_: Exception) {
            _state.value = ProximityState.Error("Cannot start scan")
        }
    }

    private fun updateProximity(rssi: Int, deviceName: String) {
        val now = System.currentTimeMillis()

        when {
            rssi >= VERY_NEAR_THRESHOLD -> {
                _state.value = ProximityState.VeryNear(rssi, deviceName)

                // Auto-open trigger when very close
                if (autoOpenEnabled && now - lastAutoOpenTime > AUTO_OPEN_COOLDOWN_MS) {
                    lastAutoOpenTime = now
                    autoOpenCallback?.onAutoOpenTrigger()
                }

                // Announce only every 8 seconds and only when transitioning
                if (!hasAnnouncedVeryNear || now - lastAnnounceTime > ANNOUNCE_COOLDOWN_MS) {
                    hasAnnouncedVeryNear = true
                    hasAnnouncedNear = false
                    lastAnnounceTime = now
                }
            }
            rssi >= NEAR_THRESHOLD -> {
                _state.value = ProximityState.Near(rssi, deviceName)
                if (!hasAnnouncedNear && !hasAnnouncedVeryNear) {
                    hasAnnouncedNear = true
                    lastAnnounceTime = now
                }
            }
            rssi >= FAR_THRESHOLD -> {
                _state.value = ProximityState.Far(rssi)
                // Reset announcements when walking away
                hasAnnouncedNear = false
                hasAnnouncedVeryNear = false
            }
            else -> {
                _state.value = ProximityState.NotDetected
                hasAnnouncedNear = false
                hasAnnouncedVeryNear = false
            }
        }
    }

    fun stopMonitoring(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val scanner = adapter?.bluetoothLeScanner

        scanCallback?.let { callback ->
            try {
                scanner?.stopScan(callback)
            } catch (_: Exception) {
            }
            scanCallback = null
        }

        handler.removeCallbacksAndMessages(null)
    }

    fun shouldAnnounce(): Boolean {
        val state = _state.value
        val now = System.currentTimeMillis()
        return when (state) {
            is ProximityState.VeryNear -> now - lastAnnounceTime > ANNOUNCE_COOLDOWN_MS
            is ProximityState.Near -> !hasAnnouncedNear && now - lastAnnounceTime > ANNOUNCE_COOLDOWN_MS
            else -> false
        }
    }

    fun getAnnounceMessage(): String? {
        return when (_state.value) {
            is ProximityState.VeryNear -> "You are very close to the door. Click to open."
            is ProximityState.Near -> "You are near the door. Click Tecla to open."
            else -> null
        }
    }

    fun getStatusMessage(): String {
        return when (val state = _state.value) {
            is ProximityState.VeryNear -> "Very close! Auto-opening enabled"
            is ProximityState.Near -> "Getting close..."
            is ProximityState.Far -> "Walk closer to the door"
            is ProximityState.Scanning -> "Scanning for door..."
            is ProximityState.NotDetected -> "Door not detected - walk closer"
            is ProximityState.Error -> ""
            is ProximityState.Unknown -> ""
        }
    }

    fun markAnnounced() {
        lastAnnounceTime = System.currentTimeMillis()
        when (_state.value) {
            is ProximityState.VeryNear -> hasAnnouncedVeryNear = true
            is ProximityState.Near -> hasAnnouncedNear = true
            else -> {}
        }
    }
}
