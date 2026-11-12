package com.canis.hejje.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.canis.hejje.HejjeApp
import com.canis.hejje.R
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Labels

/** A [Formatter] whose unit words come from string resources. */
fun Context.formatter(miles: Boolean = (applicationContext as HejjeApp).container.settings.current.miles): Formatter = Formatter(
    miles,
    Labels(
        km = getString(R.string.unit_km), mi = getString(R.string.unit_mi),
        kmh = getString(R.string.unit_kmh), mph = getString(R.string.unit_mph),
        metres = getString(R.string.unit_m), feet = getString(R.string.unit_ft),
        hours = getString(R.string.unit_h), minutes = getString(R.string.unit_min), seconds = getString(R.string.unit_s),
        justNow = getString(R.string.time_just_now), minutesAgo = getString(R.string.time_minutes_ago),
        hoursAgo = getString(R.string.time_hours_ago), today = getString(R.string.time_today),
        yesterday = getString(R.string.time_yesterday),
    ),
)

@Composable
fun rememberFormatter(miles: Boolean): Formatter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(miles, configuration) { context.formatter(miles) }
}
