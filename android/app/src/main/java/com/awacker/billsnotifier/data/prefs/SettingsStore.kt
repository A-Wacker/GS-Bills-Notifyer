package com.awacker.billsnotifier.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.awacker.billsnotifier.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore(name = "settings")

/** User preferences and sync bookkeeping. */
data class AppSettings(
    val digestHour: Int = 7,
    val digestMinute: Int = 0,
    val webAppUrl: String = "",
    val sharedSecret: String = "",
    val emailRecipients: String = "",
    /** Local changes are waiting to reach the sheet. */
    val isDirty: Boolean = false,
    val lastSyncAt: String = "",
    val lastSyncError: String = "",
) {
    val digestTime: LocalTime get() = LocalTime.of(digestHour, digestMinute)

    /** Sync is only attempted once both halves of the connection are configured. */
    val isSyncConfigured: Boolean get() = webAppUrl.isNotBlank() && sharedSecret.isNotBlank()
}

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDigestTime(hour: Int, minute: Int) = context.dataStore.edit {
        it[DIGEST_HOUR] = hour.coerceIn(0, 23)
        it[DIGEST_MINUTE] = minute.coerceIn(0, 59)
    }

    suspend fun setConnection(webAppUrl: String, sharedSecret: String) = context.dataStore.edit {
        it[WEBAPP_URL] = webAppUrl.trim()
        it[SHARED_SECRET] = sharedSecret.trim()
    }

    suspend fun setEmailRecipients(recipients: String) = context.dataStore.edit {
        it[EMAIL_RECIPIENTS] = recipients.trim()
        // The recipient list lives on the sheet, so changing it needs a sync to take effect.
        it[IS_DIRTY] = true
    }

    suspend fun markDirty() = context.dataStore.edit { it[IS_DIRTY] = true }

    suspend fun markSynced(at: String) = context.dataStore.edit {
        it[IS_DIRTY] = false
        it[LAST_SYNC_AT] = at
        it[LAST_SYNC_ERROR] = ""
    }

    suspend fun markSyncFailed(error: String) = context.dataStore.edit {
        it[LAST_SYNC_ERROR] = error
    }

    private fun Preferences.toAppSettings() = AppSettings(
        digestHour = this[DIGEST_HOUR] ?: 7,
        digestMinute = this[DIGEST_MINUTE] ?: 0,
        // Values baked in at build time from local.properties act as defaults, so a fresh
        // install is already configured; the settings screen can still override them.
        webAppUrl = this[WEBAPP_URL] ?: BuildConfig.WEBAPP_URL,
        sharedSecret = this[SHARED_SECRET] ?: BuildConfig.SHARED_SECRET,
        emailRecipients = this[EMAIL_RECIPIENTS] ?: "",
        isDirty = this[IS_DIRTY] ?: false,
        lastSyncAt = this[LAST_SYNC_AT] ?: "",
        lastSyncError = this[LAST_SYNC_ERROR] ?: "",
    )

    private companion object {
        val DIGEST_HOUR = intPreferencesKey("digest_hour")
        val DIGEST_MINUTE = intPreferencesKey("digest_minute")
        val WEBAPP_URL = stringPreferencesKey("webapp_url")
        val SHARED_SECRET = stringPreferencesKey("shared_secret")
        val EMAIL_RECIPIENTS = stringPreferencesKey("email_recipients")
        val IS_DIRTY = booleanPreferencesKey("is_dirty")
        val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
    }
}
