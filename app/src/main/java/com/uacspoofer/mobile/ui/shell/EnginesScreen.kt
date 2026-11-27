package com.uacspoofer.mobile.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.simorgh.EngineChoice
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.auto.AutoEngineStore
import com.uacspoofer.mobile.ui.DrawerDestination

/**
 * «موتور اتصال», styled like ExpressVPN's protocol screen: one radio list with the real
 * engine names. The Auto chain (order, which engines it may try) and the per-engine
 * settings stay one tap away below the list.
 */
@Composable
internal fun EnginesScreen(onBack: () -> Unit, onOpenLegacy: (DrawerDestination) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val store = remember(context) { AutoEngineStore.get(context) }
    val auto by store.settings.collectAsStateWithLifecycle()
    val mode by remember(context) { EngineModeStore.get(context) }.mode.collectAsStateWithLifecycle()
    val pow by remember(context) { PowEngineStore.get(context) }.settings.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    val selected = EngineChoice.current(auto.enabled, mode, pow.exitMode)
    val connected = location.connection == ConnectionState.CONNECTED
    var toast by remember { mutableStateOf<String?>(null) }
    var chainOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2_400L); toast = null } }

    fun settingsFor(m: EngineMode?) {
        when (m) {
            EngineMode.XRAY_CF -> onOpenLegacy(DrawerDestination.ADVANCED_SETTINGS)
            EngineMode.TOR_WEBTUNNEL -> onOpenLegacy(DrawerDestination.TOR_SETTINGS)
            EngineMode.UAC_POW -> onOpenLegacy(DrawerDestination.POW_SETTINGS)
            EngineMode.UAC_DIRECT -> onOpenLegacy(DrawerDestination.DIRECT_SETTINGS)
            EngineMode.WIREGUARD -> onOpenLegacy(DrawerDestination.WIREGUARD)
            EngineMode.FOXY_FIREFOX -> if (mode == EngineMode.FOXY_FIREFOX) {
                onOpenLegacy(DrawerDestination.CONFIGS)
            } else {
                toast = "اول Firefox VPN را انتخاب کنید، بعد صفحه‌ی کشور و حساب آن باز می‌شود"
            }
            else -> chainOpen = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
        ) {
            item(key = "head", contentType = "head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIconButtonFlat(Icons.AutoMirrored.Outlined.ArrowBack, onBack, description = "بازگشت")
                        Spacer(Modifier.width(4.dp))
                        SText("موتور اتصال", size = 21.sp, weight = FontWeight.Bold)
                    }
                    SText(
                        "اگر مطمئن نیستید Auto را نگه دارید؛ سیمرغ برای شبکه‌ی شما بهترین موتور را انتخاب می‌کند.",
                        size = 13.5.sp, color = sx.textDim, maxLines = 3,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp),
                    )
                }
            }
            items(EngineChoice.entries, key = { it.name }, contentType = { "choice" }) { choice ->
                val running = connected && choice.mode != null && location.engine == choice.mode &&
                    (choice.powExit == null || choice.powExit == pow.exitMode)
                ChoiceRow(
                    choice = choice,
                    selected = choice == selected,
                    running = running,
                    recommended = choice == EngineChoice.AUTO,
                    onSettings = if (choice == EngineChoice.AUTO) ({ chainOpen = !chainOpen }) else ({ settingsFor(choice.mode) }),
                ) {
                    if (!EngineChoice.apply(context, choice)) toast = "برای تغییر موتور، اول اتصال را قطع کنید"
                }
            }
            item(key = "chain", contentType = "chain") {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    Row(
                        Modifier.fillMaxWidth().sxClick { chainOpen = !chainOpen }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            SText("ترتیب و چرخه‌ی Auto", size = 15.sp, weight = FontWeight.Bold)
                            SText(
                                auto.order.filter { it in auto.included }.joinToString(" ← ") { it.fa },
                                size = 12.sp, color = sx.textDim, maxLines = 2,
                            )
                        }
                        Icon(if (chainOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = sx.textDim)
                    }
                    AnimatedVisibility(chainOpen) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).sxCard(sx)) {
                            auto.order.forEachIndexed { index, m ->
                                if (index > 0) DividerLine()
                                ChainRow(
                                    mode = m,
                                    index = index,
                                    last = index == auto.order.lastIndex,
                                    included = m in auto.included,
                                    onInclude = { on -> store.update { s -> s.copy(included = if (on) s.included + m else s.included - m) } },
                                    onMove = { delta ->
                                        store.update { s ->
                                            val list = s.order.toMutableList()
                                            val i = list.indexOf(m)
                                            val j = (i + delta).coerceIn(0, list.lastIndex)
                                            if (i != j) { list.removeAt(i); list.add(j, m) }
                                            s.copy(order = list)
                                        }
                                    },
                                )
                            }
                            DividerLine()
                            ToggleRow("حافظه‌ی موتور برای هر شبکه", "موتوری که روی هر وای‌فای یا شبکه‌ی موبایل وصل شده، اول امتحان می‌شود", auto.networkMemory) { on -> store.update { it.copy(networkMemory = on) } }
                            DividerLine()
                            ToggleRow("اتصال مجدد خودکار", "اگر تست واقعی تونل چند بار پشت‌سرهم شکست بخورد، دوباره وصل می‌شود", auto.autoReconnect) { on -> store.update { it.copy(autoReconnect = on) } }
                        }
                    }
                }
            }
            // Extra engines: Flux (inside Auto) and the network tools.
            item(key = "engine-tools") { com.uacspoofer.mobile.ui.engines.EngineToolsCard(onOpenLegacy) }
        }
        AnimatedVisibility(toast != null, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp)) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh).padding(horizontal = 16.dp, vertical = 10.dp)) {
                SText(toast.orEmpty(), size = 13.sp)
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    choice: EngineChoice,
    selected: Boolean,
    running: Boolean,
    recommended: Boolean,
    onSettings: () -> Unit,
    onClick: () -> Unit,
) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().background(sx.surface)) {
        Row(Modifier.fillMaxWidth().sxClick(onClick).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Radio(selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText(choice.title, size = 16.5.sp, weight = FontWeight.Bold, ltr = true)
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Tag("پیشنهادی", sx.accent)
                    }
                    if (running) {
                        Spacer(Modifier.width(8.dp))
                        Tag("متصل", sx.good)
                    }
                }
                SText(choice.subtitleFa, size = 12.5.sp, color = sx.textDim, maxLines = 2)
            }
            Icon(
                Icons.Outlined.Tune, "تنظیمات ${choice.title}", tint = sx.textFaint,
                modifier = Modifier.size(40.dp).clip(CircleShape).sxClick(onSettings).padding(9.dp),
            )
        }
        DividerLine()
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val sx = LocalSx.current
    Box(
        Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (selected) sx.accent else sx.textFaint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(sx.accent))
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
        SText(text, size = 11.sp, color = color, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChainRow(mode: EngineMode, index: Int, last: Boolean, included: Boolean, onInclude: (Boolean) -> Unit, onMove: (Int) -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.KeyboardArrowUp, "بالا", tint = if (index == 0) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                modifier = Modifier.size(28.dp).clip(CircleShape).sxClick { if (index > 0) onMove(-1) })
            Icon(Icons.Outlined.KeyboardArrowDown, "پایین", tint = if (last) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                modifier = Modifier.size(28.dp).clip(CircleShape).sxClick { if (!last) onMove(1) })
        }
        Spacer(Modifier.width(8.dp))
        SText("${index + 1}. ${mode.fa}", size = 14.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        SToggle(included, onInclude)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            SText(title, size = 14.5.sp, weight = FontWeight.SemiBold)
            SText(subtitle, size = 12.sp, color = sx.textDim, maxLines = 2)
        }
        SToggle(checked, onChange)
    }
}
