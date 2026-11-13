package com.manjaniq

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.manjaniq.core.EngineRepository
import com.manjaniq.core.models.EngineState
import com.manjaniq.remotecontrol.DeviceRegistration
import com.manjaniq.remotecontrol.ManjaniqRemoteControl
import com.manjaniq.remotecontrol.RemoteControlSession
import com.manjaniq.service.AetherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Composition-root seam, not a test-mode UI switch. Instrumentation supplies a
 * different application implementing this interface with an offline policy;
 * the shipped manifest always supplies ManjaniqApplication and the real Worker.
 */
interface RemoteControlHost {
    val remoteControl: RemoteControlSession
}

/**
 * Owns the remote-control component for this OS process. Creating the
 * application performs no network work; the launcher gate starts the one
 * bounded check before composing the existing app. No engine is initialized or
 * reconfigured here.
 */
class ManjaniqApplication : Application(), RemoteControlHost {
    override val remoteControl: RemoteControlSession by lazy {
        val app = applicationContext
        RemoteControlSession(
            source = ManjaniqRemoteControl(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            deviceId = {
                try {
                    Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
                } catch (_: Exception) {
                    null
                }
            },
            versionCode = {
                try {
                    PackageInfoCompat.getLongVersionCode(
                        app.packageManager.getPackageInfo(app.packageName, 0)
                    )
                } catch (_: Exception) {
                    null
                }
            },
            onAccessBlocked = { stopActiveEngines(app) },
            registration = DeviceRegistration(app),
        )
    }

    private fun stopActiveEngines(context: Context) {
        // Only stop an engine believed to be active. In particular, do not call
        // startService(ACTION_STOP) against a stopped service from background:
        // Android can reject that, and an outage must not disturb local use.
        if (EngineRepository.uiState.value.state != EngineState.STOPPED) {
            runCatching { EngineRepository.requestStop(context) }
        }
        if (AetherService.state.value != EngineState.STOPPED) {
            runCatching { AetherService.requestStop(context) }
        }
    }
}
