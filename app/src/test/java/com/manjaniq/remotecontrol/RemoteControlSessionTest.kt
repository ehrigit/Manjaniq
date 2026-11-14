package com.manjaniq.remotecontrol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlSessionTest {
    @Test
    fun startupIsIdempotentAndAllowsAnUnrestrictedDevice() = runTest {
        val source = FakeSource(RemotePolicy())
        val session = session(source)
        assertEquals(RemoteControlState.Checking, session.state.value)
        assertFalse(session.canStartEngine())
        session.start()
        advanceUntilIdle()
        session.start()
        advanceUntilIdle()
        assertEquals(1, source.calls)
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertTrue(session.canStartEngine())
    }

    @Test
    fun backendFailuresTimeoutsAndMissingLocalInputsFailOpen() = runTest {
        for (failure in listOf(IOException("offline"), IllegalStateException("unexpected"))) {
            val source = FakeSource(null, failure = failure)
            val session = session(source)
            session.start()
            advanceUntilIdle()
            assertEquals(RemoteControlState.Allowed, session.state.value)
            assertTrue(session.canStartEngine())
        }
        val slow = FakeSource(RemotePolicy(isBanned = true), delayMs = 10_000)
        val timed = session(slow, timeoutMs = 100)
        timed.start()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(RemoteControlState.Allowed, timed.state.value)
        assertTrue(timed.canStartEngine())
        assertEquals(1, slow.cancellations)
        advanceUntilIdle()

        for (missing in listOf("identity", "version")) {
            val unused = FakeSource(RemotePolicy(isBanned = true))
            val session = session(
                unused,
                identity = if (missing == "identity") null else "device",
                version = if (missing == "version") null else 1L,
            )
            session.start()
            advanceUntilIdle()
            assertEquals(0, unused.calls)
            assertTrue(session.canStartEngine())
        }
    }

    @Test
    fun banTakesPrecedenceAndBlocksEngineStartsUntilAnExplicitSuccessfulRetry() = runTest {
        var stops = 0
        val source = FakeSource(RemotePolicy(isBanned = true, forceUpdate = true, latestVersionCode = 9, appPassword = "x"))
        val session = session(source, onBlocked = { stops++ })
        session.start()
        advanceUntilIdle()
        assertEquals(RemoteControlState.Restricted, session.state.value)
        assertFalse(session.canStartEngine())
        assertEquals(1, stops)
        source.policy = RemotePolicy()
        session.retry()
        advanceUntilIdle()
        assertEquals(2, source.calls)
        assertTrue(session.canStartEngine())
    }

    @Test
    fun updateOnlyBlocksWhenRequiredAndInstalledCodeIsOlder() = runTest {
        val cases = listOf(
            Triple(true, 2L, true),
            Triple(true, 1L, false),
            Triple(true, 0L, false),
            Triple(false, 99L, false),
            Triple(true, 99L, true),
        )
        for ((force, latest, blocked) in cases) {
            val session = session(FakeSource(RemotePolicy(forceUpdate = force, latestVersionCode = latest)))
            session.start()
            advanceUntilIdle()
            assertEquals("force=$force latest=$latest", blocked, !session.canStartEngine())
            if (blocked) assertEquals(RemoteControlState.UpdateRequired(latest), session.state.value)
        }
    }

    @Test
    fun passwordIsExactThenAnnouncementThenAccess() = runTest {
        val source = FakeSource(RemotePolicy(appPassword = " گذرواژه ", announcement = "Maintenance tonight"))
        val session = session(source)
        session.start()
        advanceUntilIdle()
        assertEquals(RemoteControlState.PasswordRequired(), session.state.value)
        assertFalse(session.verifyPassword("گذرواژه"))
        assertEquals(RemoteControlState.PasswordRequired(true), session.state.value)
        assertFalse(session.canStartEngine())
        assertTrue(session.verifyPassword(" گذرواژه "))
        assertEquals(RemoteControlState.Announcement("Maintenance tonight"), session.state.value)
        assertFalse(session.canStartEngine())
        session.acknowledgeAnnouncement()
        assertTrue(session.canStartEngine())
        session.start()
        advanceUntilIdle()
        assertEquals(1, source.calls)
        assertTrue(session.canStartEngine())
    }

    @Test
    fun verifiedPasswordSurvivesSamePolicyButNotPasswordRotationOrOfflineReset() = runTest {
        val source = FakeSource(RemotePolicy(appPassword = "old"))
        val session = session(source)
        session.start()
        advanceUntilIdle()
        assertTrue(session.verifyPassword("old"))
        session.retry()
        advanceUntilIdle()
        assertTrue(session.canStartEngine())
        source.policy = RemotePolicy(appPassword = "new")
        session.retry()
        advanceUntilIdle()
        assertEquals(RemoteControlState.PasswordRequired(), session.state.value)
        assertFalse(session.verifyPassword("old"))
        assertTrue(session.verifyPassword("new"))
        source.policy = null
        session.retry()
        advanceUntilIdle()
        assertTrue(session.canStartEngine())
        source.policy = RemotePolicy(appPassword = "new")
        session.retry()
        advanceUntilIdle()
        assertEquals(RemoteControlState.PasswordRequired(), session.state.value)
    }

    @Test
    fun blankAnnouncementDoesNotGateAndStopFailureCannotCrashOrAllowRestriction() = runTest {
        val blank = session(FakeSource(RemotePolicy(announcement = "  ")))
        blank.start()
        advanceUntilIdle()
        assertTrue(blank.canStartEngine())
        val restricted = session(FakeSource(RemotePolicy(isBanned = true)), onBlocked = { throw IOException() })
        restricted.start()
        advanceUntilIdle()
        assertEquals(RemoteControlState.Restricted, restricted.state.value)
        assertFalse(restricted.canStartEngine())
    }

    @Test
    fun registrationRunsBesideTheGateWithoutDelayingLocalAccess() = runTest {
        val registration = FakeRegistrationSource(delayMs = 10_000)
        val session = session(FakeSource(RemotePolicy()), registration = registration)
        session.start()
        runCurrent()
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertTrue(session.canStartEngine())
        assertEquals(1, registration.calls)
        advanceUntilIdle()
        assertTrue(registration.completed)
    }

    @Test
    fun registrationFailureCannotOverridePolicyAndIsAttemptedOncePerSession() = runTest {
        val registration = FakeRegistrationSource(failure = IOException("registration offline"))
        val source = FakeSource(RemotePolicy(isBanned = true))
        val session = session(source, registration = registration)
        session.start()
        advanceUntilIdle()
        assertEquals(RemoteControlState.Restricted, session.state.value)
        assertFalse(session.canStartEngine())
        session.retry()
        advanceUntilIdle()
        session.start()
        advanceUntilIdle()
        assertEquals(1, registration.calls)
        assertEquals(2, source.calls)
        assertEquals(RemoteControlState.Restricted, session.state.value)
    }

    private fun kotlinx.coroutines.test.TestScope.session(
        source: RemotePolicySource,
        timeoutMs: Long = 7_000,
        identity: String? = "test-device",
        version: Long? = 1L,
        onBlocked: () -> Unit = {},
        registration: DeviceRegistrationSource? = null,
    ) = RemoteControlSession(
        source = source,
        // TestScope itself is the foreground scope: advanceUntilIdle() drains
        // it, and runTest cancels/waits for its children without a standalone
        // Job that would remain active after the check completes.
        scope = this,
        deviceId = { identity },
        versionCode = { version },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        checkTimeoutMs = timeoutMs,
        onAccessBlocked = onBlocked,
        registration = registration,
    )

    private class FakeRegistrationSource(
        private val delayMs: Long = 0,
        private val failure: Exception? = null,
    ) : DeviceRegistrationSource {
        var calls = 0
        var completed = false
        override suspend fun registerIfNeeded(): Boolean {
            calls++
            if (delayMs > 0) delay(delayMs)
            failure?.let { throw it }
            completed = true
            return true
        }
    }

    private class FakeSource(
        var policy: RemotePolicy?,
        val failure: Exception? = null,
        val delayMs: Long = 0,
    ) : RemotePolicySource {
        var calls = 0
        var cancellations = 0
        override suspend fun fetch(deviceId: String): RemotePolicy? {
            calls++
            if (delayMs > 0) delay(delayMs)
            failure?.let { throw it }
            return policy
        }
        override fun cancelPending() { cancellations++ }
    }
}
