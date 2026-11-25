package com.attendance.tracker.report

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.attendance.tracker.AttendanceController
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Exports a month as PDF or Excel-compatible CSV and opens the share sheet. */
object ReportExporter {
    private val zone = ZoneId.systemDefault()
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMM, EEE")

    private fun t(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        ?.let { "%d:%02d %s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM") } ?: ""
    private fun hm(sec: Long) = if (sec <= 0) "" else "%d:%02d".format(sec / 3600, (sec % 3600) / 60)
    private fun punchList(r: DayReport) = r.punches.joinToString(" | ") {
        (if (it.type.name == "CHECK_IN") "IN " else "OUT ") + t(it.timeMs) +
            (if (it.detail.startsWith(AttendanceController.MANUAL_PREFIX)) " (manual)" else "") +
            (if (it.detail.contains(com.attendance.tracker.security.TrustChecks.NOT_VERIFIED)) " (not verified)" else "")
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "reports").apply { mkdirs() }

    fun shareCsv(ctx: Context, r: MonthReport) {
        val f = File(dir(ctx), "attendance-${r.month}.csv")
        val sb = StringBuilder("﻿") // BOM so Excel reads UTF-8 correctly
        sb.append("Date,Day,First IN,Last OUT,Net worked (h:mm),Lunch (h:mm),Overtime (h:mm),Late,Day mark,Punches\n")
        r.days.filter { it.workedSec > 0 || it.mark != null || it.punches.isNotEmpty() }.forEach { d ->
            fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            sb.append(listOf(
                d.date.toString(), d.date.dayOfWeek.name.take(3), t(d.firstInMs), t(d.lastOutMs),
                hm(d.workedSec), hm(d.lunchSec), hm(d.overtimeSec), if (d.late) "Yes" else "", d.mark?.label ?: "", punchList(d),
            ).joinToString(",") { q(it) }).append('\n')
        }
        sb.append('\n')
        sb.append("\"Days present\",${r.presentDays}\n\"Average per day\",\"${hm(r.avgSec ?: 0)}\"\n")
        sb.append("\"Total worked\",\"${hm(r.totalSec)}\"\n\"Overtime\",\"${hm(r.overtimeSec)}\"\n")
        sb.append("\"Late days\",${r.lateDays}\n\"Leave days\",${r.leaveDays}\n")
        f.writeText(sb.toString())
        share(ctx, f, "text/csv", "Attendance ${r.month.format(monthFmt)}")
    }

    fun sharePdf(ctx: Context, r: MonthReport) {
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 36f
        val title = Paint().apply { textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(28, 32, 51) }
        val head = Paint().apply { textSize = 8.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(91, 108, 255) }
        val body = Paint().apply { textSize = 8.5f; color = Color.rgb(28, 32, 51) }
        val sub = Paint().apply { textSize = 9.5f; color = Color.rgb(122, 128, 153) }
        val line = Paint().apply { color = Color.rgb(232, 234, 242); strokeWidth = 0.8f }
        val cols = floatArrayOf(M, M + 78, M + 140, M + 202, M + 252, M + 296, M + 344, M + 378)
        val headers = listOf("Date", "First IN", "Last OUT", "Net", "Lunch", "Overtime", "Late", "Mark / note")
        val office = AttendanceController.settings.settings.value.office?.name ?: "Office"

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            val c = page!!.canvas
            y = M + 10
            if (pageNo == 1) {
                c.drawText("Attendance report — ${r.month.format(monthFmt)}", M, y, title); y += 18
                c.drawText(office, M, y, sub); y += 22
                val stats = listOf(
                    "Days present" to "${r.presentDays}", "Average / day" to hm(r.avgSec ?: 0).ifBlank { "—" },
                    "Total worked" to hm(r.totalSec).ifBlank { "0:00" }, "Overtime" to hm(r.overtimeSec).ifBlank { "0:00" },
                    "Late days" to "${r.lateDays}", "Leave days" to "${r.leaveDays}",
                )
                stats.forEachIndexed { i, (k, v) ->
                    val x = M + (i % 3) * 175f; val yy = y + (i / 3) * 30f
                    c.drawText(k, x, yy, sub)
                    c.drawText(v, x, yy + 13, title.apply { textSize = 13f })
                }
                title.textSize = 18f
                y += 72
            }
            headers.forEachIndexed { i, h -> c.drawText(h.uppercase(), cols[i], y, head) }
            y += 6; c.drawLine(M, y, W - M, y, line); y += 13
        }
        newPage()
        r.days.forEach { d ->
            if (d.workedSec == 0L && d.mark == null && d.punches.isEmpty()) return@forEach
            if (y > H - M) newPage()
            val c = page!!.canvas
            val manual = d.punches.count { it.detail.startsWith(AttendanceController.MANUAL_PREFIX) }
            val cells = listOf(
                d.date.format(dateFmt), t(d.firstInMs), t(d.lastOutMs), hm(d.workedSec), hm(d.lunchSec), hm(d.overtimeSec),
                if (d.late) "Late" else "",
                listOfNotNull(d.mark?.label, if (manual > 0) "$manual manual punch${if (manual > 1) "es" else ""}" else null).joinToString(", "),
            )
            cells.forEachIndexed { i, v -> c.drawText(v, cols[i], y, body) }
            y += 6; c.drawLine(M, y, W - M, y, line); y += 12
        }
        page?.let { doc.finishPage(it) }
        val f = File(dir(ctx), "attendance-${r.month}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        share(ctx, f, "application/pdf", "Attendance ${r.month.format(monthFmt)}")
    }

    private fun share(ctx: Context, f: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
