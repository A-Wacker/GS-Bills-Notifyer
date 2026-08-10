package com.awacker.billsnotifier.domain.model

import java.time.LocalDate

/**
 * How often a payment plan's installments fall due.
 *
 * Modelled as a sealed type rather than an enum-plus-interval so that combinations that
 * make no sense — "semi-monthly every 3" — simply cannot be constructed.
 */
sealed interface Recurrence {

    /** Every [everyNWeeks] weeks. Bi-weekly is `Weekly(2)`. */
    data class Weekly(val everyNWeeks: Int = 1) : Recurrence

    /**
     * Every [everyNMonths] months, anchored to the day-of-month of the plan's first due date.
     *
     * The anchor is the *original* day, not the previously clamped one: a plan starting
     * Jan 31 falls due Feb 28, then **Mar 31** — not Mar 28.
     */
    data class Monthly(val everyNMonths: Int = 1) : Recurrence

    /**
     * Twice a month on two fixed days, e.g. `SemiMonthly(1, 15)`.
     *
     * A day past the end of a short month is clamped to that month's last day, so
     * `SemiMonthly(15, 31)` falls due Feb 15 and Feb 28.
     */
    data class SemiMonthly(val firstDay: Int, val secondDay: Int) : Recurrence

    /** Every [everyNYears] years. Feb 29 falls back to Feb 28 in non-leap years. */
    data class Yearly(val everyNYears: Int = 1) : Recurrence
}

/**
 * How a payment plan terminates. Every plan ends — that is what makes it a plan rather
 * than an open-ended recurring bill, and it is what lets the whole schedule be
 * materialized up front.
 */
sealed interface PlanEnd {

    /** A fixed number of installments, e.g. 24 payments. */
    data class AfterPayments(val count: Int) : PlanEnd

    /** Runs until [date]; an installment landing exactly on [date] is included. */
    data class OnOrBefore(val date: LocalDate) : PlanEnd
}

/**
 * A payment plan definition. This is the *rule*; the individual dated installments it
 * expands into are [Occurrence]s.
 *
 * Money is always in whole cents as a [Long]. Never use a floating-point type for money.
 */
data class Bill(
    val id: String,
    val name: String,
    val installmentAmountCents: Long,
    val recurrence: Recurrence,
    val firstDueDate: LocalDate,
    val end: PlanEnd,
    val payee: String? = null,
    /**
     * Amount of the final installment, when it differs from the rest — the remainder
     * left over when a total doesn't divide evenly. Null means the last payment is the
     * same as every other one.
     */
    val finalAmountCents: Long? = null,
    /** Paid automatically. Still notified, but worded as a heads-up rather than an action. */
    val autopay: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notes: String? = null,
)

/**
 * One dated installment of a [Bill], materialized by
 * [com.awacker.billsnotifier.domain.schedule.ScheduleGenerator].
 *
 * [id] is deterministic (`billId#sequence`) so that regenerating a schedule after an edit
 * can carry paid state across, and so that syncing to the sheet is idempotent.
 */
data class Occurrence(
    val id: String,
    val billId: String,
    /** 1-based position within the plan. */
    val sequence: Int,
    val dueDate: LocalDate,
    val amountCents: Long,
    val paidOn: LocalDate? = null,
    val paidAmountCents: Long? = null,
) {
    val isPaid: Boolean get() = paidOn != null

    fun statusOn(today: LocalDate): OccurrenceStatus = when {
        isPaid -> OccurrenceStatus.PAID
        dueDate.isBefore(today) -> OccurrenceStatus.OVERDUE
        dueDate == today -> OccurrenceStatus.DUE_TODAY
        else -> OccurrenceStatus.UPCOMING
    }

    companion object {
        fun idFor(billId: String, sequence: Int): String = "$billId#$sequence"
    }
}

enum class OccurrenceStatus { UPCOMING, DUE_TODAY, OVERDUE, PAID }
