package com.manjaniq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manjaniq.core.AetherCoreManager
import com.manjaniq.core.AetherFmt
import com.manjaniq.core.AetherPsiphon
import com.manjaniq.core.AetherSettingsStorage
import com.manjaniq.core.ProfileItem
import com.manjaniq.core.PsiphonServerList
import com.manjaniq.ui.server.AetherUiState
import com.manjaniq.ui.server.psiphonRegionDisplayName
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.Locale
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PsiphonIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun entry(region: String): String =
        ("127.0.0.1 443 secret cert " + JSONObject().put("region", region).toString())
            .toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun pack(entries: String, keyPair: java.security.KeyPair): Pair<ByteArray, String> {
        val key = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(entries.toByteArray(Charsets.UTF_8))
        }.sign()
        val json = JSONObject().apply {
            put("data", entries)
            put("signingPublicKeyDigest", Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.US_ASCII))
            ))
            put("signature", Base64.getEncoder().encodeToString(signature))
        }.toString()
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return bytes.toByteArray() to key
    }

    @After
    fun cleanRememberedRegions() {
        PsiphonServerList.forgetRemembered()
    }

    @Test
    fun signedEntriesAreVerifiedAndSupplyOnlyRealIsoCountries() {
        val work = Files.createTempDirectory("psiphon-entries").toFile()
        try {
            val assets = work.resolve("assets").apply { mkdirs() }
            val dir = work.resolve("state") // It may not exist when the editor opens.
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val text = listOf(entry("ca"), entry("DE"), entry("ZZ"), "not hex").joinToString("\n")
            val (packed, signingKey) = pack(text, key)
            val source = assets.resolve(PsiphonServerList.PSIPHON_SERVERS_DAT).apply { writeBytes(packed) }
            val extracted = PsiphonServerList.entriesFile(assets, dir, signingKey)
            assertNotNull(extracted)
            assertEquals(setOf("CA", "DE"), PsiphonServerList.regions(extracted!!.readText()))

            var failure = false
            source.writeBytes(byteArrayOf(1, 2, 3))
            val retained = PsiphonServerList.entriesFile(assets, dir, signingKey) { failure = true }
            assertTrue("a corrupt replacement must be reported", failure)
            assertEquals(text, retained!!.readText())
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun builtApkListIsSignedAndProvidesChoicesBeforeAnyConnection() {
        val dir = Files.createTempDirectory("psiphon-bundled").toFile()
        try {
            // CI and local APK builds prepare the asset with fetch_psiphon_servers.py.
            context.assets.open(PsiphonServerList.PSIPHON_SERVERS_DAT).use { assertTrue(it.read() >= 0) }
            val entries = PsiphonServerList.entriesFile(context, dir) { throw AssertionError(it) }
            assertNotNull("the packaged list must be staged and verified", entries)
            assertTrue("the list must offer at least one valid region", PsiphonServerList.regions(entries!!.readText()).isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun selectionPersistsAndPropagatesForEveryDiscoveredRegionAndAny() {
        val dir = Files.createTempDirectory("psiphon-choices").toFile()
        val countries = try {
            val file = PsiphonServerList.entriesFile(context, dir) { throw AssertionError(it) }!!
            PsiphonServerList.regions(file.readText())
        } finally {
            dir.deleteRecursively()
        }
        assertTrue("the signed list must provide multiple candidate countries", countries.size >= 2)
        val blank = ProfileItem.create()
        assertEquals("", AetherUiState.fromProfile(blank).psiphonRegion)
        val guid = "psiphon-country-test"
        AetherSettingsStorage.init(context)
        try {
            for (region in countries) {
                val profile = ProfileItem.create().apply { server = "example.test"; serverPort = "443" }
                AetherUiState.fromProfile(profile).copy(psiphon = AetherPsiphon.ONLY, psiphonRegion = region).applyTo(profile)
                AetherSettingsStorage.encodeServerConfig(guid, profile)
                AetherSettingsStorage.clearInMemoryForTest() // Force the SharedPreferences/deserialization path.
                val reopened = AetherSettingsStorage.decodeServerConfig(guid)!!
                assertEquals(region, AetherUiState.fromProfile(reopened).psiphonRegion)
                assertEquals(region, ProfileItem.fromJsonString(reopened.toJsonString())!!.aetherPsiphonRegion)
                assertEquals(region, AetherFmt.parse(AetherFmt.toUri(reopened))!!.aetherPsiphonRegion)
                val args = AetherCoreManager.buildArguments(reopened, 1819)
                assertEquals(region, args[args.indexOf("--psiphon-region") + 1])

                AetherUiState.fromProfile(reopened).copy(psiphonRegion = "").applyTo(reopened)
                assertNull(reopened.aetherPsiphonRegion)
                assertEquals("", AetherUiState.fromProfile(ProfileItem.fromJsonString(reopened.toJsonString())!!).psiphonRegion)
                assertFalse("Any must not pass a region flag", "--psiphon-region" in AetherCoreManager.buildArguments(reopened, 1819))
            }
        } finally {
            AetherSettingsStorage.removeServer(guid)
        }
    }

    @Test
    fun legacyProfilesAndLearnedRegionsRemainCompatible() {
        val old = ProfileItem.fromJsonString("""{"configType":"AETHER","aetherPsiphon":"only"}""")!!
        assertEquals("", AetherUiState.fromProfile(old).psiphonRegion)
        assertFalse("--psiphon-region" in AetherCoreManager.buildArguments(old, 1819))
        AetherSettingsStorage.init(context)
        PsiphonServerList.remember(listOf("GB", "CA", "GB", "ZZ"))
        AetherSettingsStorage.clearInMemoryForTest()
        assertEquals(setOf("CA", "GB"), PsiphonServerList.remembered())
        assertEquals(listOf("CA", "GB"), PsiphonServerList.regionsOf("[*] psiphon can leave from: CA GB"))
    }

    @Test
    fun regionCodesStayAsciiAcrossLocalesAndAllPsiphonPlacements() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            for (mode in listOf(AetherPsiphon.ONLY, AetherPsiphon.CHAIN, AetherPsiphon.REVERSE)) {
                val profile = ProfileItem.create()
                AetherUiState.fromProfile(profile).copy(psiphon = mode, psiphonRegion = "in").applyTo(profile)
                assertEquals("IN", profile.aetherPsiphonRegion)
                val args = AetherCoreManager.buildArguments(profile, 1819)
                assertEquals("IN", args[args.indexOf("--psiphon-region") + 1])
                assertEquals("IN", AetherUiState.fromProfile(profile).psiphonRegion)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun labelsFollowTheSelectedEnglishOrPersianLocale() {
        val english = psiphonRegionDisplayName("ca", Locale.ENGLISH)
        val persian = psiphonRegionDisplayName("CA", Locale("fa"))
        assertTrue(english.contains("Canada") && english.endsWith("(CA)"))
        assertTrue(persian.endsWith("(CA)"))
        assertTrue("Persian should use a localized country name", persian != english && persian.any { it in '\u0600'..'\u06ff' })
    }

    @Test
    fun runtimeUsesTheHelperAndOnlyAddsTheRequestedBootstrapList() {
        val base = Files.createTempDirectory("psiphon-runtime").toFile()
        try {
            val helper = base.resolve("libpsiphon_tunnel_core.so")
            val entries = base.resolve("entries.txt")
            fun args(input: List<String>) = AetherCoreManager.runtimeArguments(
                input, base, base.resolve("bridges"), null, null, base.resolve("state"), helper, entries,
            )
            val shipped = args(listOf("--psiphon-only", "--psiphon-server-entries", "shipped-list"))
            assertEquals(helper.absolutePath, shipped[shipped.indexOf("--psiphon-bin") + 1])
            assertEquals(entries.absolutePath, shipped[shipped.indexOf("--psiphon-server-entries") + 1])
            assertFalse("disabling the bundled list must stay disabled", "--psiphon-server-entries" in args(listOf("--psiphon-only")))
            val unrelated = listOf("--protocol", "wg", "--bind", "127.0.0.1:1819")
            assertEquals(unrelated, args(unrelated))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun recoverableClientNoticesAreNotCarrierTermination() {
        assertNull(AetherCoreManager.chainEndReasonOf("[-] psiphon notice: server list fetch failed"))
        assertNull(AetherCoreManager.chainEndReasonOf("[-] psiphon lost its tunnel; it is reconnecting"))
        assertEquals("other: psiphon stopped: exit status: 1", AetherCoreManager.chainEndReasonOf("[-] psiphon: other: psiphon stopped: exit status: 1"))
    }
}
