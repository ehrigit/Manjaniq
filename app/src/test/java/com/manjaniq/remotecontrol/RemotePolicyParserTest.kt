package com.manjaniq.remotecontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RemotePolicyParserTest {
    @Test
    fun emptyObjectAndNullOptionalFieldsMeanNoRestrictions() {
        assertEquals(RemotePolicy(), RemotePolicyParser.parse("{}"))
        assertEquals(
            RemotePolicy(),
            RemotePolicyParser.parse(
                """{"is_banned":null,"force_update":null,"latest_version_code":null,"app_password":null,"announcement":null}"""
            ),
        )
    }

    @Test
    fun parsesTheCompleteContractWithoutTrimmingPasswords() {
        val policy = RemotePolicyParser.parse(
            """{"is_banned":true,"force_update":true,"latest_version_code":42,"app_password":" secret ","announcement":"Notice"}"""
        )
        assertEquals(RemotePolicy(true, true, 42L, " secret ", "Notice"), policy)
    }

    @Test
    fun acceptsIntegralVersionCodesIncludingLongAndSerializedValues() {
        assertEquals(9L, RemotePolicyParser.parse("""{"latest_version_code":9}""")?.latestVersionCode)
        assertEquals(
            9_000_000_000L,
            RemotePolicyParser.parse("""{"latest_version_code":9000000000}""")?.latestVersionCode,
        )
        assertEquals(
            Long.MAX_VALUE,
            RemotePolicyParser.parse("""{"latest_version_code":"9223372036854775807"}""")?.latestVersionCode,
        )
    }

    @Test
    fun malformedOrNonObjectJsonFailsOpen() {
        listOf("", "not json", "[]", "null", "{", "{\"is_banned\":true").forEach {
            assertNull(it, RemotePolicyParser.parse(it))
        }
    }

    @Test
    fun wrongFieldTypesFailOpenRatherThanCoercingThem() {
        listOf(
            """{"is_banned":"true"}""",
            """{"is_banned":1}""",
            """{"force_update":"false"}""",
            """{"app_password":123}""",
            """{"announcement":false}""",
            """{"latest_version_code":2.5}""",
            """{"latest_version_code":-1}""",
            """{"latest_version_code":"42x"}""",
            """{"latest_version_code":"9223372036854775808"}""",
            """{"force_update":true}""",
            """{"force_update":true,"latest_version_code":null}""",
        ).forEach { assertNull(it, RemotePolicyParser.parse(it)) }
    }

    @Test
    fun blankAnnouncementIsDistinctFromAnEmptyPassword() {
        val policy = RemotePolicyParser.parse("""{"app_password":" ","announcement":"   "}""")!!
        assertEquals(" ", policy.appPassword)
        assertEquals("   ", policy.announcement)
    }
}
