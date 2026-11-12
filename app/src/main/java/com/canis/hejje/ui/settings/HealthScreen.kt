package com.canis.hejje.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.recording.Health
import com.canis.hejje.recording.HealthId
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.launch

private fun Context.activity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun Context.openAppSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Increases every time the screen is resumed, so checks re-run after a trip to system settings. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return tick
}

/**
 * Returns a function that starts the right fix for a health item: a permission prompt, a system
 * settings page or one of the app's own screens. [onDone] runs when a prompt has been answered.
 */
@SuppressLint("BatteryLife") // a drive recorder must run in the background; the user is asked explicitly
@Composable
fun rememberFixer(onCar: () -> Unit, onDone: () -> Unit = {}): (HealthId) -> Unit {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var asked by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Denied without a prompt: Android will not ask again, so open the app's settings page instead.
        val activity = context.activity()
        val blocked = result.filterValues { !it }.keys.any { activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (blocked && asked > 1) context.openAppSettings()
        onDone()
    }
    val settingsPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onDone() }
    fun open(intent: Intent) {
        runCatching { settingsPage.launch(intent) }.onFailure { context.openAppSettings() }
    }
    return { id ->
        asked++
        when (id) {
            HealthId.LOCATION -> permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            HealthId.BACKGROUND_LOCATION -> when {
                !Health.hasFine(context) -> permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                Build.VERSION.SDK_INT >= 29 -> permissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                else -> onDone()
            }
            HealthId.LOCATION_ON -> open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            HealthId.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 && asked <= 2) {
                permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            HealthId.BLUETOOTH_PERMISSION -> if (Build.VERSION.SDK_INT >= 31) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) else onDone()
            HealthId.BLUETOOTH_ON -> open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            HealthId.CAR, HealthId.LINK -> onCar()
            HealthId.BATTERY -> open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
            HealthId.AUTO_RECORD -> scope.launch {
                container.settings.update { it.copy(autoRecord = true) }
                onDone()
            }
        }
    }
}

private class HealthText(val title: Int, val ok: Int, val bad: Int)

private fun text(id: HealthId) = when (id) {
    HealthId.LOCATION -> HealthText(R.string.health_location, R.string.health_location_ok, R.string.health_location_bad)
    HealthId.BACKGROUND_LOCATION -> HealthText(R.string.health_background, R.string.health_background_ok, R.string.health_background_bad)
    HealthId.LOCATION_ON -> HealthText(R.string.health_location_on, R.string.health_location_on_ok, R.string.health_location_on_bad)
    HealthId.NOTIFICATIONS -> HealthText(R.string.health_notifications, R.string.health_notifications_ok, R.string.health_notifications_bad)
    HealthId.BLUETOOTH_PERMISSION -> HealthText(R.string.health_bt_permission, R.string.health_bt_permission_ok, R.string.health_bt_permission_bad)
    HealthId.BLUETOOTH_ON -> HealthText(R.string.health_bt_on, R.string.health_bt_on_ok, R.string.health_bt_on_bad)
    HealthId.CAR -> HealthText(R.string.health_car, R.string.health_car_ok, R.string.health_car_bad)
    HealthId.LINK -> HealthText(R.string.health_link, R.string.health_link_ok, R.string.health_link_bad)
    HealthId.BATTERY -> HealthText(R.string.health_battery, R.string.health_battery_ok, R.string.health_battery_bad)
    HealthId.AUTO_RECORD -> HealthText(R.string.health_auto, R.string.health_auto_ok, R.string.health_auto_bad)
}

@Composable
fun HealthScreen(onBack: () -> Unit, onCar: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val s by container.settings.data.collectAsStateWithLifecycle()
    val f = rememberFormatter(s.miles)
    val scope = rememberCoroutineScope()
    val resume = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val fix = rememberFixer(onCar) { refresh++ }
    val items = remember(resume, refresh, s) { Health.check(context, s) }
    val blocking = items.count { !it.ok && it.required }

    Column(Modifier.fillMaxSize()) {
        HejjeTopBar(stringResource(R.string.settings_health), onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp)) {
            item {
                Text(
                    stringResource(if (blocking == 0) R.string.health_all_good else R.string.health_needs_attention),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.health_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            items(items, key = { it.id }) { item ->
                val t = text(item.id)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.ok) HejjeIcons.Ok else if (item.required) HejjeIcons.Warning else HejjeIcons.Info, null, Modifier.size(24.dp),
                        tint = if (item.ok) MaterialTheme.colorScheme.onSurfaceVariant else LocalPalette.current.accent,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(t.title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (item.id == HealthId.CAR && item.ok) s.carName.orEmpty() else stringResource(if (item.ok) t.ok else t.bad),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!item.ok) TextButton(onClick = { fix(item.id) }) { Text(stringResource(R.string.action_fix), color = LocalPalette.current.accentText) }
                }
            }
            item {
                SectionTitle(stringResource(R.string.health_history))
                Text(stringResource(R.string.health_last_start), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (s.lastAutoStart == 0L) stringResource(R.string.health_never) else f.relative(s.lastAutoStart, System.currentTimeMillis()),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val problem = s.lastProblem
                if (problem != null) {
                    Text(stringResource(R.string.health_last_problem), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        f.relative(s.lastProblemAt, System.currentTimeMillis()) + ": " + problem,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { scope.launch { container.settings.update { it.copy(lastProblem = null, lastProblemAt = 0) } } }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.action_clear), color = LocalPalette.current.accentText)
                    }
                }
                TextButton(onClick = { context.openAppSettings() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.health_open_settings), color = LocalPalette.current.accentText)
                }
            }
        }
    }
}
