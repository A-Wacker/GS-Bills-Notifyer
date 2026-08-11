package com.awacker.billsnotifier.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awacker.billsnotifier.BillsApp
import com.awacker.billsnotifier.data.PlanWithProgress
import com.awacker.billsnotifier.data.prefs.AppSettings
import com.awacker.billsnotifier.data.remote.SyncResult
import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Occurrence
import com.awacker.billsnotifier.work.DigestScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Transient user-facing message, e.g. the result of a connection test. */
data class Toast(val message: String, val isError: Boolean = false)

class BillsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = BillsApp.container(application)
    private val repository = container.repository
    private val settingsStore = container.settingsStore

    val plans: StateFlow<List<PlanWithProgress>> = repository.observePlans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun today(): LocalDate = repository.today()

    fun observePlan(billId: String) = repository.observePlan(billId)

    fun newPlanId(): String = repository.newPlanId()

    private suspend fun refuseIfMirror(): Boolean {
        if (!settingsStore.current().isMirrorDevice) return false
        _toast.value = Toast(
            "This device is a read-only mirror — edit on the phone that owns the plans",
            isError = true,
        )
        return true
    }

    fun savePlan(bill: Bill, onSaved: () -> Unit = {}) = viewModelScope.launch {
        if (refuseIfMirror()) return@launch
        try {
            repository.savePlan(bill)
            requestSync()
            onSaved()
        } catch (error: IllegalArgumentException) {
            // ScheduleGenerator rejects impossible plans; surface its message as-is since
            // it names the offending field.
            _toast.value = Toast(error.message ?: "That plan isn't valid", isError = true)
        }
    }

    fun setPaid(occurrence: Occurrence, paid: Boolean) = viewModelScope.launch {
        if (refuseIfMirror()) return@launch
        if (paid) {
            repository.markPaid(occurrence.id, repository.today(), occurrence.amountCents)
        } else {
            repository.markUnpaid(occurrence.id)
        }
        requestSync()
    }

    fun archivePlan(billId: String) = viewModelScope.launch {
        if (refuseIfMirror()) return@launch
        repository.archivePlan(billId)
        requestSync()
    }

    fun setDigestTime(hour: Int, minute: Int) = viewModelScope.launch {
        settingsStore.setDigestTime(hour, minute)
        // REPLACE, so the pending run moves to the new time instead of firing at the old one.
        DigestScheduler.scheduleNext(getApplication())
        _toast.value = Toast("Digest moved to %02d:%02d".format(hour, minute))
    }

    fun setConnection(url: String, secret: String) = viewModelScope.launch {
        settingsStore.setConnection(url, secret)
        _toast.value = Toast("Connection saved")
    }

    fun setEmailRecipients(recipients: String) = viewModelScope.launch {
        settingsStore.setEmailRecipients(recipients)
        requestSync()
    }

    fun testConnection() = viewModelScope.launch {
        val current = settingsStore.current()
        if (!current.isSyncConfigured) {
            _toast.value = Toast("Add the web app URL and secret first", isError = true)
            return@launch
        }
        _isBusy.value = true
        _toast.value = when (val result = container.syncClient.ping(current.webAppUrl, current.sharedSecret)) {
            is SyncResult.Success -> Toast("Connected — script replied at ${result.serverTime}")
            is SyncResult.Reported -> Toast(result.message)
            is SyncResult.Rejected -> Toast(result.message, isError = true)
            is SyncResult.Failed -> Toast("Could not reach the script: ${result.message}", isError = true)
        }
        _isBusy.value = false
    }

    fun requestSync() = DigestScheduler.requestSync(getApplication())

    /**
     * Switches this install between owning the data and mirroring it.
     *
     * Turning it on immediately downloads, which replaces everything held locally — the
     * sheet becomes the source of truth for this device. The settings screen confirms
     * before calling this.
     */
    fun setMirrorDevice(isMirror: Boolean) = viewModelScope.launch {
        settingsStore.setMirrorDevice(isMirror)
        _toast.value = if (isMirror) {
            requestSync()
            Toast("Downloading from the sheet — this device is now read-only")
        } else {
            Toast("This device now owns the data and will upload to the sheet")
        }
    }

    /** Device-only: runs the on-device digest worker. Sends no email. */
    fun testNotification() {
        DigestScheduler.runDigestNow(getApplication())
        _toast.value = Toast("Running the notification now — no email is sent by this")
    }

    /** Asks the Apps Script to run today's email digest and reports what it did. */
    fun sendTestEmail() = viewModelScope.launch {
        val current = settingsStore.current()
        if (!current.isSyncConfigured) {
            _toast.value = Toast("Add the web app URL and secret first", isError = true)
            return@launch
        }
        _isBusy.value = true
        _toast.value = when (
            val result = container.syncClient.runDigest(current.webAppUrl, current.sharedSecret)
        ) {
            is SyncResult.Reported -> Toast(result.message)
            is SyncResult.Success -> Toast("The script ran.")
            is SyncResult.Rejected -> Toast(result.message, isError = true)
            is SyncResult.Failed -> Toast("Could not reach the script: ${result.message}", isError = true)
        }
        _isBusy.value = false
    }

    fun clearToast() {
        _toast.value = null
    }
}
