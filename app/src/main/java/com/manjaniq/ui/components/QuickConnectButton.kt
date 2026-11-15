package com.manjaniq.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manjaniq.R
import com.manjaniq.core.SelectedCore
import com.manjaniq.quickconnect.QuickConnectStatus
import com.manjaniq.quickconnect.QuickConnectUiState
import com.manjaniq.ui.TestTags

private val QuickConnectBlack = Color(0xFF0A0A0A)
private val NeonPurple = Color(0xFFA66BFF)
private val NeonBlue = Color(0xFF00D5FF)
private val NeonYellow = Color(0xFFEAFF00)
private val NeonRed = Color(0xFFFF5252)
private val RstaNoteAmber = Color(0xFFD9B25F)

private val CanvasSize = 176.dp
private val ButtonRadius = 46.dp
private val HaloOuterWidth = 12.dp
private val HaloInnerWidth = 5.dp
private val RingWidth = 2.dp
private val GlyphRadius = 11.dp
private val GlyphStroke = 2.6.dp

/**
 * The Quick Connect control: a minimalist circular button with a neon ring
 * that sits between the project title and the core selector on Home.
 *
 * Idle: deep-black body, neon-purple ring and a soft static glow.
 * Active (VPN up via this button): the aura breathes outward, tinted
 * neon-blue for Aether and neon-yellow for RSTA.
 */
@Composable
fun QuickConnectButton(
    state: QuickConnectUiState,
    selectedCore: SelectedCore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionCore = state.core ?: selectedCore
    val isConnecting = state.status == QuickConnectStatus.CONNECTING
    val isConnected = state.status == QuickConnectStatus.CONNECTED
    val isStopping = state.status == QuickConnectStatus.STOPPING
    val isActive = isConnected || isConnecting || isStopping

    val accent = if (isActive) {
        when (sessionCore) {
            SelectedCore.AETHER -> NeonBlue
            SelectedCore.RSTA -> NeonYellow
        }
    } else {
        NeonPurple
    }

    // Gentle, purposeful pulse — only meaningful while the tunnel is active.
    // The draw pass reads pulse.value directly so only drawing is invalidated,
    // not the surrounding composables.
    val transition = rememberInfiniteTransition(label = "quick_connect")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "quick_connect_pulse",
    )

    val coreName = stringResource(
        if (sessionCore == SelectedCore.AETHER) R.string.aether_name else R.string.rsta_name,
    )
    val errorText = state.error
    val statusText: String
    val statusColor: Color
    when {
        errorText != null -> {
            statusText = errorText
            statusColor = NeonRed
        }
        isConnected -> {
            statusText = stringResource(
                R.string.quick_connect_connected_fmt,
                coreName,
                state.proxyEndpoint.orEmpty(),
            )
            statusColor = accent
        }
        isConnecting -> {
            statusText = stringResource(R.string.quick_connect_connecting)
            statusColor = accent
        }
        isStopping -> {
            statusText = stringResource(R.string.quick_connect_stopping)
            statusColor = accent
        }
        else -> {
            statusText = stringResource(R.string.quick_connect_hint_idle)
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    val cd = stringResource(R.string.quick_connect_cd)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(CanvasSize)
                .testTag(TestTags.QUICK_CONNECT_BUTTON)
                .semantics { contentDescription = cd }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(CanvasSize)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension / 2f
                val buttonRadius = ButtonRadius.toPx()

                val pulseValue = pulse.value
                val glow = when {
                    isConnected -> 0.42f + 0.38f * pulseValue
                    isConnecting || isStopping -> 0.48f + 0.24f * pulseValue
                    else -> 0.30f
                }
                val auraScale = if (isActive) 0.93f + 0.13f * pulseValue else 1f

                // Outer aura radiating from the body — this is what "breathes"
                // while the tunnel is up.
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.42f to accent.copy(alpha = glow * 0.42f),
                            0.72f to accent.copy(alpha = glow * 0.15f),
                            1.0f to accent.copy(alpha = 0f),
                        ),
                        center = center,
                        radius = maxRadius * auraScale,
                    ),
                    radius = maxRadius * auraScale,
                    center = center,
                )

                // Neon border glow: wide soft halo + tight halo + crisp ring.
                drawCircle(
                    color = accent.copy(alpha = glow * 0.32f),
                    radius = buttonRadius + HaloOuterWidth.toPx() * 0.5f,
                    center = center,
                    style = Stroke(width = HaloOuterWidth.toPx()),
                )
                drawCircle(
                    color = accent.copy(alpha = glow * 0.55f),
                    radius = buttonRadius + HaloInnerWidth.toPx() * 0.5f,
                    center = center,
                    style = Stroke(width = HaloInnerWidth.toPx()),
                )

                // Deep-black body.
                drawCircle(QuickConnectBlack, radius = buttonRadius, center = center)
                drawCircle(
                    color = accent.copy(alpha = 0.95f),
                    radius = buttonRadius,
                    center = center,
                    style = Stroke(width = RingWidth.toPx()),
                )

                drawPowerGlyph(center = center, radius = GlyphRadius.toPx(), color = accent)
            }
        }

        Text(
            text = stringResource(R.string.quick_connect_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        )
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (sessionCore == SelectedCore.RSTA) {
            Text(
                text = stringResource(R.string.quick_connect_rsta_note),
                color = RstaNoteAmber.copy(alpha = 0.85f),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .wrapContentWidth(),
            )
        }
    }
}

private fun DrawScope.drawPowerGlyph(center: Offset, radius: Float, color: Color) {
    val strokeWidth = GlyphStroke.toPx()
    // Arc with a gap at the top (gap centred on 270°).
    drawArc(
        color = color,
        startAngle = 305f,
        sweepAngle = 290f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    // Vertical stem.
    drawLine(
        color = color,
        start = Offset(center.x, center.y - radius * 1.55f),
        end = Offset(center.x, center.y - radius * 0.15f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}
