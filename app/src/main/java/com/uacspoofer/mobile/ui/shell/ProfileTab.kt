package com.uacspoofer.mobile.ui.shell

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.simorgh.PowerMode
import com.uacspoofer.mobile.ui.DrawerDestination

@Composable
internal fun ProfileTab(onOpenLegacy: (DrawerDestination) -> Unit, onOpenPage: (ShellPage) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val powerFlow = remember(context) { PowerMode.flow(context) }
    val power by powerFlow.collectAsStateWithLifecycle(remember(context) { PowerMode.current(context) })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "t") { ScreenTitle("پروفایل") }
        item(key = "battery-t") { SectionTitle("حالت باتری") }
        item(key = "battery") {
            Column(Modifier.fillMaxWidth().sxCard(sx).padding(12.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh.copy(alpha = 0.6f)).padding(4.dp)) {
                    PowerMode.entries.forEach { m ->
                        val on = m == power
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (on) sx.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .sxClick { PowerMode.set(context, m) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { SText(m.titleFa, size = 14.sp, color = if (on) sx.accent else sx.textDim, weight = if (on) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
                Spacer(Modifier.size(8.dp))
                SText(power.subtitleFa, size = 12.sp, color = sx.textDim, maxLines = 3)
            }
        }
        item(key = "app-t") { SectionTitle("برنامه") }
        item(key = "app") {
            Column(Modifier.fillMaxWidth().sxCard(sx)) {
                MenuRow(Icons.Outlined.Palette, "شخصی‌سازی", "پوسته، رنگ، دکمه، کارت‌های خانه") { onOpenPage(ShellPage.PERSONALIZE) }
                DividerLine()
                MenuRow(SubEngines.AUTO_ICON, "موتورها", "خودکار، ترتیب جایگزینی و وضعیت هر موتور") { onOpenPage(ShellPage.ENGINES) }
                DividerLine()
                MenuRow(Icons.Outlined.Settings, "تنظیمات", "اتصال، شبکه، امنیت و ابزارها") { onOpenLegacy(DrawerDestination.SETTINGS) }
                DividerLine()
                MenuRow(Icons.Outlined.Public, "سرورها، کانفیگ‌ها و اشتراک‌ها", "افزودن از کلیپ‌بورد، فایل یا QR؛ ورود به Firefox VPN") { onOpenPage(ShellPage.SERVERS) }
                DividerLine()
                MenuRow(Icons.Outlined.Dns, "کانفیگ‌ها و کشورهای موتور فعلی", "مدیریت کامل کانفیگ‌ها یا کشورهای موتور") { onOpenLegacy(DrawerDestination.CONFIGS) }
                DividerLine()
                MenuRow(Icons.Outlined.Apps, "برنامه‌های خارج از تونل", "انتخاب برنامه‌هایی که از وی‌پی‌ان رد نشوند") { onOpenLegacy(DrawerDestination.APP_BYPASS) }
                DividerLine()
                MenuRow(Icons.Outlined.BatteryChargingFull, "مدیریت باتری پیشرفته", "جزئیات حالت‌های مصرف") { onOpenLegacy(DrawerDestination.BATTERY) }
            }
        }
        item(key = "tools-t") { SectionTitle("ابزارها") }
        item(key = "tools") {
            Column(Modifier.fillMaxWidth().sxCard(sx)) {
                MenuRow(Icons.Outlined.Security, "تست نشت کامل", "IP، DNS و WebRTC در browserleaks.com") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://browserleaks.com/ip")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                DividerLine()
                MenuRow(Icons.Outlined.Route, "تست مسیر Xray", "یافتن بهترین مسیر برای کانفیگ انتخاب‌شده") { onOpenLegacy(DrawerDestination.ROUTE_SPEED_TEST) }
                DividerLine()
                MenuRow(Icons.Outlined.Build, "سازنده SNI", "ساخت و آزمایش SNI") { onOpenLegacy(DrawerDestination.SNI_MAKER) }
                DividerLine()
                MenuRow(Icons.AutoMirrored.Outlined.ListAlt, "لاگ زنده", "رویدادهای اتصال برای عیب‌یابی") { onOpenLegacy(DrawerDestination.LIVE_LOGS) }
            }
        }
        item(key = "about-t") { SectionTitle("درباره") }
        item(key = "about") {
            Column(Modifier.fillMaxWidth().sxCard(sx)) {
                AppIdentityRow()
                DividerLine()
                MenuRow(Icons.AutoMirrored.Outlined.Send, "کانال رسمی تلگرام", "$SIMORGH_TELEGRAM_HANDLE · t.me/theacproject") { openTelegramChannel(context) }
                DividerLine()
                MenuRow(Icons.Outlined.SupportAgent, "پشتیبانی و به‌روزرسانی", "بررسی نسخه‌ی جدید، مجوزها") { onOpenLegacy(DrawerDestination.SUPPORT) }
                DividerLine()
                MenuRow(Icons.Outlined.Info, "Simorgh ${BuildConfig.VERSION_NAME}", "داده‌ی نقشه: Natural Earth (مالکیت عمومی) · متن‌باز AGPL-3.0") { onOpenLegacy(DrawerDestination.SUPPORT) }
            }
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().sxClick(onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBox(icon, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText(title, size = 15.sp, weight = FontWeight.SemiBold)
            SText(subtitle, size = 12.sp, color = sx.textDim)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = sx.textFaint)
    }
}

/** App name + logo, shown at the top of the about section. */
@Composable
internal fun AppIdentityRow() {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.uacspoofer.mobile.R.mipmap.ic_launcher_round), null,
            Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText("سیمرغ", size = 18.sp, weight = FontWeight.Bold)
            SText("Simorgh VPN · نسخه‌ی ${BuildConfig.VERSION_NAME}", size = 12.5.sp, color = sx.textDim, ltr = false)
        }
    }
}
