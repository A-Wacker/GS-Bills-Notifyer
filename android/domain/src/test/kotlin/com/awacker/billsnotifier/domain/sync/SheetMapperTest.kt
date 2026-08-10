package com.awacker.billsnotifier.domain.sync

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import com.awacker.billsnotifier.domain.schedule.ScheduleGenerator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SheetMapperTest {

    private val plan = Bill(
        id = "car-loan",
        name = "Car loan",
        payee = "First Credit Union",
        installmentAmountCents = 25_000,
        recurrence = Recurrence.Monthly(),
        firstDueDate = LocalDate.parse("2026-01-15"),
        end = PlanEnd.AfterPayments(4),
    )
    private val schedule = ScheduleGenerator.generate(plan)
    private val updatedAt = "2026-01-10T08:00:00"

    private fun billRow(
        bill: Bill = plan,
        occurrences: List<com.awacker.billsnotifier.domain.model.Occurrence> = schedule,
        today: String = "2026-01-01",
        archived: Boolean = false,
    ) = SheetMapper.billRow(bill, occurrences, LocalDate.parse(today), updatedAt, archived)

    private fun text(row: Map<String, SheetValue>, column: String) =
        (row.getValue(column) as SheetValue.Text).value

    private fun number(row: Map<String, SheetValue>, column: String) =
        (row.getValue(column) as SheetValue.Number).value

    private fun flag(row: Map<String, SheetValue>, column: String) =
        (row.getValue(column) as SheetValue.Flag).value

    @Nested
    inner class Completeness {

        @Test
        fun `a bill row supplies every declared column and nothing extra`() {
            assertEquals(SheetSchema.BILL_COLUMNS.toSet(), billRow().keys)
        }

        @Test
        fun `an occurrence row supplies every declared column and nothing extra`() {
            val row = SheetMapper.occurrenceRow(schedule.first(), "Car loan", 4, updatedAt)
            assertEquals(SheetSchema.OCCURRENCE_COLUMNS.toSet(), row.keys)
        }
    }

    @Nested
    inner class Types {

        /**
         * Amounts must be numbers so the sheet can total them and format as currency;
         * dates must be text so Sheets does not coerce them into shifted Date objects.
         */
        @Test
        fun `amounts are numbers and dates are text`() {
            val row = SheetMapper.occurrenceRow(schedule.first(), "Car loan", 4, updatedAt)
            assertTrue(row["amount"] is SheetValue.Number)
            assertTrue(row["due_date"] is SheetValue.Text)
            assertTrue(row["paid_on"] is SheetValue.Text)
            assertTrue(row["paid"] is SheetValue.Flag)
        }

        @Test
        fun `cents convert to a two-decimal number without floating point`() {
            assertEquals(BigDecimal("250.00"), number(billRow(), "installment_amount"))

            val oddCents = plan.copy(installmentAmountCents = 1)
            assertEquals(
                BigDecimal("0.01"),
                number(billRow(oddCents, ScheduleGenerator.generate(oddCents)), "installment_amount"),
            )
        }

        @Test
        fun `dates are ISO yyyy-MM-dd, which is what the digest compares against`() {
            assertEquals("2026-01-15", text(billRow(), "first_due"))
            val row = SheetMapper.occurrenceRow(schedule[1], "Car loan", 4, updatedAt)
            assertEquals("2026-02-15", text(row, "due_date"))
        }
    }

    @Nested
    inner class BillRowContent {

        @Test
        fun `end date is where the schedule actually lands, not a user-entered value`() {
            // The plan ends by count, so nothing in the Bill itself holds this date.
            assertEquals("2026-04-15", text(billRow(), "end_date"))
        }

        @Test
        fun `progress columns reflect payments made`() {
            val paid = schedule.map {
                if (it.sequence <= 2) it.copy(paidOn = LocalDate.parse("2026-01-15")) else it
            }
            val row = billRow(occurrences = paid, today = "2026-03-01")

            assertEquals(BigDecimal(4), number(row, "installment_count"))
            assertEquals(BigDecimal(2), number(row, "paid_count"))
            assertEquals(BigDecimal("500.00"), number(row, "remaining_amount"))
            assertEquals("2026-03-15", text(row, "next_due"))
        }

        @Test
        fun `a fully paid plan is marked inactive`() {
            val paid = schedule.map { it.copy(paidOn = LocalDate.parse("2026-01-15")) }
            val row = billRow(occurrences = paid, today = "2026-05-01")
            assertEquals(false, flag(row, "active"))
            assertEquals("", text(row, "next_due"))
        }

        @Test
        fun `an archived plan is marked inactive even with payments outstanding`() {
            assertEquals(false, flag(billRow(archived = true), "active"))
            assertEquals(true, flag(billRow(archived = false), "active"))
        }

        @Test
        fun `absent optional fields become empty strings rather than the word null`() {
            val bare = plan.copy(payee = null, notes = null)
            val row = billRow(bare, ScheduleGenerator.generate(bare))
            assertEquals("", text(row, "payee"))
            assertEquals("", text(row, "notes"))
        }
    }

    @Nested
    inner class OccurrenceRowContent {

        @Test
        fun `notified_on is always sent empty because the script owns it`() {
            val row = SheetMapper.occurrenceRow(schedule.first(), "Car loan", 4, updatedAt)
            assertEquals("", text(row, "notified_on"))
        }

        @Test
        fun `paid state round-trips`() {
            val paid = schedule.first().copy(
                paidOn = LocalDate.parse("2026-01-14"),
                paidAmountCents = 24_000,
            )
            val row = SheetMapper.occurrenceRow(paid, "Car loan", 4, updatedAt)
            assertEquals(true, flag(row, "paid"))
            assertEquals("2026-01-14", text(row, "paid_on"))
        }

        @Test
        fun `an unpaid occurrence has an empty paid_on`() {
            val row = SheetMapper.occurrenceRow(schedule.first(), "Car loan", 4, updatedAt)
            assertEquals(false, flag(row, "paid"))
            assertEquals("", text(row, "paid_on"))
        }

        @Test
        fun `sequence and total feed the email's payment n of N line`() {
            val row = SheetMapper.occurrenceRow(schedule[2], "Car loan", 4, updatedAt)
            assertEquals(BigDecimal(3), number(row, "sequence"))
            assertEquals(BigDecimal(4), number(row, "total_count"))
        }
    }

    @Nested
    inner class ScheduleDescription {

        @Test
        fun `weekly intervals read naturally`() {
            assertEquals("Weekly", Recurrence.Weekly(1).describe())
            assertEquals("Every 2 weeks", Recurrence.Weekly(2).describe())
            assertEquals("Every 3 weeks", Recurrence.Weekly(3).describe())
        }

        @Test
        fun `common monthly intervals get their own names`() {
            assertEquals("Monthly", Recurrence.Monthly(1).describe())
            assertEquals("Quarterly", Recurrence.Monthly(3).describe())
            assertEquals("Yearly", Recurrence.Monthly(12).describe())
            assertEquals("Every 5 months", Recurrence.Monthly(5).describe())
        }

        @Test
        fun `semi-monthly lists both days with correct ordinals`() {
            assertEquals("Twice monthly (1st and 15th)", Recurrence.SemiMonthly(1, 15).describe())
            assertEquals("Twice monthly (2nd and 22nd)", Recurrence.SemiMonthly(22, 2).describe())
            assertEquals("Twice monthly (3rd and 13th)", Recurrence.SemiMonthly(3, 13).describe())
            assertEquals("Twice monthly (11th and 21st)", Recurrence.SemiMonthly(11, 21).describe())
        }

        @Test
        fun `yearly intervals read naturally`() {
            assertEquals("Yearly", Recurrence.Yearly(1).describe())
            assertEquals("Every 2 years", Recurrence.Yearly(2).describe())
        }
    }
}
