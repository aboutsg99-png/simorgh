package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.engine.foxy.FoxyAccountState
import com.uacspoofer.mobile.engine.foxy.FoxyAccountStatus
import com.uacspoofer.mobile.engine.foxy.FoxyEngineSettings
import com.uacspoofer.mobile.engine.foxy.FoxyFallback
import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.engine.foxy.FoxyProtocol
import com.uacspoofer.mobile.engine.foxy.FoxyUiStatus
import com.uacspoofer.mobile.ui.theme.UacColors

private val FoxyAccent = Color(0xFFFF9A5C)
private val FoxyChipShape = RoundedCornerShape(12.dp)

@Composable
private fun FoxyCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Account card: who is signed in, monthly quota if Mozilla reports it, sign in / out. */
@Composable
internal fun FoxyAccountCard(
    status: FoxyAccountStatus,
    busy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    FoxyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (status.state) {
                    FoxyAccountState.OFFLINE -> Icons.Outlined.CloudOff
                    else -> Icons.Outlined.AccountCircle
                },
                contentDescription = null,
                tint = FoxyAccent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (status.state) {
                        FoxyAccountState.SIGNED_IN -> homeText("Firefox account", "حساب فایرفاکس")
                        FoxyAccountState.OFFLINE -> homeText("Signed in (offline)", "وارد شده‌ای (موزیلا در دسترس نیست)")
                        FoxyAccountState.UNKNOWN -> homeText("Checking account", "بررسی حساب")
                        FoxyAccountState.SIGNED_OUT -> homeText("Not signed in", "وارد نشده‌ای")
                    },
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val sub = when {
                    status.state == FoxyAccountState.SIGNED_OUT -> homeText(
                        "Each user signs in with their own free Firefox account.",
                        "هر کاربر با حساب رایگان فایرفاکس خودش وارد می‌شود.",
                    )
                    status.email.isNotBlank() -> homeLtr(status.email)
                    else -> ""
                }
                if (sub.isNotBlank()) Text(sub, color = UacColors.TextSecondary, fontSize = 11.sp)
                val remaining = status.quotaRemaining
                val max = status.quotaMax
                if (remaining != null) {
                    Text(
                        homeText("Quota left: ", "سهمیه‌ی باقی‌مانده: ") + homeLtr(formatFoxyBytes(remaining)) +
                            (if (max != null) " / " + homeLtr(formatFoxyBytes(max)) else ""),
                        color = UacColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = FoxyAccent)
            } else {
                val signedOut = status.state == FoxyAccountState.SIGNED_OUT
                FoxyChip(
                    label = if (signedOut) homeText("Sign in", "ورود") else homeText("Sign out", "خروج"),
                    selected = signedOut,
                    icon = if (signedOut) Icons.Outlined.Login else Icons.AutoMirrored.Outlined.Logout,
                    onClick = if (signedOut) onSignIn else onSignOut,
                )
            }
        }
    }
}

/** Protocol and fallback engine. */
@Composable
internal fun FoxySettingsPanel(
    settings: FoxyEngineSettings,
    status: FoxyUiStatus,
    onProtocol: (FoxyProtocol) -> Unit,
    onFallback: (FoxyFallback) -> Unit,
) {
    FoxyCard {
        Text(homeText("Connection protocol", "پروتکل اتصال"), color = UacColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            homeText(
                "Auto tries HTTP/3 (MASQUE over QUIC), then HTTP/2, then HTTP/1.1, and keeps the first one that measures fast.",
                "خودکار اول HTTP/3 (ماسک روی QUIC)، بعد HTTP/2 و بعد HTTP/1.1 را امتحان می‌کند و اولین مسیری که سرعت واقعی خوبی بدهد را نگه می‌دارد.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FoxyProtocol.entries.forEach { protocol ->
                FoxyChip(
                    label = if (protocol == FoxyProtocol.AUTO) homeText("Auto", "خودکار") else homeLtr(protocol.label),
                    selected = settings.protocol == protocol,
                    onClick = { onProtocol(protocol) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(homeText("If Firefox VPN is weak, switch to", "اگر Firefox VPN ضعیف بود، برو سراغ"), color = UacColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FoxyChip(homeText("Xray", "Xray"), settings.fallback == FoxyFallback.PARVAZ) { onFallback(FoxyFallback.PARVAZ) }
            FoxyChip(homeText("Psiphon/WARP", "Psiphon/WARP"), settings.fallback == FoxyFallback.SHAHPAR) { onFallback(FoxyFallback.SHAHPAR) }
            FoxyChip(homeText("Don't switch", "عوض نکن"), settings.fallback == FoxyFallback.NONE) { onFallback(FoxyFallback.NONE) }
        }
        if (status.phase != FoxyPhase.IDLE && status.detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                status.detail,
                color = when (status.phase) {
                    FoxyPhase.FAILED, FoxyPhase.FALLBACK -> UacColors.ErrorRed
                    FoxyPhase.CONNECTED -> UacColors.ConnectedGreen
                    else -> UacColors.TextSecondary
                },
                fontSize = 12.sp,
            )
            status.tried.forEach { path ->
                val speed = path.bytesPerSecond?.let { formatFoxyBytes(it) + "/s" } ?: "-"
                val latency = path.latencyMs?.let { "$it ms" } ?: "-"
                Text(
                    homeLtr("${FoxyProtocol.labelFor(path.version)}  $latency  $speed") +
                        "  " + if (path.ok) homeText("usable", "قابل استفاده") else homeText("failed", "ناموفق"),
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun FoxyChip(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(if (selected) FoxyAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f), FoxyChipShape)
            .border(1.dp, if (selected) FoxyAccent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.10f), FoxyChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = UacColors.TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = UacColors.TextPrimary, fontSize = 12.sp)
    }
}

internal fun formatFoxyBytes(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb -> String.format(java.util.Locale.US, "%.1f GB", bytes / (kb * kb * kb))
        bytes >= kb * kb -> String.format(java.util.Locale.US, "%.1f MB", bytes / (kb * kb))
        bytes >= kb -> String.format(java.util.Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
