package com.awacker.billsnotifier.domain.schedule

import com.awacker.billsnotifier.domain.model.Occurrence
import java.time.LocalDate

/**
 * Payoff state for a single plan, derived from its occurrences rather than stored.
 * Keeping it derived means paid marks are the only thing that can be out of date.
 */
data class PlanProgress(
    val totalCount: Int,
    val paidCount: Int,
    val overdueCount: Int,
    val totalAmountCents: Long,
    val paidAmountCents: Long,
    val remainingAmountCents: Long,
    /** Earliest unpaid installment, or null once the plan is fully paid. */
    val nextDueDate: LocalDate?,
    /** Due date of the last unpaid installment — when this plan is projected to be done. */
    val projectedPayoffDate: LocalDate?,
) {
    val isPaidOff: Boolean get() = totalCount > 0 && paidCount == totalCount
}

/**
 * Occurrence-level queries shared by the UI, the notification worker, and the sheet sync,
 * so "what is due today" is defined in exactly one place.
 */
object PlanMath {

    fun progress(occurrences: List<Occurrence>, today: LocalDate): PlanProgress {
        val (paid, unpaid) = occurrences.partition { it.isPaid }
        return PlanProgress(
            totalCount = occurrences.size,
            paidCount = paid.size,
            overdueCount = unpaid.count { it.dueDate.isBefore(today) },
            totalAmountCents = occurrences.sumOf { it.amountCents },
            // Fall back to the scheduled amount when an actual paid amount wasn't recorded.
            paidAmountCents = paid.sumOf { it.paidAmountCents ?: it.amountCents },
            remainingAmountCents = unpaid.sumOf { it.amountCents },
            nextDueDate = unpaid.minOfOrNull { it.dueDate },
            projectedPayoffDate = unpaid.maxOfOrNull { it.dueDate },
        )
    }

    /** Unpaid installments falling due exactly on [date] — the morning digest's contents. */
    fun dueOn(occurrences: List<Occurrence>, date: LocalDate): List<Occurrence> =
        occurrences.filter { !it.isPaid && it.dueDate == date }.sortedBy { it.billId }

    /** Unpaid installments whose due date has already passed. */
    fun overdueAsOf(occurrences: List<Occurrence>, today: LocalDate): List<Occurrence> =
        occurrences.filter { !it.isPaid && it.dueDate.isBefore(today) }.sortedBy { it.dueDate }

    /**
     * Carries paid state from an existing schedule onto a freshly generated one, matching
     * on [Occurrence.sequence].
     *
     * Sequence rather than date is deliberate: if the plan's dates shift because the user
     * corrected the start date or frequency, "I have made three payments" is still true and
     * should survive. Matching on date would silently drop every paid mark instead.
     *
     * Installments beyond the end of the new schedule are dropped along with any paid state
     * they carried — that is inherent in shortening a plan.
     */
    fun preservePaidState(
        previous: List<Occurrence>,
        regenerated: List<Occurrence>,
    ): List<Occurrence> {
        if (previous.isEmpty()) return regenerated
        val paidBySequence = previous.filter { it.isPaid }.associateBy { it.sequence }
        if (paidBySequence.isEmpty()) return regenerated

        return regenerated.map { occurrence ->
            val previouslyPaid = paidBySequence[occurrence.sequence] ?: return@map occurrence
            occurrence.copy(
                paidOn = previouslyPaid.paidOn,
                paidAmountCents = previouslyPaid.paidAmountCents,
            )
        }
    }
}
