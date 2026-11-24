package com.attendance.tracker.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.attendance.tracker.AttendanceController

data class WifiNet(val ssid: String, val bssid: String?)

/** Reads the connected Wi-Fi (needs location permission + location on, which the app already requires). */
object WifiHelper {
    @Suppress("DEPRECATION")
    fun current(ctx: Context): WifiNet? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return null
        val ssid = info.ssid?.trim('"')
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") return null
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" }
        return WifiNet(ssid, bssid)
    }

    /** True when connected to one of the saved office networks (matched by name). */
    fun onOfficeWifi(ctx: Context): Boolean {
        val saved = AttendanceController.settings.officeWifi
        if (saved.isEmpty()) return false
        val cur = current(ctx) ?: return false
        return saved.any { it.ssid == cur.ssid }
    }
}
