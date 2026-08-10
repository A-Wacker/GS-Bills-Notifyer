package com.awacker.billsnotifier.domain.schedule

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleGeneratorTest {

    private fun bill(
        recurrence: Recurrence,
        firstDueDate: String,
        end: PlanEnd,
        installmentAmountCents: Long = 10_000,
        finalAmountCents: Long? = null,
    ) = Bill(
        id = "plan-1",
        name = "Test plan",
        installmentAmountCents = installmentAmountCents,
        finalAmountCents = finalAmountCents,
        recurrence = recurrence,
        firstDueDate = LocalDate.parse(firstDueDate),
        end = end,
    )

    private fun datesOf(bill: Bill): List<String> =
        ScheduleGenerator.generate(bill).map { it.dueDate.toString() }

    @Nested
    inner class Weekly {

        @Test
        fun `bi-weekly generates every fourteen days`() {
            val dates = datesOf(
                bill(Recurrence.Weekly(2), "2026-01-02", PlanEnd.AfterPayments(5)),
            )
            assertEquals(
                listOf("2026-01-02", "2026-01-16", "2026-01-30", "2026-02-13", "2026-02-27"),
                dates,
            )
        }

        @Test
        fun `bi-weekly over a leap day stays on cadence`() {
            val dates = datesOf(
                bill(Recurrence.Weekly(2), "2028-02-18", PlanEnd.AfterPayments(3)),
            )
            assertEquals(listOf("2028-02-18", "2028-03-03", "2028-03-17"), dates)
        }

        @Test
        fun `weekly produces the requested number of payments`() {
            val occurrences = ScheduleGenerator.generate(
                bill(Recurrence.Weekly(), "2026-03-01", PlanEnd.AfterPayments(26)),
            )
            assertEquals(26, occurrences.size)
            assertEquals("2026-08-23", occurrences.last().dueDate.toString())
        }
    }

    @Nested
    inner class Monthly {

        /**
         * The regression this whole design exists to prevent. Stepping month-by-month from
         * the previous date would clamp to Feb 28 and then stay stuck on the 28th forever.
         */
        @Test
        fun `starting on the 31st recovers the anchor day after a short month`() {
            val dates = datesOf(
                bill(Recurrence.Monthly(), "2026-01-31", PlanEnd.AfterPayments(6)),
            )
            assertEquals(
                listOf(
                    "2026-01-31",
                    "2026-02-28", // clamped
                    "2026-03-31", // must recover to the 31st, not stay on the 28th
                    "2026-04-30", // clamped
                    "2026-05-31", // recovered again
                    "2026-06-30",
                ),
                dates,
            )
        }

        @Test
        fun `starting on the 31st clamps to 29 in a leap February`() {
            val dates = datesOf(
                bill(Recurrence.Monthly(), "2028-01-31", PlanEnd.AfterPayments(3)),
            )
            assertEquals(listOf("2028-01-31", "2028-02-29", "2028-03-31"), dates)
        }

        @Test
        fun `mid-month start is unaffected by month lengths`() {
            val dates = datesOf(
                bill(Recurrence.Monthly(), "2026-01-15", PlanEnd.AfterPayments(4)),
            )
            assertEquals(
                listOf("2026-01-15", "2026-02-15", "2026-03-15", "2026-04-15"),
                dates,
            )
        }

        @Test
        fun `quarterly steps three months at a time`() {
            val dates = datesOf(
                bill(Recurrence.Monthly(3), "2026-01-31", PlanEnd.AfterPayments(4)),
            )
            assertEquals(
                listOf("2026-01-31", "2026-04-30", "2026-07-31", "2026-10-31"),
                dates,
            )
        }
    }

    @Nested
    inner class SemiMonthly {

        @Test
        fun `first and fifteenth alternate through the months`() {
            val dates = datesOf(
                bill(Recurrence.SemiMonthly(1, 15), "2026-01-01", PlanEnd.AfterPayments(6)),
            )
            assertEquals(
                listOf(
                    "2026-01-01", "2026-01-15",
                    "2026-02-01", "2026-02-15",
                    "2026-03-01", "2026-03-15",
                ),
                dates,
            )
        }

        @Test
        fun `starts at the first anchor on or after the first due date`() {
            val dates = datesOf(
                bill(Recurrence.SemiMonthly(1, 15), "2026-01-10", PlanEnd.AfterPayments(3)),
            )
            assertEquals(listOf("2026-01-15", "2026-02-01", "2026-02-15"), dates)
        }

        @Test
        fun `day order is normalised when given out of order`() {
            val dates = datesOf(
                bill(Recurrence.SemiMonthly(20, 5), "2026-01-01", PlanEnd.AfterPayments(4)),
            )
            assertEquals(listOf("2026-01-05", "2026-01-20", "2026-02-05", "2026-02-20"), dates)
        }

        @Test
        fun `both anchors clamp into a short month and stay two separate payments`() {
            val dates = datesOf(
                bill(Recurrence.SemiMonthly(30, 31), "2026-01-30", PlanEnd.AfterPayments(4)),
            )
            // February has neither a 30th nor a 31st: both land on the 28th, and the plan
            // genuinely owes two payments that month, so neither is dropped.
            assertEquals(listOf("2026-01-30", "2026-01-31", "2026-02-28", "2026-02-28"), dates)
        }
    }

    @Nested
    inner class Yearly {

        @Test
        fun `leap day plan falls back to the 28th and returns on the next leap year`() {
            val dates = datesOf(
                bill(Recurrence.Yearly(), "2028-02-29", PlanEnd.AfterPayments(5)),
            )
            assertEquals(
                listOf(
                    "2028-02-29",
                    "2029-02-28", // not a leap year
                    "2030-02-28",
                    "2031-02-28",
                    "2032-02-29", // anchor recovered
                ),
                dates,
            )
        }
    }

    @Nested
    inner class Termination {

        @Test
        fun `end date is inclusive when an installment lands exactly on it`() {
            val dates = datesOf(
                bill(
                    Recurrence.Monthly(),
                    "2026-01-15",
                    PlanEnd.OnOrBefore(LocalDate.parse("2026-04-15")),
                ),
            )
            assertEquals(
                listOf("2026-01-15", "2026-02-15", "2026-03-15", "2026-04-15"),
                dates,
            )
        }

        @Test
        fun `installments after the end date are excluded`() {
            val dates = datesOf(
                bill(
                    Recurrence.Monthly(),
                    "2026-01-15",
                    PlanEnd.OnOrBefore(LocalDate.parse("2026-04-14")),
                ),
            )
            assertEquals(listOf("2026-01-15", "2026-02-15", "2026-03-15"), dates)
        }

        @Test
        fun `an end date equal to the first due date yields a single payment`() {
            val dates = datesOf(
                bill(
                    Recurrence.Weekly(),
                    "2026-01-15",
                    PlanEnd.OnOrBefore(LocalDate.parse("2026-01-15")),
                ),
            )
            assertEquals(listOf("2026-01-15"), dates)
        }

        @Test
        fun `a far-future end date is capped by the safety valve`() {
            val occurrences = ScheduleGenerator.generate(
                bill(
                    Recurrence.Weekly(),
                    "2026-01-01",
                    PlanEnd.OnOrBefore(LocalDate.parse("2099-01-01")),
                ),
            )
            assertEquals(ScheduleGenerator.MAX_OCCURRENCES, occurrences.size)
        }

        @Test
        fun `a payment count beyond the safety valve is capped`() {
            val occurrences = ScheduleGenerator.generate(
                bill(Recurrence.Weekly(), "2026-01-01", PlanEnd.AfterPayments(5_000)),
            )
            assertEquals(ScheduleGenerator.MAX_OCCURRENCES, occurrences.size)
        }
    }

    @Nested
    inner class AmountsAndIdentity {

        @Test
        fun `final installment carries the remainder when one is set`() {
            val occurrences = ScheduleGenerator.generate(
                bill(
                    Recurrence.Monthly(),
                    "2026-01-01",
                    PlanEnd.AfterPayments(3),
                    installmentAmountCents = 33_333,
                    finalAmountCents = 33_334,
                ),
            )
            assertEquals(listOf(33_333L, 33_333L, 33_334L), occurrences.map { it.amountCents })
        }

        @Test
        fun `every installment matches when no remainder is set`() {
            val occurrences = ScheduleGenerator.generate(
                bill(Recurrence.Monthly(), "2026-01-01", PlanEnd.AfterPayments(3)),
            )
            assertTrue(occurrences.all { it.amountCents == 10_000L })
        }

        @Test
        fun `a single-payment plan gets the final amount`() {
            val occurrences = ScheduleGenerator.generate(
                bill(
                    Recurrence.Monthly(),
                    "2026-01-01",
                    PlanEnd.AfterPayments(1),
                    installmentAmountCents = 5_000,
                    finalAmountCents = 7_500,
                ),
            )
            assertEquals(listOf(7_500L), occurrences.map { it.amountCents })
        }

        @Test
        fun `ids are deterministic and sequences are one-based and contiguous`() {
            val occurrences = ScheduleGenerator.generate(
                bill(Recurrence.Monthly(), "2026-01-01", PlanEnd.AfterPayments(4)),
            )
            assertEquals(listOf(1, 2, 3, 4), occurrences.map { it.sequence })
            assertEquals(
                listOf("plan-1#1", "plan-1#2", "plan-1#3", "plan-1#4"),
                occurrences.map { it.id },
            )
            assertTrue(occurrences.all { it.billId == "plan-1" })
        }

        @Test
        fun `generation is deterministic across runs`() {
            val subject = bill(Recurrence.Monthly(), "2026-01-31", PlanEnd.AfterPayments(12))
            assertEquals(ScheduleGenerator.generate(subject), ScheduleGenerator.generate(subject))
        }

        @Test
        fun `nothing is paid on a freshly generated schedule`() {
            val occurrences = ScheduleGenerator.generate(
                bill(Recurrence.Monthly(), "2026-01-01", PlanEnd.AfterPayments(3)),
            )
            assertTrue(occurrences.none { it.isPaid })
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `rejects a non-positive installment amount`() {
            assertThrows<IllegalArgumentException> {
                ScheduleGenerator.generate(
                    bill(
                        Recurrence.Monthly(),
                        "2026-01-01",
                        PlanEnd.AfterPayments(3),
                        installmentAmountCents = 0,
                    ),
                )
            }
        }

        @Test
        fun `rejects an interval below one`() {
            assertThrows<IllegalArgumentException> {
                ScheduleGenerator.generate(
                    bill(Recurrence.Weekly(0), "2026-01-01", PlanEnd.AfterPayments(3)),
                )
            }
        }

        @Test
        fun `rejects a payment count below one`() {
            assertThrows<IllegalArgumentException> {
                ScheduleGenerator.generate(
                    bill(Recurrence.Monthly(), "2026-01-01", PlanEnd.AfterPayments(0)),
                )
            }
        }

        @Test
        fun `rejects an end date before the first due date`() {
            assertThrows<IllegalArgumentException> {
                ScheduleGenerator.generate(
                    bill(
                        Recurrence.Monthly(),
                        "2026-06-01",
                        PlanEnd.OnOrBefore(LocalDate.parse("2026-05-31")),
                    ),
                )
            }
        }

        @Test
        fun `rejects identical semi-monthly days`() {
            assertThrows<IllegalArgumentException> {
                ScheduleGenerator.generate(
                    bill(Recurrence.SemiMonthly(15, 15), "2026-01-01", PlanEnd.AfterPayments(3)),
                )
            }
        }

        @Test
        fun `rejects an out-of-range semi-monthly day`() {
            assertThrows<IllegalArgumentException> {
                ScheduleGenerator.generate(
                    bill(Recurrence.SemiMonthly(0, 15), "2026-01-01", PlanEnd.AfterPayments(3)),
                )
            }
        }
    }
}
