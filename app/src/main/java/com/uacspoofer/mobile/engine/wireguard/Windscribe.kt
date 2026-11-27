package com.uacspoofer.mobile.engine.wireguard

/**
 * Windscribe with the user's own account.
 *
 * In-app sign-in is not possible on Blackdey's cores: Windscribe's API is reached through its
 * native WSNet library (libwsnet.so, github.com/Windscribe/wsnet), which signs and fails over the
 * requests; doing it by hand would need Windscribe's client secret, and Blackdey ships no
 * third-party secrets. So the user generates WireGuard configs in their own account
 * (windscribe.com → My Account → Config Generators → WireGuard) and imports the .conf files;
 * they run on the WireGuard engine like any other tunnel.
 */
object Windscribe {
    const val CONFIG_GENERATOR_URL = "https://windscribe.com/getconfig/wireguard"
    const val SIGN_UP_URL = "https://windscribe.com/signup"

    /** Windscribe names its files `Windscribe-<City>-<Server>.conf`; the server name holds the city. */
    fun nameFromFile(fileName: String?): String? =
        fileName?.substringAfterLast('/')?.removeSuffix(".conf")?.removePrefix("Windscribe-")?.replace('-', ' ')
            ?.trim()?.takeIf { it.isNotBlank() }?.take(60)
}
