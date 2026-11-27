package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.simorgh.PowerMode
import com.uacspoofer.mobile.ui.CountryFlagResources

val Vazir = FontFamily(
    Font(R.font.vazirmatn_ui_fd_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_ui_fd_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_ui_fd_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_ui_fd_bold, FontWeight.Bold),
)

@Immutable
data class SxColors(
    val light: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val stroke: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val radius: Dp,
    val textScale: Float,
    val compact: Boolean,
    val button: ButtonStyle,
    val anim: AnimLevel,
    /** Bottom bar and other chrome. */
    val nav: Color = surface,
    /** Map sea / land / borders (ExpressVPN-style flat map). */
    val mapSea: Color = Color(0xFFD3E3E0),
    val mapLand: Color = Color.White,
    val mapBorder: Color = Color(0xFFD3E3E0),
    val hero: HeroStyle = HeroStyle.ARCS,
    /** User switch for map motion; the power mode still caps it (see [mapMotionFor]). */
    val mapAnim: Boolean = true,
)

val LocalSx = staticCompositionLocalOf { lightColors(ShellPrefs(), AnimLevel.FULL) }

/** The approved accent ("Express green"); dark theme uses its bright variant. */
private const val EXPRESS_GREEN = 0xFF1F7A5CL
private val EXPRESS_GREEN_DARK = Color(0xFF4CD4A0)

private fun darkColors(p: ShellPrefs, amoled: Boolean, anim: AnimLevel): SxColors {
    val accent = if (p.accent == EXPRESS_GREEN) EXPRESS_GREEN_DARK else lerp(Color(p.accent), Color.White, 0.18f)
    val bg = if (amoled) Color(0xFF000000) else Color(0xFF14232B)
    return SxColors(
        light = false, bgTop = bg, bgBottom = bg,
        surface = if (amoled) Color(0xFF111A1F) else Color(0xFF1C2F38),
        surfaceHigh = if (amoled) Color(0xFF1B262C) else Color(0xFF24414C),
        stroke = if (amoled) Color(0xFF22303A) else Color(0xFF28404B),
        text = Color(0xFFEEF3F4), textDim = Color(0xFF9AABB2), textFaint = Color(0xFF6E828B),
        accent = accent, onAccent = Color(0xFF0E2A20),
        good = accent, warn = Color(0xFFE0A100), bad = Color(0xFFE57373),
        radius = p.radius.dp, textScale = p.textScale, compact = p.compact, button = p.button, anim = anim,
        nav = if (amoled) Color(0xFF0B1013) else Color(0xFF182A33),
        mapSea = if (amoled) Color(0xFF0B1013) else Color(0xFF18303A),
        mapLand = if (amoled) Color(0xFF2A3338) else Color(0xFF454B4E),
        mapBorder = if (amoled) Color(0xFF0B1013) else Color(0xFF18303A),
    )
}

private fun lightColors(p: ShellPrefs, anim: AnimLevel): SxColors {
    val accent = Color(p.accent)
    return SxColors(
        light = true, bgTop = Color(0xFFF4F3EE), bgBottom = Color(0xFFF4F3EE),
        surface = Color.White, surfaceHigh = Color(0xFFEFEEE8),
        stroke = Color(0xFFE6E5DF), text = Color(0xFF1B2B2A), textDim = Color(0xFF6B7775),
        textFaint = Color(0xFF9AA19F), accent = accent, onAccent = Color.White,
        good = Color(0xFF1F7A5C), warn = Color(0xFFC98A00), bad = Color(0xFFC0504D),
        radius = p.radius.dp, textScale = p.textScale, compact = p.compact, button = p.button, anim = anim,
        nav = Color.White,
    )
}

private fun monoColors(p: ShellPrefs, anim: AnimLevel): SxColors = darkColors(p.copy(accent = 0xFFE8E8E8), true, anim).copy(
    accent = Color(0xFFEDEDED), onAccent = Color.Black, good = Color(0xFFDADADA), warn = Color(0xFFBDBDBD), bad = Color(0xFF9E9E9E),
)

/** Theme for the shell: ExpressVPN-style palette (light default), RTL, animation budget from settings + power mode. */
@Composable
fun SimorghShellTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ShellPrefsStore.get(context) }
    val prefs by store.prefsFlow.collectAsStateWithLifecycle()
    val powerFlow = remember(context) { PowerMode.flow(context) }
    val initialPower = remember(context) { PowerMode.current(context) }
    val power by powerFlow.collectAsStateWithLifecycle(initialPower)
    val systemDark = isSystemInDarkTheme()
    val anim = animLevelFor(power, prefs.anim)
    val colors = remember(prefs, systemDark, anim) {
        when (prefs.skin) {
            Skin.SYSTEM -> if (systemDark) darkColors(prefs, false, anim) else lightColors(prefs, anim)
            Skin.LIGHT -> lightColors(prefs, anim)
            Skin.DARK -> darkColors(prefs, false, anim)
            Skin.AMOLED -> darkColors(prefs, true, anim)
            Skin.MONO -> monoColors(prefs, anim)
        }.copy(hero = prefs.hero, mapAnim = prefs.mapAnim)
    }
    // Every page (including the engine settings and tool pages) draws with these colours.
    remember(colors) { com.uacspoofer.mobile.ui.theme.UacThemeState.override = colors.toPagePalette(); 0 }
    val scheme = remember(colors) { colors.materialScheme() }
    val typography = remember { vazirTypography() }
    androidx.compose.material3.MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(
            LocalSx provides colors,
            LocalLayoutDirection provides LayoutDirection.Rtl,
            com.uacspoofer.mobile.ui.LocalHomePersian provides true,
            content = content,
        )
    }
}

internal fun SxColors.toPagePalette() = com.uacspoofer.mobile.ui.theme.shellPalette(
    light = light, bg = bgTop, surface = surface, surfaceHigh = surfaceHigh, stroke = stroke,
    text = text, textDim = textDim, accent = accent, good = good, warn = warn, bad = bad,
)

private fun SxColors.materialScheme(): androidx.compose.material3.ColorScheme {
    val base = if (light) androidx.compose.material3.lightColorScheme() else androidx.compose.material3.darkColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accent.copy(alpha = 0.16f), onPrimaryContainer = text,
        secondary = accent, onSecondary = onAccent, secondaryContainer = surfaceHigh, onSecondaryContainer = text,
        tertiary = accent, onTertiary = onAccent,
        background = bgTop, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = textDim, surfaceTint = Color.Transparent,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = surfaceHigh, inverseSurface = text, inverseOnSurface = surface,
        outline = stroke, outlineVariant = stroke, error = bad, onError = Color.White,
        scrim = Color.Black,
    )
}

private fun vazirTypography(): androidx.compose.material3.Typography {
    val t = androidx.compose.material3.Typography()
    fun TextStyle.v() = copy(fontFamily = Vazir)
    return t.copy(
        displayLarge = t.displayLarge.v(), displayMedium = t.displayMedium.v(), displaySmall = t.displaySmall.v(),
        headlineLarge = t.headlineLarge.v(), headlineMedium = t.headlineMedium.v(), headlineSmall = t.headlineSmall.v(),
        titleLarge = t.titleLarge.v(), titleMedium = t.titleMedium.v(), titleSmall = t.titleSmall.v(),
        bodyLarge = t.bodyLarge.v(), bodyMedium = t.bodyMedium.v(), bodySmall = t.bodySmall.v(),
        labelLarge = t.labelLarge.v(), labelMedium = t.labelMedium.v(), labelSmall = t.labelSmall.v(),
    )
}

/**
 * The three power modes drive every animation in the shell:
 * BALANCED = what the user picked, SAVER = at most REDUCED (one-shot motion, nothing infinite),
 * ULTRA = OFF (static).
 */
fun animLevelFor(power: PowerMode, chosen: AnimLevel): AnimLevel = when {
    power == PowerMode.ULTRA -> AnimLevel.OFF
    power == PowerMode.SAVER && chosen == AnimLevel.FULL -> AnimLevel.REDUCED
    else -> chosen
}

fun SxColors.pingColor(ms: Long?): Color = when {
    ms == null -> textFaint
    ms < 120 -> good
    ms <= 250 -> warn
    else -> bad
}

@Composable
fun SText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalSx.current.text,
    maxLines: Int = 1,
    align: TextAlign? = null,
    ltr: Boolean = false,
    /** Latin digits (IP addresses): Vazirmatn FD always draws Persian digits. */
    latin: Boolean = false,
) {
    val sx = LocalSx.current
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = if (latin) FontFamily.Default else Vazir,
            fontSize = size * sx.textScale,
            fontWeight = weight,
            color = color,
            textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

fun Modifier.sxCard(sx: SxColors, shape: Shape = RoundedCornerShape(sx.radius), highlight: Boolean = false): Modifier =
    this
        .then(if (sx.light) Modifier.shadow(1.5.dp, shape, clip = true, ambientColor = CARD_SHADOW, spotColor = CARD_SHADOW) else Modifier.clip(shape))
        .background(if (highlight) lerp(sx.surface, sx.accent, 0.08f) else sx.surface)
        .then(if (highlight) Modifier.border(1.dp, sx.accent.copy(alpha = 0.45f), shape) else Modifier)

private val CARD_SHADOW = Color(0x33000000)

@Composable
fun Modifier.sxClick(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = ripple(), onClick = onClick)
}

@Composable
fun FlagBox(code: String?, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    val res = remember(code) { CountryFlagResources.drawableFor(code) }
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        modifier.size(width = size * 1.3f, height = size).clip(shape).background(sx.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) {
            Image(painterResource(res), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Outlined.Public, null, tint = sx.textDim, modifier = Modifier.size(size * 0.7f))
        }
    }
}

@Composable
fun IconBox(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = LocalSx.current.accent, filled: Boolean = false) {
    val sx = LocalSx.current
    Box(
        modifier.size(size).clip(RoundedCornerShape(sx.radius.coerceAtMost(16.dp)))
            .background(if (filled) tint else tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (filled) sx.onAccent else tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun PingPill(ms: Long?, modifier: Modifier = Modifier, measuring: Boolean = false, failed: Boolean = false) {
    val sx = LocalSx.current
    val color = if (failed) sx.bad else sx.pingColor(ms)
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        val label = when {
            ms != null -> "$ms ms"
            measuring -> "…"
            failed -> "ناموفق"
            else -> "—"
        }
        SText(label, size = 13.sp, weight = FontWeight.SemiBold, color = color, ltr = ms != null)
    }
}

@Composable
fun SChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null, color: Color? = null, onClick: (() -> Unit)? = null) {
    val sx = LocalSx.current
    val tint = color ?: if (selected) sx.accent else sx.textDim
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) sx.accent.copy(alpha = 0.18f) else (color?.copy(alpha = 0.12f) ?: sx.surfaceHigh.copy(alpha = 0.7f)))
            .then(if (onClick != null) Modifier.sxClick(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        SText(text, size = 12.5.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = tint)
    }
}

@Composable
fun SToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val sx = LocalSx.current
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = sx.accent, checkedBorderColor = sx.accent,
            uncheckedThumbColor = sx.textDim, uncheckedTrackColor = sx.surfaceHigh, uncheckedBorderColor = sx.stroke,
        ),
    )
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SText(title, size = 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, tint: Color = LocalSx.current.text) {
    val sx = LocalSx.current
    Box(
        modifier.size(size).sxCard(sx, RoundedCornerShape(sx.radius.coerceAtLeast(12.dp))).sxClick(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SText(title, size = 26.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalSx.current.stroke))
}

@Composable
fun LabeledColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        SText(label, size = 12.sp, color = LocalSx.current.textDim)
        SText(value, size = 15.sp, weight = FontWeight.SemiBold)
    }
}
