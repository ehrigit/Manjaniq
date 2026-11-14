package com.manjaniq.remotecontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * Transport contract for the subscription endpoint. It shares the hardened
 * request shape of the policy client (fixed HTTPS URL, encoded identity, no
 * redirects, no cache, bounded body) but must never turn a failure into a
 * restriction.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ManjaniqSubscriptionTest {
    @Test
    fun usesFixedHttpsEndpointEncodedIdentityAndBoundedGetSettings() = runBlocking {
        val body = """{"subscription_status":"Active","traffic_limit_gb":10,"traffic_used_gb":1}"""
        val connection = FakeHttpsConnection(URL(ManjaniqSubscription.SUBSCRIPTION_ENDPOINT), 200, body)
        var requestedUrl: URL? = null
        val client = ManjaniqSubscription { requestedUrl = it; connection }
        val info = client.fetch("a&b /+?")
        assertEquals(
            "https://manjaniq-hub.manjaniqhub.workers.dev/api/subscription?device_id=a%26b+%2F%2B%3F",
            requestedUrl.toString(),
        )
        assertEquals("Active", info!!.status)
        assertEquals("GET", connection.requestMethod)
        assertEquals(3_000, connection.connectTimeout)
        assertEquals(3_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("no-cache", connection.getRequestProperty("Cache-Control"))
        assertTrue(connection.disconnected)
        assertTrue(connection.inputClosed)
    }

    @Test
    fun everyNon200StatusIsUnavailableRatherThanARestriction() = runBlocking {
        for (status in listOf(201, 204, 301, 302, 400, 401, 403, 404, 429, 500, 503)) {
            val connection = FakeHttpsConnection(
                URL(ManjaniqSubscription.SUBSCRIPTION_ENDPOINT),
                status,
                """{"subscription_status":"Expired"}""",
            )
            assertNull("HTTP $status", ManjaniqSubscription { connection }.fetch("device"))
            assertTrue("HTTP $status must disconnect", connection.disconnected)
            assertFalse("HTTP $status must not parse a body", connection.inputOpened)
        }
    }

    @Test
    fun transportFailuresInvalidBodiesAndMissingIdentityFailOpen() = runBlocking {
        assertNull(ManjaniqSubscription { throw IOException("offline") }.fetch("device"))
        assertNull(ManjaniqSubscription { throw IllegalStateException("bad state") }.fetch("device"))
        val invalid = FakeHttpsConnection(
            URL(ManjaniqSubscription.SUBSCRIPTION_ENDPOINT), 200, "not JSON",
        )
        assertNull(ManjaniqSubscription { invalid }.fetch("device"))
        assertTrue(invalid.disconnected)

        var opened = false
        val never = ManjaniqSubscription { opened = true; throw IOException() }
        assertNull(never.fetch(""))
        assertNull(never.fetch("   "))
        assertFalse(opened)

        val plain = object : HttpURLConnection(URL("http://example.invalid/api/subscription")) {
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
        }
        assertNull(ManjaniqSubscription { plain }.fetch("device"))
    }

    @Test
    fun responseBodiesAreBoundedEvenWithoutContentLength() = runBlocking {
        val url = URL(ManjaniqSubscription.SUBSCRIPTION_ENDPOINT)
        val tooLarge = FakeHttpsConnection(url, 200, "x".repeat(ManjaniqSubscription.MAX_RESPONSE_BYTES + 1))
        assertNull(ManjaniqSubscription { tooLarge }.fetch("device"))
        assertTrue(tooLarge.disconnected)

        val advertised = FakeHttpsConnection(
            url, 200, "{}", contentLength = ManjaniqSubscription.MAX_RESPONSE_BYTES.toLong() + 1,
        )
        assertNull(ManjaniqSubscription { advertised }.fetch("device"))
        assertFalse(advertised.inputOpened)
    }

    @Test
    fun anEmptyObjectStillParsesAsNotConfigured() = runBlocking {
        val connection = FakeHttpsConnection(
            URL(ManjaniqSubscription.SUBSCRIPTION_ENDPOINT), 200, "{}",
        )
        val info = ManjaniqSubscription { connection }.fetch("device")
        assertEquals(false, info!!.isConfigured)
        assertNull(SubscriptionEvaluator.evaluate(info, System.currentTimeMillis()))
    }

    private class FakeHttpsConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val contentLength: Long = body.toByteArray(Charsets.UTF_8).size.toLong(),
    ) : HttpsURLConnection(url) {
        var disconnected = false
        var inputOpened = false
        var inputClosed = false
        private val properties = mutableMapOf<String, String>()

        override fun setRequestProperty(key: String, value: String) { properties[key] = value }
        override fun getRequestProperty(key: String): String? = properties[key]
        override fun getResponseCode() = status
        override fun getContentLengthLong() = contentLength
        override fun getInputStream(): InputStream {
            inputOpened = true
            if (status != 200) throw IOException("error status")
            return object : ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) {
                override fun close() {
                    inputClosed = true
                    super.close()
                }
            }
        }
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST_TLS"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
