package com.uacspoofer.mobile.simorgh.flux

/*
 * Flux: the adaptive route picker inside Simorgh's Auto engine.
 * Pure Kotlin: everything in this package except
 * FluxStore/FluxPicker runs in JVM unit tests.
 *
 * In Simorgh the engines already adapt edge/fragment per network (AdaptiveConnection, Foxy/PoW coordinators),
 * so Flux here decides the layer above: WHICH route (a config, a WireGuard tunnel) to try, in
 * which order, for the user's country and IP version, and what to fail over to.
 */

/** What the user picked under «نسخه IP». */
enum class IpMode(val code: String) {
    V4("v4"), V6("v6"), BOTH("both");

    val families: List<Family> get() = when (this) {
        V4 -> listOf(Family.V4)
        V6 -> listOf(Family.V6)
        BOTH -> listOf(Family.V4, Family.V6)
    }

    companion object {
        fun of(code: String?): IpMode = entries.firstOrNull { it.code == code } ?: BOTH
    }
}

enum class Family(val code: String) {
    V4("4"), V6("6");

    companion object {
        fun of(code: String?): Family? = entries.firstOrNull { it.code == code }

        /** The family of a literal address, or null for a domain name. */
        fun ofLiteral(address: String): Family? {
            val a = address.trim().removePrefix("[").removeSuffix("]")
            return when {
                a.contains(':') && a.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' } -> V6
                a.count { it == '.' } == 3 && a.split('.').all { p -> p.toIntOrNull()?.let { it in 0..255 } == true } -> V4
                else -> null
            }
        }
    }
}

/** Why a route failed. Kept per network: it decides how long the route rests. */
enum class FailReason(val code: String) {
    TIMEOUT("t"), REFUSED("r"), HANDSHAKE("h"), HTTP("x"), COUNTRY_MISMATCH("c"), OTHER("o");

    companion object {
        fun of(code: String?): FailReason = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/**
 * One route Flux can pick: a config of an engine. [id] must be stable across sessions (the
 * profile id, the tunnel id) and carries no secret.
 */
data class FluxRoute(
    val id: String,
    /** The engine id the route belongs to (`xray_cf`, `wireguard`). */
    val engine: String,
    /** The address the route dials (literal or name), used only for the IP-family filter. */
    val host: String,
    /** The route's exit country proven by a real exit check (ISO code), or null. */
    val provenCountry: String? = null,
    /** The route's own name; only a hint for ordering. */
    val label: String = "",
    /** `own` for configs the user added by hand, `sub` for subscriptions/collectors, `provider` for accounts. */
    val source: String = "own",
) {
    val countryHint: String? by lazy { CountryHint.of(label) }
    val key: String get() = "$engine:$id"
}
