package com.example.dooropen.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Minimal SwitchBot Bot BLE "press" support (no hub / no cloud).
 *
 * This uses the public/reverse-engineered BLE GATT UUIDs commonly used by SwitchBot Bot:
 * - Service: cba20d00-224d-11e6-9fb8-0002a5d5c51b
 * - Write (REQ): cba20002-224d-11e6-9fb8-0002a5d5c51b
 * - Notify (RESP): cba20003-224d-11e6-9fb8-0002a5d5c51b
 *
 * Unencrypted press payload: 0x57 0x01
 * Encrypted press payload (optional): 0x57 0x11 + 4-byte CRC32(password)
 */
object SwitchBotBle {

    private val SERVICE_UUID: UUID = UUID.fromString("cba20d00-224d-11e6-9fb8-0002a5d5c51b")
    private val REQ_UUID: UUID = UUID.fromString("cba20002-224d-11e6-9fb8-0002a5d5c51b")
    private val RESP_UUID: UUID = UUID.fromString("cba20003-224d-11e6-9fb8-0002a5d5c51b")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    data class BleResult(val ok: Boolean, val message: String)

    @SuppressLint("MissingPermission")
    suspend fun press(
        context: Context,
        macAddress: String,
        password: String,
    ): BleResult {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return BleResult(false, "Bluetooth not available")
        if (!adapter.isEnabled) return BleResult(false, "Bluetooth is off")

        val mac = macAddress.trim()
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            return BleResult(false, "Bad MAC address")
        }

        return try {
            withTimeout(18_000) {
                pressGatt(context, device, password)
            }
        } catch (_: TimeoutCancellationException) {
            BleResult(false, "Bluetooth timeout")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pressGatt(
        context: Context,
        device: BluetoothDevice,
        password: String,
    ): BleResult = suspendCancellableCoroutine { cont ->
        var gatt: BluetoothGatt? = null
        var done = false
        val mainHandler = Handler(Looper.getMainLooper())
        var pendingSuccess: Runnable? = null
        var mtuFallback: Runnable? = null

        fun finish(result: BleResult) {
            if (done) return
            done = true
            pendingSuccess?.let { mainHandler.removeCallbacks(it) }
            pendingSuccess = null
            mtuFallback?.let { mainHandler.removeCallbacks(it) }
            mtuFallback = null
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
            }
            try {
                gatt?.close()
            } catch (_: Exception) {
            }
            if (cont.isActive) cont.resume(result)
        }

        fun serviceSummary(g: BluetoothGatt): String {
            val services = try {
                g.services
            } catch (_: Exception) {
                emptyList()
            }
            if (services.isEmpty()) return "no services"
            return services.take(6).joinToString(", ") { it.uuid.toString() }
        }

        val callback = object : BluetoothGattCallback() {
            private var mtuRequested = false
            private var mtuFallbackPosted = false

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(BleResult(false, "GATT connect error ($status)"))
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Some phones return an empty/incomplete service list if discovery starts too early.
                    // Requesting MTU first tends to stabilize the connection before discovery.
                    if (Build.VERSION.SDK_INT >= 21) {
                        mtuRequested = true
                        if (!g.requestMtu(247)) {
                            mtuRequested = false
                            mtuFallback?.let { mainHandler.removeCallbacks(it) }
                            mtuFallback = null
                            if (!g.discoverServices()) finish(BleResult(false, "Service discovery failed"))
                        } else if (!mtuFallbackPosted) {
                            mtuFallbackPosted = true
                            mtuFallback = Runnable {
                                if (!done && mtuRequested) {
                                    mtuRequested = false
                                    if (!g.discoverServices()) finish(BleResult(false, "Service discovery failed"))
                                }
                            }
                            mainHandler.postDelayed(mtuFallback!!, 1500)
                        }
                    } else {
                        if (!g.discoverServices()) finish(BleResult(false, "Service discovery failed"))
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(BleResult(false, "Disconnected"))
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (!mtuRequested) return
                mtuRequested = false
                mtuFallback?.let { mainHandler.removeCallbacks(it) }
                mtuFallback = null
                // Proceed regardless: MTU negotiation failing shouldn't block discovery.
                if (!g.discoverServices()) finish(BleResult(false, "Service discovery failed"))
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(BleResult(false, "Service discovery error ($status)"))
                    return
                }
                val service = g.getService(SERVICE_UUID) ?: run {
                    finish(
                        BleResult(
                            false,
                            "SwitchBot BLE service not found (${serviceSummary(g)}). " +
                                "Usually this means the MAC is not your Bot's BLE address.",
                        ),
                    )
                    return
                }
                val req = service.getCharacteristic(REQ_UUID) ?: run {
                    finish(BleResult(false, "REQ characteristic not found"))
                    return
                }
                val resp = service.getCharacteristic(RESP_UUID) ?: run {
                    finish(BleResult(false, "RESP characteristic not found"))
                    return
                }

                if (!g.setCharacteristicNotification(resp, true)) {
                    finish(BleResult(false, "Failed to enable notifications"))
                    return
                }
                val cccd = resp.getDescriptor(CCCD_UUID) ?: run {
                    finish(BleResult(false, "CCCD descriptor missing"))
                    return
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    val r = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    if (r != BluetoothGatt.GATT_SUCCESS) {
                        finish(BleResult(false, "Failed to write CCCD ($r)"))
                        return
                    }
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    if (!g.writeDescriptor(cccd)) {
                        finish(BleResult(false, "Failed to write CCCD"))
                        return
                    }
                }

                // Next step continues in onDescriptorWrite.
                gatt = g
                // Stash req in gatt object via tag not possible; just keep local by closure:
                pendingReq = req
                pendingPassword = password
            }

            private var pendingReq: BluetoothGattCharacteristic? = null
            private var pendingPassword: String = ""

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(BleResult(false, "Notify setup failed ($status)"))
                    return
                }

                val req = pendingReq ?: run {
                    finish(BleResult(false, "REQ missing"))
                    return
                }

                val payload = buildPressPayload(pendingPassword)
                req.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (Build.VERSION.SDK_INT >= 33) {
                    val r = g.writeCharacteristic(req, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    if (r != BluetoothGatt.GATT_SUCCESS) finish(BleResult(false, "Write failed ($r)"))
                } else {
                    @Suppress("DEPRECATION")
                    req.value = payload
                    @Suppress("DEPRECATION")
                    if (!g.writeCharacteristic(req)) finish(BleResult(false, "Write failed"))
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                // SwitchBot Bot response: first byte is status. 0x01 = action complete.
                val status = value.firstOrNull()?.toInt() ?: -1
                if (status == 1) {
                    pendingSuccess?.let { mainHandler.removeCallbacks(it) }
                    pendingSuccess = null
                    finish(BleResult(true, "OK"))
                } else {
                    finish(BleResult(false, "Bot status $status"))
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                onCharacteristicChanged(g, characteristic, characteristic.value ?: ByteArray(0))
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                // If we already finished via notify, ignore.
                if (done) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingSuccess?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable {
                        if (!done) finish(BleResult(true, "OK"))
                    }
                    pendingSuccess = r
                    // Prefer notify if it arrives quickly; otherwise accept successful write as completion.
                    mainHandler.postDelayed(r, 900)
                } else {
                    finish(BleResult(false, "Write error ($status)"))
                }
            }
        }

        cont.invokeOnCancellation {
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
            }
            try {
                gatt?.close()
            } catch (_: Exception) {
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }

        if (gatt == null) finish(BleResult(false, "Failed to start BLE connection"))
    }

    private fun buildPressPayload(password: String): ByteArray {
        val pw = password.trim()
        if (pw.isEmpty()) {
            return byteArrayOf(0x57, 0x01)
        }
        val crc = java.util.zip.CRC32().apply { update(pw.toByteArray(StandardCharsets.UTF_8)) }.value
        // 0x57 0x11 + little-endian crc32
        return byteArrayOf(
            0x57,
            0x11,
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
            ((crc shr 16) and 0xFF).toByte(),
            ((crc shr 24) and 0xFF).toByte(),
        )
    }
}

