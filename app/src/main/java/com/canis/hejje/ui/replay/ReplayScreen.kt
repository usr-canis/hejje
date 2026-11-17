package com.canis.hejje.ui.replay

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.canis.hejje.R
import com.canis.hejje.replay.ReplayData
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.ScreenPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class Phase { PREPARING, MAP, VIDEO, DONE, EMPTY, UNSUPPORTED, FAILED }

/** Makes the trip's replay video on the phone and offers it to share or keep. */
@Composable
fun ReplayScreen(tripId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = LocalContainer.current
    var attempt by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(Phase.PREPARING) }
    var progress by remember { mutableFloatStateOf(0f) }
    val file = remember { File(File(context.cacheDir, "shared").apply { mkdirs() }, "hejje-replay.mp4") }

    LaunchedEffect(tripId, attempt) {
        phase = Phase.PREPARING; progress = 0f; data = null
        val json = runCatching { ReplayData.build(context, container, tripId) }.getOrNull()
        if (json == null) phase = Phase.EMPTY else { data = json; phase = Phase.MAP }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        HejjeTopBar(stringResource(R.string.replay_title), onBack)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                val json = data
                if (json != null && phase != Phase.FAILED && phase != Phase.UNSUPPORTED) {
                    // A fresh page for every attempt.
                    androidx.compose.runtime.key(attempt) {
                        AndroidView(
                            factory = { ctx ->
                                replayWebView(ctx, json, file, object : Events {
                                    override fun progress(value: Float, stage: String) { progress = value; if (phase != Phase.DONE) phase = if (stage == "map") Phase.MAP else Phase.VIDEO }
                                    override fun done() { progress = 1f; phase = Phase.DONE }
                                    override fun fail(reason: String) { phase = if (reason == "unsupported") Phase.UNSUPPORTED else Phase.FAILED }
                                })
                            },
                            onRelease = { it.destroy() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = when (phase) {
                Phase.PREPARING -> R.string.replay_preparing
                Phase.MAP -> R.string.replay_map
                Phase.VIDEO -> R.string.replay_video
                Phase.DONE -> R.string.replay_done
                Phase.EMPTY -> R.string.replay_empty
                Phase.UNSUPPORTED -> R.string.replay_unsupported
                Phase.FAILED -> R.string.replay_failed
            }
            Text(stringResource(status), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            when (phase) {
                Phase.PREPARING, Phase.MAP, Phase.VIDEO -> {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.replay_data_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Phase.DONE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { share(context, file) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.replay_share)) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        var saving by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { saving = true }, enabled = !saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.replay_save)) }
                        if (saving) LaunchedEffect(Unit) {
                            val ok = withContext(Dispatchers.IO) { runCatching { saveToGallery(context, file) }.isSuccess }
                            Toast.makeText(context, if (ok) R.string.replay_saved else R.string.replay_failed, Toast.LENGTH_SHORT).show()
                            saving = false
                        }
                    }
                }
                Phase.FAILED -> Button(onClick = { attempt++ }) { Text(stringResource(R.string.replay_retry)) }
                else -> Unit
            }
        }
    }
}

private interface Events {
    fun progress(value: Float, stage: String)
    fun done()
    fun fail(reason: String)
}

private const val HOST = "appassets.androidplatform.net"

/**
 * The page that draws and encodes the video. It runs from the app's own files, served as https.
 * The Kannada font is read straight from res/font: font resources are stored as plain files.
 */
@SuppressLint("SetJavaScriptEnabled", "ResourceType")
private fun replayWebView(context: Context, json: String, file: File, events: Events): WebView {
    val main = Handler(Looper.getMainLooper())
    val sink = VideoSink(file)
    return WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != HOST) return null
                val path = url.path.orEmpty()
                return runCatching {
                    when {
                        path == "/font/mallige.ttf" -> WebResourceResponse("font/ttf", null, context.resources.openRawResource(R.font.mallige_bold))
                        path.startsWith("/replay/") && !path.contains("..") ->
                            WebResourceResponse(mimeOf(path), "utf-8", context.assets.open(path.removePrefix("/")))
                        else -> null
                    }
                }.getOrNull()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST
        }
        addJavascriptInterface(object {
            @JavascriptInterface fun data(): String = json
            @JavascriptInterface fun progress(value: Double, stage: String) { main.post { events.progress(value.toFloat(), stage) } }
            @JavascriptInterface fun chunk(base64: String, position: Double) { sink.write(Base64.decode(base64, Base64.DEFAULT), position.toLong()) }
            @JavascriptInterface fun done(codec: String, seconds: Double) { sink.close(); main.post { events.done() } }
            @JavascriptInterface fun fail(reason: String) { sink.close(); file.delete(); main.post { events.fail(reason) } }
        }, "Android")
        loadUrl("https://$HOST/replay/index.html")
    }
}

/** Writes the encoder's pieces at their places in the file (the header is patched at the end). */
private class VideoSink(file: File) {
    private val out = RandomAccessFile(file.apply { delete() }, "rw")
    private var open = true

    @Synchronized fun write(bytes: ByteArray, position: Long) {
        if (!open) return
        out.seek(position); out.write(bytes)
    }

    @Synchronized fun close() {
        if (open) { open = false; out.close() }
    }
}

private fun mimeOf(path: String) = when (path.substringAfterLast('.')) {
    "html" -> "text/html"
    "js" -> "text/javascript"
    "css" -> "text/css"
    else -> "application/octet-stream"
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.replay_share)))
}

/** Copies the video into Movies/Hejje (Android 10 and later need no permission for this). */
private fun saveToGallery(context: Context, file: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("Not supported")
    val name = "hejje-replay-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Hejje")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("No gallery")
    resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: error("Cannot write")
    values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
}
