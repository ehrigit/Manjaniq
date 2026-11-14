package com.manjaniq.remotecontrol

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.manjaniq.ui.TestTags
import com.manjaniq.ui.screens.GeneralSettingsScreen
import com.manjaniq.ui.screens.SubscriptionSection
import com.manjaniq.ui.theme.ManjaniqTheme
import com.manjaniq.core.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The dedicated settings section: it must render "Not configured" cleanly when
 * the server returns nothing, and must show traffic/expiry/status when it does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = RemoteControlTestApplication::class)
class SubscriptionSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun anUnconfiguredSubscriptionShowsNotConfiguredAndNoFakeFigures() {
        var refreshes = 0
        compose.activity.setContent {
            ManjaniqTheme {
                SubscriptionSection(
                    snapshot = SubscriptionSnapshot(),
                    onRefresh = { refreshes++ },
                )
            }
        }
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_NOT_CONFIGURED)
            .assertIsDisplayed()
            .assertTextEquals("Not configured")
        // No status/traffic/expiry rows are invented for an absent payload.
        assertEquals(
            0,
            compose.onAllNodesWithTag(TestTags.SUBSCRIPTION_TRAFFIC_VALUE)
                .fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_REFRESH).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun aConfiguredSubscriptionShowsTrafficExpiryAndStatus() {
        compose.activity.setContent {
            ManjaniqTheme {
                SubscriptionSection(
                    snapshot = SubscriptionSnapshot(
                        configured = true,
                        statusText = "Active",
                        expireText = "2027-05-05",
                        trafficUsedGb = 12.5,
                        trafficLimitGb = 50.0,
                    ),
                    onRefresh = {},
                )
            }
        }
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_STATUS_VALUE).assertTextEquals("Active")
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_TRAFFIC_VALUE).assertTextEquals("12.5 GB of 50 GB")
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_EXPIRES_VALUE).assertTextEquals("2027-05-05")
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_BADGE).assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithTag(TestTags.SUBSCRIPTION_NOT_CONFIGURED)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aTrafficOnlyPayloadRendersUnlimitedRatherThanABlock() {
        compose.activity.setContent {
            ManjaniqTheme {
                SubscriptionSection(
                    snapshot = SubscriptionSnapshot(configured = true, trafficUsedGb = 0.4),
                    onRefresh = {},
                )
            }
        }
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_TRAFFIC_VALUE).assertTextEquals("0.4 GB of Unlimited")
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_STATUS_VALUE).assertTextEquals("Unknown")
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_EXPIRES_VALUE).assertTextEquals("Not configured")
    }

    @Test
    fun theSectionIsAnIsolatedSlotInsideTheRealSettingsScreen() {
        compose.activity.setContent {
            ManjaniqTheme {
                GeneralSettingsScreen(
                    currentLanguage = AppLanguage.EN,
                    onLanguageChange = {},
                    versionName = "0.1.0",
                    subscriptionSection = {
                        SubscriptionSection(
                            snapshot = SubscriptionSnapshot(configured = true, statusText = "Active"),
                            onRefresh = {},
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag(TestTags.SETTINGS_SCREEN).assertIsDisplayed()
        // The screen is scrollable, so presence - not viewport position - is the
        // contract for the row that was inserted into it.
        compose.onNodeWithTag(TestTags.SUBSCRIPTION_SECTION).assertExists()
        // The existing preference controls are untouched by the new section.
        compose.onNodeWithTag(TestTags.LANGUAGE_EN).assertExists()
        compose.onNodeWithTag(TestTags.LANGUAGE_FA).assertExists()
    }
}
