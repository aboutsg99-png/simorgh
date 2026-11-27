package com.uacspoofer.mobile.ui.shell

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.core.ConnectionState

/** Hero palette per theme and state (approved mockup colours). */
private data class HeroColors(val band: Color, val outer: Color, val middle: Color, val inner: Color)

private fun heroColors(light: Boolean, state: ConnectionState): HeroColors = when {
    light && state == ConnectionState.CONNECTED -> HeroColors(Color(0xFFD9E85A), Color(0xFFC7DC3E), Color(0xFFB4D132), Color(0xFF9EC42A))
    light && state == ConnectionState.CONNECTING -> HeroColors(Color(0xFFEEF1DA), Color(0xFFE5EBC4), Color(0xFFDAE3AA), Color(0xFFCDDA8E))
    light && state == ConnectionState.ERROR -> HeroColors(Color(0xFFF2E8E6), Color(0xFFEBDCDA), Color(0xFFE3CDCA), Color(0xFFD9BDB9))
    light -> HeroColors(Color(0xFFEBE9E2), Color(0xFFE2E0D8), Color(0xFFD6D4CB), Color(0xFFC9C7BE))
    state == ConnectionState.CONNECTED -> HeroColors(Color(0xFF1C3D33), Color(0xFF23503F), Color(0xFF2A6550), Color(0xFF327B60))
    state == ConnectionState.CONNECTING -> HeroColors(Color(0xFF1B3A3E), Color(0xFF214A49), Color(0xFF285854), Color(0xFF2F665F))
    state == ConnectionState.ERROR -> HeroColors(Color(0xFF33262A), Color(0xFF3E2C30), Color(0xFF4A3237), Color(0xFF55383D))
    else -> HeroColors(Color(0xFF1B3540), Color(0xFF22404C), Color(0xFF2A4C59), Color(0xFF335866))
}

/**
 * The hero: a flat band with three concentric arcs (lime when connected, grey when not).
 * Colours cross-fade once per state change; while connecting (full animation only) a soft
 * ring ripples out. Everything is read in the draw phase from cached geometry.
 */
@Composable
fun HeroArcs(state: ConnectionState, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    // State colours switch instantly (no cross-fade lag after a tap).
    val c = heroColors(sx.light, state)
    val hero = sx.hero
    val band = c.band
    val outer = c.outer
    val middle = c.middle
    val inner = c.inner
    val ripple: State<Float> = if (state == ConnectionState.CONNECTING && sx.anim == AnimLevel.FULL) {
        rememberInfiniteTransition(label = "hero-ripple")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "r")
    } else {
        remember { mutableStateOf(-1f) }
    }
    val rippleColor = if (sx.light) Color.White else sx.accent
    Box(
        // The arcs are circles centred below the hero; clip them to the hero only. The power
        // button is NOT a child of this layer, so it is never cut.
        modifier.clipToBounds().drawWithCache {
            val w = size.width
            val center = Offset(w / 2f, size.height * 1.109f)
            val r1 = w * 0.80f
            val r2 = w * 0.583f
            val r3 = w * 0.39f
            val thin = 2.dp.toPx()
            onDrawBehind {
                when (hero) {
                    HeroStyle.ARCS -> {
                        drawRect(band)
                        drawCircle(outer, r1, center)
                        drawCircle(middle, r2, center)
                        drawCircle(inner, r3, center)
                    }
                    HeroStyle.SOLID -> drawRect(middle)
                    HeroStyle.MINIMAL -> {
                        drawRect(band)
                        drawCircle(inner, r1, center, style = Stroke(thin))
                        drawCircle(inner, r2, center, style = Stroke(thin))
                        drawCircle(inner, r3, center, style = Stroke(thin))
                    }
                }
                val p = ripple.value
                if (p >= 0f) {
                    drawCircle(rippleColor.copy(alpha = 0.28f * (1f - p)), r3 + (r1 - r3) * p, center, style = Stroke(width = 10.dp.toPx() * (1f - p) + 1f))
                }
            }
        },
    )
}

/**
 * Big white (dark: slate) disc with the power glyph, overlapping the hero's lower edge.
 * Connecting shows a spinner arc around the disc (static arc with animations off).
 * Haptics: a tick on tap, a confirm when the tunnel comes up, a reject on failure.
 */
@Composable
fun PowerButton(state: ConnectionState, diameter: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    val view = LocalView.current
    val busy = state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING
    val iconTarget = when (state) {
        ConnectionState.CONNECTED -> sx.accent
        ConnectionState.ERROR -> sx.bad
        ConnectionState.CONNECTING, ConnectionState.DISCONNECTING -> sx.textDim
        ConnectionState.DISCONNECTED -> if (sx.light) Color(0xFF9AA19F) else Color(0xFFC3CCCF)
    }
    val iconColor = iconTarget // instant: the icon must match the state the moment it changes
    StateHaptics(state, view)
    val style = sx.button
    val connected = state == ConnectionState.CONNECTED
    val baseDisc = if (sx.light) Color.White else Color(0xFF24414C)
    // SOFT: white/slate disc (ExpressVPN). FILLED: accent disc once connected.
    // RING: hollow disc with a state-coloured ring. PLAIN: flat disc, no shadow.
    val disc = when {
        style == ButtonStyle.FILLED && connected -> sx.accent
        style == ButtonStyle.RING -> sx.bgTop
        else -> baseDisc
    }
    val glyph = if (style == ButtonStyle.FILLED && connected) sx.onAccent else iconColor
    val ring = diameter + 14.dp
    val source = remember { MutableInteractionSource() }
    // The whole circle (disc + spinner ring) is the touch target and carries the semantics.
    Box(
        modifier
            .size(ring)
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = when (state) {
                    ConnectionState.CONNECTED -> "قطع اتصال"
                    ConnectionState.CONNECTING -> "لغو اتصال"
                    ConnectionState.DISCONNECTING -> "در حال قطع"
                    else -> "اتصال"
                }
                stateDescription = when (state) {
                    ConnectionState.CONNECTED -> "محافظت شده"
                    ConnectionState.CONNECTING -> "در حال اتصال"
                    ConnectionState.ERROR -> "اتصال برقرار نشد"
                    else -> "محافظت نشده"
                }
            }
            .clickable(interactionSource = source, indication = null) {
                tick(view, HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) BusyRing(ring, if (state == ConnectionState.DISCONNECTING) sx.warn else sx.accent, sx.anim)
        Box(
            Modifier
                .size(diameter)
                .shadow(if (style == ButtonStyle.PLAIN || style == ButtonStyle.RING) 0.dp else 10.dp, CircleShape, clip = true)
                .background(disc)
                .then(if (style == ButtonStyle.RING) Modifier.border(3.dp, iconColor, CircleShape) else Modifier)
                .indication(source, androidx.compose.material3.ripple(bounded = true)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = glyph, modifier = Modifier.size(diameter * 0.46f))
        }
    }
}

@Composable
private fun StateHaptics(state: ConnectionState, view: View) {
    val previous = remember { arrayOf(state) }
    LaunchedEffect(state) {
        val was = previous[0]
        previous[0] = state
        if (was == state) return@LaunchedEffect
        when {
            state == ConnectionState.CONNECTED && was != ConnectionState.CONNECTED ->
                tick(view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
            state == ConnectionState.ERROR && was == ConnectionState.CONNECTING ->
                tick(view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        }
    }
}

private fun tick(view: View, constant: Int) {
    runCatching { view.performHapticFeedback(constant) }
}

@Composable
private fun BusyRing(size: Dp, color: Color, anim: AnimLevel) {
    // OFF: a still, clearly partial ring. Otherwise one rotating layer (no recomposition).
    val rotation: State<Float> = if (anim == AnimLevel.OFF) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "power-busy").animateFloat(
            0f, 360f, infiniteRepeatable(tween(if (anim == AnimLevel.REDUCED) 2200 else 1200, easing = LinearEasing)), label = "spin",
        )
    }
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = rotation.value }) {
        val stroke = 3.dp.toPx()
        drawArc(
            color = color,
            startAngle = -90f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
