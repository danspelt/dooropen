package com.example.dooropen.domain

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
    private const val AUTO_OPEN_COOLDOWN_MS = 60_000L // 60 seconds between auto-opens

    private val _state = MutableStateFlow<ProximityState>(ProximityState.Unknown)
    val state: StateFlow<ProximityState> = _state.asStateFlow()

    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()

    private val _bleDebug = MutableStateFlow<List<String>>(emptyList())
    val bleDebug: StateFlow<List<String>> = _bleDebug.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastAnnounceTime = 0L
    private var hasAnnouncedVeryNear = false
    private var hasAnnouncedNear = false

    // RSSI thresholds calibrated for this specific door (bot on top)
    // Measured: outside at door = -71 dBm, inside = -68 dBm
    // Closer = higher number: -30 touching, -100 very far
    // VeryNear set to -72 to catch -71 outside; inside false-triggers are prevented
    // by requiring VERY_NEAR_CONSECUTIVE_REQUIRED sustained hits before auto-opening.
    private const val VERY_NEAR_THRESHOLD = -72  // catches -71 dBm at the door outside
    private const val NEAR_THRESHOLD = -82       // ~5 ft away
    private const val FAR_THRESHOLD = -90        // ~10+ ft away
    private const val ANNOUNCE_COOLDOWN_MS = 8000  // Don't announce more than every 8 seconds

    // Number of consecutive VeryNear scan hits required before auto-open fires.
    // Inside the house you may get 1-2 hits while walking past; outside you stop and hold.
    private const val VERY_NEAR_CONSECUTIVE_REQUIRED = 3
    private var veryNearConsecutiveCount = 0

    // When true (set by ProximityService), run a continuous scan instead of stop/restart loop.
    // The stop/restart loop uses Handler(mainLooper) which Doze can throttle with screen off.
    private var backgroundMode = false

    fun setBackgroundMode(enabled: Boolean) {
        backgroundMode = enabled
    }

    fun setAutoOpenEnabled(enabled: Boolean) {
        autoOpenEnabled = enabled
    }

    fun setAutoOpenCallback(callback: AutoOpenCallback?) {
        autoOpenCallback = callback
    }

    fun startMonitoring(context: Context) {
        // Stop any existing scan first so repeated watchdog calls don't leak callbacks
        if (scanCallback != null) stopMonitoring(context)

        val bleOn = try { DoorPrefs.getBleEnabled(context) } catch (_: Exception) { false }
        if (!bleOn) {
            _state.value = ProximityState.Error("Bluetooth mode not enabled")
            return
        }

        val mac = try { DoorPrefs.getBleMac(context) } catch (_: Exception) { "" }
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
        // Check BLUETOOTH_SCAN permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                _state.value = ProximityState.Error("Bluetooth Scan permission not granted. Go to Settings and re-save to grant permissions.")
                return
            }
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = ProximityState.Error("Bluetooth scanner unavailable. Is Bluetooth on?")
            return
        }

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
                        // Parse battery from SwitchBot manufacturer data
                        val scanRecord = result.scanRecord
                        val debugLines = mutableListOf<String>()
                        debugLines.add("RSSI: ${result.rssi} dBm")
                        // Dump all manufacturer data
                        for (mfrId in listOf(0x0969, 0x0009, 0x0059, 0x004C, 0x0006)) {
                            val mfr = scanRecord?.getManufacturerSpecificData(mfrId)
                            if (mfr != null && mfr.isNotEmpty()) {
                                val hex = mfr.joinToString(" ") { "%02X".format(it) }
                                debugLines.add("Mfr 0x%04X [%d]: %s".format(mfrId, mfr.size, hex))
                                // Show each byte as decimal too
                                val dec = mfr.mapIndexed { i, b -> "[$i]=${b.toInt() and 0xFF}" }.joinToString(" ")
                                debugLines.add("  dec: $dec")
                            }
                        }
                        // Dump all service data
                        scanRecord?.serviceData?.forEach { (uuid, bytes) ->
                            val hex = bytes.joinToString(" ") { "%02X".format(it) }
                            val shortUuid = uuid.toString().take(8)
                            debugLines.add("SvcData $shortUuid [${bytes.size}]: $hex")
                            val dec = bytes.mapIndexed { i, b -> "[$i]=${b.toInt() and 0xFF}" }.joinToString(" ")
                            debugLines.add("  dec: $dec")
                        }
                        _bleDebug.value = debugLines
                        // Current best guess: ID 0x0969, byte[2] bits 6-0
                        result.scanRecord?.getManufacturerSpecificData(0x0969)?.let { mfr ->
                            if (mfr.size >= 3) {
                                val batt = mfr[2].toInt() and 0x7F
                                if (batt in 0..100) _battery.value = batt
                            }
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val reason = when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Already scanning"
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE not supported on this device"
                    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error - try toggling Bluetooth off/on"
                    else -> "Error code $errorCode"
                }
                _state.value = ProximityState.Error(reason)
            }
        }

        val scanMode = if (backgroundMode) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            if (!backgroundMode) {
                // Foreground: stop/restart cycle to save battery
                handler.postDelayed({
                    stopMonitoring(context)
                    handler.postDelayed({
                        startMonitoring(context)
                    }, 2000)
                }, 10000)
            }
            // Background mode: scan runs continuously — the service manages its own lifecycle.
            // Continuous scanning avoids the Handler/mainLooper being throttled by Doze.
        } catch (_: Exception) {
            _state.value = ProximityState.Error("Cannot start scan")
        }
    }

    private fun updateProximity(rssi: Int, deviceName: String) {
        val now = System.currentTimeMillis()

        when {
            rssi >= VERY_NEAR_THRESHOLD -> {
                veryNearConsecutiveCount++
                _state.value = ProximityState.VeryNear(rssi, deviceName)

                // Only fire auto-open after sustained consecutive hits to avoid inside false-triggers
                if (autoOpenEnabled
                    && veryNearConsecutiveCount >= VERY_NEAR_CONSECUTIVE_REQUIRED
                    && now - lastAutoOpenTime > AUTO_OPEN_COOLDOWN_MS
                ) {
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
                veryNearConsecutiveCount = 0
                _state.value = ProximityState.Near(rssi, deviceName)
                if (!hasAnnouncedNear && !hasAnnouncedVeryNear) {
                    hasAnnouncedNear = true
                    lastAnnounceTime = now
                }
            }
            rssi >= FAR_THRESHOLD -> {
                veryNearConsecutiveCount = 0
                _state.value = ProximityState.Far(rssi)
                hasAnnouncedNear = false
                hasAnnouncedVeryNear = false
            }
            else -> {
                veryNearConsecutiveCount = 0
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
        return when (_state.value) {
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
