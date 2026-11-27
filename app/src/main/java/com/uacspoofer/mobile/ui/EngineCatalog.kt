package com.uacspoofer.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.uacspoofer.mobile.engine.EngineMode

/**
 * Display identity of an engine: Latin and Persian name, a one-line subtitle and a
 * Material icon. Only the visual layer reads this; engine logic keeps using [EngineMode].
 */
internal data class EngineInfo(
    val key: String,
    val latin: String,
    val persian: String,
    val subtitleEn: String,
    val subtitleFa: String,
    val icon: ImageVector,
    /** The selectable engine, or null for a sub-engine or one that is not shipped yet. */
    val mode: EngineMode? = null,
)

@Composable
internal fun EngineInfo.displayName(): String = homeText(latin, persian)

@Composable
internal fun EngineInfo.subtitle(): String = homeText(subtitleEn, subtitleFa)

internal object EngineCatalog {
    val Parvaz = EngineInfo(
        key = "xray", latin = "Xray", persian = "Xray",
        subtitleEn = "Your configs and subscriptions", subtitleFa = "کانفیگ‌ها و لینک اشتراک خودت",
        icon = Icons.Outlined.FlightTakeoff, mode = EngineMode.XRAY_CF,
    )
    val Sayeh = EngineInfo(
        key = "tor", latin = "Tor", persian = "Tor",
        subtitleEn = "Layered route over bridges", subtitleFa = "مسیر چندلایه از روی پل‌ها",
        icon = Icons.Outlined.Layers, mode = EngineMode.TOR_WEBTUNNEL,
    )
    val Shahpar = EngineInfo(
        key = "pow", latin = "Psiphon/WARP", persian = "Psiphon/WARP",
        subtitleEn = "Chains WARP, Psiphon and WebTunnel", subtitleFa = "زنجیره‌ی WARP، Psiphon و WebTunnel",
        icon = Icons.Outlined.Shield, mode = EngineMode.UAC_POW,
    )
    val Tir = EngineInfo(
        key = "direct", latin = "Direct", persian = "Direct",
        subtitleEn = "Direct, no server", subtitleFa = "مستقیم، بدون سرور",
        icon = Icons.Outlined.NearMe, mode = EngineMode.UAC_DIRECT,
    )

    // Sub-engines shown inside Shahpar, Sayeh and the live route.
    val Nur = EngineInfo(
        key = "warp", latin = "WARP", persian = "WARP",
        subtitleEn = "Fast cloud tunnel", subtitleFa = "تونل ابری سریع",
        icon = Icons.Outlined.WbSunny,
    )
    val Abr = EngineInfo(
        key = "psiphon", latin = "Psiphon", persian = "Psiphon",
        subtitleEn = "Wide, resilient network", subtitleFa = "شبکه‌ی گسترده و مقاوم",
        icon = Icons.Outlined.Cloud,
    )
    val Pol = EngineInfo(
        key = "webtunnel", latin = "WebTunnel", persian = "WebTunnel",
        subtitleEn = "Hidden web bridges", subtitleFa = "پل‌های پنهان وب",
        icon = Icons.Outlined.Link,
    )
    val Auto = EngineInfo(
        key = "auto", latin = "Auto", persian = "خودکار",
        subtitleEn = "Picks the best route", subtitleFa = "بهترین مسیر خودکار",
        icon = Icons.Outlined.AutoAwesome,
    )

    /** Engine 5: Firefox VPN with the user's own Firefox account; country page holds sign-in. */
    val Rubah = EngineInfo(
        key = "foxy", latin = "Firefox VPN", persian = "Firefox VPN",
        subtitleEn = "Firefox VPN", subtitleFa = "فایرفاکس وی‌پی‌ان",
        icon = Icons.Outlined.Pets, mode = EngineMode.FOXY_FIREFOX,
    )

    /** Engine 6: WireGuard (own configs, Proton VPN, Windscribe) on Xray's userspace outbound. */
    val WireGuard = EngineInfo(
        key = "wireguard", latin = "WireGuard", persian = "WireGuard",
        subtitleEn = "Your WireGuard, Proton VPN, Windscribe", subtitleFa = "کانفیگ WireGuard خودت، Proton VPN، Windscribe",
        icon = Icons.Outlined.VpnKey, mode = EngineMode.WIREGUARD,
    )

    /** Every known engine, in rail order. */
    val all: List<EngineInfo> = listOf(Parvaz, Sayeh, Shahpar, Tir, Rubah, WireGuard)
    val subEngines: List<EngineInfo> = listOf(Nur, Abr, Pol, Auto)

    /** Engines that can be picked on the home rail: one per shipped [EngineMode]. */
    val rail: List<EngineInfo> by lazy {
        EngineMode.entries.map { mode -> all.firstOrNull { it.mode == mode } ?: fallback(mode) }
    }

    fun of(mode: EngineMode): EngineInfo = all.firstOrNull { it.mode == mode } ?: fallback(mode)

    private fun fallback(mode: EngineMode) = EngineInfo(
        key = mode.name.lowercase(), latin = mode.name, persian = mode.name,
        subtitleEn = "", subtitleFa = "", icon = Icons.Outlined.AutoAwesome, mode = mode,
    )
}

internal val EngineMode.info: EngineInfo get() = EngineCatalog.of(this)
