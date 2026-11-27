package com.uacspoofer.mobile.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Dialog
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.CrashReportStore
import com.uacspoofer.mobile.logging.LogExport
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.profiles.Subscription
import com.uacspoofer.mobile.profiles.SubscriptionStore
import com.uacspoofer.mobile.profiles.PhoneImportQr
import com.uacspoofer.mobile.ui.LicensesDialog
import com.uacspoofer.mobile.update.AppRelease
import com.uacspoofer.mobile.update.AppUpdateManager
import com.uacspoofer.mobile.update.UpdateCheckResult
import com.uacspoofer.mobile.update.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Update check state shared by the start-up check and the Support page. */
internal object UpdateUi {
    var state by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
    var dialogVisible by mutableStateOf(false)

    fun check(context: Context, scope: CoroutineScope, showDialog: Boolean) {
        if (state == UpdateUiState.Checking || state is UpdateUiState.Downloading) return
        val manager = AppUpdateManager(context.applicationContext)
        scope.launch {
            state = UpdateUiState.Checking
            state = try {
                when (val r = manager.checkForUpdate()) {
                    UpdateCheckResult.Current -> UpdateUiState.UpToDate
                    is UpdateCheckResult.Available -> {
                        if (showDialog) dialogVisible = true
                        UpdateUiState.Available(r.release)
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UpdateUiState.Error(e.message ?: "Could not check GitHub Releases")
            }
        }
    }

    fun download(context: Context, scope: CoroutineScope, release: AppRelease) {
        if (state is UpdateUiState.Downloading) return
        val manager = AppUpdateManager(context.applicationContext)
        scope.launch {
            state = UpdateUiState.Downloading(release, 0)
            state = try {
                manager.downloadAndInstall(release) { p -> state = UpdateUiState.Downloading(release, p) }
                UpdateUiState.Ready(release, "دانلود تمام شد. برای نصب، اعلان دانلود را لمس کنید.")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UpdateUiState.Error(e.message ?: "The update could not be downloaded", release)
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun shareText(context: Context, text: String, subject: String = "Blackdey") {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** «پشتیبانی و به‌روزرسانی»: version, update check, channel, source and licences. */
@Composable
internal fun SupportPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val scope = rememberCoroutineScope()
    var licenses by remember { mutableStateOf(false) }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })
    val update = UpdateUi.state
    SubPage("پشتیبانی و به‌روزرسانی", onBack, subtitle = "نسخه‌ی برنامه، کانال رسمی و گزارش مشکل") {
        item(key = "id") { BrandWordmarkCard() }
        item(key = "update") {
            SGroup("به‌روزرسانی") {
                val (title, sub) = when (update) {
                    UpdateUiState.Idle -> "بررسی نسخه‌ی جدید" to "نسخه‌ی فعلی ${BuildConfig.VERSION_NAME}"
                    UpdateUiState.Checking -> "در حال بررسی…" to "GitHub Releases"
                    UpdateUiState.UpToDate -> "برنامه به‌روز است" to "نسخه‌ی ${BuildConfig.VERSION_NAME} آخرین نسخه است"
                    is UpdateUiState.Available -> "نسخه‌ی ${update.release.version} آماده است" to "برای دانلود و نصب لمس کنید"
                    is UpdateUiState.Downloading -> "در حال دانلود ${update.release.version}" to (update.progress?.let { "$it٪" } ?: "…")
                    is UpdateUiState.Ready -> "دانلود تمام شد" to update.message
                    is UpdateUiState.Error -> "بررسی ناموفق بود" to "اتصال اینترنت را بررسی کنید و دوباره امتحان کنید"
                }
                SRow(
                    if (update is UpdateUiState.UpToDate) Icons.Outlined.CheckCircle else Icons.Outlined.SystemUpdate,
                    title, sub,
                    tint = if (update is UpdateUiState.Error) sx.bad else sx.accent,
                ) {
                    when (update) {
                        is UpdateUiState.Available -> UpdateUi.dialogVisible = true
                        is UpdateUiState.Downloading -> Unit
                        else -> UpdateUi.check(context, scope, showDialog = true)
                    }
                }
                RowDivider()
                SRow(Icons.Outlined.NewReleases, "همه‌ی نسخه‌ها", "صفحه‌ی انتشارها در GitHub") { openUrl(context, AppUpdateManager.RELEASES_URL) }
            }
        }
        item(key = "help") {
            SGroup("کمک و ارتباط") {
                SRow(Icons.AutoMirrored.Outlined.Send, "کانال رسمی تلگرام", SIMORGH_TELEGRAM_HANDLE, tint = Color(0xFF2AABEE)) { openTelegramChannel(context) }
                RowDivider()
                SRow(Icons.Outlined.BugReport, "گزارش مشکل", "گزارش خطا، فایل لاگ و مشخصات دستگاه") { onOpen(Route.REPORT) }
            }
        }
        item(key = "about") {
            SGroup("درباره") {
                SRow(Icons.Outlined.Link, "کد منبع", "github.com/aboutsg99-png/simorgh") { openUrl(context, AppUpdateManager.REPOSITORY_URL) }
                RowDivider()
                SRow(Icons.Outlined.Gavel, "مجوزها", "AGPL-3.0 و کتابخانه‌های متن‌باز") { licenses = true }
                RowDivider()
                SRow(Icons.Outlined.Info, "داده‌ها", "نقشه: Natural Earth (مالکیت عمومی) · پرچم‌ها: flag-icons (MIT)", onClick = null)
            }
        }
    }
}

/** «گزارش مشکل»: the three things that help most, each one tap. */
@Composable
internal fun ReportPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var hasCrash by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<LogExport.Snapshot?>(null) }
    LaunchedEffect(Unit) {
        hasCrash = withContext(Dispatchers.IO) { CrashReportStore.hasReport(context) }
        snapshot = withContext(Dispatchers.IO) { runCatching { LogExport.snapshot(context) }.getOrNull() }
    }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(2_600L); notice = null } }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val snap = snapshot
        if (uri != null && snap != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { LogExport.writeTo(context, uri, snap.text) }
            notice = if (ok) "فایل لاگ ذخیره شد" else "ذخیره نشد"
            if (ok) AppLogRepository.info(LogSource.APP, "Log saved as ${snap.fileName}")
        }
    }
    val device = remember {
        "Blackdey ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${Build.MANUFACTURER} ${Build.MODEL}"
    }
    SubPage("گزارش مشکل", onBack, subtitle = "همراه پیامتان در تلگرام بفرستید") {
        item(key = "how") {
            SNote("مشکل را کوتاه بنویسید: چه موتوری، چه شبکه‌ای (وای‌فای یا موبایل، کدام اپراتور) و چه چیزی دیدید. فایل لاگ را هم پیوست کنید. لاگ آدرس سرورها را پنهان می‌کند و رمز یا کلید در آن نیست.")
        }
        item(key = "steps") {
            SGroup("پیوست‌ها") {
                val snap = snapshot
                SRow(
                    Icons.Outlined.Description, "فایل لاگ",
                    if (snap == null) "در حال آماده‌سازی…" else "${snap.lineCount} خط · ذخیره یا اشتراک",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SmallAction(Icons.Outlined.SaveAlt, "ذخیره") { snap?.let { runCatching { saver.launch(it.fileName) } } }
                            SmallAction(Icons.Outlined.Share, "اشتراک") { snap?.let { shareText(context, it.text, it.fileName) } }
                        }
                    },
                )
                RowDivider()
                SRow(
                    Icons.Outlined.BugReport, "گزارش خطای برنامه",
                    if (hasCrash) "آخرین گزارش خطا؛ برای کپی لمس کنید" else "هنوز خطایی ثبت نشده است",
                    tint = if (hasCrash) sx.bad else sx.textDim,
                    onClick = if (hasCrash) ({
                        val payload = CrashReportStore.copyPayload(context)
                        if (!payload.isNullOrBlank()) { copyToClipboard(context, "Blackdey crash report", payload); notice = "گزارش خطا کپی شد" }
                    }) else null,
                )
                RowDivider()
                SRow(Icons.Outlined.PhoneAndroid, "مشخصات دستگاه", device.replace("\n", " · ")) {
                    copyToClipboard(context, "Blackdey device", device); notice = "مشخصات کپی شد"
                }
            }
        }
        item(key = "send") {
            SGroup("ارسال") {
                SRow(Icons.AutoMirrored.Outlined.Send, "ارسال در تلگرام", SIMORGH_TELEGRAM_HANDLE, tint = Color(0xFF2AABEE)) { openTelegramChannel(context) }
                RowDivider()
                SRow(Icons.AutoMirrored.Outlined.ListAlt, "لاگ زنده", "رویدادهای اتصال را همین حالا ببینید") { onOpen(Route.LIVE_LOGS) }
            }
        }
        if (notice != null) item(key = "notice") { SNote(notice.orEmpty(), tone = sx.good, icon = Icons.Outlined.CheckCircle) }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val sx = LocalSx.current
    Box(Modifier.size(40.dp).clip(CircleShape).background(sx.accent.copy(alpha = 0.12f)).sxClick(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = sx.accent, modifier = Modifier.size(20.dp))
    }
}

private data class ShareItem(val key: String, val title: String, val subtitle: String, val payload: String, val code: String)

/** «اشتراک کانفیگ‌ها و اشتراک‌ها»: copy, QR or system share for each config and subscription link. */
@Composable
internal fun SharePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    var configs by remember { mutableStateOf<List<ShareItem>?>(null) }
    var subs by remember { mutableStateOf<List<Subscription>>(emptyList()) }
    var qr by remember { mutableStateOf<Pair<String, String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            subs = runCatching { SubscriptionStore(context).all() }.getOrDefault(emptyList())
            configs = runCatching {
                ProfileStore(context).snapshot().customProfiles.map { p ->
                    val uri = p.rawUri.trim().takeIf { ProfileUriParser.extractUris(it).size == 1 } ?: ProfileUriParser.canonicalUri(p)
                    ShareItem(p.id, p.name.ifBlank { p.serverHost }, p.protocol.name, uri, p.country.countryCode.orEmpty())
                }
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(2_200L); notice = null } }
    qr?.let { (title, text) -> QrDialog(title, text) { qr = null } }
    SubPage("اشتراک‌گذاری", onBack, subtitle = "کانفیگ یا لینک اشتراک را برای دوستانتان بفرستید") {
        item(key = "note") { SNote("فقط با کسانی که به آن‌ها اعتماد دارید به اشتراک بگذارید؛ هر کس لینک را داشته باشد می‌تواند از همان سرور استفاده کند.") }
        if (notice != null) item(key = "notice") { SNote(notice.orEmpty(), tone = sx.good, icon = Icons.Outlined.CheckCircle) }
        item(key = "subs-h") { SectionTitle("لینک‌های اشتراک") }
        if (subs.isEmpty()) item(key = "subs-empty") { EmptyState("لینک اشتراکی اضافه نشده است.") }
        items(subs, key = { "s:" + it.id }) { s ->
            ShareRow(null, s.name.ifBlank { "اشتراک" }, "${s.profileIds.size} کانفیگ", onCopy = {
                copyToClipboard(context, "Blackdey subscription", s.url); notice = "لینک اشتراک کپی شد"
            }, onQr = { qr = s.name to s.url }, onShare = { shareText(context, s.url) })
        }
        item(key = "cfg-h") {
            SectionTitle("کانفیگ‌ها") {
                val all = configs.orEmpty()
                if (all.isNotEmpty()) SChip("کپی همه", icon = Icons.Outlined.ContentCopy, onClick = {
                    copyToClipboard(context, "Blackdey configurations", all.joinToString("\n") { it.payload }); notice = "${all.size} کانفیگ کپی شد"
                })
            }
        }
        val list = configs
        if (list == null) item(key = "cfg-loading") { EmptyState("در حال خواندن…") }
        else if (list.isEmpty()) item(key = "cfg-empty") { EmptyState("کانفیگی اضافه نشده است.") }
        else items(list, key = { "c:" + it.key }) { c ->
            ShareRow(c.code, c.title, c.subtitle, onCopy = {
                copyToClipboard(context, "Blackdey configuration", c.payload); notice = "کانفیگ کپی شد"
            }, onQr = { qr = c.title to c.payload }, onShare = { shareText(context, c.payload) })
        }
    }
}

@Composable
private fun ShareRow(code: String?, title: String, subtitle: String, onCopy: () -> Unit, onQr: () -> Unit, onShare: () -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().sxCard(sx).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (code != null) FlagBox(code, 24.dp) else IconBox(Icons.Outlined.Link, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText(title, size = 14.5.sp, weight = FontWeight.SemiBold)
            SText(subtitle, size = 12.sp, color = sx.textDim, ltr = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallAction(Icons.Outlined.ContentCopy, "کپی", onCopy)
            SmallAction(Icons.Outlined.QrCode2, "QR", onQr)
            SmallAction(Icons.Outlined.Share, "اشتراک", onShare)
        }
    }
}

@Composable
internal fun EmptyState(text: String) {
    val sx = LocalSx.current
    Box(Modifier.fillMaxWidth().sxCard(sx).padding(20.dp), contentAlignment = Alignment.Center) {
        SText(text, size = 13.5.sp, color = sx.textDim, maxLines = 4)
    }
}

@Composable
internal fun QrDialog(title: String, text: String, onDismiss: () -> Unit) {
    val sx = LocalSx.current
    val bitmap = remember(text) { PhoneImportQr.bitmap(text, 640)?.asImageBitmap() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(sx.surface).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SText(title, size = 16.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            if (bitmap != null) {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                    Image(bitmap, "QR", Modifier.size(240.dp))
                }
            } else {
                SText("این متن برای QR بیش از حد بلند است.", color = sx.textDim)
            }
            Spacer(Modifier.height(14.dp))
            SButton("بستن", onDismiss, primary = false, modifier = Modifier.fillMaxWidth())
        }
    }
}
