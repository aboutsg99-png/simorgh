package com.uacspoofer.mobile.profiles

import android.content.Context
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.vpn.LivePing
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL

/** Result of fetching one subscription URL. */
internal data class SubscriptionFetch(
    val body: String,
    val userInfo: SubscriptionUserInfo?,
    val title: String?,
    val viaTunnel: Boolean,
    val updateIntervalHours: Int? = null,
)

internal data class SubscriptionRefreshResult(
    val subscription: Subscription,
    val library: ProfileLibrary,
    val addedCount: Int,
    val unsupportedCount: Int,
    val invalidCount: Int,
)

/**
 * Downloads subscription links, imports the configs they carry as a named group and keeps the
 * group in sync on refresh. Configs that were already in the group keep their id, so a selected
 * config stays selected across refreshes.
 */
internal object SubscriptionManager {
    private val urlPattern = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val unsupportedPattern = Regex("(?i)(?<![a-z0-9])(?:ss|shadowsocks|hysteria2|hy2|hysteria|tuic)://[^\\s<>\"']+")

    fun isSubscriptionUrl(text: String): Boolean {
        val clean = text.trim()
        if (!urlPattern.matches(clean)) return false
        return runCatching { URI(clean).host }.getOrNull().isNullOrBlank().not()
    }

    /** Blocking; call off the main thread. */
    fun importOrRefresh(context: Context, url: String, existingId: String? = null): SubscriptionRefreshResult {
        val subscriptions = SubscriptionStore(context)
        val profiles = ProfileStore(context)
        val cleanUrl = url.trim()
        val previous = existingId?.let(subscriptions::find) ?: subscriptions.findByUrl(cleanUrl)

        val fetched = try {
            fetch(context, cleanUrl)
        } catch (error: Exception) {
            previous?.let { subscriptions.markError(it.id, error.message) }
            throw error
        }
        val parsed = SubscriptionConfigParser.parse(fetched.body)
        val unsupported = countUnsupported(fetched.body)
        if (parsed.profiles.isEmpty()) {
            throw IOException(
                if (unsupported > 0) {
                    "این اشتراک فقط لینک‌های ss یا hysteria2 دارد که هنوز پشتیبانی نمی‌شوند"
                } else {
                    "هیچ کانفیگ vless، vmess یا trojan در پاسخ سرور پیدا نشد"
                },
            )
        }

        // Reuse ids of configs that were already in this group.
        val library = profiles.snapshot()
        val oldIds = previous?.profileIds.orEmpty().toSet()
        val oldByCanonical = library.customProfiles
            .filter { it.id in oldIds }
            .associateBy { ProfileUriParser.canonicalUri(it) }
        val incoming = parsed.profiles.map { profile ->
            val canonical = ProfileUriParser.canonicalUri(profile)
            val reused = oldByCanonical[canonical]
            val withId = if (reused != null) profile.copy(id = reused.id, country = reused.country) else profile
            if (withId.rawUri.isBlank()) withId.copy(rawUri = canonical) else withId
        }
        profiles.importProfiles(incoming)
        val newIds = incoming.map { it.id }
        val stale = oldIds - newIds.toSet()
        val finalLibrary = if (stale.isNotEmpty()) profiles.deleteMany(stale) else profiles.snapshot()

        val name = fetched.title?.takeIf { it.isNotBlank() }
            ?: previous?.name?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(cleanUrl).host }.getOrNull()
            ?: cleanUrl
        val subscription = Subscription(
            id = previous?.id ?: SubscriptionStore.newId(),
            url = cleanUrl,
            name = name,
            profileIds = newIds,
            userInfo = fetched.userInfo,
            updatedAt = System.currentTimeMillis(),
            unsupportedCount = unsupported,
            remarkInfo = SubscriptionRemarkInfo.parse(parsed.profiles.map { it.name }),
            updateIntervalHours = fetched.updateIntervalHours ?: previous?.updateIntervalHours,
            lastError = null,
        )
        subscriptions.upsert(subscription)
        AppLogRepository.info(
            LogSource.APP,
            "Subscription updated name=$name configs=${newIds.size} unsupported=$unsupported tunnel=${fetched.viaTunnel}",
        )
        return SubscriptionRefreshResult(subscription, finalLibrary, newIds.size, unsupported, parsed.invalidCount)
    }

    /**
     * Refreshes every saved subscription (or [ids]), one at a time; a failing one keeps its configs
     * and records its error. Returns (refreshed, failed). Blocking; call off the main thread.
     */
    fun refreshAll(context: Context, ids: Set<String>? = null): Pair<Int, Int> {
        val store = SubscriptionStore(context)
        var ok = 0
        var failed = 0
        store.all().filter { ids == null || it.id in ids }.forEach { sub ->
            runCatching { importOrRefresh(context, sub.url, sub.id) }
                .onSuccess { ok++ }
                .onFailure { error -> failed++; store.markError(sub.id, error.message ?: "خطا") }
        }
        return ok to failed
    }

    /** Removes several subscriptions and every config they added (multi-select delete). */
    fun deleteMany(context: Context, ids: Set<String>): ProfileLibrary {
        val subscriptions = SubscriptionStore(context)
        val targets = subscriptions.all().filter { it.id in ids }
        subscriptions.removeAll(ids)
        val profiles = ProfileStore(context)
        val drop = targets.flatMap { it.profileIds }.toSet()
        val existing = profiles.snapshot().customProfiles.map { it.id }.toSet()
        return profiles.deleteMany(drop intersect existing)
    }

    /** Adds private configs (share links, one or many) outside any subscription. */
    fun addPrivate(context: Context, text: String): ProfileImportResult = ProfileStore(context).importText(text)

    /** Removes the subscription and every config it added. */
    fun delete(context: Context, id: String): ProfileLibrary {
        val subscriptions = SubscriptionStore(context)
        val target = subscriptions.find(id)
        subscriptions.remove(id)
        val profiles = ProfileStore(context)
        val ids = target?.profileIds.orEmpty().toSet()
        val existing = profiles.snapshot().customProfiles.map { it.id }.toSet()
        return profiles.deleteMany(ids intersect existing)
    }

    private fun countUnsupported(body: String): Int {
        val expanded = buildString {
            append(body)
            val collapsed = body.filterNot(Char::isWhitespace)
            runCatching { Base64Codec.decode(collapsed).toString(Charsets.UTF_8) }.getOrNull()?.let {
                append('\n').append(it)
            }
        }
        return unsupportedPattern.findAll(expanded).map { it.value }.toSet().size
    }

    /** Tries a direct request first; if it fails while a tunnel is up, retries through the local SOCKS. */
    fun fetch(context: Context, url: String): SubscriptionFetch {
        val direct = runCatching { request(url, Proxy.NO_PROXY) }
        direct.getOrNull()?.let { return it.copy(viaTunnel = false) }
        val tunnelUp = ConnectionStateStore.state.value == ConnectionState.CONNECTED
        if (!tunnelUp) throw direct.exceptionOrNull() ?: IOException("دریافت اشتراک انجام نشد")
        val (host, port) = LivePing.socksEndpoint(context)
        AppLogRepository.warning(LogSource.APP, "Subscription direct fetch failed, retrying via SOCKS", direct.exceptionOrNull())
        return request(url, Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port)))
            .copy(viaTunnel = true)
    }

    private fun request(url: String, proxy: Proxy): SubscriptionFetch {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = URL(current).openConnection(proxy) as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "*/*")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("پاسخ سرور اشتراک: HTTP $code")
                val headers = connection.headerFields.orEmpty()
                fun header(name: String): String? = headers.entries
                    .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
                    ?.value?.firstOrNull()
                val body = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_BODY_BYTES) throw IOException("پاسخ اشتراک بیش از حد بزرگ است")
                    }
                    out.toString(Charsets.UTF_8.name())
                }
                return SubscriptionFetch(
                    body = body,
                    userInfo = SubscriptionUserInfo.parse(header("subscription-userinfo")),
                    title = decodeTitle(header("profile-title")),
                    viaTunnel = proxy != Proxy.NO_PROXY,
                    updateIntervalHours = header("profile-update-interval")?.trim()?.toIntOrNull()?.takeIf { it in 1..720 },
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("تغییر مسیرهای بیش از حد")
    }

    internal fun decodeTitle(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val decoded = if (value.startsWith("base64:", ignoreCase = true)) {
            runCatching { Base64Codec.decode(value.substring(7).trim()).toString(Charsets.UTF_8) }.getOrNull()
        } else {
            value
        }
        return decoded?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
    }

    private const val TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    // Panels such as Marzban and 3x-ui return the plain base64 link list for v2rayNG clients.
    private const val USER_AGENT = "v2rayNG/1.9.0"
}
