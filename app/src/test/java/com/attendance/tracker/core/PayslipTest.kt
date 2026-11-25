package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayslipTest {
    private fun line(p: Payslip, label: String) = (p.earnings + p.deductions).firstOrNull { it.label.startsWith(label) }?.amount

    @Test fun fullMonthWithPfCapAndNoEsi() {
        val r = SalaryRules(monthlyGross = 45_000.0, basicPercent = 50.0, overtimeRatePerHour = 300.0,
            professionalTax = 200.0, tds = 1_500.0, loanEmi = 2_000.0)
        val p = PayCalculator.payslip(r, workingDays = 25, overtimeSec = 10 * 3600L, absentDays = 0, halfDays = 0)
        assertEquals(3_000.0, line(p, "Overtime")!!, 0.01)
        assertEquals(1_800.0, line(p, "Provident Fund")!!, 0.01)   // 12% of capped ₹15,000
        assertNull(line(p, "ESI"))                                   // gross above ₹21,000
        assertEquals(48_000.0, p.gross, 0.01)
        assertEquals(1_800.0 + 200 + 1_500 + 2_000, p.totalDeductions, 0.01)
        assertEquals(48_000.0 - 5_500, p.net, 0.01)
    }

    @Test fun lossOfPayAndEsi() {
        val r = SalaryRules(monthlyGross = 20_000.0, basicPercent = 50.0, pfCapOn = false,
            otherDeductions = listOf("Canteen" to 300.0), otherEarnings = listOf("Incentive" to 1_000.0))
        val p = PayCalculator.payslip(r, workingDays = 25, overtimeSec = 0, absentDays = 2, halfDays = 1)
        assertEquals(1_600.0, line(p, "Loss of pay – absent")!!, 0.01)   // 2 × 800
        assertEquals(400.0, line(p, "Loss of pay – half")!!, 0.01)
        // earned = 18,000 → basic 9,000 → PF 1,080; ESI 0.75% of 18,000 = 135
        assertEquals(1_080.0, line(p, "Provident Fund")!!, 0.01)
        assertEquals(135.0, line(p, "ESI")!!, 0.01)
        assertEquals(300.0, line(p, "Canteen")!!, 0.01)
        assertEquals(21_000.0, p.gross, 0.01)
        assertEquals(21_000.0 - (1_600 + 400 + 1_080 + 135 + 300), p.net, 0.01)
    }

    @Test fun switchedOffDeductions() {
        val r = SalaryRules(monthlyGross = 30_000.0, pfEnabled = false, esiEnabled = false, deductAbsent = false, deductHalfDay = false)
        val p = PayCalculator.payslip(r, 26, 0, absentDays = 3, halfDays = 2)
        assertEquals(30_000.0, p.net, 0.01)
    }

    @Test fun employerPfAndInsuranceAreDeducted() {
        val r = SalaryRules(monthlyGross = 40_000.0, esiEnabled = false, employerPfInSalary = true, insurance = 1_658.0, professionalTax = 200.0)
        val p = PayCalculator.payslip(r, 26, 0, 0, 0)
        // basic 20,000 capped at 15,000 → employee 1,800 + employer 1,800; PT 200 + insurance 1,658 = 1,858
        assertEquals(1800.0 + 1800.0 + 1858.0, p.totalDeductions, 0.01)
        assertEquals(40_000.0 - 5_458.0, p.net, 0.01)
    }

    @Test fun dayBasisChangesLossOfPay() {
        val base = SalaryRules(monthlyGross = 30_000.0, pfEnabled = false, esiEnabled = false)
        fun lop(b: String) = PayCalculator.payslip(base.copy(dayBasis = b), 25, 0, 1, 0, calendarDays = 30)
            .deductions.first { it.label.startsWith("Loss of pay") }.amount
        assertEquals(1200.0, lop("working"), 0.01)   // 30,000 ÷ 25
        assertEquals(1000.0, lop("calendar"), 0.01)  // ÷ 30
        assertEquals(1153.85, lop("26"), 0.01)
    }

    @Test fun gratuityLine() {
        val r = SalaryRules(monthlyGross = 40_000.0, basicPercent = 50.0, pfEnabled = false, esiEnabled = false, gratuityInCtc = true)
        val g = PayCalculator.payslip(r, 26, 0, 0, 0).deductions.single { it.label.startsWith("Gratuity") }
        assertEquals(962.0, g.amount, 0.01)          // 4.81% of 20,000
    }

    @Test fun newRegimeTax() {
        assertEquals(0.0, PayCalculator.annualTaxNewRegime(1_275_000.0), 0.01)   // 12L taxable → rebate
        // 15L salary → 14.25L taxable: 0 + 20k + 40k + 33,750 = 93,750 + 4% cess
        assertEquals(97_500.0, PayCalculator.annualTaxNewRegime(1_500_000.0), 0.01)
        // Just above 12L taxable: marginal relief caps tax at the excess
        assertEquals(1_040.0, PayCalculator.annualTaxNewRegime(1_276_000.0), 0.01)
    }
}
