package com.manjaniq.remotecontrol

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Why a *configured* subscription currently forbids app and proxy use.
 *
 * A subscription is only ever enforced when the backend actually returned
 * subscription data. An unavailable endpoint, an HTTP error, a malformed body
 * or a payload without any subscription field is not a restriction: the app
 * then assumes the unconstrained state described in REMOTE_CONTROL.md.
 */
enum class SubscriptionBlockReason {
    /** `subscription_expire_date` is at or before the current device time. */
    EXPIRED,

    /** `subscription_status` is explicitly "Expired". */
    STATUS_EXPIRED,

    /** `traffic_used_gb` reached a positive `traffic_limit_gb`. */
    TRAFFIC_EXHAUSTED,
}

/**
 * Immutable, display-safe subscription view published to the settings screen
 * and to the restriction screen. It carries only the fields the backend
 * reported. No device identifier, query URL, response body or bearer material
 * is retained here, and nothing in this object is persisted.
 */
data class SubscriptionSnapshot(
    /** True when the backend returned at least one subscription parameter. */
    val configured: Boolean = false,
    /** Raw `subscription_status` text, empty when the field was absent. */
    val statusText: String = "",
    /** Human-readable `subscription_expire_date`, empty when absent. */
    val expireText: String = "",
    val trafficUsedGb: Double? = null,
    val trafficLimitGb: Double? = null,
    /** Device time of the check that produced this snapshot. */
    val checkedAtMillis: Long = 0L,
    /** Non-null only while this subscription itself forbids use. */
    val blockedReason: SubscriptionBlockReason? = null,
) {
    /** True when the server reported a usable quota limit for this account. */
    val hasTrafficLimit: Boolean get() = (trafficLimitGb ?: 0.0) > 0.0
}

/**
 * The parsed `/api/subscription` contract. Deliberately separate from every
 * engine configuration model: subscription state never edits a proxy, tunnel,
 * profile, native argument list or user preference.
 *
 * Every field is optional. Missing, null or wrongly-typed fields simply stay
 * absent instead of guessing an operator's intent, which keeps the client on
 * the fail-open side of the contract.
 */
internal data class SubscriptionInfo(
    /** Human-readable expiry, empty when absent or unparseable. */
    val expireDisplay: String = "",
    val expiresAtMillis: Long? = null,
    val trafficLimitGb: Double? = null,
    val trafficUsedGb: Double? = null,
    val status: String = "",
) {
    /**
     * "Configured" means the backend supplied at least one usable value. A key
     * that is present but null, empty or wrongly typed is not data: it must not
     * turn a fail-open client into a restricted one, and the settings section
     * keeps showing "Not configured".
     */
    val isConfigured: Boolean
        get() = expiresAtMillis != null ||
            expireDisplay.isNotEmpty() ||
            trafficLimitGb != null ||
            trafficUsedGb != null ||
            status.isNotEmpty()

    fun toSnapshot(checkedAtMillis: Long, reason: SubscriptionBlockReason?): SubscriptionSnapshot =
        SubscriptionSnapshot(
            configured = isConfigured,
            statusText = status,
            expireText = expireDisplay,
            trafficUsedGb = trafficUsedGb,
            trafficLimitGb = trafficLimitGb,
            checkedAtMillis = checkedAtMillis,
            blockedReason = reason,
        )
}

/** Fetch boundary for `GET /api/subscription`; null means unavailable/invalid. */
internal interface SubscriptionSource {
    suspend fun fetch(deviceId: String): SubscriptionInfo?

    /** Releases a blocking socket when the refresh deadline expires. */
    fun cancelPending() {}
}

/**
 * Strict-but-tolerant parser for the documented JSON contract.
 *
 * The endpoint is served by the same Worker as `/api/check`, so it may return
 * the four fields at the top level or wrapped in a `subscription`/`data`
 * object; all three shapes are accepted. Anything that is not a JSON object is
 * unavailable (null). Individual fields that are null, missing or of the wrong
 * type are treated as absent - never as zero, and never as a block.
 */
internal object SubscriptionParser {
    private val FIELDS = listOf(
        "subscription_expire_date",
        "traffic_limit_gb",
        "traffic_used_gb",
        "subscription_status",
    )

    private const val MAX_TEXT_CHARS = 64

    fun parse(body: String): SubscriptionInfo? = try {
        val root = JSONObject(body)
        val json = selectPayload(root)
        val expireRaw = text(json, "subscription_expire_date")
        val expiresAt = SubscriptionDates.parseExpiryMillis(expireRaw)
        SubscriptionInfo(
            expireDisplay = displayExpiry(expireRaw, expiresAt),
            expiresAtMillis = expiresAt,
            trafficLimitGb = number(json, "traffic_limit_gb"),
            trafficUsedGb = number(json, "traffic_used_gb"),
            status = text(json, "subscription_status"),
        )
    } catch (_: Exception) {
        null
    }

    /** Prefers the object that actually carries subscription fields. */
    private fun selectPayload(json: JSONObject): JSONObject {
        if (hasFields(json)) return json
        for (key in listOf("subscription", "data")) {
            val nested = json.optJSONObject(key) ?: continue
            if (hasFields(nested)) return nested
            val deeper = nested.optJSONObject("subscription")
            if (deeper != null && hasFields(deeper)) return deeper
        }
        return json
    }

    private fun hasFields(json: JSONObject): Boolean = FIELDS.any(json::has)

    private fun text(json: JSONObject, name: String): String = when {
        !json.has(name) || json.isNull(name) -> ""
        else -> (json.opt(name) as? String)?.trim()?.take(MAX_TEXT_CHARS) ?: ""
    }

    /** Accepts JSON numbers and digit/dot strings; rejects negatives and NaN. */
    private fun number(json: JSONObject, name: String): Double? = when {
        !json.has(name) || json.isNull(name) -> null
        else -> when (val value = json.opt(name)) {
            is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            else -> null
        }
    }

    private fun displayExpiry(raw: String, parsedMillis: Long?): String = when {
        raw.isEmpty() -> ""
        // An epoch value is unreadable on its own; render the resolved day.
        parsedMillis != null && raw.all(Char::isDigit) -> SubscriptionDates.displayDate(parsedMillis)
        else -> raw
    }
}

/**
 * Expiry parsing for `subscription_expire_date`.
 *
 * Accepts an ISO-8601 date (`2026-01-31`, valid through the end of that UTC
 * day), an ISO date-time with or without an offset, or an epoch value
 * (10 digits = seconds, 13 digits = milliseconds). Anything else is treated as
 * absent so an unreadable date cannot lock a user out.
 */
internal object SubscriptionDates {
    private const val MAX_SECONDS_DIGITS = 10
    private const val MAX_MILLIS_DIGITS = 13
    /** 1e14 seconds is far beyond any real subscription, and cannot overflow. */
    private const val MAX_EPOCH_MILLIS = 1.0e14

    fun parseExpiryMillis(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.all(Char::isDigit)) {
            val value = text.toLongOrNull() ?: return null
            if (value <= 0L) return null
            return when {
                text.length <= MAX_SECONDS_DIGITS -> value * 1_000L
                text.length <= MAX_MILLIS_DIGITS -> value
                else -> null
            }
        }
        // Epoch fallback for a value with a sign or an embedded separator; a
        // dotted value such as 1780000000.5 is epoch seconds. Out-of-range
        // numbers are rejected instead of overflowing into a bogus date.
        text.toDoubleOrNull()?.let { numeric ->
            if (numeric.isFinite() && numeric > 0.0 && numeric < MAX_EPOCH_MILLIS) {
                return (numeric * 1_000.0).toLong()
            }
        }
        // Only direct members of these concrete classes are used: the Android
        // platform stubs do not expose every inherited java.time.chrono method.
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilli() }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }
        runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?.let { return it.toInstant(ZoneOffset.UTC).toEpochMilli() }
        runCatching { LocalDate.parse(text) }.getOrNull()?.let { day ->
            // A date without a time stays valid through the end of that UTC day.
            val nextDay = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            return nextDay - 1L
        }
        return null
    }

    fun displayDate(epochMillis: Long): String = DateTimeFormatter.ISO_LOCAL_DATE.format(
        Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toLocalDate(),
    )
}

/**
 * The single enforcement rule for subscription data.
 *
 * The caller only reaches this evaluator with a parsed payload, i.e. the
 * backend did answer. Order is deterministic: an explicit "Expired" status
 * first, then the expiry date, then the traffic quota. Every check is
 * independent, so a missing field simply cannot block.
 */
internal object SubscriptionEvaluator {
    private const val STATUS_EXPIRED = "Expired"

    fun evaluate(info: SubscriptionInfo, nowMillis: Long): SubscriptionBlockReason? {
        if (info.status.equals(STATUS_EXPIRED, ignoreCase = true)) {
            return SubscriptionBlockReason.STATUS_EXPIRED
        }
        val expiresAt = info.expiresAtMillis
        if (expiresAt != null && nowMillis >= expiresAt) {
            return SubscriptionBlockReason.EXPIRED
        }
        val limit = info.trafficLimitGb
        val used = info.trafficUsedGb
        if (limit != null && limit > 0.0 && used != null && used >= limit) {
            return SubscriptionBlockReason.TRAFFIC_EXHAUSTED
        }
        return null
    }
}
