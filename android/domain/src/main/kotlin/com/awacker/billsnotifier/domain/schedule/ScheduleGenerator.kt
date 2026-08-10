package com.awacker.billsnotifier.domain.schedule

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Occurrence
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import java.time.LocalDate
import java.time.YearMonth

/**
 * Expands a [Bill] into the full list of dated installments it implies.
 *
 * This is the single source of truth for recurrence math in the whole system. The Apps
 * Script side deliberately contains none: it reads the materialized dates out of the
 * sheet and does a string comparison against today. Two implementations of this logic
 * would drift on exactly the edge cases below.
 *
 * Pure and platform-agnostic on purpose — no Android imports, so it is directly testable.
 */
object ScheduleGenerator {

    /**
     * Safety valve. A plan that hits this is almost certainly misconfigured (a weekly plan
     * ending in 2099, say), and generating unbounded rows would bloat the sheet sync.
     */
    const val MAX_OCCURRENCES: Int = 600

    fun generate(bill: Bill): List<Occurrence> {
        validate(bill)

        val dates = dueDates(bill.recurrence, bill.firstDueDate)
        val bounded = when (val end = bill.end) {
            is PlanEnd.AfterPayments -> dates.take(minOf(end.count, MAX_OCCURRENCES))
            // Inclusive: an installment landing exactly on the end date still counts.
            is PlanEnd.OnOrBefore -> dates.takeWhile { !it.isAfter(end.date) }.take(MAX_OCCURRENCES)
        }.toList()

        val lastSequence = bounded.size
        return bounded.mapIndexed { index, dueDate ->
            val sequence = index + 1
            val isFinal = sequence == lastSequence
            Occurrence(
                id = Occurrence.idFor(bill.id, sequence),
                billId = bill.id,
                sequence = sequence,
                dueDate = dueDate,
                amountCents = bill.finalAmountCents
                    ?.takeIf { isFinal }
                    ?: bill.installmentAmountCents,
            )
        }
    }

    /**
     * The infinite sequence of due dates for a recurrence, starting at [first].
     *
     * Every date is computed as an offset from [first] rather than from the previous date.
     * That distinction is the whole ballgame for monthly plans: stepping cumulatively
     * (`previous.plusMonths(1)`) turns Jan 31 into Feb 28 and then *sticks* at the 28th
     * forever, whereas offsetting from the original anchor correctly recovers Mar 31.
     * `java.time` clamps to the shorter month for us and keeps the original day-of-month
     * as the anchor, so it does the right thing as long as we never iterate.
     */
    private fun dueDates(recurrence: Recurrence, first: LocalDate): Sequence<LocalDate> {
        val steps = generateSequence(0L) { it + 1 }
        return when (recurrence) {
            is Recurrence.Weekly ->
                steps.map { first.plusWeeks(it * recurrence.everyNWeeks) }

            is Recurrence.Monthly ->
                steps.map { first.plusMonths(it * recurrence.everyNMonths) }

            // plusYears clamps Feb 29 to Feb 28 in a non-leap year, and because we always
            // offset from `first` a leap-day plan returns to Feb 29 every fourth year.
            is Recurrence.Yearly ->
                steps.map { first.plusYears(it * recurrence.everyNYears) }

            is Recurrence.SemiMonthly -> {
                val earlier = minOf(recurrence.firstDay, recurrence.secondDay)
                val later = maxOf(recurrence.firstDay, recurrence.secondDay)
                val firstMonth = YearMonth.from(first)
                steps.flatMap { monthOffset ->
                    val month = firstMonth.plusMonths(monthOffset)
                    // Both anchors can clamp onto the same day in a short month (e.g. 30 and
                    // 31 both land on Feb 28). Kept as two installments rather than deduped:
                    // the plan really does owe two payments that month.
                    sequenceOf(month.atDayClamped(earlier), month.atDayClamped(later))
                }.dropWhile { it.isBefore(first) }
            }
        }
    }

    /** [YearMonth.atDay], but a day past the end of the month lands on the last day instead. */
    private fun YearMonth.atDayClamped(dayOfMonth: Int): LocalDate =
        atDay(minOf(dayOfMonth, lengthOfMonth()))

    private fun validate(bill: Bill) {
        require(bill.id.isNotBlank()) { "Bill id must not be blank" }
        require(bill.installmentAmountCents > 0) {
            "Installment amount must be positive, was ${bill.installmentAmountCents}"
        }
        bill.finalAmountCents?.let {
            require(it > 0) { "Final installment amount must be positive, was $it" }
        }

        when (val recurrence = bill.recurrence) {
            is Recurrence.Weekly -> require(recurrence.everyNWeeks >= 1) {
                "Weekly interval must be at least 1, was ${recurrence.everyNWeeks}"
            }

            is Recurrence.Monthly -> require(recurrence.everyNMonths >= 1) {
                "Monthly interval must be at least 1, was ${recurrence.everyNMonths}"
            }

            is Recurrence.Yearly -> require(recurrence.everyNYears >= 1) {
                "Yearly interval must be at least 1, was ${recurrence.everyNYears}"
            }

            is Recurrence.SemiMonthly -> {
                require(recurrence.firstDay in 1..31 && recurrence.secondDay in 1..31) {
                    "Semi-monthly days must be within 1..31, were " +
                        "${recurrence.firstDay} and ${recurrence.secondDay}"
                }
                require(recurrence.firstDay != recurrence.secondDay) {
                    "Semi-monthly days must differ, both were ${recurrence.firstDay}"
                }
            }
        }

        when (val end = bill.end) {
            is PlanEnd.AfterPayments -> require(end.count >= 1) {
                "A plan must have at least one payment, was ${end.count}"
            }

            is PlanEnd.OnOrBefore -> require(!end.date.isBefore(bill.firstDueDate)) {
                "End date ${end.date} is before the first due date ${bill.firstDueDate}"
            }
        }
    }
}
