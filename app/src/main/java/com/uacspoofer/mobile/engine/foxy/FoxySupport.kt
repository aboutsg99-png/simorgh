package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import android.os.Build
import android.util.Base64
import android.webkit.CookieManager
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject

/*
 * Support layer for the Firefox VPN (Rubah) engine.
 *
 * The control-plane code in this package (FxaAuthRepository, GuardianClient,
 * ServerListClient, FastlyChallengeSolver, FirefoxAccountHeaders, TokenStore) is
 * adapted from Vauth/FoxyVPN (MIT, https://github.com/Vauth/FoxyVPN). Differences:
 * - No third-party remote config: FoxyVPN downloads endpoints and headers from
 *   github.com/Vauth/FoxyConfig at runtime. A remote file that can rewrite the OAuth
 *   token URL could exfiltrate refresh tokens, so Blackdey pins Mozilla's own
 *   endpoints in code (see [FoxyRemoteConfig]).
 * - No relayer / upstream proxy chaining.
 * - Logs go to the app log with a [FOXY] prefix, never tokens.
 */

internal object AppLogger {
    private fun line(tag: String, message: String) = "[FOXY] $tag: $message"
    fun d(tag: String, message: String) = AppLogRepository.debug(LogSource.SERVICE, line(tag, message))
    fun i(tag: String, message: String) = AppLogRepository.info(LogSource.SERVICE, line(tag, message))
    fun w(tag: String, message: String, error: Throwable? = null) =
        AppLogRepository.warning(LogSource.SERVICE, line(tag, message), error)
    fun e(tag: String, message: String, error: Throwable? = null) =
        AppLogRepository.error(LogSource.SERVICE, line(tag, message), error)
}

internal object FoxyHosts {
    private val hostname = Regex("^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
    private val ipv4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    fun isValidIpAddress(value: String): Boolean {
        val v = value.trim().removeSurrounding("[", "]")
        if (ipv4.matches(v)) return true
        return v.contains(':') && v.all { it.isLetterOrDigit() || it == ':' || it == '.' } &&
            runCatching { InetAddress.getByName(v) }.isSuccess
    }

    fun isValidEdgeHost(value: String): Boolean {
        val host = value.trim().removeSuffix(".")
        return hostname.matches(host) || isValidIpAddress(host)
    }
}

data class ComponentConfig(
    val headers: Map<String, String>,
    val protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
)

data class GuardianEndpoints(
    val token: String = "https://vpn.mozilla.org/api/v1/fpn/token",
    val status: String = "https://vpn.mozilla.org/api/v1/fpn/status",
    val activate: String = "https://vpn.mozilla.org/api/v1/fpn/activate",
)

data class ControlPlaneEndpoints(
    val oauthToken: String = "https://oauth.accounts.firefox.com/v1/token",
    val oauthFallback: String = "https://api.accounts.firefox.com/v1/oauth/token",
    val authorization: String = "https://api.accounts.firefox.com/v1/oauth/authorization",
    val recoveryStatus: String = "https://api.accounts.firefox.com/v1/recovery_email/status",
    val sessionDestroy: String = "https://api.accounts.firefox.com/v1/session/destroy",
    val login: String = "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email",
)

data class EndpointsConfig(
    val guardian: GuardianEndpoints = GuardianEndpoints(),
    val controlPlane: ControlPlaneEndpoints = ControlPlaneEndpoints(),
    val serverList: String = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records",
    val productDetails: String = "https://product-details.mozilla.org/1.0/firefox_versions.json",
    val latencyProbe: String = "https://www.gstatic.com/generate_204",
)

data class RemoteConfig(
    val version: Int,
    val endpoints: EndpointsConfig = EndpointsConfig(),
    val controlPlane: ComponentConfig,
    val guardian: ComponentConfig,
    val solver: ComponentConfig,
    val browser: ComponentConfig,
)

internal val ANDROID_RELEASE: String =
    Build.VERSION.RELEASE?.trim()?.takeIf { it.isNotEmpty() } ?: "14"

internal fun resolvePlaceholders(raw: String): String =
    raw.replace("{android_version}", ANDROID_RELEASE, ignoreCase = true)

private val FIREFOX_UA = resolvePlaceholders(
    "Mozilla/5.0 (Android {android_version}; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0",
)
private val MOZILLA_VPN_UA = resolvePlaceholders("MozillaVPN/2.24.0 (sys:android {android_version})")

/** Pinned, in-code configuration. Never downloaded. */
object FoxyRemoteConfig {
    val current: RemoteConfig = RemoteConfig(
        version = 1,
        controlPlane = ComponentConfig(
            headers = mapOf(
                "User-Agent" to MOZILLA_VPN_UA,
                "Accept" to "*/*",
                "Accept-Encoding" to "identity",
            ),
        ),
        guardian = ComponentConfig(
            headers = mapOf(
                "User-Agent" to MOZILLA_VPN_UA,
                "Accept" to "application/json",
                "Accept-Encoding" to "identity",
                "Content-Type" to "application/json",
                "Cache-Control" to "max-age=0",
            ),
        ),
        solver = ComponentConfig(headers = mapOf("User-Agent" to FIREFOX_UA, "Accept-Encoding" to "identity")),
        browser = ComponentConfig(headers = mapOf("User-Agent" to FIREFOX_UA)),
    )

    /** FoxyVPN re-downloads its remote config here; Blackdey keeps pinned values. */
    fun triggerRefresh() = Unit
}

fun Request.Builder.applyFirefoxHeaders(): Request.Builder = FirefoxAccountHeaders.apply(this)

class SimpleCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies
            .filter { it.matches(url) }
            .sortedByDescending { it.domain.length }
            .distinctBy { it.name }
    }

    @Synchronized fun snapshotAll(): List<Cookie> = cookies.toList()

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    @Synchronized
    fun syncToWebView(cookies: List<Cookie>) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        for (cookie in cookies) {
            val host = cookie.domain.trimStart('.')
            if (host.isEmpty()) continue
            val cookieString = buildString {
                append(cookie.name).append("=").append(cookie.value)
                append("; Domain=").append(cookie.domain)
                append("; Path=").append(cookie.path)
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
            }
            runCatching { manager.setCookie("https://$host", cookieString) }
        }
        runCatching { manager.flush() }
    }
}

/**
 * Control-plane HTTP client. Blackdey excludes its own package from the VPN
 * (see AppRoutingPreferences), so these calls always use the phone's real network,
 * even while another engine's tunnel is up.
 */
object ControlPlaneHttp {
    val cookieJar = SimpleCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

/** AES-256-GCM payload with a non-exportable Android Keystore key. */
internal class SecurePreferences(
    context: Context,
    name: String,
    private val failure: (Exception) -> SecureStorageException = { SecureStorageException(it) },
) {
    private val namespace = "${context.packageName}.$name.v1"
    private val prefs = context.applicationContext.getSharedPreferences("${name}_secure_v1", Context.MODE_PRIVATE)
    private val lock = locks.getOrPut(namespace) { Any() }
    private var cachedPayload: String? = null
    private var cachedValues: Map<String, Any>? = null

    fun getString(key: String, default: String?): String? = guarded {
        val value = load()[key] ?: return@guarded default
        check(value is String) { "Unexpected credential value type" }
        value
    }

    fun getLong(key: String, default: Long): Long = guarded {
        val value = load()[key] ?: return@guarded default
        check(value is Long) { "Unexpected credential value type" }
        value
    }

    fun edit() = Editor()

    inner class Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun putLong(key: String, value: Long) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun clear() = apply { clear = true }

        fun commit(): Boolean = guarded {
            if (clear && changes.isEmpty()) {
                cachedPayload = null
                cachedValues = null
                return@guarded prefs.edit().clear().commit()
            }
            val current = if (clear) emptyMap() else load()
            val next = current.toMutableMap()
            for ((key, value) in changes) if (value == null) next.remove(key) else next[key] = value
            write(next)
            true
        }
    }

    private fun <T> guarded(action: () -> T): T = synchronized(lock) {
        try {
            action()
        } catch (error: Exception) {
            cachedPayload = null
            cachedValues = null
            throw error as? SecureStorageException ?: failure(error)
        }
    }

    private fun load(): Map<String, Any> {
        val payload = prefs.getString(PAYLOAD, null) ?: return emptyMap()
        if (payload == cachedPayload) return checkNotNull(cachedValues)
        return decode(payload).also {
            cachedPayload = payload
            cachedValues = it
        }
    }

    private fun write(values: Map<String, Any>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, CredentialKeys.get(namespace, create = true))
        cipher.updateAAD(namespace.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12) { "Invalid encryption IV" }
        val ciphertext = cipher.doFinal(JSONObject(values).toString().toByteArray(Charsets.UTF_8))
        val payload = "1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(payload.length <= MAX_PAYLOAD_CHARS) { "Credential payload is too large" }
        check(prefs.edit().putString(PAYLOAD, payload).commit()) { "Could not persist encrypted credentials" }
        cachedPayload = payload
        cachedValues = values.toMap()
    }

    private fun decode(payload: String): Map<String, Any> {
        check(payload.length <= MAX_PAYLOAD_CHARS) { "Credential payload is too large" }
        val parts = payload.split(':')
        check(parts.size == 3 && parts[0] == "1") { "Unsupported credential format" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        check(iv.size == 12 && ciphertext.size >= 16) { "Invalid credential payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, CredentialKeys.get(namespace, create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD(namespace.toByteArray(Charsets.UTF_8))
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        return json.keys().asSequence().associateWith { key ->
            when (val value = json.get(key)) {
                is String -> value
                is Number -> value.toLong()
                else -> error("Unexpected credential value type")
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private const val PAYLOAD = "encrypted_payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_PAYLOAD_CHARS = 64 * 1024
    }
}
