package com.manjaniq.remotecontrol

import android.content.Context
import com.manjaniq.RemoteControlHost

/**
 * Authorization-only boundary for existing service start actions. It performs
 * no IO and does not change Stop, native engine code, or tunnel configuration.
 * A sticky service restarted before the startup check finishes is not retried
 * automatically; the user can start it normally after the gate allows access.
 */
internal object RemoteControlServiceGuard {
    fun allowStart(context: Context): Boolean =
        (context.applicationContext as? RemoteControlHost)
            ?.remoteControl?.canStartEngine() == true
}
