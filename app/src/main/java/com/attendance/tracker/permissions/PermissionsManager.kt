package com.attendance.tracker.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Everything the app needs to track attendance reliably, all the time. */
data class PermissionStatus(
    val preciseLocation: Boolean,
    val approximateOnly: Boolean,
    val backgroundLocation: Boolean,
    val locationServicesOn: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val keepPermissionsWhenUnused: Boolean,
) {
    /** Without these, attendance cannot be tracked in the background. */
    val requiredOk: Boolean get() = preciseLocation && backgroundLocation && locationServicesOn && batteryUnrestricted
    val allOk: Boolean get() = requiredOk && notifications && keepPermissionsWhenUnused
    val doneCount: Int get() = listOf(preciseLocation, backgroundLocation, locationServicesOn, notifications,
        batteryUnrestricted, keepPermissionsWhenUnused).count { it }
}

object PermissionsManager {
    const val TOTAL_STEPS = 6

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasPrecise(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun locationServicesOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun notificationsOn(ctx: Context): Boolean {
        val perm = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        return perm && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Android 11+ can silently revoke permissions from apps that aren't opened for months. */
    suspend fun keepPermissionsWhenUnused(ctx: Context): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val f = PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx)
            f.addListener({
                val s = runCatching { f.get() }.getOrDefault(UnusedAppRestrictionsConstants.ERROR)
                if (cont.isActive) cont.resume(
                    s == UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE ||
                        s == UnusedAppRestrictionsConstants.DISABLED ||
                        s == UnusedAppRestrictionsConstants.ERROR
                )
            }, ContextCompat.getMainExecutor(ctx))
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(true)
        }
    }

    suspend fun status(ctx: Context) = PermissionStatus(
        preciseLocation = hasPrecise(ctx),
        approximateOnly = !hasPrecise(ctx) && granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION),
        backgroundLocation = hasBackground(ctx),
        locationServicesOn = locationServicesOn(ctx),
        notifications = notificationsOn(ctx),
        batteryUnrestricted = batteryUnrestricted(ctx),
        keepPermissionsWhenUnused = keepPermissionsWhenUnused(ctx),
    )

    /** Quick synchronous check used by the background service. */
    fun trackingBlockers(ctx: Context): List<String> = buildList {
        if (!hasPrecise(ctx)) add("Precise location permission was removed")
        else if (!hasBackground(ctx)) add("Location is no longer allowed “all the time”")
        if (!locationServicesOn(ctx)) add("Phone location (GPS) is turned off")
    }

    fun appSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @SuppressLint("BatteryLife")
    fun batteryIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun unusedAppIntent(ctx: Context): Intent =
        IntentCompat.createManageUnusedAppRestrictionsIntent(ctx, ctx.packageName)

    fun notificationSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
}
