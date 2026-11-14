package com.manjaniq.remotecontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Client for the public, device-specific subscription endpoint:
 *
 * ```
 * GET https://manjaniq-hub.manjaniqhub.workers.dev/api/subscription?device_id=XYZ
 * ```
 *
 * It is intentionally independent of the policy client and of both engine
 * transports: it owns no cookies, no cache, no shared proxy interceptor and no
 * connection pool. HTTPS uses Android's normal certificate and hostname
 * validation, redirects are disabled, and only a bounded HTTP 200 JSON body is
 * accepted. Every transport/protocol failure returns null, which the caller
 * treats as "no subscription information" - never as a restriction.
 */
internal class ManjaniqSubscription(
    // Internal constructor seam for host tests; production always uses the
    // fixed HTTPS endpoint below and the platform's URL connection factory.
    private val openConnection: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : SubscriptionSource {
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    override suspend fun fetch(deviceId: String): SubscriptionInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            coroutineContext.ensureActive()
            if (deviceId.isBlank()) return@withContext null
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = URL("$SUBSCRIPTION_ENDPOINT?device_id=$encodedId")
            connection = openConnection(url)
            if (connection !is HttpsURLConnection || url.protocol != "https") {
                return@withContext null
            }
            activeConnection.set(connection)
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            coroutineContext.ensureActive()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                return@withContext null
            }
            val body = connection.inputStream.use { readBounded(it) }
                ?: return@withContext null
            coroutineContext.ensureActive()
            SubscriptionParser.parse(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not log URLs, response bodies or device identifiers.
            null
        } finally {
            connection?.let {
                activeConnection.compareAndSet(it, null)
                it.disconnect()
            }
        }
    }

    override fun cancelPending() {
        // Unblocks a deadline-expired worker stuck in connect/read. The worker
        // still owns cleanup and the resulting value; this only releases it.
        try {
            activeConnection.get()?.disconnect()
        } catch (_: Exception) {
            // Best effort: the socket also has connect and read timeouts.
        }
    }

    private fun readBounded(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val SUBSCRIPTION_ENDPOINT =
            "https://manjaniq-hub.manjaniqhub.workers.dev/api/subscription"
        internal const val CONNECT_TIMEOUT_MS = 3_000
        internal const val READ_TIMEOUT_MS = 3_000
        internal const val REFRESH_TIMEOUT_MS = 7_000L
        internal const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
