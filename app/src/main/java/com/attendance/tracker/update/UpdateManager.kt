package com.attendance.tracker.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.attendance.tracker.AttendanceApp
import com.attendance.tracker.BuildConfig
import com.attendance.tracker.R
import com.attendance.tracker.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Contents of update.json published next to each release APK. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState()
    data class Installing(val info: UpdateInfo) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * Over-the-air updater:
 *  1. Fetches the update manifest (update.json) from [BuildConfig.UPDATE_MANIFEST_URL].
 *  2. If its versionCode is newer than the installed one, downloads the APK over HTTPS.
 *  3. Verifies the APK's SHA-256 against the manifest (corrupt/tampered downloads are rejected).
 *  4. Installs it with PackageInstaller. Android itself also refuses the update unless the
 *     APK is signed with the same key as the installed app.
 */
object UpdateManager {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private const val PREFS = "ota"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val isConfigured: Boolean get() = BuildConfig.UPDATE_MANIFEST_URL.startsWith("https://")

    /** Checks at most once per 6 hours; posts a notification if an update exists. */
    suspend fun checkIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        val info = check() ?: return
        if (prefs.getInt("notifiedVersion", 0) != info.versionCode) {
            prefs.edit().putInt("notifiedVersion", info.versionCode).apply()
            notifyAvailable(context, info)
        }
    }

    /** Returns update info if a newer version is available. */
    suspend fun check(): UpdateInfo? {
        if (!isConfigured) {
            _state.value = UpdateState.Failed("Update server not configured in this build")
            return null
        }
        val s = _state.value
        if (s is UpdateState.Downloading || s is UpdateState.Installing) return null
        _state.value = UpdateState.Checking
        return try {
            val info = withContext(Dispatchers.IO) { fetchManifest() }
            if (info.versionCode > currentVersionCode) {
                _state.value = UpdateState.Available(info); info
            } else {
                _state.value = UpdateState.UpToDate; null
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Update check failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun fetchManifest(): UpdateInfo {
        val conn = open(BuildConfig.UPDATE_MANIFEST_URL)
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val apkUrl = j.getString("apkUrl")
            require(apkUrl.startsWith("https://")) { "APK URL must be HTTPS" }
            return UpdateInfo(
                versionCode = j.getInt("versionCode"),
                versionName = j.optString("versionName", j.getInt("versionCode").toString()),
                apkUrl = apkUrl,
                sha256 = j.getString("sha256").lowercase().trim(),
                notes = j.optString("notes", ""),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** True if the user has allowed this app to install updates (Android 8+ per-app setting). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Downloads, verifies and installs [info]. */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val app = context.applicationContext
        try {
            _state.value = UpdateState.Downloading(info, 0)
            val apk = withContext(Dispatchers.IO) { download(app, info) }
            _state.value = UpdateState.Installing(info)
            withContext(Dispatchers.IO) { install(app, apk) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update-${info.versionCode}.apk")
        val conn = open(info.apkUrl)
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(info, pct) }
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != info.sha256) {
                out.delete()
                error("Downloaded file is corrupt or tampered (checksum mismatch)")
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                flags
            )
            session.commit(pi.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
            ) " — the update is signed with a different key; uninstall and reinstall once." else ""
            _state.value = UpdateState.Failed("Install failed: ${message ?: status}$hint")
        }
    }

    private fun notifyAvailable(context: Context, info: UpdateInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            nm.notify(
                3001,
                NotificationCompat.Builder(context, AttendanceApp.CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Update available: v${info.versionName}")
                    .setContentText(info.notes.ifBlank { "Tap to install the latest version." })
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
