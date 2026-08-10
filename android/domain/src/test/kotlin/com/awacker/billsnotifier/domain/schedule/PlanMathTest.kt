package com.awacker.billsnotifier.domain.schedule

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.OccurrenceStatus
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanMathTest {

    private val plan = Bill(
        id = "car-loan",
        name = "Car loan",
        installmentAmountCents = 25_000,
        recurrence = Recurrence.Monthly(),
        firstDueDate = LocalDate.parse("2026-01-15"),
        end = PlanEnd.AfterPayments(4),
    )

    private val schedule = ScheduleGenerator.generate(plan)

    private fun List<com.awacker.billsnotifier.domain.model.Occurrence>.markPaid(
        vararg sequences: Int,
        on: String = "2026-01-15",
    ) = map {
        if (it.sequence in sequences) it.copy(paidOn = LocalDate.parse(on)) else it
    }

    @Nested
    inner class Progress {

        @Test
        fun `a fresh plan owes everything`() {
            val progress = PlanMath.progress(schedule, LocalDate.parse("2026-01-01"))
            assertEquals(4, progress.totalCount)
            assertEquals(0, progress.paidCount)
            assertEquals(100_000, progress.totalAmountCents)
            assertEquals(0, progress.paidAmountCents)
            assertEquals(100_000, progress.remainingAmountCents)
            assertEquals(LocalDate.parse("2026-01-15"), progress.nextDueDate)
            assertEquals(LocalDate.parse("2026-04-15"), progress.projectedPayoffDate)
            assertTrue(!progress.isPaidOff)
        }

        @Test
        fun `paying installments moves the balance and the next due date`() {
            val progress = PlanMath.progress(
                schedule.markPaid(1, 2),
                LocalDate.parse("2026-03-01"),
            )
            assertEquals(2, progress.paidCount)
            assertEquals(50_000, progress.paidAmountCents)
            assertEquals(50_000, progress.remainingAmountCents)
            assertEquals(LocalDate.parse("2026-03-15"), progress.nextDueDate)
        }

        @Test
        fun `a fully paid plan reports no next due date`() {
            val progress = PlanMath.progress(
                schedule.markPaid(1, 2, 3, 4),
                LocalDate.parse("2026-05-01"),
            )
            assertTrue(progress.isPaidOff)
            assertEquals(0, progress.remainingAmountCents)
            assertNull(progress.nextDueDate)
            assertNull(progress.projectedPayoffDate)
        }

        @Test
        fun `an actual paid amount overrides the scheduled one`() {
            val overpaid = schedule.map {
                if (it.sequence == 1) {
                    it.copy(paidOn = LocalDate.parse("2026-01-15"), paidAmountCents = 30_000)
                } else {
                    it
                }
            }
            val progress = PlanMath.progress(overpaid, LocalDate.parse("2026-02-01"))
            assertEquals(30_000, progress.paidAmountCents)
        }

        @Test
        fun `unpaid installments in the past count as overdue`() {
            val progress = PlanMath.progress(schedule, LocalDate.parse("2026-03-16"))
            assertEquals(3, progress.overdueCount)
        }

        @Test
        fun `an empty schedule does not blow up`() {
            val progress = PlanMath.progress(emptyList(), LocalDate.parse("2026-01-01"))
            assertEquals(0, progress.totalCount)
            assertNull(progress.nextDueDate)
            assertTrue(!progress.isPaidOff)
        }
    }

    @Nested
    inner class DueToday {

        @Test
        fun `finds only what is unpaid and due on the day`() {
            val due = PlanMath.dueOn(schedule, LocalDate.parse("2026-02-15"))
            assertEquals(listOf(2), due.map { it.sequence })
        }

        @Test
        fun `skips an installment already marked paid`() {
            val due = PlanMath.dueOn(schedule.markPaid(2), LocalDate.parse("2026-02-15"))
            assertTrue(due.isEmpty())
        }

        @Test
        fun `a day with nothing due returns empty so the digest stays silent`() {
            assertTrue(PlanMath.dueOn(schedule, LocalDate.parse("2026-02-16")).isEmpty())
        }

        @Test
        fun `overdue lists past unpaid installments oldest first`() {
            val overdue = PlanMath.overdueAsOf(
                schedule.markPaid(1),
                LocalDate.parse("2026-03-16"),
            )
            assertEquals(listOf(2, 3), overdue.map { it.sequence })
        }
    }

    @Nested
    inner class Status {

        @Test
        fun `status reflects paid, overdue, due today and upcoming`() {
            val today = LocalDate.parse("2026-02-15")
            val marked = schedule.markPaid(1)
            assertEquals(OccurrenceStatus.PAID, marked[0].statusOn(today))
            assertEquals(OccurrenceStatus.DUE_TODAY, marked[1].statusOn(today))
            assertEquals(OccurrenceStatus.UPCOMING, marked[2].statusOn(today))
            assertEquals(
                OccurrenceStatus.OVERDUE,
                marked[1].statusOn(LocalDate.parse("2026-02-16")),
            )
        }

        @Test
        fun `a paid installment stays paid even when its due date has passed`() {
            val paid = schedule.markPaid(1).first()
            assertEquals(OccurrenceStatus.PAID, paid.statusOn(LocalDate.parse("2030-01-01")))
        }
    }

    @Nested
    inner class PreservePaidState {

        @Test
        fun `paid marks survive a schedule regenerated after an amount change`() {
            val paid = schedule.markPaid(1, 2)
            val regenerated = ScheduleGenerator.generate(plan.copy(installmentAmountCents = 30_000))

            val merged = PlanMath.preservePaidState(paid, regenerated)

            assertEquals(listOf(1, 2), merged.filter { it.isPaid }.map { it.sequence })
            assertTrue(merged.all { it.amountCents == 30_000L })
        }

        @Test
        fun `paid marks survive a shifted start date, matching on sequence not date`() {
            val paid = schedule.markPaid(1, 2)
            val regenerated = ScheduleGenerator.generate(
                plan.copy(firstDueDate = LocalDate.parse("2026-01-22")),
            )

            val merged = PlanMath.preservePaidState(paid, regenerated)

            // Three payments were made; shifting the schedule does not un-make them.
            assertEquals(listOf(1, 2), merged.filter { it.isPaid }.map { it.sequence })
            assertEquals(LocalDate.parse("2026-01-22"), merged.first().dueDate)
        }

        @Test
        fun `shortening a plan drops installments past the new end`() {
            val paid = schedule.markPaid(1, 2, 3, 4)
            val regenerated = ScheduleGenerator.generate(plan.copy(end = PlanEnd.AfterPayments(2)))

            val merged = PlanMath.preservePaidState(paid, regenerated)

            assertEquals(2, merged.size)
            assertTrue(merged.all { it.isPaid })
        }

        @Test
        fun `lengthening a plan leaves the new installments unpaid`() {
            val paid = schedule.markPaid(1, 2)
            val regenerated = ScheduleGenerator.generate(plan.copy(end = PlanEnd.AfterPayments(6)))

            val merged = PlanMath.preservePaidState(paid, regenerated)

            assertEquals(6, merged.size)
            assertEquals(listOf(1, 2), merged.filter { it.isPaid }.map { it.sequence })
        }

        @Test
        fun `the recorded paid date and amount carry across`() {
            val paid = schedule.map {
                if (it.sequence == 1) {
                    it.copy(paidOn = LocalDate.parse("2026-01-14"), paidAmountCents = 24_000)
                } else {
                    it
                }
            }
            val merged = PlanMath.preservePaidState(paid, ScheduleGenerator.generate(plan))

            assertEquals(LocalDate.parse("2026-01-14"), merged.first().paidOn)
            assertEquals(24_000, merged.first().paidAmountCents)
        }

        @Test
        fun `merging against an empty history is a no-op`() {
            assertEquals(schedule, PlanMath.preservePaidState(emptyList(), schedule))
        }
    }
}
