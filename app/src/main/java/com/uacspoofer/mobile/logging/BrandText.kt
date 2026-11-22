package com.uacspoofer.mobile.logging

/**
 * Display-only renaming of engine and network names. Engine code keeps the
 * original words (some messages are matched by prefix), so apply this only
 * where text reaches the screen or an exported file.
 */
internal object BrandText {
    private val map = linkedMapOf(
        "Tor / WebTunnel" to "Sayeh",
        "UAC PoW" to "Shahpar", "UAC Direct" to "Tir",
        "PoW" to "Shahpar", "POW" to "SHAHPAR",
        "Aether" to "Shahpar", "AETHER" to "SHAHPAR",
        "Xray" to "Parvaz", "XRAY" to "PARVAZ",
        "WebTunnel" to "Pol", "Tor" to "Sayeh", "TOR" to "SAYEH",
        "Psiphon" to "Abr", "PSIPHON" to "ABR",
        "WARP" to "Nur",
        "Direct" to "Tir", "DIRECT" to "TIR",
        "UAC" to "Simorgh",
    )
    private val regex = Regex(
        map.keys.joinToString("|", prefix = "(?<![A-Za-z0-9_./-])(", postfix = ")(?![A-Za-z0-9_/-])") { Regex.escape(it) },
    )

    fun apply(text: String): String =
        if (text.isEmpty()) text else regex.replace(text) { map[it.value] ?: it.value }
}
