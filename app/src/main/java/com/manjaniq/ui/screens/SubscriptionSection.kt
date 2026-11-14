package com.manjaniq.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manjaniq.R
import com.manjaniq.remotecontrol.SubscriptionBlockReason
import com.manjaniq.remotecontrol.SubscriptionSnapshot
import com.manjaniq.ui.TestTags
import java.util.Locale

/**
 * Dedicated, isolated subscription section for the settings screen.
 *
 * It is purely presentational: it renders the process-scoped snapshot the
 * remote-control session publishes and asks for a refresh through the existing
 * recheck path. It never performs IO, never edits engine configuration, and
 * never decides access - enforcement stays in [com.manjaniq.remotecontrol.RemoteControlSession].
 *
 * A snapshot the server never populated (no fields, unreachable endpoint, or a
 * backend that does not implement subscriptions) renders "Not configured",
 * matching the unconstrained state the app then operates in.
 */
@Composable
fun SubscriptionSection(
    snapshot: SubscriptionSnapshot,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(TestTags.SUBSCRIPTION_SECTION),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.subscription_section).uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                )
                if (snapshot.configured) {
                    StatusBadge(snapshot)
                }
            }
            Text(
                text = stringResource(R.string.subscription_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            if (snapshot.configured) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                SubscriptionRow(
                    label = stringResource(R.string.subscription_status),
                    value = snapshot.statusText.ifBlank {
                        stringResource(R.string.subscription_status_unknown)
                    },
                    tag = TestTags.SUBSCRIPTION_STATUS_VALUE,
                )
                SubscriptionRow(
                    label = stringResource(R.string.subscription_traffic),
                    value = trafficText(snapshot),
                    tag = TestTags.SUBSCRIPTION_TRAFFIC_VALUE,
                )
                SubscriptionRow(
                    label = stringResource(R.string.subscription_expires),
                    value = snapshot.expireText.ifBlank {
                        stringResource(R.string.subscription_not_configured)
                    },
                    tag = TestTags.SUBSCRIPTION_EXPIRES_VALUE,
                )
            } else {
                Text(
                    text = stringResource(R.string.subscription_not_configured),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.SUBSCRIPTION_NOT_CONFIGURED),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            TextButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(TestTags.SUBSCRIPTION_REFRESH),
            ) {
                Text(stringResource(R.string.subscription_refresh))
            }
        }
    }
}

/**
 * "1.5 GB of 50 GB", "0 GB of 50 GB", or "0.4 GB of Unlimited" when the server
 * reported usage without a positive limit. Figures are formatted with a locale
 * that always uses '.' as the decimal separator so the digits stay readable in
 * the Persian layout as well.
 */
@Composable
private fun trafficText(snapshot: SubscriptionSnapshot): String {
    val used = formatGb(snapshot.trafficUsedGb ?: 0.0)
    val limit = snapshot.trafficLimitGb
    val limitText = if (snapshot.hasTrafficLimit) {
        formatGb(limit ?: 0.0)
    } else {
        stringResource(R.string.subscription_unlimited)
    }
    return stringResource(R.string.subscription_traffic_value, used, limitText)
}

private fun formatGb(value: Double): String {
    val rounded = String.format(Locale.US, "%.2f", value)
    val trimmed = rounded.trimEnd('0').trimEnd('.')
    return "$trimmed GB"
}

@Composable
private fun StatusBadge(snapshot: SubscriptionSnapshot) {
    val blocked = snapshot.blockedReason != null
    val container = if (blocked) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    }
    val content = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val label = when (snapshot.blockedReason) {
        SubscriptionBlockReason.EXPIRED,
        SubscriptionBlockReason.STATUS_EXPIRED,
        -> stringResource(R.string.subscription_badge_expired)
        SubscriptionBlockReason.TRAFFIC_EXHAUSTED -> stringResource(R.string.subscription_badge_traffic)
        null -> snapshot.statusText.ifBlank {
            if (snapshot.configured) {
                stringResource(R.string.subscription_badge_active)
            } else {
                stringResource(R.string.subscription_status_unknown)
            }
        }
    }
    Badge(containerColor = container, contentColor = content) {
        Text(
            text = label,
            modifier = Modifier.testTag(TestTags.SUBSCRIPTION_BADGE),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SubscriptionRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(
            text = value,
            modifier = Modifier.testTag(tag),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
