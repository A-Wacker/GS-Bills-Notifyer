package com.awacker.billsnotifier.domain.sync

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Money
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import com.awacker.billsnotifier.domain.schedule.ScheduleGenerator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mirror rests entirely on this: a plan written to the sheet must come back identical.
 * If it doesn't, the mirror device shows something subtly different from the phone that
 * owns the data — and nobody would notice until a payment was missed.
 */
class SheetParserTest {

    private val today = LocalDate.parse("2026-03-15")
    private val updatedAt = "2026-03-15T07:00:00"

    /** Writes a bill through the mapper and reads it straight back, as the sheet would. */
    private fun roundTrip(bill: Bill, isArchived: Boolean = false): Bill? {
        val occurrences = ScheduleGenerator.generate(bill)
        val row = SheetMapper.billRow(bill, occurrences, today, updatedAt, isArchived)
        return SheetParser.billFrom(row.asSheetText())
    }

    /** Sheets hand rows back as display strings, so flatten the typed values the same way. */
    private fun Map<String, SheetValue>.asSheetText(): Map<String, String> = mapValues { (_, value) ->
        when (value) {
            is SheetValue.Text -> value.value
            is SheetValue.Number -> value.value.toPlainString()
            is SheetValue.Flag -> if (value.value) "TRUE" else "FALSE"
        }
    }

    private fun plan(
        recurrence: Recurrence = Recurrence.Monthly(),
        end: PlanEnd = PlanEnd.AfterPayments(12),
    ) = Bill(
        id = "car-loan",
        name = "Car loan",
        payee = "First Credit Union",
        installmentAmountCents = 25_000,
        recurrence = recurrence,
        firstDueDate = LocalDate.parse("2026-01-15"),
        end = end,
        notes = "Account #1234",
    )

    @Nested
    inner class RoundTrip {

        @Test
        fun `every recurrence type survives the sheet`() {
            val cases = listOf(
                Recurrence.Weekly(1),
                Recurrence.Weekly(2),
                Recurrence.Monthly(1),
                Recurrence.Monthly(3),
                Recurrence.Yearly(1),
                Recurrence.Yearly(2),
                Recurrence.SemiMonthly(1, 15),
                Recurrence.SemiMonthly(5, 20),
            )
            for (recurrence in cases) {
                assertEquals(
                    recurrence,
                    roundTrip(plan(recurrence = recurrence))?.recurrence,
                    "$recurrence did not survive the round trip",
                )
            }
        }

        @Test
        fun `both plan endings survive the sheet`() {
            assertEquals(
                PlanEnd.AfterPayments(24),
                roundTrip(plan(end = PlanEnd.AfterPayments(24)))?.end,
            )
            val byDate = PlanEnd.OnOrBefore(LocalDate.parse("2027-06-30"))
            assertEquals(byDate, roundTrip(plan(end = byDate))?.end)
        }

        @Test
        fun `the whole plan comes back unchanged`() {
            val original = plan().copy(autopay = true, finalAmountCents = 24_500)
            assertEquals(original, roundTrip(original))
        }

        @Test
        fun `a plan with no optional fields comes back unchanged`() {
            val bare = plan().copy(payee = null, notes = null, finalAmountCents = null)
            assertEquals(bare, roundTrip(bare))
        }

        @Test
        fun `notifications switched off survives, so a mirror stays quiet too`() {
            val quiet = plan().copy(notificationsEnabled = false)
            assertEquals(false, roundTrip(quiet)?.notificationsEnabled)
        }

        @Test
        fun `an odd final payment survives, so the mirror totals match`() {
            val withRemainder = plan().copy(installmentAmountCents = 8_333, finalAmountCents = 8_337)
            assertEquals(8_337, roundTrip(withRemainder)?.finalAmountCents)
        }
    }

    @Nested
    inner class Occurrences {

        private fun occurrenceRows(bill: Bill, paidSequences: Set<Int> = emptySet()) =
            ScheduleGenerator.generate(bill)
                .map { if (it.sequence in paidSequences) it.copy(paidOn = it.dueDate) else it }
                .map { SheetMapper.occurrenceRow(it, bill.name, 12, updatedAt).asSheetText() }

        @Test
        fun `occurrences come back with their dates and amounts`() {
            val bill = plan()
            val parsed = occurrenceRows(bill).mapNotNull(SheetParser::occurrenceFrom)

            assertEquals(12, parsed.size)
            assertEquals(LocalDate.parse("2026-01-15"), parsed.first().dueDate)
            assertEquals(25_000, parsed.first().amountCents)
            assertEquals(listOf(1, 2, 3), parsed.take(3).map { it.sequence })
        }

        @Test
        fun `paid state survives, so a mirror does not announce settled payments`() {
            val parsed = occurrenceRows(plan(), paidSequences = setOf(1, 2))
                .mapNotNull(SheetParser::occurrenceFrom)

            assertEquals(listOf(1, 2), parsed.filter { it.isPaid }.map { it.sequence })
            assertTrue(parsed.drop(2).none { it.isPaid })
        }

        @Test
        fun `a row marked paid without a date still counts as paid`() {
            val row = occurrenceRows(plan(), paidSequences = setOf(1)).first()
                .toMutableMap()
                .apply { this["paid_on"] = "" }

            val parsed = SheetParser.occurrenceFrom(row)
            assertNotNull(parsed)
            assertTrue(parsed.isPaid, "a paid flag with no date must not reappear as unpaid")
        }
    }

    @Nested
    inner class WholePlans {

        @Test
        fun `plans are assembled with their own occurrences`() {
            val car = plan()
            val dentist = plan().copy(id = "dentist", name = "Dentist", installmentAmountCents = 5_000)

            val billRows = listOf(car, dentist).map {
                SheetMapper.billRow(it, ScheduleGenerator.generate(it), today, updatedAt).asSheetText()
            }
            val occurrenceRows = listOf(car, dentist).flatMap { bill ->
                ScheduleGenerator.generate(bill).map {
                    SheetMapper.occurrenceRow(it, bill.name, 12, updatedAt).asSheetText()
                }
            }

            val plans = SheetParser.plansFrom(billRows, occurrenceRows)

            assertEquals(2, plans.size)
            assertEquals(listOf("car-loan", "dentist"), plans.map { it.bill.id })
            assertTrue(plans.all { it.occurrences.size == 12 })
            assertTrue(
                plans.all { p -> p.occurrences.all { it.billId == p.bill.id } },
                "occurrences must not leak between plans",
            )
        }

        @Test
        fun `a fully paid plan is not treated as archived`() {
            val bill = plan()
            val paid = ScheduleGenerator.generate(bill).map { it.copy(paidOn = it.dueDate) }
            val billRow = SheetMapper.billRow(bill, paid, today, updatedAt).asSheetText()
            val occurrenceRows = paid.map {
                SheetMapper.occurrenceRow(it, bill.name, 12, updatedAt).asSheetText()
            }

            val plans = SheetParser.plansFrom(listOf(billRow), occurrenceRows)

            // "active" goes false when a plan is paid off, which must not read as archived.
            assertEquals(false, plans.single().isArchived)
        }

        @Test
        fun `an archived plan is recognised`() {
            val bill = plan()
            val occurrences = ScheduleGenerator.generate(bill)
            val billRow = SheetMapper
                .billRow(bill, occurrences, today, updatedAt, isArchived = true)
                .asSheetText()
            val occurrenceRows = occurrences.map {
                SheetMapper.occurrenceRow(it, bill.name, 12, updatedAt).asSheetText()
            }

            assertEquals(true, SheetParser.plansFrom(listOf(billRow), occurrenceRows).single().isArchived)
        }
    }

    /**
     * The sheet is a document a human can edit, so malformed rows are expected. One bad row
     * must cost one plan, never the whole download.
     */
    @Nested
    inner class BadRows {

        private fun validRow() = SheetMapper
            .billRow(plan(), ScheduleGenerator.generate(plan()), today, updatedAt)
            .asSheetText()

        @Test
        fun `a row missing its id is skipped`() {
            assertNull(SheetParser.billFrom(validRow() + ("bill_id" to "")))
        }

        @Test
        fun `a row with an unreadable date is skipped`() {
            assertNull(SheetParser.billFrom(validRow() + ("first_due" to "15/01/2026")))
        }

        @Test
        fun `a row with an unknown recurrence is skipped`() {
            assertNull(SheetParser.billFrom(validRow() + ("recurrence_type" to "FORTNIGHTLY")))
        }

        @Test
        fun `a row with a zero amount is skipped`() {
            assertNull(SheetParser.billFrom(validRow() + ("installment_amount" to "0")))
        }

        @Test
        fun `one bad row does not take the others with it`() {
            val good = validRow()
            val bad = good + ("bill_id" to "")
            val other = SheetMapper
                .billRow(
                    plan().copy(id = "dentist", name = "Dentist"),
                    ScheduleGenerator.generate(plan()),
                    today,
                    updatedAt,
                )
                .asSheetText()

            val plans = SheetParser.plansFrom(listOf(good, bad, other), emptyList())
            assertEquals(listOf("car-loan", "dentist"), plans.map { it.bill.id })
        }
    }

    @Nested
    inner class SheetDisplayForms {

        @Test
        fun `currency-formatted amounts parse back`() {
            val row = SheetMapper
                .billRow(plan(), ScheduleGenerator.generate(plan()), today, updatedAt)
                .asSheetText()
                .toMutableMap()
                .apply { this["installment_amount"] = "$250.00" }

            assertEquals(25_000, SheetParser.billFrom(row)?.installmentAmountCents)
        }

        @Test
        fun `the several spellings of true are all understood`() {
            val base = SheetMapper
                .billRow(plan(), ScheduleGenerator.generate(plan()), today, updatedAt)
                .asSheetText()

            for (spelling in listOf("TRUE", "true", "Yes", "1")) {
                assertEquals(
                    true,
                    SheetParser.billFrom(base + ("autopay" to spelling))?.autopay,
                    "$spelling should read as true",
                )
            }
            assertEquals(false, SheetParser.billFrom(base + ("autopay" to "FALSE"))?.autopay)
        }

        @Test
        fun `a sheet predating the notifications column still notifies`() {
            val row = SheetMapper
                .billRow(plan(), ScheduleGenerator.generate(plan()), today, updatedAt)
                .asSheetText() + ("notifications_enabled" to "")

            assertEquals(true, SheetParser.billFrom(row)?.notificationsEnabled)
        }
    }
}
