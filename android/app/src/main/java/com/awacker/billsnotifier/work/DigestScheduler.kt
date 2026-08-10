package com.awacker.billsnotifier.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.awacker.billsnotifier.BillsApp
import com.awacker.billsnotifier.domain.schedule.DigestTiming
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Schedules the daily digest and the sheet sync.
 *
 * The digest uses a self-rescheduling one-shot rather than periodic work, because periodic
 * work cannot be pinned to a wall-clock time — it drifts relative to when it was first
 * enqueued.
 *
 * Deliberately inexact. Exact alarms on Android 12+ need SCHEDULE_EXACT_ALARM (a
 * user-granted permission) or USE_EXACT_ALARM (which Play restricts to alarm and calendar
 * apps), and a bill reminder does not need minute precision. The trade-off is that Doze can
 * push the digest later into the morning; see docs/SETUP.md on battery optimization, which
 * is the most common reason it arrives late or not at all.
 */
object DigestScheduler {

    private const val DIGEST_WORK = "daily-digest"
    private const val SYNC_WORK = "sheet-sync"

    /**
     * Ensures a digest is pending. Uses KEEP so routine calls — app start, boot — leave an
     * already-scheduled run alone instead of pushing it further out each time.
     */
    fun ensureScheduled(context: Context) = enqueueDigest(context, ExistingWorkPolicy.KEEP)

    /** Called after each run, and whenever the configured time changes. */
    fun scheduleNext(context: Context) = enqueueDigest(context, ExistingWorkPolicy.REPLACE)

    private fun enqueueDigest(context: Context, policy: ExistingWorkPolicy) {
        val settings = BillsApp.container(context).settingsBlocking()
        val delay = delayUntilNext(settings.digestTime)

        WorkManager.getInstance(context).enqueueUniqueWork(
            DIGEST_WORK,
            policy,
            OneTimeWorkRequestBuilder<DailyDigestWorker>()
                .setInitialDelay(delay)
                .addTag(DIGEST_WORK)
                .build(),
        )
    }

    /** Runs the digest immediately — backs the "Run digest now" button in settings. */
    fun runDigestNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$DIGEST_WORK-manual",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyDigestWorker>().build(),
        )
    }

    fun requestSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .addTag(SYNC_WORK)
                .build(),
        )
    }

    /**
     * Time from now until the next occurrence of [target] in the device's timezone.
     *
     * The DST-aware arithmetic lives in [DigestTiming] in the domain module, where it is
     * unit-tested against both clock changes.
     */
    fun delayUntilNext(target: LocalTime, now: ZonedDateTime = ZonedDateTime.now()): Duration =
        DigestTiming.delayUntilNext(target, now)
}
