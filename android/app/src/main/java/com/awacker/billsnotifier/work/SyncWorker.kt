package com.awacker.billsnotifier.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.awacker.billsnotifier.data.BillsRepository
import com.awacker.billsnotifier.data.prefs.SettingsStore
import com.awacker.billsnotifier.data.remote.PullResult
import com.awacker.billsnotifier.data.remote.SheetSyncClient
import com.awacker.billsnotifier.data.remote.SyncResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps this device and the Google Sheet in step — in whichever direction this install runs.
 *
 * The owning phone uploads a complete snapshot rather than a diff: the dataset is small and
 * there is only ever one writer, so wholesale replacement cannot drift. A read-only mirror
 * downloads instead, which is what makes a second phone safe to install — two uploading
 * devices would overwrite each other's plans on every sync.
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

        return if (current.isMirrorDevice) {
            download(current.webAppUrl, current.sharedSecret)
        } else {
            upload(current.webAppUrl, current.sharedSecret, current.emailRecipients)
        }
    }

    private suspend fun upload(url: String, secret: String, recipients: String): Result {
        val result = client.sync(
            webAppUrl = url,
            secret = secret,
            plans = repository.snapshotForSync(),
            today = repository.today(),
            updatedAt = nowStamp(),
            settings = buildMap {
                if (recipients.isNotBlank()) put("email_recipients", recipients)
            },
        )

        return when (result) {
            is SyncResult.Success -> {
                settings.markSynced(result.serverTime.ifBlank { nowStamp() })
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

            // Not produced by sync, but the type allows it; treat it as a clean run.
            is SyncResult.Reported -> Result.success()
        }
    }

    /** Replaces local data with the sheet's copy. Writes nothing back. */
    private suspend fun download(url: String, secret: String): Result =
        when (val result = client.pull(url, secret)) {
            is PullResult.Success -> {
                repository.replaceAllFromMirror(result.plans)
                settings.markSynced(result.serverTime.ifBlank { nowStamp() })
                Result.success()
            }

            is PullResult.Failed -> {
                settings.markSyncFailed(result.message)
                if (result.retryable) Result.retry() else Result.failure()
            }
        }

    private fun nowStamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
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
