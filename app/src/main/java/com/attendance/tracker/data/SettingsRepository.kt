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

    /** Forget once-a-day reminder flags (after clearing records). */
    fun clearOnceFlags() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("once_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Wipe all settings (office, gates, hours, Wi-Fi…). Permissions are untouched. */
    fun wipe() {
        prefs.edit().clear().commit()
        reload()
    }

    /** Re-read everything from storage (after a restore). */
    fun reload() { _settings.value = load() }

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
            .putInt("latestStart", sc.latestStartMin).putInt("netMin", sc.netMinutes)
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
            // Older versions stored the whole day incl. lunch ("requiredMin"); convert once to net hours.
            netMinutes = when {
                prefs.contains("netMin") -> prefs.getInt("netMin", d.netMinutes)
                prefs.contains("requiredMin") -> (prefs.getInt("requiredMin", d.requiredMinutes) - prefs.getInt("maxLunch", d.maxLunchMinutes)).coerceAtLeast(60)
                else -> d.netMinutes
            },
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

    private fun flag(key: String, def: Boolean = true) = prefs.getBoolean(key, def)
    private fun setFlag(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    /** Ask "Was this lunch?" after an afternoon OUT → IN. */
    var autoLunch: Boolean get() = flag("autoLunch"); set(v) = setFlag("autoLunch", v)
    var remindBeforeLogout: Boolean get() = flag("remindBefore"); set(v) = setFlag("remindBefore", v)
    var remindHoursDone: Boolean get() = flag("remindDone"); set(v) = setFlag("remindDone", v)
    var remindLunchOver: Boolean get() = flag("remindLunch"); set(v) = setFlag("remindLunch", v)

    /** Returns true the first time [key] is seen (used for once-a-day reminders). */
    fun firstTime(key: String): Boolean {
        if (prefs.getBoolean("once_$key", false)) return false
        prefs.edit().putBoolean("once_$key", true).apply()
        return true
    }

    /** Office Wi-Fi networks used as a second "I'm inside" signal. */
    var officeWifi: List<com.attendance.tracker.service.WifiNet>
        get() = runCatching {
            val a = JSONArray(prefs.getString("officeWifi", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let {
                com.attendance.tracker.service.WifiNet(it.getString("ssid"), it.optString("bssid").ifBlank { null })
            } }
        }.getOrDefault(emptyList())
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("ssid", it.ssid).put("bssid", it.bssid ?: "")) }
            prefs.edit().putString("officeWifi", a.toString()).apply()
        }

    // ---- on duty (official work outside the office) ----
    var dutyStartMs: Long get() = prefs.getLong("dutyStart", 0L); set(v) = prefs.edit().putLong("dutyStart", v).apply()
    var dutyReason: String get() = prefs.getString("dutyReason", "Official work")!!; set(v) = prefs.edit().putString("dutyReason", v).apply()
    var pendingDutyOutMs: Long get() = prefs.getLong("pendingDutyOut", 0L); set(v) = prefs.edit().putLong("pendingDutyOut", v).apply()
    var askDuty: Boolean get() = flag("askDuty"); set(v) = setFlag("askDuty", v)

    // ---- end-of-day summary ----
    var autoSummary: Boolean get() = flag("autoSummary"); set(v) = setFlag("autoSummary", v)
    var managerPhone: String get() = prefs.getString("managerPhone", "")!!; set(v) = prefs.edit().putString("managerPhone", v).apply()

    /** OUT / IN times of an afternoon break waiting for "Was this lunch?" (0 = none). */
    var pendingLunchOut: Long get() = prefs.getLong("pendingLunchOut", 0L); set(v) = prefs.edit().putLong("pendingLunchOut", v).apply()
    var pendingLunchIn: Long get() = prefs.getLong("pendingLunchIn", 0L); set(v) = prefs.edit().putLong("pendingLunchIn", v).apply()

    /** Sound + vibration when IN / OUT is recorded. */
    var punchSound: Boolean
        get() = prefs.getBoolean("punchSound", true)
        set(v) = prefs.edit().putBoolean("punchSound", v).apply()

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
