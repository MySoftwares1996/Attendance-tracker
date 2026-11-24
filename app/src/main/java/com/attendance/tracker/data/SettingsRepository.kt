package com.attendance.tracker.data

import android.content.Context
import com.attendance.tracker.core.AccuracyConfig
import com.attendance.tracker.core.Gate
import com.attendance.tracker.core.LatLng
import com.attendance.tracker.core.LunchState
import com.attendance.tracker.core.OfficeConfig
import com.attendance.tracker.core.Presence
import com.attendance.tracker.core.WorkSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

data class AppSettings(
    val office: OfficeConfig? = null,
    val schedule: WorkSchedule = WorkSchedule(),
    val accuracy: AccuracyConfig = AccuracyConfig(),
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("attendance_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    fun save(s: AppSettings) {
        val e = prefs.edit()
        val o = s.office
        if (o == null) {
            e.remove("office")
        } else {
            val gates = JSONArray()
            o.gates.forEach {
                gates.put(JSONObject().put("id", it.id).put("name", it.name)
                    .put("lat", it.lat).put("lng", it.lng).put("r", it.radiusM))
            }
            val poly = JSONArray()
            o.polygon.forEach { poly.put(JSONArray().put(it.lat).put(it.lng)) }
            e.putString("office", JSONObject().put("name", o.name).put("lat", o.lat)
                .put("lng", o.lng).put("r", o.radiusM).put("gates", gates).put("poly", poly).toString())
        }
        val sc = s.schedule
        e.putInt("startMin", sc.startMin).putInt("endMin", sc.endMin)
            .putString("workDays", sc.workDays.joinToString(",") { it.name })
            .putInt("lunchStartMin", sc.lunchStartMin).putInt("lunchEndMin", sc.lunchEndMin)
            .putInt("maxLunch", sc.maxLunchMinutes).putBoolean("restrictLunch", sc.restrictLunchToWindow)
            .putBoolean("flexible", sc.flexible).putInt("earliestStart", sc.earliestStartMin)
            .putInt("latestStart", sc.latestStartMin).putInt("requiredMin", sc.requiredMinutes)
            .putInt("overtimeUntil", sc.overtimeUntilMin)
        val a = s.accuracy
        e.putFloat("maxAcc", a.maxAccuracyM).putInt("confirm", a.confirmSamples)
            .putBoolean("rejectMock", a.rejectMock).putInt("maxAge", a.maxFixAgeSec)
        e.apply()
        _settings.value = s
    }

    private fun load(): AppSettings {
        val office = prefs.getString("office", null)?.let { json ->
            runCatching {
                val j = JSONObject(json)
                val ga = j.optJSONArray("gates") ?: JSONArray()
                val gates = (0 until ga.length()).map { i ->
                    val g = ga.getJSONObject(i)
                    Gate(g.getString("id"), g.getString("name"), g.getDouble("lat"),
                        g.getDouble("lng"), g.optDouble("r", 25.0))
                }
                val pa = j.optJSONArray("poly") ?: JSONArray()
                val poly = (0 until pa.length()).map { i -> pa.getJSONArray(i).let { LatLng(it.getDouble(0), it.getDouble(1)) } }
                OfficeConfig(j.getString("name"), j.getDouble("lat"), j.getDouble("lng"),
                    j.getDouble("r"), gates, poly)
            }.getOrNull()
        }
        val d = WorkSchedule()
        val days = prefs.getString("workDays", null)
            ?.split(",")?.filter { it.isNotBlank() }
            ?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet()
            ?: d.workDays
        val schedule = WorkSchedule(
            startMin = prefs.getInt("startMin", d.startMin),
            endMin = prefs.getInt("endMin", d.endMin),
            workDays = days,
            lunchStartMin = prefs.getInt("lunchStartMin", d.lunchStartMin),
            lunchEndMin = prefs.getInt("lunchEndMin", d.lunchEndMin),
            maxLunchMinutes = prefs.getInt("maxLunch", d.maxLunchMinutes),
            // No fixed lunch time and no fixed working hours: the day follows your arrival.
            restrictLunchToWindow = false,
            flexible = true,
            earliestStartMin = prefs.getInt("earliestStart", d.earliestStartMin),
            latestStartMin = prefs.getInt("latestStart", d.latestStartMin),
            requiredMinutes = prefs.getInt("requiredMin", d.requiredMinutes),
            overtimeUntilMin = prefs.getInt("overtimeUntil", d.overtimeUntilMin),
        )
        val da = AccuracyConfig()
        val accuracy = AccuracyConfig(
            maxAccuracyM = prefs.getFloat("maxAcc", da.maxAccuracyM),
            confirmSamples = prefs.getInt("confirm", da.confirmSamples),
            rejectMock = prefs.getBoolean("rejectMock", da.rejectMock),
            maxFixAgeSec = prefs.getInt("maxAge", da.maxFixAgeSec),
        )
        return AppSettings(office, schedule, accuracy)
    }

    // ---- runtime state that must survive restarts ----
    var trackingEnabled: Boolean
        get() = prefs.getBoolean("tracking", false)
        set(v) = prefs.edit().putBoolean("tracking", v).apply()

    /** True when the user explicitly paused tracking (suppresses auto-start). */
    var userPaused: Boolean
        get() = prefs.getBoolean("userPaused", false)
        set(v) = prefs.edit().putBoolean("userPaused", v).apply()

    var lastHeartbeatMs: Long
        get() = prefs.getLong("heartbeat", 0L)
        set(v) = prefs.edit().putLong("heartbeat", v).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    var presence: Presence
        get() = runCatching { Presence.valueOf(prefs.getString("presence", "UNKNOWN")!!) }
            .getOrDefault(Presence.UNKNOWN)
        set(v) = prefs.edit().putString("presence", v.name).apply()

    var lunch: LunchState
        get() = LunchState(prefs.getBoolean("lunchActive", false), prefs.getLong("lunchStart", 0L))
        set(v) = prefs.edit().putBoolean("lunchActive", v.active).putLong("lunchStart", v.startedAtMs).apply()
}
