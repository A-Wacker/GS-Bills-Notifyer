package com.awacker.billsnotifier.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.awacker.billsnotifier.data.local.BillEntity
import com.awacker.billsnotifier.data.local.BillsDao
import com.awacker.billsnotifier.data.local.BillsDatabase
import com.awacker.billsnotifier.data.local.OccurrenceEntity
import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import com.awacker.billsnotifier.domain.schedule.ScheduleGenerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Exercises the Room layer against real SQLite.
 *
 * Two things here had never actually executed before: the hand-written digest query, with
 * its join and correlated subquery, and the flattening of the domain's sealed [Recurrence]
 * and [PlanEnd] types into columns. Both compile fine while being wrong.
 *
 * Robolectric rather than an emulator — the SQL and the type converters are what matter,
 * and this runs in the existing unit-test task in about a second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.awacker.billsnotifier.TestApp::class)
class BillsDaoTest {

    private lateinit var database: BillsDatabase
    private lateinit var dao: BillsDao

    private val now: Instant = Instant.parse("2026-03-01T09:00:00Z")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillsDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.billsDao()
    }

    @After
    fun tearDown() = database.close()

    // ---------------------------------------------------------------------------
    // Sealed-type flattening
    // ---------------------------------------------------------------------------

    private suspend fun roundTrip(bill: Bill): Bill {
        dao.upsertBill(BillEntity.fromDomain(bill, createdAt = now, updatedAt = now))
        return dao.getBill(bill.id)!!.bill.toDomain()
    }

    private fun plan(
        id: String = "plan",
        recurrence: Recurrence = Recurrence.Monthly(),
        end: PlanEnd = PlanEnd.AfterPayments(12),
        notificationsEnabled: Boolean = true,
    ) = Bill(
        id = id,
        name = "Plan $id",
        installmentAmountCents = 25_000,
        recurrence = recurrence,
        firstDueDate = LocalDate.parse("2026-01-15"),
        end = end,
        notificationsEnabled = notificationsEnabled,
    )

    @Test
    fun `every recurrence type survives the trip through columns`() = runTest {
        val cases = listOf(
            Recurrence.Weekly(1),
            Recurrence.Weekly(2),
            Recurrence.Monthly(1),
            Recurrence.Monthly(3),
            Recurrence.SemiMonthly(1, 15),
            Recurrence.SemiMonthly(5, 20),
            Recurrence.Yearly(1),
            Recurrence.Yearly(2),
        )
        for (recurrence in cases) {
            val restored = roundTrip(plan(recurrence = recurrence))
            assertEquals("recurrence changed in storage", recurrence, restored.recurrence)
        }
    }

    @Test
    fun `both plan endings survive the trip through columns`() = runTest {
        assertEquals(
            PlanEnd.AfterPayments(24),
            roundTrip(plan(end = PlanEnd.AfterPayments(24))).end,
        )
        val byDate = PlanEnd.OnOrBefore(LocalDate.parse("2027-06-30"))
        assertEquals(byDate, roundTrip(plan(end = byDate)).end)
    }

    @Test
    fun `dates and money survive unchanged`() = runTest {
        val restored = roundTrip(
            plan().copy(
                firstDueDate = LocalDate.parse("2026-01-31"),
                installmentAmountCents = 1,
                finalAmountCents = 99_999,
                payee = "First Credit Union",
                notes = "Account #1234",
                autopay = true,
            ),
        )
        assertEquals(LocalDate.parse("2026-01-31"), restored.firstDueDate)
        assertEquals(1, restored.installmentAmountCents)
        assertEquals(99_999, restored.finalAmountCents)
        assertEquals("First Credit Union", restored.payee)
        assertEquals(true, restored.autopay)
    }

    // ---------------------------------------------------------------------------
    // The digest query
    // ---------------------------------------------------------------------------

    /** Saves a plan with its generated schedule, optionally archived. */
    private suspend fun seed(bill: Bill, archived: Boolean = false): List<OccurrenceEntity> {
        dao.upsertBill(
            BillEntity.fromDomain(
                bill,
                createdAt = now,
                updatedAt = now,
                archivedAt = if (archived) now else null,
            ),
        )
        val occurrences = ScheduleGenerator.generate(bill)
            .map { OccurrenceEntity.fromDomain(it, now) }
        dao.replaceSchedule(bill.id, occurrences)
        return occurrences
    }

    @Test
    fun `finds unpaid installments due on the date`() = runTest {
        seed(plan("car"))
        val due = dao.getUnpaidDueOn(LocalDate.parse("2026-02-15"))

        assertEquals(1, due.size)
        assertEquals("car", due.first().billId)
        assertEquals("Plan car", due.first().billName)
        assertEquals(2, due.first().sequence)
        assertEquals(25_000, due.first().amountCents)
    }

    @Test
    fun `reports the plan length so the notification can say payment n of N`() = runTest {
        seed(plan("car", end = PlanEnd.AfterPayments(24)))
        assertEquals(24, dao.getUnpaidDueOn(LocalDate.parse("2026-01-15")).first().totalCount)
    }

    @Test
    fun `returns nothing on a day with no installments`() = runTest {
        seed(plan("car"))
        assertTrue(dao.getUnpaidDueOn(LocalDate.parse("2026-02-16")).isEmpty())
    }

    @Test
    fun `excludes an installment already marked paid`() = runTest {
        seed(plan("car"))
        val target = "car#2"
        dao.setPaid(target, LocalDate.parse("2026-02-14"), 25_000, now)

        assertTrue(dao.getUnpaidDueOn(LocalDate.parse("2026-02-15")).isEmpty())
    }

    @Test
    fun `excludes archived plans`() = runTest {
        seed(plan("car"), archived = true)
        assertTrue(dao.getUnpaidDueOn(LocalDate.parse("2026-02-15")).isEmpty())
    }

    @Test
    fun `excludes plans with notifications switched off`() = runTest {
        seed(plan("car", notificationsEnabled = false))
        assertTrue(dao.getUnpaidDueOn(LocalDate.parse("2026-02-15")).isEmpty())
    }

    @Test
    fun `returns several plans falling due on the same day, ordered by name`() = runTest {
        seed(plan("zebra").copy(name = "Zebra loan"))
        seed(plan("apple").copy(name = "Apple loan"))

        val due = dao.getUnpaidDueOn(LocalDate.parse("2026-02-15"))

        assertEquals(listOf("Apple loan", "Zebra loan"), due.map { it.billName })
    }

    @Test
    fun `carries the autopay flag through so the notification can word itself`() = runTest {
        seed(plan("car").copy(autopay = true))
        assertEquals(true, dao.getUnpaidDueOn(LocalDate.parse("2026-02-15")).first().autopay)
    }

    // ---------------------------------------------------------------------------
    // Schedule storage
    // ---------------------------------------------------------------------------

    @Test
    fun `replaceSchedule swaps the whole schedule`() = runTest {
        val bill = plan("car", end = PlanEnd.AfterPayments(12))
        seed(bill)
        assertEquals(12, dao.getBill("car")!!.occurrences.size)

        val shorter = bill.copy(end = PlanEnd.AfterPayments(3))
        dao.replaceSchedule(
            "car",
            ScheduleGenerator.generate(shorter).map { OccurrenceEntity.fromDomain(it, now) },
        )

        val stored = dao.getBill("car")!!.occurrences
        assertEquals(3, stored.size)
        assertEquals(listOf(1, 2, 3), stored.map { it.sequence }.sorted())
    }

    @Test
    fun `deleting a plan cascades to its schedule`() = runTest {
        seed(plan("car"))
        seed(plan("dentist"))

        dao.deleteBill("car")

        assertTrue(dao.getUnpaidDueOn(LocalDate.parse("2026-02-15")).none { it.billId == "car" })
        assertEquals(null, dao.getBill("car"))
        // The other plan is untouched.
        assertEquals(12, dao.getBill("dentist")!!.occurrences.size)
    }

    @Test
    fun `archiving hides a plan from the active list but keeps its data`() = runTest {
        seed(plan("car"))
        dao.archiveBill("car", now)

        assertEquals(0, dao.getAllBills().count { it.bill.archivedAt == null })
        assertEquals(12, dao.getBill("car")!!.occurrences.size)
    }

    @Test
    fun `marking paid and unpaid round-trips`() = runTest {
        seed(plan("car"))
        dao.setPaid("car#1", LocalDate.parse("2026-01-14"), 24_000, now)

        var stored = dao.getBill("car")!!.occurrences.first { it.sequence == 1 }
        assertEquals(LocalDate.parse("2026-01-14"), stored.paidOn)
        assertEquals(24_000, stored.paidAmountCents)

        dao.setPaid("car#1", null, null, now)
        stored = dao.getBill("car")!!.occurrences.first { it.sequence == 1 }
        assertEquals(null, stored.paidOn)
        assertEquals(null, stored.paidAmountCents)
    }
}
