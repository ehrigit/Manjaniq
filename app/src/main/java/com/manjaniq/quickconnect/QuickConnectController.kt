package com.manjaniq.quickconnect

import android.content.Context
import android.util.Log
import com.manjaniq.R
import com.manjaniq.RemoteControlHost
import com.manjaniq.core.AetherService
import com.manjaniq.core.AppPreferences
import com.manjaniq.core.ConfigStore
import com.manjaniq.core.EngineRepository
import com.manjaniq.core.SelectedCore
import com.manjaniq.core.models.EngineState
import com.manjaniq.service.ManjaniqVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class QuickConnectStatus {
    /** No Quick Connect session. The cores are untouched. */
    IDLE,

    /** Core was asked to start; waiting for its SOCKS listener, then the VPN. */
    CONNECTING,

    /** Core is up and the system VPN routes device traffic through it. */
    CONNECTED,

    /** Tearing the session down (VPN first, then the core Quick Connect started). */
    STOPPING,
}

data class QuickConnectUiState(
    val status: QuickConnectStatus = QuickConnectStatus.IDLE,
    /** Core the session is (or was) attached to — drives the neon accent. */
    val core: SelectedCore? = null,
    /** "127.0.0.1:1819"-style endpoint the VPN dials, once known. */
    val proxyEndpoint: String? = null,
    /** Non-null when the last attempt failed; cleared on the next attempt. */
    val error: String? = null,
)

/**
 * Single source of truth between the Quick Connect button and the two engines
 * plus the VPN service. Tapping the button is a toggle:
 *
 *  IDLE       -> start the *currently selected* core (through exactly the
 *                same start paths and guards the manual start buttons use),
 *                wait for its local proxy to answer, then establish
 *                [ManjaniqVpnService] on it.
 *  CONNECTING -> cancel the pending attempt.
 *  CONNECTED  -> tear down the VPN; stop the core only if this flow started
 *                it (a core the user started manually is left running).
 *
 * The manual core screens never reference this object, so manual behavior is
 * untouched. If the core dies or the VPN is revoked mid-session, a watchdog
 * tears the session down instead of leaving traffic pointed at a dead proxy.
 *
 * Known limitation: the RSTA core is a single-destination TLS-spoofing relay,
 * not a general-purpose SOCKS5 proxy. Pointing the VPN at it satisfies the
 * wiring but cannot carry arbitrary device traffic; Aether is the core that
 * makes Quick Connect useful. The Home screen surfaces this note next to the
 * button when RSTA is selected.
 */
object QuickConnectController {

    private const val TAG = "QuickConnect"

    private const val RSTA_START_TIMEOUT_MS = 20_000L
    private const val AETHER_START_TIMEOUT_MS = 150_000L
    private const val VPN_START_TIMEOUT_MS = 10_000L
    private const val POLL_INTERVAL_MS = 150L
    private const val WATCHDOG_INTERVAL_MS = 1_000L
    private const val STOPPED_GRACE_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(QuickConnectUiState())
    val state: StateFlow<QuickConnectUiState> = _state.asStateFlow()

    /** Non-null while a session is established. */
    private var session: Session? = null

    /**
     * Non-null while a start attempt is in flight and before the VPN is up.
     * Lets [stopLocked] tear down a core this flow started even if the user
     * cancels mid-handshake (the wait loops themselves run outside the mutex).
     */
    private var pendingSession: Session? = null

    private data class Session(
        val core: SelectedCore,
        /** True when Quick Connect (not the user) started the core. */
        val startedCore: Boolean,
        val endpoint: String,
    )

    private sealed interface CoreWait {
        data object Ready : CoreWait
        data object Cancelled : CoreWait
        data class Failed(val message: String) : CoreWait
    }

    private sealed interface VpnWait {
        data object Up : VpnWait
        data object Cancelled : VpnWait
        data object Failed : VpnWait
    }

    // -------------------------------------------------------------- intents

    fun connect(context: Context) {
        val app = context.applicationContext
        scope.launch { runConnect(app) }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock { stopLocked(app) }
        }
    }

    /** Toggle handler used by the button; VPN consent is collected by the UI layer. */
    fun toggle(context: Context) {
        when (_state.value.status) {
            QuickConnectStatus.IDLE -> connect(context)
            QuickConnectStatus.CONNECTING,
            QuickConnectStatus.CONNECTED,
            QuickConnectStatus.STOPPING -> disconnect(context)
        }
    }

    // -------------------------------------------------------------- start

    private suspend fun runConnect(app: Context) {
        // Phase 1 (locked): guard, record the pending session, ask the
        // selected core to start through the same paths manual startup uses.
        val core: SelectedCore? = mutex.withLock {
            if (_state.value.status != QuickConnectStatus.IDLE) {
                null
            } else {
                val selected = AppPreferences(app).currentCore()
                _state.value = QuickConnectUiState(
                    status = QuickConnectStatus.CONNECTING,
                    core = selected,
                )

                // Remote-control boundary: identical to the manual start path
                // in ManjaniqApp (stop engines, recheck subscription, surface block).
                val remoteControl = (app as? RemoteControlHost)?.remoteControl
                if (remoteControl != null && !remoteControl.canStartEngine()) {
                    AetherService.requestStop(app)
                    EngineRepository.requestStop(app)
                    remoteControl.refreshSubscription()
                    remoteControl.retry()
                    fail(app.getString(R.string.remote_control_service_blocked))
                    null
                } else {
                    val coreWasRunning = coreIsRunning(selected)
                    pendingSession = Session(selected, startedCore = !coreWasRunning, endpoint = "")

                    when (selected) {
                        SelectedCore.RSTA -> {
                            if (!coreWasRunning) {
                                AetherService.requestStop(app)
                                EngineRepository.requestStart(app)
                            }
                        }
                        SelectedCore.AETHER -> {
                            if (!coreWasRunning) AetherService.requestStart(app)
                        }
                    }
                    selected
                }
            }
        }
        val selectedCore = core ?: return

        // Phase 2 (unlocked): wait for the selectedCore's proxy to answer. The wait
        // loops observe status changes so a concurrent disconnect() cancels.
        val timeout = if (selectedCore == SelectedCore.AETHER) {
            AETHER_START_TIMEOUT_MS
        } else {
            RSTA_START_TIMEOUT_MS
        }
        when (val wait = waitForCoreReady(app, selectedCore, timeout)) {
            CoreWait.Ready -> Unit
            CoreWait.Cancelled -> return@runConnect
            is CoreWait.Failed -> {
                // Leave the selectedCore running exactly as the manual flow would;
                // the VPN simply does not come up.
                pendingSession = null
                fail(wait.message)
                return@runConnect
            }
        }

        // Phase 3 (unlocked): locate the local proxy endpoint.
        val endpoint = resolveProxyEndpoint(app, selectedCore)
        if (endpoint == null) {
            pendingSession = null
            fail(app.getString(R.string.quick_connect_error))
            return@runConnect
        }
        val endpointText = "${endpoint.first}:${endpoint.second}"

        // Phase 4 (unlocked): bring the VPN up. Cancel-aware.
        ManjaniqVpnService.start(app, endpoint.first, endpoint.second)
        when (waitForVpn(app, VPN_START_TIMEOUT_MS)) {
            VpnWait.Up -> Unit
            VpnWait.Cancelled -> return@runConnect // disconnect() handles teardown
            VpnWait.Failed -> {
                val startedByUs = pendingSession?.startedCore == true
                pendingSession = null
                if (startedByUs) stopCore(app, selectedCore)
                fail(app.getString(R.string.quick_connect_error))
                return@runConnect
            }
        }

        // Phase 5 (locked): commit; roll back if cancellation won the race.
        mutex.withLock {
            if (_state.value.status != QuickConnectStatus.CONNECTING) {
                ManjaniqVpnService.stop(app)
                return@withLock
            }
            val startedByUs = pendingSession?.startedCore ?: true
            pendingSession = null
            val established = Session(selectedCore, startedCore = startedByUs, endpoint = endpointText)
            session = established
            _state.value = QuickConnectUiState(
                status = QuickConnectStatus.CONNECTED,
                core = selectedCore,
                proxyEndpoint = endpointText,
            )
            startWatchdog(app, selectedCore)
            Log.i(TAG, "Quick Connect established via $selectedCore at $endpointText")
        }
    }

    // --------------------------------------------------------------- stop

    private suspend fun stopLocked(app: Context) {
        val current = _state.value
        if (current.status == QuickConnectStatus.IDLE) return

        val active = session
        val pending = pendingSession
        session = null
        pendingSession = null
        _state.value = current.copy(status = QuickConnectStatus.STOPPING)

        ManjaniqVpnService.stop(app)

        val startedByUs = active?.startedCore == true || pending?.startedCore == true
        val startedCore = active?.core ?: pending?.core
        if (startedByUs && startedCore != null) {
            stopCore(app, startedCore)
        }

        _state.value = QuickConnectUiState()
        Log.i(TAG, "Quick Connect stopped")
    }

    private fun stopCore(app: Context, core: SelectedCore) {
        when (core) {
            SelectedCore.RSTA -> EngineRepository.requestStop(app)
            SelectedCore.AETHER -> AetherService.requestStop(app)
        }
    }

    private fun fail(message: String) {
        session = null
        _state.value = QuickConnectUiState(status = QuickConnectStatus.IDLE, error = message)
    }

    // --------------------------------------------------------- wait loops

    private suspend fun waitForCoreReady(
        app: Context,
        core: SelectedCore,
        timeoutMs: Long,
    ): CoreWait {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMs
        var sawStarting = false
        while (System.currentTimeMillis() < deadline) {
            if (_state.value.status != QuickConnectStatus.CONNECTING) {
                // Cancelled from the UI while connecting; stopLocked() is
                // performing the teardown.
                return CoreWait.Cancelled
            }
            when (core) {
                SelectedCore.RSTA -> {
                    val ui = EngineRepository.uiState.value
                    when {
                        ui.state == EngineState.RUNNING -> return CoreWait.Ready
                        ui.state == EngineState.ERROR -> {
                            return CoreWait.Failed(
                                ui.lastError ?: app.getString(R.string.quick_connect_error),
                            )
                        }
                        ui.state == EngineState.STARTING -> sawStarting = true
                        ui.lastError != null && !ui.busy && ui.state != EngineState.STARTING -> {
                            // Refused quickly (e.g. remote control blocked the
                            // service); the engine's own error says why.
                            return CoreWait.Failed(ui.lastError)
                        }
                        !sawStarting && ui.state == EngineState.STOPPED &&
                            System.currentTimeMillis() - startedAt > STOPPED_GRACE_MS -> {
                            return CoreWait.Failed(
                                ui.lastError ?: app.getString(R.string.quick_connect_error),
                            )
                        }
                    }
                }
                SelectedCore.AETHER -> {
                    val serviceState = AetherService.state.value
                    when {
                        serviceState == EngineState.RUNNING -> return CoreWait.Ready
                        serviceState == EngineState.ERROR -> {
                            return CoreWait.Failed(
                                AetherService.lastError.value
                                    ?: app.getString(R.string.quick_connect_error),
                            )
                        }
                        serviceState == EngineState.STARTING -> sawStarting = true
                        !sawStarting && serviceState == EngineState.STOPPED &&
                            System.currentTimeMillis() - startedAt > STOPPED_GRACE_MS -> {
                            return CoreWait.Failed(
                                AetherService.lastError.value
                                    ?: app.getString(R.string.quick_connect_error),
                            )
                        }
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return CoreWait.Failed(app.getString(R.string.quick_connect_timeout))
    }

    private suspend fun waitForVpn(app: Context, timeoutMs: Long): VpnWait {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_state.value.status != QuickConnectStatus.CONNECTING) {
                ManjaniqVpnService.stop(app)
                return VpnWait.Cancelled
            }
            when (ManjaniqVpnService.state.value) {
                ManjaniqVpnService.VpnState.CONNECTED -> return VpnWait.Up
                ManjaniqVpnService.VpnState.ERROR -> return VpnWait.Failed
                // A STOPPED reading before the service has processed the START
                // intent means "not yet", not "failed" — keep waiting until the
                // deadline decides.
                else -> delay(POLL_INTERVAL_MS)
            }
        }
        return if (ManjaniqVpnService.state.value == ManjaniqVpnService.VpnState.CONNECTED) {
            VpnWait.Up
        } else {
            VpnWait.Failed
        }
    }

    private fun startWatchdog(app: Context, core: SelectedCore) {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val currentSession = session ?: return@launch
                if (currentSession.core != core) return@launch
                val coreUp = coreIsRunning(core)
                val vpnUp =
                    ManjaniqVpnService.state.value == ManjaniqVpnService.VpnState.CONNECTED
                if (!coreUp || !vpnUp) {
                    Log.w(TAG, "Session lost (coreUp=$coreUp, vpnUp=$vpnUp) — tearing down")
                    ManjaniqVpnService.stop(app)
                    if (currentSession.startedCore && coreIsRunning(core)) {
                        stopCore(app, core)
                    }
                    session = null
                    _state.value = QuickConnectUiState(
                        status = QuickConnectStatus.IDLE,
                        core = core,
                        error = app.getString(R.string.quick_connect_session_lost),
                    )
                    return@launch
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun coreIsRunning(core: SelectedCore): Boolean = when (core) {
        SelectedCore.RSTA -> EngineRepository.uiState.value.state == EngineState.RUNNING
        SelectedCore.AETHER -> AetherService.state.value == EngineState.RUNNING
    }

    /** Host and port of the selected core's local proxy listener. */
    private fun resolveProxyEndpoint(app: Context, core: SelectedCore): Pair<String, Int>? {
        return when (core) {
            SelectedCore.RSTA -> {
                val config = ConfigStore(app).load()
                // 0.0.0.0 / "::" mean "all interfaces", not a dialable host.
                val host = when (config.listenHost.trim()) {
                    "", "0.0.0.0", "::" -> "127.0.0.1"
                    else -> config.listenHost.trim()
                }
                if (config.listenPort in 1..65535) host to config.listenPort else null
            }
            SelectedCore.AETHER -> {
                // AetherService publishes the address it actually bound,
                // e.g. "127.0.0.1:1819".
                val listen = AetherService.listenAddress.value
                val port = listen.substringAfterLast(':', missingDelimiterValue = "")
                    .toIntOrNull() ?: return null
                val host = listen.substringBeforeLast(':', missingDelimiterValue = "")
                    .ifBlank { "127.0.0.1" }
                    .let { if (it == "0.0.0.0" || it == "::") "127.0.0.1" else it }
                if (port in 1..65535) host to port else null
            }
        }
    }

}
