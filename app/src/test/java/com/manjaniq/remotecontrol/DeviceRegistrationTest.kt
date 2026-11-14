package com.manjaniq.remotecontrol

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Uses Robolectric's real SharedPreferences implementation and a fake HTTPS
 * connection. No test opens a socket or contacts the live Worker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeviceRegistrationTest {
    private val info = DeviceRegistrationInfo(
        deviceId = "test-android-id",
        manufacturer = "Google",
        model = "Pixel 8",
        androidVersion = "14",
        appVersionName = "0.1.0",
        appVersionCode = 1L,
    )

    @Test
    fun firstLaunchPostsSpecsAndPersistsOnlyAfterHttp200() = runTest {
        val context = context()
        var requestedUrl: URL? = null
        val connection = FakeHttpsConnection(200).apply {
            beforeResponse = { assertFalse(registered(context)) }
        }
        val client = DeviceRegistration(
            context = context,
            openConnection = { requestedUrl = it; connection },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            deviceInfo = { info },
        )

        assertTrue(client.registerIfNeeded())
        assertTrue(registered(context))
        assertEquals(DeviceRegistration.REGISTER_ENDPOINT, requestedUrl.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals(3_000, connection.connectTimeout)
        assertEquals(3_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.doOutput)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertEquals("no-cache", connection.getRequestProperty("Cache-Control"))
        assertTrue(connection.outputClosed)
        assertTrue(connection.disconnected)

        val payload = JSONObject(connection.sentPayload())
        assertEquals(
            setOf("device_id", "manufacturer", "model", "android_version", "app_version"),
            payload.keys().asSequence().toSet(),
        )
        assertEquals(info.deviceId, payload.getString("device_id"))
        assertEquals(info.manufacturer, payload.getString("manufacturer"))
        assertEquals(info.model, payload.getString("model"))
        assertEquals(info.androidVersion, payload.getString("android_version"))
        val version = payload.getJSONObject("app_version")
        assertEquals(setOf("version_name", "version_code"), version.keys().asSequence().toSet())
        assertEquals(info.appVersionName, version.getString("version_name"))
        assertEquals(info.appVersionCode, version.getLong("version_code"))
    }

    @Test
    fun subsequentLaunchesSkipTheRequestAfterASuccessfulRegistration() = runTest {
        val context = context()
        var opened = 0
        val connection = FakeHttpsConnection(200)
        fun newProcess() = DeviceRegistration(
            context = context,
            openConnection = { opened++; connection },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            deviceInfo = { info },
        )

        assertTrue(newProcess().registerIfNeeded())
        assertEquals(1, opened)
        // A fresh client models a new OS process reading the same preferences.
        assertTrue(newProcess().registerIfNeeded())
        assertEquals(1, opened)
        assertTrue(registered(context))
    }

    @Test
    fun everyNon200LeavesTheFlagFalseAndTheNextLaunchRetries() = runTest {
        val context = context()
        var status = 500
        var attempts = 0
        val connections = mutableListOf<FakeHttpsConnection>()
        fun newProcess() = DeviceRegistration(
            context = context,
            openConnection = {
                attempts++
                FakeHttpsConnection(status).also { connections += it }
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            deviceInfo = { info },
        )

        for (failureStatus in listOf(201, 204, 301, 302, 400, 401, 403, 404, 429, 500, 503)) {
            status = failureStatus
            assertFalse("HTTP $failureStatus", newProcess().registerIfNeeded())
            assertFalse("HTTP $failureStatus must not register", registered(context))
            assertTrue("HTTP $failureStatus must disconnect", connections.last().disconnected)
        }
        assertEquals(11, attempts)

        status = 200
        assertTrue(newProcess().registerIfNeeded())
        assertTrue(registered(context))
        assertEquals(12, attempts)
        assertTrue(newProcess().registerIfNeeded())
        assertEquals(12, attempts)
    }

    @Test
    fun transportFailuresAreSilentAndRetryOnTheNextLaunch() = runTest {
        val context = context()
        var attempt = 0
        val success = FakeHttpsConnection(200)
        fun newProcess() = DeviceRegistration(
            context = context,
            openConnection = {
                when (attempt++) {
                    0 -> throw SocketTimeoutException("offline")
                    1 -> throw IOException("endpoint unavailable")
                    2 -> throw IllegalStateException("bad platform state")
                    else -> success
                }
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            deviceInfo = { info },
        )

        repeat(3) {
            assertFalse(newProcess().registerIfNeeded())
            assertFalse(registered(context))
        }
        assertTrue(newProcess().registerIfNeeded())
        assertTrue(registered(context))
        assertEquals(4, attempt)
    }

    @Test
    fun missingIdentityOrOversizedPayloadNeverOpensAConnection() = runTest {
        val context = context()
        var opened = false
        fun client(details: DeviceRegistrationInfo?) = DeviceRegistration(
            context = context,
            openConnection = { opened = true; FakeHttpsConnection(200) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            deviceInfo = { details },
        )

        assertFalse(client(null).registerIfNeeded())
        assertFalse(client(info.copy(manufacturer = "x".repeat(DeviceRegistration.MAX_PAYLOAD_BYTES))).registerIfNeeded())
        assertFalse(opened)
        assertFalse(registered(context))
    }

    @Test
    fun nonHttpsConnectionsAreRejectedBeforeSendingSpecs() = runTest {
        val context = context()
        var outputOpened = false
        val plain = object : HttpURLConnection(URL("http://example.invalid/api/register")) {
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
            override fun getOutputStream(): OutputStream {
                outputOpened = true
                return ByteArrayOutputStream()
            }
        }
        val client = DeviceRegistration(
            context = context,
            openConnection = { plain },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            deviceInfo = { info },
        )
        assertFalse(client.registerIfNeeded())
        assertFalse(outputOpened)
        assertFalse(registered(context))
    }

    @Test
    fun blockedSocketTimesOutWithoutRegisteringAndTheNextLaunchCanRetry() = runBlocking {
        val context = context()
        val blocked = FakeHttpsConnection(200, blockOutputUntilDisconnect = true)
        val client = DeviceRegistration(
            context = context,
            openConnection = { blocked },
            ioDispatcher = Dispatchers.IO,
            timeoutMs = 100,
            deviceInfo = { info },
        )

        // The outer deadline is only a CI safety net; the client must return
        // false at its own 100 ms deadline and disconnect the blocked socket.
        assertFalse(withTimeout(2_000) { client.registerIfNeeded() })
        assertTrue(blocked.disconnected)
        assertFalse(registered(context))
        // Wait for the interrupted IO child before checking the late-write guard.
        coroutineContext.job.children.toList().joinAll()
        assertFalse(registered(context))

        val retry = FakeHttpsConnection(200)
        assertTrue(
            DeviceRegistration(
                context = context,
                openConnection = { retry },
                ioDispatcher = Dispatchers.IO,
                deviceInfo = { info },
            ).registerIfNeeded()
        )
        assertTrue(registered(context))
    }

    @Test
    fun collectorReadsAndroidIdBuildSpecsAndInstalledPackageVersion() {
        val context = context()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "collector-device")
        val collected = DeviceRegistration.collectDeviceInfo(context)
        requireNotNull(collected)
        assertEquals("collector-device", collected.deviceId)
        assertEquals(expectedBuildField(Build.MANUFACTURER), collected.manufacturer)
        assertEquals(expectedBuildField(Build.MODEL), collected.model)
        assertEquals(expectedBuildField(Build.VERSION.RELEASE), collected.androidVersion)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(expectedBuildField(packageInfo.versionName), collected.appVersionName)
        assertEquals(PackageInfoCompat.getLongVersionCode(packageInfo), collected.appVersionCode)

        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, null)
        assertNull(DeviceRegistration.collectDeviceInfo(context))
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "   ")
        assertNull(DeviceRegistration.collectDeviceInfo(context))
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun registered(context: Context): Boolean = context
        .getSharedPreferences(DeviceRegistration.PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getBoolean(DeviceRegistration.KEY_REGISTERED, false)

    private fun expectedBuildField(value: String?): String =
        value?.takeIf { it.isNotBlank() } ?: "unknown"

    private class FakeHttpsConnection(
        private val status: Int,
        private val blockOutputUntilDisconnect: Boolean = false,
    ) : HttpsURLConnection(URL(DeviceRegistration.REGISTER_ENDPOINT)) {
        var disconnected = false
        var outputClosed = false
        var beforeResponse: (() -> Unit)? = null
        private val properties = mutableMapOf<String, String>()
        private val outputLatch = CountDownLatch(if (blockOutputUntilDisconnect) 1 else 0)
        private var payload: String? = null

        fun sentPayload(): String = requireNotNull(payload)

        override fun setRequestProperty(key: String, value: String) { properties[key] = value }
        override fun getRequestProperty(key: String): String? = properties[key]
        override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (!outputLatch.await(5, TimeUnit.SECONDS)) throw IOException("test output latch expired")
                super.write(buffer, offset, length)
            }

            override fun close() {
                outputClosed = true
                payload = toString(Charsets.UTF_8.name())
                super.close()
            }
        }
        override fun getResponseCode(): Int {
            beforeResponse?.invoke()
            return status
        }
        override fun getInputStream(): InputStream = throw IOException("registration does not read a body")
        override fun disconnect() {
            disconnected = true
            outputLatch.countDown()
        }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST_TLS"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
