package com.uacspoofer.mobile.engine

import com.uacspoofer.mobile.core.ConnectionState

enum class EngineMode(val id: String) {
    XRAY_CF("xray_cf"),
    TOR_WEBTUNNEL("tor_webtunnel"),
    UAC_POW("uac_pow"),
    /** Engine 4: Direct, serverless direct DPI bypass on the bundled Xray core. */
    UAC_DIRECT("uac_direct"),
    /** Engine 5: Rubah, Mozilla's Firefox VPN with the user's own Firefox account (sing-box MASQUE). */
    FOXY_FIREFOX("foxy_firefox");

    val isTor: Boolean get() = this == TOR_WEBTUNNEL
    val isXray: Boolean get() = this == XRAY_CF
    val isPow: Boolean get() = this == UAC_POW
    val isDirect: Boolean get() = this == UAC_DIRECT
    val isFoxy: Boolean get() = this == FOXY_FIREFOX

    fun next(): EngineMode = when (this) {
        XRAY_CF -> TOR_WEBTUNNEL
        TOR_WEBTUNNEL -> UAC_POW
        UAC_POW -> UAC_DIRECT
        UAC_DIRECT -> FOXY_FIREFOX
        FOXY_FIREFOX -> XRAY_CF
    }

    fun toggled(): EngineMode = next()

    companion object {
        fun fromStored(raw: String?): EngineMode {
            val key = raw?.trim().orEmpty()
            // Old uncommitted engine-4 id from the ACT WIP; keep the user's engine-4 selection.
            if (key.equals("act_hitch", ignoreCase = true)) return UAC_DIRECT
            return entries.firstOrNull { it.id.equals(key, ignoreCase = true) } ?: XRAY_CF
        }
    }
}

enum class EngineModeChangeResult {
    APPLIED,
    BLOCKED_WHILE_ACTIVE,
}

fun canChangeEngineMode(state: ConnectionState): Boolean =
    state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR
