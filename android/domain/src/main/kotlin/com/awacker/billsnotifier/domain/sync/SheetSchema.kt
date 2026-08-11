package com.awacker.billsnotifier.domain.sync

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Occurrence
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import com.awacker.billsnotifier.domain.schedule.PlanMath
import java.math.BigDecimal

/**
 * The wire contract between the Android app and the Apps Script.
 *
 * These column lists must stay identical to `BILL_COLUMNS` and `OCCURRENCE_COLUMNS` in
 * `appsscript/Common.gs`. A drift test in SheetSchemaContractTest parses the .gs file and
 * fails if they diverge, because a silently renamed column would produce a sheet that
 * looks fine and a digest email that never finds anything due.
 *
 * The layout serves two audiences at once. The leading columns are for a human reading the
 * sheet — a plain-English schedule, progress, a payoff date. The trailing `machine_*`-ish
 * columns carry the fields needed to rebuild a plan exactly: without them the sheet cannot
 * reconstruct a [Recurrence], which is what a read-only mirror device and any restore-from-
 * backup both depend on.
 */
object SheetSchema {

    val BILL_COLUMNS: List<String> = listOf(
        "bill_id",
        "name",
        "payee",
        "schedule",
        "installment_amount",
        "first_due",
        "end_date",
        "installment_count",
        "paid_count",
        "remaining_amount",
        "next_due",
        "autopay",
        "notes",
        "active",
        "updated_at",
        // Machine-readable plan definition. Everything above is derived or decorative;
        // these are what let a plan be rebuilt rather than merely displayed.
        "recurrence_type",
        "recurrence_interval",
        "semi_monthly_days",
        "end_mode",
        "end_on",
        "final_amount",
        "notifications_enabled",
    )

    val OCCURRENCE_COLUMNS: List<String> = listOf(
        "occurrence_id",
        "bill_id",
        "bill_name",
        "sequence",
        "total_count",
        "due_date",
        "amount",
        "paid",
        "paid_on",
        "notified_on",
        "updated_at",
    )

    /**
     * Columns the Apps Script owns. The app sends them empty and the script restores its
     * own values on top of the snapshot, so the phone can never clobber them.
     */
    val SCRIPT_OWNED_COLUMNS: List<String> = listOf("notified_on")

    const val RECURRENCE_WEEKLY = "WEEKLY"
    const val RECURRENCE_MONTHLY = "MONTHLY"
    const val RECURRENCE_SEMI_MONTHLY = "SEMI_MONTHLY"
    const val RECURRENCE_YEARLY = "YEARLY"
    const val END_BY_COUNT = "BY_COUNT"
    const val END_BY_DATE = "BY_DATE"
}

/**
 * A single cell's value, kept independent of any JSON library so the domain module stays
 * dependency-free. The data layer turns these into JSON primitives.
 *
 * The distinction matters: amounts must reach the sheet as real numbers so it can sum them
 * and apply a currency format, while dates must reach it as text so Sheets doesn't coerce
 * them into timezone-shifted Date objects.
 */
sealed interface SheetValue {
    data class Text(val value: String) : SheetValue
    data class Number(val value: BigDecimal) : SheetValue
    data class Flag(val value: Boolean) : SheetValue
}

/** Renders a recurrence for the `schedule` column, which exists purely for humans reading the sheet. */
fun Recurrence.describe(): String = when (this) {
    is Recurrence.Weekly -> when (everyNWeeks) {
        1 -> "Weekly"
        2 -> "Every 2 weeks"
        else -> "Every $everyNWeeks weeks"
    }

    is Recurrence.Monthly -> when (everyNMonths) {
        1 -> "Monthly"
        3 -> "Quarterly"
        12 -> "Yearly"
        else -> "Every $everyNMonths months"
    }

    is Recurrence.SemiMonthly -> {
        val earlier = minOf(firstDay, secondDay)
        val later = maxOf(firstDay, secondDay)
        "Twice monthly (${ordinal(earlier)} and ${ordinal(later)})"
    }

    is Recurrence.Yearly -> if (everyNYears == 1) "Yearly" else "Every $everyNYears years"
}

private fun ordinal(day: Int): String {
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}

/**
 * Projects domain objects onto sheet rows.
 *
 * [updatedAt] is passed in rather than read from a clock so this stays pure and testable.
 */
object SheetMapper {

    fun billRow(
        bill: Bill,
        occurrences: List<Occurrence>,
        today: java.time.LocalDate,
        updatedAt: String,
        isArchived: Boolean = false,
    ): Map<String, SheetValue> {
        val progress = PlanMath.progress(occurrences, today)
        return mapOf(
            "bill_id" to SheetValue.Text(bill.id),
            "name" to SheetValue.Text(bill.name),
            "payee" to SheetValue.Text(bill.payee.orEmpty()),
            "schedule" to SheetValue.Text(bill.recurrence.describe()),
            "installment_amount" to money(bill.installmentAmountCents),
            "first_due" to SheetValue.Text(bill.firstDueDate.toString()),
            // The real end of the plan is where the generated schedule actually lands,
            // which is not the same as a user-entered end date when the plan ends by count.
            "end_date" to SheetValue.Text(occurrences.lastOrNull()?.dueDate?.toString().orEmpty()),
            "installment_count" to SheetValue.Number(BigDecimal(progress.totalCount)),
            "paid_count" to SheetValue.Number(BigDecimal(progress.paidCount)),
            "remaining_amount" to money(progress.remainingAmountCents),
            "next_due" to SheetValue.Text(progress.nextDueDate?.toString().orEmpty()),
            "autopay" to SheetValue.Flag(bill.autopay),
            "notes" to SheetValue.Text(bill.notes.orEmpty()),
            "active" to SheetValue.Flag(!isArchived && !progress.isPaidOff),
            "updated_at" to SheetValue.Text(updatedAt),

            "recurrence_type" to SheetValue.Text(bill.recurrence.typeName()),
            "recurrence_interval" to SheetValue.Number(BigDecimal(bill.recurrence.interval())),
            "semi_monthly_days" to SheetValue.Text(bill.recurrence.semiMonthlyDays()),
            "end_mode" to SheetValue.Text(
                when (bill.end) {
                    is PlanEnd.AfterPayments -> SheetSchema.END_BY_COUNT
                    is PlanEnd.OnOrBefore -> SheetSchema.END_BY_DATE
                },
            ),
            // The user-chosen end date, distinct from end_date above, which is where the
            // generated schedule happened to land.
            "end_on" to SheetValue.Text(
                (bill.end as? PlanEnd.OnOrBefore)?.date?.toString().orEmpty(),
            ),
            "final_amount" to (bill.finalAmountCents?.let(::money) ?: SheetValue.Text("")),
            "notifications_enabled" to SheetValue.Flag(bill.notificationsEnabled),
        )
    }

    fun occurrenceRow(
        occurrence: Occurrence,
        billName: String,
        totalCount: Int,
        updatedAt: String,
    ): Map<String, SheetValue> = mapOf(
        "occurrence_id" to SheetValue.Text(occurrence.id),
        "bill_id" to SheetValue.Text(occurrence.billId),
        "bill_name" to SheetValue.Text(billName),
        "sequence" to SheetValue.Number(BigDecimal(occurrence.sequence)),
        "total_count" to SheetValue.Number(BigDecimal(totalCount)),
        "due_date" to SheetValue.Text(occurrence.dueDate.toString()),
        "amount" to money(occurrence.amountCents),
        "paid" to SheetValue.Flag(occurrence.isPaid),
        "paid_on" to SheetValue.Text(occurrence.paidOn?.toString().orEmpty()),
        // Owned by the Apps Script. Always sent empty; the script restores the real value.
        "notified_on" to SheetValue.Text(""),
        "updated_at" to SheetValue.Text(updatedAt),
    )

    /** Cents to a two-decimal number, never via Double. */
    private fun money(cents: Long): SheetValue.Number =
        SheetValue.Number(BigDecimal.valueOf(cents, 2))
}

internal fun Recurrence.typeName(): String = when (this) {
    is Recurrence.Weekly -> SheetSchema.RECURRENCE_WEEKLY
    is Recurrence.Monthly -> SheetSchema.RECURRENCE_MONTHLY
    is Recurrence.SemiMonthly -> SheetSchema.RECURRENCE_SEMI_MONTHLY
    is Recurrence.Yearly -> SheetSchema.RECURRENCE_YEARLY
}

internal fun Recurrence.interval(): Int = when (this) {
    is Recurrence.Weekly -> everyNWeeks
    is Recurrence.Monthly -> everyNMonths
    is Recurrence.Yearly -> everyNYears
    is Recurrence.SemiMonthly -> 1
}

internal fun Recurrence.semiMonthlyDays(): String =
    (this as? Recurrence.SemiMonthly)?.let { "${it.firstDay},${it.secondDay}" }.orEmpty()
