package com.uacspoofer.mobile.ui.shell

import android.content.Context
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

private enum class PickTab(val fa: String) { SUGGESTED("پیشنهادی"), ALL("همه‌ی مکان‌ها"), FAVOURITES("علاقه‌مندی‌ها") }

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

/**
 * Location picker, ExpressVPN style: search, three tabs (suggested / all / favourites), the
 * smart location on top, then flat rows with flag, ping and a star. Grouping and sorting run
 * on a background dispatcher; rows are keyed and typed so scrolling reuses them.
 */
@Composable
internal fun LocationsScreen(
    state: ConnectionState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onManage: () -> Unit,
    topInset: Dp,
    bottomInset: Dp,
) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val fastest by FastestRepository.state.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PickTab.SUGGESTED) }
    var query by rememberSaveable { mutableStateOf("") }
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
            it.name.contains(q, true) || countryName(it.code, false).contains(q, true) || it.code.equals(q, true)
        }
    }
    fun pick(target: Target) = SelectionActions.apply(context, target, onConnect).also { onBack() }
    fun pickRow(row: FastRow) = pick(Target(row.engine, if (row.engine.isXray) "" else row.countryCode, row.profileId))
    fun pickGroup(g: PlaceGroup) {
        val best = g.best ?: g.rows.firstOrNull()
        if (best != null) pickRow(best) else g.engines.firstOrNull()?.let { pick(Target(it, g.code)) }
    }
    fun toggleStar(code: String) {
        favourites = if (code in favourites) favourites - code else favourites + code
        FavouriteCountries.write(context, favourites)
    }
    val smart = model.suggested.firstOrNull()

    Column(Modifier.fillMaxSize().background(sx.bgTop)) {
        Row(Modifier.fillMaxWidth().padding(top = topInset + 10.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButtonFlat(Icons.AutoMirrored.Outlined.ArrowBack, onBack, description = "بازگشت")
            Spacer(Modifier.width(4.dp))
            SText("انتخاب مکان", size = 21.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            RoundIconButtonFlat(Icons.Outlined.Add, onManage, description = "کانفیگ‌ها و اشتراک‌ها")
        }
        Spacer(Modifier.height(10.dp))
        SearchBox(query, { query = it }, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        PickTabs(tab) { tab = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
            if (q.isEmpty() && tab == PickTab.SUGGESTED) {
                item(key = "smart", contentType = "smart") {
                    SmartRow(smart) {
                        if (smart != null) pickRow(smart) else FastestRepository.pingAll(context)
                    }
                }
                if (model.suggested.isNotEmpty()) {
                    item(key = "h-suggest", contentType = "header") { ListHeader("پیشنهاد برای شما") }
                    items(model.suggested, key = { "s:" + it.key }, contentType = { "row" }) { row ->
                        PlaceRow(row.countryCode, rowTitle(row), row.pingMs, row.measuring, row.countryCode in favourites, isActive(location, row), { toggleStar(row.countryCode) }) { pickRow(row) }
                    }
                }
                if (model.firefox.isNotEmpty()) {
                    item(key = "h-ff", contentType = "header") { ListHeader("Firefox VPN · MASQUE") }
                    items(model.firefox, key = { "f:" + it.key }, contentType = { "row" }) { row ->
                        PlaceRow(row.countryCode, countryName(row.countryCode), row.pingMs, row.measuring, row.countryCode in favourites, isActive(location, row), { toggleStar(row.countryCode) }) { pickRow(row) }
                    }
                }
                if (model.configs.isNotEmpty()) {
                    item(key = "h-cfg", contentType = "header") { ListHeader("Xray · کانفیگ‌ها و اشتراک‌ها") }
                    items(model.configs, key = { "c:" + it.key }, contentType = { "row" }) { row ->
                        PlaceRow(row.countryCode, row.title, row.pingMs, row.measuring, row.countryCode in favourites, isActive(location, row), null) { pickRow(row) }
                    }
                }
                if (model.suggested.isEmpty() && model.firefox.isEmpty() && model.configs.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyNote(if (fastest.running) "در حال پینگ گرفتن…" else "هنوز سروری اندازه‌گیری نشده. «همه‌ی مکان‌ها» را ببینید یا با + کانفیگ اضافه کنید.")
                    }
                }
            } else {
                val list = if (tab == PickTab.FAVOURITES) filtered.filter { it.code in favourites } else filtered
                if (list.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyNote(if (tab == PickTab.FAVOURITES && q.isEmpty()) "با ستاره، مکان‌های دلخواه را اینجا نگه دارید." else "مکانی پیدا نشد.")
                    }
                }
                items(list, key = { "g:" + it.code }, contentType = { "row" }) { g ->
                    val best = g.best
                    PlaceRow(
                        g.code, g.name, best?.pingMs, g.rows.any { it.measuring }, g.code in favourites,
                        active = location.connection == ConnectionState.CONNECTED && location.displayCode == g.code,
                        onStar = { toggleStar(g.code) },
                        subtitle = when {
                            g.rows.isEmpty() -> g.engines.joinToString("، ") { it.fa }
                            else -> (g.rows.map { it.engine }.distinct() + g.engines).distinct().joinToString("، ") { it.fa }
                        },
                    ) { pickGroup(g) }
                }
            }
        }
    }
}

private fun rowTitle(row: FastRow): String = when (row.source) {
    RowSource.CONFIG, RowSource.SUBSCRIPTION -> countryName(row.countryCode).ifEmpty { row.title }
    else -> countryName(row.countryCode).ifEmpty { row.title }
}

private fun isActive(location: CurrentLocation, row: FastRow): Boolean {
    if (location.connection != ConnectionState.CONNECTED || location.engine != row.engine) return false
    return when (row.source) {
        RowSource.CONFIG, RowSource.SUBSCRIPTION -> row.profileId == location.selection.profileId
        RowSource.RUBAH -> row.countryCode == location.selection.countryCode
        RowSource.LIVE -> true
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    Row(modifier.fillMaxWidth().sxCard(sx).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = sx.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) SText("جستجوی کشور یا شهر", color = sx.textDim, size = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = Vazir, fontSize = 15.sp * sx.textScale, color = sx.text),
                cursorBrush = SolidColor(sx.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PickTabs(selected: PickTab, onSelect: (PickTab) -> Unit) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            PickTab.entries.forEach { t ->
                val on = t == selected
                Column(Modifier.weight(1f).sxClick { onSelect(t) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    SText(t.fa, size = 14.5.sp, weight = FontWeight.SemiBold, color = if (on) sx.accent else sx.textDim, modifier = Modifier.padding(vertical = 10.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) sx.accent else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(sx.stroke))
    }
}

@Composable
private fun SmartRow(best: FastRow?, onClick: () -> Unit) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().background(sx.surface)) {
        Row(Modifier.fillMaxWidth().sxClick(onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(sx.accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Bolt, null, tint = sx.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SText("مکان هوشمند", size = 15.sp, weight = FontWeight.Bold)
                SText(best?.let { countryName(it.countryCode).ifEmpty { it.title } } ?: "برای یافتن سریع‌ترین مکان لمس کنید", size = 12.sp, color = sx.textDim)
            }
            if (best?.pingMs != null) PingText(best.pingMs, false)
        }
        DividerLine()
    }
}

@Composable
private fun ListHeader(title: String) {
    val sx = LocalSx.current
    SText(title, size = 13.sp, color = sx.textDim, weight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun PlaceRow(
    code: String,
    title: String,
    pingMs: Long?,
    measuring: Boolean,
    favourite: Boolean,
    active: Boolean,
    onStar: (() -> Unit)?,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().background(if (active) lerpAccent(sx) else sx.surface)) {
        Row(Modifier.fillMaxWidth().sxClick(onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FlagBox(code, 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SText(title, size = 15.5.sp, weight = FontWeight.SemiBold)
                if (!subtitle.isNullOrEmpty()) SText(subtitle, size = 11.5.sp, color = sx.textDim)
            }
            PingText(pingMs, measuring)
            if (onStar != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (favourite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    "علاقه‌مندی",
                    tint = if (favourite) androidx.compose.ui.graphics.Color(0xFFE0A100) else sx.textFaint,
                    modifier = Modifier.size(36.dp).clip(CircleShape).sxClick(onStar).padding(6.dp),
                )
            }
        }
        DividerLine()
    }
}

private fun lerpAccent(sx: SxColors) = androidx.compose.ui.graphics.lerp(sx.surface, sx.accent, 0.08f)

@Composable
private fun PingText(ms: Long?, measuring: Boolean) {
    val sx = LocalSx.current
    when {
        ms != null -> SText("$ms ms", size = 13.5.sp, weight = FontWeight.SemiBold, color = sx.pingColor(ms), ltr = true)
        measuring -> SText("…", size = 13.5.sp, color = sx.textFaint)
        else -> Unit
    }
}

@Composable
private fun EmptyNote(text: String) {
    val sx = LocalSx.current
    SText(text, size = 13.sp, color = sx.textDim, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(24.dp))
}
