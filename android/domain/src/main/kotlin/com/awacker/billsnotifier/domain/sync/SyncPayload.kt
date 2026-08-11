package com.awacker.billsnotifier.domain.sync

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Occurrence
import java.time.LocalDate

/** A plan together with the schedule it expanded into. */
data class PlanSnapshot(
    val bill: Bill,
    val occurrences: List<Occurrence>,
    val isArchived: Boolean = false,
)

/**
 * Builds the request bodies the Apps Script web app expects.
 *
 * The app posts a complete snapshot rather than a diff. The dataset is a few hundred rows
 * and the phone is the only writer, so wholesale replacement is both cheap and impossible
 * to get subtly wrong — the only thing that needs merging is the handful of columns the
 * script owns, and the script does that itself.
 */
object SyncPayload {

    /** Body for the app's "Test connection" button. */
    fun ping(secret: String): String =
        Json.obj(
            "secret" to Json.Str(secret),
            "action" to Json.Str("ping"),
        ).encode()

    /** Body for "Send test email" — runs the real digest server-side and reports back. */
    fun digest(secret: String): String =
        Json.obj(
            "secret" to Json.Str(secret),
            "action" to Json.Str("digest"),
        ).encode()

    fun sync(
        secret: String,
        plans: List<PlanSnapshot>,
        today: LocalDate,
        updatedAt: String,
        settings: Map<String, String> = emptyMap(),
    ): String {
        val billRows = plans.map { plan ->
            Json.row(
                SheetSchema.BILL_COLUMNS,
                SheetMapper.billRow(
                    bill = plan.bill,
                    occurrences = plan.occurrences,
                    today = today,
                    updatedAt = updatedAt,
                    isArchived = plan.isArchived,
                ),
            )
        }

        val occurrenceRows = plans.flatMap { plan ->
            plan.occurrences.map { occurrence ->
                Json.row(
                    SheetSchema.OCCURRENCE_COLUMNS,
                    SheetMapper.occurrenceRow(
                        occurrence = occurrence,
                        billName = plan.bill.name,
                        totalCount = plan.occurrences.size,
                        updatedAt = updatedAt,
                    ),
                )
            }
        }

        return Json.obj(
            "secret" to Json.Str(secret),
            "action" to Json.Str("sync"),
            "payload" to Json.obj(
                "bills" to Json.Arr(billRows),
                "occurrences" to Json.Arr(occurrenceRows),
                "settings" to Json.Obj(settings.map { (key, value) -> key to Json.Str(value) }),
            ),
        ).encode()
    }
}
