package com.canis.hejje.recording

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

data class CarDevice(val name: String, val address: String)

/** Bluetooth helpers: paired devices, "is the car connected right now", and the companion-device link. */
@SuppressLint("MissingPermission") // every entry point checks hasPermission() first
object CarBluetooth {
    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun isOn(context: Context): Boolean = runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    fun paired(context: Context): List<CarDevice> {
        if (!hasPermission(context)) return emptyList()
        return runCatching {
            adapter(context)?.bondedDevices.orEmpty().map { CarDevice(it.name ?: it.address, it.address) }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** Asks the phone whether [address] is connected (hands-free or media audio). Calls back on the main thread. */
    fun isConnected(context: Context, address: String, callback: (Boolean) -> Unit) {
        val adapter = adapter(context)
        val main = Handler(Looper.getMainLooper())
        if (adapter == null || !hasPermission(context) || !isOn(context)) {
            main.post { callback(false) }
            return
        }
        val profiles = intArrayOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
        var remaining = profiles.size
        var done = false
        fun finish(result: Boolean) {
            if (done) return
            if (result || remaining == 0) {
                done = true
                callback(result)
            }
        }
        for (profile in profiles) {
            val ok = runCatching {
                adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val found = runCatching { proxy.connectedDevices.any { it.address.equals(address, true) } }.getOrDefault(false)
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        main.post { remaining--; finish(found) }
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }.getOrDefault(false)
            if (!ok) main.post { remaining--; finish(false) }
        }
        main.postDelayed({ remaining = 0; finish(false) }, 3_000)
    }

    // --- Companion device link (lets Android wake the app reliably when the car connects) ---

    fun supportsLink(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun cdm(context: Context): CompanionDeviceManager? = context.getSystemService(CompanionDeviceManager::class.java)

    fun isLinked(context: Context, address: String?): Boolean {
        if (address == null || !supportsLink(context)) return false
        val cdm = cdm(context) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(address, true) }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.any { it.equals(address, true) }
            }
        }.getOrDefault(false)
    }

    fun associationRequest(address: String): AssociationRequest = AssociationRequest.Builder()
        .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
        .setSingleDevice(true)
        .build()

    /** Asks Android to tell us when the linked car appears and disappears. Safe to call repeatedly. */
    fun observe(context: Context, address: String?) {
        if (address == null || !supportsLink(context)) return
        val cdm = cdm(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.myAssociations.filter { it.deviceMacAddress?.toString().equals(address, true) }.forEach {
                    cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(it.id).build())
                }
            } else if (Build.VERSION.SDK_INT >= 31 && isLinked(context, address)) {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(address)
            }
        }
    }

    fun unlink(context: Context, address: String?) {
        if (address == null || !supportsLink(context)) return
        val cdm = cdm(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.filter { it.deviceMacAddress?.toString().equals(address, true) }.forEach { cdm.disassociate(it.id) }
            } else {
                @Suppress("DEPRECATION")
                cdm.disassociate(address)
            }
        }
    }

    fun deviceAddress(device: BluetoothDevice?): String? = device?.address
}
