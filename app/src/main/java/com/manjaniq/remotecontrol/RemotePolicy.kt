package com.manjaniq.remotecontrol

import org.json.JSONObject

/**
 * The Worker's public, device-specific policy. This is deliberately separate
 * from every engine configuration model: remote control never edits a proxy,
 * tunnel, profile, native argument list, or user preference.
 *
 * The password is kept out of UI state, saved state, preferences and logs.
 */
internal data class RemotePolicy(
    val isBanned: Boolean = false,
    val forceUpdate: Boolean = false,
    val latestVersionCode: Long? = null,
    val appPassword: String = "",
    val announcement: String = "",
)

/**
 * Strict parsing of the documented JSON contract. Missing/null optional
 * fields mean "no restriction". A malformed response is not a restriction:
 * the caller fails open rather than guessing an operator's intent.
 */
internal object RemotePolicyParser {
    fun parse(body: String): RemotePolicy? = try {
        val json = JSONObject(body)
        val banned = boolean(json, "is_banned") ?: return null
        val forceUpdate = boolean(json, "force_update") ?: return null
        val latest = versionCode(json)
        val password = text(json, "app_password") ?: return null
        val announcement = text(json, "announcement") ?: return null
        if (latest == INVALID_VERSION || (forceUpdate && latest == null)) {
            null
        } else {
            RemotePolicy(banned, forceUpdate, latest, password, announcement)
        }
    } catch (_: Exception) {
        null
    }

    private const val INVALID_VERSION = -1L

    private fun boolean(json: JSONObject, name: String): Boolean? = when {
        !json.has(name) || json.isNull(name) -> false
        else -> json.opt(name) as? Boolean
    }

    private fun text(json: JSONObject, name: String): String? = when {
        !json.has(name) || json.isNull(name) -> ""
        else -> json.opt(name) as? String
    }

    private fun versionCode(json: JSONObject): Long? = when {
        !json.has("latest_version_code") || json.isNull("latest_version_code") -> null
        else -> when (val value = json.opt("latest_version_code")) {
            is Int -> value.toLong().takeIf { it >= 0 } ?: INVALID_VERSION
            is Long -> value.takeIf { it >= 0 } ?: INVALID_VERSION
            // Some Worker/KV serializers represent large integer fields as strings.
            is String -> value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.toLongOrNull()?.takeIf { it >= 0 } ?: INVALID_VERSION
            else -> INVALID_VERSION
        }
    }
}
