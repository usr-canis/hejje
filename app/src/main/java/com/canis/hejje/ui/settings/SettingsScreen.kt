package com.canis.hejje.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.BuildConfig
import com.canis.hejje.R
import com.canis.hejje.data.BackupManager
import com.canis.hejje.data.SettingsData
import com.canis.hejje.ui.Routes
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.LocalUndo
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.map.MapEngine
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.trips.updateErrorText
import com.canis.hejje.update.UpdateState
import com.canis.hejje.widget.HejjeWidget
import kotlinx.coroutines.launch

private enum class Picker { MERGE_GAP, MIN_TRIP, GPS, SENSITIVITY, UNITS, THEME, CACHE, SUMMARY, KEEP }

@Composable
fun SettingsScreen(onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val s by container.settings.data.collectAsStateWithLifecycle()
    val update by container.updater.state.collectAsStateWithLifecycle()
    val zones by container.db.zones().observe().collectAsStateWithLifecycle(emptyList())
    val f = rememberFormatter(s.miles)
    val scope = rememberCoroutineScope()
    val undo = LocalUndo.current
    val haptics = rememberHaptics()
    var picker by remember { mutableStateOf<Picker?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun set(change: (SettingsData) -> SettingsData) {
        scope.launch { container.settings.update(change) }
    }

    val msgExported = stringResource(R.string.settings_exported)
    val msgFailed = stringResource(R.string.settings_file_failed)
    val msgCacheCleared = stringResource(R.string.settings_cache_cleared)
    val msgDeleted = stringResource(R.string.settings_all_deleted)
    val importResult = stringResource(R.string.settings_import_result)

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch { undo.show(if (runCatching { container.backup.export(uri) }.isSuccess) msgExported else msgFailed) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = runCatching { container.backup.import(uri) }.getOrNull()
            if (r != null) {
                com.canis.hejje.work.PlaceWorker.enqueue(context)
                HejjeWidget.refresh(context)
            }
            undo.show(if (r == null) msgFailed else String.format(importResult, r.added, r.duplicates, r.failed))
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            scope.launch {
                container.settings.update { it.copy(backupUri = uri.toString()) }
                runCatching { container.backup.autoBackup() }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        HejjeTopBar(stringResource(R.string.tab_settings))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Header(R.string.settings_recording, first = true)
                SettingRow(stringResource(R.string.settings_car), s.carName ?: stringResource(R.string.settings_car_none), chevron = true) { onOpen(Routes.CAR) }
                SwitchRow(stringResource(R.string.settings_auto_record), stringResource(R.string.settings_auto_record_summary), s.autoRecord) { v -> set { it.copy(autoRecord = v) } }
                SettingRow(stringResource(R.string.settings_health), stringResource(R.string.settings_health_summary), chevron = true) { onOpen(Routes.HEALTH) }
                SettingRow(stringResource(R.string.settings_merge_gap), mergeGapLabel(s.mergeGapMin)) { picker = Picker.MERGE_GAP }
                SettingRow(
                    stringResource(R.string.settings_min_trip),
                    if (s.minTripM == 0) stringResource(R.string.settings_min_trip_none) else f.distance(s.minTripM.toDouble()),
                ) { picker = Picker.MIN_TRIP }
                SettingRow(stringResource(R.string.settings_gps), stringResource(if (s.gpsMode == 0) R.string.settings_gps_best else R.string.settings_gps_saver)) { picker = Picker.GPS }
                SettingRow(stringResource(R.string.settings_sensitivity), stringResource(sensitivityLabels[s.brakeSensitivity.coerceIn(0, 2)])) { picker = Picker.SENSITIVITY }
            }
            item {
                Header(R.string.settings_display)
                SettingRow(stringResource(R.string.settings_units), stringResource(if (s.miles) R.string.settings_units_mi else R.string.settings_units_km)) { picker = Picker.UNITS }
                SettingRow(stringResource(R.string.settings_theme), stringResource(themeLabels[s.theme.coerceIn(0, 2)])) { picker = Picker.THEME }
                SwitchRow(stringResource(R.string.settings_route_speed), stringResource(R.string.settings_route_speed_summary), s.routeBySpeed) { v -> set { it.copy(routeBySpeed = v) } }
            }
            item {
                Header(R.string.settings_privacy)
                SettingRow(
                    stringResource(R.string.settings_zones),
                    if (zones.isEmpty()) stringResource(R.string.settings_zones_none) else pluralStringResource(R.plurals.zone_count, zones.size, zones.size),
                    chevron = true,
                ) { onOpen(Routes.ZONES) }
                SwitchRow(stringResource(R.string.settings_zones_in_app), stringResource(R.string.settings_zones_in_app_summary), s.zonesInApp) { v -> set { it.copy(zonesInApp = v) } }
                SwitchRow(stringResource(R.string.settings_place_lookup), stringResource(R.string.settings_place_lookup_summary), s.placeLookup) { v ->
                    set { it.copy(placeLookup = v) }
                    if (v) com.canis.hejje.work.PlaceWorker.enqueue(context)
                }
            }
            item {
                Header(R.string.settings_maps)
                SettingRow(stringResource(R.string.settings_cache), stringResource(R.string.settings_cache_value, s.tileCacheMb)) { picker = Picker.CACHE }
                SettingRow(stringResource(R.string.settings_cache_clear)) { MapEngine.clearCache(context); undo.show(msgCacheCleared) }
            }
            item {
                Header(R.string.settings_notifications)
                SettingRow(stringResource(R.string.settings_summary), stringResource(summaryLabels[s.summaryTiming.coerceIn(0, 3)])) { picker = Picker.SUMMARY }
                SwitchRow(stringResource(R.string.settings_widget_last), stringResource(R.string.settings_widget_last_summary), s.widgetLastTrip) { v ->
                    set { it.copy(widgetLastTrip = v) }
                    HejjeWidget.refresh(context)
                }
            }
            item {
                Header(R.string.settings_backup)
                SettingRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_summary)) { export.launch(BackupManager.backupName()) }
                SettingRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_summary)) { import.launch(arrayOf("*/*")) }
                val folderName = s.backupUri?.let { runCatching { DocumentFile.fromTreeUri(context, it.toUri())?.name }.getOrNull() }
                SettingRow(
                    stringResource(R.string.settings_auto_backup),
                    when {
                        s.backupUri == null -> stringResource(R.string.settings_auto_backup_off)
                        s.lastBackup == 0L -> stringResource(R.string.settings_auto_backup_on, folderName ?: "")
                        else -> stringResource(R.string.settings_auto_backup_last, folderName ?: "", f.relative(s.lastBackup, System.currentTimeMillis()))
                    },
                ) { folder.launch(null) }
                if (s.backupUri != null) {
                    SettingRow(stringResource(R.string.settings_backup_keep), pluralStringResource(R.plurals.backup_count, s.backupKeep, s.backupKeep)) { picker = Picker.KEEP }
                    SettingRow(stringResource(R.string.settings_auto_backup_stop)) { set { it.copy(backupUri = null) } }
                }
            }
            item {
                Header(R.string.settings_updates)
                SwitchRow(stringResource(R.string.settings_update_auto), stringResource(R.string.settings_update_auto_summary), s.updateAutoCheck) { v -> set { it.copy(updateAutoCheck = v) } }
                SwitchRow(stringResource(R.string.settings_update_notify), null, s.updateNotify) { v -> set { it.copy(updateNotify = v) } }
                SwitchRow(stringResource(R.string.settings_update_wifi), null, s.updateWifiOnly) { v -> set { it.copy(updateWifiOnly = v) } }
                SettingRow(
                    stringResource(R.string.settings_update_check),
                    when (val u = update) {
                        UpdateState.Checking -> stringResource(R.string.settings_update_checking)
                        UpdateState.UpToDate -> stringResource(R.string.settings_update_latest, BuildConfig.VERSION_NAME)
                        is UpdateState.Available -> stringResource(R.string.settings_update_found, u.info.version)
                        is UpdateState.Downloading -> stringResource(R.string.update_downloading, (u.progress * 100).toInt())
                        is UpdateState.Installing -> stringResource(R.string.update_confirm_hint)
                        is UpdateState.Failed -> stringResource(updateErrorText(u.error))
                        UpdateState.Idle -> stringResource(R.string.settings_update_current, BuildConfig.VERSION_NAME)
                    },
                ) {
                    haptics.tick()
                    val available = (update as? UpdateState.Available)?.info
                    if (available != null) container.updater.download(available) else scope.launch { container.updater.check(manual = true) }
                }
            }
            item {
                Header(R.string.settings_data)
                SettingRow(stringResource(R.string.settings_delete_all), stringResource(R.string.settings_delete_all_summary)) { confirmDelete = true }
                SettingRow(stringResource(R.string.settings_about), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), chevron = true) { onOpen(Routes.ABOUT) }
            }
        }
    }

    when (picker) {
        Picker.MERGE_GAP -> ChoiceDialog(
            stringResource(R.string.settings_merge_gap), listOf(0, 15, 30, 60, 90, 120, 180, 240).map { it to mergeGapLabel(it) }, s.mergeGapMin,
            { v -> set { it.copy(mergeGapMin = v) } }, { picker = null },
        )
        Picker.MIN_TRIP -> ChoiceDialog(
            stringResource(R.string.settings_min_trip),
            listOf(0, 100, 250, 500, 1000, 2000).map { it to if (it == 0) stringResource(R.string.settings_min_trip_none) else f.distance(it.toDouble()) },
            s.minTripM, { v -> set { it.copy(minTripM = v) } }, { picker = null },
        )
        Picker.GPS -> ChoiceDialog(
            stringResource(R.string.settings_gps),
            listOf(0 to stringResource(R.string.settings_gps_best_long), 1 to stringResource(R.string.settings_gps_saver_long)),
            s.gpsMode, { v -> set { it.copy(gpsMode = v) } }, { picker = null },
        )
        Picker.SENSITIVITY -> ChoiceDialog(
            stringResource(R.string.settings_sensitivity), sensitivityLabels.mapIndexed { i, l -> i to stringResource(l) },
            s.brakeSensitivity, { v -> set { it.copy(brakeSensitivity = v) } }, { picker = null },
        )
        Picker.UNITS -> ChoiceDialog(
            stringResource(R.string.settings_units),
            listOf(false to stringResource(R.string.settings_units_km), true to stringResource(R.string.settings_units_mi)),
            s.miles, { v -> set { it.copy(miles = v) }; HejjeWidget.refresh(context) }, { picker = null },
        )
        Picker.THEME -> ChoiceDialog(
            stringResource(R.string.settings_theme), themeLabels.mapIndexed { i, l -> i to stringResource(l) },
            s.theme, { v -> set { it.copy(theme = v) } }, { picker = null },
        )
        Picker.CACHE -> ChoiceDialog(
            stringResource(R.string.settings_cache), listOf(50, 100, 200, 500, 1000).map { it to stringResource(R.string.settings_cache_value, it) },
            s.tileCacheMb, { v -> set { it.copy(tileCacheMb = v) }; MapEngine.setCacheSize(context, v) }, { picker = null },
        )
        Picker.SUMMARY -> ChoiceDialog(
            stringResource(R.string.settings_summary), summaryLabels.mapIndexed { i, l -> i to stringResource(l) },
            s.summaryTiming, { v -> set { it.copy(summaryTiming = v) } }, { picker = null },
        )
        Picker.KEEP -> ChoiceDialog(
            stringResource(R.string.settings_backup_keep), listOf(2, 4, 8, 12).map { it to pluralStringResource(R.plurals.backup_count, it, it) },
            s.backupKeep, { v -> set { it.copy(backupKeep = v) } }, { picker = null },
        )
        null -> Unit
    }

    if (confirmDelete) {
        // The one place with a confirmation: wiping everything cannot be undone.
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.settings_delete_all), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.settings_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    haptics.confirm()
                    scope.launch {
                        container.trips.deleteEverything()
                        container.settings.update { it.copy(parkedLat = null, parkedLon = null, parkedAt = 0) }
                        HejjeWidget.refresh(context)
                        undo.show(msgDeleted)
                    }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

@Composable
private fun Header(text: Int, first: Boolean = false) {
    SectionTitle(stringResource(text), Modifier.padding(horizontal = ScreenPadding).padding(top = if (first) 0.dp else 4.dp))
}

@Composable
private fun mergeGapLabel(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.settings_merge_gap_off)
    minutes < 60 -> pluralStringResource(R.plurals.minute_count, minutes, minutes)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.hour_count, minutes / 60, minutes / 60)
    else -> stringResource(R.string.settings_hours_minutes, minutes / 60, minutes % 60)
}

private val sensitivityLabels = listOf(R.string.settings_sensitivity_low, R.string.settings_sensitivity_normal, R.string.settings_sensitivity_high)
private val themeLabels = listOf(R.string.settings_theme_system, R.string.settings_theme_light, R.string.settings_theme_dark)
private val summaryLabels = listOf(R.string.settings_summary_now, R.string.settings_summary_5, R.string.settings_summary_final, R.string.settings_summary_off)
