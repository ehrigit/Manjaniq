package com.manjaniq

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manjaniq.core.AetherCoreManager
import com.manjaniq.core.AetherIdentityManager
import com.manjaniq.core.AetherPsiphon
import com.manjaniq.core.ProfileItem
import com.manjaniq.core.PsiphonServerList
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Bounded exploratory network measurements, not a network-dependent CI gate.
 * Every attempt records its actual result; a timeout or a mismatch is never
 * reported as a successful tunnel/exit. Deterministic contracts are gated by
 * PsiphonAndroidTest separately. Only country codes and coarse states are logged.
 */
@RunWith(AndroidJUnit4::class)
class PsiphonLiveEvidenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recordLiveTunnelTrafficAndExitEvidence() {
        val scratch = File(context.cacheDir, "psiphon-live-${System.nanoTime()}").apply { mkdirs() }
        try {
            val entries = PsiphonServerList.entriesFile(context, scratch) { throw AssertionError(it) }
            assertNotNull(entries)
            val regions = PsiphonServerList.regions(entries!!.readText()).sorted()
            assertTrue("the signed list must provide two candidate regions", regions.size >= 2)
            AetherBridge.initialize(context)
            assertTrue("native Aether is required to run the probe", AetherBridge.isNativeAvailable)
            val reports = (listOf("") + regions.take(2)).mapIndexed { index, requested ->
                runAttempt(scratch, entries, requested, index)
            }
            // Emit together so a long connection attempt cannot rotate earlier
            // evidence out of Android's bounded log buffer.
            reports.forEach { Log.i(TAG, "PSIPHON_LIVE $it") }
        } finally {
            AetherBridge.envReset()
            scratch.deleteRecursively()
        }
    }

    private fun runAttempt(root: File, entries: File, requested: String, index: Int): String {
        val state = File(root, "probe-$index")
        val port = ServerSocket(0).use { it.localPort }
        val profile = ProfileItem.create().apply {
            aetherPsiphon = AetherPsiphon.ONLY.type
            aetherPsiphonRegion = requested.ifEmpty { null }
        }
        val original = AetherCoreManager.buildArguments(profile, port = port)
        val arguments = listOf("--config", File(root, AetherIdentityManager.BASE_FILE).absolutePath) +
            AetherCoreManager.runtimeArguments(
                original, root, File(root, "bridges"), null, null, state,
                AetherCoreManager.psiphonFile(context), entries,
            )
        var phase = "not_started"
        var connected = false
        var ready = false
        var traffic = "not_attempted"
        var observed: String? = null
        var notice = "none"
        var job: Long? = null
        try {
            AetherBridge.envReset()
            val environment = AetherCoreManager.runtimeEnvironmentMap(context, root, original) +
                ("AETHER_PSIPHON_READY_SECS" to "70")
            if (!AetherBridge.envSet(environment).isSuccess) {
                phase = "environment_error"
            } else {
                var cursor = AetherBridge.logLines(0).getOrNull()?.lastOrNull()?.seq ?: 0L
                val started = AetherBridge.coreStart(arguments)
                val id = started.getOrNull()?.optLong("job", -1L)?.takeIf { it > 0 }
                job = id
                if (id == null) {
                    phase = "start_error"
                } else {
                    phase = "timeout"
                    val deadline = SystemClock.elapsedRealtime() + 75_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        for (line in AetherBridge.logLines(cursor).getOrNull().orEmpty()) {
                            cursor = maxOf(cursor, line.seq)
                            when {
                                line.text == "[+] psiphon tunnel established" -> connected = true
                                line.text == "[-] psiphon lost its tunnel; it is reconnecting" -> connected = false
                                line.text.contains("psiphon is ready;") -> ready = true
                                line.text.startsWith("[-] psiphon notice: ") -> {
                                    // The Rust side emits fixed, non-sensitive categories only.
                                    notice = line.text.removePrefix("[-] psiphon notice: ")
                                        .lowercase(Locale.ROOT).replace(' ', '_')
                                        .takeIf { it.matches(Regex("[a-z_-]+")) } ?: "other"
                                }
                            }
                        }
                        if (connected && ready) {
                            val result = httpsTraceThroughSocks(port)
                            traffic = result.first
                            observed = result.second
                            phase = "ready"
                            break
                        }
                        if (AetherBridge.jobPoll(id).getOrNull()?.optString("state") == "done") {
                            phase = "job_ended"
                            break
                        }
                        Thread.sleep(250)
                    }
                }
            }
        } catch (_: Exception) {
            // Exceptions may contain addresses or credentials; record only a fixed state.
            phase = "probe_error"
        } finally {
            job?.let { id ->
                AetherBridge.jobCancel(id)
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < deadline && AetherBridge.jobPoll(id).getOrNull()?.optString("state") != "done") {
                    Thread.sleep(50)
                }
                AetherBridge.jobFree(id)
            }
        }
        val match = when {
            requested.isEmpty() -> "not_requested"
            observed == null -> "unknown"
            observed == requested -> "yes"
            else -> "no"
        }
        return "requested=${requested.ifEmpty { "ANY" }} phase=$phase tunnel=$connected ready=$ready " +
            "https=$traffic exit=${observed ?: "unknown"} match=$match notice=$notice"
    }

    /** TLS-authenticated Cloudflare trace over the app's actual SOCKS listener. */
    private fun httpsTraceThroughSocks(port: Int): Pair<String, String?> {
        val host = "www.cloudflare.com"
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
                socket.soTimeout = 15_000
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) return "socks_auth_error" to null
                val domain = host.toByteArray(Charsets.US_ASCII)
                output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()))
                output.write(domain)
                output.write(byteArrayOf(1, 0xbb.toByte())) // port 443
                output.flush()
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) return "socks_connect_error" to null
                input.readUnsignedByte() // reserved
                val addressLength = when (input.readUnsignedByte()) {
                    1 -> 4
                    3 -> input.readUnsignedByte()
                    4 -> 16
                    else -> return "socks_protocol_error" to null
                }
                input.readFully(ByteArray(addressLength + 2))
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(socket, host, 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 15_000
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    tls.outputStream.write(
                        "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    )
                    tls.outputStream.flush()
                    val reader = tls.inputStream.bufferedReader(Charsets.US_ASCII)
                    val status = reader.readLine().orEmpty()
                    if (!status.matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) return "http_error" to null
                    var code: String? = null
                    for (ignored in 0 until 150) {
                        val line = reader.readLine() ?: break
                        if (line.matches(Regex("loc=[A-Z]{2}"))) {
                            code = line.substringAfter('=')
                            break
                        }
                    }
                    "ok" to code
                }
            }
        } catch (_: Exception) {
            "failed" to null
        }
    }

    companion object {
        private const val TAG = "PsiphonLiveProbe"
    }
}
