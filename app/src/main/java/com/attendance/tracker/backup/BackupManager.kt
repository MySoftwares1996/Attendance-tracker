package com.attendance.tracker.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.attendance.tracker.AttendanceController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Full app backup as one JSON file:
 *  - every attendance record (punches with their GPS position and accuracy, lunch, duty, home trips),
 *  - per-day totals, day marks (leave, present, absent…),
 *  - all settings: office map, gates, home location, hours, lunch, permission,
 *    holidays, salary, reminders, and the permission-setup / trust state.
 *
 * Zero data loss:
 *  - the file carries row counts and a SHA-256 checksum; every write is read back and verified,
 *  - a restore checks the whole file before touching anything, saves an on-phone copy of the
 *    current data first, restores in one all-or-nothing transaction, verifies the counts,
 *    and rolls back to that copy if anything goes wrong ("Undo last restore" also uses it),
 *  - besides the file you pick (e.g. Google Drive), the app keeps daily copies on the phone,
 *    and backs up again a minute after every punch or settings change.
 */
object BackupManager {
    private const val META = "backup_meta"
    /** Every SharedPreferences file that holds user data. */
    private val PREF_FILES = listOf("attendance_settings", "perm", "trust")
    private const val MAIN_PREFS = "attendance_settings"
    private const val KEEP_LOCAL = 14
    const val FORMAT = 2

    data class Info(val createdAt: Long, val events: Int, val durations: Int, val marks: Int, val settings: Int,
                    val hasOffice: Boolean, val gates: Int, val verified: Boolean)

    fun backupUri(ctx: Context): Uri? = ctx.getSharedPreferences(META, Context.MODE_PRIVATE).getString("uri", null)?.let(Uri::parse)
    fun lastBackupMs(ctx: Context): Long = ctx.getSharedPreferences(META, Context.MODE_PRIVATE).getLong("last", 0L)
    fun lastLocalMs(ctx: Context): Long = localCopies(ctx).firstOrNull()?.lastModified() ?: 0L

    private fun localDir(ctx: Context) = File(ctx.filesDir, "backups").apply { mkdirs() }

    /** On-phone copies, newest first (daily copies and the copy taken before a restore). */
    fun localCopies(ctx: Context): List<File> =
        localDir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.lastModified() }

    fun preRestoreCopy(ctx: Context): File? = File(localDir(ctx), "before-restore.json").takeIf { it.exists() }

    /** Remember the chosen file (persisting access) and back up right away. */
    fun setBackupUri(ctx: Context, uri: Uri): Boolean {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        ctx.getSharedPreferences(META, Context.MODE_PRIVATE).edit().putString("uri", uri.toString()).apply()
        schedule(ctx)
        return backupNow(ctx)
    }

    /** Writes the on-phone daily copy and, if chosen, the backup file. True when the chosen file was written and verified. */
    @Synchronized
    fun backupNow(ctx: Context): Boolean {
        val text = export(ctx).toString()
        // On-phone daily copy (atomic: temp file then rename)
        runCatching {
            val dir = localDir(ctx)
            val tmp = File(dir, "tmp.part"); tmp.writeText(text)
            tmp.renameTo(File(dir, "auto-${LocalDate.now()}.json"))
            dir.listFiles { f -> f.name.startsWith("auto-") }.orEmpty().sortedByDescending { it.name }.drop(KEEP_LOCAL).forEach { it.delete() }
        }
        val uri = backupUri(ctx) ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()); it.flush() }
            // Read it back: the file must be complete and match.
            val back = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            check(back == text) { "verify failed" }
            ctx.getSharedPreferences(META, Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
        }.isSuccess
    }

    /** Saves an on-phone copy of everything now under [name] (e.g. before a reset). */
    fun saveLocal(ctx: Context, name: String): Boolean = runCatching {
        val dir = localDir(ctx)
        val tmp = File(dir, "tmp.part"); tmp.writeText(export(ctx).toString()); check(tmp.renameTo(File(dir, "$name.json")))
    }.isSuccess

    private fun prefsJson(ctx: Context, file: String): JSONObject {
        val o = JSONObject()
        ctx.getSharedPreferences(file, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            }
            if (e != null) o.put(k, e)
        }
        return o
    }

    fun export(ctx: Context): JSONObject {
        val db = AttendanceController.db
        val prefs = JSONObject()
        PREF_FILES.forEach { prefs.put(it, prefsJson(ctx, it)) }
        val data = JSONObject()
            .put("prefs", prefs)
            .put("events", JSONArray(db.dumpEvents().map { JSONArray(it) }))
            .put("durations", JSONArray(db.dumpDurations().map { JSONArray(it) }))
            .put("marks", JSONArray(db.dumpMarks().map { JSONArray(it) }))
        return com.attendance.tracker.core.BackupFormat.wrap(data, System.currentTimeMillis(), com.attendance.tracker.update.UpdateManager.currentVersionName)
            // Older app versions read this key directly.
            .put("settings", prefs.getJSONObject(MAIN_PREFS))
    }

    /** A fully checked backup, ready to apply. */
    private class Parsed(val createdAt: Long, val prefs: Map<String, JSONObject>, val events: List<JSONArray>,
                         val durations: List<JSONArray>, val marks: List<JSONArray>, val verified: Boolean)

    private fun parse(text: String): Parsed {
        val json = runCatching { JSONObject(text) }.getOrElse { error("This file is damaged or incomplete (not valid JSON).") }
        check(json.optString("app") == "attendance-tracker") { "That isn't an Attendance Tracker backup." }
        val format = json.optInt("format", 1)
        fun rows(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i) } } ?: emptyList()
        return if (format >= 2) {
            val data = com.attendance.tracker.core.BackupFormat.unwrap(json)
            val p = data.getJSONObject("prefs")
            Parsed(json.optLong("createdAt"), p.keys().asSequence().associateWith { p.getJSONObject(it) },
                rows(data, "events"), rows(data, "durations"), rows(data, "marks"), true)
        } else {
            Parsed(json.optLong("createdAt"), mapOf(MAIN_PREFS to (json.optJSONObject("settings") ?: JSONObject())),
                rows(json, "events"), rows(json, "durations"), rows(json, "marks"), false)
        }.also { p ->
            // Check every row's shape up front, so the restore itself can't fail half-way.
            p.events.forEach { it.getLong(0); it.getString(1); it.getString(2) }
            p.durations.forEach { it.getString(0); it.getString(1); it.getLong(2) }
            p.marks.forEach { it.getString(0); it.getString(1) }
            p.prefs.values.forEach { o -> o.keys().forEach { k -> o.getJSONObject(k).getString("t") } }
        }
    }

    private fun read(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }

    private fun infoOf(p: Parsed): Info {
        val main = p.prefs[MAIN_PREFS] ?: JSONObject()
        val office = runCatching { JSONObject(main.getJSONObject("office").getString("v")) }.getOrNull()
        val gates = office?.optJSONArray("gates")?.length() ?: 0
        return Info(p.createdAt, p.events.size, p.durations.size, p.marks.size, p.prefs.values.sumOf { it.length() },
            office != null, gates, p.verified)
    }

    /** Reads and checks a backup without changing anything. */
    fun inspect(ctx: Context, uri: Uri): Result<Info> = runCatching { infoOf(parse(read(ctx, uri))) }
    fun inspect(file: File): Result<Info> = runCatching { infoOf(parse(file.readText())) }

    /** Replaces everything with the backup's content. Returns an error message or null. */
    fun restore(ctx: Context, uri: Uri): String? =
        runCatching { read(ctx, uri) }.fold({ restoreText(ctx, it, saveCurrent = true) }, { "Couldn't read that file." })

    fun restore(ctx: Context, file: File, saveCurrent: Boolean = true): String? =
        runCatching { file.readText() }.fold({ restoreText(ctx, it, saveCurrent) }, { "Couldn't read that copy." })

    @Synchronized
    private fun restoreText(ctx: Context, text: String, saveCurrent: Boolean): String? {
        val parsed = runCatching { parse(text) }.getOrElse { return it.message ?: "Invalid backup." }
        // 1. Keep a copy of what's on the phone now, so the restore can always be undone.
        val safety = export(ctx).toString()
        if (saveCurrent) runCatching {
            val dir = localDir(ctx)
            val tmp = File(dir, "tmp.part"); tmp.writeText(safety); tmp.renameTo(File(dir, "before-restore.json"))
        }.onFailure { return "Couldn't save a safety copy first, so nothing was changed." }
        return try {
            applyParsed(ctx, parsed)
            null
        } catch (t: Throwable) {
            // 2. Anything failed → put the previous data back exactly.
            runCatching { applyParsed(ctx, parse(safety)) }
            "Restore failed (${t.message}). Your previous data was kept."
        } finally {
            AttendanceController.reload()
        }
    }

    private fun applyParsed(ctx: Context, p: Parsed) {
        AttendanceController.db.restoreAll(p.events, p.durations, p.marks)
        PREF_FILES.forEach { file ->
            val src = p.prefs[file] ?: if (file == MAIN_PREFS) JSONObject() else return@forEach
            val ed = ctx.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear()
            src.keys().forEach { k ->
                val e = src.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "ss" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                }
            }
            check(ed.commit()) { "couldn't save settings" }
        }
        val c = AttendanceController.db.counts()
        check(c["events"] == p.events.size && c["durations"] == p.durations.size && c["day_marks"] == p.marks.size) { "verify failed" }
    }

    /**
     * One-time import of past records (e.g. from a company attendance report). Merges: it never
     * deletes anything, skips days that already have punches, and keeps existing day marks.
     * File: {"app":"attendance-tracker","kind":"import","events":[{"date":"2026-11-11","time":"10:08","type":"CHECK_IN","detail":"…"}],
     *        "marks":[{"date":"2026-11-05","mark":"ABSENT"}]}
     * Returns a message for the user.
     */
    @Synchronized
    fun importRecords(ctx: Context, uri: Uri): String = runCatching {
        val json = JSONObject(read(ctx, uri))
        check(json.optString("app") == "attendance-tracker") { "That isn't an Attendance Tracker file." }
        if (json.optString("kind") == "settings") return@runCatching importSettings(ctx, json)
        check(json.optString("kind") == "import") { "That's a full backup \u2014 use \u201cRestore from backup file\u201d instead." }
        val zone = java.time.ZoneId.systemDefault()
        val evArr = json.optJSONArray("events") ?: JSONArray()
        val parsed = (0 until evArr.length()).map { i ->
            val o = evArr.getJSONObject(i)
            val day = LocalDate.parse(o.getString("date"))
            val t = java.time.LocalTime.parse(o.getString("time"))
            val type = com.attendance.tracker.data.EventType.valueOf(o.getString("type"))
            Triple(day, day.atTime(t).atZone(zone).toInstant().toEpochMilli(), type) to o.optString("detail", "Imported")
        }
        val mkArr = json.optJSONArray("marks") ?: JSONArray()
        val marks = (0 until mkArr.length()).associate { i ->
            val o = mkArr.getJSONObject(i)
            LocalDate.parse(o.getString("date")).toString() to com.attendance.tracker.core.DayMark.valueOf(o.getString("mark")).name
        }
        val db = AttendanceController.db
        // Skip any day that already has punches in the app, so nothing is doubled.
        val days = parsed.map { it.first.first }.toSet()
        val busy = days.filter { d ->
            db.events(d.atStartOfDay(zone).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                .any { it.type == com.attendance.tracker.data.EventType.CHECK_IN || it.type == com.attendance.tracker.data.EventType.CHECK_OUT }
        }.toSet()
        val toAdd = parsed.filter { it.first.first !in busy }.map { (k, detail) -> Triple(k.second, k.third, detail) }
        val existingMarks = db.dayMarks("0000-01-01", "9999-12-31")
        val newMarks = marks.filterKeys { it !in existingMarks && LocalDate.parse(it) !in busy }
        check(saveLocal(ctx, "before-import")) { "Couldn't save a safety copy first, so nothing was changed." }
        // Optional salary values (only the ones present are changed).
        val sal = json.optJSONObject("salary")
        if (sal != null) {
            val st = AttendanceController.settings
            var r = st.salaryRules
            if (sal.has("professionalTax")) r = r.copy(professionalTax = sal.getDouble("professionalTax"))
            if (sal.has("insurance")) r = r.copy(insurance = sal.getDouble("insurance"))
            if (sal.has("employerPfInSalary")) r = r.copy(employerPfInSalary = sal.getBoolean("employerPfInSalary"), pfEnabled = r.pfEnabled || sal.getBoolean("employerPfInSalary"))
            if (sal.has("monthlyGross")) r = r.copy(monthlyGross = sal.getDouble("monthlyGross"))
            st.saveSalaryRules(r, java.time.YearMonth.now())
        }
        db.importBatch(toAdd, newMarks)
        AttendanceController.reload()
        backupSoon(ctx)
        val addedDays = days.size - busy.size
        if (parsed.isEmpty() && marks.isEmpty() && sal != null) return@runCatching "Salary settings updated \u2713"
        "Imported $addedDays day${if (addedDays == 1) "" else "s"} (${toAdd.size} records, ${newMarks.size} marks)" +
            if (busy.isNotEmpty()) " · skipped ${busy.size} day${if (busy.size == 1) "" else "s"} already recorded" else ""
    }.getOrElse { "Import failed: ${it.message}. Nothing was changed." }

    /** Settings that describe your setup (not live tracking state) — what "settings only" moves. */
    private val RUNTIME_KEYS = setOf("presence", "heartbeat", "lastAtHome", "atHome", "lunchActive", "lunchStart", "dutyStart",
        "dutyReason", "pendingDutyOut", "pendingLunchIn", "pendingLunchOut", "tracking", "userPaused")

    /** Office map, gates, hours, lunch, holidays, home, salary, leave quotas… without any records. */
    fun exportSettingsFile(ctx: Context): File {
        val all = prefsJson(ctx, MAIN_PREFS)
        val keep = JSONObject()
        all.keys().forEach { k -> if (k !in RUNTIME_KEYS && !k.startsWith("once_")) keep.put(k, all.get(k)) }
        val o = JSONObject().put("app", "attendance-tracker").put("kind", "settings").put("createdAt", System.currentTimeMillis())
            .put("settings", keep)
        val f = File(File(ctx.cacheDir, "reports").apply { mkdirs() }, "attendance-settings.json")
        f.writeText(o.toString())
        return f
    }

    /** Applies a settings-only file; records and live tracking state are left alone. */
    private fun importSettings(ctx: Context, json: JSONObject): String {
        val s = json.getJSONObject("settings")
        check(saveLocal(ctx, "before-import")) { "Couldn't save a safety copy first, so nothing was changed." }
        val ed = ctx.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE).edit()
        var n = 0
        s.keys().forEach { k ->
            if (k in RUNTIME_KEYS || k.startsWith("once_")) return@forEach
            val e = s.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v")); "i" -> ed.putInt(k, e.getInt("v")); "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat()); "s" -> ed.putString(k, e.getString("v"))
                "ss" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
            n++
        }
        check(ed.commit()) { "couldn't save settings" }
        AttendanceController.reload()
        return "Settings imported ($n items) \u2713 \u2014 office map, gates, hours, salary and more"
    }

    /** Back up shortly after a change (punch, mark, settings), batching bursts of changes. */
    fun backupSoon(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "backup-soon", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(1, TimeUnit.MINUTES).build(),
            )
        }
    }

    /** Settings keys that change all the time on their own and are not worth a backup each. */
    private val NOISY_KEYS = setOf("heartbeat", "presence", "lastAtHome", "atHome")
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Watch for real changes (punches, marks, office map, settings) and back up a minute later. */
    fun watch(ctx: Context) {
        val app = ctx.applicationContext
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key !in NOISY_KEYS) backupSoon(app) }
        listener = l
        app.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(l)
    }

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "nightly-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build(),
        )
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ok = BackupManager.backupNow(applicationContext)
        // No file chosen → the on-phone copy is all we can do, which is fine.
        return if (ok || BackupManager.backupUri(applicationContext) == null) Result.success() else Result.retry()
    }
}
