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
        key = "xray", latin = "Parvaz", persian = "پرواز",
        subtitleEn = "Your configs and subscriptions", subtitleFa = "کانفیگ‌ها و لینک اشتراک خودت",
        icon = Icons.Outlined.FlightTakeoff, mode = EngineMode.XRAY_CF,
    )
    val Sayeh = EngineInfo(
        key = "tor", latin = "Sayeh", persian = "سایه",
        subtitleEn = "Layered route over bridges", subtitleFa = "مسیر چندلایه از روی پل‌ها",
        icon = Icons.Outlined.Layers, mode = EngineMode.TOR_WEBTUNNEL,
    )
    val Shahpar = EngineInfo(
        key = "pow", latin = "Shahpar", persian = "شاهپر",
        subtitleEn = "Chains Nur, Abr and Pol", subtitleFa = "زنجیره‌ی نور، ابر و پل",
        icon = Icons.Outlined.Shield, mode = EngineMode.UAC_POW,
    )
    val Tir = EngineInfo(
        key = "direct", latin = "Tir", persian = "تیر",
        subtitleEn = "Direct, no server", subtitleFa = "مستقیم، بدون سرور",
        icon = Icons.Outlined.NearMe, mode = EngineMode.UAC_DIRECT,
    )

    // Sub-engines shown inside Shahpar, Sayeh and the live route.
    val Nur = EngineInfo(
        key = "warp", latin = "Nur", persian = "نور",
        subtitleEn = "Fast cloud tunnel", subtitleFa = "تونل ابری سریع",
        icon = Icons.Outlined.WbSunny,
    )
    val Abr = EngineInfo(
        key = "psiphon", latin = "Abr", persian = "ابر",
        subtitleEn = "Wide, resilient network", subtitleFa = "شبکه‌ی گسترده و مقاوم",
        icon = Icons.Outlined.Cloud,
    )
    val Pol = EngineInfo(
        key = "webtunnel", latin = "Pol", persian = "پل",
        subtitleEn = "Hidden web bridges", subtitleFa = "پل‌های پنهان وب",
        icon = Icons.Outlined.Link,
    )
    val Auto = EngineInfo(
        key = "auto", latin = "Auto", persian = "خودکار",
        subtitleEn = "Picks the best route", subtitleFa = "بهترین مسیر خودکار",
        icon = Icons.Outlined.AutoAwesome,
    )

    /**
     * Reserved for the Firefox VPN engine (branch foxy-engine). It stays off the rail until
     * that branch adds its [EngineMode]; then set [EngineInfo.mode] here and it appears.
     */
    val Rubah = EngineInfo(
        key = "foxy", latin = "Rubah", persian = "روباه",
        subtitleEn = "Firefox VPN", subtitleFa = "فایرفاکس وی‌پی‌ان",
        icon = Icons.Outlined.Pets,
    )

    /** Every known engine, in rail order. */
    val all: List<EngineInfo> = listOf(Parvaz, Sayeh, Shahpar, Tir, Rubah)
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
