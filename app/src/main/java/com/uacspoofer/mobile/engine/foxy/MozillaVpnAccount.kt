package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import com.uacspoofer.mobile.engine.wireguard.SecretFile
import com.uacspoofer.mobile.engine.wireguard.WgKeys
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MozillaVpnNotSignedInException : IllegalStateException("برای حالت WireGuard موزیلا، اول با اشتراک Mozilla VPN وارد شو")

class MozillaVpnNoSubscriptionException(code: Int) :
    IllegalStateException("این حساب اشتراک فعال Mozilla VPN ندارد (HTTP $code). حالت HTTPS فایرفاکس همچنان کار می‌کند.")

/**
 * The user's own Mozilla VPN subscription (separate from the free Firefox account the MASQUE
 * path uses): a Guardian token from the vpn.mozilla.org sign-in, one WireGuard key registered as
 * a device on the account, and the relay list. Everything is stored encrypted on the phone
 * (Keystore), the private key never leaves it.
 */
class MozillaVpnAccount private constructor(context: Context) {
    private val app = context.applicationContext
    private val secret = SecretFile(app, "mozvpn.bin", "simorgh_mozvpn_v1")
    private val serversCache = File(app.noBackupFilesDir, "mozvpn_servers.json")
    private val http get() = ControlPlaneHttp.client
    private val mutableSignedIn = MutableStateFlow(runCatching { load()?.token?.isNotBlank() == true }.getOrDefault(false))
    val signedIn: StateFlow<Boolean> = mutableSignedIn.asStateFlow()

    private data class Stored(
        val token: String,
        val privateKey: String = "",
        val uniqueId: String = UUID.randomUUID().toString(),
        val ipv4: String = "",
        val ipv6: String = "",
    ) {
        fun json(): String = JSONObject()
            .put("token", token).put("privateKey", privateKey).put("uniqueId", uniqueId)
            .put("ipv4", ipv4).put("ipv6", ipv6).toString()

        companion object {
            fun of(raw: String): Stored = JSONObject(raw).let {
                Stored(it.optString("token"), it.optString("privateKey"), it.optString("uniqueId").ifBlank { UUID.randomUUID().toString() }, it.optString("ipv4"), it.optString("ipv6"))
            }
        }
    }

    @Synchronized private fun load(): Stored? = secret.read()?.let(Stored::of)
    @Synchronized private fun save(s: Stored) { secret.write(s.json()); mutableSignedIn.value = s.token.isNotBlank() }

    fun isSignedIn(): Boolean = signedIn.value

    /** Exchanges the one-time code of the sign-in page for the Guardian token (PKCE). */
    suspend fun completeLogin(code: String, verifier: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
            val body = JSONObject().put("code", code).put("code_verifier", verifier).toString()
            val req = Request.Builder().url("${MozillaWg.GUARDIAN}/api/v2/vpn/login/verify")
                .post(body.toRequestBody(JSON))
                .applyFirefoxHeaders()
                .build()
            val json = http.newCall(req).execute().use { r ->
                val text = r.body?.readBoundedText(256 * 1024).orEmpty()
                if (!r.isSuccessful) throw IOException("Mozilla VPN sign-in failed: HTTP ${r.code}")
                JSONObject(text)
            }
            val token = json.optString("token")
            require(token.isNotBlank()) { "Mozilla VPN returned no token" }
            val previous = load()
            save(Stored(token = token, uniqueId = previous?.uniqueId ?: UUID.randomUUID().toString()))
            log("signed in")
            Unit
        }
    }

    fun signOut() {
        secret.delete()
        serversCache.delete()
        mutableSignedIn.value = false
    }

    /** This phone's key and the addresses Mozilla gave it; registers a new key on first use. */
    suspend fun device(): Pair<String, MozillaWgDevice> = withContext(Dispatchers.IO) {
        val s = load()?.takeIf { it.token.isNotBlank() } ?: throw MozillaVpnNotSignedInException()
        if (s.privateKey.isNotBlank() && s.ipv4.isNotBlank()) {
            return@withContext s.privateKey to MozillaWgDevice(WgKeys.publicKey(s.privateKey), s.ipv4, s.ipv6)
        }
        val keys = WgKeys.generate()
        val body = JSONObject().put("name", "Blackdey Android").put("unique_id", s.uniqueId).put("pubkey", keys.publicKey).toString()
        val device = authorized("POST", "/api/v1/vpn/device", body) { MozillaWg.parseDevice(it) }
        save(s.copy(privateKey = keys.privateKey, ipv4 = device.ipv4, ipv6 = device.ipv6))
        log("device key registered")
        keys.privateKey to device
    }

    /** Relay list (cached 6 h; the cached copy is used when the request fails). */
    suspend fun servers(): List<MozillaWgServer> = withContext(Dispatchers.IO) {
        val fresh = serversCache.isFile && System.currentTimeMillis() - serversCache.lastModified() < 6 * 3600_000L
        if (fresh) runCatching { MozillaWg.parseServers(serversCache.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        runCatching {
            authorized("GET", "/api/v1/vpn/servers", null) { raw ->
                MozillaWg.parseServers(raw).also { if (it.isNotEmpty()) serversCache.writeText(raw) }
            }
        }.getOrElse { error ->
            if (error is MozillaVpnNotSignedInException || error is MozillaVpnNoSubscriptionException) throw error
            runCatching { MozillaWg.parseServers(serversCache.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: throw error
        }
    }

    /** Forget the registered key (e.g. Mozilla removed the device); the next connect registers a new one. */
    fun resetDevice() {
        load()?.let { save(it.copy(privateKey = "", ipv4 = "", ipv6 = "")) }
    }

    private fun <T> authorized(method: String, path: String, body: String?, parse: (String) -> T): T {
        val s = load()?.takeIf { it.token.isNotBlank() } ?: throw MozillaVpnNotSignedInException()
        val builder = Request.Builder().url(MozillaWg.GUARDIAN + path)
            .header("Authorization", "Bearer ${s.token}")
            .applyFirefoxHeaders()
        if (method == "POST") builder.post((body ?: "{}").toRequestBody(JSON)) else builder.get()
        return http.newCall(builder.build()).execute().use { r ->
            val text = r.body?.readBoundedText(4 * 1024 * 1024).orEmpty()
            when {
                r.code == 401 -> { signOut(); throw MozillaVpnNotSignedInException() }
                r.code == 403 || r.code == 402 -> throw MozillaVpnNoSubscriptionException(r.code)
                !r.isSuccessful -> throw IOException("Mozilla VPN $path: HTTP ${r.code}")
                else -> parse(text)
            }
        }
    }

    private fun log(line: String) = runCatching { AppLogRepository.info(LogSource.SERVICE, "[MOZVPN] $line") }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        @Volatile private var instance: MozillaVpnAccount? = null
        fun get(context: Context): MozillaVpnAccount = instance ?: synchronized(this) {
            instance ?: MozillaVpnAccount(context).also { instance = it }
        }
    }
}
