package com.canis.hejje.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.canis.hejje.R

/**
 * Hejje on Android Auto: the live cluster (speedometer, compass with the route, trip odometer)
 * drawn by the app beside Android Auto's own card with the next stop and this trip.
 */
class HejjeCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Only Android Auto and Android Automotive (Google's signed hosts) may connect.
            HostValidator.Builder(applicationContext).addAllowedHosts(R.array.car_hosts_allowlist).build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = ClusterScreen(carContext)
    }
}
