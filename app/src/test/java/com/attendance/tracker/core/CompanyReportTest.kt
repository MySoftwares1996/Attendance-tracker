package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CompanyReportTest {
    @Test fun minutesDropTrailingZero() {
        assertEquals(610, CompanyReport.minutes("10.1"))
        assertEquals(570, CompanyReport.minutes("9.3"))
        assertEquals(28, CompanyReport.minutes("0.28"))
        assertEquals(600, CompanyReport.minutes("10"))
    }

    @Test fun parsesPastedTable() {
        val text = """Code	Emp Name	Date	Shift	Status	First In	Last Out	Out Side	Permission	Total Hours
PEVE01596	SOME NAME	11-Nov-2026	GS	XX	10.08	21.04	1.26	0	9.3
PEVE01596	SOME NAME	15-Nov-2026	WH	WH	0	0	0	0	0
PEVE01596	SOME NAME	04-Nov-2026	GS	XA	10.1	20.15	1.45	0	8.2"""
        val rows = CompanyReport.parse(text)
        assertEquals(3, rows.size)
        val r = rows[0]
        assertEquals(LocalDate.of(2026, 11, 11), r.date)
        assertEquals("XX", r.status)
        assertEquals(10 * 60 + 8, r.inMin); assertEquals(21 * 60 + 4, r.outMin)
        assertEquals(86, r.outsideMin); assertEquals(570, r.totalMin)
        assertEquals("WH", rows[1].status); assertEquals(false, rows[1].worked)
        assertEquals("XA", rows[2].status); assertEquals(500, rows[2].totalMin)
    }

    @Test fun parsesHtmlRows() {
        val html = "<tr><td>PEVE01596</td><td>A B</td><td>17-Nov-2026</td><td>GS</td><td>XX</td><td>10.17</td><td>20.17</td><td>0</td><td>0</td><td>10</td></tr>"
        val r = CompanyReport.parse(html).single()
        assertEquals(600, r.totalMin); assertEquals(0, r.outsideMin)
    }
}
