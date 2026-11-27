package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** HTTP version of the MASQUE / HTTP CONNECT hop to Mozilla's Fastly edge. */
enum class FoxyProtocol(val id: String, val version: Int, val label: String) {
    AUTO("auto", 0, "Auto"),
    H3("h3", 3, "HTTP/3"),
    H2("h2", 2, "HTTP/2"),
    H1("h1", 1, "HTTP/1.1");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.id == raw } ?: AUTO
        fun labelFor(version: Int) = entries.firstOrNull { it.version == version && it != AUTO }?.label ?: "?"
    }
}

/** Engine Blackdey switches to when every Firefox VPN path is unusable. */
enum class FoxyFallback(val id: String) {
    PARVAZ("parvaz"),
    SHAHPAR("shahpar"),
    NONE("none");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.id == raw } ?: PARVAZ
    }
}

data class FoxyEngineSettings(
    /** Mozilla country code, or "" for the fastest measured server. */
    val countryCode: String = "",
    val protocol: FoxyProtocol = FoxyProtocol.AUTO,
    val fallback: FoxyFallback = FoxyFallback.PARVAZ,
    /** Local SOCKS/HTTP port of the sing-box process in tunnel mode. */
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    /** Last HTTP version that passed the throughput check; tried first next time. */
    val lastGoodVersion: Int = 0,
    /** MASQUE (HTTPS) or a Mozilla VPN WireGuard variant; HTTPS stays the fallback of every choice. */
    val transport: FoxyTransport = FoxyTransport.MASQUE,
    /** TLS shaping of the HTTPS (MASQUE) hop against SNI filtering; Auto walks [AntiFilterPlan]. */
    val antiFilter: AntiFilterMethod = AntiFilterMethod.AUTO,
) {
    fun validated(): FoxyEngineSettings = copy(
        countryCode = countryCode.trim().uppercase().take(8),
        socksPort = socksPort.takeIf { it in 1024..65535 } ?: DEFAULT_SOCKS_PORT,
        lastGoodVersion = lastGoodVersion.takeIf { it in 1..3 } ?: 0,
    )

    companion object {
        const val DEFAULT_SOCKS_PORT = 10_871
    }
}

class FoxyEngineStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val settings: StateFlow<FoxyEngineSettings> = mutable.asStateFlow()

    fun snapshot(): FoxyEngineSettings = mutable.value

    @Synchronized
    fun save(next: FoxyEngineSettings) {
        val value = next.validated()
        prefs.edit()
            .putString(KEY_COUNTRY, value.countryCode)
            .putString(KEY_PROTOCOL, value.protocol.id)
            .putString(KEY_FALLBACK, value.fallback.id)
            .putInt(KEY_PORT, value.socksPort)
            .putInt(KEY_LAST_VERSION, value.lastGoodVersion)
            .putString(KEY_TRANSPORT, value.transport.id)
            .putString(KEY_ANTI_FILTER, value.antiFilter.id)
            .apply()
        mutable.value = value
    }

    fun update(transform: (FoxyEngineSettings) -> FoxyEngineSettings) = save(transform(snapshot()))

    private fun read(): FoxyEngineSettings = FoxyEngineSettings(
        countryCode = prefs.getString(KEY_COUNTRY, "").orEmpty(),
        protocol = FoxyProtocol.from(prefs.getString(KEY_PROTOCOL, null)),
        fallback = FoxyFallback.from(prefs.getString(KEY_FALLBACK, null)),
        socksPort = prefs.getInt(KEY_PORT, FoxyEngineSettings.DEFAULT_SOCKS_PORT),
        lastGoodVersion = prefs.getInt(KEY_LAST_VERSION, 0),
        transport = FoxyTransport.from(prefs.getString(KEY_TRANSPORT, null)),
        antiFilter = AntiFilterMethod.from(prefs.getString(KEY_ANTI_FILTER, null)),
    ).validated()

    companion object {
        private const val PREFS = "foxy_engine_v1"
        private const val KEY_COUNTRY = "country"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_FALLBACK = "fallback"
        private const val KEY_PORT = "socks_port"
        private const val KEY_LAST_VERSION = "last_good_version"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_ANTI_FILTER = "anti_filter"

        @Volatile private var instance: FoxyEngineStore? = null

        fun get(context: Context): FoxyEngineStore = instance ?: synchronized(this) {
            instance ?: FoxyEngineStore(context.applicationContext).also { instance = it }
        }
    }
}

enum class FoxyPhase { IDLE, PREPARING, PINGING, TESTING, CONNECTED, FALLBACK, FAILED }

data class FoxyPathResult(
    val version: Int,
    val ok: Boolean,
    val latencyMs: Int? = null,
    val bytesPerSecond: Long? = null,
    val error: String = "",
)

data class FoxyUiStatus(
    val phase: FoxyPhase = FoxyPhase.IDLE,
    /** Persian, user-facing. */
    val detail: String = "",
    val countryCode: String = "",
    val server: String = "",
    val activeVersion: Int = 0,
    val tried: List<FoxyPathResult> = emptyList(),
)

object FoxyStatusStore {
    private val mutable = MutableStateFlow(FoxyUiStatus())
    val status: StateFlow<FoxyUiStatus> = mutable.asStateFlow()

    fun set(value: FoxyUiStatus) { mutable.value = value }
    fun update(transform: (FoxyUiStatus) -> FoxyUiStatus) { mutable.value = transform(mutable.value) }
    fun reset() { mutable.value = FoxyUiStatus() }
}
