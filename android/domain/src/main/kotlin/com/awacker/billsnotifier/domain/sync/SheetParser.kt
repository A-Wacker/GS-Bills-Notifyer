package com.awacker.billsnotifier.domain.sync

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Money
import com.awacker.billsnotifier.domain.model.Occurrence
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import java.time.LocalDate

/**
 * Rebuilds domain objects from sheet rows — the reverse of [SheetMapper].
 *
 * Used by a read-only mirror device, which downloads the sheet instead of uploading to it,
 * and by anything restoring a plan from the sheet as a backup.
 *
 * Every function returns null rather than throwing on a row it cannot understand. A sheet
 * is a document a human can edit, so a malformed row is an expected condition; losing one
 * plan is bad, but refusing to load any of them because of one stray cell is worse.
 */
object SheetParser {

    /** A downloaded plan and its schedule. */
    data class MirroredPlan(
        val bill: Bill,
        val occurrences: List<Occurrence>,
        val isArchived: Boolean,
    )

    fun billFrom(row: Map<String, String>): Bill? {
        val id = row.text("bill_id").ifBlank { return null }
        val name = row.text("name").ifBlank { return null }
        val installment = Money.parseToCents(row.text("installment_amount"))
            ?.takeIf { it > 0 }
            ?: return null
        val firstDue = row.date("first_due") ?: return null
        val recurrence = recurrenceFrom(row) ?: return null
        val end = endFrom(row) ?: return null

        return Bill(
            id = id,
            name = name,
            payee = row.text("payee").ifBlank { null },
            installmentAmountCents = installment,
            finalAmountCents = Money.parseToCents(row.text("final_amount"))?.takeIf { it > 0 },
            recurrence = recurrence,
            firstDueDate = firstDue,
            end = end,
            autopay = row.flag("autopay"),
            // Absent on an older sheet written before this column existed; default to on so
            // a mirror that predates it still notifies rather than silently going quiet.
            notificationsEnabled = row.flagOrDefault("notifications_enabled", default = true),
            notes = row.text("notes").ifBlank { null },
        )
    }

    fun occurrenceFrom(row: Map<String, String>): Occurrence? {
        val id = row.text("occurrence_id").ifBlank { return null }
        val billId = row.text("bill_id").ifBlank { return null }
        val sequence = row.text("sequence").toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val dueDate = row.date("due_date") ?: return null
        val amount = Money.parseToCents(row.text("amount")) ?: return null

        val paidOn = row.date("paid_on")
        return Occurrence(
            id = id,
            billId = billId,
            sequence = sequence,
            dueDate = dueDate,
            amountCents = amount,
            // A row flagged paid but missing its date still counts as paid — fall back to
            // the due date so it cannot reappear in tomorrow's notification.
            paidOn = paidOn ?: dueDate.takeIf { row.flag("paid") },
            paidAmountCents = null,
        )
    }

    /** Assembles whole plans, dropping any row that cannot be understood. */
    fun plansFrom(
        billRows: List<Map<String, String>>,
        occurrenceRows: List<Map<String, String>>,
    ): List<MirroredPlan> {
        val occurrencesByBill = occurrenceRows
            .mapNotNull(::occurrenceFrom)
            .groupBy { it.billId }

        return billRows.mapNotNull { row ->
            val bill = billFrom(row) ?: return@mapNotNull null
            MirroredPlan(
                bill = bill,
                occurrences = occurrencesByBill[bill.id].orEmpty().sortedBy { it.sequence },
                // "active" also goes false once a plan is fully paid, so it cannot stand in
                // for archived on its own; a paid-off plan should still be visible.
                isArchived = !row.flagOrDefault("active", default = true) &&
                    occurrencesByBill[bill.id].orEmpty().any { !it.isPaid },
            )
        }
    }

    private fun recurrenceFrom(row: Map<String, String>): Recurrence? {
        val interval = row.text("recurrence_interval").toIntOrNull()?.takeIf { it >= 1 } ?: 1
        return when (row.text("recurrence_type").uppercase()) {
            SheetSchema.RECURRENCE_WEEKLY -> Recurrence.Weekly(interval)
            SheetSchema.RECURRENCE_MONTHLY -> Recurrence.Monthly(interval)
            SheetSchema.RECURRENCE_YEARLY -> Recurrence.Yearly(interval)
            SheetSchema.RECURRENCE_SEMI_MONTHLY -> {
                val days = row.text("semi_monthly_days")
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 1..31 }
                    .distinct()
                if (days.size == 2) Recurrence.SemiMonthly(days[0], days[1]) else null
            }

            else -> null
        }
    }

    private fun endFrom(row: Map<String, String>): PlanEnd? =
        when (row.text("end_mode").uppercase()) {
            SheetSchema.END_BY_COUNT ->
                row.text("installment_count").toIntOrNull()
                    ?.takeIf { it >= 1 }
                    ?.let(PlanEnd::AfterPayments)

            SheetSchema.END_BY_DATE ->
                row.date("end_on")?.let(PlanEnd::OnOrBefore)

            else -> null
        }

    private fun Map<String, String>.text(column: String): String = this[column]?.trim().orEmpty()

    private fun Map<String, String>.date(column: String): LocalDate? =
        text(column).takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun Map<String, String>.flag(column: String): Boolean =
        text(column).lowercase() in setOf("true", "yes", "y", "1")

    private fun Map<String, String>.flagOrDefault(column: String, default: Boolean): Boolean =
        if (text(column).isBlank()) default else flag(column)
}
