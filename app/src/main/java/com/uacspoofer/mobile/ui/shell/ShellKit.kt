package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Shared building blocks for every full-screen page of the app (settings, engine settings,
 * tools, support, guide). Same palette, radius and type as the home tab.
 */

/** Page with a back arrow, a title and a scrolling body. Draws its own status/nav bar padding. */
@Composable
internal fun SubPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    spacing: Dp = 12.dp,
    content: LazyListScope.() -> Unit,
) {
    val sx = LocalSx.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(sx.bgTop)) {
        PageHeader(title, onBack, subtitle, Modifier.padding(top = top), actions)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 28.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** Header row used by [SubPage] and by pages that lay out their own body. */
@Composable
internal fun PageHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val sx = LocalSx.current
    Row(
        modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButtonFlat(Icons.AutoMirrored.Outlined.ArrowBack, onBack, description = "بازگشت")
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            SText(title, size = 20.sp, weight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) SText(subtitle, size = 12.5.sp, color = sx.textDim, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A titled card holding rows separated by hairlines. */
@Composable
internal fun SGroup(title: String? = null, modifier: Modifier = Modifier, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val sx = LocalSx.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            SText(title, size = 14.sp, weight = FontWeight.Bold, color = sx.textDim, modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp, top = 6.dp))
        }
        Column(Modifier.fillMaxWidth().sxCard(sx), content = content)
        if (footer != null) {
            SText(footer, size = 12.sp, color = sx.textFaint, maxLines = 4, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
        }
    }
}

/** Icon + title + subtitle + optional trailing; chevron when clickable and no trailing. */
@Composable
internal fun SRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalSx.current.accent,
    subtitleColor: Color = LocalSx.current.textDim,
    titleLtr: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val sx = LocalSx.current
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.sxClick(onClick) else Modifier)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBox(icon, size = 38.dp, tint = tint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            SText(title, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 2, ltr = titleLtr)
            if (!subtitle.isNullOrBlank()) SText(subtitle, size = 12.5.sp, color = subtitleColor, maxLines = 3)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else if (onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = sx.textFaint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun SToggleRow(icon: ImageVector?, title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SRow(icon, title, subtitle, onClick = if (enabled) ({ onChange(!checked) }) else null, trailing = { SToggle(checked, onChange, enabled) })
}

/** Hairline inset to line up with the row text. */
@Composable
internal fun RowDivider(inset: Dp = 64.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(LocalSx.current.stroke))
}

@Composable
internal fun SButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color? = null,
) {
    val sx = LocalSx.current
    val base = color ?: sx.accent
    val shape = RoundedCornerShape(sx.radius.coerceIn(12.dp, 18.dp))
    val bg = when {
        !enabled -> sx.surfaceHigh
        primary -> base
        else -> base.copy(alpha = 0.12f)
    }
    val fg = when {
        !enabled -> sx.textFaint
        primary -> if (color == null) sx.onAccent else Color.White
        else -> base
    }
    Row(
        modifier.clip(shape).background(bg)
            .then(if (enabled) Modifier.sxClick(onClick) else Modifier)
            .defaultMinSize(minHeight = 46.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        SText(text, size = 14.5.sp, weight = FontWeight.Bold, color = fg)
    }
}

/** Soft notice card (info, success, warning). */
@Composable
internal fun SNote(text: String, modifier: Modifier = Modifier, tone: Color = LocalSx.current.accent, icon: ImageVector = Icons.Outlined.Info) {
    val sx = LocalSx.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(sx.radius.coerceAtMost(18.dp)))
            .background(tone.copy(alpha = if (sx.light) 0.09f else 0.15f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        SText(text, size = 13.sp, color = sx.text, maxLines = 12, modifier = Modifier.weight(1f))
    }
}

/** Flat text field in the card style. */
@Composable
internal fun SField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    ltr: Boolean = false,
    number: Boolean = false,
    minHeight: Dp = 48.dp,
) {
    val sx = LocalSx.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(sx.surfaceHigh.copy(alpha = if (sx.light) 0.7f else 1f))
            .border(1.dp, sx.stroke, shape)
            .defaultMinSize(minHeight = minHeight)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) SText(placeholder, size = 14.sp, color = sx.textFaint, maxLines = 2)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            textStyle = TextStyle(
                fontFamily = if (ltr) androidx.compose.ui.text.font.FontFamily.Monospace else Vazir,
                fontSize = 14.sp * sx.textScale,
                color = sx.text,
                textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
            ),
            cursorBrush = SolidColor(sx.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Segmented control (two to five options). */
@Composable
internal fun <T> SSegmented(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.then(modifier).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh.copy(alpha = 0.7f)).padding(4.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) sx.surface else Color.Transparent)
                    .sxClick { onSelect(o) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(label(o), size = 13.5.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) sx.accent else sx.textDim)
            }
        }
    }
}

/** Radio option row. */
@Composable
internal fun SRadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().sxClick(onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) sx.accent else sx.textFaint, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(sx.accent)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText(title, size = 15.sp, weight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) SText(subtitle, size = 12.5.sp, color = sx.textDim, maxLines = 3)
        }
    }
}

/** Small coloured tag ("متصل", "پیشنهادی"). */
@Composable
internal fun STag(text: String, color: Color = LocalSx.current.accent) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        SText(text, size = 11.sp, color = color, weight = FontWeight.SemiBold)
    }
}

/** Paragraph text for guide and info pages (no line cap). */
@Composable
internal fun SParagraph(text: String, modifier: Modifier = Modifier, color: Color = LocalSx.current.textDim, size: androidx.compose.ui.unit.TextUnit = 13.5.sp) {
    SText(text, modifier = modifier, size = size, color = color, maxLines = Int.MAX_VALUE)
}
