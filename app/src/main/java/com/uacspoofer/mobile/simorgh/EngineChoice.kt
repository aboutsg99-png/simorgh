package com.uacspoofer.mobile.simorgh

import android.content.Context
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeChangeResult
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.pow.PowEngineSettings
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.simorgh.auto.AutoEngineStore

/**
 * The rows of the «موتور اتصال» screen, by their real names. Several rows can share one
 * [EngineMode]: the PoW engine runs Cloudflare WARP, Psiphon, or Psiphon over WARP
 * depending on its exit mode, so each of those is its own choice here.
 */
enum class EngineChoice(
    val title: String,
    val subtitleFa: String,
    val mode: EngineMode?,
    val powExit: String? = null,
) {
    AUTO("Auto", "انتخاب خودکار سریع‌ترین موتور سالم", null),
    XRAY("Xray", "VLESS، VMess، Trojan، Reality · کانفیگ و اشتراک خودتان", EngineMode.XRAY_CF),
    WARP("Cloudflare WARP", "WireGuard / MASQUE روی شبکه‌ی کلودفلر", EngineMode.UAC_POW, PowEngineSettings.EXIT_WARP),
    FIREFOX("Firefox VPN", "MASQUE با حساب فایرفاکس خودتان", EngineMode.FOXY_FIREFOX),
    PSIPHON("Psiphon", "بدون کانفیگ، مقاوم در فیلترینگ سنگین", EngineMode.UAC_POW, PowEngineSettings.EXIT_PSIPHON),
    PSIPHON_WARP("Psiphon + WARP", "دو لایه: Psiphon از روی WARP", EngineMode.UAC_POW, PowEngineSettings.EXIT_CHAIN),
    TOR("Tor + WebTunnel", "بیشترین ناشناسی، سرعت کمتر", EngineMode.TOR_WEBTUNNEL),
    WIREGUARD("WireGuard", "کانفیگ WireGuard خودتان، Proton VPN یا Windscribe", EngineMode.WIREGUARD),
    DIRECT("Direct", "بدون سرور، عبور مستقیم از فیلتر (DPI)", EngineMode.UAC_DIRECT),
    ;

    companion object {
        /** The row that matches what is selected now. */
        fun current(autoOn: Boolean, mode: EngineMode, powExit: String): EngineChoice = when {
            autoOn -> AUTO
            mode == EngineMode.UAC_POW -> when (powExit) {
                PowEngineSettings.EXIT_WARP -> WARP
                PowEngineSettings.EXIT_PSIPHON -> PSIPHON
                else -> PSIPHON_WARP
            }
            else -> entries.firstOrNull { it.mode == mode } ?: XRAY
        }

        /** Applies a row. Returns false when the engine cannot change while connected. */
        fun apply(context: Context, choice: EngineChoice): Boolean {
            val app = context.applicationContext
            val auto = AutoEngineStore.get(app)
            if (choice == AUTO) {
                auto.update { it.copy(enabled = true) }
                return true
            }
            val mode = choice.mode ?: return false
            val pow = PowEngineStore.get(app)
            // WARP, Psiphon and Psiphon + WARP share one engine mode, so setMode() alone lets a
            // switch between them through while connected: the row changed but the running
            // session kept the old exit. Block it like any other engine change.
            val exitChange = choice.powExit != null && mode == EngineModeStore.get(app).snapshot() &&
                pow.snapshot().exitMode != choice.powExit
            if (exitChange && !com.uacspoofer.mobile.engine.canChangeEngineMode(
                    com.uacspoofer.mobile.core.ConnectionStateStore.state.value,
                )
            ) return false
            if (EngineModeStore.get(app).setMode(mode) == EngineModeChangeResult.BLOCKED_WHILE_ACTIVE) return false
            choice.powExit?.let { exit ->
                val now = pow.snapshot()
                if (now.exitMode != exit) pow.save(now.copy(exitMode = exit))
            }
            if (auto.snapshot().enabled) auto.update { it.copy(enabled = false) }
            return true
        }
    }
}

/** Real, user-facing name of an engine (never the old Persian codenames). */
fun engineDisplayName(mode: EngineMode, powExit: String = PowEngineSettings.EXIT_CHAIN): String = when (mode) {
    EngineMode.XRAY_CF -> "Xray"
    EngineMode.TOR_WEBTUNNEL -> "Tor + WebTunnel"
    EngineMode.UAC_POW -> when (powExit) {
        PowEngineSettings.EXIT_WARP -> "Cloudflare WARP"
        PowEngineSettings.EXIT_PSIPHON -> "Psiphon"
        else -> "Psiphon + WARP"
    }
    EngineMode.UAC_DIRECT -> "Direct"
    EngineMode.FOXY_FIREFOX -> "Firefox VPN"
    EngineMode.WIREGUARD -> "WireGuard"
}
