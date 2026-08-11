package com.awacker.billsnotifier.domain.model

/**
 * Works out installment amounts when a plan is entered as a lump sum to pay off rather
 * than as a per-payment figure — "$1,000 over 12 monthly payments".
 *
 * The remainder lands on the final payment, which is what [Bill.finalAmountCents] exists
 * for. That keeps the installments summing to exactly the total: dividing $1,000 by 12 and
 * rounding gives twelve payments of $83.33, which is $999.96, and the missing four cents
 * would quietly never be repaid.
 */
object PlanAmounts {

    data class Split(
        val installmentCents: Long,
        /** Null when the total divides evenly and every payment is the same. */
        val finalCents: Long?,
    )

    /**
     * Splits [totalCents] across [count] payments, or null when that isn't a usable plan.
     *
     * Returns null rather than throwing because this runs on every keystroke while the
     * user is still typing — a half-entered amount is not an error, it just isn't a plan
     * yet. The caller keeps the save button disabled until it returns non-null.
     */
    fun fromTotal(totalCents: Long, count: Int): Split? {
        if (totalCents <= 0 || count < 1) return null

        val split = Money.splitEvenly(totalCents, count)
        // Too small to divide — e.g. 5 cents over 12 payments would make most of them zero,
        // which ScheduleGenerator rejects outright.
        if (split.regularCents <= 0) return null

        return Split(
            installmentCents = split.regularCents,
            finalCents = split.finalCents.takeIf { it != split.regularCents },
        )
    }

    /** Human summary of a split, for the entry screen. */
    fun describe(split: Split, count: Int): String {
        val payments = if (count == 1) "1 payment" else "$count payments"
        val base = "$payments of ${Money.format(split.installmentCents)}"
        return split.finalCents
            ?.let { "$base, last one ${Money.format(it)}" }
            ?: base
    }
}
