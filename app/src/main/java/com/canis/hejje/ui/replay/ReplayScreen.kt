package com.canis.hejje.ui.replay

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.replay.ReplayJobs
import com.canis.hejje.replay.ReplayJobs.Phase
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Starts (or shows) the trip's replay video. It is made in the background while the app is open. */
@Composable
fun ReplayScreen(tripId: Long, onBack: () -> Unit) {
    val jobs = LocalContainer.current.replays
    val job by jobs.job.collectAsStateWithLifecycle()
    LaunchedEffect(tripId) {
        val j = jobs.job.value
        if (j == null || (!j.running && j.tripId != tripId)) { jobs.clear(); jobs.start(tripId) }
    }
    val mine = job?.takeIf { it.tripId == tripId }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        HejjeTopBar(stringResource(R.string.replay_title), onBack)
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = ScreenPadding).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val other = job
            when {
                mine == null && other != null && other.running -> Busy(other, onBack)
                mine == null -> Unit
                mine.running -> Making(mine, onLeave = onBack, onCancel = { jobs.cancel(); onBack() })
                mine.phase == Phase.DONE -> Ready(mine, jobs, onAgain = { jobs.clear(); jobs.start(tripId) })
                else -> Problem(mine.phase, onRetry = { jobs.clear(); jobs.start(tripId) })
            }
        }
    }
}

@Composable
private fun ColumnScope.Making(job: ReplayJobs.Job, onLeave: () -> Unit, onCancel: () -> Unit) {
    // Progress glides: the page reports a few times a second and the numbers ease between reports.
    val target = if (job.phase == Phase.PREPARING) .01f else job.progress
    val shown by animateFloatAsState(target, tween(900, easing = LinearEasing), label = "progress")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    Spacer(Modifier.height(8.dp))
    Card(job.title) {
        RouteDrawing(job.outline, shown, Modifier.size(230.dp), dark = true)
        Spacer(Modifier.height(18.dp))
        Text(
            "${(shown * 100).roundToInt()}%",
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"), color = Color.White,
            modifier = Modifier.semantics { contentDescription = "${(shown * 100).roundToInt()}%" },
        )
        Text(
            stringResource(
                when {
                    job.paused -> R.string.replay_stage_paused
                    job.phase == Phase.PREPARING -> R.string.replay_stage_preparing
                    job.phase == Phase.MAP -> R.string.replay_stage_map
                    job.phase == Phase.VIDEO -> R.string.replay_stage_video
                    else -> R.string.replay_stage_saving
                },
            ),
            style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = .86f),
        )
        Spacer(Modifier.height(2.dp))
        Text(timeLeft(job, now), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .55f))
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { shown }, modifier = Modifier.fillMaxWidth(.78f).height(5.dp), color = LocalPalette.current.accent,
            trackColor = Color.White.copy(alpha = .12f), strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
    }
    Spacer(Modifier.weight(1f))
    Text(stringResource(R.string.replay_background_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(14.dp))
    Button(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.replay_keep_using)) }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.replay_cancel)) }
}

/** The dark card the progress and the finished replay sit on, in the video's own colours. */
@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2030), Color(0xFF0D0F14))))
            .padding(vertical = 26.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title.isNotEmpty()) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp), color = Color.White.copy(alpha = .6f), maxLines = 1)
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

@Composable
private fun timeLeft(job: ReplayJobs.Job, now: Long): String {
    // Filming is most of the work; estimate from its pace once it has run a little.
    val filmed = ((job.progress - .2f) / .8f).coerceIn(0f, 1f)
    if (job.phase != Phase.VIDEO || job.videoStartedAt == 0L || filmed < .06f) return stringResource(R.string.replay_left_soon)
    val leftSec = ((now - job.videoStartedAt) / 1000f) / filmed * (1 - filmed)
    return if (leftSec < 60) stringResource(R.string.replay_left_less) else stringResource(R.string.replay_left_min, (leftSec / 60f).roundToInt().coerceAtLeast(1))
}

@Composable
private fun ColumnScope.Ready(job: ReplayJobs.Job, jobs: ReplayJobs, onAgain: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(8.dp))
    Card(job.title) {
        RouteDrawing(job.outline, 1f, Modifier.size(230.dp), dark = true)
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.replay_ready), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(if (job.saved != null) R.string.replay_saved else R.string.replay_done), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .6f))
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { watch(context, job.saved ?: cacheUri(context, jobs)) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.replay_watch)) }
        OutlinedButton(onClick = { share(context, cacheUri(context, jobs)) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.replay_share)) }
    }
    TextButton(onClick = onAgain) { Text(stringResource(R.string.replay_again)) }
}

@Composable
private fun ColumnScope.Busy(other: ReplayJobs.Job, onLeave: () -> Unit) {
    Spacer(Modifier.weight(1f))
    RouteDrawing(other.outline, other.progress, Modifier.size(200.dp), dark = false)
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.replay_other), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.weight(1f))
    Button(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.replay_keep_using)) }
}

@Composable
private fun ColumnScope.Problem(phase: Phase, onRetry: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text(
        stringResource(
            when (phase) {
                Phase.EMPTY -> R.string.replay_empty
                Phase.UNSUPPORTED -> R.string.replay_unsupported
                else -> R.string.replay_failed
            },
        ),
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    if (phase != Phase.EMPTY && phase != Phase.UNSUPPORTED) Button(onClick = onRetry) { Text(stringResource(R.string.replay_retry)) }
    Spacer(Modifier.weight(1f))
}

/** The trip's shape, drawn in step with the progress, with a soft glowing head. */
@Composable
private fun RouteDrawing(outline: FloatArray?, fraction: Float, modifier: Modifier, dark: Boolean) {
    val accent = LocalPalette.current.accent
    val faint = if (dark) Color.White.copy(alpha = .14f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val pts = outline ?: return@Canvas
        if (pts.size < 4) return@Canvas
        val pad = 18.dp.toPx()
        val s = size.minDimension - 2 * pad
        val full = Path().apply {
            moveTo(pad + pts[0] * s, pad + pts[1] * s)
            for (i in 1 until pts.size / 2) lineTo(pad + pts[2 * i] * s, pad + pts[2 * i + 1] * s)
        }
        val stroke = { w: Float -> Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        drawPath(full, faint, style = stroke(5.dp.toPx()))
        val measure = PathMeasure().apply { setPath(full, false) }
        val done = Path()
        val upTo = measure.length * fraction.coerceIn(0f, 1f)
        if (upTo > 0f) measure.getSegment(0f, upTo, done, true)
        drawPath(done, accent.copy(alpha = if (dark) .28f else .22f), style = stroke(16.dp.toPx()))
        drawPath(done, accent, style = stroke(5.dp.toPx()))
        val head: Offset = measure.getPosition(upTo.coerceAtLeast(.01f))
        drawCircle(accent.copy(alpha = .25f * pulse), radius = 18.dp.toPx() * pulse, center = head)
        drawCircle(accent, radius = 7.dp.toPx(), center = head)
        drawCircle(Color.White, radius = 3.dp.toPx(), center = head)
    }
}

private fun cacheUri(context: Context, jobs: ReplayJobs): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", jobs.file)

private fun watch(context: Context, uri: Uri) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.replay_share)))
}

