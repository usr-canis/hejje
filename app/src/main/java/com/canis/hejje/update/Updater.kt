package com.canis.hejje.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.canis.hejje.BuildConfig
import com.canis.hejje.HejjeApp
import com.canis.hejje.data.SettingsStore
import com.canis.hejje.domain.HejjeJson
import com.canis.hejje.domain.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

@Serializable
data class GhAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
    val digest: String? = null,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?)

enum class UpdateError { NETWORK, WIFI_ONLY, VERIFY, INSTALL }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val info: UpdateInfo?, val error: UpdateError) : UpdateState
}

/** Checks GitHub Releases for a newer version, downloads the APK, verifies it and hands it to Android's installer. */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * @param manual true for "Check now": always asks GitHub and reports errors. Automatic checks
     * run at most once a day, respect "Skip this version" and fail silently when offline.
     */
    suspend fun check(manual: Boolean): UpdateInfo? {
        val s = settings.current
        val busy = _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing
        if (busy) return null
        if (!manual) {
            if (!s.updateAutoCheck) return null
            if (System.currentTimeMillis() - s.lastUpdateCheck < DAY_MS) return (_state.value as? UpdateState.Available)?.info
        } else {
            _state.value = UpdateState.Checking
        }
        val release = fetchLatest()
        if (release == null) {
            if (manual) _state.value = UpdateState.Failed(null, UpdateError.NETWORK)
            return null
        }
        settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        val info = toInfo(release)
        return if (info != null && Version.isNewer(info.version, BuildConfig.VERSION_NAME) && (manual || info.version != s.skippedVersion)) {
            _state.value = UpdateState.Available(info)
            info
        } else {
            _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
            null
        }
    }

    private suspend fun fetchLatest(): GhRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { r ->
                r.body?.string()?.takeIf { r.isSuccessful }?.let { HejjeJson.decodeFromString(GhRelease.serializer(), it) }
            }
        }.getOrNull()
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    fun skip(info: UpdateInfo) {
        scope.launch { settings.update { it.copy(skippedVersion = info.version) } }
        _state.value = UpdateState.Idle
    }

    fun download(info: UpdateInfo) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            if (settings.current.updateWifiOnly && context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) {
                _state.value = UpdateState.Failed(info, UpdateError.WIFI_ONLY)
                return@launch
            }
            _state.value = UpdateState.Downloading(info, 0f)
            val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, "hejje-${info.version}.apk")
            val ok = runCatching {
                http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null) error("HTTP ${r.code}")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var shown = 0f
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                if (p - shown >= 0.01f) {
                                    shown = p
                                    _state.value = UpdateState.Downloading(info, p)
                                }
                            }
                        }
                    }
                }
            }.isSuccess
            if (!ok) {
                _state.value = UpdateState.Failed(info, UpdateError.NETWORK)
                return@launch
            }
            if (!verify(file, info)) {
                file.delete()
                _state.value = UpdateState.Failed(info, UpdateError.VERIFY)
                return@launch
            }
            _state.value = UpdateState.Installing(info)
            runCatching { install(file) }.onFailure { _state.value = UpdateState.Failed(info, UpdateError.INSTALL) }
        }
    }

    /**
     * The download must be complete, match GitHub's checksum, be this very app, be newer than
     * what is installed and be signed with the same key. (Android checks the key again itself.)
     */
    private fun verify(file: File, info: UpdateInfo): Boolean {
        if (info.size > 0 && file.length() != info.size) return false
        if (info.sha256 != null && !sha256(file).equals(info.sha256, ignoreCase = true)) return false
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return false
        if (archive.packageName != context.packageName) return false
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) return false
        val a = signatures(archive)
        val b = signatures(installed)
        return a.isEmpty() || b.isEmpty() || a.any { it in b }
    }

    private fun signatures(info: PackageInfo): Set<String> {
        val list = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return list.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hands the APK to Android. Android then shows its own confirmation; apps cannot skip that. */
    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("hejje.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    internal fun onInstallResult(status: Int) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> UpdateState.Failed(info, UpdateError.INSTALL)
        }
    }

    companion object {
        private const val DAY_MS = 24 * 3_600_000L

        fun toInfo(r: GhRelease): UpdateInfo? {
            if (r.draft || r.prerelease || r.tag.isBlank()) return null
            val apk = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
            return UpdateInfo(
                version = r.tag.removePrefix("v"),
                notes = r.body.orEmpty().trim(),
                url = apk.url,
                size = apk.size,
                sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        }
    }
}

/** Receives the installer's progress: opens Android's confirmation screen, or reports a failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        (context.applicationContext as HejjeApp).container.updater.onInstallResult(status)
    }

    companion object {
        const val ACTION = "com.canis.hejje.action.INSTALL_RESULT"
    }
}
