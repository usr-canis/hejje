package com.canis.hejje.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.BuildConfig
import com.canis.hejje.R
import com.canis.hejje.ui.components.CardShape
import com.canis.hejje.ui.components.EmptyState
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.LocalUndo
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.map.MapMarker
import com.canis.hejje.ui.map.TripMap
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import java.util.Locale

/** Where the car was when its Bluetooth last disconnected, with a way back to it. */
@Composable
fun ParkedScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val s by container.settings.data.collectAsStateWithLifecycle()
    val f = rememberFormatter(s.miles)
    val scope = rememberCoroutineScope()
    val undo = LocalUndo.current
    val haptics = rememberHaptics()
    val lat = s.parkedLat
    val lon = s.parkedLon
    val noMaps = stringResource(R.string.parked_no_maps)
    val label = stringResource(R.string.parked_title)

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        HejjeTopBar(label, onBack)
        if (lat == null || lon == null) {
            EmptyState(stringResource(R.string.parked_empty))
            return@Column
        }
        var place by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(lat, lon) { place = runCatching { container.places.name(lat, lon) }.getOrNull() }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = ScreenPadding).clip(CardShape)) {
            TripMap(
                routes = emptyList(), modifier = Modifier.fillMaxSize(), markers = listOf(MapMarker(lat, lon, 3)), center = lat to lon,
                description = stringResource(R.string.parked_map_description),
            )
        }
        Column(Modifier.padding(horizontal = ScreenPadding).padding(top = 18.dp, bottom = 16.dp)) {
            Text(place ?: f.coordinates(lat, lon), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.parked_since, f.relative(s.parkedAt, System.currentTimeMillis())),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    haptics.confirm()
                    // A geo: link opens whichever maps app is the phone's default.
                    val uri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(label)).toUri()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { undo.show(noMaps) }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LocalPalette.current.accent, contentColor = LocalPalette.current.onAccent),
            ) { Text(stringResource(R.string.parked_navigate), style = MaterialTheme.typography.labelLarge) }
            TextButton(
                onClick = { scope.launch { container.settings.update { it.copy(parkedLat = null, parkedLon = null, parkedAt = 0) } }; onBack() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.parked_forget), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        HejjeTopBar(stringResource(R.string.settings_about), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(bottom = 40.dp).navigationBarsPadding(),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_mark), null, Modifier.size(96.dp))
                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.about_meaning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
            SectionTitle(stringResource(R.string.about_privacy_title))
            Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyLarge)
            SectionTitle(stringResource(R.string.about_credits_title))
            Text(stringResource(R.string.about_credits), style = MaterialTheme.typography.bodyLarge)
            listOf(
                R.string.about_link_osm to "https://www.openstreetmap.org/copyright",
                R.string.about_link_ofm to "https://openfreemap.org",
                R.string.about_link_source to "https://github.com/${BuildConfig.UPDATE_REPO}",
            ).forEach { (text, url) ->
                TextButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(stringResource(text), color = LocalPalette.current.accentText) }
            }
        }
    }
}
