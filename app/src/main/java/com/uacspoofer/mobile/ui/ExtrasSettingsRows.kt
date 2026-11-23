package com.uacspoofer.mobile.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.WifiLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.extras.ExtrasSettings
import com.uacspoofer.mobile.extras.ExtrasSettingsStore
import com.uacspoofer.mobile.extras.SiteReachability
import com.uacspoofer.mobile.extras.UntrustedWifiWatcher
import com.uacspoofer.mobile.extras.WifiTrustMode
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.MonthlyTrafficStore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings rows for the 1.0.1 extras. Kept in their own file so the settings
// restyle can change SettingsScreen.kt freely; SettingsScreen only calls these.

private val ExtrasTextInset = 58.dp

@Composable
private fun ExtrasRow(
    icon: ImageVector,
    title: String,
    summary: String,
    active: Boolean,
    showDivider: Boolean,
    summaryColor: Color? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val tint = if (active) UacColors.ConnectedGreen else UacColors.TextSecondary
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .defaultMinSize(minHeight = 60.dp)
                    .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).background(tint.copy(alpha = 0.13f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = UacColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                    Text(summary, color = summaryColor ?: tint, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2)
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = UacColors.TextSecondary.copy(alpha = 0.72f),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (checked != null && onCheckedChange != null) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.semantics { contentDescription = title },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = UacColors.ConnectedGreen,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.88f),
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
        if (expanded) {
            Column(
                Modifier.padding(start = ExtrasTextInset, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = ExtrasTextInset, end = 14.dp).height(1.dp)
                    .background(Color.White.copy(alpha = 0.07f)),
            )
        }
    }
}

@Composable
private fun HelpText(text: String, color: Color = UacColors.TextSecondary) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SmallAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = if (enabled) UacColors.DisconnectedBlue else UacColors.TextSecondary, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------
// 2. Auto-connect on untrusted Wi-Fi
// ---------------------------------------------------------------------------

private data class WifiPermissionState(
    val fine: Boolean = false,
    val background: Boolean = false,
    val locationOn: Boolean = false,
    val ssid: String? = null,
)

private fun readWifiPermissionState(context: Context): WifiPermissionState = runCatching {
    WifiPermissionState(
        fine = UntrustedWifiWatcher.hasFineLocation(context),
        background = UntrustedWifiWatcher.hasBackgroundLocation(context),
        locationOn = UntrustedWifiWatcher.locationServiceOn(context),
        ssid = UntrustedWifiWatcher.currentSsid(context),
    )
}.getOrDefault(WifiPermissionState())

@Composable
internal fun UntrustedWifiSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember(app) { ExtrasSettingsStore.get(app) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var perm by remember { mutableStateOf(WifiPermissionState()) }
    var refreshTick by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var manualName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(refreshTick, settings.wifiTrustMode) {
        perm = withContext(Dispatchers.IO) { readWifiPermissionState(app) }
    }

    fun syncWatcher() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { UntrustedWifiWatcher.sync(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                notice = "فعال‌سازی پایش وای‌فای انجام نشد. دوباره امتحان کنید."
            }
        }
    }

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshTick++
        if (!granted) {
            notice = "بدون مجوز «همیشه»، وقتی برنامه بسته است نام وای‌فای خوانده نمی‌شود و هر وای‌فای ناشناس، ناامن حساب می‌شود."
        }
    }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refreshTick++
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fine) {
            store.setWifiTrustMode(WifiTrustMode.TRUSTED_LIST)
            notice = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !UntrustedWifiWatcher.hasBackgroundLocation(app)) {
                dialog = "background"
            }
        } else {
            store.setWifiTrustMode(WifiTrustMode.ANY_WIFI)
            notice = "مجوز موقعیت داده نشد؛ حالت «هر وای‌فای» فعال ماند. در این حالت به مجوز نیازی نیست."
        }
    }

    val summary = when {
        !settings.untrustedWifiAutoConnect -> "خاموش"
        settings.wifiTrustMode == WifiTrustMode.ANY_WIFI -> "روشن · هر وای‌فای"
        else -> "روشن · ${settings.trustedSsids.size} شبکهٔ مورد اعتماد"
    }

    ExtrasRow(
        icon = Icons.Outlined.WifiLock,
        title = "اتصال خودکار در وای‌فای ناامن",
        summary = summary,
        active = settings.untrustedWifiAutoConnect,
        showDivider = showDivider,
        checked = settings.untrustedWifiAutoConnect,
        onCheckedChange = { checked ->
            store.setUntrustedWifiAutoConnect(checked)
            syncWatcher()
        },
    ) {
        HelpText(
            "وقتی گوشی به یک وای‌فای وصل می‌شود که در فهرست مورد اعتماد نیست، سیمرغ خودش روشن می‌شود. " +
                "پایش با رویداد شبکهٔ اندروید انجام می‌شود و باتری مصرف نمی‌کند. اگر اندروید اجازهٔ شروع از پس‌زمینه ندهد، " +
                "یک اعلان می‌آید که با یک لمس وصل شوید. مجوز VPN باید یک بار از داخل برنامه داده شده باشد.",
        )
        Column(Modifier.fillMaxWidth()) {
            ModeOption(
                selected = settings.wifiTrustMode == WifiTrustMode.ANY_WIFI,
                title = "هر وای‌فای",
                body = "در هر وای‌فای وصل می‌شود. به مجوز موقعیت نیاز ندارد.",
                onClick = { store.setWifiTrustMode(WifiTrustMode.ANY_WIFI); notice = null },
            )
            ModeOption(
                selected = settings.wifiTrustMode == WifiTrustMode.TRUSTED_LIST,
                title = "به‌جز شبکه‌های مورد اعتماد",
                body = "شبکه‌های خانه یا محل کار را مورد اعتماد کنید تا آنجا خودکار وصل نشود.",
                onClick = {
                    if (UntrustedWifiWatcher.hasFineLocation(app)) {
                        store.setWifiTrustMode(WifiTrustMode.TRUSTED_LIST)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !UntrustedWifiWatcher.hasBackgroundLocation(app)) {
                            dialog = "background"
                        }
                    } else {
                        dialog = "fine"
                    }
                },
            )
        }

        if (settings.wifiTrustMode == WifiTrustMode.TRUSTED_LIST) {
            when {
                !perm.fine -> HelpText("مجوز موقعیت داده نشده؛ نام شبکه خوانده نمی‌شود و هر وای‌فای ناامن حساب می‌شود.", UacColors.DisconnectingAmber)
                !perm.locationOn -> HelpText("موقعیت مکانی گوشی خاموش است؛ اندروید نام وای‌فای را نشان نمی‌دهد.", UacColors.DisconnectingAmber)
                !perm.background -> HelpText("مجوز «همیشه» داده نشده؛ وقتی برنامه بسته است، شبکهٔ ناشناس ناامن حساب می‌شود.", UacColors.DisconnectingAmber)
            }
            val current = perm.ssid
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (current != null) "شبکهٔ فعلی: $current" else "شبکهٔ فعلی: نامشخص",
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                SmallAction(
                    text = if (current != null && current in settings.trustedSsids) "مورد اعتماد است" else "مورد اعتماد کن",
                    enabled = current != null && current !in settings.trustedSsids,
                ) { current?.let(store::addTrustedSsid) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualName,
                    onValueChange = { manualName = it.take(32) },
                    singleLine = true,
                    label = { Text("نام شبکه (دستی)") },
                    modifier = Modifier.weight(1f),
                )
                SmallAction("افزودن", enabled = manualName.isNotBlank()) {
                    store.addTrustedSsid(manualName)
                    manualName = ""
                }
            }
            if (settings.trustedSsids.isEmpty()) {
                HelpText("هنوز شبکهٔ مورد اعتمادی اضافه نشده است.")
            } else {
                settings.trustedSsids.sorted().forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = UacColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { store.removeTrustedSsid(name) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "حذف $name", tint = UacColors.TextSecondary)
                        }
                    }
                }
            }
        }
        notice?.let { HelpText(it, UacColors.DisconnectingAmber) }
    }

    when (dialog) {
        "fine" -> PermissionDialog(
            title = "مجوز موقعیت برای خواندن نام وای‌فای",
            body = "اندروید نام وای‌فای (SSID) را فقط به برنامه‌هایی نشان می‌دهد که مجوز موقعیت دارند. " +
                "سیمرغ موقعیت شما را نمی‌خواند، ذخیره نمی‌کند و جایی نمی‌فرستد؛ فقط نام شبکه را با فهرست مورد اعتماد مقایسه می‌کند. " +
                "اگر نمی‌خواهید، حالت «هر وای‌فای» بدون هیچ مجوزی کار می‌کند.",
            confirm = "ادامه",
            onConfirm = {
                dialog = null
                runCatching {
                    fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }.onFailure { notice = "درخواست مجوز باز نشد." }
            },
            onDismiss = { dialog = null },
        )
        "background" -> PermissionDialog(
            title = "اجازهٔ «همیشه» برای وقتی برنامه بسته است",
            body = "برای اینکه وقتی سیمرغ باز نیست هم نام وای‌فای شناخته شود، در صفحهٔ بعد «همیشه مجاز» را انتخاب کنید. " +
                "اگر ندهید، فقط وقتی برنامه باز است شبکهٔ مورد اعتماد شناخته می‌شود و در بقیهٔ مواقع هر وای‌فای ناامن حساب می‌شود.",
            confirm = "ادامه",
            onConfirm = {
                dialog = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                        .onFailure { notice = "درخواست مجوز باز نشد." }
                }
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(body, color = UacColors.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun PermissionDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, lineHeight = 22.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

// ---------------------------------------------------------------------------
// 3. Monthly data cap warning
// ---------------------------------------------------------------------------

private fun gbLabel(bytes: Long): String {
    val gb = bytes / ExtrasSettings.BYTES_PER_GB
    return if (gb >= 10) String.format(Locale.US, "%.0f", gb) else String.format(Locale.US, "%.1f", gb)
}

private fun capLabel(gb: Float): String =
    if (gb % 1f == 0f) String.format(Locale.US, "%.0f", gb) else String.format(Locale.US, "%.1f", gb)

@Composable
internal fun DataCapSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember(app) { ExtrasSettingsStore.get(app) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val usage by remember(app) { MonthlyTrafficStore.get(app) }.usage.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val capBytes = settings.dataCapBytes
    val fraction = if (capBytes > 0) (usage.totalBytes.toDouble() / capBytes).toFloat().coerceIn(0f, 1f) else 0f
    val summary = if (settings.dataCapEnabled) {
        "${gbLabel(usage.totalBytes)} از ${capLabel(settings.dataCapGb)} گیگابایت این ماه"
    } else {
        "خاموش"
    }
    val color = when {
        !settings.dataCapEnabled -> null
        fraction >= 1f -> UacColors.ErrorRed
        fraction >= 0.8f -> UacColors.DisconnectingAmber
        else -> null
    }
    ExtrasRow(
        icon = Icons.Outlined.DataUsage,
        title = "هشدار سقف حجم ماهانه",
        summary = summary,
        active = settings.dataCapEnabled,
        showDivider = showDivider,
        summaryColor = color,
        checked = settings.dataCapEnabled,
        onCheckedChange = { store.setDataCapEnabled(it) },
    ) {
        HelpText("در ۸۰٪ و ۱۰۰٪ سقف، هر کدام یک بار در ماه اعلان می‌گیرید. اتصال قطع نمی‌شود. مصرف از شمارندهٔ ماهانهٔ همین برنامه خوانده می‌شود.")
        if (settings.dataCapEnabled) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = color ?: UacColors.ConnectedGreen,
                trackColor = Color.White.copy(alpha = 0.10f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(7); error = null },
                singleLine = true,
                label = { Text("سقف فعلی: ${capLabel(settings.dataCapGb)} گیگابایت") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            SmallAction("ذخیره", enabled = input.isNotBlank()) {
                val normalized = input.trim()
                    .map { ch -> if (ch in '۰'..'۹') '0' + (ch - '۰') else if (ch in '٠'..'٩') '0' + (ch - '٠') else ch }
                    .joinToString("")
                    .replace('٫', '.').replace(',', '.').replace('/', '.')
                val value = normalized.toFloatOrNull()
                if (value == null || !value.isFinite() || value < ExtrasSettings.MIN_CAP_GB || value > ExtrasSettings.MAX_CAP_GB) {
                    error = "عدد معتبر بین ۰٫۵ و ۱۰۰۰۰ گیگابایت وارد کنید."
                } else {
                    store.setDataCapGb(value)
                    input = ""
                }
            }
        }
        error?.let { HelpText(it, UacColors.ErrorRed) }
    }
}

// ---------------------------------------------------------------------------
// 4. One-tap site reachability through the tunnel
// ---------------------------------------------------------------------------

private fun siteIcon(id: SiteReachability.SiteId): ImageVector = when (id) {
    SiteReachability.SiteId.WEB -> Icons.Outlined.Language
    SiteReachability.SiteId.GEMINI -> Icons.Outlined.AutoAwesome
    SiteReachability.SiteId.CHATGPT -> Icons.Outlined.SmartToy
    SiteReachability.SiteId.INSTAGRAM -> Icons.Outlined.PhotoCamera
    SiteReachability.SiteId.TELEGRAM -> Icons.AutoMirrored.Outlined.Send
    SiteReachability.SiteId.YOUTUBE -> Icons.Outlined.SmartDisplay
    SiteReachability.SiteId.GITHUB -> Icons.Outlined.Code
}

@Composable
internal fun SiteReachabilitySettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val tunnelUp = connection == ConnectionState.CONNECTED
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<SiteReachability.SiteId, SiteReachability.Result>() }
    var running by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val okCount = results.values.count { it is SiteReachability.Result.Reachable }
    val doneCount = results.values.count {
        it is SiteReachability.Result.Reachable || it is SiteReachability.Result.Restricted || it is SiteReachability.Result.Blocked
    }
    val summary = when {
        !tunnelUp -> "تونل خاموش است"
        running -> "در حال تست…"
        doneCount == SiteReachability.SITES.size -> "$okCount از ${SiteReachability.SITES.size} در دسترس"
        else -> "با یک لمس، دسترسی چند سایت را از داخل تونل بسنجید"
    }

    fun start() {
        if (running || !SiteReachability.tunnelUp()) return
        running = true
        failure = null
        results.clear()
        SiteReachability.SITES.forEach { results[it.id] = SiteReachability.Result.Pending }
        scope.launch {
            try {
                SiteReachability.runAll(app) { id, result -> results[id] = result }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                failure = "اجرای تست با خطا روبه‌رو شد. دوباره امتحان کنید."
            } finally {
                running = false
            }
        }
    }

    ExtrasRow(
        icon = Icons.Outlined.NetworkCheck,
        title = "تست دسترسی سایت‌ها",
        summary = summary,
        active = tunnelUp && doneCount > 0 && okCount > 0,
        summaryColor = if (!tunnelUp) UacColors.DisconnectingAmber else null,
        showDivider = showDivider,
    ) {
        HelpText(
            "برای هر سایت فقط یک درخواست کوچک HTTPS از مسیر تونل فرستاده می‌شود (حداکثر ۵ ثانیه، ${SiteReachability.CONCURRENCY} تا هم‌زمان). " +
                "عدد میلی‌ثانیه زمان واقعی رسیدن پاسخ است.",
        )
        if (!tunnelUp) {
            HelpText("تونل خاموش است. اول وصل شوید تا تست از مسیر سیمرغ انجام شود.", UacColors.DisconnectingAmber)
        }
        SmallAction(if (running) "در حال تست…" else "شروع تست", enabled = tunnelUp && !running) { start() }
        if (results.isNotEmpty()) {
            SiteReachability.SITES.forEach { site ->
                SiteResultRow(site, results[site.id] ?: SiteReachability.Result.Pending)
            }
        }
        failure?.let { HelpText(it, UacColors.ErrorRed) }
    }
}

@Composable
private fun SiteResultRow(site: SiteReachability.Site, result: SiteReachability.Result) {
    val (statusIcon, statusText, statusColor) = when (result) {
        SiteReachability.Result.Pending -> Triple(Icons.Outlined.HourglassEmpty, "در صف", UacColors.TextSecondary)
        SiteReachability.Result.Testing -> Triple(Icons.Outlined.HourglassEmpty, "در حال تست…", UacColors.ConnectingCyan)
        is SiteReachability.Result.Reachable ->
            Triple(Icons.Outlined.CheckCircle, "در دسترس · ${result.latencyMs} میلی‌ثانیه", UacColors.ConnectedGreen)
        is SiteReachability.Result.Restricted ->
            Triple(Icons.Outlined.Lock, "پاسخ داد ولی دسترسی محدود است (${result.httpCode}) · ${result.latencyMs} میلی‌ثانیه", UacColors.DisconnectingAmber)
        is SiteReachability.Result.Blocked ->
            Triple(Icons.Outlined.Block, "مسدود یا بدون پاسخ", UacColors.ErrorRed)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(siteIcon(site.id), null, tint = UacColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        Text(site.label, color = UacColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(0.4f))
        Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(statusText, color = statusColor, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(0.6f))
    }
}

/** Opens browserleaks.com/ip in the browser for a full DNS / WebRTC / IP leak check. */
@Composable
internal fun LeakTestSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    ExtrasRow(
        icon = Icons.Outlined.NetworkCheck,
        title = "تست نشت کامل",
        summary = "IP، DNS و WebRTC در browserleaks.com",
        active = false,
        showDivider = showDivider,
    ) {
        HelpText("صفحه‌ی browserleaks.com/ip در مرورگر باز می‌شود و IP خروجی، کشور، DNS و نشت WebRTC را نشان می‌دهد.")
        SmallAction("باز کردن تست", enabled = true) { openLeakTest(context) }
    }
}

internal fun openLeakTest(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://browserleaks.com/ip"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
