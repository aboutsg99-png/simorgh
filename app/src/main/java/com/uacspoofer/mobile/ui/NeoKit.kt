package com.uacspoofer.mobile.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.UacThemeState
import com.uacspoofer.mobile.ui.theme.NeoBackdrop
import com.uacspoofer.mobile.ui.theme.NeoStyleState
import com.uacspoofer.mobile.ui.theme.PowerButtonStyle
import kotlin.math.roundToInt

/**
 * Neumorphic kit for the Graphite (Classic) and Mist themes.
 *
 * Shadows are drawn by a cached [DrawModifierNode]: native paints with a shadow layer are
 * built once per size or theme change, so nothing is allocated per frame and no
 * RenderEffect blur is used. Neon and Solaris keep their own look; callers check
 * [Neo.active] before switching to these surfaces.
 */
internal object Neo {
    /** True for the neumorphic themes (Graphite and Mist). */
    val active: Boolean get() = !UacThemeState.isNeon
    val light: Boolean get() = UacThemeState.isLight
    val bg: Color get() = UacThemeState.palette.backgroundMiddle
    val hi: Color get() = UacThemeState.palette.neoHi
    val lo: Color get() = UacThemeState.palette.neoLo
    val accentStart: Color get() = NeoStyleState.style.accentStops()?.first ?: UacThemeState.palette.accentStart
    val accentEnd: Color get() = NeoStyleState.style.accentStops()?.second ?: UacThemeState.palette.accentEnd
    val compact: Boolean get() = NeoStyleState.style.compact
    val powerStyle: PowerButtonStyle get() = NeoStyleState.style.power
    val text: Color get() = UacColors.TextPrimary
    val muted: Color get() = UacColors.TextSecondary
    val hairline: Color get() = if (light) Color(0x12000000) else Color(0x0BFFFFFF)
    /** Text drawn on top of the accent gradient. */
    val onAccent: Color get() = if (light) Color.White else Color(0xFF0D1317)

    private var shapeRadius = -1f
    private var cardShape = RoundedCornerShape(24.dp)
    private var rowShape = RoundedCornerShape(18.dp)

    private fun syncShapes() {
        val radius = NeoStyleState.style.cardRadiusDp
        if (radius != shapeRadius) {
            shapeRadius = radius
            cardShape = RoundedCornerShape(radius.dp)
            rowShape = RoundedCornerShape((radius * 0.75f).dp)
        }
    }

    /** Card radius follows the corner slider; shapes are reused until it changes. */
    val CardShape: RoundedCornerShape get() {
        syncShapes()
        return cardShape
    }
    val RowShape: RoundedCornerShape get() {
        syncShapes()
        return rowShape
    }
    val TileShape = RoundedCornerShape(12.dp)
    val PillShape = RoundedCornerShape(50)
}

/**
 * Neumorphic surface: raised (light top-left, dark bottom-right) or inset, with an
 * optional light glass layer (faint white fill, top sheen and a 1dp highlight edge).
 * Shadows reach outside the bounds, so do not clip before this modifier.
 */
internal fun Modifier.neoSurface(
    shape: Shape,
    depth: Dp = 6.dp,
    inset: Boolean = false,
    glass: Boolean = !inset,
    fill: Color = Color.Unspecified,
): Modifier = this then NeoSurfaceElement(shape, depth, inset, glass, fill)

/** Neo surface on Graphite/Mist, the given fallback on the neon themes. */
internal fun Modifier.neoOr(
    shape: Shape,
    depth: Dp = 4.dp,
    inset: Boolean = false,
    fallback: Modifier.() -> Modifier,
): Modifier = if (Neo.active) neoSurface(shape, depth, inset) else fallback()

private data class NeoSurfaceElement(
    val shape: Shape,
    val depth: Dp,
    val inset: Boolean,
    val glass: Boolean,
    val fill: Color,
) : ModifierNodeElement<NeoSurfaceNode>() {
    override fun create(): NeoSurfaceNode = NeoSurfaceNode(shape, depth, inset, glass, fill)

    override fun update(node: NeoSurfaceNode) {
        node.shape = shape
        node.depth = depth
        node.inset = inset
        node.glass = glass
        node.fill = fill
        node.dirty = true
        node.invalidateDraw()
    }
}

private class NeoSurfaceNode(
    var shape: Shape,
    var depth: Dp,
    var inset: Boolean,
    var glass: Boolean,
    var fill: Color,
) : Modifier.Node(), DrawModifierNode {
    var dirty = true
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val ring = android.graphics.Path()
    private val rect = RectF()
    private var radius = -1f
    private var outline: Outline? = null
    private var cachedSize = Size.Zero
    private var cachedDirection = LayoutDirection.Ltr
    private var cachedKey = 0
    private var glassFill: Brush? = null
    private var sheen: Brush? = null
    private var edge: Brush? = null
    private var hairPx = 1f
    private var glassScale = 1f
    private var depthScale = 1f

    override fun ContentDrawScope.draw() {
        val palette = UacThemeState.palette
        val style = NeoStyleState.style
        val base = if (fill.isSpecified) fill else palette.backgroundMiddle
        glassScale = style.glassScale
        depthScale = style.depthScale
        val key = ((base.hashCode() * 31 + palette.neoHi.hashCode()) * 31 + palette.neoLo.hashCode()) * 31 +
            (glassScale * 100f).toInt() * 1_000 + (depthScale * 100f).toInt()
        if (dirty || size != cachedSize || layoutDirection != cachedDirection || key != cachedKey) {
            rebuild(this, base, palette.neoHi, palette.neoLo, palette.light)
            cachedSize = size
            cachedDirection = layoutDirection
            cachedKey = key
            dirty = false
        }
        val o = outline
        if (o != null && size.width > 0f && size.height > 0f) {
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                if (inset) {
                    drawShape(nc, basePaint)
                    nc.save()
                    nc.clipPath(path)
                    nc.drawPath(ring, darkPaint)
                    nc.drawPath(ring, lightPaint)
                    nc.restore()
                } else {
                    drawShape(nc, lightPaint)
                    drawShape(nc, darkPaint)
                    drawShape(nc, basePaint)
                }
            }
            if (glass) {
                glassFill?.let { drawOutline(o, it) }
                sheen?.let { drawOutline(o, it) }
                edge?.let { drawOutline(o, it, style = Stroke(hairPx)) }
            }
        }
        drawContent()
    }

    private fun drawShape(nc: android.graphics.Canvas, paint: Paint) {
        if (radius >= 0f) nc.drawRoundRect(rect, radius, radius, paint) else nc.drawPath(path, paint)
    }

    private fun rebuild(scope: ContentDrawScope, base: Color, hi: Color, lo: Color, light: Boolean) {
        val size = scope.size
        val o = shape.createOutline(size, scope.layoutDirection, scope)
        outline = o
        path.reset()
        radius = -1f
        when (o) {
            is Outline.Rectangle -> {
                rect.set(o.rect.left, o.rect.top, o.rect.right, o.rect.bottom)
                radius = 0f
                path.addRect(rect, android.graphics.Path.Direction.CW)
            }
            is Outline.Rounded -> {
                val rr = o.roundRect
                rect.set(rr.left, rr.top, rr.right, rr.bottom)
                val radii = floatArrayOf(
                    rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y,
                    rr.topRightCornerRadius.x, rr.topRightCornerRadius.y,
                    rr.bottomRightCornerRadius.x, rr.bottomRightCornerRadius.y,
                    rr.bottomLeftCornerRadius.x, rr.bottomLeftCornerRadius.y,
                )
                path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
                if (radii.all { it == radii[0] }) radius = radii[0]
            }
            is Outline.Generic -> {
                path.set(o.path.asAndroidPath())
                rect.set(0f, 0f, size.width, size.height)
            }
        }
        val d = with(scope) { depth.toPx() } * depthScale
        val baseArgb = base.toArgb()
        basePaint.color = baseArgb
        basePaint.clearShadowLayer()
        if (inset) {
            val off = d * 0.55f
            val blur = (d * 1.25f).coerceAtLeast(1f)
            val margin = blur * 3f + off
            ring.reset()
            ring.fillType = android.graphics.Path.FillType.EVEN_ODD
            ring.addRect(
                rect.left - margin, rect.top - margin, rect.right + margin, rect.bottom + margin,
                android.graphics.Path.Direction.CW,
            )
            ring.addPath(path)
            darkPaint.color = lo.toArgb()
            darkPaint.setShadowLayer(blur, off, off, lo.copy(alpha = if (light) 0.85f else 0.95f).toArgb())
            lightPaint.color = hi.toArgb()
            lightPaint.setShadowLayer(blur, -off, -off, hi.copy(alpha = if (light) 1f else 0.8f).toArgb())
        } else {
            val blur = (d * 2f).coerceAtLeast(1f)
            lightPaint.color = baseArgb
            lightPaint.setShadowLayer(blur, -d, -d, hi.copy(alpha = if (light) 1f else 0.8f).toArgb())
            darkPaint.color = baseArgb
            darkPaint.setShadowLayer(blur, d, d, lo.copy(alpha = if (light) 0.75f else 0.92f).toArgb())
        }
        val g = glassScale
        if (glass && g > 0.01f) {
            hairPx = with(scope) { 1.dp.toPx() }
            glassFill = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = ((if (light) 0.30f else 0.055f) * g).coerceAtMost(0.6f)),
                    Color.White.copy(alpha = ((if (light) 0.06f else 0.012f) * g).coerceAtMost(0.3f)),
                ),
                startY = 0f,
                endY = size.height,
            )
            sheen = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = ((if (light) 0.30f else 0.045f) * g).coerceAtMost(0.6f)), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.42f,
            )
            edge = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = ((if (light) 0.95f else 0.16f) * g).coerceAtMost(1f)),
                    Color.White.copy(alpha = ((if (light) 0.25f else 0.025f) * g).coerceAtMost(1f)),
                    Color.White.copy(alpha = ((if (light) 0.55f else 0.06f) * g).coerceAtMost(1f)),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        } else {
            glassFill = null
            sheen = null
            edge = null
        }
    }
}

/** Accent gradient (#4FE3C1 to #3AA8FF on Graphite). */
@Composable
internal fun rememberNeoAccentBrush(): Brush {
    val start = Neo.accentStart
    val end = Neo.accentEnd
    return remember(start, end) { Brush.linearGradient(listOf(start, end)) }
}

@Composable
internal fun NeoCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    depth: Dp = 7.dp,
    contentPadding: PaddingValues = PaddingValues(if (Neo.compact) 12.dp else 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (corner == 24.dp) Neo.CardShape else RoundedCornerShape(corner)
    Column(
        modifier = modifier
            .neoSurface(shape, depth)
            .clip(shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
internal fun NeoIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Neo.accentStart,
    size: Dp = 38.dp,
    inset: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .neoSurface(RoundedCornerShape(size * 0.32f), depth = 3.5.dp, inset = inset),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}

/** Square-ish raised icon button that sinks while pressed. */
@Composable
internal fun NeoIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    tint: Color = Neo.muted,
    shape: Shape = RoundedCornerShape(15.dp),
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(size)
            .neoSurface(shape, depth = 4.dp, inset = pressed)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

@Composable
internal fun NeoCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    tint: Color = Neo.muted,
    enabled: Boolean = true,
) = NeoIconButton(icon, contentDescription, onClick, modifier, size, tint, CircleShape, enabled)

/** Pill button; the primary one is filled with the accent gradient. */
@Composable
internal fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    font: FontFamily? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    val brush = rememberNeoAccentBrush()
    val content = if (primary) Neo.onAccent else Neo.text
    Row(
        modifier = modifier
            .height(48.dp)
            .neoSurface(shape, depth = 4.dp, inset = pressed && !primary)
            .clip(shape)
            .then(if (primary) Modifier.background(brush, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (enabled) content else content.copy(alpha = 0.45f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = font,
            maxLines = 1,
        )
    }
}

/** Pill switch: inset track, gradient fill when on, raised thumb. */
@Composable
internal fun NeoToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "neo-toggle",
    )
    val brush = rememberNeoAccentBrush()
    val track = Neo.PillShape
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 28.dp)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                } else {
                    Modifier
                },
            )
            .neoSurface(track, depth = 3.dp, inset = true)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(brush, track),
        )
        Box(
            Modifier
                .offset { IntOffset((4.dp + 22.dp * progress).roundToPx(), 4.dp.roundToPx()) }
                .size(20.dp)
                .neoSurface(
                    CircleShape,
                    depth = 2.dp,
                    glass = false,
                    fill = if (checked) Color(0xFFF4FFFC) else Color.Unspecified,
                ),
        )
    }
}

/** Inset square stat tile: label, big value and a small unit. */
@Composable
internal fun NeoStatTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Neo.text,
    font: FontFamily? = null,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .neoSurface(RoundedCornerShape(18.dp), depth = 4.dp, inset = true)
            .padding(vertical = if (compact) 8.dp else 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Neo.muted, fontSize = 11.sp, fontFamily = font, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = valueColor,
                fontSize = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    color = Neo.muted,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

/** Small capsule for values and badges (inset by default). */
@Composable
internal fun NeoPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Neo.accentStart,
    raised: Boolean = false,
) {
    Box(
        modifier = modifier
            .neoSurface(Neo.PillShape, depth = 2.5.dp, inset = !raised, glass = false)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

internal data class NeoSegment(
    val label: String,
    val icon: ImageVector? = null,
    val description: String? = null,
)

/**
 * Segmented rail: an inset pill with the active tab raised. The indicator slides on the
 * layout phase only, so switching does not recompose the row every frame.
 */
@Composable
internal fun NeoSegmented(
    segments: List<NeoSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    spinningIndex: Int = -1,
    font: FontFamily? = null,
    itemModifier: @Composable (Int) -> Modifier = { Modifier },
) {
    val count = segments.size.coerceAtLeast(1)
    var widthPx by remember { mutableIntStateOf(0) }
    val position by animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "neo-segment",
    )
    val outer = RoundedCornerShape(20.dp)
    val inner = RoundedCornerShape(15.dp)
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .height(height)
            .neoSurface(outer, depth = 4.dp, inset = true)
            .padding(5.dp)
            .onSizeChanged { widthPx = it.width },
    ) {
        if (selectedIndex >= 0 && widthPx > 0) {
            val cell = widthPx / count
            val cellDp = with(density) { cell.toDp() }
            Box(
                Modifier
                    .offset { IntOffset((position * cell).roundToInt(), 0) }
                    .width(cellDp)
                    .fillMaxHeight()
                    .neoSurface(inner, depth = 3.dp),
            )
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            segments.forEachIndexed { index, segment ->
                key(index) {
                    val selected = index == selectedIndex
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(inner)
                            .then(itemModifier(index))
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .semantics(mergeDescendants = true) {
                                this.selected = selected
                                segment.description?.let { contentDescription = it }
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = segment.icon
                        if (icon != null) {
                            NeoSegmentIcon(
                                icon = icon,
                                tint = if (selected) Neo.accentStart else Neo.muted,
                                spinning = index == spinningIndex,
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            segment.label,
                            color = if (selected) Neo.text else Neo.muted,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = font,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NeoSegmentIcon(icon: ImageVector, tint: Color, spinning: Boolean) {
    val spin = if (spinning) {
        rememberInfiniteTransition(label = "neo-seg-spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
            label = "neo-seg-spin-deg",
        )
    } else {
        null
    }
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(16.dp)
            .then(if (spin != null) Modifier.graphicsLayer { rotationZ = spin.value } else Modifier),
    )
}

/**
 * List row: raised icon tile, title and subtitle, optional trailing content. In Persian the
 * row is laid out right-to-left so the tile sits on the right, as in the mockup.
 */
@Composable
internal fun NeoListRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = Neo.accentStart,
    selected: Boolean = false,
    rtl: Boolean = LocalHomePersian.current,
    font: FontFamily? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = Neo.RowShape
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(if (selected) Modifier.neoSurface(shape, depth = 4.dp) else Modifier)
                .clip(shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = if (compact || Neo.compact) 5.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NeoIconTile(
                icon = icon,
                tint = if (selected) Neo.accentStart else iconTint,
                size = if (compact) 32.dp else 38.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Neo.text,
                    fontSize = if (compact) 13.sp else 14.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    fontFamily = font,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = Neo.muted,
                        fontSize = if (compact) 10.5.sp else 11.5.sp,
                        fontFamily = font,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}

/** Big round power button: raised disc, gradient ring with a soft glow, inset well, raised core. */
@Composable
internal fun NeoPowerButton(
    state: ConnectionState,
    accent: Color,
    diameter: Dp,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    font: FontFamily? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.97f else 1f, tween(150), label = "neo-power-press")
    val moving = state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING
    val spin = if (moving) {
        rememberInfiniteTransition(label = "neo-power-spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
            label = "neo-power-spin-deg",
        )
    } else {
        null
    }
    val start = Neo.accentEnd
    val end = Neo.accentStart
    val muted = Neo.muted
    val error = UacColors.ErrorRed
    val look = Neo.powerStyle
    val orbBrush = rememberNeoAccentBrush()
    val connected = state == ConnectionState.CONNECTED
    val pulse = if (look == PowerButtonStyle.PULSE && connected) {
        rememberInfiniteTransition(label = "neo-power-pulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "neo-power-pulse-alpha",
        )
    } else {
        null
    }
    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (pulse != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = pulse.value
                        scaleX = 1f + pulse.value * 0.06f
                        scaleY = 1f + pulse.value * 0.06f
                    }
                    .drawWithCache {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = size.minDimension / 2f
                        val halo = Brush.radialGradient(
                            listOf(end.copy(alpha = 0.32f), end.copy(alpha = 0f)),
                            center = c,
                            radius = r * 1.15f,
                        )
                        onDrawBehind { drawCircle(halo, radius = r * 1.15f, center = c) }
                    },
            )
        }
        if (look != PowerButtonStyle.LINE) {
            Box(Modifier.matchParentSize().neoSurface(CircleShape, depth = 10.dp))
        }
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = spin?.value ?: 0f }
                .drawWithCache {
                    val stroke = if (look == PowerButtonStyle.LINE) 2.dp.toPx() else 4.5.dp.toPx()
                    val r = size.minDimension / 2f - 10.dp.toPx()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val sweep = Brush.sweepGradient(listOf(start, end, start), center = c)
                    val glow = Brush.radialGradient(
                        listOf(end.copy(alpha = 0.22f), end.copy(alpha = 0f)),
                        center = c,
                        radius = r + stroke * 3f,
                    )
                    val arcTopLeft = Offset(c.x - r, c.y - r)
                    val arcSize = Size(r * 2f, r * 2f)
                    onDrawBehind {
                        when (state) {
                            ConnectionState.CONNECTED -> {
                                drawCircle(glow, radius = r + stroke * 3f, center = c)
                                drawCircle(end.copy(alpha = 0.10f), radius = r, center = c, style = Stroke(stroke * 3.6f))
                                drawCircle(end.copy(alpha = 0.16f), radius = r, center = c, style = Stroke(stroke * 2f))
                                drawCircle(sweep, radius = r, center = c, style = Stroke(stroke))
                            }
                            ConnectionState.CONNECTING, ConnectionState.DISCONNECTING -> {
                                drawCircle(muted.copy(alpha = 0.14f), radius = r, center = c, style = Stroke(stroke * 0.6f))
                                drawArc(
                                    brush = sweep,
                                    startAngle = -90f,
                                    sweepAngle = 120f,
                                    useCenter = false,
                                    topLeft = arcTopLeft,
                                    size = arcSize,
                                    style = Stroke(stroke, cap = StrokeCap.Round),
                                )
                            }
                            ConnectionState.ERROR ->
                                drawCircle(error.copy(alpha = 0.75f), radius = r, center = c, style = Stroke(stroke * 0.7f))
                            ConnectionState.DISCONNECTED ->
                                drawCircle(muted.copy(alpha = 0.20f), radius = r, center = c, style = Stroke(stroke * 0.5f))
                        }
                    }
                },
        )
        if (look == PowerButtonStyle.RING || look == PowerButtonStyle.PULSE) {
            Box(Modifier.size(diameter * 0.79f).neoSurface(CircleShape, depth = 6.dp, inset = true))
        }
        val orb = look == PowerButtonStyle.ORB && connected
        Box(
            modifier = Modifier
                .size(diameter * if (look == PowerButtonStyle.ORB) 0.7f else 0.575f)
                .neoSurface(CircleShape, depth = if (pressed) 3.dp else 6.dp, inset = pressed)
                .then(if (orb) Modifier.background(orbBrush, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = when {
                        orb -> Neo.onAccent
                        connected -> end
                        else -> accent
                    },
                    modifier = Modifier.size(diameter * 0.19f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    color = if (orb) Neo.onAccent else Neo.muted,
                    fontSize = if (diameter < 160.dp) 10.sp else 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = font,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal data class NeoTimelineStep(
    val title: String,
    val detail: String,
    val time: String,
    val done: Boolean,
)

/** Vertical timeline used for the live route: gradient rail with glowing dots. */
@Composable
internal fun NeoTimeline(
    steps: List<NeoTimelineStep>,
    modifier: Modifier = Modifier,
    font: FontFamily? = null,
) {
    val start = Neo.accentStart
    val idle = Neo.muted
    Column(modifier) {
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(24.dp)
                        .fillMaxHeight()
                        .drawBehind {
                            val x = size.width / 2f
                            val dotY = 14.dp.toPx()
                            val top = if (index == 0) dotY else 0f
                            val bottom = if (index == steps.lastIndex) dotY else size.height
                            drawLine(
                                start.copy(alpha = if (step.done) 0.7f else 0.18f),
                                Offset(x, top),
                                Offset(x, bottom),
                                strokeWidth = 2.dp.toPx(),
                            )
                            if (step.done) {
                                drawCircle(start.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = Offset(x, dotY))
                                drawCircle(start, radius = 5.dp.toPx(), center = Offset(x, dotY))
                            } else {
                                drawCircle(idle.copy(alpha = 0.35f), radius = 5.dp.toPx(), center = Offset(x, dotY), style = Stroke(1.5.dp.toPx()))
                            }
                        },
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(step.title, color = Neo.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = font)
                    Text(step.detail, color = Neo.muted, fontSize = 11.5.sp, fontFamily = font)
                }
                Text(
                    step.time,
                    color = Neo.muted,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Neumorphic page background: plain, a soft gradient, or a faint dot grain. The dot tile is
 * rendered once into a small bitmap and repeated by a shader, so it has no per-frame cost.
 */
internal fun Modifier.neoBackground(): Modifier = drawWithCache {
    val bg = Neo.bg
    val light = Neo.light
    when (NeoStyleState.style.backdrop) {
        NeoBackdrop.PLAIN -> onDrawBehind { drawRect(bg) }
        NeoBackdrop.GRADIENT -> {
            val brush = Brush.verticalGradient(
                listOf(
                    lerp(bg, Color.White, if (light) 0.35f else 0.05f),
                    bg,
                    lerp(bg, Neo.accentEnd, if (light) 0.06f else 0.05f),
                ),
            )
            onDrawBehind { drawRect(brush) }
        }
        NeoBackdrop.DOTS -> {
            val cell = 18.dp.toPx().roundToInt().coerceAtLeast(8)
            val tile = ImageBitmap(cell, cell)
            val paint = androidx.compose.ui.graphics.Paint().apply {
                color = if (light) Color(0x14000000) else Color(0x12FFFFFF)
                isAntiAlias = true
            }
            androidx.compose.ui.graphics.Canvas(tile).drawCircle(
                Offset(cell / 2f, cell / 2f),
                (cell * 0.07f).coerceAtLeast(1f),
                paint,
            )
            val dots = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
            onDrawBehind {
                drawRect(bg)
                drawRect(dots)
            }
        }
    }
}

/** Shared semantics helper so rows read as buttons with their title. */
internal fun Modifier.neoButtonSemantics(label: String): Modifier = semantics {
    role = Role.Button
    contentDescription = label
}
