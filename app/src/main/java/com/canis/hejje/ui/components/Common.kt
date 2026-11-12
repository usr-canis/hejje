package com.canis.hejje.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.data.TripRepository
import com.canis.hejje.data.Undo
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalUndo = staticCompositionLocalOf<UndoHost> { error("UndoHost not provided") }

/** Screen-edge padding used everywhere. */
val ScreenPadding = 20.dp
val CardShape = RoundedCornerShape(20.dp)

/** Shows a snackbar; with an [Undo] it offers to reverse the edit instead of asking beforehand. */
class UndoHost(
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val trips: TripRepository,
    private val undoLabel: String,
) {
    fun show(message: String, undo: Undo? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message, actionLabel = if (undo != null) undoLabel else null,
                duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && undo != null) trips.undo(undo)
        }
    }
}

class Haptics(private val view: View) {
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HejjeTopBar(title: String, onBack: (() -> Unit)? = null, titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(HejjeIcons.Back, stringResource(R.string.action_back)) }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
fun HejjeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = CardShape, color = color, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = CardShape, color = color, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.accentText,
        modifier = modifier.padding(top = 28.dp, bottom = 10.dp).semantics { heading() },
    )
}

/** A gentle pulse that says "recording". Static when the system asks for no animations. */
@Composable
fun PulsingDot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = LocalPalette.current.accent
    val pulse = if (LocalReduceMotion.current) 0f else {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse").value
    }
    val description = stringResource(R.string.recording_now)
    Canvas(modifier.size(size * 2.4f).clearAndSetSemantics { contentDescription = description }) {
        val r = size.toPx() / 2
        drawCircle(color.copy(alpha = 0.35f * (1 - pulse)), radius = r * (1f + 1.4f * pulse))
        drawCircle(color, radius = r)
    }
}

/** Label above a value: the building block of every stats grid. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueStyle: TextStyle = MaterialTheme.typography.titleLarge) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text(value, style = valueStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
    }
}

/** Counts up from zero once, the first time it is shown. */
@Composable
fun countUp(target: Float): Float {
    if (LocalReduceMotion.current) return target
    var played by rememberSaveable { mutableStateOf(false) }
    val anim = remember { Animatable(if (played) target else 0f) }
    LaunchedEffect(target) {
        if (played) anim.snapTo(target) else {
            anim.animateTo(target, tween(650, easing = FastOutSlowInEasing))
            played = true
        }
    }
    return anim.value
}

/** Tiny route outline drawn from the stored thumbnail points; no map tiles involved. */
@Composable
fun RouteThumb(thumb: ByteArray, modifier: Modifier = Modifier, color: Color = LocalPalette.current.accent) {
    val background = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier) {
        drawRoundRect(background, cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))
        val n = thumb.size / 2
        if (n == 0) return@Canvas
        val pad = size.minDimension * 0.2f
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun at(i: Int) = Offset(pad + (thumb[i * 2].toInt() and 0xFF) / 255f * w, pad + (thumb[i * 2 + 1].toInt() and 0xFF) / 255f * h)
        if (n == 1) {
            drawCircle(color, 3.dp.toPx(), at(0))
            return@Canvas
        }
        val path = Path().apply {
            val first = at(0)
            moveTo(first.x, first.y)
            for (i in 1 until n) at(i).let { lineTo(it.x, it.y) }
        }
        drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(background, 3.2.dp.toPx(), at(0))
        drawCircle(color, 3.2.dp.toPx(), at(0), style = Stroke(1.6.dp.toPx()))
        drawCircle(color, 3.2.dp.toPx(), at(n - 1))
    }
}

/** One simple line illustration (a trail ending in a footprint) with one helpful sentence. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val line = MaterialTheme.colorScheme.outline
    val accent = LocalPalette.current.accent
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(width = 168.dp, height = 104.dp).clearAndSetSemantics { }) {
            val w = size.width
            val h = size.height
            val trail = Path().apply {
                moveTo(w * 0.08f, h * 0.84f)
                cubicTo(w * 0.34f, h * 0.9f, w * 0.3f, h * 0.38f, w * 0.52f, h * 0.44f)
                cubicTo(w * 0.7f, h * 0.5f, w * 0.62f, h * 0.2f, w * 0.78f, h * 0.24f)
            }
            drawPath(
                trail, line,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 9.dp.toPx()))),
            )
            drawCircle(line, 5.dp.toPx(), Offset(w * 0.08f, h * 0.84f), style = Stroke(2.dp.toPx()))
            // Footprint at the end of the trail.
            val c = Offset(w * 0.86f, h * 0.2f)
            rotate(24f, c) {
                drawOval(accent, topLeft = Offset(c.x - 7.dp.toPx(), c.y - 6.dp.toPx()), size = androidx.compose.ui.geometry.Size(14.dp.toPx(), 22.dp.toPx()))
                val toes = listOf(-7f to 3.1f, -2.2f to 2.7f, 2.4f to 2.4f, 6.4f to 2.1f)
                toes.forEachIndexed { i, (dx, r) ->
                    drawCircle(accent, r.dp.toPx(), Offset(c.x + dx.dp.toPx(), c.y - (10.5f - i * 0.9f).dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Two tiles side by side; rows of these form the stats grid and reflow cleanly with large fonts. */
@Composable
fun StatRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp)) { Canvas(Modifier.size(8.dp)) { drawCircle(color) } }
}
