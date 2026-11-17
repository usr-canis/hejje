package com.canis.hejje.ui.replay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.replay.ReplayJobs

/**
 * Hosts the page that draws and encodes the replay, at the app's root and out of sight, so the
 * video keeps being made while the person uses any screen of the app.
 */
@Composable
fun ReplayHost(jobs: ReplayJobs) {
    val job by jobs.job.collectAsStateWithLifecycle()
    val j = job ?: return
    val json = j.json ?: return
    if (j.phase != ReplayJobs.Phase.MAP && j.phase != ReplayJobs.Phase.VIDEO) return
    key(j.id) {
        AndroidView(
            factory = { replayWebView(it, json, j.id, jobs) },
            onRelease = { it.destroy(); jobs.onHostGone(j.id) },
            modifier = Modifier.size(1.dp),
        )
    }
}

private const val HOST = "appassets.androidplatform.net"

/**
 * The page runs from the app's own files, served as https. The Kannada font is read straight from
 * res/font: font resources are stored as plain files.
 */
@SuppressLint("SetJavaScriptEnabled", "ResourceType")
private fun replayWebView(context: Context, json: String, id: Int, jobs: ReplayJobs): WebView = WebView(context).apply {
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
        @JavascriptInterface fun progress(value: Double, stage: String) = jobs.onProgress(id, value.toFloat(), stage)
        @JavascriptInterface fun chunk(base64: String, position: Double) = jobs.onChunk(id, Base64.decode(base64, Base64.DEFAULT), position.toLong())
        @JavascriptInterface fun done(codec: String, seconds: Double) = jobs.onDone(id)
        @JavascriptInterface fun fail(reason: String) = jobs.onFail(id, reason)
    }, "Android")
    loadUrl("https://$HOST/replay/index.html")
}

private fun mimeOf(path: String) = when (path.substringAfterLast('.')) {
    "html" -> "text/html"
    "js" -> "text/javascript"
    "css" -> "text/css"
    else -> "application/octet-stream"
}
