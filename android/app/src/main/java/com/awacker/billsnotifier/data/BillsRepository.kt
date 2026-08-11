package com.awacker.billsnotifier.data

import com.awacker.billsnotifier.data.local.BillEntity
import com.awacker.billsnotifier.data.local.BillWithOccurrences
import com.awacker.billsnotifier.data.local.BillsDao
import com.awacker.billsnotifier.data.local.DueItem
import com.awacker.billsnotifier.data.local.OccurrenceEntity
import com.awacker.billsnotifier.data.prefs.SettingsStore
import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Occurrence
import com.awacker.billsnotifier.domain.schedule.PlanMath
import com.awacker.billsnotifier.domain.schedule.PlanProgress
import com.awacker.billsnotifier.domain.schedule.ScheduleGenerator
import com.awacker.billsnotifier.domain.sync.PlanSnapshot
import com.awacker.billsnotifier.domain.sync.SheetParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A plan plus its schedule and derived payoff figures, as the UI wants it. */
data class PlanWithProgress(
    val bill: Bill,
    val occurrences: List<Occurrence>,
    val progress: PlanProgress,
    val isArchived: Boolean,
)

/**
 * The single place plans are created, edited and marked paid.
 *
 * Every mutation regenerates the affected schedule and flags the sheet as dirty, so the
 * sheet mirror and the email digest cannot silently fall behind what the app shows.
 */
class BillsRepository(
    private val dao: BillsDao,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    fun today(): LocalDate = LocalDate.now(clock)

    fun observePlans(): Flow<List<PlanWithProgress>> =
        dao.observeActiveBills().map { rows -> rows.map { it.toPlanWithProgress(today()) } }

    fun observePlan(billId: String): Flow<PlanWithProgress?> =
        dao.observeBill(billId).map { it?.toPlanWithProgress(today()) }

    suspend fun getPlan(billId: String): PlanWithProgress? =
        dao.getBill(billId)?.toPlanWithProgress(today())

    /** What the morning digest announces. Local-only, so it works offline. */
    suspend fun dueOn(date: LocalDate): List<DueItem> = dao.getUnpaidDueOn(date)

    /**
     * Creates or replaces a plan and regenerates its schedule.
     *
     * Paid marks are carried across by installment number rather than by date, so
     * correcting a start date or an amount does not un-make payments already recorded.
     */
    suspend fun savePlan(bill: Bill): String {
        val now = Instant.now(clock)
        val existing = dao.getBill(bill.id)

        val regenerated = ScheduleGenerator.generate(bill)
        val merged = PlanMath.preservePaidState(
            previous = existing?.occurrences?.map { it.toDomain() }.orEmpty(),
            regenerated = regenerated,
        )

        dao.upsertBill(
            BillEntity.fromDomain(
                bill = bill,
                createdAt = existing?.bill?.createdAt ?: now,
                updatedAt = now,
                archivedAt = existing?.bill?.archivedAt,
            ),
        )
        dao.replaceSchedule(bill.id, merged.map { OccurrenceEntity.fromDomain(it, now) })
        settings.markDirty()
        return bill.id
    }

    suspend fun markPaid(occurrenceId: String, paidOn: LocalDate, paidAmountCents: Long?) {
        dao.setPaid(occurrenceId, paidOn, paidAmountCents, Instant.now(clock))
        settings.markDirty()
    }

    suspend fun markUnpaid(occurrenceId: String) {
        dao.setPaid(occurrenceId, null, null, Instant.now(clock))
        settings.markDirty()
    }

    /** Soft delete, so the plan can still be marked inactive on the sheet. */
    suspend fun archivePlan(billId: String) {
        dao.archiveBill(billId, Instant.now(clock))
        settings.markDirty()
    }

    suspend fun deletePlan(billId: String) {
        dao.deleteBill(billId)
        settings.markDirty()
    }

    /** Everything the sheet sync sends, archived plans included so they can be deactivated. */
    suspend fun snapshotForSync(): List<PlanSnapshot> = dao.getAllBills().map { row ->
        PlanSnapshot(
            bill = row.bill.toDomain(),
            occurrences = row.occurrences.sortedBy { it.sequence }.map { it.toDomain() },
            isArchived = row.bill.archivedAt != null,
        )
    }

    /**
     * Replaces everything with a copy downloaded from the sheet, for a mirror device.
     *
     * Deliberately does not mark anything dirty: a mirror never uploads, and flagging it
     * would queue a push that overwrites the sheet with the copy it just read.
     */
    suspend fun replaceAllFromMirror(plans: List<SheetParser.MirroredPlan>) {
        val now = Instant.now(clock)
        dao.replaceAll(
            bills = plans.map { plan ->
                BillEntity.fromDomain(
                    bill = plan.bill,
                    createdAt = now,
                    updatedAt = now,
                    archivedAt = now.takeIf { plan.isArchived },
                )
            },
            occurrences = plans.flatMap { plan ->
                plan.occurrences.map { OccurrenceEntity.fromDomain(it, now) }
            },
        )
    }

    fun newPlanId(): String = UUID.randomUUID().toString()

    private fun BillWithOccurrences.toPlanWithProgress(today: LocalDate): PlanWithProgress {
        val domainOccurrences = occurrences.sortedBy { it.sequence }.map { it.toDomain() }
        return PlanWithProgress(
            bill = bill.toDomain(),
            occurrences = domainOccurrences,
            progress = PlanMath.progress(domainOccurrences, today),
            isArchived = bill.archivedAt != null,
        )
    }
}
