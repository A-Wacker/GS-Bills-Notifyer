package com.awacker.billsnotifier.data.remote

import com.awacker.billsnotifier.domain.sync.DigestOutcome
import com.awacker.billsnotifier.domain.sync.PlanSnapshot
import com.awacker.billsnotifier.domain.sync.SyncPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate

sealed interface SyncResult {
    data class Success(val billCount: Int, val occurrenceCount: Int, val serverTime: String) : SyncResult
    /** The script ran and had something to say about it — shown to the user as-is. */
    data class Reported(val message: String) : SyncResult
    /** The request reached the script and it said no. Retrying unchanged will not help. */
    data class Rejected(val message: String) : SyncResult
    /** Network or transport level. Worth retrying later. */
    data class Failed(val message: String) : SyncResult
}

/**
 * Posts snapshots to the Apps Script web app.
 *
 * A note on redirects: Apps Script answers a web app POST with a 302 to
 * script.googleusercontent.com. OkHttp follows redirects by default, but on a 302 it
 * converts the POST to a GET — which is exactly what we want here, because the script's
 * response body is served from the redirect target. The request body has already been
 * delivered to the first URL, so nothing is lost.
 */
class SheetSyncClient(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun ping(webAppUrl: String, secret: String): SyncResult =
        post(webAppUrl, SyncPayload.ping(secret)) { json ->
            SyncResult.Success(
                billCount = 0,
                occurrenceCount = 0,
                serverTime = json.optString("serverTime"),
            )
        }

    /**
     * Asks the script to run today's digest now and report what happened.
     *
     * Deliberately the real thing rather than a simulation: it respects the same
     * once-a-day guard, so if the email already went out it says so instead of sending a
     * duplicate.
     */
    suspend fun runDigest(webAppUrl: String, secret: String): SyncResult =
        post(webAppUrl, SyncPayload.digest(secret)) { json ->
            SyncResult.Reported(
                DigestOutcome.describe(
                    reason = json.optString("reason", "unknown"),
                    dueTodayCount = json.optInt("dueTodayCount"),
                    pendingCount = json.optInt("pendingCount"),
                    recipients = json.optInt("recipients"),
                ),
            )
        }

    suspend fun sync(
        webAppUrl: String,
        secret: String,
        plans: List<PlanSnapshot>,
        today: LocalDate,
        updatedAt: String,
        settings: Map<String, String>,
    ): SyncResult {
        val body = SyncPayload.sync(
            secret = secret,
            plans = plans,
            today = today,
            updatedAt = updatedAt,
            settings = settings,
        )
        return post(webAppUrl, body) { json ->
            SyncResult.Success(
                billCount = json.optInt("billCount"),
                occurrenceCount = json.optInt("occurrenceCount"),
                serverTime = json.optString("serverTime"),
            )
        }
    }

    private suspend fun post(
        url: String,
        body: String,
        onSuccess: (JSONObject) -> SyncResult,
    ): SyncResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext SyncResult.Rejected("No web app URL configured")

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext SyncResult.Failed("HTTP ${response.code}")
                }

                val json = try {
                    JSONObject(text)
                } catch (error: Exception) {
                    // Apps Script serves an HTML error page when the deployment is
                    // misconfigured, so say something more useful than "parse error".
                    return@withContext SyncResult.Rejected(
                        "Unexpected response — check the deployment is set to " +
                            "\"Anyone with the link\" and the URL ends in /exec",
                    )
                }

                if (json.optBoolean("ok")) {
                    onSuccess(json)
                } else {
                    SyncResult.Rejected(json.optString("error", "Rejected by the script"))
                }
            }
        } catch (error: Exception) {
            SyncResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(60)) // Apps Script can be slow to warm up.
            .writeTimeout(Duration.ofSeconds(60))
            .build()
    }
}
