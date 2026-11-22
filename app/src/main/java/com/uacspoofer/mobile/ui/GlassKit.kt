package com.uacspoofer.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.ui.theme.UacColors

/**
 * Glass layer: a page backdrop with soft colour light behind the content, and
 * frosted translucent surfaces with a light-catching edge on top of it.
 */
internal object Glass {
    val Fill: Brush
        get() = Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x0DFFFFFF)))
    val Edge: Brush
        get() = Brush.linearGradient(
            0f to Color(0x59FFFFFF),
            0.35f to Color(0x14FFFFFF),
            0.7f to Color(0x08FFFFFF),
            1f to Color(0x26FFFFFF),
        )
}

/** Frosted translucent surface with a gradient highlight edge. */
internal fun Modifier.glassSurface(shape: Shape, tint: Color = Color.Transparent): Modifier =
    this
        .then(
            if (UacColors.SurfaceOverride.alpha > 0f) Modifier.background(UacColors.SurfaceOverride, shape)
            else Modifier.background(Glass.Fill, shape),
        )
        .then(if (tint.alpha > 0f) Modifier.background(tint, shape) else Modifier)
        .border(1.dp, Glass.Edge, shape)

/** Page backdrop: theme gradient plus blurred colour light so glass has something to frost. */
@Composable
internal fun GlassBackdrop(
    accent: Color,
    modifier: Modifier = Modifier,
    top: Color = UacColors.BackgroundTop,
    middle: Color = UacColors.BackgroundMiddle,
    bottom: Color = UacColors.BackgroundBottom,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, middle, bottom)))) {
        GlassLights(accent, UacColors.NeonCyan)
        content()
    }
}

@Composable
internal fun GlassLights(primary: Color, secondary: Color) {
    // Radial falloff already reads as blurred light; no RenderEffect, so the
    // backdrop costs one cached draw and never re-renders while scrolling.
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun light(c: Color, x: Float, y: Float, r: Float, a: Float) = drawCircle(
            brush = Brush.radialGradient(
                listOf(c.copy(alpha = a), c.copy(alpha = 0f)),
                center = Offset(x, y),
                radius = r,
            ),
            radius = r,
            center = Offset(x, y),
        )
        light(primary, w * 0.12f, h * 0.18f, w * 0.75f, 0.22f)
        light(secondary, w * 0.92f, h * 0.55f, w * 0.70f, 0.16f)
        light(primary, w * 0.30f, h * 0.98f, w * 0.65f, 0.12f)
    }
}

/** Soft top sheen for glass panels (drawer, sheets). */
internal fun Modifier.glassSheen(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color.Transparent), endY = size.height * 0.25f),
    )
}
