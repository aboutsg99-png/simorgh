package com.uacspoofer.mobile.logging

/**
 * Display-only renaming of internal engine labels to their real names. Engine code keeps the
 * original words (some messages are matched by prefix), so apply this only
 * where text reaches the screen or an exported file.
 */
internal object BrandText {
    // Real engine names on screen (1.1): only the internal labels are rewritten.
    private val map = linkedMapOf(
        "Tor / WebTunnel" to "Tor + WebTunnel",
        "UAC PoW" to "WARP/Psiphon", "UAC Direct" to "Direct",
        "PoW" to "WARP/Psiphon", "POW" to "WARP/PSIPHON",
        "Aether" to "WARP", "AETHER" to "WARP",
        "Foxy" to "Firefox VPN", "FOXY" to "FIREFOX VPN",
        "UAC" to "Blackdey",
    )
    private val regex = Regex(
        map.keys.joinToString("|", prefix = "(?<![A-Za-z0-9_./-])(", postfix = ")(?![A-Za-z0-9_/-])") { Regex.escape(it) },
    )

    fun apply(text: String): String =
        if (text.isEmpty()) text else regex.replace(text) { map[it.value] ?: it.value }
}
