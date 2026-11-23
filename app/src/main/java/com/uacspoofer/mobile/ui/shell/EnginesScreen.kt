package com.uacspoofer.mobile.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeChangeResult
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.auto.AutoEngineSettings
import com.uacspoofer.mobile.simorgh.auto.AutoEngineStore
import com.uacspoofer.mobile.simorgh.auto.EngineResult
import com.uacspoofer.mobile.ui.DrawerDestination

@Composable
internal fun EnginesScreen(onBack: () -> Unit, onOpenLegacy: (DrawerDestination) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val store = remember(context) { AutoEngineStore.get(context) }
    val auto by store.settings.collectAsStateWithLifecycle()
    val selected by remember(context) { EngineModeStore.get(context) }.mode.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2_400L); toast = null } }
    val connected = location.connection == ConnectionState.CONNECTED

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowForward, onBack)
                    Spacer(Modifier.width(12.dp))
                    SText("موتورها", size = 26.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }
            item(key = "auto") { AutoCard(auto, if (connected) location.engine else null) { on -> store.update { it.copy(enabled = on) } } }
            item(key = "note") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.SwapVert, null, tint = sx.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    SText(
                        "ترتیب جایگزینی: اگر موتور بالایی وصل نشد، خودکار سراغ بعدی می‌رود. با فلش‌ها جابه‌جا کنید. موتوری که روی هر شبکه آخرین بار وصل شده، اول امتحان می‌شود.",
                        size = 12.5.sp, color = sx.textDim, maxLines = 4,
                    )
                }
            }
            items(auto.order, key = { it.id }, contentType = { "engine" }) { mode ->
                val index = auto.order.indexOf(mode)
                EngineRow(
                    mode = mode,
                    index = index,
                    last = index == auto.order.lastIndex,
                    selected = mode == selected,
                    running = connected && location.engine == mode,
                    result = auto.results[mode],
                    included = mode in auto.included,
                    onInclude = { on -> store.update { s -> s.copy(included = if (on) s.included + mode else s.included - mode) } },
                    onMove = { delta ->
                        store.update { s ->
                            val list = s.order.toMutableList()
                            val i = list.indexOf(mode)
                            val j = (i + delta).coerceIn(0, list.lastIndex)
                            if (i != j) { list.removeAt(i); list.add(j, mode) }
                            s.copy(order = list)
                        }
                    },
                    onSelect = {
                        when (EngineModeStore.get(context).setMode(mode)) {
                            EngineModeChangeResult.APPLIED -> toast = "موتور ${mode.fa} انتخاب شد"
                            EngineModeChangeResult.BLOCKED_WHILE_ACTIVE -> toast = "برای تغییر موتور، اول اتصال را قطع کنید"
                        }
                    },
                    onSettings = {
                        when (mode) {
                            EngineMode.XRAY_CF -> onOpenLegacy(DrawerDestination.ADVANCED_SETTINGS)
                            EngineMode.TOR_WEBTUNNEL -> onOpenLegacy(DrawerDestination.TOR_SETTINGS)
                            EngineMode.UAC_POW -> onOpenLegacy(DrawerDestination.POW_SETTINGS)
                            EngineMode.UAC_DIRECT -> onOpenLegacy(DrawerDestination.DIRECT_SETTINGS)
                            EngineMode.FOXY_FIREFOX -> if (selected == EngineMode.FOXY_FIREFOX) {
                                onOpenLegacy(DrawerDestination.CONFIGS)
                            } else {
                                toast = "اول روباه را انتخاب کنید، بعد صفحه‌ی کشور و حساب آن باز می‌شود"
                            }
                        }
                    },
                )
            }
            item(key = "subs-title") { SectionTitle("زیرموتورهای شاهپر و سایه") }
            items(SubEngines.all, key = { it.key }, contentType = { "sub" }) { sub ->
                Row(Modifier.fillMaxWidth().sxCard(sx).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBox(sub.icon, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        SText("${sub.fa} (${sub.latin})", size = 15.sp, weight = FontWeight.Bold)
                        SText(sub.desc, size = 12.sp, color = sx.textDim, maxLines = 2)
                    }
                }
            }
            item(key = "opts-title") { SectionTitle("هوشمندی") }
            item(key = "opts") {
                Column(Modifier.fillMaxWidth().sxCard(sx)) {
                    ToggleRow("حافظه‌ی موتور برای هر شبکه", "موتوری که روی هر وای‌فای یا شبکه‌ی موبایل وصل شده را به خاطر می‌سپارد", auto.networkMemory) { on -> store.update { it.copy(networkMemory = on) } }
                    DividerLine()
                    ToggleRow("اتصال مجدد خودکار", "اگر تست واقعی تونل چند بار پشت‌سرهم شکست بخورد، دوباره وصل می‌شود", auto.autoReconnect) { on -> store.update { it.copy(autoReconnect = on) } }
                }
            }
        }
        AnimatedVisibility(toast != null, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh).padding(horizontal = 16.dp, vertical = 10.dp)) {
                SText(toast.orEmpty(), size = 13.sp)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SText(title, size = 14.5.sp, weight = FontWeight.SemiBold)
            SText(subtitle, size = 12.sp, color = sx.textDim, maxLines = 2)
        }
        Spacer(Modifier.width(10.dp))
        SToggle(checked, onChange)
    }
}

@Composable
private fun AutoCard(auto: AutoEngineSettings, runningEngine: EngineMode?, onToggle: (Boolean) -> Unit) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().sxCard(sx, highlight = true).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(SubEngines.AUTO_ICON, filled = true, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText("خودکار", size = 18.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    StatusChip(
                        if (auto.enabled) "روشن" + (runningEngine?.let { " · الان ${it.fa}" } ?: "") else "خاموش",
                        if (auto.enabled) sx.good else sx.textFaint,
                    )
                }
                SText("بهترین مسیر خودکار، بر اساس نتیجه‌ی واقعی", size = 12.5.sp, color = sx.textDim)
            }
            SToggle(auto.enabled, onToggle)
        }
        Spacer(Modifier.size(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            val chain = auto.order.filter { it in auto.included }
            chain.forEachIndexed { i, m ->
                if (i > 0) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = sx.textFaint, modifier = Modifier.size(18.dp))
                SChip(m.fa, selected = i == 0)
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        SText(text, size = 11.5.sp, color = color, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun EngineRow(
    mode: EngineMode,
    index: Int,
    last: Boolean,
    selected: Boolean,
    running: Boolean,
    result: EngineResult?,
    included: Boolean,
    onInclude: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onSelect: () -> Unit,
    onSettings: () -> Unit,
) {
    val sx = LocalSx.current
    val (status, color) = when {
        running -> "متصل" to sx.good
        result == null -> "آزمایش نشده" to sx.textFaint
        !result.ok -> "ناموفق" to sx.bad
        (result.latencyMs ?: 0L) > 250 -> "کند" to sx.warn
        else -> "آماده" to sx.good
    }
    Column(Modifier.fillMaxWidth().sxCard(sx, highlight = running || selected)) {
        Row(Modifier.fillMaxWidth().sxClick(onSelect).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.KeyboardArrowUp, "بالا", tint = if (index == 0) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                    modifier = Modifier.size(26.dp).clip(CircleShape).sxClick { if (index > 0) onMove(-1) })
                SText("${index + 1}", size = 12.sp, color = sx.textDim)
                Icon(Icons.Outlined.KeyboardArrowDown, "پایین", tint = if (last) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                    modifier = Modifier.size(26.dp).clip(CircleShape).sxClick { if (!last) onMove(1) })
            }
            Spacer(Modifier.width(8.dp))
            IconBox(mode.icon, size = 50.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText(mode.fa, size = 17.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    StatusChip(status, color)
                    if (selected && !running) {
                        Spacer(Modifier.width(6.dp))
                        StatusChip("پیش‌فرض", sx.accent)
                    }
                }
                SText(mode.descFa, size = 12.sp, color = sx.textDim, maxLines = 2)
            }
            Icon(Icons.Outlined.Tune, "تنظیمات", tint = sx.textDim, modifier = Modifier.size(36.dp).clip(CircleShape).sxClick(onSettings).padding(7.dp))
        }
        DividerLine()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SText("آخرین نتیجه‌ی واقعی", size = 12.5.sp, color = sx.textDim)
            Spacer(Modifier.width(8.dp))
            when {
                result == null -> SText("—", size = 12.5.sp, color = sx.textFaint)
                !result.ok -> SText("ناموفق · ${timeAgoFa(result.atMs)}", size = 12.5.sp, color = sx.bad)
                result.latencyMs != null -> PingPill(result.latencyMs)
                else -> SText("وصل شد · ${timeAgoFa(result.atMs)}", size = 12.5.sp, color = sx.good)
            }
            Spacer(Modifier.weight(1f))
            SText("در چرخه‌ی خودکار", size = 13.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            SToggle(included, onInclude)
        }
    }
}
