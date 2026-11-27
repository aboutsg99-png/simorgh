package com.uacspoofer.mobile.ui.shell

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.engine.tor.TorExitCountry
import com.uacspoofer.mobile.simorgh.CurrentLocation
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.SelectionActions
import com.uacspoofer.mobile.simorgh.Target
import com.uacspoofer.mobile.simorgh.countryName
import com.uacspoofer.mobile.simorgh.servers.FastRow
import com.uacspoofer.mobile.simorgh.servers.FastestRepository
import com.uacspoofer.mobile.simorgh.servers.RowSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Favourite country codes, persisted (read off the main thread). */
internal object FavouriteCountries {
    private const val PREFS = "simorgh_favourites_v1"
    fun read(context: Context): Set<String> =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("codes", emptySet())?.toSet() }
            .getOrNull() ?: emptySet()
    fun write(context: Context, codes: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("codes", codes).apply()
    }
}

/** One country with every way out we know for it. Built off the main thread. */
@Immutable
internal data class PlaceGroup(val code: String, val name: String, val rows: List<FastRow>, val engines: List<EngineMode>) {
    val best: FastRow? get() = rows.firstOrNull { it.pingMs != null }
    val bestMs: Long? get() = best?.pingMs
}

@Immutable
private data class PickerModel(
    val groups: List<PlaceGroup> = emptyList(),
    val suggested: List<FastRow> = emptyList(),
    val firefox: List<FastRow> = emptyList(),
    val configs: List<FastRow> = emptyList(),
)

private fun buildModel(rows: List<FastRow>): PickerModel {
    val measured = rows.filter { it.pingMs != null }.sortedBy { it.pingMs }
    val byCode = rows.filter { it.countryCode.length == 2 }.groupBy { it.countryCode }
    val engineCodes = HashMap<String, MutableList<EngineMode>>()
    TorExitCountry.RECOMMENDED.forEach { engineCodes.getOrPut(it.uppercase()) { mutableListOf() } += EngineMode.TOR_WEBTUNNEL }
    runCatching { PowRegions.options(emptyList()) }.getOrDefault(emptyList())
        .forEach { engineCodes.getOrPut(it.uppercase()) { mutableListOf() } += EngineMode.UAC_POW }
    val groups = (byCode.keys + engineCodes.keys).distinct().map { code ->
        PlaceGroup(
            code = code,
            name = countryName(code),
            rows = byCode[code].orEmpty().sortedWith(compareBy({ it.pingMs == null }, { it.pingMs ?: Long.MAX_VALUE })),
            engines = engineCodes[code].orEmpty(),
        )
    }.sortedWith(compareBy({ it.bestMs == null }, { it.bestMs ?: Long.MAX_VALUE }, { it.name }))
    return PickerModel(
        groups = groups,
        suggested = measured.distinctBy { it.countryCode.ifEmpty { it.key } }.take(5),
        firefox = rows.filter { it.source == RowSource.RUBAH }.sortedWith(compareBy({ it.pingMs == null }, { it.pingMs ?: Long.MAX_VALUE })),
        configs = rows.filter { it.source == RowSource.CONFIG || it.source == RowSource.SUBSCRIPTION }
            .sortedWith(compareBy({ it.pingMs == null }, { it.pingMs ?: Long.MAX_VALUE })).take(30),
    )
}

private enum class PickTab(val fa: String) { ALL("همه"), FAVOURITES("علاقه‌مندی‌ها"), RECENT("اخیر") }

/**
 * Location picker (ExpressVPN / Proton style): search, the smart location on top, the fastest
 * few, then every country as one row with its flag and best ping. A country with more than one
 * server expands to list them. Grouping and sorting run on a background dispatcher.
 */
@Composable
internal fun LocationsScreen(
    state: ConnectionState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onManage: () -> Unit,
    topInset: Dp,
    bottomInset: Dp,
    initialTab: Int = 0,
) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val fastest by FastestRepository.state.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    val recentItems by remember(context) { com.uacspoofer.mobile.simorgh.RecentLocations.get(context) }.items.collectAsStateWithLifecycle()
    val home by com.uacspoofer.mobile.simorgh.ExitIpChecker.home.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PickTab.entries[initialTab.coerceIn(0, PickTab.entries.lastIndex)]) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf("") }
    var favourites by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        favourites = withContext(Dispatchers.IO) { FavouriteCountries.read(context) }
        FastestRepository.loadCached(context)
        if (System.currentTimeMillis() - FastestRepository.state.value.lastRunMs > 10 * 60_000L) FastestRepository.pingAll(context)
    }
    // Measurements run only while the picker is open.
    DisposableEffect(Unit) { onDispose { FastestRepository.cancel() } }
    val model by produceState(PickerModel(), fastest.rows) {
        value = withContext(Dispatchers.Default) { buildModel(fastest.rows) }
    }
    val q = query.trim()
    val filtered = remember(model, q) {
        if (q.isEmpty()) model.groups else model.groups.filter {
            it.name.contains(q, true) || countryName(it.code, false).contains(q, true) || it.code.equals(q, true) ||
                it.rows.any { r -> r.title.contains(q, true) }
        }
    }
    val recent = remember(recentItems, home.countryCode) { recentPlaces(recentItems, home.countryCode) }
    val connected = location.connection == ConnectionState.CONNECTED
    fun pick(target: Target) { SelectionActions.apply(context, target, onConnect); onBack() }
    fun pickRow(row: FastRow) = pick(Target(row.engine, if (row.engine.isXray) "" else row.countryCode, row.profileId))
    fun pickGroup(g: PlaceGroup) {
        val best = g.best ?: g.rows.firstOrNull()
        if (best != null) pickRow(best) else g.engines.firstOrNull()?.let { pick(Target(it, g.code)) }
    }
    fun toggleStar(code: String) {
        favourites = if (code in favourites) favourites - code else favourites + code
        FavouriteCountries.write(context, favourites)
    }
    fun groupActive(g: PlaceGroup) = connected && location.displayCode.equals(g.code, true)
    val smart = model.suggested.firstOrNull()

    Column(Modifier.fillMaxSize().background(sx.bgTop)) {
        PageHeader("انتخاب مکان", onBack, modifier = Modifier.padding(top = topInset)) {
            RoundIconButtonFlat(Icons.Outlined.Add, onManage, description = "افزودن کانفیگ یا اشتراک")
        }
        SearchBox(query, { query = it }, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        SSegmented(PickTab.entries, tab, { it.fa }, Modifier.padding(horizontal = 16.dp)) { tab = it }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                tab == PickTab.RECENT -> {
                    if (recent.isEmpty()) item(key = "r-empty") { EmptyState("هنوز به جایی وصل نشده‌اید.") }
                    else item(key = "recent") {
                        Column(Modifier.fillMaxWidth().sxCard(sx)) {
                            recent.forEachIndexed { i, r ->
                                if (i > 0) RowDivider(58.dp)
                                PlaceRow(
                                    code = r.code, title = r.title,
                                    subtitle = r.entry.engine.fa + " · " + relativeDayFa(r.entry.atMs),
                                    pingMs = null, measuring = false, favourite = r.code in favourites,
                                    active = false, onStar = if (r.code.isNotEmpty()) ({ toggleStar(r.code) }) else null,
                                ) { pick(Target(r.entry.engine, r.entry.selectedCode, r.entry.profileId)) }
                            }
                        }
                    }
                }
                else -> {
                    if (q.isEmpty() && tab == PickTab.ALL) {
                        item(key = "smart", contentType = "smart") {
                            SmartCard(smart, fastest.running) {
                                if (smart != null) pickRow(smart) else FastestRepository.pingAll(context)
                            }
                        }
                        if (model.suggested.size > 1) {
                            item(key = "h-fast", contentType = "header") { ListHeader("پرسرعت‌ها", if (fastest.lastRunMs > 0) timeAgoFa(fastest.lastRunMs) else null) }
                            item(key = "fast", contentType = "card") {
                                Column(Modifier.fillMaxWidth().sxCard(sx)) {
                                    model.suggested.forEachIndexed { i, row ->
                                        if (i > 0) RowDivider(58.dp)
                                        PlaceRow(
                                            row.countryCode, rowTitle(row), sourceLabel(row), row.pingMs, row.measuring,
                                            row.countryCode in favourites, rowActive(location, row),
                                            onStar = if (row.countryCode.length == 2) ({ toggleStar(row.countryCode) }) else null,
                                        ) { pickRow(row) }
                                    }
                                }
                            }
                        }
                    }
                    val list = if (tab == PickTab.FAVOURITES) filtered.filter { it.code in favourites } else filtered
                    item(key = "h-all", contentType = "header") {
                        ListHeader(if (tab == PickTab.FAVOURITES) "کشورهای دلخواه" else "همه‌ی کشورها", "${list.size} کشور")
                    }
                    if (list.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            EmptyState(
                                when {
                                    tab == PickTab.FAVOURITES && q.isEmpty() -> "با ستاره، کشورهای دلخواه را اینجا نگه دارید."
                                    fastest.running -> "در حال پینگ گرفتن…"
                                    else -> "مکانی پیدا نشد."
                                },
                            )
                        }
                    }
                    items(list, key = { "g:" + it.code }, contentType = { "group" }) { g ->
                        CountryCard(
                            g = g,
                            favourite = g.code in favourites,
                            active = groupActive(g),
                            open = expanded == g.code,
                            onToggle = { expanded = if (expanded == g.code) "" else g.code },
                            onStar = { toggleStar(g.code) },
                            onPick = { pickGroup(g) },
                            onRow = { pickRow(it) },
                            onEngine = { pick(Target(it, g.code)) },
                            rowActive = { rowActive(location, it) },
                        )
                    }
                }
            }
        }
    }
}

/** Recent places without the user's own country and without duplicates. */
internal fun recentPlaces(items: List<com.uacspoofer.mobile.simorgh.RecentLocation>, homeCode: String): List<RecentPlace> {
    val out = ArrayList<RecentPlace>()
    var rest = items
    while (rest.isNotEmpty() && out.size < 6) {
        val p = recentPlace(rest, homeCode) ?: break
        if (out.none { it.code == p.code && it.title == p.title }) out += p
        rest = rest.drop(rest.indexOf(p.entry) + 1)
    }
    return out
}

private fun rowTitle(row: FastRow): String = countryName(row.countryCode).ifEmpty { row.title }

private fun sourceLabel(row: FastRow): String = when (row.source) {
    RowSource.CONFIG, RowSource.SUBSCRIPTION -> "Xray · " + row.title
    RowSource.RUBAH -> "Firefox VPN"
    RowSource.LIVE -> "اتصال فعلی"
}

private fun rowActive(location: CurrentLocation, row: FastRow): Boolean {
    if (location.connection != ConnectionState.CONNECTED || location.engine != row.engine) return false
    return when (row.source) {
        RowSource.CONFIG, RowSource.SUBSCRIPTION -> row.profileId == location.selection.profileId
        RowSource.RUBAH -> row.countryCode.equals(location.selection.countryCode, true)
        RowSource.LIVE -> true
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    Row(modifier.fillMaxWidth().sxCard(sx).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = sx.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) SText("جستجوی کشور یا کانفیگ", color = sx.textFaint, size = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = Vazir, fontSize = 15.sp * sx.textScale, color = sx.text),
                cursorBrush = SolidColor(sx.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Outlined.Close, "پاک کردن", tint = sx.textDim, modifier = Modifier.size(32.dp).clip(CircleShape).sxClick { onChange("") }.padding(5.dp))
        }
    }
}

@Composable
private fun SmartCard(best: FastRow?, running: Boolean, onClick: () -> Unit) {
    val sx = LocalSx.current
    Row(
        Modifier.fillMaxWidth().sxCard(sx, highlight = true).sxClick(onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(sx.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Bolt, null, tint = sx.onAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText("مکان هوشمند", size = 16.sp, weight = FontWeight.Bold)
            SText(
                best?.let { "سریع‌ترین: " + rowTitle(it) } ?: if (running) "در حال یافتن سریع‌ترین…" else "برای یافتن سریع‌ترین مکان لمس کنید",
                size = 12.5.sp, color = sx.textDim,
            )
        }
        if (best != null) {
            FlagBox(best.countryCode, 20.dp)
            Spacer(Modifier.width(8.dp))
            PingPill(best.pingMs)
        }
    }
}

@Composable
private fun ListHeader(title: String, trailing: String? = null) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        SText(title, size = 14.sp, color = sx.textDim, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (trailing != null) SText(trailing, size = 12.sp, color = sx.textFaint)
    }
}

@Composable
private fun CountryCard(
    g: PlaceGroup,
    favourite: Boolean,
    active: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onStar: () -> Unit,
    onPick: () -> Unit,
    onRow: (FastRow) -> Unit,
    onEngine: (EngineMode) -> Unit,
    rowActive: (FastRow) -> Boolean,
) {
    val sx = LocalSx.current
    val ways = g.rows.size + g.engines.size
    Column(Modifier.fillMaxWidth().sxCard(sx, highlight = active)) {
        PlaceRow(
            code = g.code,
            title = g.name.ifEmpty { g.code },
            subtitle = (g.rows.map { it.engine } + g.engines).distinct().joinToString("، ") { it.fa },
            pingMs = g.bestMs,
            measuring = g.rows.any { it.measuring },
            favourite = favourite,
            active = active,
            onStar = onStar,
            expand = if (ways > 1) open else null,
            onExpand = if (ways > 1) onToggle else null,
            onClick = onPick,
        )
        AnimatedVisibility(open && ways > 1) {
            Column(Modifier.fillMaxWidth().background(sx.surfaceHigh.copy(alpha = if (sx.light) 0.45f else 0.6f))) {
                g.rows.forEach { row ->
                    DividerLine()
                    SubRow(row.title.ifEmpty { row.engine.fa }, row.engine.fa, row.pingMs, row.measuring, rowActive(row)) { onRow(row) }
                }
                g.engines.forEach { m ->
                    DividerLine()
                    SubRow(m.fa, "کشور خروجی " + g.name, null, false, false) { onEngine(m) }
                }
            }
        }
    }
}

@Composable
private fun SubRow(title: String, subtitle: String, pingMs: Long?, measuring: Boolean, active: Boolean, onClick: () -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().sxClick(onClick).padding(start = 58.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SText(title, size = 14.sp, weight = FontWeight.SemiBold)
                if (active) { Spacer(Modifier.width(6.dp)); STag("متصل", sx.good) }
            }
            SText(subtitle, size = 11.5.sp, color = sx.textDim)
        }
        if (pingMs != null || measuring) PingPill(pingMs, measuring = measuring)
    }
}

@Composable
private fun PlaceRow(
    code: String,
    title: String,
    subtitle: String?,
    pingMs: Long?,
    measuring: Boolean,
    favourite: Boolean,
    active: Boolean,
    onStar: (() -> Unit)?,
    expand: Boolean? = null,
    onExpand: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().sxClick(onClick).padding(start = 14.dp, end = 4.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        FlagBox(code, 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SText(title, size = 15.5.sp, weight = FontWeight.SemiBold)
                if (active) { Spacer(Modifier.width(6.dp)); STag("متصل", sx.good) }
            }
            if (!subtitle.isNullOrEmpty()) SText(subtitle, size = 11.5.sp, color = sx.textDim)
        }
        if (pingMs != null || measuring) PingPill(pingMs, measuring = measuring)
        if (onExpand != null && expand != null) {
            Icon(
                if (expand) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "سرورهای این کشور", tint = sx.textDim,
                modifier = Modifier.size(38.dp).clip(CircleShape).sxClick(onExpand).padding(7.dp),
            )
        }
        if (onStar != null) {
            Icon(
                if (favourite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                "علاقه‌مندی",
                tint = if (favourite) androidx.compose.ui.graphics.Color(0xFFE0A100) else sx.textFaint,
                modifier = Modifier.size(38.dp).clip(CircleShape).sxClick(onStar).padding(7.dp),
            )
        } else Spacer(Modifier.width(8.dp))
    }
}
