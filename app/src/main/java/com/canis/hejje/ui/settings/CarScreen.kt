package com.canis.hejje.ui.settings

import android.app.Activity
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.recording.CarBluetooth
import com.canis.hejje.recording.CarDevice
import com.canis.hejje.recording.HealthId
import com.canis.hejje.ui.components.EmptyState
import com.canis.hejje.ui.components.HejjeCard
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** Starts Android's companion-device pairing for [address]. [onResult] gets true when the car is linked. */
@Composable
fun rememberCarLinker(onResult: (Boolean) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val address = pending
        pending = null
        val ok = result.resultCode == Activity.RESULT_OK
        if (ok) CarBluetooth.observe(context, address)
        onResult(ok && CarBluetooth.isLinked(context, address))
    }
    return link@{ address ->
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null || !CarBluetooth.supportsLink(context)) {
            onResult(false)
            return@link
        }
        pending = address
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

            @Deprecated("Used before Android 13")
            override fun onDeviceFound(intentSender: IntentSender) {
                if (Build.VERSION.SDK_INT < 33) launch(intentSender)
            }

            private fun launch(intentSender: IntentSender) {
                runCatching { launcher.launch(IntentSenderRequest.Builder(intentSender).build()) }.onFailure { onResult(false) }
            }

            override fun onFailure(error: CharSequence?) {
                pending = null
                onResult(false)
            }
        }
        runCatching { cdm.associate(CarBluetooth.associationRequest(address), callback, null) }.onFailure { onResult(false) }
    }
}

@Composable
fun CarScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val s by container.settings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val resume = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    var linkFailed by remember { mutableStateOf(false) }
    val fix = rememberFixer(onCar = {}) { refresh++ }
    val hasPermission = remember(resume, refresh) { CarBluetooth.hasPermission(context) }
    val devices: List<CarDevice> = remember(resume, refresh, hasPermission) { CarBluetooth.paired(context) }
    val linked = remember(resume, refresh, s.carAddress) { CarBluetooth.isLinked(context, s.carAddress) }
    val link = rememberCarLinker { ok ->
        linkFailed = !ok
        refresh++
    }

    Column(Modifier.fillMaxSize()) {
        HejjeTopBar(stringResource(R.string.settings_car), onBack)
        if (!hasPermission) {
            EmptyState(stringResource(R.string.car_need_permission)) {
                TextButton(onClick = { fix(HealthId.BLUETOOTH_PERMISSION) }) { Text(stringResource(R.string.action_allow), color = LocalPalette.current.accentText) }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    stringResource(R.string.car_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
                )
            }
            if (devices.isEmpty()) item { EmptyState(stringResource(R.string.car_none_paired)) }
            items(devices, key = { it.address }) { d ->
                val selected = d.address.equals(s.carAddress, ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) {
                        haptics.confirm()
                        linkFailed = false
                        scope.launch {
                            if (!selected) CarBluetooth.unlink(context, s.carAddress)
                            container.settings.update { it.copy(carAddress = d.address, carName = d.name) }
                            refresh++
                        }
                    }.heightIn(min = 56.dp).padding(horizontal = ScreenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected, null, colors = RadioButtonDefaults.colors(selectedColor = LocalPalette.current.accent))
                    Spacer(Modifier.width(14.dp))
                    Text(d.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
            val address = s.carAddress
            if (address != null && CarBluetooth.supportsLink(context)) {
                item {
                    HejjeCard(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(top = 20.dp)) {
                        Text(stringResource(if (linked) R.string.car_linked_title else R.string.car_link_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                when {
                                    linked -> R.string.car_linked_text
                                    linkFailed -> R.string.car_link_failed
                                    else -> R.string.car_link_text
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                        )
                        if (!linked) {
                            TextButton(onClick = { linkFailed = false; link(address) }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(if (linkFailed) R.string.action_retry else R.string.car_link_action), color = LocalPalette.current.accentText)
                            }
                        }
                    }
                }
            }
            if (address != null) {
                item {
                    TextButton(
                        onClick = {
                            scope.launch {
                                CarBluetooth.unlink(context, address)
                                container.settings.update { it.copy(carAddress = null, carName = null) }
                                refresh++
                            }
                        },
                        modifier = Modifier.padding(horizontal = ScreenPadding - 12.dp).padding(top = 8.dp),
                    ) { Text(stringResource(R.string.car_forget), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
