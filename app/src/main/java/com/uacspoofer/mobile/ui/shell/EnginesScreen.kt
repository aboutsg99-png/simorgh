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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.lerp
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

private fun EngineChoice.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    EngineChoice.AUTO -> SubEngines.AUTO_ICON
    EngineChoice.WARP -> SubEngines.NUR.icon
    EngineChoice.PSIPHON -> SubEngines.ABR.icon
    EngineChoice.PSIPHON_WARP -> Icons.Outlined.Layers
    else -> mode?.icon ?: SubEngines.AUTO_ICON
}

/**
 * «موتورها», like ExpressVPN's protocol screen and Proton's protocol list: Auto on top, then
 * one radio card per engine with its real name. The gear on each row opens that engine's own
 * settings page; the Auto card opens the Auto order and options.
 */
@Composable
internal fun EnginesScreen(onBack: () -> Unit, onOpen: (Route) -> Unit, contentPadding: PaddingValues) {
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
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2_400L); toast = null } }

    fun pick(choice: EngineChoice) {
        if (!EngineChoice.apply(context, choice)) toast = "برای تغییر موتور، اول اتصال را قطع کنید"
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head", contentType = "head") {
                Column(Modifier.fillMaxWidth()) {
                    ScreenTitle("موتورها")
                    SText(
                        "موتور، راهی است که بلک دی از فیلتر عبور می‌کند. اگر مطمئن نیستید Auto را نگه دارید.",
                        size = 13.5.sp, color = sx.textDim, maxLines = 3,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = "auto", contentType = "auto") {
                AutoCard(
                    selected = selected == EngineChoice.AUTO,
                    chain = auto.order.filter { it in auto.included }.joinToString("  →  ") { it.fa },
                    running = connected && auto.enabled,
                    onPick = { pick(EngineChoice.AUTO) },
                    onSettings = { onOpen(Route.AUTO_SETTINGS) },
                )
            }
            item(key = "list-h", contentType = "header") { SectionTitle("انتخاب دستی") }
            item(key = "list", contentType = "list") {
                Column(Modifier.fillMaxWidth().sxCard(sx)) {
                    val manual = EngineChoice.entries.filter { it != EngineChoice.AUTO }
                    manual.forEachIndexed { i, choice ->
                        if (i > 0) RowDivider(56.dp)
                        val running = connected && !auto.enabled && choice.mode != null && location.engine == choice.mode &&
                            (choice.powExit == null || choice.powExit == pow.exitMode)
                        ChoiceRow(
                            choice = choice,
                            selected = choice == selected,
                            running = running,
                            onSettings = { onOpen(settingsRouteFor(choice.mode)) },
                        ) { pick(choice) }
                    }
                }
            }
            item(key = "tools-h", contentType = "header") { SectionTitle("هوشمندی و ابزارها") }
            item(key = "tools", contentType = "tools") {
                Column(Modifier.fillMaxWidth().sxCard(sx)) {
                    SRow(Icons.Outlined.AutoAwesome, "Flux", "کشور و نسخه‌ی IP را بگویید؛ Flux بهترین کانفیگ را برای هر شبکه انتخاب می‌کند", titleLtr = true) { onOpen(Route.FLUX) }
                    RowDivider()
                    SRow(Icons.Outlined.CleaningServices, "اسکنر آی‌پی تمیز", "آی‌پی‌های سالم کلودفلر روی همین شبکه، برای کانفیگ‌های CDN") { onOpen(Route.CLEAN_IP) }
                    RowDivider()
                    SRow(Icons.Outlined.SportsEsports, "DNS بازی", "سریع‌ترین DNS؛ فقط DNS از بلک دی می‌گذرد و پینگ بازی پایین می‌ماند") { onOpen(Route.GAME_DNS) }
                }
            }
        }
        AnimatedVisibility(toast != null, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp)) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(sx.text).padding(horizontal = 16.dp, vertical = 10.dp)) {
                SText(toast.orEmpty(), size = 13.sp, color = sx.bgTop)
            }
        }
    }
}

@Composable
private fun AutoCard(selected: Boolean, chain: String, running: Boolean, onPick: () -> Unit, onSettings: () -> Unit) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().sxCard(sx, highlight = selected)) {
        Row(Modifier.fillMaxWidth().sxClick(onPick).padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Radio(selected)
            Spacer(Modifier.width(12.dp))
            IconBox(SubEngines.AUTO_ICON, size = 40.dp, filled = selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText("Auto", size = 17.sp, weight = FontWeight.Bold, ltr = true)
                    Spacer(Modifier.width(8.dp))
                    STag("پیشنهادی")
                    if (running) { Spacer(Modifier.width(6.dp)); STag("متصل", sx.good) }
                }
                SText("موتورها را به ترتیب امتحان می‌کند و بهترین را برای هر شبکه به خاطر می‌سپارد", size = 12.5.sp, color = sx.textDim, maxLines = 2)
            }
        }
        DividerLine()
        Row(Modifier.fillMaxWidth().sxClick(onSettings).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, null, tint = sx.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                SText("ترتیب و گزینه‌های Auto", size = 14.sp, weight = FontWeight.SemiBold, color = sx.accent)
                SText(chain.ifEmpty { "هیچ موتوری در Auto نیست" }, size = 12.sp, color = sx.textDim, maxLines = 2, ltr = true)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = sx.textFaint)
        }
    }
}

@Composable
private fun ChoiceRow(
    choice: EngineChoice,
    selected: Boolean,
    running: Boolean,
    onSettings: () -> Unit,
    onClick: () -> Unit,
) {
    val sx = LocalSx.current
    Row(
        Modifier.fillMaxWidth().background(if (selected) lerp(sx.surface, sx.accent, 0.06f) else Color.Transparent)
            .sxClick(onClick).padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(12.dp))
        Icon(choice.icon(), null, tint = if (selected) sx.accent else sx.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SText(choice.title, size = 15.5.sp, weight = FontWeight.Bold, ltr = true)
                if (running) { Spacer(Modifier.width(8.dp)); STag("متصل", sx.good) }
            }
            SText(choice.subtitleFa, size = 12.sp, color = sx.textDim, maxLines = 2)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).sxClick(onSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Settings, "تنظیمات ${choice.title}", tint = sx.textDim, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val sx = LocalSx.current
    Box(
        Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) sx.accent else sx.textFaint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(sx.accent))
    }
}

/** «ترتیب و گزینه‌های Auto»: which engines Auto may try, in what order, and its two options. */
@Composable
internal fun AutoSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val store = remember(context) { AutoEngineStore.get(context) }
    val auto by store.settings.collectAsStateWithLifecycle()
    SubPage("تنظیمات Auto", onBack, subtitle = "ترتیب امتحان موتورها و رفتار هنگام قطعی") {
        item(key = "on") {
            SGroup {
                SToggleRow(SubEngines.AUTO_ICON, "Auto روشن باشد", "با هر اتصال، بهترین موتور به‌صورت خودکار انتخاب می‌شود", auto.enabled) { on ->
                    if (on) EngineChoice.apply(context, EngineChoice.AUTO) else store.update { it.copy(enabled = false) }
                }
            }
        }
        item(key = "order") {
            SGroup("ترتیب امتحان", footer = "موتورهای خاموش در Auto امتحان نمی‌شوند. با فلش‌ها ترتیب را عوض کنید.") {
                auto.order.forEachIndexed { index, m ->
                    if (index > 0) RowDivider(16.dp)
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
            }
        }
        item(key = "opts") {
            SGroup("گزینه‌ها") {
                SToggleRow(Icons.Outlined.Wifi, "حافظه‌ی موتور برای هر شبکه", "موتوری که روی هر وای‌فای یا شبکه‌ی موبایل وصل شده، اول امتحان می‌شود", auto.networkMemory) { on -> store.update { it.copy(networkMemory = on) } }
                RowDivider()
                SToggleRow(Icons.Outlined.Autorenew, "اتصال مجدد خودکار", "اگر تست واقعی تونل چند بار پشت‌سرهم شکست بخورد، دوباره وصل می‌شود", auto.autoReconnect) { on -> store.update { it.copy(autoReconnect = on) } }
            }
        }
    }
}

@Composable
private fun ChainRow(mode: EngineMode, index: Int, last: Boolean, included: Boolean, onInclude: (Boolean) -> Unit, onMove: (Int) -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.KeyboardArrowUp, "بالا", tint = if (index == 0) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                modifier = Modifier.size(30.dp).clip(CircleShape).sxClick { if (index > 0) onMove(-1) })
            Icon(Icons.Outlined.KeyboardArrowDown, "پایین", tint = if (last) sx.textFaint.copy(alpha = 0.3f) else sx.textDim,
                modifier = Modifier.size(30.dp).clip(CircleShape).sxClick { if (!last) onMove(1) })
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (included) sx.accent.copy(alpha = 0.14f) else sx.surfaceHigh), contentAlignment = Alignment.Center) {
            SText("${index + 1}", size = 12.sp, weight = FontWeight.Bold, color = if (included) sx.accent else sx.textFaint)
        }
        Spacer(Modifier.width(10.dp))
        Icon(mode.icon, null, tint = if (included) sx.text else sx.textFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        SText(mode.fa, size = 14.5.sp, weight = FontWeight.SemiBold, color = if (included) sx.text else sx.textFaint, modifier = Modifier.weight(1f), ltr = true)
        SToggle(included, onInclude)
    }
}
