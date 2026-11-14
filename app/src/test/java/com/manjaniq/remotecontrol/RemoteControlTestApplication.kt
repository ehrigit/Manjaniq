package com.manjaniq.remotecontrol

import android.app.Application
import com.manjaniq.RemoteControlHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Host-test composition root. No production test-mode flag or bypass exists. */
internal class RemoteControlTestApplication : Application(), RemoteControlHost {
    val source = ConfigurablePolicySource()
    val registration = ConfigurableDeviceRegistrationSource()
    val subscription = ConfigurableSubscriptionSource()
    /**
     * The process-scoped session, created eagerly so a test can set its fakes
     * before the gate composes. Unconfined lets those fakes resolve inline, so
     * the host UI tests start deterministically from a known state.
     */
    override val remoteControl: RemoteControlSession = RemoteControlSession(
        source = source,
        scope = CoroutineScope(Dispatchers.Unconfined),
        deviceId = { "host-test-device" },
        versionCode = { 1L },
        registration = registration,
        subscriptionSource = subscription,
        // Fakes answer immediately; Unconfined keeps the host UI tests
        // deterministic instead of racing a real IO thread.
        ioDispatcher = Dispatchers.Unconfined,
    )
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

/** Host tests never contact the live subscription endpoint unless configured. */
internal class ConfigurableSubscriptionSource : SubscriptionSource {
    var info: SubscriptionInfo? = null
    var calls = 0
    override suspend fun fetch(deviceId: String): SubscriptionInfo? {
        calls++
        return info
    }
}
