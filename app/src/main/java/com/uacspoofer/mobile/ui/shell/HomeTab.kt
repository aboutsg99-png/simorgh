package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import com.uacspoofer.mobile.profiles.ProfileStore
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
import com.uacspoofer.mobile.simorgh.countryName
import com.uacspoofer.mobile.simorgh.engineDisplayName
import com.uacspoofer.mobile.simorgh.servers.FastestRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Home, ExpressVPN layout: hero with three arcs and the power button, the status line, the
 * selected location, smart/recent shortcuts, the map with the exit IP, protection time and
 * the engine in use. Every card collects only what it shows, so a ticking value (the session
 * clock, a ping) recomposes one Text, never the list. Nothing here updates every second
 * except the clock while connected.
 */
@Composable
fun HomeTab(
    state: ConnectionState,
    onPrimary: () -> Unit,
    onConnect: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenEngines: () -> Unit,
    contentPadding: PaddingValues,
    topInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val prefs by remember(context) { ShellPrefsStore.get(context) }.prefsFlow.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    val cards = remember(prefs.cards, prefs.hidden) { prefs.cards.filterNot { it in prefs.hidden } }
    val listState = rememberLazyListState()
    val gap = if (sx.compact) 10.dp else 12.dp
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        item(key = "hero", contentType = "hero") {
            HomeHero(state, topInset, onPrimary) { ShellPrefsStore.get(context).toggleTheme() }
        }
        item(key = "status", contentType = "status") { StatusLine(state, location) }
        var i = 0
        while (i < cards.size) {
            val card = cards[i]
            val next = cards.getOrNull(i + 1)
            val pair = (card == HomeCard.TIME && next == HomeCard.ENGINE) || (card == HomeCard.ENGINE && next == HomeCard.TIME) ||
                (card == HomeCard.USAGE && next == HomeCard.AUTOCONNECT) || (card == HomeCard.AUTOCONNECT && next == HomeCard.USAGE)
            if (pair && next != null) {
                item(key = "pair-${card.name}", contentType = "pair") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.weight(1f)) { HomeCardContent(card, state, location, listState, onConnect, onOpenLocations, onOpenEngines) }
                        Box(Modifier.weight(1f)) { HomeCardContent(next, state, location, listState, onConnect, onOpenLocations, onOpenEngines) }
                    }
                }
                i += 2
            } else {
                item(key = card.name, contentType = card.name) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        HomeCardContent(card, state, location, listState, onConnect, onOpenLocations, onOpenEngines)
                    }
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
    listState: LazyListState,
    onConnect: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenEngines: () -> Unit,
) {
    when (card) {
        HomeCard.LOCATION -> LocationCard(state, location, onOpenLocations)
        HomeCard.SHORTCUTS -> ShortcutTiles(onConnect)
        HomeCard.MAP -> MapCard(location, listState)
        HomeCard.TIME -> ProtectedCard()
        HomeCard.ENGINE -> EngineCard(state, location, onOpenEngines)
        HomeCard.USAGE -> UsageCard()
        HomeCard.AUTOCONNECT -> AutoConnectCard()
    }
}

// ---------------------------------------------------------------- hero + status

@Composable
private fun HomeHero(state: ConnectionState, topInset: Dp, onPrimary: () -> Unit, onToggleTheme: () -> Unit) {
    val sx = LocalSx.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Proportions of the approved mockup (412 dp wide): visible hero 238, button 118.
        // The button is centred on the hero's bottom edge and drawn as a sibling ABOVE the
        // (clipped) hero layer, inside a box tall enough for the whole circle.
        val heroH = (maxWidth * 0.578f).coerceAtLeast(topInset + 150.dp)
        val button = (maxWidth * 0.29f).coerceIn(104.dp, 132.dp)
        val ring = button + 14.dp
        Box(Modifier.fillMaxWidth().height(heroH + ring / 2)) {
            HeroArcs(state, Modifier.fillMaxWidth().height(heroH))
            // App name, top centre of the hero.
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = topInset + 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.uacspoofer.mobile.R.mipmap.ic_launcher_round), null,
                    Modifier.size(30.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                SText("سیمرغ", size = 19.sp, weight = FontWeight.Bold, color = if (sx.light) Color(0xFF1B2B2A) else Color(0xFFEEF3F4))
            }
            RoundIconButtonFlat(
                Icons.Outlined.Palette,
                onToggleTheme,
                Modifier.align(Alignment.TopEnd).padding(top = topInset + 8.dp, end = 10.dp),
                "تغییر پوسته",
            )
            PowerButton(
                state = state,
                diameter = button,
                onClick = onPrimary,
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
            )
        }
    }
}

@Composable
private fun StatusLine(state: ConnectionState, location: CurrentLocation) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            ConnectionState.CONNECTED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = sx.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                SText("محافظت شده", size = 19.sp, weight = FontWeight.SemiBold)
                SText("  |  ", size = 19.sp, color = sx.textDim)
                SessionClock()
            }
            ConnectionState.CONNECTING -> {
                SText("در حال اتصال…", size = 19.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                ConnectStep(location)
            }
            ConnectionState.DISCONNECTING -> SText("در حال قطع اتصال…", size = 19.sp, weight = FontWeight.SemiBold, color = sx.textDim)
            ConnectionState.ERROR -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LockOpen, null, tint = sx.bad, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    SText("اتصال برقرار نشد", size = 19.sp, weight = FontWeight.SemiBold, color = sx.bad)
                }
                val hint by ConnectionStateStore.errorHint.collectAsStateWithLifecycle()
                SText(hint ?: "دوباره لمس کنید یا موتور دیگری انتخاب کنید", size = 13.sp, color = sx.textDim)
            }
            ConnectionState.DISCONNECTED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LockOpen, null, tint = if (sx.light) Color(0xFF9A3B36) else sx.bad, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                SText("محافظت نشده", size = 19.sp, weight = FontWeight.SemiBold, color = if (sx.light) Color(0xFF9A3B36) else sx.bad)
            }
        }
    }
}

/** HH:MM:SS of the running session. The only per-second update on home, and only this Text. */
@Composable
private fun SessionClock() {
    val context = LocalContext.current
    val days by remember(context) { ProtectedTimeStore.get(context) }.days.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val start = days.sessionStartMs
    LaunchedEffect(start) {
        // Disconnected: nothing to count, so no per-second recomposition at all.
        if (start <= 0) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    SText(if (start > 0) clock((now - start) / 1000) else "00:00:00", size = 19.sp, weight = FontWeight.SemiBold, ltr = true)
}

@Composable
private fun ConnectStep(location: CurrentLocation) {
    val sx = LocalSx.current
    val auto by AutoConnector.progress.collectAsStateWithLifecycle()
    val engine = auto.engine ?: location.engine
    val detail = when {
        engine.isTor -> TorStatusStore.status.collectAsStateWithLifecycle().value.bootstrapPercent.takeIf { it > 0 }?.let { "$it٪" }
        engine.isPow -> PowStatusStore.status.collectAsStateWithLifecycle().value.progressPercent.takeIf { it > 0 }?.let { "$it٪" }
        engine.isFoxy -> FoxyStatusStore.status.collectAsStateWithLifecycle().value.detail.takeIf { it.isNotBlank() }?.take(40)
        else -> null
    }
    val name = engineName(engine)
    val text = buildString {
        append(name)
        if (auto.active && auto.total > 1) append(" · ${auto.index} از ${auto.total}")
        if (detail != null) append(" · $detail")
    }
    SText(text, size = 13.sp, color = sx.textDim)
}

/** Real engine name, with the PoW exit mode resolved (Cloudflare WARP / Psiphon / both). */
@Composable
internal fun engineName(engine: EngineMode): String {
    if (engine != EngineMode.UAC_POW) return engineDisplayName(engine)
    val context = LocalContext.current
    val pow by remember(context) { PowEngineStore.get(context) }.settings.collectAsStateWithLifecycle()
    return engineDisplayName(engine, pow.exitMode)
}

// ---------------------------------------------------------------- cards

private fun placeLabel(code: String, city: String?): String {
    val country = countryName(code)
    return if (!city.isNullOrBlank() && country.isNotEmpty()) "$country - $city" else country
}

@Composable
private fun LocationCard(state: ConnectionState, location: CurrentLocation, onOpenLocations: () -> Unit) {
    val sx = LocalSx.current
    val code = location.displayCode
    val city = location.exit.city.takeIf { location.exitVerified && it.isNotBlank() }
    val smart = code.isEmpty()
    val title: String
    val subtitle: String
    when {
        location.selection.countryless && smart -> { title = "مستقیم (بدون سرور)"; subtitle = engineName(location.engine) }
        smart && location.selection.engine.isXray && location.selection.profileName.isNotBlank() && !location.selection.autoEngine -> {
            title = location.selection.profileName; subtitle = "کانفیگ انتخاب‌شده"
        }
        smart -> { title = "سریع‌ترین مکان"; subtitle = "مکان هوشمند" }
        else -> { title = placeLabel(code, city); subtitle = if (state == ConnectionState.CONNECTED && !location.showingSelection) "مکان فعلی" else "مکان انتخاب‌شده" }
    }
    Row(
        Modifier.fillMaxWidth().sxCard(sx).sxClick(onOpenLocations).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (smart) {
            Icon(Icons.Outlined.Public, null, tint = sx.text, modifier = Modifier.size(28.dp))
        } else {
            FlagBox(code, 24.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText(subtitle, size = 12.5.sp, color = sx.textDim)
            SText(title, size = 17.sp, weight = FontWeight.Bold)
        }
        if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            SText("تغییر", size = 15.sp, weight = FontWeight.Bold, color = sx.accent)
        } else {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = sx.textDim, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ShortcutTiles(onConnect: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val fastest by FastestRepository.state.collectAsStateWithLifecycle()
    val recent by remember(context) { RecentLocations.get(context) }.items.collectAsStateWithLifecycle()
    val best = remember(fastest) { fastest.sorted.firstOrNull { it.pingMs != null } }
    val last = recent.firstOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (sx.compact) 10.dp else 12.dp)) {
        ShortcutTile(
            title = "مکان هوشمند",
            icon = Icons.Outlined.Bolt,
            value = best?.let { countryName(it.countryCode).ifEmpty { it.title } } ?: "پیدا کردن سریع‌ترین",
            accentValue = best != null,
            modifier = Modifier.weight(1f),
        ) {
            best?.let { SelectionActions.apply(context, Target(it.engine, if (it.engine.isXray) "" else it.countryCode, it.profileId), onConnect) }
                ?: FastestRepository.pingAll(context)
        }
        val code = last?.countryCode?.ifEmpty { last.selectedCode }
        ShortcutTile(
            title = "مکان اخیر",
            icon = Icons.Outlined.Schedule,
            value = when {
                last == null -> "هنوز اتصالی نیست"
                !code.isNullOrEmpty() -> countryName(code)
                last.profileName.isNotEmpty() -> last.profileName
                else -> engineDisplayName(last.engine)
            },
            accentValue = last != null,
            modifier = Modifier.weight(1f),
        ) {
            last?.let { SelectionActions.apply(context, Target(it.engine, it.selectedCode, it.profileId), onConnect) }
        }
    }
}

@Composable
private fun ShortcutTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    accentValue: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val sx = LocalSx.current
    Column(modifier.height(84.dp).sxCard(sx).sxClick(onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText(title, size = 13.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = sx.textDim, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        SText(value, size = 14.sp, weight = FontWeight.Bold, color = if (accentValue) sx.accent else sx.textDim)
    }
}

@Composable
private fun MapCard(location: CurrentLocation, listState: LazyListState) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val home by ExitIpChecker.home.collectAsStateWithLifecycle()
    val connected = location.connection == ConnectionState.CONNECTED
    val focus = if (connected) location.displayCode.ifEmpty { home.countryCode } else home.countryCode.ifEmpty { location.displayCode }
    val view = remember(focus, home.countryCode, connected) { MapView(focus = focus, home = home.countryCode, connected = connected) }
    // Animate only while it can be seen: resumed (screen on, app in front) and not scrolling.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !scrolling
    Box(Modifier.fillMaxWidth().height(if (sx.compact) 150.dp else 168.dp).sxCard(sx).background(sx.mapSea)) {
        WorldMapCanvas(view, mapMotionFor(sx.anim, sx.mapAnim), active, Modifier.fillMaxSize())
        MapTooltip(location, home.ip, home.countryCode, home.city, Modifier.align(Alignment.Center).padding(start = 40.dp)) {
            SimorghRuntime.recheckExit(context)
        }
    }
}

@Composable
private fun MapTooltip(location: CurrentLocation, homeIp: String, homeCode: String, homeCity: String, modifier: Modifier, onRefresh: () -> Unit) {
    val sx = LocalSx.current
    val exit = location.exit
    val connected = location.connection == ConnectionState.CONNECTED
    val code = if (connected) location.displayCode else homeCode
    val city = if (connected) exit.city.takeIf { location.exitVerified && it.isNotBlank() } else homeCity.takeIf { it.isNotBlank() }
    val ip = when {
        exit.phase == ExitPhase.CHECKING -> "…"
        connected && location.exitVerified -> exit.ip
        !connected && homeIp.isNotBlank() -> homeIp
        !connected && exit.known && !exit.viaTunnel -> exit.ip
        else -> "—"
    }
    Column(
        modifier
            .widthIn(min = 150.dp, max = 230.dp)
            .sxCard(sx, RoundedCornerShape(10.dp))
            .then(if (sx.light) Modifier else Modifier.background(sx.surfaceHigh))
            .sxClick(onRefresh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        SText(placeLabel(code, city).ifEmpty { if (connected) "سریع‌ترین مکان" else "مکان شما" }, size = 13.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        SText(if (connected) "IP شما" else "IP فعلی (محافظت نشده)", size = 11.sp, color = sx.textDim)
        SText(ip, size = 14.sp, weight = FontWeight.Bold, color = if (connected) sx.accent else sx.bad, ltr = true, latin = true)
    }
}

@Immutable
private data class Bars(val values: List<Float>)

@Composable
private fun WeekBars(bars: Bars, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    val on = sx.accent
    val off = sx.stroke
    Box(
        modifier.width(64.dp).height(30.dp).drawWithCache {
            val n = bars.values.size.coerceAtLeast(1)
            val gap = 3.dp.toPx()
            val bw = (size.width - gap * (n - 1)) / n
            val radius = CornerRadius(2.dp.toPx())
            val minH = 6.dp.toPx()
            onDrawBehind {
                // Oldest on the left, today on the right (numbers read LTR, like the mockup).
                bars.values.forEachIndexed { i, v ->
                    val h = if (v <= 0f) size.height else (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(minH)
                    drawRoundRect(if (v > 0f) on else off, Offset(i * (bw + gap), size.height - h), Size(bw, h), radius)
                }
            }
        },
    )
}

@Composable
private fun ProtectedCard() {
    val context = LocalContext.current
    val sx = LocalSx.current
    val data by remember(context) { ProtectedTimeStore.get(context) }.days.collectAsStateWithLifecycle()
    // Recomputed once a minute while a session runs; nothing else ticks here.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(data.sessionStartMs) {
        now = System.currentTimeMillis()
        while (data.sessionStartMs > 0) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    val days = remember(data, now / 60_000L) { data.lastDays(now) }
    val max = remember(days) { days.maxOf { it.second }.coerceAtLeast(60L) }
    val week = remember(days) { days.sumOf { it.second } }
    Column(Modifier.fillMaxWidth().height(96.dp).sxCard(sx).padding(horizontal = 14.dp, vertical = 12.dp)) {
        SText("زمان محافظت", size = 13.sp, color = sx.textDim)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                SText(formatDurationShortFa(week), size = 15.sp, weight = FontWeight.Bold)
                SText("این هفته", size = 11.sp, color = sx.textDim)
            }
            WeekBars(remember(days, max) { Bars(days.map { it.second.toFloat() / max }) })
        }
    }
}

@Composable
private fun EngineCard(state: ConnectionState, location: CurrentLocation, onOpenEngines: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val auto by remember(context) { AutoEngineStore.get(context) }.settings.collectAsStateWithLifecycle()
    val live = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
    val engine = location.engine
    val base = if (auto.enabled && !live) "Auto" else engineName(engine)
    val protocol by produceState("", engine, location.selection.profileId, live) {
        value = if (!engine.isXray || location.selection.profileId.isEmpty()) {
            ""
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    ProfileStore(context).snapshot().allProfiles.firstOrNull { it.id == location.selection.profileId }?.protocol?.wireName?.uppercase()
                }.getOrNull().orEmpty()
            }
        }
    }
    Column(Modifier.fillMaxWidth().height(96.dp).sxCard(sx).sxClick(onOpenEngines).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("موتور اتصال", size = 13.sp, color = sx.textDim, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = sx.textDim, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        SText(if (state == ConnectionState.CONNECTED) "متصل با:" else if (auto.enabled) "انتخاب:" else "موتور:", size = 11.sp, color = sx.textDim)
        SText(if (protocol.isNotEmpty()) "$base · $protocol" else base, size = 14.5.sp, weight = FontWeight.Bold, ltr = true)
    }
}

/** Optional (hidden by default): data usage now lives on the profile tab's usage page. */
@Composable
private fun UsageCard() {
    val context = LocalContext.current
    val sx = LocalSx.current
    val usage by remember(context) { com.uacspoofer.mobile.vpn.DailyTrafficStore.get(context) }.usage.collectAsStateWithLifecycle()
    // DailyTrafficStore ticks with traffic; only the derived total is shown, so recomposition
    // stops at this card and only when today's total really changes.
    val today by remember { derivedStateOf { usage.firstOrNull { it.epochDay == com.uacspoofer.mobile.vpn.DailyTrafficStore.today() }?.totalBytes ?: 0L } }
    val (v, u) = formatBytesFa(today)
    Column(Modifier.fillMaxWidth().height(96.dp).sxCard(sx).padding(horizontal = 14.dp, vertical = 12.dp)) {
        SText("مصرف داده", size = 13.sp, color = sx.textDim)
        Spacer(Modifier.weight(1f))
        SText("$v $u", size = 15.sp, weight = FontWeight.Bold)
        SText("امروز", size = 11.sp, color = sx.textDim)
    }
}

/** Optional (hidden by default): the switches also live in settings. */
@Composable
private fun AutoConnectCard() {
    val context = LocalContext.current
    val sx = LocalSx.current
    val guard = remember(context) { com.uacspoofer.mobile.settings.NetworkGuardStore.get(context) }
    val g by guard.settings.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().height(96.dp).sxCard(sx).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SText("اتصال خودکار", size = 13.sp, color = sx.textDim)
            SText(if (g.autoConnect) "روشن" else "خاموش", size = 15.sp, weight = FontWeight.Bold)
        }
        SToggle(g.autoConnect, { runCatching { guard.setAutoConnect(it) } })
    }
}

/** "18 ساعت" / "45 دقیقه" for the week total. */
internal fun formatDurationShortFa(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "$h ساعت"
        m > 0 -> "$m دقیقه"
        else -> "۰ دقیقه"
    }
}

@Composable
internal fun RoundIconButtonFlat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val sx = LocalSx.current
    Box(modifier.size(48.dp).clip(CircleShape).sxClick(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = sx.text, modifier = Modifier.size(28.dp))
    }
}
