package com.attendance.tracker.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads a company attendance report (pasted text, CSV or the web page) with rows like
 * "11-Nov-2026  GS  XX  10.08  21.04  1.26  0  9.3": date, shift, status, first in, last out,
 * outside, permission, total. Times are hours.minutes with a dropped trailing zero ("10.1" = 10:10).
 */
object CompanyReport {
    data class Row(
        val date: LocalDate, val status: String,
        val inMin: Int, val outMin: Int, val outsideMin: Int, val permissionMin: Int, val totalMin: Int,
    ) {
        val worked: Boolean get() = inMin > 0 || outMin > 0 || totalMin > 0
    }

    private val dateRx = Regex("""\b(\d{1,2})[-/ ]([A-Za-z]{3})[-/ ](\d{4})\b""")
    private val numRx = Regex("""^\d{1,2}(\.\d{1,2})?$""")
    private val fmt = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)

    /** "10.1" → 610, "9.3" → 570, "0.28" → 28, "0" → 0. */
    fun minutes(v: String): Int {
        val (h, m) = if ('.' in v) v.substringBefore('.') to v.substringAfter('.') else v to ""
        val mm = if (m.isEmpty()) 0 else (m + "0").take(2).toInt()
        return h.toInt() * 60 + mm
    }

    /** Strips HTML tags so a saved report page can be read too. */
    fun plain(text: String): String = text
        .replace(Regex("(?i)</t[dh]>"), " ").replace(Regex("(?i)</tr>"), "\n")
        .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ")

    fun parse(text: String): List<Row> {
        val t = plain(text)
        val matches = dateRx.findAll(t).toList()
        return matches.mapIndexedNotNull { i, m ->
            val date = runCatching {
                LocalDate.parse("${m.groupValues[1]}-${m.groupValues[2].lowercase().replaceFirstChar { it.uppercase() }}-${m.groupValues[3]}", fmt)
            }.getOrNull() ?: return@mapIndexedNotNull null
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else t.length
            val tokens = t.substring(m.range.last + 1, end).split(Regex("\\s+|,|;|\\|")).filter { it.isNotBlank() }
            val codes = tokens.takeWhile { !numRx.matches(it) }.filter { it.length in 2..3 && it.all { c -> c.isLetter() } }
            val nums = tokens.dropWhile { !numRx.matches(it) }.takeWhile { numRx.matches(it) }
            if (nums.size < 5) return@mapIndexedNotNull null
            Row(date, codes.getOrElse(1) { codes.firstOrNull() ?: "" }.uppercase(),
                minutes(nums[0]), minutes(nums[1]), minutes(nums[2]), minutes(nums[3]), minutes(nums[4]))
        }.distinctBy { it.date }
    }
}
