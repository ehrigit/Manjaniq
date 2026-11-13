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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ManjaniqRemoteControlTest {
    @Test
    fun usesFixedHttpsEndpointEncodedIdentityAndBoundedGetSettings() = runBlocking {
        val connection = FakeHttpsConnection(URL(ManjaniqRemoteControl.CHECK_ENDPOINT), 200, """{"is_banned":false}""")
        val client = ManjaniqRemoteControl { connection }
        assertEquals(RemotePolicy(), client.fetch("a&b /+?"))
        assertEquals(
            "https://manjaniq-hub.manjaniqhub.workers.dev/api/check?device_id=a%26b+%2F%2B%3F",
            connection.url.toString(),
        )
        assertEquals("GET", connection.requestMethod)
        assertEquals(3_000, connection.connectTimeout)
        assertEquals(3_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertTrue(connection.disconnected)
        assertTrue(connection.inputClosed)
    }

    @Test
    fun everyNon200StatusFailsOpenAndDisconnects() = runBlocking {
        for (status in listOf(201, 204, 301, 302, 400, 401, 403, 404, 429, 500, 503)) {
            val connection = FakeHttpsConnection(URL(ManjaniqRemoteControl.CHECK_ENDPOINT), status, """{"is_banned":true}""")
            assertNull("HTTP $status", ManjaniqRemoteControl { connection }.fetch("device"))
            assertTrue("HTTP $status must disconnect", connection.disconnected)
            assertFalse("HTTP $status must not parse a body", connection.inputOpened)
        }
    }

    @Test
    fun transportExceptionsAndInvalidJsonFailOpen() = runBlocking {
        assertNull(ManjaniqRemoteControl { throw IOException("offline") }.fetch("device"))
        assertNull(ManjaniqRemoteControl { throw IllegalStateException("bad platform state") }.fetch("device"))
        val invalid = FakeHttpsConnection(URL(ManjaniqRemoteControl.CHECK_ENDPOINT), 200, "not JSON")
        assertNull(ManjaniqRemoteControl { invalid }.fetch("device"))
        assertTrue(invalid.disconnected)
    }

    @Test
    fun responseBodiesAreBoundedEvenWithoutContentLength() = runBlocking {
        val url = URL(ManjaniqRemoteControl.CHECK_ENDPOINT)
        val tooLarge = FakeHttpsConnection(url, 200, "x".repeat(ManjaniqRemoteControl.MAX_RESPONSE_BYTES + 1))
        assertNull(ManjaniqRemoteControl { tooLarge }.fetch("device"))
        assertTrue(tooLarge.disconnected)
        val advertised = FakeHttpsConnection(
            url, 200, "{}", contentLength = ManjaniqRemoteControl.MAX_RESPONSE_BYTES.toLong() + 1,
        )
        assertNull(ManjaniqRemoteControl { advertised }.fetch("device"))
        assertFalse(advertised.inputOpened)
    }

    @Test
    fun missingIdentityOrNonHttpsConnectionNeverStartsARequest() = runBlocking {
        var opened = false
        val client = ManjaniqRemoteControl { opened = true; throw IOException() }
        assertNull(client.fetch(""))
        assertNull(client.fetch("   "))
        assertFalse(opened)
        val plain = object : HttpURLConnection(URL("http://example.invalid/api/check")) {
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
        }
        assertNull(ManjaniqRemoteControl { plain }.fetch("device"))
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
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
