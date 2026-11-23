package com.uacspoofer.mobile.engine.foxy

data class VpnProtocol(val name: String, val host: String = "", val port: Int = 0)

data class VpnServerNode(
    val hostname: String,
    val port: Int = 0,
    val quarantined: Boolean = false,
    val protocols: List<VpnProtocol> = emptyList(),
)

data class VpnCity(
    val name: String,
    val code: String,
    val servers: List<VpnServerNode> = emptyList(),
)

data class VpnCountry(val name: String, val code: String, val cities: List<VpnCity> = emptyList())

data class ProxyCandidate(
    val host: String,
    val port: Int,
    val countryCode: String,
    val countryName: String,
    val cityCode: String = "",
) {
    val authority: String
        get() = "${if (':' in host) "[$host]" else host}:$port"
}

data class Entitlement(
    val maxBytes: Long?,
    val limitedBandwidth: Boolean,
    val quotaRemaining: Long? = null,
)

data class RuntimeAuth(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString() = "RuntimeAuth(expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

const val RECOMMENDED_COUNTRY_CODE = "REC"
const val RECOMMENDED_COUNTRY_NAME = "Recommended"

fun normalizeCountryName(code: String, rawName: String): String {
    return if (code.equals(RECOMMENDED_COUNTRY_CODE, ignoreCase = true) ||
        rawName.equals("Recomended", ignoreCase = true) ||
        rawName.equals(RECOMMENDED_COUNTRY_NAME, ignoreCase = true)
    ) {
        RECOMMENDED_COUNTRY_NAME
    } else {
        rawName
    }
}

val VpnCountry.displayName: String
    get() = normalizeCountryName(code, name)

val ProxyCandidate.displayName: String
    get() = normalizeCountryName(countryCode, countryName)
