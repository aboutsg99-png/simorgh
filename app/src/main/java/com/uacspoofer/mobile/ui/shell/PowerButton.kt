package com.uacspoofer.mobile.ui.shell

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.core.ConnectionState

/**
 * Connect button: big round disc inside concentric rings.
 * Disconnected = neutral disc, accent icon. Connecting = rotating sweep with a head dot
 * and slow pulsing rings. Connected = accent-filled disc with a check shield.
 * All motion is graphicsLayer/draw-phase only, and the infinite animations exist only
 * while connecting or disconnecting (and never with animations off).
 */
@Composable
fun PowerButton(state: ConnectionState, diameter: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    val view = LocalView.current
    val busy = state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING
    val connected = state == ConnectionState.CONNECTED
    val anim = sx.anim
    val fillProgress by animateFloatAsState(
        targetValue = if (connected) 1f else 0f,
        animationSpec = tween(if (anim == AnimLevel.OFF) 0 else 520, easing = FastOutSlowInEasing),
        label = "power-fill",
    )
    val ringColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.ERROR -> sx.bad
            ConnectionState.DISCONNECTING -> sx.warn
            else -> sx.accent
        },
        animationSpec = tween(if (anim == AnimLevel.OFF) 0 else 300),
        label = "power-ring",
    )
    val wasConnected = remember { booleanArrayOf(connected) }
    LaunchedEffect(connected) {
        val transitioned = connected && !wasConnected[0]
        wasConnected[0] = connected
        if (transitioned) {
            runCatching {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
                )
            }
        }
    }
    val outer = diameter * 1.9f
    Box(modifier.size(outer), contentAlignment = Alignment.Center) {
        // Static concentric rings and the dashed status arc (cached geometry, redrawn only on state change).
        val dashed = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 14f)) }
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r0 = diameter.toPx() / 2
            val alphaBase = if (sx.light) 0.10f else 0.13f
            for (i in 1..3) {
                drawCircle(ringColor.copy(alpha = alphaBase / i), r0 + (i * 0.17f) * r0 * 1.6f, c, style = Stroke(1.2.dp.toPx()))
            }
            if (!busy) {
                val ar = r0 * 1.38f
                drawArc(
                    color = ringColor.copy(alpha = if (connected) 0.75f else 0.35f),
                    startAngle = 160f, sweepAngle = 220f, useCenter = false,
                    topLeft = Offset(c.x - ar, c.y - ar), size = Size(ar * 2, ar * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = dashed),
                )
            }
        }
        if (busy && anim != AnimLevel.OFF) {
            BusyRings(diameter, ringColor, reverse = state == ConnectionState.DISCONNECTING, reduced = anim == AnimLevel.REDUCED)
        } else if (busy) {
            // Animations off: a still, clearly partial ring instead of motion.
            Canvas(Modifier.size(diameter * 1.18f)) {
                drawArc(ringColor, -90f, 120f, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        val source = remember { MutableInteractionSource() }
        val discBase = if (sx.light) Color(0xFFFFFFFF) else Color(0xFF1E2A40)
        val discEdge = if (sx.light) Color(0xFFE3E9F1) else Color(0xFF162033)
        val fillA = sx.accent
        val fillB = androidx.compose.ui.graphics.lerp(sx.accent, Color.Black, 0.18f)
        val style = sx.button
        Box(
            Modifier
                .size(diameter)
                .graphicsLayer { shadowElevation = if (style == ButtonStyle.PLAIN) 0f else 18f; shape = CircleShape; clip = true }
                .drawBehind {
                    val r = size.minDimension / 2
                    when (style) {
                        ButtonStyle.PLAIN -> Unit
                        ButtonStyle.RING -> drawCircle(discBase.copy(alpha = 0.35f), r)
                        else -> drawCircle(Brush.radialGradient(listOf(discBase, discEdge), radius = r * 1.1f), r)
                    }
                    if (fillProgress > 0f) {
                        drawCircle(
                            Brush.radialGradient(listOf(fillA, fillB), center = Offset(size.width * 0.4f, size.height * 0.35f), radius = r * 1.3f),
                            r * (0.3f + 0.7f * fillProgress),
                            alpha = fillProgress,
                        )
                    }
                }
                .then(
                    if (style == ButtonStyle.RING || style == ButtonStyle.PLAIN) {
                        Modifier.border(2.dp, ringColor.copy(alpha = if (style == ButtonStyle.PLAIN) 0.25f else 0.9f), CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = when (state) {
                        ConnectionState.CONNECTED -> "قطع اتصال"
                        ConnectionState.CONNECTING -> "لغو اتصال"
                        else -> "اتصال"
                    }
                }
                .clickable(interactionSource = source, indication = null) {
                    runCatching { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            val iconTint = if (fillProgress > 0.5f) sx.onAccent else ringColor
            Icon(
                imageVector = if (connected) Icons.Rounded.GppGood else Icons.Outlined.PowerSettingsNew,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(diameter * 0.38f),
            )
        }
    }
}

@Composable
private fun BusyRings(diameter: Dp, color: Color, reverse: Boolean, reduced: Boolean) {
    val t = rememberInfiniteTransition(label = "power-busy")
    val rotation by t.animateFloat(
        initialValue = 0f, targetValue = if (reverse) -360f else 360f,
        animationSpec = infiniteRepeatable(tween(if (reduced) 2400 else 1400, easing = LinearEasing)),
        label = "sweep",
    )
    val pulse by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val ring = diameter * 1.18f
    // Sweep arc + head dot: geometry fixed, rotated in the layer (draw phase only).
    Canvas(
        Modifier.size(ring).graphicsLayer { rotationZ = rotation },
    ) {
        val stroke = 4.dp.toPx()
        val inset = stroke
        drawArc(
            brush = Brush.sweepGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 0.25f), color)),
            startAngle = 0f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        val r = size.width / 2 - inset
        val a = Math.toRadians(300.0)
        val head = Offset(size.width / 2 + (r * kotlin.math.cos(a)).toFloat(), size.height / 2 + (r * kotlin.math.sin(a)).toFloat())
        drawCircle(color.copy(alpha = 0.35f), stroke * 2.6f, head)
        drawCircle(color, stroke * 1.4f, head)
    }
    if (!reduced) {
        Box(
            Modifier
                .size(diameter * 1.25f)
                .graphicsLayer {
                    val s = 1f + 0.45f * pulse
                    scaleX = s; scaleY = s
                    alpha = (1f - pulse) * 0.55f
                }
                .border(2.dp, color, CircleShape),
        )
    }
}
