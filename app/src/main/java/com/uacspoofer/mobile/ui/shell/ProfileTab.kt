package com.uacspoofer.mobile.ui.shell

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Share
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

@Composable
internal fun ProfileTab(onOpen: (Route) -> Unit, onOpenEngines: () -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val powerFlow = remember(context) { PowerMode.flow(context) }
    val power by powerFlow.collectAsStateWithLifecycle(remember(context) { PowerMode.current(context) })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "t") { ScreenTitle("پروفایل") }
        item(key = "id") { Column(Modifier.fillMaxWidth().sxCard(sx)) { AppIdentityRow() } }
        item(key = "battery") {
            SGroup("حالت باتری") {
                Column(Modifier.padding(12.dp)) {
                    SSegmented(PowerMode.entries, power, { it.titleFa }) { PowerMode.set(context, it) }
                    Spacer(Modifier.size(8.dp))
                    SText(power.subtitleFa, size = 12.sp, color = sx.textDim, maxLines = 3)
                }
                DividerLine()
                SRow(Icons.Outlined.BatteryChargingFull, "باتری و مصرف", "بهینه‌سازی باتری اندروید و صرفه‌جویی جزئی") { onOpen(Route.BATTERY) }
            }
        }
        item(key = "conn") {
            SGroup("اتصال") {
                SRow(Icons.Outlined.Public, "سرورها و کانفیگ‌ها", "افزودن کانفیگ و اشتراک، Firefox VPN، پینگ") { onOpen(Route.SERVERS) }
                RowDivider()
                SRow(SubEngines.AUTO_ICON, "موتورها", "Auto، ترتیب امتحان و تنظیمات هر موتور") { onOpenEngines() }
                RowDivider()
                SRow(Icons.Outlined.Apps, "برنامه‌های خارج از تونل", "برنامه‌هایی که از وی‌پی‌ان عبور نکنند") { onOpen(Route.APP_BYPASS) }
                RowDivider()
                SRow(Icons.Outlined.Share, "اشتراک‌گذاری کانفیگ‌ها", "کپی، QR یا ارسال لینک کانفیگ و اشتراک") { onOpen(Route.SHARE) }
            }
        }
        item(key = "app") {
            SGroup("برنامه") {
                SRow(Icons.Outlined.Settings, "تنظیمات", "ظاهر، اتصال، امنیت و ابزارها") { onOpen(Route.SETTINGS) }
                RowDivider()
                SRow(Icons.Outlined.Palette, "شخصی‌سازی", "پوسته، رنگ، دکمه و کارت‌های خانه") { onOpen(Route.PERSONALIZE) }
            }
        }
        item(key = "tools") {
            SGroup("ابزارها") {
                SRow(Icons.Outlined.Route, "تست مسیر Xray", "بهترین مسیر برای کانفیگ انتخاب‌شده") { onOpen(Route.ROUTE_TEST) }
                RowDivider()
                SRow(Icons.Outlined.Build, "سازنده SNI", "ساخت و آزمایش کانفیگ با SNI") { onOpen(Route.SNI_MAKER) }
                RowDivider()
                SRow(Icons.AutoMirrored.Outlined.ListAlt, "لاگ زنده", "رویدادهای اتصال برای عیب‌یابی") { onOpen(Route.LIVE_LOGS) }
                RowDivider()
                SRow(Icons.Outlined.Security, "تست نشت کامل", "IP، DNS و WebRTC در browserleaks.com") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://browserleaks.com/ip")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        item(key = "about") {
            SGroup("پشتیبانی") {
                SRow(Icons.AutoMirrored.Outlined.Send, "کانال رسمی تلگرام", SIMORGH_TELEGRAM_HANDLE) { openTelegramChannel(context) }
                RowDivider()
                SRow(Icons.Outlined.SupportAgent, "پشتیبانی و به‌روزرسانی", "نسخه‌ی جدید، کد منبع و مجوزها") { onOpen(Route.SUPPORT) }
                RowDivider()
                SRow(Icons.Outlined.BugReport, "گزارش مشکل", "فایل لاگ، گزارش خطا و مشخصات دستگاه") { onOpen(Route.REPORT) }
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

/** Brand wordmark (mark + BLACKDEY + بلک دی) on its black tile, with the version: top of About. */
@Composable
internal fun BrandWordmarkCard() {
    val sx = LocalSx.current
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(sx.radius))
            .background(androidx.compose.ui.graphics.Color.Black).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.uacspoofer.mobile.R.drawable.brand_wordmark), "Blackdey · بلک دی",
            Modifier.height(170.dp),
        )
        Spacer(Modifier.height(6.dp))
        SText("نسخه‌ی ${BuildConfig.VERSION_NAME} · متن‌باز (AGPL-3.0)", size = 12.5.sp, color = androidx.compose.ui.graphics.Color(0xFFB8B8B8))
    }
}

/** App name + logo, shown at the top of the about section. */
@Composable
internal fun AppIdentityRow() {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.uacspoofer.mobile.R.drawable.brand_logo), null,
            Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText("بلک دی", size = 18.sp, weight = FontWeight.Bold)
            SText("Blackdey VPN · نسخه‌ی ${BuildConfig.VERSION_NAME}", size = 12.5.sp, color = sx.textDim, ltr = false)
        }
    }
}
