package com.uacspoofer.mobile.ui.shell

import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.profiles.ProfileLatencyCache
import com.uacspoofer.mobile.profiles.ProfileLatencyTester
import com.uacspoofer.mobile.profiles.ProfileLibrary
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.profiles.PhoneImportQr
import com.uacspoofer.mobile.profiles.PhoneImportServer
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.Subscription
import com.uacspoofer.mobile.profiles.SubscriptionManager
import com.uacspoofer.mobile.profiles.SubscriptionStore
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.servers.FastestRepository
import com.uacspoofer.mobile.ui.ConfigLatencySort
import com.uacspoofer.mobile.ui.LocalWideShell
import com.uacspoofer.mobile.ui.sortProfilesByLatency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Delay {
    data object Testing : Delay
    data object Failed : Delay
    data class Ready(val ms: Long) : Delay
}

private fun sortLabel(s: ConfigLatencySort) = when (s) {
    ConfigLatencySort.DEFAULT -> "ترتیب افزودن"
    ConfigLatencySort.LATENCY_ASC -> "کمترین پینگ"
    ConfigLatencySort.LATENCY_DESC -> "بیشترین پینگ"
    ConfigLatencySort.COUNTRY_ASC -> "کشور"
}

/**
 * Configs and subscriptions (v2rayNG-style management): every private config and every
 * subscription link with its usage, cap and expiry, real delay tests, multi-select with
 * select all / copy / delete, add from clipboard, file or typed text, edit.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConfigsPage(
    onBack: () -> Unit,
    connectionState: ConnectionState,
    activeProfileId: String?,
    onSwitchProfile: () -> Unit,
) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val scope = rememberCoroutineScope()
    val wide = LocalWideShell.current
    val store = remember(context) { ProfileStore(context) }
    val subStore = remember(context) { SubscriptionStore(context) }
    val cache = remember(context) { ProfileLatencyCache(context) }
    val tester = remember(context) { ProfileLatencyTester(context) }
    var library by remember { mutableStateOf(runCatching { store.snapshot() }.getOrElse { ProfileLibrary(emptyList(), ProxyProfile.AUTO_ID) }) }
    var subs by remember { mutableStateOf(runCatching { subStore.all() }.getOrDefault(emptyList())) }
    var delays by remember {
        mutableStateOf<Map<String, Delay>>(
            runCatching { cache.snapshot(library.allProfiles.mapTo(hashSetOf()) { it.id }) }.getOrDefault(emptyMap()).mapValues { Delay.Ready(it.value) },
        )
    }
    var sort by rememberSaveable { mutableStateOf(ConfigLatencySort.DEFAULT) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var marked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var markedSubs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var confirm by remember { mutableStateOf<ConfirmState?>(null) }
    var phoneImport by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2_600); toast = null } }
    BackHandler(enabled = selecting) { selecting = false; marked = emptySet(); markedSubs = emptySet() }

    fun changed(lib: ProfileLibrary) {
        library = lib
        subs = runCatching { subStore.all() }.getOrDefault(subs)
        LocationState.bumpProfiles()
        FastestRepository.loadCached(context)
    }

    fun select(p: ProxyProfile) {
        val selectionChanged = library.selectedId != p.id
        if (selectionChanged) changed(store.select(p.id))
        if (connectionState == ConnectionState.CONNECTED && activeProfileId != p.id) {
            toast = "در حال تغییر به ${p.name}…"
            onSwitchProfile()
        } else if (selectionChanged) {
            toast = "«${p.name}» برای اتصال بعدی انتخاب شد"
        }
    }

    fun test(profiles: List<ProxyProfile>) {
        testJob?.cancel()
        testJob = scope.launch {
            profiles.forEach { p ->
                delays = delays + (p.id to Delay.Testing)
                delays = try {
                    val ms = tester.measure(p)
                    cache.put(p.id, ms)
                    delays + (p.id to Delay.Ready(ms))
                } catch (c: CancellationException) {
                    delays = delays - p.id
                    throw c
                } catch (_: Throwable) {
                    delays + (p.id to Delay.Failed)
                }
            }
            testJob = null
        }
    }

    fun refresh(sub: Subscription) {
        if (sub.id in refreshing) return
        refreshing = refreshing + sub.id
        scope.launch {
            toast = try {
                val r = withContext(Dispatchers.IO) { SubscriptionManager.importOrRefresh(context, sub.url, sub.id) }
                changed(r.library)
                "«${r.subscription.name}» به‌روز شد: ${r.addedCount} کانفیگ"
            } catch (c: CancellationException) { throw c } catch (e: Throwable) {
                runCatching { subStore.markError(sub.id, e.message) }
                subs = runCatching { subStore.all() }.getOrDefault(subs)
                "به‌روزرسانی ناموفق بود: ${e.message ?: "خطای شبکه"}"
            } finally { refreshing = refreshing - sub.id }
        }
    }

    fun refreshAll() {
        if (subs.isEmpty() || refreshing.isNotEmpty()) return
        refreshing = subs.mapTo(hashSetOf()) { it.id }
        scope.launch {
            val (ok, failed) = withContext(Dispatchers.IO) { runCatching { SubscriptionManager.refreshAll(context) }.getOrDefault(0 to subs.size) }
            changed(store.snapshot())
            refreshing = emptySet()
            toast = if (failed == 0) "$ok اشتراک به‌روز شد" else "$ok اشتراک به‌روز شد، $failed ناموفق"
        }
    }

    /** Text from the clipboard, a file, the editor or the phone page: a subscription URL or share links. */
    fun importText(text: String, name: String? = null): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return "متنی برای وارد کردن نیست"
        if (SubscriptionManager.isSubscriptionUrl(clean)) {
            scope.launch {
                toast = "در حال دریافت اشتراک…"
                toast = try {
                    val r = withContext(Dispatchers.IO) { SubscriptionManager.importOrRefresh(context, clean) }
                    changed(r.library)
                    "اشتراک «${r.subscription.name}» اضافه شد: ${r.addedCount} کانفیگ"
                } catch (c: CancellationException) { throw c } catch (e: Throwable) { "دریافت اشتراک انجام نشد: ${e.message ?: "خطای شبکه"}" }
            }
            return null
        }
        val r = store.importText(clean, name?.takeIf { it.isNotBlank() })
        if (r.importedCount == 0) return r.errors.firstOrNull()?.let(::configErrorFa) ?: "کانفیگ معتبری پیدا نشد"
        changed(store.select(r.library.customProfiles.first().id))
        toast = if (r.importedCount == 1) "کانفیگ اضافه و انتخاب شد" else "${r.importedCount} کانفیگ اضافه شد"
        if (connectionState == ConnectionState.CONNECTED) onSwitchProfile()
        return null
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(512 * 1024).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
            }
            val err = importText(text.orEmpty())
            if (err != null) toast = err else editor = null
        }
    }

    fun clipboardText(): String = runCatching {
        context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull().orEmpty()

    fun copyMarked() {
        val chosen = library.customProfiles.filter { it.id in marked }
        val links = chosen.map { p -> p.rawUri.trim().takeIf { ProfileUriParser.extractUris(it).size == 1 } ?: ProfileUriParser.canonicalUri(p) } +
            subs.filter { it.id in markedSubs }.map { it.url }
        if (links.isEmpty()) return
        copyToClipboard(context, "Blackdey configurations", links.joinToString("\n"))
        toast = "${links.size} مورد کپی شد"
    }

    val latencies = delays.mapNotNull { (id, d) -> (d as? Delay.Ready)?.ms?.let { id to it } }.toMap()
    val profiles = sortProfilesByLatency(library.allProfiles, latencies, sort)
    val subOf = remember(subs) { buildMap { subs.forEach { s -> s.profileIds.forEach { put(it, s.name) } } } }
    val deletable = library.customProfiles.size + subs.size

    Box(Modifier.fillMaxSize()) {
        SubPage(
            title = if (selecting) "${marked.size + markedSubs.size} انتخاب‌شده" else "کانفیگ‌ها و اشتراک‌ها",
            onBack = { if (selecting) { selecting = false; marked = emptySet(); markedSubs = emptySet() } else onBack() },
            subtitle = if (selecting) "روی هر مورد بزنید تا انتخاب شود" else "${library.customProfiles.size} کانفیگ · ${subs.size} اشتراک",
            spacing = 10.dp,
            actions = {
                if (selecting) {
                    RoundIconButtonFlat(Icons.Outlined.SelectAll, {
                        val all = library.customProfiles.mapTo(linkedSetOf()) { it.id }
                        val allSubs = subs.mapTo(linkedSetOf()) { it.id }
                        if (marked == all && markedSubs == allSubs) { marked = emptySet(); markedSubs = emptySet() } else { marked = all; markedSubs = allSubs }
                    }, description = "انتخاب همه")
                    RoundIconButtonFlat(Icons.Outlined.ContentCopy, ::copyMarked, description = "کپی انتخاب‌شده‌ها")
                    RoundIconButtonFlat(Icons.Outlined.Delete, {
                        if (marked.isNotEmpty() || markedSubs.isNotEmpty()) confirm = ConfirmState.Bulk(marked, markedSubs)
                    }, description = "حذف انتخاب‌شده‌ها", tint = sx.bad)
                } else {
                    RoundIconButtonFlat(if (testJob != null) Icons.Outlined.Sync else Icons.Outlined.NetworkPing, {
                        if (testJob != null) { testJob?.cancel(); testJob = null } else test(library.allProfiles)
                    }, description = if (testJob != null) "توقف تست" else "تست پینگ همه")
                    if (deletable > 0) RoundIconButtonFlat(Icons.Outlined.Checklist, { selecting = true }, description = "انتخاب چندتایی")
                    RoundIconButtonFlat(Icons.Outlined.Add, { editor = EditorState(null, "", "") }, description = "افزودن", tint = sx.accent)
                }
            },
        ) {
            item(key = "sort") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigLatencySort.entries.forEach { s -> SChip(sortLabel(s), selected = sort == s) { sort = s } }
                }
            }
            if (subs.isNotEmpty()) {
                item(key = "subs-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SText("اشتراک‌ها", size = 14.sp, weight = FontWeight.Bold, color = sx.textDim, modifier = Modifier.weight(1f))
                        if (subs.size > 1 && !selecting) SChip(if (refreshing.isNotEmpty()) "در حال به‌روزرسانی…" else "به‌روزرسانی همه", icon = Icons.Outlined.Refresh) { refreshAll() }
                    }
                }
                items(subs, key = { "s:" + it.id }, contentType = { "sub" }) { sub ->
                    val live = library.customProfiles.mapTo(hashSetOf()) { it.id }
                    SubscriptionUsageCard(
                        sub = sub,
                        configCount = sub.profileIds.count { it in live },
                        busy = sub.id in refreshing,
                        selecting = selecting,
                        marked = sub.id in markedSubs,
                        onClick = { if (selecting) markedSubs = markedSubs.toggle(sub.id) },
                        onLongClick = { selecting = true; markedSubs = markedSubs + sub.id },
                        onRefresh = { refresh(sub) },
                        onCopy = { copyToClipboard(context, "Blackdey subscription", sub.url); toast = "لینک اشتراک کپی شد" },
                        onDelete = { confirm = ConfirmState.Sub(sub) },
                    )
                }
            }
            item(key = "cfg-head") {
                SText("کانفیگ‌ها", size = 14.sp, weight = FontWeight.Bold, color = sx.textDim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
            items(profiles, key = { "p:" + it.id }, contentType = { "cfg" }) { p ->
                ConfigCard(
                    profile = p,
                    source = subOf[p.id],
                    selected = p.id == library.selectedId,
                    active = connectionState == ConnectionState.CONNECTED && p.id == activeProfileId,
                    selecting = selecting,
                    marked = p.id in marked,
                    delay = delays[p.id],
                    onClick = {
                        when {
                            selecting && !p.isBuiltIn -> marked = marked.toggle(p.id)
                            !selecting -> select(p)
                        }
                    },
                    onLongClick = if (p.isBuiltIn) null else ({ selecting = true; marked = marked + p.id }),
                    onTest = { test(listOf(p)) },
                    onEdit = if (p.isBuiltIn) null else ({ editor = EditorState(p.id, p.name, ProfileUriParser.canonicalUri(p)) }),
                    onCopy = if (p.isBuiltIn) null else ({
                        copyToClipboard(context, "Blackdey configuration", p.rawUri.ifBlank { ProfileUriParser.canonicalUri(p) }); toast = "کانفیگ کپی شد"
                    }),
                    onDelete = if (p.isBuiltIn) null else ({ confirm = ConfirmState.Profile(p) }),
                )
            }
            if (library.customProfiles.isEmpty() && subs.isEmpty()) {
                item(key = "empty") {
                    SNote("هنوز کانفیگ شخصی یا اشتراکی اضافه نکرده‌اید. با + لینک VLESS، VMess، Trojan یا لینک اشتراک (http/https) را از کلیپ‌بورد، فایل یا متن اضافه کنید.")
                }
            }
        }
        AnimatedVisibility(toast != null, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp, start = 16.dp, end = 16.dp).zIndex(3f)) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(sx.text).padding(horizontal = 16.dp, vertical = 10.dp)) {
                SText(toast.orEmpty(), size = 13.sp, color = sx.surface, maxLines = 3)
            }
        }
    }

    editor?.let { e ->
        ConfigEditor(
            state = e,
            onDismiss = { editor = null },
            onPaste = {
                val text = clipboardText()
                if (text.isBlank()) editor = e.copy(error = "کلیپ‌بورد خالی است")
                else if (e.id == null) { val err = importText(text, e.name); if (err == null) editor = null else editor = e.copy(uri = text, error = err) }
                else editor = e.copy(uri = ProfileUriParser.extractUris(text).firstOrNull() ?: text.trim(), error = null)
            },
            onFile = { runCatching { fileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) } },
            onPhone = if (wide && e.id == null) ({ editor = null; phoneImport = true }) else null,
            onChange = { editor = it },
            onSave = {
                if (e.id == null) {
                    val err = importText(e.uri, e.name)
                    if (err == null) editor = null else editor = e.copy(error = err)
                } else {
                    runCatching { store.update(e.id, e.uri, e.name) }
                        .onSuccess { changed(it); editor = null; toast = "کانفیگ ذخیره شد" }
                        .onFailure { editor = e.copy(error = configErrorFa(it.message ?: "invalid")) }
                }
            },
        )
    }

    when (val c = confirm) {
        is ConfirmState.Profile -> SConfirmDialog("حذف «${c.p.name}»؟", "این کانفیگ از گوشی حذف می‌شود.", "حذف", {
            changed(store.delete(c.p.id)); cache.remove(c.p.id); delays = delays - c.p.id; confirm = null; toast = "کانفیگ حذف شد"
        }, { confirm = null }, destructive = true)
        is ConfirmState.Sub -> SConfirmDialog("حذف اشتراک «${c.s.name}»؟", "لینک اشتراک و همه‌ی کانفیگ‌هایی که آورده از گوشی حذف می‌شوند.", "حذف", {
            changed(SubscriptionManager.delete(context, c.s.id)); cache.removeAll(c.s.profileIds.toSet()); confirm = null; toast = "اشتراک حذف شد"
        }, { confirm = null }, destructive = true)
        is ConfirmState.Bulk -> SConfirmDialog(
            "حذف ${c.profiles.size + c.subs.size} مورد؟",
            buildString {
                if (c.profiles.isNotEmpty()) append("${c.profiles.size} کانفیگ")
                if (c.subs.isNotEmpty()) { if (isNotEmpty()) append(" و "); append("${c.subs.size} اشتراک (با کانفیگ‌هایشان)") }
                append(" از گوشی حذف می‌شود.")
            },
            "حذف",
            {
                var lib = library
                if (c.subs.isNotEmpty()) lib = SubscriptionManager.deleteMany(context, c.subs)
                val left = c.profiles intersect lib.customProfiles.mapTo(hashSetOf()) { it.id }
                if (left.isNotEmpty()) lib = store.deleteMany(left)
                cache.removeAll(c.profiles)
                changed(lib)
                marked = emptySet(); markedSubs = emptySet(); selecting = false; confirm = null
                toast = "حذف شد"
            },
            { confirm = null },
            destructive = true,
        )
        null -> Unit
    }

    if (phoneImport) PhoneImportDialog(onImported = { importText(it) }, onDismiss = { phoneImport = false }, onFailed = {
        phoneImport = false; toast = "شبکه‌ی محلی پیدا نشد؛ گوشی و این دستگاه باید روی یک وای‌فای باشند."
    })
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private data class EditorState(val id: String?, val name: String, val uri: String, val error: String? = null)

private sealed interface ConfirmState {
    data class Profile(val p: ProxyProfile) : ConfirmState
    data class Sub(val s: Subscription) : ConfirmState
    data class Bulk(val profiles: Set<String>, val subs: Set<String>) : ConfirmState
}

internal fun configErrorFa(message: String): String = when {
    message.contains("clipboard", true) -> "کلیپ‌بورد کانفیگ معتبری ندارد"
    message.contains("unsupported", true) -> "این نوع کانفیگ پشتیبانی نمی‌شود"
    message.contains("empty", true) -> "لینک کانفیگ خالی است"
    message.contains("invalid", true) -> "کانفیگ معتبر نیست"
    message.any { it in '\u0600'..'\u06FF' } -> message
    else -> "کانفیگ وارد نشد ($message)"
}

/** Persian date (Jalali) for an expiry in Unix seconds; ISO date where ICU is unavailable. */
internal fun expiryDateFa(epochSeconds: Long): String = runCatching {
    val fmt = android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.MEDIUM, android.icu.util.ULocale("fa_IR@calendar=persian"))
    fmt.format(java.util.Date(epochSeconds * 1000L))
}.getOrElse { java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }

/** Subscription card: name, config count, used / total with a bar, remaining, expiry, update interval, last error. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubscriptionUsageCard(
    sub: Subscription,
    configCount: Int,
    busy: Boolean,
    selecting: Boolean = false,
    marked: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onRefresh: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val sx = LocalSx.current
    var menu by remember { mutableStateOf(false) }
    val used = sub.usedBytes
    val total = sub.totalBytes
    val remaining = sub.remainingBytes
    val days = sub.daysLeft()
    val expireAt = sub.expireAt
    Column(
        Modifier.fillMaxWidth().sxCard(sx, highlight = marked)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                SelectMark(marked)
                Spacer(Modifier.width(10.dp))
            } else {
                IconBox(Icons.Outlined.Link, size = 40.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                SText(sub.name.ifBlank { "اشتراک" }, size = 15.5.sp, weight = FontWeight.Bold, maxLines = 2)
                SText(
                    "$configCount کانفیگ" + (if (sub.unsupportedCount > 0) " · ${sub.unsupportedCount} پشتیبانی‌نشده" else "") +
                        (if (sub.updatedAt > 0) " · ${timeAgoFa(sub.updatedAt)}" else "") +
                        (sub.updateIntervalHours?.let { " · هر $it ساعت" } ?: ""),
                    size = 12.sp, color = sx.textDim, maxLines = 2,
                )
            }
            if (!selecting) {
                RoundIconButtonFlat(if (busy) Icons.Outlined.Sync else Icons.Outlined.Refresh, onRefresh, description = "به‌روزرسانی اشتراک", tint = sx.accent)
                if (onCopy != null || onDelete != null) Box {
                    RoundIconButtonFlat(Icons.Outlined.MoreVert, { menu = true }, description = "گزینه‌ها", tint = sx.textDim)
                    DropdownMenu(menu, { menu = false }) {
                        onCopy?.let { DropdownMenuItem({ SText("کپی لینک اشتراک") }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }) }
                        onDelete?.let { DropdownMenuItem({ SText("حذف اشتراک", color = sx.bad) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = sx.bad) }) }
                    }
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = sx.accent, trackColor = sx.surfaceHigh)
        }
        val unlimited = sub.userInfo?.unlimited == true && total == null
        if (used != null || total != null || remaining != null || days != null || unlimited) {
            Spacer(Modifier.height(12.dp))
            sub.usedFraction?.let { f ->
                val tone = when { f >= 0.9f -> sx.bad; f >= 0.75f -> sx.warn; else -> sx.accent }
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)), color = tone, trackColor = sx.surfaceHigh)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageCell("مصرف‌شده", used?.let(::bytesFa) ?: "—", Modifier.weight(1f))
                UsageCell("حجم کل", when { total != null -> bytesFa(total); unlimited -> "نامحدود"; else -> "—" }, Modifier.weight(1f))
                UsageCell("باقی‌مانده", remaining?.let(::bytesFa) ?: if (unlimited) "نامحدود" else "—", Modifier.weight(1f))
            }
            if (days != null && expireAt != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tone = when { days < 0 -> sx.bad; days < 3 -> sx.warn; else -> sx.textDim }
                    Icon(Icons.Outlined.CalendarMonth, null, tint = tone, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    SText(
                        (if (days >= 0) "$days روز تا انقضا" else "منقضی شده") + " · " + expiryDateFa(expireAt),
                        size = 12.5.sp, color = tone, weight = if (days < 3) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        sub.lastError?.let {
            Spacer(Modifier.height(8.dp))
            SText("آخرین به‌روزرسانی ناموفق: $it", size = 12.sp, color = sx.warn, maxLines = 2)
        }
    }
}

private fun bytesFa(b: Long): String = formatBytesFa(b).let { (v, u) -> "$v $u" }

@Composable
private fun UsageCell(label: String, value: String, modifier: Modifier) {
    val sx = LocalSx.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(sx.surfaceHigh.copy(alpha = if (sx.light) 0.7f else 0.6f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        SText(label, size = 11.5.sp, color = sx.textDim)
        SText(value, size = 14.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun SelectMark(on: Boolean) {
    val sx = LocalSx.current
    Icon(if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (on) sx.accent else sx.textFaint, modifier = Modifier.size(24.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConfigCard(
    profile: ProxyProfile,
    source: String?,
    selected: Boolean,
    active: Boolean,
    selecting: Boolean,
    marked: Boolean,
    delay: Delay?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onTest: () -> Unit,
    onEdit: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val sx = LocalSx.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().sxCard(sx, highlight = selected || marked)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            if (profile.isBuiltIn) Spacer(Modifier.width(24.dp)) else SelectMark(marked)
            Spacer(Modifier.width(10.dp))
        }
        val code = profile.country.countryCode
        if (code != null) FlagBox(code, 26.dp) else ProtocolMark(profile)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SText(if (profile.isAuto) "خودکار (Auto)" else profile.name, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (active) { Spacer(Modifier.width(6.dp)); STag("متصل", sx.good) } else if (selected) { Spacer(Modifier.width(6.dp)); STag("انتخاب‌شده") }
            }
            val tech = listOf(profile.protocol.wireName.uppercase(), profile.network.uppercase(), profile.security.uppercase())
                .filter { it.isNotBlank() && it != "NONE" }.joinToString(" · ")
            SText(
                buildString {
                    append(if (profile.isBuiltIn) "پیش‌فرض برنامه" else source?.let { "اشتراک $it" } ?: "کانفیگ شخصی")
                    if (tech.isNotBlank() && !profile.isAuto) append(" · ").append(tech)
                },
                size = 12.sp, color = sx.textDim, maxLines = 1,
            )
        }
        val d = delay
        Box(Modifier.sxClick(onTest).padding(4.dp)) {
            PingPill(ms = (d as? Delay.Ready)?.ms, measuring = d is Delay.Testing, failed = d is Delay.Failed)
        }
        if (!selecting && (onEdit != null || onCopy != null || onDelete != null)) Box {
            RoundIconButtonFlat(Icons.Outlined.MoreVert, { menu = true }, description = "گزینه‌ها", tint = sx.textDim)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ SText("تست پینگ") }, { menu = false; onTest() }, leadingIcon = { Icon(Icons.Outlined.NetworkPing, null) })
                onEdit?.let { DropdownMenuItem({ SText("ویرایش") }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }) }
                onCopy?.let { DropdownMenuItem({ SText("کپی لینک") }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }) }
                onDelete?.let { DropdownMenuItem({ SText("حذف", color = sx.bad) }, { menu = false; it() }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = sx.bad) }) }
            }
        } else Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ProtocolMark(p: ProxyProfile) {
    val sx = LocalSx.current
    Box(Modifier.size(width = 34.dp, height = 26.dp).clip(RoundedCornerShape(6.dp)).background(sx.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        SText(if (p.isAuto) "A" else p.protocol.wireName.take(1).uppercase(), size = 13.sp, weight = FontWeight.Bold, color = sx.accent, latin = true)
    }
}

@Composable
private fun ConfigEditor(
    state: EditorState,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onFile: () -> Unit,
    onPhone: (() -> Unit)?,
    onChange: (EditorState) -> Unit,
    onSave: () -> Unit,
) {
    val sx = LocalSx.current
    SDialog(
        visible = true,
        title = if (state.id == null) "افزودن کانفیگ یا اشتراک" else "ویرایش کانفیگ",
        subtitle = if (state.id == null) "لینک VLESS، VMess، Trojan، چند لینک با هم یا لینک اشتراک" else null,
        icon = if (state.id == null) Icons.Outlined.Add else Icons.Outlined.Edit,
        onDismissRequest = onDismiss,
    ) {
        if (state.id == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SButton("از کلیپ‌بورد", onPaste, Modifier.weight(1f), icon = Icons.Outlined.ContentPaste)
                SButton("از فایل", onFile, Modifier.weight(1f), primary = false, icon = Icons.Outlined.Description)
            }
            if (onPhone != null) {
                Spacer(Modifier.height(8.dp))
                SButton("از موبایل (QR)", onPhone, Modifier.fillMaxWidth(), primary = false, icon = Icons.Outlined.PhoneAndroid)
            }
            Spacer(Modifier.height(14.dp))
            SText("یا متن را بچسبانید:", size = 13.sp, color = sx.textDim)
            Spacer(Modifier.height(6.dp))
        }
        SField(state.name, { onChange(state.copy(name = it, error = null)) }, "نام (اختیاری)")
        Spacer(Modifier.height(8.dp))
        SField(state.uri, { onChange(state.copy(uri = it, error = null)) }, "vless://…  یا  https://…/sub", singleLine = false, ltr = true, minHeight = 96.dp)
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            SText(it, size = 12.5.sp, color = sx.bad, maxLines = 3)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SButton("ذخیره", onSave, Modifier.weight(1f), enabled = state.uri.isNotBlank())
            SButton("انصراف", onDismiss, Modifier.weight(1f), primary = false)
        }
    }
}

/** Wide screens (TV/tablet): a local page a phone on the same Wi-Fi opens to send configs. */
@Composable
private fun PhoneImportDialog(onImported: (String) -> Unit, onDismiss: () -> Unit, onFailed: () -> Unit) {
    val sx = LocalSx.current
    var url by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val server = PhoneImportServer(onImported)
        if (!server.start()) {
            onFailed()
            onDispose { }
        } else {
            url = server.url
            onDispose { server.stop() }
        }
    }
    val qr = remember(url) { url?.let { PhoneImportQr.bitmap(it)?.asImageBitmap() } }
    SDialog(visible = true, title = "افزودن کانفیگ با موبایل", icon = Icons.Outlined.PhoneAndroid, onDismissRequest = onDismiss) {
        SParagraph("با موبایلی که روی همین وای‌فای است این QR را اسکن کنید و در صفحه‌ای که باز می‌شود کانفیگ را بچسبانید.")
        Spacer(Modifier.height(14.dp))
        if (qr != null) {
            Image(qr, "QR", Modifier.align(Alignment.CenterHorizontally).size(220.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp))
        }
        url?.let { SText(it, size = 12.5.sp, color = sx.accent, ltr = true, latin = true, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)) }
        Spacer(Modifier.height(12.dp))
        SButton("بستن", onDismiss, Modifier.fillMaxWidth(), primary = false)
    }
}
