package com.manjaniq.remotecontrol

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.manjaniq.ui.theme.ManjaniqTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = RemoteControlTestApplication::class)
class RemoteControlGateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var contentComposed = false
    private val app get() = ApplicationProvider.getApplicationContext<RemoteControlTestApplication>()

    private fun showGate() {
        compose.activity.setContent {
            ManjaniqTheme {
                RemoteControlGate {
                    contentComposed = true
                    Text("Existing app content")
                }
            }
        }
    }

    @Test
    fun unavailableBackendImmediatelyShowsExistingContent() {
        app.source.policy = null
        showGate()
        compose.onNodeWithText("Existing app content").assertIsDisplayed()
        assertTrue(contentComposed)
        assertTrue(app.remoteControl.canStartEngine())
    }

    @Test
    fun restrictionHidesMainUiAndRetryCannotDismissItWithoutAnAllowResponse() {
        app.source.policy = RemotePolicy(isBanned = true)
        showGate()
        compose.onNodeWithTag(RemoteControlTags.RESTRICTED).assertIsDisplayed()
        assertFalse(contentComposed)
        compose.onNodeWithTag(RemoteControlTags.RETRY).performClick()
        compose.onNodeWithTag(RemoteControlTags.RESTRICTED).assertIsDisplayed()
        assertFalse(app.remoteControl.canStartEngine())
        app.source.policy = RemotePolicy()
        compose.onNodeWithTag(RemoteControlTags.RETRY).performClick()
        compose.onNodeWithText("Existing app content").assertIsDisplayed()
    }

    @Test
    fun mandatoryUpdateHidesMainUi() {
        app.source.policy = RemotePolicy(forceUpdate = true, latestVersionCode = 2)
        showGate()
        compose.onNodeWithTag(RemoteControlTags.UPDATE).assertIsDisplayed()
        compose.onNodeWithTag(RemoteControlTags.OPEN_UPDATE).assertIsDisplayed()
        assertFalse(contentComposed)
        assertFalse(app.remoteControl.canStartEngine())
    }

    @Test
    fun wrongPasswordCannotUnlockButCorrectPasswordAndNoticeAckCan() {
        app.source.policy = RemotePolicy(appPassword = "open sesame", announcement = "A global notice")
        showGate()
        compose.onNodeWithTag(RemoteControlTags.PASSWORD_INPUT).performTextInput("wrong")
        compose.onNodeWithTag(RemoteControlTags.PASSWORD_SUBMIT).performClick()
        compose.onNodeWithTag(RemoteControlTags.PASSWORD).assertIsDisplayed()
        assertFalse(contentComposed)
        compose.onNodeWithTag(RemoteControlTags.PASSWORD_INPUT).performTextClearance()
        compose.onNodeWithTag(RemoteControlTags.PASSWORD_INPUT).performTextInput("open sesame")
        compose.onNodeWithTag(RemoteControlTags.PASSWORD_SUBMIT).performClick()
        compose.onNodeWithTag(RemoteControlTags.ANNOUNCEMENT).assertIsDisplayed()
        compose.onNodeWithText("A global notice").assertIsDisplayed()
        assertFalse(contentComposed)
        compose.onNodeWithTag(RemoteControlTags.ANNOUNCEMENT_ACK).performClick()
        compose.onNodeWithText("Existing app content").assertIsDisplayed()
        assertTrue(app.remoteControl.canStartEngine())
    }
}
