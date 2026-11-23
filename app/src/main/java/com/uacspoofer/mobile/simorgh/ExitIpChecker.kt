package com.uacspoofer.mobile.simorgh

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.vpn.LivePing
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ExitPhase { IDLE, CHECKING, DONE, FAILED }

/**
 * Real exit IP and country, read through the route traffic actually takes: the tunnel's
 * local SOCKS when connected, the plain network otherwise (the app itself is excluded
 * from the VPN, so a direct request never enters the tunnel).
 *
 * Sources: DB-IP free API (the same data browserleaks.com/ip shows) and Cloudflare's
 * cdn-cgi/trace (ip= and loc=). DB-IP wins a disagreement; Cloudflare fills in when DB-IP
 * fails. Nothing here ever falls back to the country the user selected.
 */
@Immutable
data class ExitCheck(
    val phase: ExitPhase = ExitPhase.IDLE,
    val viaTunnel: Boolean = false,
    val engine: EngineMode? = null,
    val ip: String = "",
    val countryCode: String = "",
    val countryName: String = "",
    val city: String = "",
    val region: String = "",
    val dbipCountry: String = "",
    val cloudflareCountry: String = "",
    val checkedAtMs: Long = 0L,
    val error: String = "",
) {
    val known: Boolean get() = phase == ExitPhase.DONE && countryCode.length == 2
    /** Both sources answered and named the same country. */
    val sourcesAgree: Boolean get() = dbipCountry.isNotEmpty() && dbipCountry == cloudflareCountry
}

/** The user's own network location, from the last direct (non-tunnel) check. */
@Immutable
data class HomeNetwork(val ip: String = "", val countryCode: String = "", val city: String = "", val checkedAtMs: Long = 0L) {
    val known: Boolean get() = countryCode.length == 2
}

object ExitIpChecker {
    private val mutable = MutableStateFlow(ExitCheck())
    val state: StateFlow<ExitCheck> = mutable.asStateFlow()
    private val mutableHome = MutableStateFlow(HomeNetwork())
    val home: StateFlow<HomeNetwork> = mutableHome.asStateFlow()
    private val lock = Mutex()
    @Volatile private var homeLoaded = false

    private const val PREFS = "simorgh_exit_ip_v1"
    private const val TIMEOUT_DIRECT_MS = 6_000
    private const val TIMEOUT_TUNNEL_MS = 12_000
    private const val MAX_BYTES = 64 * 1024

    fun loadHome(context: Context) {
        if (homeLoaded) return
        homeLoaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mutableHome.value = HomeNetwork(
            ip = p.getString("home_ip", "").orEmpty(),
            countryCode = p.getString("home_cc", "").orEmpty(),
            city = p.getString("home_city", "").orEmpty(),
            checkedAtMs = p.getLong("home_at", 0L),
        )
    }

    fun clearTunnelResult() {
        val current = mutable.value
        if (current.viaTunnel) mutable.value = ExitCheck()
    }

    /** Runs one check on the current route. Safe to call from any thread; never throws. */
    suspend fun check(context: Context, reason: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            loadHome(context)
            val state = ConnectionStateStore.state.value
            val tunnel = state == ConnectionState.CONNECTED
            val engine = if (tunnel) LivePing.routeEngine(context) else null
            val proxy = if (tunnel) {
                val (host, port) = LivePing.socksEndpoint(context)
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            } else {
                Proxy.NO_PROXY
            }
            val timeout = when {
                !tunnel -> TIMEOUT_DIRECT_MS
                engine?.isTor == true -> 20_000
                else -> TIMEOUT_TUNNEL_MS
            }
            mutable.value = mutable.value.copy(phase = ExitPhase.CHECKING, viaTunnel = tunnel, engine = engine)
            try {
                val (db, cf) = coroutineScope {
                    val a = async { runCatching { dbip(proxy, timeout) }.getOrNull() }
                    val b = async { runCatching { cloudflare(proxy, timeout) }.getOrNull() }
                    a.await() to b.await()
                }
                // A tunnel drop while checking would report the real network as the exit.
                if (tunnel && ConnectionStateStore.state.value != ConnectionState.CONNECTED) {
                    mutable.value = ExitCheck()
                    return@withLock
                }
                val dbCc = db?.countryCode.orEmpty()
                val cfCc = cf?.second.orEmpty()
                val cc = dbCc.ifEmpty { cfCc }
                if (cc.length != 2) {
                    mutable.value = ExitCheck(
                        phase = ExitPhase.FAILED, viaTunnel = tunnel, engine = engine,
                        checkedAtMs = System.currentTimeMillis(), error = "no answer",
                    )
                    return@withLock
                }
                val ip = db?.ip?.ifEmpty { null } ?: cf?.first.orEmpty()
                val result = ExitCheck(
                    phase = ExitPhase.DONE,
                    viaTunnel = tunnel,
                    engine = engine,
                    ip = ip,
                    countryCode = cc,
                    countryName = db?.countryName.orEmpty(),
                    city = if (dbCc.isNotEmpty()) db?.city.orEmpty() else "",
                    region = if (dbCc.isNotEmpty()) db?.region.orEmpty() else "",
                    dbipCountry = dbCc,
                    cloudflareCountry = cfCc,
                    checkedAtMs = System.currentTimeMillis(),
                )
                mutable.value = result
                if (!tunnel) {
                    val home = HomeNetwork(ip, cc, result.city, result.checkedAtMs)
                    mutableHome.value = home
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("home_ip", home.ip).putString("home_cc", home.countryCode)
                        .putString("home_city", home.city).putLong("home_at", home.checkedAtMs).apply()
                }
                runCatching {
                    AppLogRepository.info(
                        LogSource.APP,
                        "Exit check ($reason) ${if (tunnel) "tunnel ${engine?.id}" else "direct"}: " +
                            "db-ip=${dbCc.ifEmpty { "-" }} cloudflare=${cfCc.ifEmpty { "-" }}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutable.value = ExitCheck(
                    phase = ExitPhase.FAILED, viaTunnel = tunnel, engine = engine,
                    checkedAtMs = System.currentTimeMillis(), error = error.javaClass.simpleName,
                )
            }
        }
    }

    private data class DbIp(val ip: String, val countryCode: String, val countryName: String, val city: String, val region: String)

    private fun dbip(proxy: Proxy, timeout: Int): DbIp {
        val json = JSONObject(fetch("https://api.db-ip.com/v2/free/self", proxy, timeout))
        val cc = json.optString("countryCode").trim().uppercase()
        require(cc.length == 2 && cc != "ZZ")
        return DbIp(
            ip = json.optString("ipAddress").trim(),
            countryCode = cc,
            countryName = json.optString("countryName").trim(),
            city = json.optString("city").trim(),
            region = json.optString("stateProv").trim(),
        )
    }

    /** ip and loc from Cloudflare's trace. */
    private fun cloudflare(proxy: Proxy, timeout: Int): Pair<String, String> {
        var ip = ""
        var loc = ""
        fetch("https://www.cloudflare.com/cdn-cgi/trace", proxy, timeout).lineSequence().forEach { line ->
            when {
                line.startsWith("ip=") -> ip = line.substring(3).trim()
                line.startsWith("loc=") -> loc = line.substring(4).trim().uppercase()
            }
        }
        require(loc.length == 2 && loc != "XX" && loc != "T1")
        return ip to loc
    }

    private fun fetch(url: String, proxy: Proxy, timeout: Int): String {
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        try {
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "Simorgh/1.0")
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(1024)
                val buf = ByteArray(2048)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
