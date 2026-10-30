package org.librewakeword.integration

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.librewakeword.client.WakewordEvent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val payload = inputData.getString(KEY_PAYLOAD) ?: return@withContext Result.failure()
        val secret = inputData.getString(KEY_SECRET).orEmpty()
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val target = try {
            URI(url)
        } catch (_: Exception) {
            return@withContext Result.failure()
        }
        if (target.scheme?.lowercase() != "https" || target.host.isNullOrBlank()) {
            return@withContext Result.failure()
        }
        var connection: HttpURLConnection? = null
        try {
            connection = target.toURL().openConnection() as? HttpURLConnection
                ?: return@withContext Result.failure()
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/cloudevents+json")
            connection.setRequestProperty("User-Agent", "LibreWakeWord/1")
            if (secret.isNotEmpty()) {
                connection.setRequestProperty(
                    "LWW-Signature",
                    "t=$timestamp,v1=${hmac(secret, "$timestamp.$payload")}",
                )
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val response = connection.responseCode
            if (response in 200..299) Result.success()
            else if (response == 408 || response == 429 || response >= 500) retryOrGiveUp()
            else Result.failure()
        } catch (_: IOException) {
            retryOrGiveUp()
        } catch (_: Exception) {
            Result.failure()
        } finally {
            connection?.disconnect()
        }
    }

    // A wake event that arrives minutes late is rarely useful, so retries are short-lived.
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val KEY_URL = "url"
        private const val KEY_SECRET = "secret"
        private const val KEY_PAYLOAD = "payload"

        fun enqueue(context: Context, config: IntegrationStore.WebhookConfiguration, event: WakewordEvent) {
            val payload = cloudEvent(event)
            val request = OneTimeWorkRequestBuilder<WebhookWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_URL, config.url)
                        .putString(KEY_SECRET, config.secret)
                        .putString(KEY_PAYLOAD, payload)
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "wakeword-webhook-${event.eventId}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        internal fun cloudEvent(event: WakewordEvent): String = """
            {"specversion":"1.0","type":"org.librewakeword.wakeword.confirmed.v1","source":"android://org.librewakeword","id":"${escape(event.eventId)}","time":"${Instant.ofEpochMilli(event.occurredAtEpochMillis)}","subject":"wakewords/${escape(event.phrase.orEmpty())}","datacontenttype":"application/json","data":{"detectionId":"${escape(event.detectionId)}","phrase":"${escape(event.phrase.orEmpty())}","locale":"${escape(event.localeTag.orEmpty())}"}}
        """.trimIndent()

        private fun escape(value: String): String = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) append("\\u%04x".format(character.code))
                    else append(character)
                }
            }
        }

        internal fun hmac(secret: String, value: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
