package com.awacker.billsnotifier.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.awacker.billsnotifier.R
import com.awacker.billsnotifier.data.BillsRepository
import com.awacker.billsnotifier.data.local.DueItem
import com.awacker.billsnotifier.domain.model.Money
import com.awacker.billsnotifier.ui.MainActivity

/**
 * Posts the morning notification listing what falls due today, then schedules tomorrow's run.
 *
 * Reads only from the local database, so it works with no connectivity — which is the whole
 * reason the app doesn't treat the Google Sheet as its storage. The email digest is
 * produced independently by the Apps Script from the synced sheet; the two paths never
 * depend on each other, so either can fail without silencing the other.
 */
class DailyDigestWorker(
    context: Context,
    parameters: WorkerParameters,
    private val repository: BillsRepository,
    /**
     * Arms tomorrow's run. Injected so a test can assert the daily chain continues —
     * that self-rescheduling is the entire mechanism, and a silent break in it would
     * look exactly like a working app right up until the notifications stopped.
     */
    private val rescheduleNext: (Context) -> Unit = DigestScheduler::scheduleNext,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val today = repository.today()
        val due = try {
            repository.dueOn(today)
        } catch (error: Exception) {
            // Reschedule regardless — a bad day must not silently end the daily chain.
            rescheduleNext(applicationContext)
            return Result.retry()
        }

        if (due.isNotEmpty()) {
            notify(due)
        }
        // Deliberately silent on days with nothing due; a daily "nothing due" notification
        // trains you to swipe it away without reading it.

        rescheduleNext(applicationContext)
        return Result.success()
    }

    private fun notify(due: List<DueItem>) {
        val context = applicationContext
        if (!canPostNotifications(context)) return

        ensureChannel(context)

        val total = due.sumOf { it.amountCents }
        val title = if (due.size == 1) {
            "${due.first().billName} — ${Money.format(due.first().amountCents)} due today"
        } else {
            "${due.size} payments due today — ${Money.format(total)}"
        }

        val lines = due.map { item ->
            val progress = "payment ${item.sequence} of ${item.totalCount}"
            val autopay = if (item.autopay) " · autopay" else ""
            "${item.billName} — ${Money.format(item.amountCents)} ($progress$autopay)"
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                lines.forEach(style::addLine)
                if (due.size > 1) style.setSummaryText(Money.format(total) + " total")
            })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "due_today"
        const val NOTIFICATION_ID = 1001

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Payments due today",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "A morning summary of payment plan installments falling due today."
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        /** API 33+ requires the runtime permission; posting without it silently does nothing. */
        fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
