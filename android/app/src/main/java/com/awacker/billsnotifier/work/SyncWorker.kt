package com.awacker.billsnotifier.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.awacker.billsnotifier.data.BillsRepository
import com.awacker.billsnotifier.data.prefs.SettingsStore
import com.awacker.billsnotifier.data.remote.SheetSyncClient
import com.awacker.billsnotifier.data.remote.SyncResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Mirrors the local database to the Google Sheet.
 *
 * Sends a complete snapshot rather than a diff — the dataset is small and the phone is the
 * only writer, so wholesale replacement cannot drift. The script preserves the couple of
 * columns it owns.
 *
 * Nothing on the device depends on this succeeding; it is what keeps the email digest
 * accurate. A failure retries with backoff and surfaces on the settings screen.
 */
class SyncWorker(
    context: Context,
    parameters: WorkerParameters,
    private val repository: BillsRepository,
    private val settings: SettingsStore,
    private val client: SheetSyncClient,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val current = settings.current()
        if (!current.isSyncConfigured) {
            // Not an error: the app is perfectly usable with on-device notifications alone.
            return Result.success()
        }

        val result = client.sync(
            webAppUrl = current.webAppUrl,
            secret = current.sharedSecret,
            plans = repository.snapshotForSync(),
            today = repository.today(),
            updatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            settings = buildMap {
                if (current.emailRecipients.isNotBlank()) {
                    put("email_recipients", current.emailRecipients)
                }
            },
        )

        return when (result) {
            is SyncResult.Success -> {
                settings.markSynced(
                    result.serverTime.ifBlank {
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    },
                )
                Result.success()
            }

            // The script understood us and refused. Retrying the same payload won't help,
            // so record it for the settings screen and stop.
            is SyncResult.Rejected -> {
                settings.markSyncFailed(result.message)
                Result.failure()
            }

            is SyncResult.Failed -> {
                settings.markSyncFailed(result.message)
                Result.retry()
            }
        }
    }
}

/**
 * Re-arms the daily digest after a reboot.
 *
 * WorkManager does restore its own queue across restarts, so this is belt-and-braces rather
 * than strictly required — but the digest is the feature the app exists for, and
 * re-enqueueing with KEEP costs nothing and leaves an already-restored run alone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> DigestScheduler.ensureScheduled(context)
        }
    }
}
