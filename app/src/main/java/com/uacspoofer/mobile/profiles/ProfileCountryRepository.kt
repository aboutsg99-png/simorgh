package com.uacspoofer.mobile.profiles

import android.content.Context
import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProfileEndpoint(val host: String, val port: Int)

enum class CountryResolutionSource(val label: String) {
    PROFILE_METADATA("profile-metadata"),
    CACHE("geoip-cache"),
    GEOIP("geoip"),
    UNKNOWN("unknown"),
}

data class ProfileCountryResolution(
    val profileId: String,
    val endpoint: ProfileEndpoint,
    val resolvedIp: String?,
    val country: CountryMetadata,
    val source: CountryResolutionSource,
    val reason: String? = null,
    val resolvedAtMs: Long = System.currentTimeMillis(),
)







class ProfileCountryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val endpointLocks = ConcurrentHashMap<String, Mutex>()
    private val stateLock = Any()
    private val mutableResolutions = MutableStateFlow<Map<String, ProfileCountryResolution>>(emptyMap())

    val resolutions: StateFlow<Map<String, ProfileCountryResolution>> = mutableResolutions.asStateFlow()

    suspend fun resolve(profile: ProxyProfile, endpoint: ProfileEndpoint): ProfileCountryResolution =
        withContext(Dispatchers.IO) {
            val cleanEndpoint = endpoint.copy(host = endpoint.host.trim(), port = endpoint.port.coerceIn(1, 65_535))
            val stateKey = stateKey(profile, cleanEndpoint)
            currentResolution(stateKey)?.let { return@withContext it }

            endpointLocks.computeIfAbsent(endpointKey(cleanEndpoint)) { Mutex() }.withLock {
                currentResolution(stateKey)?.let { return@withLock it }

                if (profile.country.isKnown) {
                    // The country is already known: no DNS query at all (it used to resolve the
                    // host anyway, outside the tunnel, only to show the IP).
                    val resolvedIp = cleanEndpoint.host.takeIf { isIpLiteral(it) }
                    return@withLock publish(
                        stateKey,
                        ProfileCountryResolution(
                            profileId = profile.id,
                            endpoint = cleanEndpoint,
                            resolvedIp = resolvedIp,
                            country = profile.country,
                            source = CountryResolutionSource.PROFILE_METADATA,
                        ),
                        profile,
                    )
                }

                if (cleanEndpoint.host.isBlank()) {
                    return@withLock unknown(stateKey, profile, cleanEndpoint, null, "no-host")
                }

                val resolvedIp = runCatchingNonCancellation { resolvePrivately(cleanEndpoint.host) }
                    .getOrElse { error ->
                        currentCoroutineContext().ensureActive()
                        return@withLock unknown(
                            stateKey,
                            profile,
                            cleanEndpoint,
                            null,
                            failureReason("dns-failed", error),
                        )
                    }
                if (resolvedIp == null) {
                    currentCoroutineContext().ensureActive()
                    val reason = if (isIpLiteral(cleanEndpoint.host) || tunnelSocks() != null || directDnsAllowed()) {
                        "no-public-ip"
                    } else {
                        // Retried (negative cache is 60 s) once a tunnel is up or the user opts in.
                        "dns-deferred-until-tunnel"
                    }
                    return@withLock unknown(stateKey, profile, cleanEndpoint, null, reason)
                }

                currentCoroutineContext().ensureActive()
                readCache(resolvedIp)?.let { cached ->
                    currentCoroutineContext().ensureActive()
                    return@withLock publish(
                        stateKey,
                        ProfileCountryResolution(
                            profileId = profile.id,
                            endpoint = cleanEndpoint,
                            resolvedIp = resolvedIp,
                            country = cached,
                            source = CountryResolutionSource.CACHE,
                        ),
                        profile,
                    )
                }

                val lookup = runCatchingNonCancellation { lookupCountry(resolvedIp) }
                    .getOrElse { error ->
                        currentCoroutineContext().ensureActive()
                        return@withLock unknown(
                            stateKey,
                            profile,
                            cleanEndpoint,
                            resolvedIp,
                            failureReason("geo-failed", error),
                        )
                    }

                currentCoroutineContext().ensureActive()
                if (!lookup.isKnown) {
                    return@withLock unknown(stateKey, profile, cleanEndpoint, resolvedIp, "no-geo-result")
                }

                writeCache(resolvedIp, lookup)
                publish(
                    stateKey,
                    ProfileCountryResolution(
                        profileId = profile.id,
                        endpoint = cleanEndpoint,
                        resolvedIp = resolvedIp,
                        country = lookup,
                        source = CountryResolutionSource.GEOIP,
                    ),
                    profile,
                )
            }
        }

    fun resolutionFor(profile: ProxyProfile, endpoint: ProfileEndpoint): ProfileCountryResolution? =
        currentResolution(stateKey(profile, endpoint))

    private fun currentResolution(stateKey: String): ProfileCountryResolution? {
        val resolution = mutableResolutions.value[stateKey] ?: return null
        if (resolution.country.isKnown) return resolution
        return resolution.takeIf { System.currentTimeMillis() - it.resolvedAtMs < NEGATIVE_CACHE_TTL_MS }
    }

    /**
     * Resolves a config's server host without leaking it:
     * - IP literal: no DNS;
     * - tunnel up: DNS-over-HTTPS sent through the tunnel's SOCKS port (remote DNS);
     * - no tunnel: the system resolver only if the user opted in ([setDirectDnsAllowed]).
     * Before, every config host was resolved with the system resolver while Blackdey is
     * excluded from its own VPN, i.e. always straight to the ISP's DNS.
     */
    private fun resolvePrivately(host: String): String? {
        if (isIpLiteral(host)) {
            return runCatching { InetAddress.getByName(host.removeSurrounding("[", "]")) }.getOrNull()
                ?.takeIf { it.isPublicAddress() }?.hostAddress
        }
        tunnelSocks()?.let { (socksHost, socksPort) ->
            for (url in DOH_JSON_ENDPOINTS) {
                val ip = runCatching {
                    val body = com.uacspoofer.mobile.vpn.TunnelHttp.get(
                        url + java.net.URLEncoder.encode(host, "UTF-8"), socksHost, socksPort, LOOKUP_TIMEOUT_MS,
                    )
                    com.uacspoofer.mobile.engine.foxy.FoxyPing.parseDohA(body)
                }.getOrNull()
                val address = ip?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                if (address != null && address.isPublicAddress()) return address.hostAddress
            }
            return null
        }
        if (!directDnsAllowed()) return null
        val addresses = InetAddress.getAllByName(host)
        val address = addresses.firstOrNull { it is Inet4Address && it.isPublicAddress() }
            ?: addresses.firstOrNull { it.isPublicAddress() }
        return address?.hostAddress
    }

    /** Local SOCKS of the running tunnel, or null when no tunnel is connected. */
    private fun tunnelSocks(): Pair<String, Int>? {
        if (com.uacspoofer.mobile.core.ConnectionStateStore.state.value != com.uacspoofer.mobile.core.ConnectionState.CONNECTED) return null
        return runCatching { com.uacspoofer.mobile.vpn.LivePing.socksEndpoint(appContext) }.getOrNull()
            ?.takeIf { it.second in 1..65_535 }
    }

    fun directDnsAllowed(): Boolean = prefs.getBoolean(KEY_DIRECT_DNS, false)

    /** Opt-in: resolve config hosts with the phone's own DNS when no tunnel is up. */
    fun setDirectDnsAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_DIRECT_DNS, allowed).apply()
    }

    private fun InetAddress.isPublicAddress(): Boolean =
        !isAnyLocalAddress && !isLoopbackAddress && !isLinkLocalAddress && !isSiteLocalAddress && !isMulticastAddress

    /** Offline: the IPFire database bundled for Tor. No request leaves the phone. */
    private fun lookupCountry(ip: String): CountryMetadata {
        val address = InetAddress.getByName(ip)
        val code = OfflineGeoIp.get(appContext).lookup(address) ?: return CountryMetadata.UNKNOWN
        return CountryMetadata.resolve(code, null)
    }

    private fun readCache(ip: String): CountryMetadata? {
        val raw = prefs.getString("$CACHE_PREFIX$ip", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val timestamp = json.optLong("timestamp", 0L)
            if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return null
            CountryMetadata.resolve(json.optString("code"), json.optString("name")).takeIf { it.isKnown }
        }.getOrNull()
    }

    private fun writeCache(ip: String, country: CountryMetadata) {
        prefs.edit().putString(
            "$CACHE_PREFIX$ip",
            JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("code", country.countryCode)
                .put("name", country.countryName)
                .toString(),
        ).apply()
    }

    private fun unknown(
        stateKey: String,
        profile: ProxyProfile,
        endpoint: ProfileEndpoint,
        resolvedIp: String?,
        reason: String,
    ): ProfileCountryResolution = publish(
        stateKey,
        ProfileCountryResolution(
            profileId = profile.id,
            endpoint = endpoint,
            resolvedIp = resolvedIp,
            country = CountryMetadata.UNKNOWN,
            source = CountryResolutionSource.UNKNOWN,
            reason = reason,
        ),
        profile,
    )

    private fun publish(
        stateKey: String,
        resolution: ProfileCountryResolution,
        profile: ProxyProfile,
    ): ProfileCountryResolution {
        synchronized(stateLock) {
            mutableResolutions.value = mutableResolutions.value + (stateKey to resolution)
        }
        val message = buildString {
            append("Country config=${profile.name}")
            append(" host=${resolution.endpoint.host}")
            append(" resolvedIp=${resolution.resolvedIp ?: "-"}")
            append(" countrySource=${resolution.source.label}")
            if (resolution.country.isKnown) {
                append(" countryCode=${resolution.country.countryCode}")
                append(" countryName=${resolution.country.countryName}")
            } else {
                append(" reason=${resolution.reason ?: "unknown"}")
            }
        }
        if (resolution.country.isKnown) {
            AppLogRepository.info(LogSource.APP, message)
            Log.d(TAG, message)
        } else {
            AppLogRepository.warning(LogSource.APP, message)
            Log.w(TAG, message)
        }
        return resolution
    }

    companion object {
        private const val TAG = "ProfileCountry"
        private const val PREFS = "profile_country_cache_v1"
        private const val CACHE_PREFIX = "geo:"
        private const val LOOKUP_TIMEOUT_MS = 4_500
        private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val NEGATIVE_CACHE_TTL_MS = 60_000L
        private const val KEY_DIRECT_DNS = "direct_dns_opt_in"
        private val DOH_JSON_ENDPOINTS = listOf(
            "https://dns.google/resolve?type=A&name=",
            "https://cloudflare-dns.com/dns-query?type=A&name=",
        )
        private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        internal fun isIpLiteral(host: String): Boolean {
            val h = host.trim().removeSurrounding("[", "]")
            return IPV4_LITERAL.matches(h) || (h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' })
        }

        @Volatile private var instance: ProfileCountryRepository? = null

        fun get(context: Context): ProfileCountryRepository = instance ?: synchronized(this) {
            instance ?: ProfileCountryRepository(context.applicationContext).also { instance = it }
        }

        internal fun parseCountryLookup(payload: String): CountryMetadata {
            val json = JSONObject(payload)
            if (!json.optBoolean("success", false)) return CountryMetadata.UNKNOWN
            return CountryMetadata.resolve(json.optString("country_code"), json.optString("country"))
        }

        private fun stateKey(profile: ProxyProfile, endpoint: ProfileEndpoint): String =
            "${profile.id}:${profile.country.countryCode.orEmpty()}@${endpointKey(endpoint)}"

        private fun endpointKey(endpoint: ProfileEndpoint): String =
            "${endpoint.host.trim().lowercase()}:${endpoint.port}"

        private fun failureReason(prefix: String, error: Throwable): String {
            val detail = error.message.orEmpty().replace(Regex("\\s+"), " ").trim().take(96)
            return if (detail.isBlank()) "$prefix:${error.javaClass.simpleName}" else "$prefix:${error.javaClass.simpleName}:$detail"
        }

        private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
