package com.uacspoofer.mobile.engine.wireguard

import android.content.Context
import android.webkit.CookieManager
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.Base64Codec
import com.uacspoofer.mobile.vpn.LivePing
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONArray
import org.json.JSONObject

/**
 * Proton VPN with the user's own Proton account (free or paid). On Blackdey:
 *
 * The user signs in on Proton's own page (account.protonvpn.com) in a WebView: the password
 * never reaches Blackdey. With the session cookie the page left, Blackdey makes the same calls
 * Proton's dashboard makes for «WireGuard configuration»:
 *  1. `vpn/v2` (the plan's highest tier), `vpn/v2/clientconfig` (feature flags);
 *  2. `vpn/v1/logicals` (servers): tier within the plan, no Secure Core / Tor, a WireGuard key;
 *  3. an Ed25519 key made on the phone, registered with `vpn/v1/certificate` (persistent); the
 *     WireGuard private key is that key's X25519 twin. One key serves every server.
 * No API key, no Blackdey account: only the user's own session. Nothing is uploaded except the
 * public key Proton needs to issue the config. The device name in the dashboard is «Blackdey».
 *
 * The requests go out directly (Blackdey is excluded from its own tunnel) and, if that fails
 * while a tunnel is up, through the tunnel's local SOCKS.
 */
object ProtonVpn {
    const val SIGN_IN_URL = "https://account.protonvpn.com/login"
    const val DASHBOARD_HOST = "account.protonvpn.com"
    private const val BASE = "https://account.protonvpn.com"
    private const val COOKIE_URL = "$BASE/api/"
    /** The dashboard's own client id; its version is read from the page (it moves every release). */
    private const val CLIENT_ID = "web-vpn-settings"
    const val FALLBACK_VERSION = "5.0.421.0"
    const val DEVICE_NAME = "Blackdey"
    const val MAX_SERVERS = 300
    private const val RENEW_BEFORE_MS = 3L * 24 * 3600_000

    data class Pick(val name: String, val country: String?, val ip: String, val publicKey: String, val label: String, val ipv6: String? = null)

    /** The account's WireGuard key: X25519, base64; [expiresAt] in seconds (0: not said). */
    data class AccountKey(val privateKey: String, val expiresAt: Long)

    sealed class Outcome {
        data class Got(val tunnels: List<WgTunnel>, val key: AccountKey, val tier: Int) : Outcome()
        object NotSignedIn : Outcome()
        object Expired : Outcome()
        data class Failed(val detail: String) : Outcome()
    }

    // ── account key (encrypted on the device) ───────────────────────────────────────────────────

    private fun keyFile(context: Context) = SecretFile(context, "proton-key.bin", "simorgh_proton_v1")

    fun savedKey(context: Context): AccountKey? = runCatching {
        val o = JSONObject(keyFile(context).read() ?: return null)
        AccountKey(o.getString("priv"), o.optLong("exp"))
    }.getOrNull()

    private fun saveKey(context: Context, key: AccountKey) =
        keyFile(context).write(JSONObject().put("priv", key.privateKey).put("exp", key.expiresAt).toString())

    // ── session (the WebView's cookies) ─────────────────────────────────────────────────────────

    fun sessions(): List<String> {
        val all = runCatching { CookieManager.getInstance().getCookie(COOKIE_URL) }.getOrNull() ?: return emptyList()
        return parseSessionUids(all)
    }

    internal fun parseSessionUids(cookieHeader: String): List<String> =
        cookieHeader.split(';').map { it.trim().substringBefore('=') }
            .filter { it.startsWith("AUTH-") && it.length > 5 }
            .map { it.removePrefix("AUTH-") }
            .distinct()

    fun signedIn(): Boolean = sessions().isNotEmpty()

    /** Sign out: Proton's cookies and site storage, plus the device key and every Proton tunnel. */
    fun signOut(context: Context) {
        val cm = CookieManager.getInstance()
        for (site in SITES) for (path in COOKIE_PATHS) {
            val url = "https://$site$path"
            val names = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
                .split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }
            val parent = site.split('.').takeLast(2).joinToString(".")
            for (name in names) for (p in COOKIE_PATHS) {
                cm.setCookie(url, "$name=; Max-Age=0; Path=$p; Secure")
                cm.setCookie(url, "$name=; Max-Age=0; Path=$p; Domain=$parent; Secure")
            }
        }
        cm.flush()
        runCatching { val ws = android.webkit.WebStorage.getInstance(); SITES.forEach { ws.deleteOrigin("https://$it") } }
        runCatching { keyFile(context).delete() }
        runCatching { WgTunnelStore.get(context).replaceSource(WgSource.PROTON, emptyList()) }
    }

    private val SITES = listOf(
        "account.protonvpn.com", "account-api.protonvpn.com", "protonvpn.com",
        "account.proton.me", "account-api.proton.me", "proton.me", "verify.proton.me", "verify-api.proton.me",
    )
    private val COOKIE_PATHS = listOf("/", "/api/", "/api/auth/refresh")

    // ── the dashboard's calls ───────────────────────────────────────────────────────────────────

    /**
     * Fetches the account's servers and makes one tunnel per server (all on one key). [version]
     * is the dashboard's own (`SENTRY_RELEASE.id` on the page), "" for the fallback.
     */
    suspend fun sync(context: Context, version: String, userAgent: String, max: Int = MAX_SERVERS): Outcome =
        withContext(Dispatchers.IO) {
            val uids = sessions()
            if (uids.isEmpty()) return@withContext Outcome.NotSignedIn
            val cookies = runCatching { CookieManager.getInstance().getCookie(COOKIE_URL) }.getOrNull().orEmpty()
            val outcome = runCatching { syncVia(context, Proxy.NO_PROXY, uids, cookies, version, userAgent, max) }
            val direct = outcome.getOrNull()
            if (direct != null && direct !is Outcome.Failed) return@withContext direct
            if (ConnectionStateStore.state.value != ConnectionState.CONNECTED) {
                return@withContext direct ?: Outcome.Failed(outcome.exceptionOrNull()?.message ?: "network")
            }
            val (h, p) = LivePing.socksEndpoint(context)
            log("direct failed; retrying through the tunnel")
            runCatching {
                syncVia(context, Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(h, p)), uids, cookies, version, userAgent, max)
            }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }
        }

    private fun syncVia(
        context: Context, proxy: Proxy, uids: List<String>, cookies: String, version: String, userAgent: String, max: Int,
    ): Outcome {
        val http = OkHttpClient.Builder().proxy(proxy)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .build()
        try {
            var found: Pair<Api, JSONObject>? = null
            for (uid in uids) {
                val a = Api(http, uid, cookies, version.ifBlank { FALLBACK_VERSION }, userAgent)
                try { found = a to a.get("vpn/v2"); break } catch (e: ApiError) { if (!e.auth && !e.scope) throw e }
            }
            val (api, vpn) = found ?: return Outcome.Expired
            val maxTier = vpn.optJSONObject("VPN")?.optInt("MaxTier", 0) ?: 0
            val flags = runCatching { api.get("vpn/v2/clientconfig").optJSONObject("FeatureFlags") }.getOrNull()
            val logicals = api.get("vpn/v1/logicals?WithIpV6=1").optJSONArray("LogicalServers") ?: JSONArray()
            val picks = pick(logicals, maxTier, max)
            log("tier $maxTier, ${logicals.length()} servers listed, ${picks.size} usable")
            if (picks.isEmpty()) return Outcome.Failed("هیچ سرور WireGuard برای این حساب نیست")
            val usable = savedKey(context)?.takeIf { it.expiresAt == 0L || it.expiresAt * 1000 - System.currentTimeMillis() > RENEW_BEFORE_MS }
            val key = usable ?: run {
                val keys = Keys.generate()
                val r = api.post(
                    "vpn/v1/certificate",
                    JSONObject()
                        .put("ClientPublicKey", Base64Codec.encode(keys.ed25519Public))
                        .put("Mode", "persistent")
                        .put("DeviceName", DEVICE_NAME)
                        .put("Features", features(flags, picks.first())),
                )
                AccountKey(keys.x25519Private, r.optLong("ExpirationTime", 0L)).also { saveKey(context, it) }
            }
            val now = System.currentTimeMillis()
            return Outcome.Got(picks.map { tunnelFor(key.privateKey, it, now) }, key, maxTier)
        } catch (e: ApiError) {
            log("refused, HTTP ${e.status} code ${e.code}")
            return if (e.auth) Outcome.Expired else Outcome.Failed(e.message ?: "HTTP ${e.status}")
        } finally {
            http.connectionPool.evictAll()
        }
    }

    // ── pure parts (unit-tested) ────────────────────────────────────────────────────────────────

    /**
     * The dashboard's filter, best Score first, then one per country before a second of any
     * country. Of a server's machines an online one is preferred.
     */
    fun pick(logicals: JSONArray, maxTier: Int, max: Int, random: Random = Random.Default): List<Pick> {
        val usable = (0 until logicals.length()).mapNotNull { logicals.optJSONObject(it) }
            .filter { it.optInt("Tier", Int.MAX_VALUE) <= maxTier && (it.optInt("Features") and 3) == 0 && it.optInt("Status", 1) != 0 }
            .mapNotNull { l ->
                val servers = l.optJSONArray("Servers") ?: return@mapNotNull null
                val withKey = (0 until servers.length()).mapNotNull { servers.optJSONObject(it) }
                    .filter { str(it, "X25519PublicKey").isNotEmpty() && str(it, "EntryIP").isNotEmpty() }
                if (withKey.isEmpty()) null else l to withKey
            }
            .sortedBy { it.first.optDouble("Score", Double.MAX_VALUE) }
        val byCountry = LinkedHashMap<String, MutableList<Pair<JSONObject, List<JSONObject>>>>()
        for (u in usable) byCountry.getOrPut(str(u.first, "ExitCountry")) { ArrayList() } += u
        val out = ArrayList<Pick>()
        var round = 0
        while (out.size < max) {
            var any = false
            for (list in byCountry.values) {
                val (l, servers) = list.getOrNull(round) ?: continue
                val online = servers.filter { truthy(it, "Status", default = true) }.ifEmpty { servers }
                val s = online[random.nextInt(online.size)]
                out += Pick(
                    name = str(l, "Name"), country = str(l, "ExitCountry").ifEmpty { null },
                    ip = str(s, "EntryIP"), publicKey = str(s, "X25519PublicKey"), label = str(s, "Label"),
                    ipv6 = str(s, "EntryIPv6").ifEmpty { null },
                )
                any = true
                if (out.size >= max) break
            }
            if (!any) break
            round++
        }
        return out
    }

    fun features(flags: JSONObject?, p: Pick): JSONObject = JSONObject().apply {
        if (p.label.isNotEmpty()) put("Bouncing", p.label)
        if (truthy(flags, "PortForwarding")) put("PortForwarding", false)
        if (truthy(flags, "VpnAccelerator")) put("SplitTCP", true)
        put("peerName", p.name)
        put("peerIp", p.ip)
        put("peerPublicKey", p.publicKey)
        put("platform", "Android")
    }

    /** The dashboard's config template (IPv4 inside the tunnel, as the free dashboard issues it). */
    fun tunnelFor(privateKey: String, p: Pick, now: Long): WgTunnel = WgTunnel(
        id = "wg:proton:" + p.ip,
        name = p.name,
        privateKey = privateKey,
        addresses = listOf("10.2.0.2/32"),
        dns = listOf("10.2.0.1"),
        mtu = 1280,
        peers = listOf(WgPeer(publicKey = p.publicKey, endpointHost = p.ip, endpointPort = 51820, keepAliveSeconds = 25)),
        source = WgSource.PROTON,
        countryCode = p.country?.uppercase()?.takeIf { it.length == 2 },
        server = p.name,
        addedAt = now,
    )

    /**
     * Proton registers an Ed25519 public key and its servers turn it into the X25519 key WireGuard
     * sees; the WireGuard private key is the Ed25519 seed's clamped SHA-512 half.
     */
    internal object Keys {
        class Pair(val ed25519Public: ByteArray, val x25519Private: String)
        private val random = SecureRandom()

        fun generate(): Pair = fromSeed(ByteArray(32).also(random::nextBytes))

        fun fromSeed(seed: ByteArray): Pair {
            require(seed.size == 32)
            val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
            Ed25519.generatePublicKey(seed, 0, pub, 0)
            return Pair(pub, WgKeys.x25519FromEd25519Seed(seed))
        }
    }

    // ── the wire ────────────────────────────────────────────────────────────────────────────────

    private class ApiError(val status: Int, val code: Int, message: String) : Exception(message) {
        val auth: Boolean get() = status == 401 || code == 10013 || code == 401
        val scope: Boolean get() = code == 9106
    }

    private class Api(val http: OkHttpClient, val uid: String, val cookies: String, val version: String, val userAgent: String) {
        fun get(path: String): JSONObject = send(request(path).get().build())
        fun post(path: String, body: JSONObject): JSONObject =
            send(request(path).post(body.toString().toRequestBody("application/json;charset=utf-8".toMediaType())).build())

        private fun request(path: String) = Request.Builder().url("$BASE/api/$path")
            .header("x-pm-uid", uid)
            .header("x-pm-appversion", "$CLIENT_ID@$version")
            .header("Accept", "application/vnd.protonmail.v1+json")
            .header("Cookie", cookies)
            .header("User-Agent", userAgent)

        private fun send(req: Request): JSONObject {
            var last: java.io.IOException? = null
            repeat(3) { i ->
                if (i > 0) Thread.sleep(2_000L * i)
                try { return once(req) } catch (e: java.io.IOException) { last = e }
            }
            throw last ?: java.io.IOException("no answer")
        }

        private fun once(req: Request): JSONObject = http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull()
            val code = o?.optInt("Code") ?: 0
            if (!r.isSuccessful || o == null || (code != 1000 && code != 1001)) {
                throw ApiError(r.code, code, o?.let { str(it, "Error") }?.ifEmpty { null } ?: "HTTP ${r.code}")
            }
            o
        }
    }

    private fun str(o: JSONObject, key: String): String {
        val v = o.opt(key)
        return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
    }

    private fun truthy(o: JSONObject?, key: String, default: Boolean = false): Boolean = when (val v = o?.opt(key)) {
        null, JSONObject.NULL -> default
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "0" && v != "false"
        else -> true
    }

    /** Steps only: never a cookie, key or session id. */
    private fun log(line: String) { runCatching { AppLogRepository.info(LogSource.SERVICE, "[Proton] $line") } }
}
