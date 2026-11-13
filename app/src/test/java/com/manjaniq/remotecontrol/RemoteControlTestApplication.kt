package com.manjaniq.remotecontrol

import android.app.Application
import com.manjaniq.RemoteControlHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Host-test composition root. No production test-mode flag or bypass exists. */
internal class RemoteControlTestApplication : Application(), RemoteControlHost {
    val source = ConfigurablePolicySource()
    override val remoteControl: RemoteControlSession by lazy {
        RemoteControlSession(
            source = source,
            scope = CoroutineScope(Dispatchers.Unconfined),
            deviceId = { "host-test-device" },
            versionCode = { 1L },
        )
    }
}

internal class ConfigurablePolicySource : RemotePolicySource {
    var policy: RemotePolicy? = RemotePolicy()
    override suspend fun fetch(deviceId: String): RemotePolicy? = policy
}
