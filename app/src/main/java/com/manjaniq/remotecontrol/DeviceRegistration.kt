package com.manjaniq.remotecontrol

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * One-time, best-effort installation registration. It is deliberately separate
 * from the policy gate: a registration failure never blocks local VPN use, and
 * a successful registration never grants access by itself.
 */
internal interface DeviceRegistrationSource {
    /** True only when this installation is already registered or HTTP 200 was persisted. */
    suspend fun registerIfNeeded(): Boolean

    /** Releases a blocking socket when the registration deadline expires. */
    fun cancelPending() {}
}

/** Non-sensitive specs only. No IMEI, serial, advertising ID, account, or location. */
internal data class DeviceRegistrationInfo(
    val deviceId: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersionName: String,
    val appVersionCode: Long,
) {
    fun toJson(): String = JSONObject()
        .put("device_id", deviceId)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("android_version", androidVersion)
        .put(
            "app_version",
            JSONObject()
                .put("version_name", appVersionName)
                .put("version_code", appVersionCode),
        )
        .toString()
}

/**
 * Dedicated SharedPreferences guard for the remote-control module. The flag is
 * written only after the fixed HTTPS registration endpoint returns HTTP 200.
 * Transport failures, non-200 responses, missing local identity/version, and
 * timeouts leave it false so the next OS process can retry cleanly.
 */
internal class DeviceRegistration(
    private val context: Context,
    // Internal constructor seam for host tests; production always uses the
    // fixed HTTPS endpoint below and the platform's URL connection factory.
    private val openConnection: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = REGISTRATION_TIMEOUT_MS,
    private val deviceInfo: () -> DeviceRegistrationInfo? = { collectDeviceInfo(context) },
) : DeviceRegistrationSource {
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val inFlight = AtomicBoolean(false)

    override suspend fun registerIfNeeded(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        val request = try {
            // Like the policy check, this Deferred belongs to the caller's
            // application scope, not the timeout's child scope. A blocked
            // socket therefore cannot hold the caller past the deadline.
            CoroutineScope(coroutineContext).async(ioDispatcher) {
                try {
                    performRegistration()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            inFlight.set(false)
            throw cancelled
        } catch (_: Exception) {
            inFlight.set(false)
            return false
        }
        request.invokeOnCompletion { inFlight.set(false) }
        return try {
            withTimeoutOrNull(timeoutMs) { request.await() } ?: false
        } finally {
            if (!request.isCompleted) {
                request.cancel()
                cancelPending()
            }
        }
    }

    override fun cancelPending() {
        try {
            activeConnection.get()?.disconnect()
        } catch (_: Exception) {
            // Best effort: the socket also has connect and read timeouts.
        }
    }

    private suspend fun performRegistration(): Boolean {
        coroutineContext.ensureActive()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_REGISTERED, false)) return true

        val info = deviceInfo() ?: return false
        val payload = info.toJson().toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_PAYLOAD_BYTES) return false

        var connection: HttpURLConnection? = null
        try {
            val url = URL(REGISTER_ENDPOINT)
            connection = openConnection(url)
            if (connection !is HttpsURLConnection || url.protocol != "https") return false
            activeConnection.set(connection)
            connection.requestMethod = "POST"
            connection.connectTimeout = ManjaniqRemoteControl.CONNECT_TIMEOUT_MS
            connection.readTimeout = ManjaniqRemoteControl.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Cache-Control", "no-cache")
            coroutineContext.ensureActive()
            connection.outputStream.use { output ->
                output.write(payload)
                output.flush()
            }
            coroutineContext.ensureActive()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            coroutineContext.ensureActive()
            // Persist only after a confirmed 200. commit() runs on the IO
            // dispatcher and makes the one-time guard survive process death.
            preferences.edit().putBoolean(KEY_REGISTERED, true).commit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not log URLs, payloads, device identifiers, or response bodies.
            false
        } finally {
            connection?.let {
                activeConnection.compareAndSet(it, null)
                try {
                    it.disconnect()
                } catch (_: Exception) {
                    // Cleanup must not discard a confirmed HTTP 200 result.
                }
            }
        }
    }

    companion object {
        const val REGISTER_ENDPOINT = "https://manjaniq-hub.manjaniqhub.workers.dev/api/register"
        internal const val PREFERENCES_NAME = "com.manjaniq.remotecontrol.device_registration"
        internal const val KEY_REGISTERED = "is_device_registered"
        internal const val REGISTRATION_TIMEOUT_MS = 7_000L
        internal const val MAX_PAYLOAD_BYTES = 4 * 1024
        private const val MAX_FIELD_CHARS = 128
        private const val UNKNOWN = "unknown"

        /** Zero runtime permissions: Build fields, ANDROID_ID, and PackageManager only. */
        internal fun collectDeviceInfo(context: Context): DeviceRegistrationInfo? = try {
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() } ?: return null
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            if (versionCode < 0) return null
            DeviceRegistrationInfo(
                deviceId = bounded(deviceId),
                manufacturer = bounded(Build.MANUFACTURER),
                model = bounded(Build.MODEL),
                androidVersion = bounded(Build.VERSION.RELEASE),
                appVersionName = bounded(packageInfo.versionName),
                appVersionCode = versionCode,
            )
        } catch (_: Exception) {
            null
        }

        private fun bounded(value: String?): String {
            val text = value?.takeIf { it.isNotBlank() } ?: return UNKNOWN
            return if (text.length <= MAX_FIELD_CHARS) text else text.substring(0, MAX_FIELD_CHARS)
        }
    }
}
