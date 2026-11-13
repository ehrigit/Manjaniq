package com.manjaniq.remotecontrol

import android.app.Application
import com.manjaniq.RemoteControlHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Host-test composition root. No production test-mode flag or bypass exists. */
internal class RemoteControlTestApplication : Application(), RemoteControlHost {
    val source = ConfigurablePolicySource()
    val registration = ConfigurableDeviceRegistrationSource()
    override val remoteControl: RemoteControlSession by lazy {
        RemoteControlSession(
            source = source,
            scope = CoroutineScope(Dispatchers.Unconfined),
            deviceId = { "host-test-device" },
            versionCode = { 1L },
            registration = registration,
        )
    }
}

internal class ConfigurablePolicySource : RemotePolicySource {
    var policy: RemotePolicy? = RemotePolicy()
    override suspend fun fetch(deviceId: String): RemotePolicy? = policy
}

/** Host tests never contact the live registration endpoint. */
internal class ConfigurableDeviceRegistrationSource : DeviceRegistrationSource {
    var result = false
    var calls = 0
    override suspend fun registerIfNeeded(): Boolean {
        calls++
        return result
    }
}
