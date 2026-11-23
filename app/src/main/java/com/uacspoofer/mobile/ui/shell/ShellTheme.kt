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
)

val LocalSx = staticCompositionLocalOf { darkColors(ShellPrefs(), false, AnimLevel.FULL) }

private fun darkColors(p: ShellPrefs, amoled: Boolean, anim: AnimLevel): SxColors {
    val accent = Color(p.accent)
    val surfaceOpaque = if (amoled) Color(0xFF101318) else Color(0xFF18233A)
    val bgTop = if (amoled) Color(0xFF000000) else Color(0xFF132036)
    val bgBottom = if (amoled) Color(0xFF000000) else Color(0xFF0A111E)
    // Fake glass: more glass = more of the background shows through (no blur at runtime).
    val surface = lerp(surfaceOpaque, bgBottom, 0.45f * p.glass).copy(alpha = 1f - 0.18f * p.glass)
    return SxColors(
        light = false, bgTop = bgTop, bgBottom = bgBottom, surface = surface,
        surfaceHigh = if (amoled) Color(0xFF1A1E25) else Color(0xFF22304A),
        stroke = Color.White.copy(alpha = 0.07f), text = Color(0xFFEAF0F7), textDim = Color(0xFF93A1B5),
        textFaint = Color(0xFF5F6D82), accent = accent, onAccent = Color(0xFF06231C),
        good = Color(0xFF3ED6A0), warn = Color(0xFFF2B35A), bad = Color(0xFFF06A6A),
        radius = p.radius.dp, textScale = p.textScale, compact = p.compact, button = p.button, anim = anim,
    )
}

private fun lightColors(p: ShellPrefs, anim: AnimLevel): SxColors {
    val accent = Color(p.accent)
    return SxColors(
        light = true, bgTop = Color(0xFFF3F6FA), bgBottom = Color(0xFFE6ECF3),
        surface = Color.White.copy(alpha = 1f - 0.15f * p.glass), surfaceHigh = Color(0xFFEFF3F8),
        stroke = Color(0xFF0B1A2E).copy(alpha = 0.08f), text = Color(0xFF0E1A2B), textDim = Color(0xFF5B6B80),
        textFaint = Color(0xFF94A2B5), accent = lerp(accent, Color.Black, 0.12f), onAccent = Color.White,
        good = Color(0xFF16A37C), warn = Color(0xFFC98516), bad = Color(0xFFD94848),
        radius = p.radius.dp, textScale = p.textScale, compact = p.compact, button = p.button, anim = anim,
    )
}

private fun monoColors(p: ShellPrefs, anim: AnimLevel): SxColors = darkColors(p.copy(accent = 0xFFE8E8E8), true, anim).copy(
    accent = Color(0xFFEDEDED), onAccent = Color.Black, good = Color(0xFFDADADA), warn = Color(0xFFBDBDBD), bad = Color(0xFF9E9E9E),
)

/** Theme for the 1.0.2 shell: palette, RTL, fixed animation budget from settings and power mode. */
@Composable
fun SimorghShellTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ShellPrefsStore.get(context) }
    val prefs by store.prefsFlow.collectAsStateWithLifecycle()
    val power by remember(context) { PowerMode.flow(context) }.collectAsStateWithLifecycle(PowerMode.current(context))
    val systemDark = isSystemInDarkTheme()
    val anim = when {
        power == PowerMode.ULTRA -> AnimLevel.OFF
        power == PowerMode.SAVER && prefs.anim == AnimLevel.FULL -> AnimLevel.REDUCED
        else -> prefs.anim
    }
    val colors = remember(prefs, systemDark, anim) {
        when (prefs.skin) {
            Skin.SYSTEM -> if (systemDark) darkColors(prefs, false, anim) else lightColors(prefs, anim)
            Skin.LIGHT -> lightColors(prefs, anim)
            Skin.DARK -> darkColors(prefs, false, anim)
            Skin.AMOLED -> darkColors(prefs, true, anim)
            Skin.MONO -> monoColors(prefs, anim)
        }
    }
    CompositionLocalProvider(LocalSx provides colors, LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
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
) {
    val sx = LocalSx.current
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = Vazir,
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

fun Modifier.sxCard(sx: SxColors, shape: Shape = RoundedCornerShape(sx.radius + 6.dp), highlight: Boolean = false): Modifier =
    this
        .clip(shape)
        .background(
            if (highlight) {
                Brush.linearGradient(listOf(sx.accent.copy(alpha = 0.16f), sx.surface))
            } else {
                Brush.verticalGradient(listOf(sx.surface, sx.surface))
            },
        )
        .border(1.dp, if (highlight) sx.accent.copy(alpha = 0.35f) else sx.stroke, shape)

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
