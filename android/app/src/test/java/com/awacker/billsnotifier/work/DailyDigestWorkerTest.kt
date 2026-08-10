package com.awacker.billsnotifier.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.awacker.billsnotifier.TestApp
import com.awacker.billsnotifier.data.BillsRepository
import com.awacker.billsnotifier.data.local.BillsDatabase
import com.awacker.billsnotifier.data.prefs.SettingsStore
import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs the morning digest end to end on the JVM: real Room queries, real
 * NotificationCompat, real WorkManager result handling.
 *
 * The clock is fixed so "today" is a known date rather than whenever the suite happens to
 * run — otherwise these tests would quietly change meaning every day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class DailyDigestWorkerTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val today: LocalDate = LocalDate.parse("2026-03-15")
    private val clock: Clock = Clock.fixed(
        today.atStartOfDay(zone).toInstant().plusSeconds(7 * 3600),
        zone,
    )

    private lateinit var context: Application
    private lateinit var database: BillsDatabase
    private lateinit var repository: BillsRepository

    /** Records that tomorrow's run was armed, without touching WorkManager. */
    private var rescheduled = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        database = Room.inMemoryDatabaseBuilder(context, BillsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BillsRepository(
            dao = database.billsDao(),
            settings = SettingsStore(context),
            clock = clock,
        )
    }

    @After
    fun tearDown() = database.close()

    private fun plan(
        id: String,
        name: String,
        firstDue: LocalDate,
        amountCents: Long = 25_000,
        payments: Int = 24,
        autopay: Boolean = false,
    ) = Bill(
        id = id,
        name = name,
        installmentAmountCents = amountCents,
        recurrence = Recurrence.Monthly(),
        firstDueDate = firstDue,
        end = PlanEnd.AfterPayments(payments),
        autopay = autopay,
    )

    private suspend fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<DailyDigestWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = DailyDigestWorker(
                        appContext,
                        workerParameters,
                        repository,
                        rescheduleNext = { rescheduled++ },
                    )
                },
            )
            .build()
        return worker.doWork()
    }

    private fun postedNotifications(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun Notification.title(): String? =
        extras.getString(Notification.EXTRA_TITLE)

    @Test
    fun `posts a notification naming the single payment due today`() = runTest {
        repository.savePlan(plan("car", "Car loan", today))

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        val posted = postedNotifications()
        assertEquals(1, posted.size)
        assertEquals("Car loan — \$250.00 due today", posted.first().title())
    }

    @Test
    fun `summarises and totals when several plans fall due together`() = runTest {
        repository.savePlan(plan("car", "Car loan", today, amountCents = 25_000))
        repository.savePlan(plan("dentist", "Dentist", today, amountCents = 12_550))

        runWorker()

        assertEquals("2 payments due today — \$375.50", postedNotifications().first().title())
    }

    @Test
    fun `stays silent on a day with nothing due`() = runTest {
        // Falls due on the 20th, so the 15th has nothing.
        repository.savePlan(plan("car", "Car loan", today.plusDays(5)))

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("a quiet day must not notify", postedNotifications().isEmpty())
    }

    @Test
    fun `stays silent when there are no plans at all`() = runTest {
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun `skips a payment already marked paid`() = runTest {
        repository.savePlan(plan("car", "Car loan", today))
        val occurrence = repository.getPlan("car")!!.occurrences.first { it.dueDate == today }
        repository.markPaid(occurrence.id, today, occurrence.amountCents)

        runWorker()

        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun `does not announce a plan with notifications switched off`() = runTest {
        repository.savePlan(plan("car", "Car loan", today).copy(notificationsEnabled = false))

        runWorker()

        assertTrue(postedNotifications().isEmpty())
    }

    /**
     * The chain is the mechanism: each run arms the next one. If this ever stops happening
     * the app looks entirely healthy until the morning the notifications simply stop.
     */
    @Test
    fun `arms tomorrow's run whether or not anything was due`() = runTest {
        repository.savePlan(plan("car", "Car loan", today))
        runWorker()
        assertEquals(1, rescheduled)

        // And again on a quiet day.
        rescheduled = 0
        repository.archivePlan("car")
        runWorker()
        assertEquals("a quiet day must still arm tomorrow", 1, rescheduled)
    }

    @Test
    fun `posts on the high-importance channel so it is not silently collapsed`() = runTest {
        repository.savePlan(plan("car", "Car loan", today))
        runWorker()

        val channel = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotificationChannel(DailyDigestWorker.CHANNEL_ID) as android.app.NotificationChannel
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(DailyDigestWorker.CHANNEL_ID, postedNotifications().first().channelId)
    }

    @Test
    fun `does not post when the notification permission was denied`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        repository.savePlan(plan("car", "Car loan", today))

        val result = runWorker()

        // Still succeeds and still arms tomorrow — the user may grant it later.
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(postedNotifications().isEmpty())
        assertEquals(1, rescheduled)
    }

    @Test
    fun `notification body carries progress and the autopay wording`() = runTest {
        repository.savePlan(
            plan("car", "Car loan", today.minusMonths(2), payments = 24, autopay = true),
        )

        runWorker()

        val text = postedNotifications().first()
            .extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue("expected progress in \"$text\"", text.contains("payment 3 of 24"))
        assertTrue("expected autopay wording in \"$text\"", text.contains("autopay"))
        assertFalse(text.isBlank())
    }
}
