package com.canis.hejje.recording

import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import com.canis.hejje.HejjeApp
import com.canis.hejje.data.DriveLog

private val Context.container get() = (applicationContext as HejjeApp).container

/**
 * Standard Bluetooth connect/disconnect broadcasts. These still reach apps that are not running
 * (they are on Android's list of exempted broadcasts) and act as the fallback trigger.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val car = context.container.settings.current.carAddress ?: return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (!CarBluetooth.deviceAddress(device).equals(car, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> context.container.controller.carConnected(DriveLog.SOURCE_BLUETOOTH)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> context.container.controller.carDisconnected(source = DriveLog.SOURCE_BLUETOOTH)
        }
    }
}

/**
 * Companion-device presence: Android binds this service when the linked car connects, which both
 * wakes the app and permits starting the recording service from the background.
 */
@RequiresApi(31)
class CarPresenceService : CompanionDeviceService() {
    private val car get() = container.settings.current.carAddress

    // Android 16+: precise events.
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> container.controller.carConnected(DriveLog.SOURCE_COMPANION)
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> container.controller.carDisconnected(source = DriveLog.SOURCE_COMPANION)
        }
    }

    // Android 12-15: "appeared" can also mean "in Bluetooth LE range", so confirm the real connection.
    @Deprecated("Replaced by onDevicePresenceEvent on Android 16")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    @Deprecated("Replaced by onDevicePresenceEvent on Android 16")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared()

    @Deprecated("Replaced on Android 13")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) appeared()
    }

    @Deprecated("Replaced on Android 13")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) disappeared()
    }

    private fun appeared() {
        if (Build.VERSION.SDK_INT >= 36) return
        val address = car ?: return
        CarBluetooth.isConnected(this, address) { if (it) container.controller.carConnected(DriveLog.SOURCE_COMPANION) }
    }

    private fun disappeared() {
        if (Build.VERSION.SDK_INT >= 36) return
        val address = car ?: return
        CarBluetooth.isConnected(this, address) { if (!it) container.controller.carDisconnected(source = DriveLog.SOURCE_COMPANION) }
    }
}

/** After a reboot or an app update: the app process starts (which runs recovery) and re-arms its triggers. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = context.container
        CarBluetooth.observe(context, c.settings.current.carAddress)
        c.scheduleWork()
    }
}

/** The "Stop" button on the recording notification. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) context.container.controller.manualStop()
    }

    companion object {
        const val ACTION_STOP = "com.canis.hejje.action.STOP"
    }
}
