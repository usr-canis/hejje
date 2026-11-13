package com.canis.hejje.ui.onboarding

import androidx.compose.ui.text.font.FontWeight
import com.canis.hejje.ui.theme.WordmarkFont
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.recording.CarBluetooth
import com.canis.hejje.recording.Health
import com.canis.hejje.recording.HealthId
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.settings.rememberFixer
import com.canis.hejje.ui.settings.rememberResumeTick
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.launch

private class Step(
    val icon: ImageVector?, val title: Int, val body: Int, val action: Int, val fix: HealthId?,
    /** True when there is nothing (left) to do for this step. */
    val done: () -> Boolean = { false },
)

/** First-run guide: explains each permission in plain words before Android asks for it. */
@Composable
fun OnboardingScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val s by container.settings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    val resume = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }

    val steps = remember {
        listOfNotNull(
            Step(null, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body, R.string.onboarding_start, null),
            Step(HejjeIcons.Pin, R.string.onboarding_location_title, R.string.onboarding_location_body, R.string.action_allow, HealthId.LOCATION) { Health.hasFine(context) },
            if (Build.VERSION.SDK_INT >= 29) {
                Step(HejjeIcons.Layers, R.string.onboarding_background_title, R.string.onboarding_background_body, R.string.onboarding_background_action, HealthId.BACKGROUND_LOCATION) {
                    Health.hasFine(context) && Health.hasBackground(context)
                }
            } else null,
            if (Build.VERSION.SDK_INT >= 33) {
                Step(HejjeIcons.Info, R.string.onboarding_notifications_title, R.string.onboarding_notifications_body, R.string.action_allow, HealthId.NOTIFICATIONS) { Health.notificationsOn(context) }
            } else null,
            Step(HejjeIcons.Bluetooth, R.string.onboarding_car_title, R.string.onboarding_car_body, R.string.action_allow, HealthId.BLUETOOTH_PERMISSION) { false },
            Step(HejjeIcons.Ok, R.string.onboarding_battery_title, R.string.onboarding_battery_body, R.string.onboarding_battery_action, HealthId.BATTERY) { Health.batteryExempt(context) },
            Step(HejjeIcons.Check, R.string.onboarding_done_title, R.string.onboarding_done_body, R.string.onboarding_finish, null),
        )
    }
    fun next() {
        if (index >= steps.size - 1) scope.launch { container.settings.update { it.copy(onboardingDone = true) } } else index++
    }
    val fix = rememberFixer(onCar = {}) { refresh++ }
    val step = steps[index.coerceIn(0, steps.size - 1)]
    val isCarStep = step.fix == HealthId.BLUETOOTH_PERMISSION
    val hasBluetooth = remember(resume, refresh) { CarBluetooth.hasPermission(context) }
    val stepDone = remember(resume, refresh, index) { step.done() }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 20.dp)) {
        AnimatedContent(index, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { i ->
            val st = steps[i.coerceIn(0, steps.size - 1)]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                if (st.icon == null) {
                    Image(painterResource(R.drawable.ic_mark), null, Modifier.size(120.dp))
                    Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.displaySmall.copy(fontFamily = WordmarkFont, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(20.dp))
                } else {
                    Icon(st.icon, null, Modifier.size(56.dp), tint = palette.accent)
                    Spacer(Modifier.height(24.dp))
                }
                Text(stringResource(st.title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(st.body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (st.fix == HealthId.BLUETOOTH_PERMISSION && hasBluetooth) {
                    Spacer(Modifier.height(16.dp))
                    val devices = remember(resume, refresh) { CarBluetooth.paired(context) }
                    if (devices.isEmpty()) {
                        Text(stringResource(R.string.car_none_paired), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    devices.forEach { d ->
                        val selected = d.address.equals(s.carAddress, ignoreCase = true)
                        Row(
                            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) {
                                scope.launch { container.settings.update { it.copy(carAddress = d.address, carName = d.name) } }
                            }.heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected, null, colors = RadioButtonDefaults.colors(selectedColor = palette.accent))
                            Spacer(Modifier.width(12.dp))
                            Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        // Small progress dots.
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            steps.indices.forEach { i ->
                androidx.compose.foundation.Canvas(Modifier.padding(horizontal = 4.dp).size(8.dp)) {
                    drawCircle(if (i == index) palette.accent else palette.accent.copy(alpha = 0.25f), radius = if (i == index) size.minDimension / 2 else size.minDimension / 3)
                }
            }
        }
        val proceed = step.fix == null || stepDone || (isCarStep && hasBluetooth)
        Button(
            onClick = { if (proceed) next() else step.fix?.let(fix) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
        ) {
            Text(
                stringResource(
                    when {
                        step.fix == null -> step.action
                        isCarStep && hasBluetooth -> if (s.carAddress != null) R.string.action_continue else R.string.onboarding_car_later
                        stepDone -> R.string.action_continue
                        else -> step.action
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (!proceed) {
            TextButton(onClick = { next() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.onboarding_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}
