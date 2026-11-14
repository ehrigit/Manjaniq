package com.manjaniq.remotecontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/** No policy text or password is exposed to Compose or saved instance state. */
sealed interface RemoteControlState {
    data object Checking : RemoteControlState
    data object Restricted : RemoteControlState
    data class UpdateRequired(val latestVersionCode: Long) : RemoteControlState
    data class PasswordRequired(val incorrectAttempt: Boolean = false) : RemoteControlState
    data class Announcement(val message: String) : RemoteControlState

    /**
     * A configured subscription forbids use (expired, explicitly expired, or
     * out of traffic). Unlike [Restricted] this is derived from live data and
     * the device clock, so it lifts by itself once the server reports an
     * extended subscription - no operator action is required.
     */
    data class SubscriptionBlocked(
        val reason: SubscriptionBlockReason,
        val snapshot: SubscriptionSnapshot,
    ) : RemoteControlState

    data object Allowed : RemoteControlState
}

/**
 * One process-scoped startup check, owned by the application composition root.
 * Activity recreation/language changes reuse the same verified session; a new
 * OS process starts from Checking and never loads a cached restriction.
 *
 * All state transitions run on the injected main coroutine scope. The service
 * boundary only reads the resulting StateFlow and never performs network IO.
 *
 * Subscription state lives in the same process-scoped session: it is fetched
 * beside the policy check, re-polled on a fixed interval while the process
 * lives, and re-evaluated against the device clock at every check and at every
 * engine-start attempt. Nothing is persisted, so an unavailable backend can
 * only ever widen access (the documented fail-open contract).
 */
class RemoteControlSession internal constructor(
    private val source: RemotePolicySource,
    private val scope: CoroutineScope,
    private val deviceId: () -> String?,
    private val versionCode: () -> Long?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val checkTimeoutMs: Long = ManjaniqRemoteControl.CHECK_TIMEOUT_MS,
    private val onAccessBlocked: () -> Unit = {},
    private val registration: DeviceRegistrationSource? = null,
    private val subscriptionSource: SubscriptionSource? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Scope for the long-lived monitoring loop only. It defaults to [scope];
     * host tests pass a background scope so the permanent loop cannot keep the
     * test's virtual clock busy.
     */
    private val monitorScope: CoroutineScope = scope,
) {
    private val _state = MutableStateFlow<RemoteControlState>(RemoteControlState.Checking)
    val state: StateFlow<RemoteControlState> = _state.asStateFlow()

    private val _subscription = MutableStateFlow(SubscriptionSnapshot())
    /** Display-safe mirror for the settings section; never a credential. */
    val subscription: StateFlow<SubscriptionSnapshot> = _subscription.asStateFlow()

    /**
     * The last successfully fetched payload, or null when the backend never
     * returned subscription fields. Enforcement reads this instead of the
     * display snapshot, so the rule stays a pure function of data + clock.
     */
    @Volatile
    private var latestSubscription: SubscriptionInfo? = null

    private var started = false
    private var registrationStarted = false
    private val subscriptionPolling = java.util.concurrent.atomic.AtomicBoolean(false)
    private var expectedPasswordDigest: ByteArray? = null
    private var verifiedPasswordDigest: ByteArray? = null
    private var announcement: String = ""

    /** Idempotent: composition/recreation must not repeat prompts or polls. */
    fun start() {
        if (started) return
        started = true
        launchRegistration()
        check()
        // Beside the gate, never in front of it: a subscription fetch cannot
        // delay the startup decision.
        refreshSubscription()
        startSubscriptionPolling()
    }

    /** An operator can remove a restriction without forcing an app restart. */
    fun retry() {
        if (_state.value is RemoteControlState.Checking) return
        refreshSubscription()
        check()
    }

    /**
     * Authorization for both engine services and the Home screen.
     *
     * Deliberately read-only and free of side effects: `EngineService` and
     * `AetherService` call it from their own threads right before a start, so
     * it must never mutate state. Subscription data is evaluated here with the
     * device clock, which means an expiry or a spent quota that fell due while
     * the app was open refuses that start. The published state is updated by the
     * session's own scope, so the restriction surface takes over concurrently.
     */
    fun canStartEngine(): Boolean =
        _state.value is RemoteControlState.Allowed && currentSubscriptionRestriction() == null

    /**
     * Best-effort first-launch telemetry. It runs beside the policy check, is
     * never awaited by the gate, and cannot change access state. The one-time
     * persistence/retry guard lives in DeviceRegistration, not in the UI.
     */
    private fun launchRegistration() {
        val source = registration ?: return
        if (registrationStarted) return
        registrationStarted = true
        scope.launch {
            try {
                source.registerIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Registration is silent by contract; a failure must not cancel
                // the policy check or block local VPN access.
            }
        }
    }

    fun verifyPassword(candidate: String): Boolean {
        if (_state.value !is RemoteControlState.PasswordRequired) return false
        val expected = expectedPasswordDigest ?: return false
        // Hash both UTF-8 values before the constant-time comparison. Neither
        // value is persisted, logged, put in an Intent, or saved with the UI.
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(candidate.toByteArray(Charsets.UTF_8))
        if (!MessageDigest.isEqual(expected, actual)) {
            _state.value = RemoteControlState.PasswordRequired(incorrectAttempt = true)
            return false
        }
        verifiedPasswordDigest = expected.copyOf()
        showAnnouncementOrAllow()
        return true
    }

    fun acknowledgeAnnouncement() {
        if (_state.value is RemoteControlState.Announcement) {
            _state.value = subscriptionStateAfterAccess()
        }
    }

    private fun check() {
        _state.value = RemoteControlState.Checking
        expectedPasswordDigest = null
        scope.launch {
            val policy = try {
                val identity = deviceId().takeUnless { it.isNullOrBlank() }
                val installedVersion = versionCode()
                if (identity == null || installedVersion == null) {
                    null
                } else {
                    // This Deferred belongs to the application scope, not the
                    // timeout's child scope. A blocked DNS/socket call therefore
                    // cannot keep the UI waiting past the startup deadline.
                    val request = async(ioDispatcher) {
                        // Contain transport failures inside the Deferred. An
                        // escaping async child exception would cancel this
                        // launch before await() can translate it to fail-open.
                        try {
                            source.fetch(identity)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    try {
                        withTimeoutOrNull(checkTimeoutMs) { request.await() }
                    } finally {
                        if (!request.isCompleted) {
                            request.cancel()
                            source.cancelPending()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            apply(policy)
        }
    }

    private fun apply(policy: RemotePolicy?) {
        // Fail open includes clearing any in-memory password requirement from
        // an earlier check. Nothing restrictive is written to disk.
        if (policy == null) {
            verifiedPasswordDigest = null
            // A subscription the backend did report is evaluated independently
            // of the policy response, so re-derive it instead of dropping it.
            _state.value = subscriptionStateAfterAccess()
            blockIfRestricted()
            return
        }
        val installedVersion = versionCode()
        val updateRequired = policy.forceUpdate && installedVersion != null &&
            policy.latestVersionCode != null && installedVersion < policy.latestVersionCode
        val password = policy.appPassword
        expectedPasswordDigest = password.takeIf { it.isNotEmpty() }?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
        }
        val alreadyVerified = expectedPasswordDigest != null && verifiedPasswordDigest != null &&
            MessageDigest.isEqual(expectedPasswordDigest, verifiedPasswordDigest)
        // Keep the notice even when the password branch is taken first; it is
        // shown immediately after a successful verification.
        announcement = policy.announcement.takeIf { it.isNotBlank() }.orEmpty()

        // Operator bans and mandatory updates are policy decisions and keep
        // their precedence. A subscription restriction follows them and
        // precedes the password prompt, because it is not user-unlockable.
        val blocked = currentSubscriptionRestriction()
        _state.value = when {
            policy.isBanned -> RemoteControlState.Restricted
            updateRequired -> RemoteControlState.UpdateRequired(policy.latestVersionCode!!)
            blocked != null -> blockedState(blocked)
            expectedPasswordDigest != null && !alreadyVerified -> RemoteControlState.PasswordRequired()
            else -> announcementState()
        }
        blockIfRestricted()
    }

    private fun blockIfRestricted() {
        if (_state.value is RemoteControlState.Allowed) return
        // Use existing public Stop requests only. Native lifecycle, transport
        // protocols and the services' Stop handlers remain untouched.
        try {
            onAccessBlocked()
        } catch (_: Exception) {
            // A stop-request failure must not turn a confirmed restriction
            // into an allow, or crash the startup gate.
        }
    }

    private fun announcementState(): RemoteControlState =
        if (announcement.isEmpty()) RemoteControlState.Allowed
        else RemoteControlState.Announcement(announcement)

    private fun showAnnouncementOrAllow() {
        val blocked = currentSubscriptionRestriction()
        _state.value = when {
            blocked != null -> blockedState(blocked)
            announcement.isEmpty() -> RemoteControlState.Allowed
            else -> RemoteControlState.Announcement(announcement)
        }
    }

    /** State a password/notice transition lands on, honouring the quota. */
    private fun subscriptionStateAfterAccess(): RemoteControlState {
        val blocked = currentSubscriptionRestriction()
        return if (blocked == null) RemoteControlState.Allowed else blockedState(blocked)
    }

    private fun blockedState(reason: SubscriptionBlockReason): RemoteControlState.SubscriptionBlocked {
        val snapshot = _subscription.value
        val current = if (snapshot.blockedReason == reason) {
            snapshot
        } else {
            snapshot.copy(blockedReason = reason).also { _subscription.value = it }
        }
        return RemoteControlState.SubscriptionBlocked(reason, current)
    }

    /**
     * The current subscription restriction, evaluated with the device clock.
     *
     * Enforcement only applies when subscription data is actually present, so
     * `null` means "unconstrained": no data, a payload with no subscription
     * fields, an unreachable endpoint, or an invalid response.
     */
    private fun currentSubscriptionRestriction(): SubscriptionBlockReason? {
        val info = latestSubscription?.takeIf { it.isConfigured } ?: return null
        return SubscriptionEvaluator.evaluate(info, now())
    }

    // ---- subscription refresh -------------------------------------------------

    /**
     * Re-polls the endpoint once for this process. Bounded by
     * [ManjaniqSubscription.REFRESH_TIMEOUT_MS]; every failure is swallowed
     * because an unavailable subscription endpoint must never block local use.
     */
    fun refreshSubscription() {
        val source = subscriptionSource ?: return
        val identity = deviceId()?.takeUnless { it.isNullOrBlank() } ?: return
        scope.launch {
            try {
                val fetched = try {
                    // Own Deferred in the application scope: a blocked socket
                    // cannot hold the caller past the deadline.
                    val request = async(ioDispatcher) {
                        try {
                            source.fetch(identity)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    try {
                        withTimeoutOrNull(ManjaniqSubscription.REFRESH_TIMEOUT_MS) { request.await() }
                    } finally {
                        if (!request.isCompleted) {
                            request.cancel()
                            source.cancelPending()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                applySubscription(fetched, now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Publishing must not crash the application scope.
            }
        }
    }

    /**
     * Stores the newest data and re-applies the gate.
     *
     * Only a payload that actually carries subscription fields is enforced.
     * A failed fetch (null), an invalid body, or a 200 response without any
     * subscription field all mean "not configured": the payload is cleared, the
     * section shows "Not configured" and any subscription restriction lifts, so
     * the user keeps normal app and proxy operation. This is the required
     * fail-open behaviour and the reason nothing here is persisted.
     */
    private fun applySubscription(fetched: SubscriptionInfo?, atMillis: Long) {
        val info = fetched?.takeIf { it.isConfigured }
        latestSubscription = info
        _subscription.value = if (info != null) {
            info.toSnapshot(atMillis, SubscriptionEvaluator.evaluate(info, atMillis))
        } else {
            // Nothing enforceable arrived: publish the unconstrained snapshot so
            // the section shows "Not configured" and access stays open.
            SubscriptionSnapshot(checkedAtMillis = atMillis)
        }
        reapplyGateForSubscription()
    }

    /**
     * Re-runs the gate decision from the state already held. A confirmed ban,
     * update requirement, password requirement or pending notice is never
     * downgraded by a subscription refresh; an expired/over-quota subscription
     * replaces an allow state, and lifting it returns to that state.
     */
    private fun reapplyGateForSubscription() {
        val state = _state.value
        // The startup check owns these states: it re-evaluates the subscription
        // itself when it lands, so a refresh must not race it.
        if (state !is RemoteControlState.Allowed && state !is RemoteControlState.SubscriptionBlocked) {
            return
        }
        val blocked = currentSubscriptionRestriction()
        val next = if (blocked != null) blockedState(blocked) else RemoteControlState.Allowed
        _state.value = next
        // Stop the engines only on the transition into a restriction. A poll
        // that finds the same block again must not repeat Stop requests.
        if (state is RemoteControlState.Allowed && next is RemoteControlState.SubscriptionBlocked) {
            blockIfRestricted()
        }
    }

    /**
     * Fixed-interval monitoring for as long as this OS process lives. It is
     * idempotent (one loop per session) and makes no request when no
     * subscription source is wired, so offline composition roots stay silent.
     */
    private fun startSubscriptionPolling() {
        if (subscriptionSource == null) return
        if (!subscriptionPolling.compareAndSet(false, true)) return
        monitorScope.launch {
            while (true) {
                delay(SUBSCRIPTION_POLL_INTERVAL_MS)
                refreshSubscription()
            }
        }
    }

    internal companion object {
        /**
         * Five minutes: often enough to enforce an expiry shortly after it
         * falls due while the app is in use, cheap enough to remain a single
         * bounded JSON GET on the control plane.
         */
        internal const val SUBSCRIPTION_POLL_INTERVAL_MS = 5 * 60 * 1_000L
    }
}
