package com.uacspoofer.mobile.profiles

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Values of the standard `subscription-userinfo` response header, in bytes and Unix seconds.
 * A field the server did not send is null; nothing here is ever estimated.
 */
data class SubscriptionUserInfo(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    val expire: Long?,
) {
    val used: Long get() = (upload ?: 0L) + (download ?: 0L)

    /** True when the server gave no cap (total missing or 0). */
    val unlimited: Boolean get() = total == null || total <= 0L

    val remaining: Long? get() = total?.takeIf { it > 0L }?.let { (it - used).coerceAtLeast(0L) }

    val noExpiry: Boolean get() = expire == null || expire <= 0L

    fun expired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        !noExpiry && expire!! * 1000L <= nowMillis

    companion object {
        /** Parses `upload=1; download=2; total=3; expire=4`; returns null when no field is present. */
        fun parse(header: String?): SubscriptionUserInfo? {
            if (header.isNullOrBlank()) return null
            val values = HashMap<String, Long>()
            header.split(';', ',').forEach { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2) {
                    val key = pieces[0].trim().lowercase()
                    val number = pieces[1].trim().toDoubleOrNull()?.toLong()
                    if (number != null) values[key] = number
                }
            }
            if (values.keys.none { it in setOf("upload", "download", "total", "expire") }) return null
            return SubscriptionUserInfo(values["upload"], values["download"], values["total"], values["expire"])
        }
    }
}

data class Subscription(
    val id: String,
    val url: String,
    val name: String,
    val profileIds: List<String>,
    val userInfo: SubscriptionUserInfo?,
    val updatedAt: Long,
    val unsupportedCount: Int = 0,
    /** Usage/expiry written in config names, used where the header gave nothing. */
    val remarkInfo: SubscriptionRemarkInfo? = null,
    /** `profile-update-interval` header, hours; null when the server did not send it. */
    val updateIntervalHours: Int? = null,
    /** Last refresh error (null after a successful refresh). */
    val lastError: String? = null,
) {
    /** Bytes used: header (upload + download) first, then config names. Null when unknown. */
    val usedBytes: Long?
        get() = userInfo?.takeIf { it.upload != null || it.download != null }?.used
            ?: remarkInfo?.usedBytes
            ?: remarkInfo?.let { r -> if (r.totalBytes != null && r.remainingBytes != null) (r.totalBytes - r.remainingBytes).coerceAtLeast(0L) else null }

    /** Traffic cap in bytes; null when unknown or unlimited. */
    val totalBytes: Long?
        get() = userInfo?.total?.takeIf { it > 0L }
            ?: remarkInfo?.totalBytes
            ?: remarkInfo?.let { r -> if (r.usedBytes != null && r.remainingBytes != null) r.usedBytes + r.remainingBytes else null }

    val remainingBytes: Long?
        get() = userInfo?.remaining ?: remarkInfo?.remainingBytes
            ?: totalBytes?.let { t -> usedBytes?.let { u -> (t - u).coerceAtLeast(0L) } }

    /** Expiry as Unix seconds; null when none was given. */
    val expireAt: Long?
        get() = userInfo?.expire?.takeIf { it > 0L } ?: remarkInfo?.expire

    /** Whole days until expiry (negative once expired), or null. */
    fun daysLeft(nowMillis: Long = System.currentTimeMillis()): Long? =
        expireAt?.let { Math.floorDiv(it * 1000L - nowMillis, 86_400_000L) }

    /** Used share of the cap, 0..1, or null without a cap. */
    val usedFraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { t -> ((usedBytes ?: (t - (remainingBytes ?: t))).toFloat() / t).coerceIn(0f, 1f) }
}

/** Saved subscription links, the configs each one added, and the last usage the server reported. */
class SubscriptionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Subscription> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ITEMS, "[]") ?: "[]")
        buildList {
            repeat(array.length()) { index ->
                array.optJSONObject(index)?.let { item -> runCatching { fromJson(item) }.getOrNull()?.let(::add) }
            }
        }
    }.getOrDefault(emptyList())

    fun find(id: String): Subscription? = all().firstOrNull { it.id == id }

    fun findByUrl(url: String): Subscription? = all().firstOrNull { it.url.trim() == url.trim() }

    @Synchronized
    fun upsert(subscription: Subscription) {
        val items = all().toMutableList()
        val index = items.indexOfFirst { it.id == subscription.id }
        if (index >= 0) items[index] = subscription else items.add(0, subscription)
        write(items)
    }

    @Synchronized
    fun remove(id: String) {
        write(all().filterNot { it.id == id })
    }

    @Synchronized
    fun removeAll(ids: Set<String>) {
        write(all().filterNot { it.id in ids })
    }

    @Synchronized
    fun markError(id: String, message: String?) {
        val items = all().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = items[index].copy(lastError = message?.take(200))
        write(items)
    }

    private fun write(items: List<Subscription>) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun toJson(item: Subscription): JSONObject = JSONObject()
        .put("id", item.id)
        .put("url", item.url)
        .put("name", item.name)
        .put("profileIds", JSONArray(item.profileIds))
        .put("updatedAt", item.updatedAt)
        .put("unsupported", item.unsupportedCount)
        .apply {
            item.updateIntervalHours?.let { put("interval", it) }
            item.lastError?.let { put("error", it) }
            item.remarkInfo?.let { r ->
                put(
                    "remarkInfo",
                    JSONObject().apply {
                        r.usedBytes?.let { put("used", it) }
                        r.totalBytes?.let { put("total", it) }
                        r.remainingBytes?.let { put("remaining", it) }
                        r.expire?.let { put("expire", it) }
                    },
                )
            }
        }
        .apply {
            item.userInfo?.let { info ->
                put(
                    "userInfo",
                    JSONObject().apply {
                        info.upload?.let { put("upload", it) }
                        info.download?.let { put("download", it) }
                        info.total?.let { put("total", it) }
                        info.expire?.let { put("expire", it) }
                    },
                )
            }
        }

    private fun fromJson(item: JSONObject): Subscription {
        val ids = item.optJSONArray("profileIds")
        val info = item.optJSONObject("userInfo")?.let { json ->
            fun opt(key: String): Long? = if (json.has(key)) json.optLong(key) else null
            SubscriptionUserInfo(opt("upload"), opt("download"), opt("total"), opt("expire"))
        }
        return Subscription(
            id = item.getString("id"),
            url = item.getString("url"),
            name = item.optString("name"),
            profileIds = buildList { if (ids != null) repeat(ids.length()) { add(ids.optString(it)) } },
            userInfo = info,
            updatedAt = item.optLong("updatedAt"),
            unsupportedCount = item.optInt("unsupported"),
            remarkInfo = item.optJSONObject("remarkInfo")?.let { json ->
                fun opt(key: String): Long? = if (json.has(key)) json.optLong(key) else null
                SubscriptionRemarkInfo(opt("used"), opt("total"), opt("remaining"), opt("expire")).takeUnless { it.isEmpty }
            },
            updateIntervalHours = if (item.has("interval")) item.optInt("interval") else null,
            lastError = item.optString("error").takeIf { it.isNotBlank() },
        )
    }

    companion object {
        private const val PREFS = "simorgh_subscriptions_v1"
        private const val KEY_ITEMS = "items"

        fun newId(): String = "sub-" + UUID.randomUUID().toString()
    }
}
