package com.manjaniq.remotecontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Subscription enforcement at the session boundary: the gate precedence, the
 * fail-open paths, live re-evaluation with the device clock, and the periodic
 * poll. Every case here is reachable through the real composition root.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionSessionTest {

    /**
     * The monitoring loop runs for the life of the process, so it is never a
     * child of the test scope (which would make `advanceUntilIdle` chase an
     * endless delay chain). By default it sits on real time and therefore never
     * fires during a test; the polling test drives it on the virtual clock.
     */
    private var monitor: CoroutineScope? = null

    @After
    fun stopMonitor() {
        monitor?.cancel()
        monitor = null
    }

    // ---- fail open ------------------------------------------------------------

    @Test
    fun backendWithoutSubscriptionFieldsAllowsUseAndReportsNotConfigured() = runTest {
        for (fetched in listOf(null, SubscriptionInfo())) {
            val session = session(subscription = FakeSubscriptionSource(fetched))
            session.start()
            advanceUntilIdle()
            assertTrue("null/empty must allow", session.canStartEngine())
            assertEquals(RemoteControlState.Allowed, session.state.value)
            val snapshot = session.subscription.value
            assertFalse(snapshot.configured)
            assertEquals("", snapshot.expireText)
            assertNull(snapshot.blockedReason)
        }
    }

    @Test
    fun anUnavailableEndpointKeepsAccessAndRepeatsNoRestriction() = runTest {
        val source = FakeSubscriptionSource(info = null, failure = IOException("offline"))
        val session = session(subscription = source)
        session.start()
        advanceUntilIdle()
        assertEquals(1, source.calls)
        assertTrue(session.canStartEngine())
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertFalse(session.subscription.value.configured)
    }

    @Test
    fun aFailedRefreshFailsOpenInsteadOfKeepingAStaleRestriction() = runTest {
        val source = FakeSubscriptionSource(expired())
        val session = session(subscription = source)
        session.start()
        advanceUntilIdle()
        assertFalse(session.canStartEngine())

        // The endpoint goes dark (DNS/socket/TLS error -> null, not a payload).
        source.info = null
        session.refreshSubscription()
        advanceUntilIdle()
        // Nothing is enforced without data, and the section is honest about it.
        assertFalse(session.subscription.value.configured)
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertTrue(session.canStartEngine())
    }

    // ---- enforcement ----------------------------------------------------------

    @Test
    fun anExpiredSubscriptionBlocksTheEngineAndShowsIsolatedNode() = runTest {
        val session = session(subscription = FakeSubscriptionSource(expired()))
        session.start()
        advanceUntilIdle()
        val state = session.state.value
        assertTrue("expected a subscription block, was $state", state is RemoteControlState.SubscriptionBlocked)
        assertEquals(SubscriptionBlockReason.EXPIRED, (state as RemoteControlState.SubscriptionBlocked).reason)
        assertFalse(session.canStartEngine())
        assertTrue("a confirmed block requests Stop once", stops >= 1)
    }

    @Test
    fun anExplicitlyExpiredStatusBlocksEvenWithAFutureDate() = runTest {
        val session = session(
            subscription = FakeSubscriptionSource(
                info(status = "Expired", expireDate = "2099-01-01"),
            ),
        )
        session.start()
        advanceUntilIdle()
        val state = session.state.value as RemoteControlState.SubscriptionBlocked
        assertEquals(SubscriptionBlockReason.STATUS_EXPIRED, state.reason)
        assertFalse(session.canStartEngine())
    }

    @Test
    fun exhaustedTrafficBlocksButRemainingTrafficAndUnlimitedDoNot() = runTest {
        for ((used, limit, blocked) in listOf(
            Triple(10.0, 10.0, true),
            Triple(10.5, 10.0, true),
            Triple(9.9, 10.0, false),
            Triple(500.0, 0.0, false),
        )) {
            val session = session(
                subscription = FakeSubscriptionSource(info(used = used, limit = limit)),
            )
            session.start()
            advanceUntilIdle()
            assertEquals(
                "used=$used limit=$limit",
                blocked,
                session.state.value is RemoteControlState.SubscriptionBlocked,
            )
            assertEquals("used=$used limit=$limit", !blocked, session.canStartEngine())
        }
    }

    @Test
    fun anOperatorBanKeepsPrecedenceOverASubscriptionBlock() = runTest {
        val session = session(
            source = FakePolicySource(RemotePolicy(isBanned = true)),
            subscription = FakeSubscriptionSource(expired()),
        )
        session.start()
        advanceUntilIdle()
        assertEquals(RemoteControlState.Restricted, session.state.value)
        assertFalse(session.canStartEngine())
    }

    @Test
    fun aSubscriptionIsCheckedEvenWhenThePolicyCheckFailsOpen() = runTest {
        val session = session(
            source = FakePolicySource(null),
            subscription = FakeSubscriptionSource(expired()),
        )
        session.start()
        advanceUntilIdle()
        assertTrue(session.state.value is RemoteControlState.SubscriptionBlocked)
        assertFalse(session.canStartEngine())
    }

    // ---- live re-evaluation ---------------------------------------------------

    @Test
    fun expiryThatFallsDueWhileTheAppIsOpenBlocksTheNextStart() = runTest {
        var clock = EXPIRY - 1_000L
        val session = session(
            subscription = FakeSubscriptionSource(info(expireDate = EXPIRY_ISO, status = "Active")),
            now = { clock },
        )
        session.start()
        advanceUntilIdle()
        // One second before expiry the subscription is still valid.
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertTrue(session.canStartEngine())

        // One second later the same data denies the start, before any poll:
        // both services consult this read-only boundary.
        clock = EXPIRY + 1L
        assertFalse(session.canStartEngine())
        assertEquals(
            "the boundary is read-only; the published state stays as the poll left it",
            RemoteControlState.Allowed,
            session.state.value,
        )

        // The next recheck (or poll) publishes the restriction surface.
        session.retry()
        advanceUntilIdle()
        assertTrue(session.state.value is RemoteControlState.SubscriptionBlocked)
        assertEquals(
            SubscriptionBlockReason.EXPIRED,
            (session.state.value as RemoteControlState.SubscriptionBlocked).reason,
        )
        assertFalse(session.canStartEngine())
    }

    @Test
    fun aRenewedSubscriptionLiftsTheBlockOnTheNextRetry() = runTest {
        val source = FakeSubscriptionSource(expired())
        val session = session(subscription = source)
        session.start()
        advanceUntilIdle()
        assertFalse(session.canStartEngine())

        source.info = active()
        session.retry()
        advanceUntilIdle()
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertTrue(session.canStartEngine())
        assertNull(session.subscription.value.blockedReason)
    }

    @Test
    fun theBlockLiftsWhenTheBackendStopsReportingSubscriptionFields() = runTest {
        val source = FakeSubscriptionSource(expired())
        val session = session(subscription = source)
        session.start()
        advanceUntilIdle()
        assertFalse(session.canStartEngine())

        // Endpoint removed/rolled back: an HTTP 200 with no subscription field.
        source.info = SubscriptionInfo()
        session.refreshSubscription()
        advanceUntilIdle()
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertTrue(session.canStartEngine())
        assertFalse(session.subscription.value.configured)
    }

    // ---- periodic polling -----------------------------------------------------

    @Test
    fun thePollRefreshesOnItsIntervalForTheLifeOfTheProcess() = runTest {
        val source = FakeSubscriptionSource(active())
        val session = session(subscription = source, virtualMonitor = true)
        session.start()
        // Both the startup policy check and the startup subscription fetch.
        advanceTimeBy(1_000)
        assertEquals("startup fetch", 1, source.calls)
        assertEquals(RemoteControlState.Allowed, session.state.value)

        advanceTimeBy(RemoteControlSession.SUBSCRIPTION_POLL_INTERVAL_MS)
        assertEquals("first interval", 2, source.calls)

        source.info = expired()
        advanceTimeBy(RemoteControlSession.SUBSCRIPTION_POLL_INTERVAL_MS)
        assertEquals(3, source.calls)
        assertTrue(session.state.value is RemoteControlState.SubscriptionBlocked)
        assertFalse(session.canStartEngine())
        monitor?.cancel()
    }

    @Test
    fun repeatedPollsOfTheSameBlockDoNotRepeatStopRequests() = runTest {
        val source = FakeSubscriptionSource(expired())
        val session = session(subscription = source, virtualMonitor = true)
        session.start()
        advanceTimeBy(1_000)
        val afterFirstBlock = stops
        assertEquals(1, afterFirstBlock)
        assertTrue(session.state.value is RemoteControlState.SubscriptionBlocked)

        // Same restriction re-observed by the interval poll: no extra Stop.
        advanceTimeBy(RemoteControlSession.SUBSCRIPTION_POLL_INTERVAL_MS)
        assertEquals(2, source.calls)
        assertEquals(afterFirstBlock, stops)
        monitor?.cancel()
    }

    @Test
    fun anInFlightSubscriptionFetchNeverDelaysTheGateAndIsNotDuplicated() = runTest {
        val source = FakeSubscriptionSource(active(), delayMs = 10_000)
        val policy = FakePolicySource(RemotePolicy())
        val session = session(source = policy, subscription = source, timeoutMs = 7_000)
        session.start()
        runCurrent()
        // The policy gate has already allowed access even though the
        // subscription request is still blocked in its socket.
        assertEquals(RemoteControlState.Allowed, session.state.value)
        assertTrue(session.canStartEngine())

        // start() is idempotent per session, so it adds neither a second fetch
        // nor a second poller; an explicit recheck is what re-fetches.
        session.start()
        runCurrent()
        assertEquals(1, source.calls)
        session.retry()
        runCurrent()
        assertEquals(2, source.calls)
        advanceUntilIdle()
    }

    @Test
    fun aSlowSubscriptionEndpointCannotOverrideAConfirmedRestrictionOrAllow() = runTest {
        val slow = FakeSubscriptionSource(active(), delayMs = 10_000)
        val banned = session(
            source = FakePolicySource(RemotePolicy(isBanned = true)),
            subscription = slow,
        )
        banned.start()
        runCurrent()
        assertEquals(RemoteControlState.Restricted, banned.state.value)
        advanceUntilIdle()
        assertEquals(RemoteControlState.Restricted, banned.state.value)
        assertFalse(banned.canStartEngine())
    }

    // ---- helpers --------------------------------------------------------------

    private var stops = 0

    private fun active() = info(used = 1.0, limit = 10.0, expireDate = "2099-12-31", status = "Active")

    private fun expired() = info(expireDate = "2020-01-01", status = "Active")

    private fun info(
        used: Double? = null,
        limit: Double? = null,
        expireDate: String = "",
        status: String = "",
    ) = SubscriptionInfo(
        expireDisplay = expireDate,
        expiresAtMillis = SubscriptionDates.parseExpiryMillis(expireDate),
        trafficLimitGb = limit,
        trafficUsedGb = used,
        status = status,
    )

    private fun TestScope.session(
        source: RemotePolicySource = FakePolicySource(RemotePolicy()),
        subscription: SubscriptionSource? = null,
        timeoutMs: Long = 7_000,
        now: () -> Long = { TEST_NOW },
        virtualMonitor: Boolean = false,
    ): RemoteControlSession {
        stops = 0
        val background = CoroutineScope(
            SupervisorJob() +
                if (virtualMonitor) StandardTestDispatcher(testScheduler) else Dispatchers.Unconfined,
        )
        monitor?.cancel()
        monitor = background
        return RemoteControlSession(
            source = source,
            scope = this,
            monitorScope = background,
            deviceId = { "test-device" },
            versionCode = { 1L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            checkTimeoutMs = timeoutMs,
            onAccessBlocked = { stops++ },
            subscriptionSource = subscription,
            now = now,
        )
    }

    private class FakePolicySource(private val policy: RemotePolicy?) : RemotePolicySource {
        override suspend fun fetch(deviceId: String): RemotePolicy? = policy
    }

    private class FakeSubscriptionSource(
        var info: SubscriptionInfo?,
        val failure: Exception? = null,
        val delayMs: Long = 0,
    ) : SubscriptionSource {
        var calls = 0
        override suspend fun fetch(deviceId: String): SubscriptionInfo? {
            calls++
            if (delayMs > 0) delay(delayMs)
            failure?.let { throw it }
            return info
        }
    }

    private companion object {
        val EXPIRY_ISO: String = "2026-06-01T12:00:00Z"
        val EXPIRY: Long = Instant.parse(EXPIRY_ISO).toEpochMilli()
        val TEST_NOW: Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    }
}
