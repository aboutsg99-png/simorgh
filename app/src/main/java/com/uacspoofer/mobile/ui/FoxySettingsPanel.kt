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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.ui.shell.sxCard
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.engine.foxy.FoxyAccountState
import com.uacspoofer.mobile.engine.foxy.FoxyAccountStatus
import com.uacspoofer.mobile.engine.foxy.FoxyEngineSettings
import com.uacspoofer.mobile.engine.foxy.FoxyFallback
import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.engine.foxy.FoxyProtocol
import com.uacspoofer.mobile.engine.foxy.FoxyUiStatus
import com.uacspoofer.mobile.ui.theme.UacColors

private val FoxyAccent: Color get() = UacColors.DisconnectedBlue
private val FoxyChipShape = RoundedCornerShape(12.dp)

@Composable
private fun FoxyCard(content: @Composable () -> Unit) {
    val sx = com.uacspoofer.mobile.ui.shell.LocalSx.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = com.uacspoofer.mobile.ui.shell.Vazir),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().sxCard(sx).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
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
                if (sub.isNotBlank()) Text(sub, color = UacColors.TextSecondary, fontSize = 12.5.sp)
                val remaining = status.quotaRemaining
                val max = status.quotaMax
                if (remaining != null) {
                    Text(
                        homeText("Quota left: ", "سهمیه‌ی باقی‌مانده: ") + homeLtr(formatFoxyBytes(remaining)) +
                            (if (max != null) " / " + homeLtr(formatFoxyBytes(max)) else ""),
                        color = UacColors.TextSecondary,
                        fontSize = 12.5.sp,
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
    FoxyTransportCard()
    FoxyCard {
        Text(homeText("Connection protocol", "پروتکل اتصال"), color = UacColors.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        Text(
            homeText(
                "Auto tries HTTP/3 (MASQUE over QUIC), then HTTP/2, then HTTP/1.1, and keeps the first one that measures fast.",
                "خودکار اول HTTP/3 (ماسک روی QUIC)، بعد HTTP/2 و بعد HTTP/1.1 را امتحان می‌کند و اولین مسیری که سرعت واقعی خوبی بدهد را نگه می‌دارد.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 12.5.sp,
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
        Text(homeText("If Firefox VPN is weak, switch to", "اگر Firefox VPN ضعیف بود، برو سراغ"), color = UacColors.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
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
                    fontSize = 12.5.sp,
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
    com.uacspoofer.mobile.ui.shell.SChip(label, selected = selected, icon = icon, onClick = onClick)
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

/**
 * How Firefox VPN reaches Mozilla: HTTPS (MASQUE, the free Firefox account) or Mozilla VPN
 * WireGuard (own subscription; plain UDP, over TCP, or inside Shadowsocks). HTTPS stays the
 * fallback of every WireGuard choice.
 */
@Composable
private fun FoxyTransportCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = androidx.compose.runtime.remember(context) { com.uacspoofer.mobile.engine.foxy.FoxyEngineStore.get(context) }
    val settings by store.settings.collectAsState()
    val account = androidx.compose.runtime.remember(context) { com.uacspoofer.mobile.engine.foxy.MozillaVpnAccount.get(context) }
    val signedIn by account.signedIn.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var signing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var message by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val verifier = androidx.compose.runtime.remember(signing) { com.uacspoofer.mobile.engine.foxy.MozillaWg.newVerifier() }
    FoxyCard {
        Text(homeText("Route to Mozilla", "مسیر رسیدن به موزیلا"), color = UacColors.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        Text(
            homeText(
                "WireGuard modes need a Mozilla VPN subscription. If they fail, HTTPS is used.",
                "حالت‌های WireGuard اشتراک Mozilla VPN می‌خواهند. اگر وصل نشوند، HTTPS امتحان می‌شود.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 12.5.sp,
        )
        // WireGuard modes only make sense with a Mozilla VPN sign-in; without one HTTPS is the route.
        com.uacspoofer.mobile.engine.foxy.FoxyTransport.entries
            .filter { signedIn || it == com.uacspoofer.mobile.engine.foxy.FoxyTransport.MASQUE }
            .forEach { t ->
                val selected = settings.transport == t ||
                    (!signedIn && t == com.uacspoofer.mobile.engine.foxy.FoxyTransport.MASQUE)
                FoxyChip(label = t.fa, selected = selected, onClick = { store.update { it.copy(transport = t) } })
            }
        Spacer(Modifier.height(4.dp))
        Text(homeText("Anti-filter (HTTPS)", "ضدفیلتر (HTTPS)"), color = UacColors.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        Text(
            homeText(
                "Auto tries plain TLS, a fragmented handshake and a clean edge IP, and remembers what worked on this network.",
                "خودکار TLS ساده، دست‌دادن تکه‌تکه و IP تمیز را امتحان می‌کند و روش موفق را برای همین شبکه به خاطر می‌سپارد.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 12.5.sp,
        )
        com.uacspoofer.mobile.engine.foxy.AntiFilterMethod.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    FoxyChip(label = homeText(m.en, m.fa), selected = settings.antiFilter == m, onClick = { store.update { it.copy(antiFilter = m) } })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (signedIn) {
                Text(homeText("Mozilla VPN: signed in", "Mozilla VPN: وارد شده‌ای"), color = UacColors.TextSecondary, fontSize = 12.sp)
                FoxyChip(homeText("Sign out", "خروج"), false) { account.signOut() }
            } else {
                FoxyChip(homeText("Sign in to Mozilla VPN", "ورود به Mozilla VPN"), false) { message = null; signing = true }
            }
        }
    }
    if (signing) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { signing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FoxySignInScreen(
                startUrl = com.uacspoofer.mobile.engine.foxy.MozillaWg.loginUrl(verifier),
                busy = busy,
                message = message,
                onSessionCaptured = { },
                onCancel = { signing = false },
                onUrl = { url ->
                    val code = com.uacspoofer.mobile.engine.foxy.MozillaWg.codeFromRedirect(url)
                    if (code != null && !busy) {
                        busy = true
                        scope.launch {
                            val result = account.completeLogin(code, verifier)
                            busy = false
                            result.onSuccess { signing = false }.onFailure { message = it.message ?: "ورود ناموفق بود" }
                        }
                    }
                    code != null
                },
            )
        }
    }
}
