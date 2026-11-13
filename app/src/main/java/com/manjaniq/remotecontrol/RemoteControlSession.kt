package com.manjaniq.remotecontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    data object Allowed : RemoteControlState
}

/**
 * One process-scoped startup check, owned by the application composition root.
 * Activity recreation/language changes reuse the same verified session; a new
 * OS process starts from Checking and never loads a cached restriction.
 *
 * All state transitions run on the injected main coroutine scope. The service
 * boundary only reads the resulting StateFlow and never performs network IO.
 */
class RemoteControlSession internal constructor(
    private val source: RemotePolicySource,
    private val scope: CoroutineScope,
    private val deviceId: () -> String?,
    private val versionCode: () -> Long?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val checkTimeoutMs: Long = ManjaniqRemoteControl.CHECK_TIMEOUT_MS,
    private val onAccessBlocked: () -> Unit = {},
) {
    private val _state = MutableStateFlow<RemoteControlState>(RemoteControlState.Checking)
    val state: StateFlow<RemoteControlState> = _state.asStateFlow()

    private var started = false
    private var expectedPasswordDigest: ByteArray? = null
    private var verifiedPasswordDigest: ByteArray? = null

    /** Idempotent: composition/recreation must not repeat prompts or polls. */
    fun start() {
        if (started) return
        started = true
        check()
    }

    /** An operator can remove a restriction without forcing an app restart. */
    fun retry() {
        if (_state.value is RemoteControlState.Checking) return
        check()
    }

    fun canStartEngine(): Boolean = _state.value is RemoteControlState.Allowed

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
            _state.value = RemoteControlState.Allowed
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
                    val request = async(ioDispatcher) { source.fetch(identity) }
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
            _state.value = RemoteControlState.Allowed
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

        _state.value = when {
            policy.isBanned -> RemoteControlState.Restricted
            updateRequired -> RemoteControlState.UpdateRequired(policy.latestVersionCode!!)
            expectedPasswordDigest != null && !alreadyVerified -> RemoteControlState.PasswordRequired()
            else -> announcementState()
        }
        if (_state.value !is RemoteControlState.Allowed) {
            // Use existing public Stop requests only. Native lifecycle, transport
            // protocols and the services' Stop handlers remain untouched.
            try {
                onAccessBlocked()
            } catch (_: Exception) {
                // A stop-request failure must not turn a confirmed restriction
                // into an allow, or crash the startup gate.
            }
        }
    }

    private var announcement: String = ""

    private fun announcementState(): RemoteControlState =
        if (announcement.isEmpty()) RemoteControlState.Allowed
        else RemoteControlState.Announcement(announcement)

    private fun showAnnouncementOrAllow() {
        _state.value = if (announcement.isEmpty()) RemoteControlState.Allowed
        else RemoteControlState.Announcement(announcement)
    }
}
