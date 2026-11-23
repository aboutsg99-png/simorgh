package com.uacspoofer.mobile.extras

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the untrusted Wi-Fi auto-connect decides that a network is "untrusted". */
enum class WifiTrustMode(val id: String) {
    /** Every Wi-Fi counts as untrusted. Needs no location permission. */
    ANY_WIFI("any"),

    /** Only Wi-Fi networks whose name is not in the trusted list. Needs location permission to read the SSID. */
    TRUSTED_LIST("list"),
    ;

    companion object {
        fun fromId(id: String?): WifiTrustMode = entries.firstOrNull { it.id == id } ?: ANY_WIFI
    }
}

data class ExtrasSettings(
    val untrustedWifiAutoConnect: Boolean = false,
    val wifiTrustMode: WifiTrustMode = WifiTrustMode.ANY_WIFI,
    val trustedSsids: Set<String> = emptySet(),
    val dataCapEnabled: Boolean = false,
    val dataCapGb: Float = DEFAULT_CAP_GB,
) {
    val dataCapBytes: Long get() = (dataCapGb.toDouble() * BYTES_PER_GB).toLong()

    companion object {
        const val DEFAULT_CAP_GB = 20f
        const val MIN_CAP_GB = 0.5f
        const val MAX_CAP_GB = 10_000f
        const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
    }
}

/** Settings for the 1.0.1 extras (untrusted Wi-Fi auto-connect and monthly data cap). */
class ExtrasSettingsStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val settings: StateFlow<ExtrasSettings> = mutable.asStateFlow()

    fun snapshot(): ExtrasSettings = mutable.value

    @Synchronized
    fun setUntrustedWifiAutoConnect(enabled: Boolean) = persist(mutable.value.copy(untrustedWifiAutoConnect = enabled))

    @Synchronized
    fun setWifiTrustMode(mode: WifiTrustMode) = persist(mutable.value.copy(wifiTrustMode = mode))

    @Synchronized
    fun addTrustedSsid(ssid: String) {
        val clean = normalizeSsid(ssid) ?: return
        persist(mutable.value.copy(trustedSsids = mutable.value.trustedSsids + clean))
    }

    @Synchronized
    fun removeTrustedSsid(ssid: String) {
        persist(mutable.value.copy(trustedSsids = mutable.value.trustedSsids - ssid))
    }

    @Synchronized
    fun setDataCapEnabled(enabled: Boolean) {
        persist(mutable.value.copy(dataCapEnabled = enabled))
    }

    @Synchronized
    fun setDataCapGb(gb: Float) {
        val clamped = gb.coerceIn(ExtrasSettings.MIN_CAP_GB, ExtrasSettings.MAX_CAP_GB)
        if (clamped == mutable.value.dataCapGb) return
        persist(mutable.value.copy(dataCapGb = clamped))
        // A new cap starts a fresh pair of warnings for the current month.
        clearCapWarnings()
    }

    /** Last threshold (0, 80 or 100) already announced for [monthKey]. */
    fun capWarnedLevel(monthKey: String): Int =
        if (prefs.getString(KEY_CAP_WARN_MONTH, null) == monthKey) prefs.getInt(KEY_CAP_WARN_LEVEL, 0) else 0

    fun setCapWarnedLevel(monthKey: String, level: Int) {
        prefs.edit().putString(KEY_CAP_WARN_MONTH, monthKey).putInt(KEY_CAP_WARN_LEVEL, level).apply()
    }

    private fun clearCapWarnings() {
        prefs.edit().remove(KEY_CAP_WARN_MONTH).remove(KEY_CAP_WARN_LEVEL).apply()
    }

    /** Network handle the untrusted Wi-Fi watcher already acted on, so one join triggers one connect. */
    var lastHandledNetwork: Long
        get() = prefs.getLong(KEY_LAST_NETWORK, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_NETWORK, value).apply()
        }

    private fun read(): ExtrasSettings = ExtrasSettings(
        untrustedWifiAutoConnect = prefs.getBoolean(KEY_WIFI_AUTO, false),
        wifiTrustMode = WifiTrustMode.fromId(prefs.getString(KEY_WIFI_MODE, null)),
        trustedSsids = prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty().toSet(),
        dataCapEnabled = prefs.getBoolean(KEY_CAP_ENABLED, false),
        dataCapGb = prefs.getFloat(KEY_CAP_GB, ExtrasSettings.DEFAULT_CAP_GB)
            .takeIf { it.isFinite() && it >= ExtrasSettings.MIN_CAP_GB } ?: ExtrasSettings.DEFAULT_CAP_GB,
    )

    private fun persist(value: ExtrasSettings) {
        prefs.edit()
            .putBoolean(KEY_WIFI_AUTO, value.untrustedWifiAutoConnect)
            .putString(KEY_WIFI_MODE, value.wifiTrustMode.id)
            .putStringSet(KEY_TRUSTED, HashSet(value.trustedSsids))
            .putBoolean(KEY_CAP_ENABLED, value.dataCapEnabled)
            .putFloat(KEY_CAP_GB, value.dataCapGb)
            .apply()
        mutable.value = value
    }

    companion object {
        private const val PREFS = "simorgh_extras_v1"
        private const val KEY_WIFI_AUTO = "untrusted_wifi_auto"
        private const val KEY_WIFI_MODE = "wifi_trust_mode"
        private const val KEY_TRUSTED = "trusted_ssids"
        private const val KEY_CAP_ENABLED = "data_cap_enabled"
        private const val KEY_CAP_GB = "data_cap_gb"
        private const val KEY_CAP_WARN_MONTH = "data_cap_warn_month"
        private const val KEY_CAP_WARN_LEVEL = "data_cap_warn_level"
        private const val KEY_LAST_NETWORK = "wifi_last_network"

        /** Strips the quotes Android puts around SSIDs; returns null for hidden/unknown names. */
        fun normalizeSsid(raw: String?): String? {
            val trimmed = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == UNKNOWN_SSID || trimmed == "0x") return null
            return trimmed
        }

        const val UNKNOWN_SSID = "<unknown ssid>"

        @Volatile private var instance: ExtrasSettingsStore? = null

        fun get(context: Context): ExtrasSettingsStore = instance ?: synchronized(this) {
            instance ?: ExtrasSettingsStore(context.applicationContext).also { instance = it }
        }
    }
}
