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

internal interface RemotePolicySource {
    /** null means unavailable/invalid, never "banned". */
    suspend fun fetch(deviceId: String): RemotePolicy?

    /** Releases a blocking socket when the startup deadline expires. */
    fun cancelPending() {}
}

/**
 * Small, isolated control-plane client. It does not share an HTTP client,
 * interceptor, proxy setting, cookie store or connection pool with either
 * networking engine.
 *
 * HTTPS uses Android's normal certificate/hostname validation. Redirects are
 * disabled so neither the device identifier nor policy traffic can be sent to
 * a different origin or downgraded to HTTP. Only a bounded HTTP 200 JSON body
 * is accepted; every transport/protocol failure fails open.
 */
internal class ManjaniqRemoteControl(
    // Internal constructor seam for host tests; production always uses the
    // fixed HTTPS endpoint below and the platform's URL connection factory.
    private val openConnection: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : RemotePolicySource {
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    override suspend fun fetch(deviceId: String): RemotePolicy? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            coroutineContext.ensureActive()
            if (deviceId.isBlank()) return@withContext null
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = URL("$CHECK_ENDPOINT?device_id=$encodedId")
            connection = openConnection(url)
            // The endpoint is immutable in production. This also makes an
            // accidental future HTTP/downgrade change fail before connecting.
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
            RemotePolicyParser.parse(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not log URLs, response bodies, passwords or device identifiers.
            null
        } finally {
            connection?.let {
                activeConnection.compareAndSet(it, null)
                it.disconnect()
            }
        }
    }

    override fun cancelPending() {
        // disconnect() is safe to call from the deadline watcher while an IO
        // worker is blocked in connect/read. The worker still owns cleanup;
        // this only unblocks it and never changes the resulting policy.
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
        const val CHECK_ENDPOINT = "https://manjaniq-hub.manjaniqhub.workers.dev/api/check"
        internal const val CONNECT_TIMEOUT_MS = 3_000
        internal const val READ_TIMEOUT_MS = 3_000
        internal const val CHECK_TIMEOUT_MS = 7_000L
        internal const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
