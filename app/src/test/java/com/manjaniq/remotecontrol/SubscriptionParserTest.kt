package com.manjaniq.remotecontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The documented `/api/subscription` contract, including every shape the Worker
 * may realistically return. Parsing must never invent data: absent, null or
 * wrongly-typed fields stay absent, and unparseable bodies are unavailable.
 *
 * Robolectric supplies the real org.json implementation: the unit-test stub jar
 * would return null from every getter and hide the contract entirely.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubscriptionParserTest {
    @Test
    fun parsesTheDocumentedTopLevelFields() {
        val info = SubscriptionParser.parse(
            """
            {
              "subscription_expire_date": "2030-01-31",
              "traffic_limit_gb": 50,
              "traffic_used_gb": 12.5,
              "subscription_status": "Active"
            }
            """.trimIndent(),
        )
        assertNotNull(info)
        info!!
        assertTrue(info.isConfigured)
        assertEquals("Active", info.status)
        assertEquals(50.0, info.trafficLimitGb!!, 0.0)
        assertEquals(12.5, info.trafficUsedGb!!, 0.0)
        assertEquals("2030-01-31", info.expireDisplay)
        // A date without a time is valid through the end of that UTC day.
        val endOfDay = Instant.parse("2030-01-31T23:59:59.999Z").toEpochMilli()
        assertEquals(endOfDay, info.expiresAtMillis)
        assertNull(SubscriptionEvaluator.evaluate(info, NOW))
    }

    @Test
    fun acceptsNestedSubscriptionAndDataEnvelopes() {
        for (body in listOf(
            """{"subscription":{"subscription_status":"Active","traffic_limit_gb":5}}""",
            """{"data":{"subscription":{"subscription_status":"Active","traffic_limit_gb":5}}}""",
            """{"data":{"traffic_used_gb":1,"subscription_status":"Active"}}""",
        )) {
            val info = SubscriptionParser.parse(body)
            assertNotNull(body, info)
            assertTrue(body, info!!.isConfigured)
            assertEquals(body, "Active", info.status)
        }
    }

    @Test
    fun missingAndNullFieldsAreAbsentInsteadOfZeroOrABlock() {
        val info = SubscriptionParser.parse(
            """{"traffic_limit_gb":null,"traffic_used_gb":null,"subscription_status":null}""",
        )
        assertNotNull(info)
        // The key is present, so the backend *did* answer with the field; the
        // null value itself stays absent and therefore cannot block.
        assertNull(info!!.trafficLimitGb)
        assertNull(info.trafficUsedGb)
        assertEquals("", info.status)
        assertNull(SubscriptionEvaluator.evaluate(info, NOW))
    }

    @Test
    fun aPayloadWithoutUsableSubscriptionDataIsNotConfiguredAndNotABlock() {
        for (body in listOf(
            "{}",
            """{"is_banned":false}""",
            """{"subscription_status":null,"traffic_limit_gb":null}""",
            """{"subscription_status":"","subscription_expire_date":""}""",
        )) {
            val info = SubscriptionParser.parse(body)
            assertNotNull(body, info)
            assertFalse("$body must not report a configured subscription", info!!.isConfigured)
            assertNull(SubscriptionEvaluator.evaluate(info, NOW))
            assertFalse(info.toSnapshot(NOW, null).configured)
        }
    }

    @Test
    fun aZeroLimitIsConfiguredUnlimitedAndNeverBlocks() {
        val info = SubscriptionParser.parse("""{"traffic_limit_gb":0,"traffic_used_gb":500}""")!!
        assertTrue(info.isConfigured)
        assertFalse(info.toSnapshot(NOW, null).hasTrafficLimit)
        assertNull(SubscriptionEvaluator.evaluate(info, NOW))
    }

    @Test
    fun invalidJsonIsUnavailableRatherThanARestriction() {
        for (body in listOf("", "not JSON", "[1,2,3]", "\"text\"", "null")) {
            assertNull(body, SubscriptionParser.parse(body))
        }
    }

    @Test
    fun numericStringsAndInvalidNumbersAreHandledWithoutGuessing() {
        val strings = SubscriptionParser.parse(
            """{"traffic_limit_gb":"10.5","traffic_used_gb":"3"}""",
        )
        assertEquals(10.5, strings!!.trafficLimitGb!!, 0.0)
        assertEquals(3.0, strings.trafficUsedGb!!, 0.0)

        val invalid = SubscriptionParser.parse(
            """{"traffic_limit_gb":"many","traffic_used_gb":true}""",
        )
        assertNull(invalid!!.trafficLimitGb)
        assertNull(invalid.trafficUsedGb)
    }

    @Test
    fun expiryAcceptsIsoDateTimeOffsetAndEpochForms() {
        val iso = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        assertEquals(iso, SubscriptionDates.parseExpiryMillis("2026-01-01T00:00:00Z"))
        assertEquals(iso, SubscriptionDates.parseExpiryMillis("2026-01-01T00:00:00"))
        assertEquals(iso, SubscriptionDates.parseExpiryMillis("2026-01-01T03:30:00+03:30"))
        assertEquals(iso, SubscriptionDates.parseExpiryMillis((iso / 1000).toString()))
        assertEquals(iso, SubscriptionDates.parseExpiryMillis(iso.toString()))
        assertEquals(
            Instant.parse("2026-01-01T23:59:59.999Z").toEpochMilli(),
            SubscriptionDates.parseExpiryMillis("2026-01-01"),
        )
        for (bad in listOf("", "soon", "0", "-1", "2026-13-45", "99999999999999999")) {
            assertNull("'$bad' must not parse", SubscriptionDates.parseExpiryMillis(bad))
        }
    }

    @Test
    fun expiryEvaluationIsInclusiveOfTheCurrentInstant() {
        val info = SubscriptionParser.parse(
            """{"subscription_expire_date":"2026-06-01T12:00:00Z"}""",
        )!!
        val atExpiry = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli()
        assertEquals(
            SubscriptionBlockReason.EXPIRED,
            SubscriptionEvaluator.evaluate(info, atExpiry),
        )
        assertNull(SubscriptionEvaluator.evaluate(info, atExpiry - 1))
    }

    @Test
    fun explicitExpiredStatusBlocksAndTakesPrecedence() {
        val info = SubscriptionParser.parse(
            """{"subscription_status":"expired","traffic_limit_gb":100,"subscription_expire_date":"2099-01-01"}""",
        )!!
        assertEquals(
            SubscriptionBlockReason.STATUS_EXPIRED,
            SubscriptionEvaluator.evaluate(info, NOW),
        )
    }

    @Test
    fun trafficBlocksAtOrAboveAPositiveLimitOnly() {
        fun reason(used: String, limit: String): SubscriptionBlockReason? =
            SubscriptionEvaluator.evaluate(
                SubscriptionParser.parse("""{"traffic_used_gb":$used,"traffic_limit_gb":$limit}""")!!,
                NOW,
            )

        assertEquals(SubscriptionBlockReason.TRAFFIC_EXHAUSTED, reason("10", "10"))
        assertEquals(SubscriptionBlockReason.TRAFFIC_EXHAUSTED, reason("11.4", "10"))
        assertNull(reason("9.99", "10"))
        // A zero/absent limit means unlimited, never an instant block.
        assertNull(reason("500", "0"))
        assertNull(reason("500", "null"))
    }

    @Test
    fun snapshotsCarryOnlyDisplayFieldsAndTheBlockReason() {
        val info = SubscriptionParser.parse(
            """{"subscription_status":"Active","traffic_limit_gb":"20","traffic_used_gb":"5","subscription_expire_date":"2027-05-05"}""",
        )!!
        val snapshot = info.toSnapshot(NOW, null)
        assertTrue(snapshot.configured)
        assertEquals("Active", snapshot.statusText)
        assertEquals("2027-05-05", snapshot.expireText)
        assertEquals(20.0, snapshot.trafficLimitGb!!, 0.0)
        assertEquals(5.0, snapshot.trafficUsedGb!!, 0.0)
        assertTrue(snapshot.hasTrafficLimit)
        assertNull(snapshot.blockedReason)

        // Defaults are the unconstrained state the UI renders as "Not configured".
        val default = SubscriptionSnapshot()
        assertFalse(default.configured)
        assertFalse(default.hasTrafficLimit)
        assertEquals("", default.expireText)
        assertNull(default.blockedReason)
    }

    private companion object {
        val NOW: Long = Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()
    }
}
