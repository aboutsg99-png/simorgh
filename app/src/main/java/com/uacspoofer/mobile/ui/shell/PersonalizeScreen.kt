package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PersonalizeScreen(onBack: () -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val store = remember(context) { ShellPrefsStore.get(context) }
    val p by store.prefsFlow.collectAsStateWithLifecycle()
    var customOpen by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButtonFlat(Icons.AutoMirrored.Outlined.ArrowBack, onBack, description = "بازگشت")
                Spacer(Modifier.width(12.dp))
                SText("شخصی‌سازی", size = 21.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SChip("بازنشانی", icon = Icons.Outlined.Restore, selected = true) { store.reset() }
            }
        }
        item(key = "preview") {
            Row(Modifier.fillMaxWidth().sxCard(sx, highlight = true).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniPower(p.button, true, 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    SText("پیش‌نمایش زنده", size = 16.sp, weight = FontWeight.Bold)
                    val accentName = ACCENT_NAMES[ShellPrefs.ACCENTS.indexOf(p.accent)] ?: "سفارشی"
                    SText("${p.skin.fa} · $accentName · دکمه ${p.button.fa}", size = 12.5.sp, color = sx.textDim)
                }
            }
        }
        item(key = "skin-t") { SectionTitle("پوسته") }
        item(key = "skin") {
            Row(Modifier.fillMaxWidth().sxCard(sx).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Skin.entries.forEach { s ->
                    Column(Modifier.weight(1f).sxClick { store.update { it.copy(skin = s) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                        SkinSwatch(s, s == p.skin)
                        Spacer(Modifier.height(6.dp))
                        SText(s.fa, size = 12.sp, color = if (s == p.skin) sx.accent else sx.textDim, weight = if (s == p.skin) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        item(key = "accent-t") {
            SectionTitle("رنگ اصلی") { SText(ACCENT_NAMES[ShellPrefs.ACCENTS.indexOf(p.accent)] ?: "سفارشی", size = 12.5.sp, color = sx.textDim) }
        }
        item(key = "accent") {
            Row(Modifier.fillMaxWidth().sxCard(sx).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ShellPrefs.ACCENTS.forEach { a ->
                    val on = a == p.accent
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color(a))
                            .then(if (on) Modifier.border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape) else Modifier)
                            .sxClick { store.update { it.copy(accent = a) } },
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                        .sxClick { customOpen = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Colorize, "رنگ دلخواه", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        item(key = "surf-t") { SectionTitle("سطح‌ها و متن") }
        item(key = "surf") {
            Column(Modifier.fillMaxWidth().sxCard(sx).padding(14.dp)) {
                SliderRow("گردی گوشه‌ها", "${p.radius} dp", p.radius.toFloat(), 0f..28f, "0", "28") { v -> store.update { it.copy(radius = v.toInt()) } }
                DividerLine(Modifier.padding(vertical = 10.dp))
                SText("اندازه‌ی متن", size = 15.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh.copy(alpha = 0.6f)).padding(4.dp)) {
                    listOf("کوچک", "عادی", "بزرگ", "خیلی بزرگ").forEachIndexed { i, label ->
                        val on = p.textStep == i
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (on) sx.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .sxClick { store.update { it.copy(textStep = i) } }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SText("آ", size = (14 + i * 3).sp, color = if (on) sx.accent else sx.textDim, weight = FontWeight.Bold)
                            SText(label, size = 11.5.sp, color = if (on) sx.accent else sx.textDim)
                        }
                    }
                }
                DividerLine(Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(Icons.Outlined.UnfoldLess, size = 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        SText("حالت فشرده", size = 15.sp, weight = FontWeight.SemiBold)
                        SText("فاصله‌های کمتر و کارت‌های کوچک‌تر", size = 12.sp, color = sx.textDim)
                    }
                    SToggle(p.compact, { v -> store.update { it.copy(compact = v) } })
                }
            }
        }
        item(key = "btn-t") { SectionTitle("ظاهر دکمه اتصال") }
        item(key = "btn") {
            Row(Modifier.fillMaxWidth().sxCard(sx).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ButtonStyle.entries.forEach { b ->
                    val on = b == p.button
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (on) sx.accent.copy(alpha = 0.12f) else sx.surfaceHigh.copy(alpha = 0.4f))
                            .then(if (on) Modifier.border(1.5.dp, sx.accent, RoundedCornerShape(16.dp)) else Modifier)
                            .sxClick { store.update { it.copy(button = b) } }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MiniPower(b, b == ButtonStyle.FILLED || b == ButtonStyle.SOFT, 44.dp)
                        Spacer(Modifier.height(6.dp))
                        SText(b.fa, size = 12.5.sp, color = if (on) sx.accent else sx.textDim)
                    }
                }
            }
        }
        item(key = "hero-t") { SectionTitle("ظاهر بالای صفحه") }
        item(key = "hero") {
            Row(Modifier.fillMaxWidth().sxCard(sx).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStyle.entries.forEach { h ->
                    val on = h == p.hero
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (on) sx.accent.copy(alpha = 0.12f) else sx.surfaceHigh.copy(alpha = 0.4f))
                            .then(if (on) Modifier.border(1.5.dp, sx.accent, RoundedCornerShape(16.dp)) else Modifier)
                            .sxClick { store.update { it.copy(hero = h) } }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CompositionLocalProvider(LocalSx provides sx.copy(hero = h)) {
                            HeroArcs(com.uacspoofer.mobile.core.ConnectionState.CONNECTED, Modifier.size(width = 72.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)))
                        }
                        Spacer(Modifier.height(6.dp))
                        SText(h.fa, size = 12.5.sp, color = if (on) sx.accent else sx.textDim)
                    }
                }
            }
        }
        item(key = "anim-t") { SectionTitle("انیمیشن‌ها") }
        item(key = "anim") {
            Column(Modifier.fillMaxWidth().sxCard(sx).padding(12.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh.copy(alpha = 0.6f)).padding(4.dp)) {
                    AnimLevel.entries.forEach { a ->
                        val on = a == p.anim
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (on) sx.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .sxClick { store.update { it.copy(anim = a) } }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { SText(a.fa, size = 14.sp, color = if (on) sx.accent else sx.textDim, weight = if (on) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SText("در گوشی‌های ضعیف‌تر «کم» یا «خاموش» برنامه را روان‌تر می‌کند و باتری کمتری مصرف می‌شود. در حالت‌های کم‌مصرف باتری، انیمیشن‌ها خودکار کم یا خاموش می‌شوند.", size = 12.sp, color = sx.textDim, maxLines = 4)
                DividerLine(Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SText("انیمیشن نقشه", size = 15.sp, weight = FontWeight.SemiBold)
                        SText(
                            when (sx.anim) {
                                AnimLevel.FULL -> "حرکت نرم تا کشور خروجی و نقطه‌ی تپنده"
                                AnimLevel.REDUCED -> "حالت کم‌مصرف: فقط یک حرکت، بدون تپش"
                                AnimLevel.OFF -> "حالت فوق‌کم‌مصرف: نقشه ثابت است"
                            },
                            size = 12.sp, color = sx.textDim, maxLines = 2,
                        )
                    }
                    SToggle(p.mapAnim, { v -> store.update { it.copy(mapAnim = v) } }, enabled = sx.anim != AnimLevel.OFF)
                }
            }
        }
        item(key = "cards-t") {
            SectionTitle("کارت‌های صفحه خانه") {
                SText("${p.cards.size - p.hidden.size} از ${p.cards.size} فعال", size = 12.5.sp, color = sx.textDim)
            }
        }
        item(key = "cards") {
            Column(Modifier.fillMaxWidth().sxCard(sx)) {
                p.cards.forEachIndexed { i, card ->
                    if (i > 0) DividerLine()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SText(card.fa, size = 14.5.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.KeyboardArrowUp, "بالا", tint = if (i == 0) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                            modifier = Modifier.size(34.dp).clip(CircleShape).sxClick { if (i > 0) store.update { it.copy(cards = it.cards.move(i, i - 1)) } }.padding(5.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, "پایین", tint = if (i == p.cards.lastIndex) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                            modifier = Modifier.size(34.dp).clip(CircleShape).sxClick { if (i < p.cards.lastIndex) store.update { it.copy(cards = it.cards.move(i, i + 1)) } }.padding(5.dp))
                        Spacer(Modifier.width(6.dp))
                        SToggle(card !in p.hidden, { on -> store.update { it.copy(hidden = if (on) it.hidden - card else it.hidden + card) } })
                    }
                }
            }
        }
    }
    if (customOpen) CustomColorDialog(Color(p.accent), onDismiss = { customOpen = false }) { c ->
        store.update { it.copy(accent = c.toArgb().toLong() and 0xFFFFFFFFL) }
        customOpen = false
    }
}

private val ACCENT_NAMES: Map<Int, String> = mapOf(0 to "فیروزه‌ای", 1 to "آبی", 2 to "بنفش", 3 to "صورتی", 4 to "نارنجی", 5 to "زرد", 6 to "آسمانی", 7 to "سبز")

private fun <T> List<T>.move(from: Int, to: Int): List<T> = toMutableList().also { val x = it.removeAt(from); it.add(to, x) }

@Composable
private fun SliderRow(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, start: String, end: String, onChange: (Float) -> Unit) {
    val sx = LocalSx.current
    var local by remember(current) { mutableFloatStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SText(title, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(sx.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            SText(value, size = 12.5.sp, color = sx.accent, weight = FontWeight.Bold)
        }
    }
    Slider(
        value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = sx.accent, inactiveTrackColor = sx.surfaceHigh),
    )
    Row {
        SText(start, size = 11.sp, color = sx.textFaint, modifier = Modifier.weight(1f))
        SText(end, size = 11.sp, color = sx.textFaint)
    }
}

@Composable
private fun SkinSwatch(skin: Skin, selected: Boolean) {
    val sx = LocalSx.current
    val (bg, fg) = when (skin) {
        Skin.SYSTEM -> Color(0xFF2A3346) to Color(0xFFE9EEF5)
        Skin.LIGHT -> Color(0xFFF1F4F8) to Color(0xFFC9D3E0)
        Skin.DARK -> Color(0xFF172234) to Color(0xFF34445F)
        Skin.AMOLED -> Color.Black to Color(0xFF2A2A2A)
        Skin.MONO -> Color.White to Color(0xFF444444)
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(bg)
            .then(if (selected) Modifier.border(2.dp, sx.accent, RoundedCornerShape(16.dp)) else Modifier.border(1.dp, sx.stroke, RoundedCornerShape(16.dp)))
            .padding(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.align(Alignment.Center)) {
            Box(Modifier.fillMaxWidth(0.55f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (skin == Skin.MONO) Color.Black else sx.accent))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(fg))
            Box(Modifier.fillMaxWidth(0.8f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(fg))
        }
    }
}

@Composable
private fun MiniPower(style: ButtonStyle, filled: Boolean, size: androidx.compose.ui.unit.Dp) {
    val sx = LocalSx.current
    val mod = when (style) {
        ButtonStyle.SOFT -> Modifier.background(Brush.radialGradient(listOf(sx.accent, androidx.compose.ui.graphics.lerp(sx.accent, Color.Black, 0.25f))), CircleShape)
            .border(6.dp, sx.accent.copy(alpha = 0.2f), CircleShape)
        ButtonStyle.RING -> Modifier.border(2.dp, sx.accent, CircleShape)
        ButtonStyle.FILLED -> Modifier.background(sx.accent, CircleShape)
        ButtonStyle.PLAIN -> Modifier
    }
    Box(Modifier.size(size).clip(CircleShape).then(mod), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.PowerSettingsNew, null,
            tint = if (style == ButtonStyle.SOFT || style == ButtonStyle.FILLED) sx.onAccent else sx.accent,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun CustomColorDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceIn(0.35f, 1f)) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 0.85f)))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(color) }) { SText("انتخاب", color = color, weight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { SText("لغو") } },
        title = { SText("رنگ دلخواه", size = 18.sp, weight = FontWeight.Bold) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(color))
                Spacer(Modifier.height(12.dp))
                SText("رنگ", size = 13.sp)
                Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.8f, 0.9f))) }))
                }
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                SText("اشباع", size = 13.sp)
                Slider(value = sat, onValueChange = { sat = it }, valueRange = 0.35f..1f)
            }
        },
    )
}
