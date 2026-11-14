package com.manjaniq.remotecontrol

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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

    /**
     * The session is process-scoped (eagerly built by the host application), so
     * each test states the two inputs it needs before composing the real gate.
     */
    private fun setBackend(policy: RemotePolicy?, subscription: SubscriptionInfo?) {
        app.source.policy = policy
        app.subscription.info = subscription
    }

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
        setBackend(policy = null, subscription = null)
        showGate()
        compose.onNodeWithText("Existing app content").assertIsDisplayed()
        assertTrue(contentComposed)
        assertTrue(app.remoteControl.canStartEngine())
    }

    @Test
    fun restrictionHidesMainUiAndRetryCannotDismissItWithoutAnAllowResponse() {
        setBackend(policy = RemotePolicy(isBanned = true), subscription = null)
        showGate()
        compose.onNodeWithTag(RemoteControlTags.RESTRICTED).assertIsDisplayed()
        // The restriction surface is the minimalist title only.
        compose.onNodeWithTag(RemoteControlTags.RESTRICTED_MESSAGE)
            .assertIsDisplayed()
            .assertTextEquals("Isolated Node")
        assertFalse(contentComposed)
        compose.onNodeWithTag(RemoteControlTags.RETRY).performClick()
        compose.onNodeWithTag(RemoteControlTags.RESTRICTED).assertIsDisplayed()
        assertFalse(app.remoteControl.canStartEngine())
        setBackend(policy = RemotePolicy(), subscription = null)
        compose.onNodeWithTag(RemoteControlTags.RETRY).performClick()
        compose.onNodeWithText("Existing app content").assertIsDisplayed()
    }

    @Test
    fun anExpiredSubscriptionShowsIsolatedNodeAndBlocksTheEngine() {
        setBackend(policy = RemotePolicy(), subscription = SubscriptionInfo(
                expireDisplay = "2020-01-01",
            expiresAtMillis = 1_577_836_800_000L,
            status = "Active",
        ))
        showGate()
        compose.onNodeWithTag(RemoteControlTags.SUBSCRIPTION_RESTRICTED).assertIsDisplayed()
        compose.onNodeWithTag(RemoteControlTags.RESTRICTED_MESSAGE).assertTextEquals("Isolated Node")
        assertFalse(contentComposed)
        assertFalse(app.remoteControl.canStartEngine())

        // A renewal lifts it without restarting the app.
        app.subscription.info = SubscriptionInfo(
                expireDisplay = "2099-01-01",
            expiresAtMillis = 4_070_908_800_000L,
            status = "Active",
        )
        compose.onNodeWithTag(RemoteControlTags.RETRY).performClick()
        compose.onNodeWithText("Existing app content").assertIsDisplayed()
        assertTrue(app.remoteControl.canStartEngine())
    }

    @Test
    fun aBackendWithoutSubscriptionDataComposesTheAppNormally() {
        setBackend(policy = RemotePolicy(), subscription = null)
        showGate()
        compose.onNodeWithText("Existing app content").assertIsDisplayed()
        assertTrue(contentComposed)
        assertTrue(app.remoteControl.canStartEngine())
    }

    @Test
    fun mandatoryUpdateHidesMainUi() {
        setBackend(policy = RemotePolicy(forceUpdate = true, latestVersionCode = 2), subscription = null)
        showGate()
        compose.onNodeWithTag(RemoteControlTags.UPDATE).assertIsDisplayed()
        compose.onNodeWithTag(RemoteControlTags.OPEN_UPDATE).assertIsDisplayed()
        assertFalse(contentComposed)
        assertFalse(app.remoteControl.canStartEngine())
    }

    @Test
    fun wrongPasswordCannotUnlockButCorrectPasswordAndNoticeAckCan() {
        setBackend(
            policy = RemotePolicy(appPassword = "open sesame", announcement = "A global notice"),
            subscription = null,
        )
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
