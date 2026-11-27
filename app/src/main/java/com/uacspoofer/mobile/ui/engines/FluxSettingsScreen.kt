package com.uacspoofer.mobile.ui.engines

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.simorgh.countryName
import com.uacspoofer.mobile.simorgh.flux.FluxPicker
import com.uacspoofer.mobile.simorgh.flux.FluxPlanner
import com.uacspoofer.mobile.simorgh.flux.FluxStore
import com.uacspoofer.mobile.simorgh.flux.IpMode
import com.uacspoofer.mobile.ui.PowCompactHeader
import com.uacspoofer.mobile.ui.ToolPageScaffold
import com.uacspoofer.mobile.ui.theme.UacColors

/**
 * Flux, inside Auto: the user picks a country and an IP version; Flux picks the config/tunnel,
 * the order and the fallbacks, and learns per network what really worked.
 */
@Composable
internal fun FluxSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { FluxStore.get(context) }
    val prefs by store.preferences.collectAsStateWithLifecycle()
    var forgotten by remember { mutableStateOf(false) }
    val countries = remember(context) {
        FluxPlanner.countries(FluxPicker.routesFor(context, EngineMode.XRAY_CF) + FluxPicker.routesFor(context, EngineMode.WIREGUARD))
    }
    val accent = UacColors.DisconnectedBlue
    ToolPageScaffold(
        accent = accent,
        header = {
            PowCompactHeader(accent = accent, onBack = onBack, title = "Flux", subtitle = "انتخاب هوشمند مسیر در حالت خودکار", icon = Icons.Outlined.AutoAwesome)
        },
    ) {
        item(key = "intro") {
            EngineCard {
                EngineToggleRow(
                    "Flux روشن باشد",
                    "در حالت خودکار، برای Xray و WireGuard بهترین کانفیگ را بر اساس نتیجهٔ واقعی روی همین شبکه انتخاب می‌کند و اگر وصل نشد سراغ بعدی می‌رود.",
                    prefs.enabled,
                ) { on -> store.updatePrefs { it.copy(enabled = on) } }
            }
        }
        item(key = "country") {
            EngineCard {
                EngineTitle("کشور")
                EngineBody("کشورهای تأییدشده (با بررسی آی‌پی خروجی) جلوتر می‌آیند؛ نام کانفیگ فقط یک سرنخ است.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EngineChip("هر کشوری", prefs.country == null) { store.updatePrefs { it.copy(country = null) } }
                    countries.forEach { cc ->
                        EngineChip("${flagOf(cc)} ${countryName(cc)}", prefs.country == cc) { store.updatePrefs { it.copy(country = cc) } }
                    }
                }
            }
        }
        item(key = "ip") {
            EngineCard {
                EngineTitle("نسخهٔ IP")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EngineChip("هر دو", prefs.ipMode == IpMode.BOTH) { store.updatePrefs { it.copy(ipMode = IpMode.BOTH) } }
                    EngineChip("فقط IPv4", prefs.ipMode == IpMode.V4) { store.updatePrefs { it.copy(ipMode = IpMode.V4) } }
                    EngineChip("فقط IPv6", prefs.ipMode == IpMode.V6) { store.updatePrefs { it.copy(ipMode = IpMode.V6) } }
                }
                EngineBody("اگر شبکه IPv6 نداشته باشد، «فقط IPv6» چیزی پیدا نمی‌کند.")
            }
        }
        item(key = "failover") {
            EngineCard {
                EngineTitle("جایگزینی")
                EngineBody("چند کانفیگ از هر موتور امتحان شود، پیش از رفتن سراغ موتور بعدی.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { n -> EngineChip("$n", prefs.failover == n) { store.updatePrefs { it.copy(failover = n) } } }
                }
                EngineTitle("مسابقهٔ پیش از اتصال (WireGuard)")
                EngineBody("چند سرور هم‌زمان با درخواست واقعی سنجیده شوند تا سریع‌ترین اول وصل شود. ۰ یعنی بدون مسابقه.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 2, 4, 6).forEach { n -> EngineChip("$n", prefs.raceSize == n) { store.updatePrefs { it.copy(raceSize = n) } } }
                }
            }
        }
        item(key = "forget") {
            EngineCard {
                EngineBody(if (forgotten) "حافظهٔ Flux پاک شد." else "Flux برای هر شبکه (وای‌فای / سیم‌کارت) جداگانه یاد می‌گیرد. این حافظه فقط روی همین دستگاه است.")
                EngineSecondaryButton("پاک کردن حافظهٔ Flux") { store.forgetAll(); forgotten = true }
            }
        }
    }
}
