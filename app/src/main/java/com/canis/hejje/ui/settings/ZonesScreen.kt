package com.canis.hejje.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.ui.components.CardShape
import com.canis.hejje.ui.components.EmptyState
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.LocalUndo
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.map.MapCircle
import com.canis.hejje.ui.map.TripMap
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ZonesScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val dao = container.db.zones()
    val zones by dao.observe().collectAsStateWithLifecycle(emptyList())
    val s by container.settings.data.collectAsStateWithLifecycle()
    val f = rememberFormatter(s.miles)
    val scope = rememberCoroutineScope()
    val undo = LocalUndo.current
    val haptics = rememberHaptics()
    var editing by remember { mutableStateOf<ZoneEntity?>(null) }
    val removed = stringResource(R.string.zones_removed)
    val undoLabel = stringResource(R.string.action_undo)
    val defaultName = stringResource(R.string.zones_default_name)

    val current = editing
    if (current != null) {
        ZoneEditor(current, onCancel = { editing = null }) { zone ->
            scope.launch { dao.upsert(zone) }
            haptics.confirm()
            editing = null
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        HejjeTopBar(stringResource(R.string.settings_zones), onBack) {
            IconButton(onClick = {
                scope.launch {
                    // Start the new zone where it is most likely wanted: the parked car or the last trip's end.
                    val last = container.trips.latest()
                    editing = ZoneEntity(0, defaultName, s.parkedLat ?: last?.endLat ?: 20.0, s.parkedLon ?: last?.endLon ?: 78.0, 300.0)
                }
            }) { Icon(HejjeIcons.Plus, stringResource(R.string.zones_add)) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    stringResource(R.string.zones_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
                )
            }
            if (zones.isEmpty()) item { EmptyState(stringResource(R.string.zones_empty)) }
            items(zones, key = { it.id }) { zone ->
                Row(Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HejjeIcons.Pin, null, Modifier.size(22.dp), tint = LocalPalette.current.accent)
                    SettingRowBody(zone.name, stringResource(R.string.zones_radius_value, f.distance(zone.radiusM)), Modifier.weight(1f)) { editing = zone }
                    IconButton(onClick = {
                        haptics.confirm()
                        scope.launch {
                            dao.delete(zone.id)
                            // Zones are not trips, so their Undo is handled here.
                            val result = undo.snackbar.showSnackbar(removed, undoLabel)
                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) dao.upsert(zone)
                        }
                    }) { Icon(HejjeIcons.Trash, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun SettingRowBody(title: String, summary: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Move the map so the crosshair is on the place, then choose a name and a radius. */
@Composable
private fun ZoneEditor(zone: ZoneEntity, onCancel: () -> Unit, onSave: (ZoneEntity) -> Unit) {
    val container = LocalContainer.current
    val s by container.settings.data.collectAsStateWithLifecycle()
    val f = rememberFormatter(s.miles)
    val palette = LocalPalette.current
    var name by remember { mutableStateOf(zone.name) }
    var radius by remember { mutableFloatStateOf(zone.radiusM.toFloat()) }
    var centre by remember { mutableStateOf(zone.lat to zone.lon) }
    val start = remember { zone.lat to zone.lon }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        HejjeTopBar(stringResource(if (zone.id == 0L) R.string.zones_add else R.string.zones_edit), onCancel)
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = ScreenPadding).clip(CardShape)) {
            TripMap(
                routes = emptyList(), modifier = Modifier.fillMaxSize(), center = start,
                circles = listOf(MapCircle(centre.first, centre.second, radius.toDouble())),
                onCenterChanged = { lat, lon -> centre = lat to lon },
                description = stringResource(R.string.zones_map_description),
            )
            Canvas(Modifier.align(Alignment.Center).size(28.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                drawLine(palette.accentText, Offset(c.x, 0f), Offset(c.x, size.height), 2.dp.toPx())
                drawLine(palette.accentText, Offset(0f, c.y), Offset(size.width, c.y), 2.dp.toPx())
            }
        }
        Column(Modifier.padding(horizontal = ScreenPadding).padding(top = 16.dp, bottom = 12.dp)) {
            Text(stringResource(R.string.zones_editor_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                name, { name = it.take(30) }, singleLine = true, label = { Text(stringResource(R.string.zones_name)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.zones_radius_value, f.distance(radius.toDouble())),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 14.dp),
            )
            Slider(
                value = radius, onValueChange = { radius = (it / 50).roundToInt() * 50f }, valueRange = 100f..2000f,
                colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent),
            )
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onSave(zone.copy(name = name.trim(), lat = centre.first, lon = centre.second, radiusM = radius.toDouble())) },
                ) { Text(stringResource(R.string.action_save), color = palette.accentText) }
            }
        }
    }
}
