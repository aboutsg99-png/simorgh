package com.uacspoofer.mobile.ui.shell

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.foxy.FoxyAccount
import com.uacspoofer.mobile.engine.foxy.FoxyAccountState
import com.uacspoofer.mobile.engine.foxy.FoxyErrors
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.engine.tor.TorExitCountry
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.Subscription
import com.uacspoofer.mobile.profiles.SubscriptionManager
import com.uacspoofer.mobile.profiles.SubscriptionStore
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.RecentLocations
import com.uacspoofer.mobile.simorgh.SelectionActions
import com.uacspoofer.mobile.simorgh.Target
import com.uacspoofer.mobile.simorgh.countryName
import com.uacspoofer.mobile.simorgh.servers.FastRow
import com.uacspoofer.mobile.simorgh.servers.FastestRepository
import com.uacspoofer.mobile.simorgh.servers.RowSource
import com.uacspoofer.mobile.ui.DrawerDestination
import com.uacspoofer.mobile.ui.FoxySignInScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ServerTab(val fa: String) { FAST("پرسرعت‌ها"), ALL("همه"), FAV("علاقه‌مندی‌ها"), RECENT("اخیر") }

/** Favourite country codes, persisted. */
private object Favourites {
    private const val PREFS = "simorgh_favourites_v1"
    fun read(context: Context): Set<String> =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("codes", emptySet())?.toSet() }
            .getOrNull() ?: emptySet()
    fun write(context: Context, codes: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("codes", codes).apply()
    }
}

/** A country with every way out we know for it (configs, Mozilla, engine exits). */
private data class CountryGroup(val code: String, val items: List<FastRow>, val engineOnly: List<EngineMode>) {
    val best: Long? get() = items.mapNotNull { it.pingMs }.minOrNull()
    val count: Int get() = items.size + engineOnly.size
}

@Composable
internal fun ServersTab(state: ConnectionState, onConnect: () -> Unit, onOpenLegacy: (DrawerDestination) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val scope = rememberCoroutineScope()
    val fastest by FastestRepository.state.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    val account = remember(context) { FoxyAccount.get(context) }
    val accountStatus by account.status.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(ServerTab.FAST) }
    var query by rememberSaveable { mutableStateOf("") }
    var engineFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var favourites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    var signBusy by remember { mutableStateOf(false) }
    var signMessage by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var subs by remember { mutableStateOf<List<Subscription>>(emptyList()) }
    var refreshing by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val recent by remember(context) { RecentLocations.get(context) }.items.collectAsStateWithLifecycle()

    fun reloadSubs() { scope.launch { subs = withContext(Dispatchers.IO) { runCatching { SubscriptionStore(context).all() }.getOrDefault(emptyList()) } } }

    LaunchedEffect(Unit) {
        favourites = withContext(Dispatchers.IO) { Favourites.read(context) }
        FastestRepository.loadCached(context)
        reloadSubs()
        if (runCatching { account.hasStoredSession() }.getOrDefault(false)) runCatching { account.refreshStatus() }
    }
    // Pings run only while this screen is visible.
    DisposableEffect(Unit) { onDispose { FastestRepository.cancel() } }
    LaunchedEffect(accountStatus.state) {
        if (accountStatus.state == FoxyAccountState.SIGNED_IN) FastestRepository.loadCached(context)
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2_600L)
            toast = null
        }
    }

    fun importText(text: String) {
        scope.launch {
            val clean = text.trim()
            if (clean.isEmpty()) { toast = "متنی برای وارد کردن پیدا نشد"; return@launch }
            toast = try {
                withContext(Dispatchers.IO) {
                    if (SubscriptionManager.isSubscriptionUrl(clean)) {
                        val r = SubscriptionManager.importOrRefresh(context, clean)
                        "اشتراک «${r.subscription.name}» اضافه شد: ${r.addedCount} کانفیگ"
                    } else {
                        val r = ProfileStore(context).importText(clean)
                        if (r.importedCount > 0) "${r.importedCount} کانفیگ اضافه شد" else "کانفیگ معتبری پیدا نشد"
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                "وارد کردن ناموفق بود"
            }
            LocationState.bumpProfiles()
            reloadSubs()
            FastestRepository.loadCached(context)
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(512 * 1024).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
            }
            importText(text.orEmpty())
        }
    }
    val qrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) { decodeQr(context, uri) }
            if (text == null) toast = "کد QR در تصویر پیدا نشد" else importText(text)
        }
    }

    // Grouping/sorting hundreds of rows (with country-name lookups) runs off the main thread.
    val groups by androidx.compose.runtime.produceState(emptyList<CountryGroup>(), fastest.rows, engineFilter) {
        value = withContext(Dispatchers.Default) { buildGroups(context, fastest.rows, engineFilter) }
    }
    val q = query.trim()
    fun matches(code: String, extra: String = "") = q.isEmpty() || countryName(code).contains(q, true) ||
        countryName(code, false).contains(q, true) || code.equals(q, true) || extra.contains(q, true)

    fun apply(target: Target) {
        SelectionActions.apply(context, target, onConnect)
        toast = "در حال اتصال با ${target.engine.fa}" + (countryName(target.countryCode).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
    }
    fun applyRow(row: FastRow) = apply(Target(row.engine, if (row.engine.isXray) "" else row.countryCode, row.profileId))

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "title") {
                ScreenTitle("سرورها و کانفیگ‌ها") {
                    RoundIconButton(Icons.Outlined.QrCodeScanner, { qrLauncher.launch("image/*") })
                    Box {
                        RoundIconButton(Icons.Outlined.Add, { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { SText("از کلیپ‌بورد") }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) }, onClick = {
                                menuOpen = false
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                importText(runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull().orEmpty())
                            })
                            DropdownMenuItem(text = { SText("از فایل") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = {
                                menuOpen = false
                                runCatching { fileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }
                            })
                            DropdownMenuItem(text = { SText("از تصویر QR") }, leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, null) }, onClick = {
                                menuOpen = false
                                runCatching { qrLauncher.launch("image/*") }
                            })
                            DropdownMenuItem(text = { SText("مدیریت کانفیگ‌ها") }, leadingIcon = { Icon(Icons.Outlined.MoreVert, null) }, onClick = {
                                menuOpen = false
                                onOpenLegacy(DrawerDestination.CONFIGS)
                            })
                        }
                    }
                }
            }
            item(key = "search") { SearchField(query) { query = it } }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth().sxCard(sx).padding(6.dp)) {
                    ServerTab.entries.forEach { t ->
                        val on = t == tab
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (on) sx.accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                                .sxClick { tab = t }.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (t == ServerTab.FAST) {
                                Icon(Icons.Outlined.Bolt, null, tint = if (on) sx.accent else sx.textDim, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(3.dp))
                            }
                            SText(t.fa, size = 13.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) sx.accent else sx.textDim)
                        }
                    }
                }
            }
            when (tab) {
                ServerTab.FAST -> {
                    item(key = "foxy-banner") {
                        FoxyBanner(accountStatus.state == FoxyAccountState.SIGNED_IN) { signingIn = true }
                    }
                    item(key = "fast-head") {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            SText("مرتب بر اساس پینگ", size = 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            val measured = fastest.rows.count { it.pingMs != null }
                            SText(
                                "$measured سرور" + if (fastest.lastRunMs > 0) " · ${timeAgoFa(fastest.lastRunMs)}" else "",
                                size = 12.sp, color = sx.textDim,
                            )
                        }
                    }
                    val rows = fastest.sorted.filter { matches(it.countryCode, it.title + " " + it.tag) }
                    if (rows.isEmpty()) {
                        item(key = "fast-empty") {
                            EmptyCard(
                                if (fastest.running) "در حال پینگ گرفتن…" else "هنوز سروری اندازه‌گیری نشده. «پینگ همه» را بزنید یا کانفیگ اضافه کنید.",
                            )
                        }
                    }
                    items(rows, key = { it.key }, contentType = { "fast" }) { row ->
                        FastRowCard(row, row.countryCode in favourites, active = isActive(location, row), onStar = {
                            favourites = favourites.toggle(row.countryCode); Favourites.write(context, favourites)
                        }) { applyRow(row) }
                    }
                }
                ServerTab.ALL -> {
                    item(key = "engines") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SChip("همه موتورها", selected = engineFilter == null) { engineFilter = null }
                            listOf(EngineMode.XRAY_CF, EngineMode.FOXY_FIREFOX, EngineMode.TOR_WEBTUNNEL, EngineMode.UAC_POW).forEach { m ->
                                SChip(m.fa, selected = engineFilter == m.id) { engineFilter = if (engineFilter == m.id) null else m.id }
                            }
                        }
                    }
                    val best = fastest.sorted.firstOrNull { it.pingMs != null }
                    if (best != null) item(key = "best") { BestCard(best) { applyRow(best) } }
                    item(key = "my-head") {
                        SectionTitle("کانفیگ‌های من") {
                            SChip("افزودن", icon = Icons.Outlined.Add, selected = true) { menuOpen = true }
                        }
                    }
                    items(subs, key = { "sub:" + it.id }, contentType = { "sub" }) { sub ->
                        SubscriptionCard(sub, refreshing == sub.id) {
                            refreshing = sub.id
                            scope.launch {
                                toast = try {
                                    val r = withContext(Dispatchers.IO) { SubscriptionManager.importOrRefresh(context, sub.url, sub.id) }
                                    "«${r.subscription.name}» به‌روز شد: ${r.addedCount} کانفیگ"
                                } catch (c: CancellationException) { throw c } catch (_: Throwable) { "به‌روزرسانی اشتراک ناموفق بود" }
                                refreshing = null
                                reloadSubs()
                                FastestRepository.loadCached(context)
                            }
                        }
                    }
                    val configs = fastest.rows.filter { (it.source == RowSource.CONFIG || it.source == RowSource.SUBSCRIPTION) && matches(it.countryCode, it.title) }
                        .sortedWith(compareBy({ it.pingMs == null }, { it.pingMs ?: Long.MAX_VALUE }))
                    if (configs.isEmpty() && subs.isEmpty()) item(key = "no-configs") { EmptyCard("کانفیگی نداری. با + از کلیپ‌بورد، فایل یا تصویر QR اضافه کن.") }
                    if (configs.isNotEmpty()) item(key = "configs") {
                        Column(Modifier.fillMaxWidth().sxCard(sx)) {
                            configs.forEachIndexed { i, row ->
                                if (i > 0) DividerLine()
                                ConfigRow(row, isActive(location, row), onMore = { onOpenLegacy(DrawerDestination.CONFIGS) }) { applyRow(row) }
                            }
                        }
                    }
                    item(key = "countries-head") {
                        SectionTitle("کشورها") {
                            Icon(Icons.AutoMirrored.Outlined.Sort, null, tint = sx.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            SText("مرتب‌سازی: پینگ", size = 12.5.sp, color = sx.accent)
                        }
                    }
                    val list = groups.filter { matches(it.code) }
                    items(list, key = { "c:" + it.code }, contentType = { "country" }) { g ->
                        CountryCard(
                            g, g.code in favourites, expanded == g.code,
                            onToggle = { expanded = if (expanded == g.code) null else g.code },
                            onStar = { favourites = favourites.toggle(g.code); Favourites.write(context, favourites) },
                            onRow = { applyRow(it) },
                            onEngine = { apply(Target(it, g.code)) },
                        )
                    }
                }
                ServerTab.FAV -> {
                    val list = groups.filter { it.code in favourites && matches(it.code) }
                    if (list.isEmpty()) item(key = "fav-empty") { EmptyCard("کشوری را با ستاره علاقه‌مندی کن تا اینجا بیاید.") }
                    items(list, key = { "f:" + it.code }, contentType = { "country" }) { g ->
                        CountryCard(
                            g, true, expanded == g.code,
                            onToggle = { expanded = if (expanded == g.code) null else g.code },
                            onStar = { favourites = favourites.toggle(g.code); Favourites.write(context, favourites) },
                            onRow = { applyRow(it) },
                            onEngine = { apply(Target(it, g.code)) },
                        )
                    }
                }
                ServerTab.RECENT -> {
                    if (recent.isEmpty()) item(key = "recent-empty") { EmptyCard("هنوز اتصالی ثبت نشده.") }
                    items(recent, key = { "r:${it.engine.id}:${it.selectedCode}:${it.profileId}" }, contentType = { "recent" }) { r ->
                        val code = r.countryCode.ifEmpty { r.selectedCode }
                        Row(
                            Modifier.fillMaxWidth().sxCard(sx).sxClick { apply(Target(r.engine, r.selectedCode, r.profileId)) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FlagBox(code, 24.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                SText(countryName(code).ifEmpty { r.profileName.ifEmpty { "خودکار" } }, size = 15.sp, weight = FontWeight.Bold)
                                SText("${relativeDayFa(r.atMs)} · موتور ${r.engine.fa}", size = 12.sp, color = sx.textDim)
                            }
                            Icon(Icons.Outlined.History, null, tint = sx.textDim)
                        }
                    }
                }
            }
        }
        // Ping-all FAB, bottom start (left in RTL? start = right) -> place at bottom end like the mockup (left).
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Row(
            Modifier.align(Alignment.BottomEnd).padding(bottom = bottom + 100.dp, end = 18.dp)
                .clip(RoundedCornerShape(24.dp)).background(sx.accent)
                .sxClick { if (fastest.running) FastestRepository.cancel() else FastestRepository.pingAll(context) }
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SText(if (fastest.running) "توقف پینگ" else "پینگ همه", size = 15.sp, weight = FontWeight.Bold, color = sx.onAccent)
            Spacer(Modifier.width(8.dp))
            Icon(if (fastest.running) Icons.Outlined.Sync else Icons.Outlined.NetworkPing, null, tint = sx.onAccent)
        }
        AnimatedVisibility(toast != null, modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp).zIndex(3f)) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh).padding(horizontal = 16.dp, vertical = 10.dp)) {
                SText(toast.orEmpty(), size = 13.sp, maxLines = 2)
            }
        }
        if (signingIn) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                FoxySignInScreen(
                    startUrl = remember { runCatching { account.loginUrl() }.getOrDefault("https://accounts.firefox.com") },
                    busy = signBusy,
                    message = signMessage,
                    onSessionCaptured = { token ->
                        if (!signBusy) scope.launch {
                            signBusy = true
                            signMessage = null
                            val result = try { account.completeSignIn(token) } finally { signBusy = false }
                            result.onSuccess {
                                signingIn = false
                                toast = "ورود به فایرفاکس انجام شد؛ کشورهای موزیلا اضافه می‌شوند"
                                FastestRepository.loadCached(context)
                                FastestRepository.pingAll(context)
                            }.onFailure { signMessage = FoxyErrors.persian(it) }
                        }
                    },
                    onCancel = { signingIn = false; signMessage = null },
                )
            }
        }
    }
}

private fun Set<String>.toggle(code: String): Set<String> = if (code in this) this - code else this + code

private fun isActive(location: com.uacspoofer.mobile.simorgh.CurrentLocation, row: FastRow): Boolean {
    if (location.connection != ConnectionState.CONNECTED || location.engine != row.engine) return false
    return when (row.source) {
        RowSource.CONFIG, RowSource.SUBSCRIPTION -> row.profileId == location.selection.profileId
        RowSource.RUBAH -> row.countryCode == location.selection.countryCode
        RowSource.LIVE -> true
    }
}

private fun buildGroups(context: Context, rows: List<FastRow>, engineFilter: String?): List<CountryGroup> {
    val filtered = rows.filter { engineFilter == null || it.engine.id == engineFilter }.filter { it.countryCode.length == 2 }
    val byCode = filtered.groupBy { it.countryCode }
    val engineCodes = HashMap<String, MutableList<EngineMode>>()
    if (engineFilter == null || engineFilter == EngineMode.TOR_WEBTUNNEL.id) {
        TorExitCountry.RECOMMENDED.forEach { engineCodes.getOrPut(it.uppercase()) { mutableListOf() } += EngineMode.TOR_WEBTUNNEL }
    }
    if (engineFilter == null || engineFilter == EngineMode.UAC_POW.id) {
        val cached = runCatching { com.uacspoofer.mobile.engine.pow.PowEngineStore.get(context).snapshot() }.getOrNull()
        PowRegions.options(emptyList()).forEach { engineCodes.getOrPut(it.uppercase()) { mutableListOf() } += EngineMode.UAC_POW }
        cached?.let { }
    }
    val codes = (byCode.keys + engineCodes.keys).distinct()
    return codes.map { CountryGroup(it, byCode[it].orEmpty().sortedBy { r -> r.pingMs ?: Long.MAX_VALUE }, engineCodes[it].orEmpty()) }
        .sortedWith(compareBy({ it.best == null }, { it.best ?: Long.MAX_VALUE }, { countryName(it.code) }))
}

private fun decodeQr(context: Context, uri: Uri): String? = runCatching {
    val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val scaled = if (bitmap.width > 1600 || bitmap.height > 1600) {
        val f = 1600f / maxOf(bitmap.width, bitmap.height)
        android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
    } else bitmap
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    val source = com.google.zxing.RGBLuminanceSource(scaled.width, scaled.height, pixels)
    val hints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)
    val reader = com.google.zxing.MultiFormatReader()
    runCatching { reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)), hints).text }
        .getOrElse { reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.GlobalHistogramBinarizer(source)), hints).text }
}.getOrNull()

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().sxCard(sx).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = sx.textDim)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) SText("جستجوی کشور، شهر یا کانفیگ", color = sx.textDim, size = 15.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = sx.text, fontSize = 15.sp, fontFamily = Vazir),
                cursorBrush = SolidColor(sx.accent), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    val sx = LocalSx.current
    Box(Modifier.fillMaxWidth().sxCard(sx).padding(20.dp), contentAlignment = Alignment.Center) {
        SText(text, color = sx.textDim, size = 13.5.sp, maxLines = 3)
    }
}

@Composable
private fun FoxyBanner(signedIn: Boolean, onSignIn: () -> Unit) {
    val sx = LocalSx.current
    val tone = if (signedIn) sx.good else sx.warn
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(sx.radius + 6.dp)).background(tone.copy(alpha = 0.10f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(if (signedIn) EngineMode.FOXY_FIREFOX.icon else Icons.Outlined.Info, tint = tone, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (signedIn) {
                SText("فایرفاکس: وارد شده", size = 14.sp, weight = FontWeight.Bold)
                SText("کشورهای موزیلا به فهرست اضافه شدند", size = 12.sp, color = sx.textDim)
            } else {
                SText("بعد از ورود با اکانت فایرفاکس خودت، کشورهای موزیلا هم اضافه می‌شوند", size = 13.sp, maxLines = 2)
            }
        }
        if (signedIn) {
            SChip("فعال", icon = Icons.Outlined.CheckCircle, color = sx.good)
        } else {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(sx.warn).sxClick(onSignIn).padding(horizontal = 16.dp, vertical = 8.dp)) {
                SText("ورود", size = 14.sp, weight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF2A1A00))
            }
        }
    }
}

@Composable
private fun SourceTag(row: FastRow) {
    val sx = LocalSx.current
    val color = when (row.source) {
        RowSource.RUBAH -> androidx.compose.ui.graphics.Color(0xFFF2A65A)
        RowSource.CONFIG, RowSource.SUBSCRIPTION -> androidx.compose.ui.graphics.Color(0xFF7FB2FF)
        RowSource.LIVE -> sx.accent
    }
    val icon = when (row.source) {
        RowSource.RUBAH -> EngineMode.FOXY_FIREFOX.icon
        RowSource.CONFIG -> Icons.Outlined.Key
        RowSource.SUBSCRIPTION -> Icons.Outlined.Sync
        RowSource.LIVE -> row.engine.icon
    }
    SChip(row.tag, icon = icon, color = color)
}

@Composable
private fun FastRowCard(row: FastRow, favourite: Boolean, active: Boolean, onStar: () -> Unit, onClick: () -> Unit) {
    val sx = LocalSx.current
    Row(
        Modifier.fillMaxWidth().sxCard(sx, highlight = active).sxClick(onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBox(row.countryCode, 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val name = countryName(row.countryCode)
            SText(
                if (row.source == RowSource.CONFIG || row.source == RowSource.SUBSCRIPTION) {
                    if (name.isNotEmpty()) "$name · ${row.title}" else row.title
                } else {
                    name.ifEmpty { row.title }
                },
                size = 15.sp, weight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            SourceTag(row)
        }
        PingPill(row.pingMs, measuring = row.measuring, failed = row.failed && row.pingMs == null)
        Spacer(Modifier.width(6.dp))
        Icon(
            if (favourite) Icons.Outlined.Star else Icons.Outlined.StarBorder, "علاقه‌مندی",
            tint = if (favourite) sx.warn else sx.textFaint,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).sxClick(onStar).padding(5.dp),
        )
    }
}

@Composable
private fun BestCard(row: FastRow, onClick: () -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().sxCard(sx, highlight = true).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBox(Icons.Outlined.Bolt)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText("سریع‌ترین سرور", size = 16.sp, weight = FontWeight.Bold)
            SText(listOf(countryName(row.countryCode), row.tag).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.5.sp, color = sx.textDim)
        }
        PingPill(row.pingMs)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(sx.accent).sxClick(onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.PowerSettingsNew, "اتصال", tint = sx.onAccent) }
    }
}

@Composable
private fun SubscriptionCard(sub: Subscription, busy: Boolean, onRefresh: () -> Unit) {
    val sx = LocalSx.current
    val info = sub.userInfo
    Column(Modifier.fillMaxWidth().sxCard(sx).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(Icons.Outlined.Sync)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SText(sub.name.ifBlank { "اشتراک" }, size = 15.sp, weight = FontWeight.Bold)
                SText("${sub.profileIds.size} کانفیگ · به‌روزرسانی ${timeAgoFa(sub.updatedAt)}", size = 12.sp, color = sx.textDim)
            }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh).sxClick { if (!busy) onRefresh() }, contentAlignment = Alignment.Center) {
                Icon(if (busy) Icons.Outlined.Sync else Icons.Outlined.Refresh, "به‌روزرسانی", tint = sx.text)
            }
        }
        if (info != null && !info.unlimited) {
            val total = info.total ?: 0L
            val remaining = info.remaining ?: 0L
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) (remaining.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = sx.accent, trackColor = sx.surfaceHigh,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                val (rv, ru) = formatBytesFa(remaining)
                val (tv, tu) = formatBytesFa(total)
                SText("$rv $ru از $tv $tu باقی‌مانده", size = 12.sp, color = sx.textDim, modifier = Modifier.weight(1f))
                if (!info.noExpiry) {
                    val days = (((info.expire ?: 0L) * 1000L - System.currentTimeMillis()) / 86_400_000L)
                    Icon(Icons.Outlined.CalendarMonth, null, tint = sx.textDim, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    SText(if (days >= 0) "$days روز تا انقضا" else "منقضی شده", size = 12.sp, color = if (days < 3) sx.warn else sx.textDim)
                }
            }
        } else if (info != null) {
            Spacer(Modifier.height(6.dp))
            SText("حجم نامحدود", size = 12.sp, color = sx.textDim)
        }
    }
}

@Composable
private fun ConfigRow(row: FastRow, active: Boolean, onMore: () -> Unit, onClick: () -> Unit) {
    val sx = LocalSx.current
    Row(
        Modifier.fillMaxWidth().background(if (active) sx.accent.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .sxClick(onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(if (row.source == RowSource.SUBSCRIPTION) Icons.Outlined.Sync else Icons.Outlined.Key, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText(row.title, size = 14.5.sp, weight = FontWeight.Bold)
            SText(
                listOf(countryName(row.countryCode), "Xray", if (row.source == RowSource.SUBSCRIPTION) row.tag.removeSuffix(" · Xray") else "").filter { it.isNotEmpty() }.joinToString(" · "),
                size = 12.sp, color = sx.textDim,
            )
        }
        PingPill(row.pingMs, measuring = row.measuring, failed = row.failed && row.pingMs == null)
        Icon(Icons.Outlined.MoreVert, "مدیریت", tint = sx.textDim, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).sxClick(onMore).padding(6.dp))
    }
}

@Composable
private fun CountryCard(
    g: CountryGroup,
    favourite: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onStar: () -> Unit,
    onRow: (FastRow) -> Unit,
    onEngine: (EngineMode) -> Unit,
) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().sxCard(sx)) {
        Row(Modifier.fillMaxWidth().sxClick(onToggle).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FlagBox(g.code, 28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SText(countryName(g.code), size = 15.sp, weight = FontWeight.Bold)
                SText("${g.count} مسیر", size = 12.sp, color = sx.textDim)
            }
            if (g.best != null) PingPill(g.best)
            Icon(
                if (favourite) Icons.Outlined.Star else Icons.Outlined.StarBorder, "علاقه‌مندی",
                tint = if (favourite) sx.warn else sx.textFaint,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).sxClick(onStar).padding(5.dp),
            )
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = sx.textDim)
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(sx.radius)).background(sx.surfaceHigh.copy(alpha = 0.5f))) {
                g.items.forEachIndexed { i, row ->
                    if (i > 0) DividerLine()
                    Row(Modifier.fillMaxWidth().sxClick { onRow(row) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, null, tint = sx.textDim, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        SText(if (row.source == RowSource.RUBAH) "موزیلا" else row.title, size = 14.sp, modifier = Modifier.weight(1f))
                        SChip(row.engine.fa)
                        Spacer(Modifier.width(6.dp))
                        PingPill(row.pingMs, measuring = row.measuring)
                    }
                }
                g.engineOnly.forEach { engine ->
                    if (g.items.isNotEmpty() || engine != g.engineOnly.first()) DividerLine()
                    Row(Modifier.fillMaxWidth().sxClick { onEngine(engine) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(engine.icon, null, tint = sx.textDim, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        SText("خروجی ${engine.fa}", size = 14.sp, modifier = Modifier.weight(1f))
                        SText("پینگ بعد از اتصال", size = 11.5.sp, color = sx.textFaint)
                    }
                }
            }
        }
    }
}
