package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.theme.AppTheme
import com.uacspoofer.mobile.ui.theme.AppThemeStore
import com.uacspoofer.mobile.ui.theme.CUSTOM_ACCENT_ID
import com.uacspoofer.mobile.ui.theme.NeoAccents
import com.uacspoofer.mobile.ui.theme.NeoBackdrop
import com.uacspoofer.mobile.ui.theme.NeoStyle
import com.uacspoofer.mobile.ui.theme.NeoStyleState
import com.uacspoofer.mobile.ui.theme.PowerButtonStyle
import kotlin.math.roundToInt

/**
 * Personalisation controls for the neumorphic themes: live preview, accent, power button
 * style, glass, shadow depth, corners, density, background and automatic theme switching.
 * Sliders preview live and persist once when the finger lifts.
 */
@Composable
internal fun NeoAppearanceControls() {
    val context = LocalContext.current
    val store = remember(context) { AppThemeStore.get(context) }
    val saved by store.style.collectAsStateWithLifecycle()
    val live = NeoStyleState.style
    val font = homeLocalizedFont()
    fun save(block: (NeoStyle) -> NeoStyle) = store.updateStyle(block)
    fun preview(block: (NeoStyle) -> NeoStyle) {
        NeoStyleState.style = block(NeoStyleState.style)
    }
    val commit = { store.updateStyle { NeoStyleState.style } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NeoLivePreview()

        AppearanceLabel(homeText("Accent colour", "رنگ اصلی"))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (NeoAccents.map { it.id } + CUSTOM_ACCENT_ID).chunked(5).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    row.forEach { id ->
                        AccentSwatch(
                            id = id,
                            selected = saved.accentId == id,
                            customHue = live.customHue,
                            onClick = { save { it.copy(accentId = id) } },
                        )
                    }
                    repeat(5 - row.size) { Spacer(Modifier.size(42.dp)) }
                }
            }
        }
        if (saved.accentId == CUSTOM_ACCENT_ID) {
            AppearanceSlider(
                label = homeText("Custom hue", "رنگ دلخواه"),
                value = live.customHue / 360f,
                display = "${live.customHue.roundToInt()}°",
                onChange = { v -> preview { it.copy(customHue = v * 360f) } },
                onDone = commit,
            )
        }

        AppearanceLabel(homeText("Power button", "دکمه‌ی اتصال"))
        NeoSegmented(
            segments = listOf(
                NeoSegment(homeText("Ring", "حلقه")),
                NeoSegment(homeText("Orb", "گوی")),
                NeoSegment(homeText("Line", "خط ساده")),
                NeoSegment(homeText("Pulse", "تپش")),
            ),
            selectedIndex = saved.power.ordinal,
            onSelect = { index -> save { it.copy(power = PowerButtonStyle.entries[index]) } },
            modifier = Modifier.fillMaxWidth(),
            font = font,
        )

        AppearanceSlider(
            label = homeText("Glass", "شیشه‌ای بودن"),
            value = live.glass,
            display = "${(live.glass * 100).roundToInt()}%",
            onChange = { v -> preview { it.copy(glass = v) } },
            onDone = commit,
        )
        AppearanceSlider(
            label = homeText("Shadow depth", "عمق سایه"),
            value = live.depth,
            display = "${(live.depth * 100).roundToInt()}%",
            onChange = { v -> preview { it.copy(depth = v) } },
            onDone = commit,
        )
        AppearanceSlider(
            label = homeText("Corner radius", "گردی گوشه‌ها"),
            value = live.corner,
            display = "${live.cardRadiusDp.roundToInt()}",
            onChange = { v -> preview { it.copy(corner = v) } },
            onDone = commit,
        )

        AppearanceLabel(homeText("Card density", "تراکم کارت‌ها"))
        NeoSegmented(
            segments = listOf(
                NeoSegment(homeText("Comfortable", "راحت")),
                NeoSegment(homeText("Compact", "فشرده")),
            ),
            selectedIndex = if (saved.compact) 1 else 0,
            onSelect = { index -> save { it.copy(compact = index == 1) } },
            modifier = Modifier.fillMaxWidth(),
            font = font,
        )

        AppearanceLabel(homeText("Background", "پس‌زمینه"))
        NeoSegmented(
            segments = listOf(
                NeoSegment(homeText("Plain", "ساده")),
                NeoSegment(homeText("Soft gradient", "گرادیان نرم")),
                NeoSegment(homeText("Dots", "نقطه‌ای")),
            ),
            selectedIndex = saved.backdrop.ordinal,
            onSelect = { index -> save { it.copy(backdrop = NeoBackdrop.entries[index]) } },
            modifier = Modifier.fillMaxWidth(),
            font = font,
        )

        AppearanceToggleRow(
            title = homeText("Theme of the day", "تم روز"),
            subtitle = homeText("Rotates daily among the themes you pick", "هر روز یکی از تم‌های انتخابی"),
            checked = saved.themeOfDay,
            onChange = { on -> save { it.copy(themeOfDay = on) } },
        )
        if (saved.themeOfDay) {
            val themes = listOf(
                AppTheme.CLASSIC, AppTheme.MIST, AppTheme.MIDNIGHT,
                AppTheme.SAHAR, AppTheme.JANGAL, AppTheme.FIROUZEH,
            )
            themes.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { theme ->
                        val on = theme.name in saved.dayThemes
                        DayThemeChip(
                            label = neoThemeName(theme),
                            selected = on,
                            onClick = {
                                save {
                                    val next = if (on) it.dayThemes - theme.name else it.dayThemes + theme.name
                                    it.copy(dayThemes = next.ifEmpty { setOf(theme.name) })
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        AppearanceToggleRow(
            title = homeText("Auto dark and light", "تیره و روشن خودکار"),
            subtitle = homeText("Light by day, dark from 7 pm", "روز روشن، از ساعت ۱۹ تیره"),
            checked = saved.autoDarkLight,
            onChange = { on -> save { it.copy(autoDarkLight = on) } },
        )
    }
}

@Composable
internal fun neoThemeName(theme: AppTheme): String = when (theme) {
    AppTheme.CLASSIC -> homeText("Graphite", "گرافیت")
    AppTheme.MIST -> homeText("Mist", "مه")
    AppTheme.NEON -> homeText("Neon", "نئون")
    AppTheme.SOLARIS -> homeText("Solaris", "سولاریس")
    AppTheme.MIDNIGHT -> homeText("Midnight", "نیمه‌شب")
    AppTheme.SAHAR -> homeText("Sahar", "سحر")
    AppTheme.JANGAL -> homeText("Jangal", "جنگل")
    AppTheme.FIROUZEH -> homeText("Firouzeh", "فیروزه")
}

/** Small live sample of the current look; it never touches the real connection. */
@Composable
private fun NeoLivePreview() {
    NeoCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                NeoPowerButton(
                    state = ConnectionState.CONNECTED,
                    accent = Neo.accentStart,
                    diameter = 96.dp,
                    label = homeText("Preview", "پیش‌نمایش"),
                    enabled = false,
                    onClick = {},
                    font = homeLocalizedFont(),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        homeText("Live preview", "پیش‌نمایش زنده"),
                        color = Neo.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = homeLocalizedFont(),
                        modifier = Modifier.weight(1f),
                    )
                    NeoToggle(checked = true, onCheckedChange = null)
                }
                NeoStatTile(
                    label = homeText("Sample", "نمونه"),
                    value = "12.4",
                    unit = "MB/s",
                    compact = true,
                    font = homeLocalizedFont(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(id: String, selected: Boolean, customHue: Float, onClick: () -> Unit) {
    val accent = NeoAccents.firstOrNull { it.id == id }
    val stops = when {
        id == CUSTOM_ACCENT_ID -> NeoStyle(accentId = CUSTOM_ACCENT_ID, customHue = customHue).accentStops()
        accent == null || accent.start == Color.Unspecified -> null
        else -> accent.start to accent.end
    }
    val start = stops?.first ?: com.uacspoofer.mobile.ui.theme.UacThemeState.palette.accentStart
    val end = stops?.second ?: com.uacspoofer.mobile.ui.theme.UacThemeState.palette.accentEnd
    val brush = remember(start, end, id) {
        if (id == CUSTOM_ACCENT_ID) {
            Brush.sweepGradient(listOf(Color(0xFFFF5E5E), Color(0xFFFFD25E), Color(0xFF5EFF9A), Color(0xFF5EC8FF), Color(0xFFB45EFF), Color(0xFFFF5E5E)))
        } else {
            Brush.linearGradient(listOf(start, end))
        }
    }
    val name = when {
        id == CUSTOM_ACCENT_ID -> homeText("Custom", "دلخواه")
        accent != null -> homeText(accent.nameEn, accent.nameFa)
        else -> id
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .neoSurface(CircleShape, depth = 3.dp, inset = !selected, glass = selected)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = name
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (selected) 26.dp else 22.dp).background(brush, CircleShape))
    }
}

@Composable
private fun AppearanceLabel(text: String) {
    Text(
        text,
        color = Neo.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = homeLocalizedFont(),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun AppearanceSlider(
    label: String,
    value: Float,
    display: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                label,
                color = Neo.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = homeLocalizedFont(),
                modifier = Modifier.weight(1f),
            )
            Text(display, color = Neo.muted, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(
                thumbColor = Neo.accentStart,
                activeTrackColor = Neo.accentEnd,
                inactiveTrackColor = Neo.muted.copy(alpha = 0.25f),
            ),
        )
    }
}

@Composable
private fun AppearanceToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Neo.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = homeLocalizedFont())
            Text(subtitle, color = Neo.muted, fontSize = 11.5.sp, fontFamily = homeLocalizedFont())
        }
        Spacer(Modifier.size(10.dp))
        NeoToggle(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DayThemeChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(38.dp)
            .neoSurface(Neo.PillShape, depth = 3.dp, inset = !selected, glass = selected)
            .clip(Neo.PillShape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Neo.accentStart else Neo.muted,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = homeLocalizedFont(),
            maxLines = 1,
        )
    }
}
