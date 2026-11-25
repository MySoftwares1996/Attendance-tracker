package com.attendance.tracker.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.PayLine
import com.attendance.tracker.core.Payslip
import com.attendance.tracker.core.SalaryRules
import com.attendance.tracker.report.MonthReport
import com.attendance.tracker.report.MonthReports
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private fun rs(v: Double) = "₹ %,.0f".format(v)

/** Everything about pay: take-home estimate, earnings, every deduction, and the rules behind them. */
@Composable
fun SalaryScreen() {
    val c = Soft.colors
    val ctx = LocalContext.current
    var month by remember { mutableStateOf(YearMonth.now()) }
    var rules by remember { mutableStateOf(AttendanceController.settings.salaryRules) }
    var tick by remember { mutableIntStateOf(0) }
    val report = rememberInBackground(month, tick) { MonthReports.build(month) }
    val slip = remember(report, rules) { if (rules.monthlyGross > 0) report?.payslip() else null }
    var editing by remember { mutableStateOf(rules.monthlyGross <= 0) }
    fun save(r: SalaryRules) { rules = r; AttendanceController.settings.salaryRules = r; tick++ }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.padding(top = 6.dp, start = 2.dp)) {
            Text("Earnings, deductions and take-home", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Text("Salary", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        }

        // Month switcher
        Row(
            Modifier.fillMaxWidth().clip(Soft.pillShape).background(c.card).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", Modifier.clip(CircleShape).pressScale { month = month.minusMonths(1) }.padding(horizontal = 18.dp, vertical = 6.dp),
                style = MaterialTheme.typography.headlineSmall, color = c.accent)
            Text(month.format(monthFmt), Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            val canNext = month < YearMonth.now()
            Text("›", Modifier.clip(CircleShape).then(if (canNext) Modifier.pressScale { month = month.plusMonths(1) } else Modifier)
                .padding(horizontal = 18.dp, vertical = 6.dp),
                style = MaterialTheme.typography.headlineSmall, color = if (canNext) c.accent else c.hairline)
        }

        if (slip != null && report != null) {
            TakeHomeHero(slip, month == YearMonth.now())
            BreakdownCard("Earnings", slip.earnings, c.success, "+")
            BreakdownCard("Deductions", slip.deductions, c.danger, "−",
                empty = "No deductions this month")
            AttendanceBasis(report)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Payslip PDF", {
                    runCatching { com.attendance.tracker.report.ReportExporter.sharePayslipPdf(ctx, report, slip) }
                }, Modifier.weight(1f))
                SoftGhostButton(if (editing) "Hide setup" else "Edit salary", { editing = !editing }, Modifier.weight(1f))
            }
        } else {
            SoftCard {
                CardHeader(painterResource(R.drawable.ic_rupee), "Set up your salary", "Takes a minute · stays on this phone", c.success, c.successSoft)
                Spacer(Modifier.height(8.dp))
                Text("Enter your monthly salary and the deductions on your payslip (PF, ESI, professional tax, TDS, loan…). " +
                    "The app then works out your take-home from your real attendance and overtime.",
                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        }

        if (rules.monthlyGross > 0) YearSummaryCard(month.year, rules)
        AnimatedVisibility(editing, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SalaryEditor(rules, ::save)
        }
        Text("Estimates only — your company's payslip is final. One day's pay = salary ÷ working days in the month (working days minus holidays).",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(NavBarClearance))
    }
}

@Composable
private fun TakeHomeHero(p: Payslip, current: Boolean) {
    val shape = Soft.cardShape
    val a = Color(0xFF34D3A0); val b = Color(0xFF12A97A)
    Column(
        Modifier.fillMaxWidth().shadow(24.dp, shape, ambientColor = b.copy(alpha = 0.4f), spotColor = b.copy(alpha = 0.4f))
            .clip(shape).background(Brush.linearGradient(listOf(a, b))).padding(22.dp),
    ) {
        Text(if (current) "TAKE-HOME SO FAR (ESTIMATE)" else "TAKE-HOME (ESTIMATE)", color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall)
        Text(rs(p.net), color = Color.White, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                Text(if (AttendanceController.settings.salaryRules.employerPfInSalary) "CTC" else "Gross", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                Text(rs(p.gross), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Column {
                Text("Deductions", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                Text(rs(p.totalDeductions), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun BreakdownCard(title: String, lines: List<PayLine>, color: Color, sign: String, empty: String = "") {
    val c = Soft.colors
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(rs(lines.sumOf { it.amount }), style = MaterialTheme.typography.titleMedium, color = color)
        }
        Spacer(Modifier.height(8.dp))
        if (lines.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    if (l.note.isNotBlank()) Text(l.note, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Text("$sign ${rs(l.amount)}", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun AttendanceBasis(r: MonthReport) {
    val c = Soft.colors
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_clock), "Based on your attendance", r.month.format(monthFmt))
        Spacer(Modifier.height(8.dp))
        InfoLine("Working days (excl. holidays)", "${r.workingDays}")
        InfoLine("Days present", "${r.presentDays}")
        InfoLine("Absent (no punches, no leave)", "${r.absentDays}", if (r.absentDays > 0) c.danger else c.textPrimary)
        InfoLine("Half days", "${r.halfDays}")
        InfoLine("Leave days (paid)", "${r.leaveDays}")
        InfoLine("Holidays (paid)", "${r.holidayDays}")
        InfoLine("Overtime", fmtDuration(r.overtimeSec), Color(0xFF6A4FE8))
    }
}

// ───────────────────────────── editor ─────────────────────────────

@Composable
private fun SalaryEditor(r: SalaryRules, save: (SalaryRules) -> Unit) {
    val c = Soft.colors
    val netH = AttendanceController.settings.settings.value.schedule.netMinutes / 60.0
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SoftCard {
            CardHeader(painterResource(R.drawable.ic_rupee), "Earnings", null, c.success, c.successSoft)
            Spacer(Modifier.height(12.dp))
            MoneyField(if (r.employerPfInSalary) "Monthly CTC (salary before all deductions)" else "Monthly gross salary", r.monthlyGross) { save(r.copy(monthlyGross = it)) }
            if (r.employerPfInSalary) Text("Employer PF, employee PF, PT and insurance are all taken from this CTC.",
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(10.dp))
            MoneyField("Overtime pay per hour", r.overtimeRatePerHour) { save(r.copy(overtimeRatePerHour = it)) }
            if (r.monthlyGross > 0 && r.overtimeRatePerHour == 0.0) {
                val hourly = r.monthlyGross / 26 / netH
                Spacer(Modifier.height(8.dp))
                MiniAction("Use your hourly rate: ₹ %.0f / h".format(hourly)) { save(r.copy(overtimeRatePerHour = Math.round(hourly).toDouble())) }
            }
            Spacer(Modifier.height(10.dp))
            CustomItems("Other earnings (incentive, allowance…)", r.otherEarnings) { save(r.copy(otherEarnings = it)) }
        }

        SoftCard {
            CardHeader(painterResource(R.drawable.ic_shield), "Statutory deductions", "As on most Indian payslips", c.danger, c.dangerSoft)
            Spacer(Modifier.height(6.dp))
            SoftSwitchRow("Provident Fund (PF)", "Employee share, % of basic", r.pfEnabled) { save(r.copy(pfEnabled = it)) }
            if (r.pfEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { NumberField(if (r.employerPfInSalary) "Basic (% of CTC)" else "Basic (% of gross)", r.basicPercent, "%") { save(r.copy(basicPercent = it.coerceIn(1.0, 100.0))) } }
                    Box(Modifier.weight(1f)) { NumberField("PF rate", r.pfPercent, "%") { save(r.copy(pfPercent = it.coerceIn(0.0, 30.0))) } }
                }
                SoftSwitchRow("Limit PF to ₹ 15,000 basic", "Statutory ceiling → max ₹ 1,800 / month", r.pfCapOn) { save(r.copy(pfCapOn = it)) }
                SoftSwitchRow("Employer PF is part of my salary", "Employer share also comes off your pay (CTC)", r.employerPfInSalary) { save(r.copy(employerPfInSalary = it)) }
                if (r.employerPfInSalary) NumberField("Employer PF rate", r.employerPfPercent, "%") { save(r.copy(employerPfPercent = it.coerceIn(0.0, 30.0))) }
            }
            SoftSwitchRow("ESI", "0.75% of gross · only when gross ≤ ₹ 21,000", r.esiEnabled) { save(r.copy(esiEnabled = it)) }
            if (r.esiEnabled) NumberField("ESI rate", r.esiPercent, "%") { save(r.copy(esiPercent = it.coerceIn(0.0, 10.0))) }
            Spacer(Modifier.height(10.dp))
            MoneyField("Professional tax (per month)", r.professionalTax) { save(r.copy(professionalTax = it)) }
            Spacer(Modifier.height(10.dp))
            MoneyField("Insurance (per month)", r.insurance) { save(r.copy(insurance = it)) }
            Spacer(Modifier.height(10.dp))
            MoneyField("Income tax / TDS (per month)", r.tds) { save(r.copy(tds = it)) }
        }

        SoftCard {
            CardHeader(painterResource(R.drawable.ic_clock), "Attendance deductions", "Loss of pay (LOP)", c.warning, c.warningSoft)
            Spacer(Modifier.height(6.dp))
            SoftSwitchRow("Absent days", "Working day with no punches and no leave mark", r.deductAbsent) { save(r.copy(deductAbsent = it)) }
            SoftSwitchRow("Half days", "Half a day's pay", r.deductHalfDay) { save(r.copy(deductHalfDay = it)) }
            Text("Leave, sick leave, WFH and holidays are paid.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }

        SoftCard {
            CardHeader(painterResource(R.drawable.ic_rupee), "Other deductions", "Loans, advances and anything else", c.accent, c.accentSoft)
            Spacer(Modifier.height(12.dp))
            MoneyField("Loan / salary advance (per month)", r.loanEmi) { save(r.copy(loanEmi = it)) }
            Spacer(Modifier.height(10.dp))
            CustomItems("Your own deductions (canteen, transport, insurance…)", r.otherDeductions) { save(r.copy(otherDeductions = it)) }
        }
    }
}

@Composable
private fun MoneyField(label: String, value: Double, onChange: (Double) -> Unit) =
    NumberField(label, value, "₹", prefix = true, onChange = onChange)

@Composable
private fun NumberField(label: String, value: Double, unit: String, prefix: Boolean = false, onChange: (Double) -> Unit) {
    val c = Soft.colors
    var text by remember(label) { mutableStateOf(if (value == 0.0) "" else if (value % 1.0 == 0.0) "%.0f".format(value) else value.toString()) }
    val unitText: @Composable () -> Unit = { Text(if (prefix) "$unit " else unit, color = c.textSecondary) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() || it == '.' }
            onChange(text.toDoubleOrNull() ?: 0.0)
        },
        label = { Text(label) }, singleLine = true,
        prefix = if (prefix) unitText else null,
        suffix = if (!prefix) unitText else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = c.card, unfocusedContainerColor = c.cardMuted, cursorColor = c.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A small list of named amounts with add / remove. */
@Composable
private fun CustomItems(title: String, items: List<Pair<String, Double>>, onChange: (List<Pair<String, Double>>) -> Unit) {
    val c = Soft.colors
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
    Spacer(Modifier.height(6.dp))
    items.forEachIndexed { i, (n, a) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(Soft.tileShape).background(c.cardMuted)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(rs(a), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text("✕", Modifier.clip(CircleShape).pressScale { onChange(items.filterIndexed { j, _ -> j != i }) }.padding(10.dp),
                color = c.textSecondary)
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it }, Modifier.weight(1.4f), singleLine = true, placeholder = { Text("Name") },
            shape = RoundedCornerShape(16.dp))
        OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f), singleLine = true,
            placeholder = { Text("₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp))
        Text("Add", Modifier.clip(Soft.pillShape).background(c.accent).pressScale {
            val a = amount.toDoubleOrNull()
            if (name.isNotBlank() && a != null && a > 0) { onChange(items + (name.trim() to a)); name = ""; amount = "" }
        }.padding(horizontal = 14.dp, vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Month-by-month for the year: take-home, PF saved (both shares), insurance and loss of pay. */
@Composable
private fun YearSummaryCard(year: Int, rules: SalaryRules) {
    val c = Soft.colors
    var open by remember { mutableStateOf(false) }
    val rows = rememberInBackground(year, rules, open) {
        if (!open) emptyList() else (1..12).map { YearMonth.of(year, it) }.filter { !it.isAfter(YearMonth.now()) }.mapNotNull { ym ->
            runCatching { MonthReports.build(ym) }.getOrNull()?.takeIf { it.presentDays > 0 || it.leaveDays > 0 }?.payslip()?.let { p ->
                fun sum(prefix: String) = p.deductions.filter { it.label.startsWith(prefix) }.sumOf { it.amount }
                listOf(ym.format(java.time.format.DateTimeFormatter.ofPattern("MMM")), p.net,
                    sum("Provident") + sum("Employer PF"), sum("Insurance"), sum("Loss of pay"))
            }
        }
    }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$year summary", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            MiniAction(if (open) "Hide" else "Show") { open = !open }
        }
        if (open) {
            if (rows == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            else if (rows.isEmpty()) Text("No months with attendance yet.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            else {
                Spacer(Modifier.height(8.dp))
                val headers = listOf("Month", "Take-home", "PF saved", "Insurance", "LOP")
                Row { headers.forEachIndexed { i, h -> Text(h, Modifier.weight(if (i == 0) 0.8f else 1f), style = MaterialTheme.typography.labelSmall, color = c.accent) } }
                rows.forEach { r ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(r[0] as String, Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                        (1..4).forEach { i -> Text("%,.0f".format(r[i] as Double), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textPrimary) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("Total", Modifier.weight(0.8f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    (1..4).forEach { i -> Text("%,.0f".format(rows.sumOf { it[i] as Double }), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary) }
                }
            }
        }
    }
}
