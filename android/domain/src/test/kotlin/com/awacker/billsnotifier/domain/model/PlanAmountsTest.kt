package com.awacker.billsnotifier.domain.model

import com.awacker.billsnotifier.domain.schedule.ScheduleGenerator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlanAmountsTest {

    @Nested
    inner class Splitting {

        @Test
        fun `an even total gives identical payments and no remainder`() {
            val split = PlanAmounts.fromTotal(totalCents = 120_000, count = 24)!!
            assertEquals(5_000, split.installmentCents)
            assertNull(split.finalCents, "every payment is the same, so there is no final override")
        }

        @Test
        fun `an uneven total puts the remainder on the last payment`() {
            val split = PlanAmounts.fromTotal(totalCents = 100_000, count = 12)!!
            assertEquals(8_333, split.installmentCents)
            assertEquals(8_337, split.finalCents)
        }

        @Test
        fun `a single payment is the whole total`() {
            val split = PlanAmounts.fromTotal(totalCents = 45_678, count = 1)!!
            assertEquals(45_678, split.installmentCents)
            assertNull(split.finalCents)
        }

        @Test
        fun `rejects amounts too small to divide`() {
            // 5 cents over 12 payments would make most of them zero.
            assertNull(PlanAmounts.fromTotal(totalCents = 5, count = 12))
        }

        @Test
        fun `rejects nonsense input rather than throwing mid-typing`() {
            assertNull(PlanAmounts.fromTotal(totalCents = 0, count = 12))
            assertNull(PlanAmounts.fromTotal(totalCents = -100, count = 12))
            assertNull(PlanAmounts.fromTotal(totalCents = 100_000, count = 0))
        }

        @Test
        fun `the smallest viable plan is one cent per payment`() {
            val split = PlanAmounts.fromTotal(totalCents = 12, count = 12)!!
            assertEquals(1, split.installmentCents)
            assertNull(split.finalCents)
        }
    }

    /**
     * The property that actually matters: whatever total goes in, the generated schedule
     * must add back up to exactly that. Rounding each payment independently would quietly
     * lose or invent cents, and nobody would notice until the final payment was wrong.
     */
    @Nested
    inner class SumsBackToTheTotal {

        private fun scheduleTotal(totalCents: Long, count: Int): Long {
            val split = PlanAmounts.fromTotal(totalCents, count)
            assertNotNull(split, "expected $totalCents over $count payments to be viable")
            val bill = Bill(
                id = "plan",
                name = "Plan",
                installmentAmountCents = split.installmentCents,
                finalAmountCents = split.finalCents,
                recurrence = Recurrence.Monthly(),
                firstDueDate = LocalDate.parse("2026-01-15"),
                end = PlanEnd.AfterPayments(count),
            )
            return ScheduleGenerator.generate(bill).sumOf { it.amountCents }
        }

        @Test
        fun `the classic case adds up`() {
            assertEquals(100_000, scheduleTotal(100_000, 12))
        }

        @Test
        fun `every awkward combination still adds up exactly`() {
            val totals = listOf(100_000L, 99_999L, 12L, 45_678L, 1_000_000L, 7L, 123_457L)
            val counts = listOf(1, 2, 3, 7, 12, 24, 26, 52)
            for (total in totals) {
                for (count in counts) {
                    if (PlanAmounts.fromTotal(total, count) == null) continue
                    assertEquals(
                        total,
                        scheduleTotal(total, count),
                        "$total over $count payments did not sum back to the total",
                    )
                }
            }
        }
    }

    @Nested
    inner class Wording {

        @Test
        fun `an even split reads simply`() {
            val split = PlanAmounts.fromTotal(120_000, 24)!!
            assertEquals("24 payments of \$50.00", PlanAmounts.describe(split, 24))
        }

        @Test
        fun `an uneven split names the final payment`() {
            val split = PlanAmounts.fromTotal(100_000, 12)!!
            assertEquals(
                "12 payments of \$83.33, last one \$83.37",
                PlanAmounts.describe(split, 12),
            )
        }

        @Test
        fun `a single payment is singular`() {
            val split = PlanAmounts.fromTotal(45_678, 1)!!
            assertEquals("1 payment of \$456.78", PlanAmounts.describe(split, 1))
        }
    }
}
