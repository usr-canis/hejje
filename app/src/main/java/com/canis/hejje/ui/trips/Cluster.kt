package com.canis.hejje.ui.trips

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canis.hejje.R
import com.canis.hejje.data.SettingsData
import com.canis.hejje.domain.Formatter
import com.canis.hejje.recording.LiveState
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.PulsingDot
import com.canis.hejje.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** The cluster is always dark, like a real dashboard, in both app themes. */
private object Dash {
    val panelTop = Color(0xFF24282C)
    val panelBottom = Color(0xFF15181A)
    val edge = Color.White.copy(alpha = 0.06f)
    val highlight = Color.White.copy(alpha = 0.08f)
    val text = Color(0xFFECE9E6)
    val muted = Color(0xFF8E8984)
    val accent = Color(0xFFFF7A33)
    val window = Color(0xFF0B0C0D)
    val drumLight = Color(0xFF3A3F45)
    val drumDark = Color(0xFF1A1D20)
}

/**
 * Top of the Trips tab, styled as an instrument cluster: the wordmark, this week's distance on a
 * rolling odometer, this week's trip count, and the parked car as a lit "P" (or the live drive).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstrumentCluster(
    weekDistanceM: Double,
    weekTrips: Int,
    settings: SettingsData,
    live: LiveState?,
    f: Formatter,
    onParked: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val radius = 24.dp
    val shape = RoundedCornerShape(radius)
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier.fillMaxWidth()
            // A soft, short shadow that fades out within the spacing around the panel.
            .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Dash.panelTop, Dash.panelBottom)))
            .border(1.dp, Dash.edge, shape)
            .drawBehind {
                // A thin light edge along the top, as on a raised panel.
                val inset = radius.toPx()
                drawLine(Dash.highlight, Offset(inset, 1f), Offset(size.width - inset, 1f), 1.dp.toPx())
            }
            .padding(start = 22.dp, end = 18.dp, top = 22.dp, bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicText(
                stringResource(R.string.brand_name),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Medium, color = Dash.text,
                    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 6f),
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 72.sp, stepSize = 2.sp),
                modifier = Modifier.fillMaxWidth(0.92f).semantics { heading() },
            )
        }
        Column(Modifier.weight(1.3f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.this_week).uppercase(locale),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.6.sp),
                color = Dash.accent, maxLines = 1,
            )
            Odometer(weekDistanceM, f)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TripChip(weekTrips)
                when {
                    live != null -> {
                        LiveChip(live, f)
                        Chip(onClick = onStop, description = stringResource(R.string.action_stop)) {
                            Icon(HejjeIcons.Stop, null, Modifier.size(14.dp), tint = Dash.text)
                            Text(stringResource(R.string.action_stop), style = chipText, color = Dash.text)
                        }
                    }
                    settings.parkedLat != null && settings.parkedLon != null -> {
                        Chip(onClick = onParked, description = stringResource(R.string.parked_open)) {
                            Indicator("P", lit = true)
                            Text(f.relative(settings.parkedAt, System.currentTimeMillis()), style = chipText, color = Dash.text, maxLines = 1)
                            Icon(HejjeIcons.ChevronRight, null, Modifier.size(14.dp), tint = Dash.muted)
                        }
                    }
                    else -> Text(
                        stringResource(if (settings.carAddress != null && settings.autoRecord) R.string.summary_idle_auto else R.string.summary_idle_setup),
                        style = chipText, color = Dash.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private val chipText = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, fontFeatureSettings = "tnum")

@Composable
private fun Chip(onClick: (() -> Unit)? = null, description: String? = null, content: @Composable RowScope.() -> Unit) {
    val clickable = if (onClick != null) {
        Modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) {
            role = Role.Button
            if (description != null) contentDescription = description
        }
    } else Modifier.semantics(mergeDescendants = true) {}
    Row(
        Modifier.clip(CircleShape).background(Dash.window).then(clickable)
            .drawWithContent {
                drawContent()
                // Recessed look: a soft shadow along the inside top edge.
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), endY = 6.dp.toPx()))
            }
            .heightIn(min = 34.dp).padding(start = 7.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable
private fun TripChip(trips: Int) {
    val label = stringResource(R.string.cluster_trip)
    Chip {
        Box(
            Modifier.border(1.dp, Dash.muted, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        ) { Text(label, style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp), color = Dash.muted) }
        Text(trips.toString(), style = chipText, color = Dash.text)
    }
}

/** A warning-light style indicator, e.g. the lit "P" for the parked car. */
@Composable
private fun Indicator(letter: String, lit: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(20.dp)
            .then(if (lit) Modifier.shadow(8.dp, shape, ambientColor = Dash.accent, spotColor = Dash.accent) else Modifier)
            .background(if (lit) Dash.accent else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) { Text(letter, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = Color.White) }
}

@Composable
private fun LiveChip(live: LiveState, f: Formatter) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Chip {
        PulsingDot(size = 6.dp)
        Text(
            if (live.hasFix) "${f.duration(now - live.startedAt)}  ·  ${f.distance(live.distanceM)}" else stringResource(R.string.notif_waiting_gps),
            style = chipText, color = Dash.text, maxLines = 1,
        )
    }
}

/**
 * Rolling odometer. Three whole-number drums (four from 1000 up) and an orange tenths drum. It
 * rolls up when first shown and, after a drive, only the drums that change turn, like a real one.
 */
@Composable
private fun Odometer(distanceM: Double, f: Formatter) {
    // Rounded to the shown 0.1, so every drum sits square once the roll has finished.
    val value = ((distanceM / if (f.miles) 1609.344 else 1000.0).coerceIn(0.0, 9999.9) * 10).roundToInt() / 10f
    val reduceMotion = LocalReduceMotion.current
    var settled by rememberSaveable { mutableFloatStateOf(0f) }
    val anim = remember { Animatable(if (reduceMotion) value else settled) }
    LaunchedEffect(value) {
        if (reduceMotion) anim.snapTo(value) else anim.animateTo(value, tween(900, easing = FastOutSlowInEasing))
        settled = value
    }
    val wholeDrums = if (value >= 999.95f) 4 else 3
    val small = wholeDrums == 4
    val positions = odometerPositions(anim.value, wholeDrums)
    val unit = (if (f.miles) f.labels.mi else f.labels.km).uppercase(LocalConfiguration.current.locales[0])
    val description = stringResource(R.string.cluster_odometer_description, f.distance(distanceM))
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Dash.window)
            .drawWithContent {
                drawContent()
                // Recessed window: shadow inside the top edge, glass glare, light lower lip.
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), endY = 8.dp.toPx()))
                val glare = Size(size.width - 12.dp.toPx(), size.height * 0.42f)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent), startY = 4.dp.toPx(), endY = 4.dp.toPx() + glare.height),
                    topLeft = Offset(6.dp.toPx(), 4.dp.toPx()), size = glare, cornerRadius = CornerRadius(8.dp.toPx()),
                )
                drawLine(Color.White.copy(alpha = 0.06f), Offset(12.dp.toPx(), size.height - 1f), Offset(size.width - 12.dp.toPx(), size.height - 1f))
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (i in wholeDrums downTo 1) Drum(positions[i], small, tenths = false)
        Box(Modifier.align(Alignment.Bottom).padding(bottom = 6.dp).size(4.dp).background(Dash.text.copy(alpha = 0.7f), CircleShape))
        Drum(positions[0], small, tenths = true)
        Text(
            unit, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp), color = Dash.muted,
            modifier = Modifier.align(Alignment.Bottom).padding(start = 3.dp, bottom = 3.dp),
        )
    }
}

/**
 * Drum positions for [value], index 0 = tenths, 1 = ones, 2 = tens and so on. Like a mechanical
 * odometer, a drum only turns while the drum below it goes from 9 to 0.
 */
internal fun odometerPositions(value: Float, wholeDrums: Int): FloatArray {
    val out = FloatArray(wholeDrums + 1)
    out[0] = (value * 10f) % 10f
    for (i in 1..wholeDrums) {
        val base = floor(value / 10f.pow(i - 1)) % 10f
        val below = out[i - 1]
        out[i] = base + if (below > 9f) below - 9f else 0f
    }
    return out
}

@Composable
private fun Drum(position: Float, small: Boolean, tenths: Boolean) {
    val width: Dp = if (small) 22.dp else 26.dp
    val height: Dp = if (small) 36.dp else 40.dp
    val density = LocalDensity.current
    // A fixed graphic: the digits do not grow with the font-size setting (the whole odometer is read aloud instead).
    val digitSize = with(density) { (if (small) 22.dp else 25.dp).toSp() }
    val digit = floor(position).toInt().mod(10)
    val fraction = position - floor(position)
    val style = TextStyle(
        fontSize = digitSize, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum",
        color = if (tenths) Color.White else Dash.text,
        shadow = if (tenths) Shadow(Color(0x99FFB478), Offset.Zero, 10f) else null,
    )
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(5.dp))
            .background(
                if (tenths) Brush.horizontalGradient(listOf(Dash.accent, Dash.accent))
                else Brush.horizontalGradient(listOf(Dash.drumDark, Dash.drumLight, Dash.drumDark)),
            ),
    ) {
        Column(
            Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
                .offset { IntOffset(0, -(fraction * height.toPx()).roundToInt()) },
        ) {
            for (d in listOf(digit, (digit + 1) % 10)) {
                Box(Modifier.size(width, height), contentAlignment = Alignment.Center) { Text(d.toString(), style = style) }
            }
        }
        // Curved-drum shading: darker towards the top and bottom edges, a soft shine across the middle.
        Box(
            Modifier.size(width, height).drawBehind {
                val dark = Color.Black.copy(alpha = if (tenths) 0.45f else 0.65f)
                drawRect(
                    Brush.verticalGradient(
                        0f to dark, 0.3f to Color.Transparent, 0.5f to Color.White.copy(alpha = if (tenths) 0.16f else 0.07f),
                        0.7f to Color.Transparent, 1f to dark,
                    ),
                )
            },
        )
    }
}
