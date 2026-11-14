package com.manjaniq

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manjaniq.AppLaunch.waitForTag
import com.manjaniq.core.EngineRepository
import com.manjaniq.core.models.EngineState
import com.manjaniq.remotecontrol.RemoteControlState
import com.manjaniq.remotecontrol.RemoteControlTags
import com.manjaniq.remotecontrol.RemotePolicy
import com.manjaniq.service.EngineService
import com.manjaniq.ui.TestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real launcher/service boundary with a deterministic, test-only policy host. */
@RunWith(AndroidJUnit4::class)
class RemoteControlServiceGuardTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<Activity>? = null
    private val app get() = AppLaunch.context.applicationContext as TestManjaniqApplication

    @After
    fun restoreAccess() {
        scenario?.close()
        scenario = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.source.policy = RemotePolicy()
            app.remoteControl.retry()
        }
        compose.waitUntil(AppLaunch.UI_TIMEOUT_MS) { app.remoteControl.canStartEngine() }
    }

    @Test
    fun confirmedRestrictionHidesRealHomeAndRejectsRstaServiceStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            app.source.policy = RemotePolicy(isBanned = true)
            app.remoteControl.retry()
        }
        scenario = AppLaunch.launch()
        compose.waitForTag(RemoteControlTags.RESTRICTED, AppLaunch.STARTUP_TIMEOUT_MS)
        assertEquals(RemoteControlState.Restricted, app.remoteControl.state.value)
        // The restriction surface is the minimalist "Isolated Node" title.
        assertEquals(
            AppLaunch.context.getString(R.string.remote_control_restricted_title),
            "Isolated Node",
        )
        assertFalse(app.remoteControl.canStartEngine())
        assertEquals(0, compose.onAllNodesWithTag(TestTags.HOME_SCREEN).fetchSemanticsNodes().size)

        val context = AppLaunch.context
        ContextCompat.startForegroundService(
            context,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_START),
        )
        compose.waitUntil(AppLaunch.UI_TIMEOUT_MS) {
            !EngineRepository.uiState.value.busy &&
                EngineRepository.uiState.value.lastError == context.getString(R.string.remote_control_service_blocked)
        }
        assertEquals(EngineState.STOPPED, NativeBridge.getEngineState())
        assertFalse(NativeBridge.isEngineRunning())
    }
}
