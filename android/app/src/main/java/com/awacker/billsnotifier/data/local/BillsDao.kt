package com.awacker.billsnotifier.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** A plan together with its materialized schedule, fetched in one shot. */
data class BillWithOccurrences(
    @Embedded val bill: BillEntity,
    @Relation(parentColumn = "id", entityColumn = "billId")
    val occurrences: List<OccurrenceEntity>,
)

@Dao
interface BillsDao {

    @Transaction
    @Query("SELECT * FROM bills WHERE archivedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActiveBills(): Flow<List<BillWithOccurrences>>

    @Transaction
    @Query("SELECT * FROM bills ORDER BY name COLLATE NOCASE")
    suspend fun getAllBills(): List<BillWithOccurrences>

    @Transaction
    @Query("SELECT * FROM bills WHERE id = :billId")
    fun observeBill(billId: String): Flow<BillWithOccurrences?>

    @Transaction
    @Query("SELECT * FROM bills WHERE id = :billId")
    suspend fun getBill(billId: String): BillWithOccurrences?

    /**
     * Unpaid installments falling due on a given date, joined to their plan name.
     *
     * This is what the morning digest runs. It deliberately excludes plans with
     * notifications switched off, and reads only from the local database so it works with
     * no connectivity.
     */
    @Query(
        """
        SELECT o.id AS occurrenceId, o.billId AS billId, b.name AS billName,
               o.sequence AS sequence, o.amountCents AS amountCents, o.dueDate AS dueDate,
               b.autopay AS autopay,
               (SELECT COUNT(*) FROM occurrences WHERE billId = b.id) AS totalCount
        FROM occurrences o
        JOIN bills b ON b.id = o.billId
        WHERE o.dueDate = :date
          AND o.paidOn IS NULL
          AND b.archivedAt IS NULL
          AND b.notificationsEnabled = 1
        ORDER BY b.name COLLATE NOCASE
        """,
    )
    suspend fun getUnpaidDueOn(date: LocalDate): List<DueItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBill(bill: BillEntity)

    @Update
    suspend fun updateBill(bill: BillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrences(occurrences: List<OccurrenceEntity>)

    @Query("DELETE FROM occurrences WHERE billId = :billId")
    suspend fun deleteOccurrencesFor(billId: String)

    @Query("DELETE FROM bills WHERE id = :billId")
    suspend fun deleteBill(billId: String)

    @Query("DELETE FROM bills")
    suspend fun deleteAllBills()

    /**
     * Replaces everything with a downloaded copy, for a read-only mirror device.
     *
     * One transaction so the device is never left holding half a download — the digest
     * runs off this data, and a partial replace would announce a fictional set of payments.
     * Occurrences go with their bills via the cascade.
     */
    @Transaction
    suspend fun replaceAll(bills: List<BillEntity>, occurrences: List<OccurrenceEntity>) {
        deleteAllBills()
        bills.forEach { upsertBill(it) }
        upsertOccurrences(occurrences)
    }

    @Query("UPDATE bills SET archivedAt = :archivedAt, updatedAt = :archivedAt WHERE id = :billId")
    suspend fun archiveBill(billId: String, archivedAt: Instant)

    @Query(
        """
        UPDATE occurrences
        SET paidOn = :paidOn, paidAmountCents = :paidAmountCents, updatedAt = :updatedAt
        WHERE id = :occurrenceId
        """,
    )
    suspend fun setPaid(
        occurrenceId: String,
        paidOn: LocalDate?,
        paidAmountCents: Long?,
        updatedAt: Instant,
    )

    /**
     * Replaces a plan's schedule wholesale.
     *
     * Done in one transaction so a regenerated schedule can never be half-applied — which
     * would leave the sheet, and therefore the email, describing a plan that doesn't exist.
     */
    @Transaction
    suspend fun replaceSchedule(billId: String, occurrences: List<OccurrenceEntity>) {
        deleteOccurrencesFor(billId)
        upsertOccurrences(occurrences)
    }
}

/** Flat projection for the digest query. */
data class DueItem(
    val occurrenceId: String,
    val billId: String,
    val billName: String,
    val sequence: Int,
    val amountCents: Long,
    val dueDate: LocalDate,
    val autopay: Boolean,
    val totalCount: Int,
)

@Database(
    entities = [BillEntity::class, OccurrenceEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BillsDatabase : RoomDatabase() {
    abstract fun billsDao(): BillsDao
}
