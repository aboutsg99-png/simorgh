package com.uacspoofer.mobile.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.uacspoofer.mobile.engine.EngineMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** User-facing engine name: the real name (Xray, Tor + WebTunnel, ...), no codenames. */
val EngineMode.fa: String
    get() = com.uacspoofer.mobile.simorgh.engineDisplayName(this)

val EngineMode.icon: ImageVector
    get() = when (this) {
        EngineMode.XRAY_CF -> Icons.Outlined.FlightTakeoff
        EngineMode.TOR_WEBTUNNEL -> Icons.Outlined.Layers
        EngineMode.UAC_POW -> Icons.Outlined.Shield
        EngineMode.UAC_DIRECT -> Icons.Outlined.NearMe
        EngineMode.FOXY_FIREFOX -> Icons.Outlined.Pets
        EngineMode.WIREGUARD -> Icons.Outlined.VpnKey
    }

val EngineMode.descFa: String
    get() = when (this) {
        EngineMode.XRAY_CF -> "VLESS، VMess، Trojan، Reality · کانفیگ و اشتراک خودتان"
        EngineMode.TOR_WEBTUNNEL -> "شبکه‌ی Tor از روی پل‌های WebTunnel"
        EngineMode.UAC_POW -> "Cloudflare WARP و Psiphon"
        EngineMode.UAC_DIRECT -> "مستقیم، بدون سرور (عبور از DPI)"
        EngineMode.FOXY_FIREFOX -> "Firefox VPN (MASQUE) با حساب خودتان"
        EngineMode.WIREGUARD -> "کانفیگ WireGuard خودتان، Proton VPN یا Windscribe با حساب خودتان"
    }

object SubEngines {
    data class Sub(val key: String, val fa: String, val latin: String, val desc: String, val icon: ImageVector)
    val NUR = Sub("warp", "Cloudflare WARP", "WARP", "تونل WireGuard/MASQUE کلودفلر", Icons.Outlined.WbSunny)
    val ABR = Sub("psiphon", "Psiphon", "Psiphon", "شبکه‌ی گسترده و مقاوم Psiphon", Icons.Outlined.Cloud)
    val POL = Sub("webtunnel", "WebTunnel", "WebTunnel", "پل‌های پنهان وب برای Tor", Icons.Outlined.Link)
    val AUTO_ICON = Icons.Outlined.AutoAwesome
    val all = listOf(NUR, ABR, POL)
}

fun formatBytesFa(bytes: Long): Pair<String, String> {
    val b = bytes.coerceAtLeast(0L).toDouble()
    return when {
        b >= 1e9 -> String.format(Locale.US, "%.1f", b / 1e9) to "گیگابایت"
        b >= 1e6 -> String.format(Locale.US, if (b >= 1e8) "%.0f" else "%.1f", b / 1e6) to "مگابایت"
        b >= 1e3 -> String.format(Locale.US, "%.0f", b / 1e3) to "کیلوبایت"
        else -> String.format(Locale.US, "%.0f", b) to "بایت"
    }
}

fun formatDurationFa(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h ساعت $m دقیقه"
        h > 0 -> "$h ساعت"
        m > 0 -> "$m دقیقه"
        else -> "کمتر از ۱ دقیقه"
    }
}

fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/** Persian weekday initial of an epoch day: ش ی د س چ پ ج. */
fun weekdayFa(epochDay: Long): String = when (LocalDate.ofEpochDay(epochDay).dayOfWeek.value) {
    6 -> "ش"; 7 -> "ی"; 1 -> "د"; 2 -> "س"; 3 -> "چ"; 4 -> "پ"; else -> "ج"
}

fun relativeDayFa(ms: Long): String {
    if (ms <= 0) return ""
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (val diff = today.toEpochDay() - day.toEpochDay()) {
        0L -> "امروز"
        1L -> "دیروز"
        else -> "$diff روز پیش"
    }
}

fun timeAgoFa(ms: Long): String {
    if (ms <= 0) return ""
    val min = (System.currentTimeMillis() - ms) / 60_000L
    return when {
        min < 1 -> "همین حالا"
        min < 60 -> "$min دقیقه پیش"
        min < 1440 -> "${min / 60} ساعت پیش"
        else -> "${min / 1440} روز پیش"
    }
}

fun mbps(v: Double?): String = when {
    v == null -> "—"
    v >= 100 -> String.format(Locale.US, "%.0f", v)
    v >= 10 -> String.format(Locale.US, "%.1f", v)
    else -> String.format(Locale.US, "%.2f", v)
}

/** Official channel (owner, 1.1). */
const val SIMORGH_TELEGRAM_HANDLE = "@theacproject"
const val SIMORGH_TELEGRAM_URL = "https://t.me/theacproject"

fun openTelegramChannel(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SIMORGH_TELEGRAM_URL))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
