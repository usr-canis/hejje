package com.canis.hejje.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.data.toPlace
import com.canis.hejje.domain.Places
import com.canis.hejje.ui.components.CardShape
import com.canis.hejje.ui.components.EmptyState
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.LocalUndo
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.map.MapCircle
import com.canis.hejje.ui.map.TripMap
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun iconLabel(key: String) = when (key) {
    "home" -> R.string.place_icon_home
    "work" -> R.string.place_icon_work
    "school" -> R.string.place_icon_school
    "gym" -> R.string.place_icon_gym
    "shop" -> R.string.place_icon_shop
    "family" -> R.string.place_icon_family
    "star" -> R.string.place_icon_star
    else -> R.string.place_icon_pin
}

/** Homes, workplaces and family addresses are private by default; other places are not. */
private fun privateByDefault(icon: String) = icon in setOf("home", "work", "family")

/** Places: named spots used for trip titles, automatic tags and (when private) hiding routes. */
@Composable
fun ZonesScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val dao = container.db.zones()
    val zones by dao.observe().collectAsStateWithLifecycle(emptyList())
    val trips by container.trips.trips.collectAsStateWithLifecycle(emptyList())
    val tags by container.trips.tags.collectAsStateWithLifecycle(emptyList())
    val s by container.settings.data.collectAsStateWithLifecycle()
    val f = rememberFormatter(s.miles)
    val scope = rememberCoroutineScope()
    val undo = LocalUndo.current
    val haptics = rememberHaptics()
    var editing by remember { mutableStateOf<ZoneEntity?>(null) }
    val removed = stringResource(R.string.zones_removed)
    val undoLabel = stringResource(R.string.action_undo)
    val homeName = stringResource(R.string.zones_default_name)
    val workName = stringResource(R.string.zones_default_work)
    val suggestions = remember(trips, zones) {
        Places.suggest(trips.map { Triple(it.trip.endLat, it.trip.endLon, it.trip.endPlace) }, zones.map { it.toPlace() })
    }

    fun newPlace(lat: Double, lon: Double, name: String? = null): ZoneEntity {
        val icon = when {
            zones.none { it.icon == "home" } -> "home"
            zones.none { it.icon == "work" } -> "work"
            else -> "pin"
        }
        val defaultName = when (icon) {
            "home" -> homeName
            "work" -> workName
            else -> ""
        }
        return ZoneEntity(0, name ?: defaultName, lat, lon, if (name != null) 150.0 else 300.0, icon, privateByDefault(icon))
    }

    val current = editing
    if (current != null) {
        PlaceEditor(current, tags.map { it.name }, onCancel = { editing = null }) { zone ->
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
                    // Start where it is most likely wanted: the parked car or the last trip's end.
                    val last = container.trips.latest()
                    editing = newPlace(s.parkedLat ?: last?.endLat ?: 20.0, s.parkedLon ?: last?.endLon ?: 78.0)
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
            if (suggestions.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.places_suggested), Modifier.padding(horizontal = ScreenPadding)) }
                items(suggestions, key = { "s-${it.lat}-${it.lon}" }) { sug ->
                    Row(Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HejjeIcons.Pin, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sug.name ?: f.coordinates(sug.lat, sug.lon), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                pluralStringResource(R.plurals.visit_count, sug.visits, sug.visits) + " · " + stringResource(R.string.places_suggested_hint),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { editing = newPlace(sug.lat, sug.lon, sug.name?.substringBefore(", ")) }) {
                            Text(stringResource(R.string.action_add), color = LocalPalette.current.accentText)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            if (zones.isEmpty()) item { EmptyState(stringResource(R.string.zones_empty)) }
            items(zones, key = { it.id }) { zone ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = zone }.padding(start = ScreenPadding, end = 8.dp).heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HejjeIcons.place(zone.icon), null, Modifier.size(22.dp), tint = LocalPalette.current.accent)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(zone.name, style = MaterialTheme.typography.bodyLarge)
                        val details = listOfNotNull(
                            stringResource(R.string.zones_radius_value, f.distance(zone.radiusM)),
                            if (zone.hide) stringResource(R.string.place_private) else null,
                            zone.tag,
                        ).joinToString(" · ")
                        Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        haptics.confirm()
                        scope.launch {
                            dao.delete(zone.id)
                            val result = undo.snackbar.showSnackbar(removed, undoLabel)
                            if (result == SnackbarResult.ActionPerformed) dao.upsert(zone)
                        }
                    }) { Icon(HejjeIcons.Trash, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** Move the map so the cross is on the place, then choose name, icon, size, privacy and tag. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditor(zone: ZoneEntity, tags: List<String>, onCancel: () -> Unit, onSave: (ZoneEntity) -> Unit) {
    val container = LocalContainer.current
    val s by container.settings.data.collectAsStateWithLifecycle()
    val f = rememberFormatter(s.miles)
    val palette = LocalPalette.current
    var name by remember { mutableStateOf(zone.name) }
    var icon by remember { mutableStateOf(zone.icon) }
    var hide by remember { mutableStateOf(zone.hide) }
    var tag by remember { mutableStateOf(zone.tag) }
    var radius by remember { mutableFloatStateOf(zone.radiusM.toFloat()) }
    var centre by remember { mutableStateOf(zone.lat to zone.lon) }
    val start = remember { zone.lat to zone.lon }
    val iconNames = Places.ICONS.associateWith { stringResource(iconLabel(it)) }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        HejjeTopBar(stringResource(if (zone.id == 0L) R.string.zones_add else R.string.zones_edit), onCancel)
        Box(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = ScreenPadding).clip(CardShape)) {
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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(top = 12.dp)) {
            Text(stringResource(R.string.zones_editor_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                name, { name = it.take(30) }, singleLine = true, label = { Text(stringResource(R.string.zones_name)) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Text(stringResource(R.string.place_icon), style = MaterialTheme.typography.labelMedium, color = palette.accentText, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Places.ICONS.forEach { key ->
                    FilterChip(
                        selected = icon == key,
                        onClick = {
                            // A name that only described the old icon follows the new one.
                            if (name.isBlank() || name == iconNames[icon]) name = iconNames[key].orEmpty().takeIf { key != "pin" }.orEmpty()
                            if (zone.id == 0L) hide = privateByDefault(key)
                            icon = key
                        },
                        label = { Text(iconNames[key].orEmpty()) },
                        leadingIcon = { Icon(HejjeIcons.place(key), null, Modifier.size(18.dp)) },
                        colors = chipColors,
                    )
                }
            }
            Text(
                stringResource(R.string.zones_radius_value, f.distance(radius.toDouble())),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp),
            )
            Slider(
                value = radius, onValueChange = { radius = (it / 50).roundToInt() * 50f }, valueRange = 50f..2000f,
                colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent),
            )
            Row(
                Modifier.fillMaxWidth().toggleable(hide, role = Role.Switch) { hide = it }.padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.place_private), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.place_private_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(hide, null, colors = SwitchDefaults.colors(checkedTrackColor = palette.accent, checkedThumbColor = palette.onAccent))
            }
            Text(stringResource(R.string.place_tag), style = MaterialTheme.typography.labelMedium, color = palette.accentText, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tag == null, onClick = { tag = null }, label = { Text(stringResource(R.string.place_tag_none)) }, colors = chipColors)
                tags.forEach { t -> FilterChip(selected = tag == t, onClick = { tag = t }, label = { Text(t) }, colors = chipColors) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(zone.copy(name = name.trim(), lat = centre.first, lon = centre.second, radiusM = radius.toDouble(), icon = icon, hide = hide, tag = tag))
                },
            ) { Text(stringResource(R.string.action_save), color = palette.accentText) }
        }
    }
}
