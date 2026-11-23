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
)

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
        )
    }

    companion object {
        private const val PREFS = "simorgh_subscriptions_v1"
        private const val KEY_ITEMS = "items"

        fun newId(): String = "sub-" + UUID.randomUUID().toString()
    }
}
