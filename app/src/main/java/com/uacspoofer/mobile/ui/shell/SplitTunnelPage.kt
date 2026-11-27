package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.uacspoofer.mobile.vpn.AppRoutingMode
import com.uacspoofer.mobile.vpn.AppRoutingPreferences
import com.uacspoofer.mobile.vpn.InstalledVpnApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun modeTitle(m: AppRoutingMode) = when (m) {
    AppRoutingMode.ALL_APPS -> "همه‌ی برنامه‌ها از VPN"
    AppRoutingMode.BYPASS_SELECTED -> "برنامه‌های انتخابی بیرون از تونل"
    AppRoutingMode.VPN_ONLY_SELECTED -> "فقط برنامه‌های انتخابی از VPN"
}

private fun modeHint(m: AppRoutingMode) = when (m) {
    AppRoutingMode.ALL_APPS -> "همه‌ی ترافیک گوشی از تونل می‌گذرد (پیشنهادی)."
    AppRoutingMode.BYPASS_SELECTED -> "مثلاً بانک‌ها و برنامه‌های ایرانی مستقیم وصل شوند و بقیه از VPN."
    AppRoutingMode.VPN_ONLY_SELECTED -> "فقط برنامه‌هایی که تیک می‌زنید از VPN می‌گذرند؛ بقیه مستقیم."
}

/** Split tunnelling (برنامه‌های خارج از تونل): which apps use the tunnel. */
@Composable
internal fun SplitTunnelPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    AppRoutingPreferences.initialize(context)
    val settings by AppRoutingPreferences.settings.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledVpnApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyPicked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { runCatching { AppRoutingPreferences.installedApps(context) }.getOrDefault(emptyList()) } }
    val list = remember(apps, query, settings.selectedPackages, onlyPicked) {
        val q = query.trim().lowercase()
        apps.orEmpty()
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .filter { !onlyPicked || it.packageName in settings.selectedPackages }
            .sortedWith(compareByDescending<InstalledVpnApp> { it.packageName in settings.selectedPackages }.thenBy { it.label.lowercase() })
    }

    SubPage("برنامه‌های خارج از تونل", onBack, subtitle = "کدام برنامه‌ها از VPN بگذرند و کدام مستقیم", spacing = 10.dp) {
        item(key = "mode") {
            SGroup("حالت") {
                AppRoutingMode.entries.forEachIndexed { i, m ->
                    if (i > 0) RowDivider(48.dp)
                    SRadioRow(modeTitle(m), modeHint(m), settings.mode == m) { AppRoutingPreferences.setMode(context, m) }
                }
            }
        }
        if (settings.mode == AppRoutingMode.ALL_APPS) {
            item(key = "all") { SNote("همه‌ی برنامه‌های نصب‌شده از تونل عبور می‌کنند. برای انتخاب برنامه‌ها، یکی از دو حالت دیگر را بزنید.") }
        } else {
            item(key = "search") { SField(query, { query = it }, "جست‌وجوی برنامه (نام یا بسته)") }
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    SText(
                        if (settings.mode == AppRoutingMode.BYPASS_SELECTED) "تیک‌خورده‌ها مستقیم وصل می‌شوند" else "تیک‌خورده‌ها از VPN می‌گذرند",
                        size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    SChip("${settings.selectedPackages.size} انتخاب", selected = onlyPicked) { onlyPicked = !onlyPicked }
                }
            }
            when {
                apps == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = sx.accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    }
                }
                list.isEmpty() -> item(key = "none") { SNote(if (onlyPicked) "هنوز برنامه‌ای انتخاب نشده." else "برنامه‌ای پیدا نشد.") }
                else -> items(list, key = { it.packageName }, contentType = { "app" }) { app ->
                    val picked = app.packageName in settings.selectedPackages
                    AppRow(app, picked) { AppRoutingPreferences.setPackageSelected(context, app.packageName, !picked) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledVpnApp, picked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    var icon by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        icon = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().sxCard(sx, highlight = picked).sxClick(onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ic = icon
        if (ic != null) {
            Image(ic, null, Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)))
        } else {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(sx.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                SText(app.label.take(1).uppercase(), size = 17.sp, weight = FontWeight.Bold, color = sx.accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText(app.label, size = 15.sp, weight = FontWeight.SemiBold)
            SText(app.packageName, size = 12.sp, color = sx.textDim, ltr = true, latin = true)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (picked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            if (picked) "انتخاب‌شده" else "انتخاب نشده",
            tint = if (picked) sx.accent else sx.textFaint, modifier = Modifier.size(24.dp),
        )
    }
}
