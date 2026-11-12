package com.canis.hejje.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.canis.hejje.data.SettingsData

enum class HealthId { LOCATION, BACKGROUND_LOCATION, LOCATION_ON, NOTIFICATIONS, BLUETOOTH_PERMISSION, BLUETOOTH_ON, CAR, LINK, BATTERY, AUTO_RECORD }

/** [required] items block automatic recording; the others only make it more dependable or pleasant. */
data class HealthItem(val id: HealthId, val ok: Boolean, val required: Boolean = true)

/** Everything automatic recording depends on, checked live. */
object Health {
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasFine(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(context: Context) = Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun notificationsOn(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun batteryExempt(context: Context) =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun locationOn(context: Context) =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } == true

    fun check(context: Context, s: SettingsData): List<HealthItem> {
        val items = ArrayList<HealthItem>()
        items += HealthItem(HealthId.LOCATION, hasFine(context))
        items += HealthItem(HealthId.BACKGROUND_LOCATION, hasFine(context) && hasBackground(context))
        items += HealthItem(HealthId.LOCATION_ON, locationOn(context))
        items += HealthItem(HealthId.BLUETOOTH_PERMISSION, CarBluetooth.hasPermission(context))
        items += HealthItem(HealthId.BLUETOOTH_ON, CarBluetooth.isOn(context))
        items += HealthItem(HealthId.CAR, s.carAddress != null)
        items += HealthItem(HealthId.BATTERY, batteryExempt(context))
        if (CarBluetooth.supportsLink(context)) {
            items += HealthItem(HealthId.LINK, CarBluetooth.isLinked(context, s.carAddress), required = false)
        }
        items += HealthItem(HealthId.AUTO_RECORD, s.autoRecord)
        items += HealthItem(HealthId.NOTIFICATIONS, notificationsOn(context), required = false)
        return items
    }
}
