package com.attendance.tracker.core

import kotlin.math.round

/** Everything that decides pay: earnings and every type of deduction (all editable). */
data class SalaryRules(
    val monthlyGross: Double = 0.0,
    /** Basic pay as % of gross (PF is calculated on basic). */
    val basicPercent: Double = 50.0,
    val overtimeRatePerHour: Double = 0.0,
    val pfEnabled: Boolean = true,
    val pfPercent: Double = 12.0,
    /** Statutory PF wage ceiling: PF on at most ₹15,000 basic. */
    val pfCapOn: Boolean = true,
    val pfCapBasic: Double = 15_000.0,
    /** Employer's PF share is part of your salary (CTC) and is taken off your pay too. */
    val employerPfInSalary: Boolean = false,
    val employerPfPercent: Double = 12.0,
    val esiEnabled: Boolean = true,
    val esiPercent: Double = 0.75,
    /** ESI applies only when gross is at or below this. */
    val esiGrossLimit: Double = 21_000.0,
    val professionalTax: Double = 0.0,
    /** Health / group insurance premium per month. */
    val insurance: Double = 0.0,
    val tds: Double = 0.0,
    val loanEmi: Double = 0.0,
    val deductAbsent: Boolean = true,
    val deductHalfDay: Boolean = true,
    val otherDeductions: List<Pair<String, Double>> = emptyList(),
    val otherEarnings: List<Pair<String, Double>> = emptyList(),
)

data class PayLine(val label: String, val amount: Double, val note: String = "")

data class Payslip(val earnings: List<PayLine>, val deductions: List<PayLine>) {
    val gross: Double get() = earnings.sumOf { it.amount }
    val totalDeductions: Double get() = deductions.sumOf { it.amount }
    val net: Double get() = gross - totalDeductions
}

object PayCalculator {
    private fun r2(v: Double) = round(v * 100) / 100

    /**
     * Builds the month's payslip.
     * One day's pay = gross ÷ working days. PF uses basic earned (after loss of pay), optionally capped.
     * ESI uses earned gross + overtime, only when monthly gross ≤ the ESI limit.
     */
    fun payslip(r: SalaryRules, workingDays: Int, overtimeSec: Long, absentDays: Int, halfDays: Int): Payslip {
        val perDay = if (workingDays > 0) r.monthlyGross / workingDays else 0.0
        val ot = r2(overtimeSec / 3600.0 * r.overtimeRatePerHour)
        val earnings = buildList {
            add(PayLine(if (r.employerPfInSalary) "CTC (monthly)" else "Salary (gross)", r.monthlyGross))
            if (ot > 0) add(PayLine("Overtime", ot, "%.1f h × ₹%.0f".format(overtimeSec / 3600.0, r.overtimeRatePerHour)))
            r.otherEarnings.filter { it.second > 0 }.forEach { add(PayLine(it.first, it.second)) }
        }
        val lopAbsent = if (r.deductAbsent) r2(absentDays * perDay) else 0.0
        val lopHalf = if (r.deductHalfDay) r2(halfDays * perDay / 2) else 0.0
        val paidFraction = if (r.monthlyGross > 0) ((r.monthlyGross - lopAbsent - lopHalf) / r.monthlyGross).coerceIn(0.0, 1.0) else 0.0
        val basicEarned = r.monthlyGross * r.basicPercent / 100 * paidFraction
        val pfBase = if (r.pfCapOn) minOf(basicEarned, r.pfCapBasic) else basicEarned
        val pf = if (r.pfEnabled) r2(pfBase * r.pfPercent / 100) else 0.0
        val employerPf = if (r.pfEnabled && r.employerPfInSalary) r2(pfBase * r.employerPfPercent / 100) else 0.0
        val esiBase = r.monthlyGross * paidFraction + ot
        val esi = if (r.esiEnabled && r.monthlyGross <= r.esiGrossLimit) r2(esiBase * r.esiPercent / 100) else 0.0
        val deductions = buildList {
            if (lopAbsent > 0) add(PayLine("Loss of pay – absent", lopAbsent, "$absentDays day${if (absentDays == 1) "" else "s"} × ₹%.0f".format(perDay)))
            if (lopHalf > 0) add(PayLine("Loss of pay – half days", lopHalf, "$halfDays × half day"))
            if (pf > 0) add(PayLine("Provident Fund (PF)", pf, "%.0f%% of basic ₹%,.0f".format(r.pfPercent, pfBase)))
            if (employerPf > 0) add(PayLine("Employer PF (part of salary)", employerPf, "%.0f%% of basic ₹%,.0f".format(r.employerPfPercent, pfBase)))
            if (esi > 0) add(PayLine("ESI", esi, "%.2f%% of ₹%,.0f".format(r.esiPercent, esiBase)))
            if (r.professionalTax > 0) add(PayLine("Professional tax", r.professionalTax))
            if (r.insurance > 0) add(PayLine("Insurance", r.insurance))
            if (r.tds > 0) add(PayLine("Income tax (TDS)", r.tds))
            if (r.loanEmi > 0) add(PayLine("Loan / salary advance", r.loanEmi))
            r.otherDeductions.filter { it.second > 0 }.forEach { add(PayLine(it.first, it.second)) }
        }
        return Payslip(earnings, deductions)
    }
}
