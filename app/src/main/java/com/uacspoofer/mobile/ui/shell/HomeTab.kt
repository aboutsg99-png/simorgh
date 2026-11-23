package com.uacspoofer.mobile.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import com.uacspoofer.mobile.extras.ExtrasSettingsStore
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.simorgh.CurrentLocation
import com.uacspoofer.mobile.simorgh.ExitIpChecker
import com.uacspoofer.mobile.simorgh.ExitPhase
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.ProtectedTimeStore
import com.uacspoofer.mobile.simorgh.RecentLocations
import com.uacspoofer.mobile.simorgh.SelectionActions
import com.uacspoofer.mobile.simorgh.SimorghRuntime
import com.uacspoofer.mobile.simorgh.Target
import com.uacspoofer.mobile.simorgh.auto.AutoConnector
import com.uacspoofer.mobile.simorgh.auto.AutoEngineStore
import com.uacspoofer.mobile.simorgh.auto.AutoProgress
import com.uacspoofer.mobile.simorgh.countryName
import com.uacspoofer.mobile.simorgh.servers.FastestRepository
import com.uacspoofer.mobile.vpn.ConnectionMetricsStore
import com.uacspoofer.mobile.vpn.DailyTrafficStore
import kotlinx.coroutines.delay

@Composable
fun HomeTab(
    state: ConnectionState,
    onPrimary: () -> Unit,
    onConnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenPersonalize: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEngines: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val prefs by remember(context) { ShellPrefsStore.get(context) }.prefsFlow.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    val auto by AutoConnector.progress.collectAsStateWithLifecycle()
    val cards = remember(prefs.cards, prefs.hidden) { prefs.cards.filterNot { it in prefs.hidden } }
    val gap = if (sx.compact) 10.dp else 14.dp
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        item(key = "header", contentType = "header") { HomeHeader(onOpenPersonalize, onOpenSettings) }
        item(key = "power", contentType = "power") { PowerSection(state, auto, location, onPrimary) }
        var i = 0
        while (i < cards.size) {
            val card = cards[i]
            val next = cards.getOrNull(i + 1)
            val pair = (card == HomeCard.USAGE && next == HomeCard.TIME) || (card == HomeCard.TIME && next == HomeCard.USAGE) ||
                (card == HomeCard.ENGINE && next == HomeCard.AUTOCONNECT) || (card == HomeCard.AUTOCONNECT && next == HomeCard.ENGINE)
            if (pair && next != null) {
                item(key = "pair-${card.name}", contentType = "pair") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 10.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.weight(1f)) { HomeCardContent(card, state, location, onPrimary, onConnect, onOpenServers, onOpenEngines) }
                        Box(Modifier.weight(1f)) { HomeCardContent(next, state, location, onPrimary, onConnect, onOpenServers, onOpenEngines) }
                    }
                }
                i += 2
            } else {
                item(key = card.name, contentType = card.name) {
                    HomeCardContent(card, state, location, onPrimary, onConnect, onOpenServers, onOpenEngines)
                }
                i += 1
            }
        }
    }
}

@Composable
private fun HomeCardContent(
    card: HomeCard,
    state: ConnectionState,
    location: CurrentLocation,
    onPrimary: () -> Unit,
    onConnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenEngines: () -> Unit,
) {
    when (card) {
        HomeCard.LOCATION -> LocationCard(state, location, onPrimary, onOpenServers)
        HomeCard.SHORTCUTS -> ShortcutTiles(location, onConnect)
        HomeCard.MAP -> MapCard(location)
        HomeCard.USAGE -> UsageCard()
        HomeCard.TIME -> ProtectedCard()
        HomeCard.ENGINE -> EngineCard(location, onOpenEngines)
        HomeCard.AUTOCONNECT -> AutoConnectCard()
    }
}

@Composable
private fun HomeHeader(onOpenPersonalize: () -> Unit, onOpenSettings: () -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Outlined.Settings, onOpenSettings)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            SText("سیمرغ", size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Image(painterResource(R.mipmap.ic_launcher_round), null, Modifier.size(34.dp).clip(CircleShape))
        }
        RoundIconButton(Icons.Outlined.Palette, onOpenPersonalize)
    }
}

@Composable
private fun PowerSection(state: ConnectionState, auto: AutoProgress, location: CurrentLocation, onPrimary: () -> Unit) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val d = (maxWidth * if (sx.compact) 0.42f else 0.48f).coerceAtMost(210.dp)
            PowerButton(state, d, onPrimary)
        }
        val key = when (state) {
            ConnectionState.CONNECTED -> 2
            ConnectionState.CONNECTING -> 1
            ConnectionState.DISCONNECTING -> 3
            ConnectionState.ERROR -> 4
            ConnectionState.DISCONNECTED -> 0
        }
        AnimatedContent(
            targetState = key,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "status",
        ) { k ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (k) {
                    2 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SText("متصل", size = 26.sp, weight = FontWeight.Bold, color = sx.accent)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Outlined.CheckCircle, null, tint = sx.accent, modifier = Modifier.size(24.dp))
                        }
                        ConnectedClock()
                        val where = countryName(location.connectedCode).takeIf { it.isNotEmpty() }
                        SText(
                            "از طریق موتور ${location.engine.fa}" + (where?.let { " · $it" } ?: ""),
                            size = 13.5.sp, color = sx.textDim,
                        )
                    }
                    1 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SText("در حال اتصال…", size = 24.sp, weight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Outlined.Sync, null, tint = sx.accent, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        ConnectStep(auto, location)
                    }
                    3 -> SText("در حال قطع…", size = 24.sp, weight = FontWeight.Bold, color = sx.warn)
                    4 -> {
                        SText("اتصال برقرار نشد", size = 22.sp, weight = FontWeight.Bold, color = sx.bad)
                        SText("دوباره لمس کنید یا موتور دیگری انتخاب کنید", size = 13.sp, color = sx.textDim)
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SText("محافظت نشده", size = 24.sp, weight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Outlined.Lock, null, tint = sx.text, modifier = Modifier.size(22.dp))
                        }
                        SText("برای اتصال، دکمه را لمس کنید", size = 13.sp, color = sx.textDim)
                    }
                }
            }
        }
    }
}

/** HH:MM:SS of the running session. Ticks once a second, only while shown. */
@Composable
private fun ConnectedClock() {
    val context = LocalContext.current
    val days by remember(context) { ProtectedTimeStore.get(context) }.days.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(days.sessionStartMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    val start = days.sessionStartMs
    SText(if (start > 0) clock((now - start) / 1000) else "00:00:00", size = 30.sp, weight = FontWeight.Bold, ltr = true)
}

@Composable
private fun ConnectStep(auto: AutoProgress, location: CurrentLocation) {
    val sx = LocalSx.current
    val engine = auto.engine ?: location.engine
    val detail = when {
        engine.isTor -> TorStatusStore.status.collectAsStateWithLifecycle().value.bootstrapPercent.takeIf { it > 0 }?.let { "$it٪" }
        engine.isPow -> PowStatusStore.status.collectAsStateWithLifecycle().value.progressPercent.takeIf { it > 0 }?.let { "$it٪" }
        engine.isFoxy -> FoxyStatusStore.status.collectAsStateWithLifecycle().value.detail.takeIf { it.isNotBlank() }?.take(40)
        else -> null
    }
    val text = buildString {
        append("تلاش با ${engine.fa}")
        if (auto.active && auto.total > 1) append(" ${auto.index} از ${auto.total}")
        if (detail != null) append(" · $detail")
    }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(sx.surface).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (auto.active && auto.total > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (n in 1..auto.total) {
                    val c = when {
                        n < auto.index -> sx.textFaint
                        n == auto.index -> sx.accent
                        else -> sx.stroke
                    }
                    Box(Modifier.size(if (n == auto.index) 9.dp else 6.dp).background(c, CircleShape))
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        SText(text, size = 13.sp, color = sx.textDim)
    }
}

@Composable
private fun LivePingPill(location: CurrentLocation) {
    val metrics by ConnectionMetricsStore.metrics.collectAsStateWithLifecycle()
    val fastest by FastestRepository.state.collectAsStateWithLifecycle()
    val ms = if (location.connection == ConnectionState.CONNECTED) {
        metrics.latencyMs
    } else {
        // Last real measurement of the selected server, if one exists.
        val sel = location.selection
        fastest.rows.firstOrNull {
            (sel.engine.isXray && it.profileId == sel.profileId && it.profileId.isNotEmpty()) ||
                (sel.engine.isFoxy && it.source == com.uacspoofer.mobile.simorgh.servers.RowSource.RUBAH && it.countryCode == sel.countryCode)
        }?.pingMs
    }
    if (ms != null || location.connection == ConnectionState.CONNECTED) PingPill(ms, measuring = metrics.isMeasuringLatency)
}

@Composable
private fun LocationCard(state: ConnectionState, location: CurrentLocation, onPrimary: () -> Unit, onOpenServers: () -> Unit) {
    val sx = LocalSx.current
    val code = location.displayCode
    val label = when {
        state == ConnectionState.CONNECTED && !location.showingSelection -> "مکان فعلی"
        code.isEmpty() -> if (location.selection.autoEngine) "انتخاب خودکار" else "سریع‌ترین مکان"
        else -> "مکان انتخابی"
    }
    val city = location.exit.city.takeIf { location.exitVerified && it.isNotBlank() }
    val title = when {
        location.selection.countryless && code.isEmpty() -> "مستقیم (بدون سرور)"
        code.isEmpty() -> if (location.selection.engine.isXray && location.selection.profileName.isNotBlank()) location.selection.profileName else "سریع‌ترین سرور"
        city != null -> "${countryName(code)} · $city"
        else -> countryName(code)
    }
    Row(
        Modifier.fillMaxWidth().sxCard(sx).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBox(code, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText(label, size = 12.5.sp, color = sx.textDim)
            SText(title, size = 17.sp, weight = FontWeight.Bold)
        }
        LivePingPill(location)
        Spacer(Modifier.width(8.dp))
        when (state) {
            ConnectionState.CONNECTING -> SChip("لغو", onClick = onPrimary)
            ConnectionState.CONNECTED -> SChip("قطع", icon = Icons.Outlined.PowerSettingsNew, color = sx.bad, onClick = onPrimary)
            else -> SChip("تغییر", icon = Icons.Outlined.Tune, selected = true, onClick = onOpenServers)
        }
    }
}

@Composable
private fun ShortcutTiles(location: CurrentLocation, onConnect: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val fastest by FastestRepository.state.collectAsStateWithLifecycle()
    val recent by remember(context) { RecentLocations.get(context) }.items.collectAsStateWithLifecycle()
    val best = remember(fastest) { fastest.sorted.firstOrNull { it.pingMs != null } }
    val last = recent.firstOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (sx.compact) 10.dp else 14.dp)) {
        Column(
            Modifier.weight(1f).sxCard(sx).sxClick {
                best?.let { SelectionActions.apply(context, Target(it.engine, it.countryCode, it.profileId), onConnect) }
                    ?: FastestRepository.pingAll(context)
            }.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SText("مکان هوشمند", size = 12.5.sp, color = sx.textDim, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Bolt, null, tint = sx.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagBox(best?.countryCode, 18.dp)
                Spacer(Modifier.width(8.dp))
                SText(best?.let { countryName(it.countryCode).ifEmpty { it.title } } ?: "پینگ بگیرید", size = 16.sp, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            SText(if (best != null) "بهترین پینگ از اینترنت شما · ${best.pingMs} ms" else "برای یافتن سریع‌ترین، لمس کنید", size = 12.sp, color = sx.textDim)
        }
        Column(
            Modifier.weight(1f).sxCard(sx).sxClick {
                last?.let {
                    SelectionActions.apply(context, Target(it.engine, it.selectedCode, it.profileId), onConnect)
                }
            }.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SText("مکان اخیر", size = 12.5.sp, color = sx.textDim, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.History, null, tint = sx.textDim, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val code = last?.countryCode?.ifEmpty { last.selectedCode }
                FlagBox(code, 18.dp)
                Spacer(Modifier.width(8.dp))
                SText(
                    when {
                        last == null -> "—"
                        !code.isNullOrEmpty() -> countryName(code)
                        last.profileName.isNotEmpty() -> last.profileName
                        else -> "خودکار"
                    },
                    size = 16.sp, weight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(4.dp))
            SText(last?.let { "${relativeDayFa(it.atMs)} · موتور ${it.engine.fa}" } ?: "هنوز اتصالی ثبت نشده", size = 12.sp, color = sx.textDim)
        }
    }
}

@Composable
private fun MapCard(location: CurrentLocation) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val home by ExitIpChecker.home.collectAsStateWithLifecycle()
    val connected = location.connection == ConnectionState.CONNECTED
    val focus = location.displayCode.ifEmpty { home.countryCode }
    val view = MapView(focus = focus, home = home.countryCode, connected = connected && focus != home.countryCode, animate = sx.anim == AnimLevel.FULL)
    Box(Modifier.fillMaxWidth().height(if (sx.compact) 200.dp else 236.dp).sxCard(sx)) {
        WorldMapCanvas(view, Modifier.fillMaxSize())
        // Status chip (top start in RTL = top right).
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(14.dp))
                .background(sx.bgBottom.copy(alpha = 0.82f)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(if (connected) sx.good else sx.textFaint, CircleShape))
            Spacer(Modifier.width(6.dp))
            SText(if (connected) "متصل · ${location.engine.fa}" else "قطع · ${location.selection.engine.fa}", size = 12.5.sp)
        }
        MapTooltip(location, Modifier.align(Alignment.BottomEnd).padding(12.dp)) { SimorghRuntime.recheckExit(context) }
    }
}

@Composable
private fun MapTooltip(location: CurrentLocation, modifier: Modifier, onRefresh: () -> Unit) {
    val sx = LocalSx.current
    val exit = location.exit
    val code = location.displayCode
    val connected = location.connection == ConnectionState.CONNECTED
    Column(
        modifier.width(220.dp).clip(RoundedCornerShape(16.dp)).background(sx.bgBottom.copy(alpha = 0.9f)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagBox(code, 16.dp)
            Spacer(Modifier.width(8.dp))
            val city = exit.city.takeIf { location.exitVerified && it.isNotBlank() }
            SText(
                (countryName(code).ifEmpty { "سریع‌ترین" }) + (city?.let { "، $it" } ?: ""),
                size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.Refresh, "بررسی دوباره", tint = sx.textDim,
                modifier = Modifier.size(28.dp).clip(CircleShape).sxClick(onRefresh).padding(5.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText(if (connected) "IP وی‌پی‌ان" else "IP شما", size = 12.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            val ip = when {
                exit.phase == ExitPhase.CHECKING -> "در حال بررسی…"
                connected && location.exitVerified -> exit.ip
                !connected && exit.known && !exit.viaTunnel -> exit.ip
                else -> "—"
            }
            SText(ip, size = 12.5.sp, weight = FontWeight.SemiBold, ltr = true)
        }
        if (connected && location.exitVerified && location.selection.countryCode.isNotEmpty() &&
            location.selection.countryCode != exit.countryCode
        ) {
            SText("خروجی واقعی با کشور انتخابی فرق دارد", size = 11.sp, color = sx.warn)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("پینگ واقعی", size = 12.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            if (connected) {
                val metrics by ConnectionMetricsStore.metrics.collectAsStateWithLifecycle()
                PingPill(metrics.latencyMs, measuring = metrics.isMeasuringLatency)
            } else {
                SText("—", size = 12.sp, color = sx.textFaint)
            }
        }
    }
}

@Immutable
private data class Bars(val values: List<Float>, val labels: List<String>)

@Composable
private fun BarStrip(bars: Bars) {
    val sx = LocalSx.current
    val accent = sx.accent
    val dim = sx.accent.copy(alpha = 0.32f)
    Column {
        Box(
            Modifier.fillMaxWidth().height(46.dp).drawBehind {
                val n = bars.values.size.coerceAtLeast(1)
                val slot = size.width / n
                val bw = slot * 0.62f
                bars.values.forEachIndexed { i, v ->
                    // RTL: newest (today) on the left like the mockup.
                    val x = size.width - (i + 1) * slot + (slot - bw) / 2
                    val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(5.dp.toPx())
                    drawRoundRect(
                        if (i == bars.values.lastIndex) accent else dim,
                        topLeft = Offset(x, size.height - h), size = Size(bw, h), cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            bars.labels.forEach { SText(it, size = 11.sp, color = sx.textFaint, modifier = Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

@Composable
private fun UsageCard() {
    val context = LocalContext.current
    val sx = LocalSx.current
    val usage by remember(context) { DailyTrafficStore.get(context) }.usage.collectAsStateWithLifecycle()
    val days = remember(usage) {
        val today = DailyTrafficStore.today()
        val byDay = usage.associateBy { it.epochDay }
        (6 downTo 0).map { back -> (today - back) to (byDay[today - back]?.totalBytes ?: 0L) }
    }
    val max = days.maxOf { it.second }.coerceAtLeast(1L)
    val today = days.last().second
    val total = days.sumOf { it.second }
    val (v, u) = formatBytesFa(today)
    val (tv, tu) = formatBytesFa(total)
    Column(Modifier.fillMaxWidth().sxCard(sx).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("مصرف داده", size = 12.5.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = sx.textFaint, modifier = Modifier.size(18.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            SText(v, size = 26.sp, weight = FontWeight.Bold, ltr = true)
            Spacer(Modifier.width(4.dp))
            SText(u, size = 13.sp, color = sx.textDim)
        }
        SText("امروز · ۷ روز اخیر $tv $tu", size = 11.5.sp, color = sx.textDim)
        Spacer(Modifier.height(10.dp))
        BarStrip(Bars(days.map { it.second.toFloat() / max }, days.map { weekdayFa(it.first) }))
    }
}

@Composable
private fun ProtectedCard() {
    val context = LocalContext.current
    val sx = LocalSx.current
    val data by remember(context) { ProtectedTimeStore.get(context) }.days.collectAsStateWithLifecycle()
    // Recomputed once a minute while visible; the running session counts too.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(data.sessionStartMs) {
        while (data.sessionStartMs > 0) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    val days = remember(data, now) { data.lastDays(now) }
    val max = days.maxOf { it.second }.coerceAtLeast(60L)
    val today = days.last().second
    val avg = days.map { it.second }.average().toLong()
    Column(Modifier.fillMaxWidth().sxCard(sx).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("زمان محافظت", size = 12.5.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = sx.textFaint, modifier = Modifier.size(18.dp))
        }
        SText(formatDurationFa(today), size = 20.sp, weight = FontWeight.Bold)
        SText("امروز · میانگین ${formatDurationFa(avg)}", size = 11.5.sp, color = sx.textDim)
        Spacer(Modifier.height(10.dp))
        BarStrip(Bars(days.map { it.second.toFloat() / max }, days.map { weekdayFa(it.first) }))
    }
}

@Composable
private fun EngineCard(location: CurrentLocation, onOpenEngines: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val auto by remember(context) { AutoEngineStore.get(context) }.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().sxCard(sx).sxClick(onOpenEngines).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("موتور اتصال", size = 12.5.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = sx.textFaint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText(if (auto.enabled) "خودکار" else location.engine.fa, size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Icon(if (auto.enabled) SubEngines.AUTO_ICON else location.engine.icon, null, tint = sx.accent, modifier = Modifier.size(22.dp))
        }
        SText("انتخاب خودکار · ${if (auto.enabled) "روشن" else "خاموش"}", size = 11.5.sp, color = sx.textDim)
        Spacer(Modifier.height(10.dp))
        val list = auto.order.filter { it in auto.included }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.take(3).forEach { SChip(it.fa, selected = it == location.engine) }
            if (list.size > 3) SChip("+${list.size - 3}")
        }
    }
}

@Composable
private fun AutoConnectCard() {
    val context = LocalContext.current
    val sx = LocalSx.current
    val guard = remember(context) { NetworkGuardStore.get(context) }
    val g by guard.settings.collectAsStateWithLifecycle()
    val extras = remember(context) { ExtrasSettingsStore.get(context) }
    val e by extras.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().sxCard(sx).padding(14.dp)) {
        SText("اتصال خودکار", size = 12.5.sp, color = sx.textDim)
        Spacer(Modifier.height(4.dp))
        SText(if (g.autoConnect) "روشن" else "خاموش", size = 20.sp, weight = FontWeight.Bold)
        SText("هنگام روشن شدن گوشی", size = 11.5.sp, color = sx.textDim)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("روشن شدن", size = 12.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            SToggle(g.autoConnect, { runCatching { guard.setAutoConnect(it) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("Wi-Fi عمومی", size = 12.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            SToggle(e.untrustedWifiAutoConnect, { runCatching { extras.setUntrustedWifiAutoConnect(it) } })
        }
    }
}
