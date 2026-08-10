package com.awacker.billsnotifier.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Occurrence
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import java.time.Instant
import java.time.LocalDate

/**
 * Room storage for payment plans.
 *
 * The local database is the source of truth. The Google Sheet is a mirror kept up to date
 * by background sync — which means the morning notification is computed entirely from here
 * and never waits on the network.
 *
 * The domain layer models recurrence and plan termination as sealed types so illegal
 * combinations cannot be built. SQLite has no such notion, so these entities flatten them
 * into a discriminator plus parameters and [toDomain] reassembles them.
 */
@Entity(tableName = "bills")
data class BillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val payee: String?,
    val installmentAmountCents: Long,
    val finalAmountCents: Long?,
    /** WEEKLY | MONTHLY | SEMI_MONTHLY | YEARLY */
    val recurrenceType: String,
    /** Interval for weekly/monthly/yearly; unused for semi-monthly. */
    val recurrenceInterval: Int,
    val semiMonthlyFirstDay: Int?,
    val semiMonthlySecondDay: Int?,
    val firstDueDate: LocalDate,
    /** BY_COUNT | BY_DATE */
    val endMode: String,
    val installmentCount: Int?,
    val endDate: LocalDate?,
    val autopay: Boolean,
    val notificationsEnabled: Boolean,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Soft delete, so a removed plan can still be tombstoned on the next sync. */
    val archivedAt: Instant?,
) {
    fun toDomain(): Bill = Bill(
        id = id,
        name = name,
        payee = payee,
        installmentAmountCents = installmentAmountCents,
        finalAmountCents = finalAmountCents,
        recurrence = when (recurrenceType) {
            RECURRENCE_WEEKLY -> Recurrence.Weekly(recurrenceInterval)
            RECURRENCE_MONTHLY -> Recurrence.Monthly(recurrenceInterval)
            RECURRENCE_YEARLY -> Recurrence.Yearly(recurrenceInterval)
            RECURRENCE_SEMI_MONTHLY -> Recurrence.SemiMonthly(
                firstDay = semiMonthlyFirstDay ?: 1,
                secondDay = semiMonthlySecondDay ?: 15,
            )

            else -> error("Unknown recurrence type '$recurrenceType' for bill $id")
        },
        firstDueDate = firstDueDate,
        end = when (endMode) {
            END_BY_COUNT -> PlanEnd.AfterPayments(
                installmentCount ?: error("Bill $id ends by count but has no count"),
            )

            END_BY_DATE -> PlanEnd.OnOrBefore(
                endDate ?: error("Bill $id ends by date but has no end date"),
            )

            else -> error("Unknown end mode '$endMode' for bill $id")
        },
        autopay = autopay,
        notificationsEnabled = notificationsEnabled,
        notes = notes,
    )

    companion object {
        const val RECURRENCE_WEEKLY = "WEEKLY"
        const val RECURRENCE_MONTHLY = "MONTHLY"
        const val RECURRENCE_SEMI_MONTHLY = "SEMI_MONTHLY"
        const val RECURRENCE_YEARLY = "YEARLY"
        const val END_BY_COUNT = "BY_COUNT"
        const val END_BY_DATE = "BY_DATE"

        fun fromDomain(bill: Bill, createdAt: Instant, updatedAt: Instant, archivedAt: Instant? = null) =
            BillEntity(
                id = bill.id,
                name = bill.name,
                payee = bill.payee,
                installmentAmountCents = bill.installmentAmountCents,
                finalAmountCents = bill.finalAmountCents,
                recurrenceType = when (bill.recurrence) {
                    is Recurrence.Weekly -> RECURRENCE_WEEKLY
                    is Recurrence.Monthly -> RECURRENCE_MONTHLY
                    is Recurrence.SemiMonthly -> RECURRENCE_SEMI_MONTHLY
                    is Recurrence.Yearly -> RECURRENCE_YEARLY
                },
                recurrenceInterval = when (val recurrence = bill.recurrence) {
                    is Recurrence.Weekly -> recurrence.everyNWeeks
                    is Recurrence.Monthly -> recurrence.everyNMonths
                    is Recurrence.Yearly -> recurrence.everyNYears
                    is Recurrence.SemiMonthly -> 1
                },
                semiMonthlyFirstDay = (bill.recurrence as? Recurrence.SemiMonthly)?.firstDay,
                semiMonthlySecondDay = (bill.recurrence as? Recurrence.SemiMonthly)?.secondDay,
                firstDueDate = bill.firstDueDate,
                endMode = when (bill.end) {
                    is PlanEnd.AfterPayments -> END_BY_COUNT
                    is PlanEnd.OnOrBefore -> END_BY_DATE
                },
                installmentCount = (bill.end as? PlanEnd.AfterPayments)?.count,
                endDate = (bill.end as? PlanEnd.OnOrBefore)?.date,
                autopay = bill.autopay,
                notificationsEnabled = bill.notificationsEnabled,
                notes = bill.notes,
                createdAt = createdAt,
                updatedAt = updatedAt,
                archivedAt = archivedAt,
            )
    }
}

/**
 * One materialized installment.
 *
 * These are generated from the plan rather than entered, but they are stored rather than
 * recomputed on the fly because they carry paid state, and because the sheet sync needs a
 * stable row identity. [id] is deterministic (`billId#sequence`).
 */
@Entity(
    tableName = "occurrences",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("billId"), Index("dueDate")],
)
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val billId: String,
    val sequence: Int,
    @ColumnInfo(name = "dueDate") val dueDate: LocalDate,
    val amountCents: Long,
    val paidOn: LocalDate?,
    val paidAmountCents: Long?,
    val updatedAt: Instant,
) {
    fun toDomain(): Occurrence = Occurrence(
        id = id,
        billId = billId,
        sequence = sequence,
        dueDate = dueDate,
        amountCents = amountCents,
        paidOn = paidOn,
        paidAmountCents = paidAmountCents,
    )

    companion object {
        fun fromDomain(occurrence: Occurrence, updatedAt: Instant) = OccurrenceEntity(
            id = occurrence.id,
            billId = occurrence.billId,
            sequence = occurrence.sequence,
            dueDate = occurrence.dueDate,
            amountCents = occurrence.amountCents,
            paidOn = occurrence.paidOn,
            paidAmountCents = occurrence.paidAmountCents,
            updatedAt = updatedAt,
        )
    }
}

/**
 * Dates are stored as ISO `yyyy-MM-dd` text rather than epoch numbers.
 *
 * A due date is a calendar date, not an instant: "the 15th" means the 15th regardless of
 * timezone. Storing it as an epoch millisecond would reintroduce exactly the timezone
 * shifting this project avoids elsewhere, and it keeps the DB readable.
 */
class Converters {

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun instantToEpochMilli(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMilliToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
