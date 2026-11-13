package com.manjaniq

import android.app.Application
import com.manjaniq.remotecontrol.DeviceRegistrationSource
import com.manjaniq.remotecontrol.RemoteControlSession
import com.manjaniq.remotecontrol.RemotePolicy
import com.manjaniq.remotecontrol.RemotePolicySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Instrumentation-only composition root. The real gate, Activity, services and
 * native engines run unchanged, but tests never depend on a live operator's
 * device ban/password/update policy. This class is not in the shipped APK.
 */
internal class TestManjaniqApplication : Application(), RemoteControlHost {
    val source = DevicePolicySource()
    override val remoteControl: RemoteControlSession by lazy {
        RemoteControlSession(
            source = source,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            deviceId = { "instrumentation-device" },
            versionCode = { 1L },
            registration = OfflineDeviceRegistrationSource(),
        )
    }
}

internal class DevicePolicySource : RemotePolicySource {
    @Volatile var policy: RemotePolicy? = RemotePolicy()
    override suspend fun fetch(deviceId: String): RemotePolicy? = policy
}

/** Instrumentation must not send telemetry to the live Worker. */
internal class OfflineDeviceRegistrationSource : DeviceRegistrationSource {
    override suspend fun registerIfNeeded(): Boolean = false
}
