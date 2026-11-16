package com.manjaniq

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manjaniq.core.AetherCoreManager
import com.manjaniq.core.AetherIdentityManager
import com.manjaniq.core.AetherPsiphon
import com.manjaniq.core.AetherPsiphonMode
import com.manjaniq.core.AetherSettingsStorage
import com.manjaniq.core.AppLanguage
import com.manjaniq.core.ProfileItem
import com.manjaniq.core.PsiphonServerList
import com.manjaniq.ui.server.ServerAetherActivity
import com.manjaniq.ui.server.psiphonRegionDisplayName
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Device-level checks. None of these claims a live tunnel or an external exit. */
@RunWith(AndroidJUnit4::class)
class PsiphonAndroidTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val guid = "instrumentation-psiphon-country"
    private var scenario: ActivityScenario<ServerAetherActivity>? = null

    @After
    fun cleanUp() {
        scenario?.close()
        scenario = null
        AetherSettingsStorage.removeServer(guid)
        AppLaunch.resetPreferences()
        Locale.setDefault(Locale.ENGLISH)
    }

    private fun packagedRegions(): List<String> {
        val scratch = File(context.cacheDir, "psiphon-choices-${System.nanoTime()}")
        return try {
            val file = PsiphonServerList.entriesFile(context, scratch) { throw AssertionError(it) }
            assertNotNull("the signed APK bootstrap list must be usable on Android", file)
            PsiphonServerList.regions(file!!.readText()).sorted().also {
                assertTrue("expected at least two candidate countries", it.size >= 2)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun prepareProfile(region: String? = null) {
        AetherSettingsStorage.init(context)
        AetherSettingsStorage.encodeServerConfig(guid, ProfileItem.create().apply {
            remarks = "Psiphon device test"
            aetherPsiphon = AetherPsiphon.ONLY.type
            aetherPsiphonMode = AetherPsiphonMode.DIRECT.type
            aetherPsiphonRegion = region
        })
    }

    private fun openEditor() {
        val intent = Intent(context, ServerAetherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("guid", guid)
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("psiphon_country").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openCountryMenu() {
        compose.onNodeWithTag("psiphon_country").performScrollTo()
        compose.onNode(
            hasClickAction() and hasAnyAncestor(hasTestTag("psiphon_country")),
            useUnmergedTree = true,
        ).performClick()
    }

    @Test
    fun countryDropdownSavesReopensAndReturnsToAny() {
        AppLaunch.resetPreferences(language = AppLanguage.EN)
        val countries = packagedRegions().take(2)
        prepareProfile()
        for (selected in countries + listOf("")) {
            openEditor()
            openCountryMenu()
            val label = if (selected.isEmpty()) context.getString(R.string.aether_psiphon_region_any)
                else psiphonRegionDisplayName(selected, Locale.ENGLISH)
            compose.waitUntil(20_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.menu_item_save_config)).performClick()
            compose.waitUntil(20_000) {
                AetherSettingsStorage.decodeServerConfig(guid)?.aetherPsiphonRegion == selected.ifEmpty { null }
            }
            scenario?.close()
            scenario = null
            // Read the saved JSON rather than the in-memory profile cache.
            AetherSettingsStorage.clearInMemoryForTest()
            assertEquals(selected.ifEmpty { null }, AetherSettingsStorage.decodeServerConfig(guid)?.aetherPsiphonRegion)
        }
        openEditor()
        compose.onNodeWithTag("psiphon_country").performScrollTo()
        compose.onNodeWithText(context.getString(R.string.aether_psiphon_region_any)).assertIsDisplayed()
    }

    @Test
    fun standaloneEditorUsesTheAppLanguageForCountries() {
        val country = packagedRegions().first()
        AppLaunch.resetPreferences(language = AppLanguage.FA)
        prepareProfile(country)
        val intent = Intent(context, ServerAetherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("guid", guid)
        scenario = ActivityScenario.launch(intent)
        val localized = psiphonRegionDisplayName(country, Locale("fa"))
        compose.waitUntil(20_000) { compose.onAllNodesWithText(localized).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("psiphon_country").performScrollTo()
        compose.onNodeWithText(localized).assertIsDisplayed()
        openCountryMenu()
        compose.onNodeWithText(AppLaunch.resourcesFor(Locale("fa")).getString(R.string.aether_psiphon_region_any)).assertIsDisplayed()
    }

    @Test
    fun packagedPsiphonHelperActuallyExecutesOnAndroid() {
        val helper = AetherCoreManager.psiphonFile(context)
        assertTrue("Psiphon helper must be packaged and executable", helper.isFile && helper.canExecute())
        val process = ProcessBuilder(helper.absolutePath, "-version").redirectErrorStream(true).start()
        try {
            assertTrue("helper did not exit within 20 seconds", process.waitFor(20, TimeUnit.SECONDS))
            assertEquals("helper -version failed", 0, process.exitValue())
            assertTrue(process.inputStream.bufferedReader().use { it.readText() }.contains("Psiphon Console Client"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun selectedCountriesReachTheRealHelperConfiguration() {
        val scratch = File(context.cacheDir, "psiphon-config-${System.nanoTime()}").apply { mkdirs() }
        val entries = PsiphonServerList.entriesFile(context, scratch) { throw AssertionError(it) }
        assertNotNull(entries)
        val countries = PsiphonServerList.regions(entries!!.readText()).sorted()
        assertTrue(countries.size >= 2)
        val helper = AetherCoreManager.psiphonFile(context)
        AetherBridge.initialize(context)
        assertTrue(AetherBridge.isNativeAvailable)
        try {
            for ((index, country) in (listOf("") + countries.take(2)).withIndex()) {
                val state = File(scratch, "state-$index")
                val profile = ProfileItem.create().apply {
                    aetherPsiphon = AetherPsiphon.ONLY.type
                    aetherPsiphonRegion = country.ifEmpty { null }
                }
                val original = AetherCoreManager.buildArguments(profile, port = 0)
                val args = listOf(
                    "--config", File(scratch, AetherIdentityManager.BASE_FILE).absolutePath,
                ) + AetherCoreManager.runtimeArguments(
                    original, scratch, File(scratch, "bridges"), null, null, state, helper, entries,
                )
                AetherBridge.envReset()
                val environment = AetherCoreManager.runtimeEnvironmentMap(context, scratch, original)
                assertTrue("could not set the Psiphon environment", AetherBridge.envSet(environment).isSuccess)
                val start = AetherBridge.coreStart(args)
                assertTrue("core rejected Psiphon arguments", start.isSuccess)
                val job = start.getOrNull()!!.optLong("job", -1)
                assertTrue(job > 0)
                try {
                    val file = File(state, "aether-psiphon.json")
                    var configuration: JSONObject? = null
                    val deadline = System.currentTimeMillis() + 20_000
                    while (configuration == null && System.currentTimeMillis() < deadline) {
                        configuration = runCatching { JSONObject(file.readText()) }.getOrNull()
                        if (configuration == null) Thread.sleep(100)
                    }
                    assertNotNull("Psiphon client configuration was never produced", configuration)
                    assertEquals(country, configuration!!.optString("EgressRegion", ""))
                    assertTrue("Android DNS overlay must reach Psiphon", configuration.has("DNSResolverAlternateServers"))
                    assertEquals(false, configuration.optBoolean("EmitDiagnosticNetworkParameters", true))
                } finally {
                    assertTrue(AetherBridge.jobCancel(job).isSuccess)
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline && AetherBridge.jobPoll(job).getOrNull()?.optString("state") != "done") {
                        Thread.sleep(50)
                    }
                    assertEquals("done", AetherBridge.jobPoll(job).getOrNull()?.optString("state"))
                    assertTrue(AetherBridge.jobFree(job).isSuccess)
                }
            }
        } finally {
            AetherBridge.envReset()
            scratch.deleteRecursively()
        }
    }
}
