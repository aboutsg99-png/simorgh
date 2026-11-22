package com.uacspoofer.mobile.vpn

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One calendar day of real tunnel traffic, counted from the same deltas as the monthly total. */
data class DailyTraffic(val epochDay: Long, val uploadBytes: Long, val downloadBytes: Long) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
}

/**
 * Keeps the last [KEEP_DAYS] days of usage. Writes are throttled so the hot traffic path
 * never touches disk more than once every [FLUSH_MS].
 */
class DailyTrafficStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val days = LinkedHashMap<Long, LongArray>()
    private val mutable = MutableStateFlow<List<DailyTraffic>>(emptyList())
    val usage: StateFlow<List<DailyTraffic>> = mutable.asStateFlow()
    private var lastPersistMs = 0L
    private var lastPublishMs = 0L

    init {
        prefs.getString(KEY, null).orEmpty().split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size == 3) {
                val day = parts[0].toLongOrNull() ?: return@forEach
                val up = parts[1].toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                val down = parts[2].toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                days[day] = longArrayOf(up, down)
            }
        }
        trim(today())
        publish()
    }

    @Synchronized
    fun add(uploadDelta: Long, downloadDelta: Long, nowMs: Long = System.currentTimeMillis()) {
        if (uploadDelta <= 0L && downloadDelta <= 0L) return
        val day = dayOf(nowMs)
        val slot = days.getOrPut(day) { longArrayOf(0L, 0L) }
        slot[0] += uploadDelta.coerceAtLeast(0L)
        slot[1] += downloadDelta.coerceAtLeast(0L)
        trim(day)
        if (nowMs - lastPublishMs >= PUBLISH_MS) {
            publish()
            lastPublishMs = nowMs
        }
        if (nowMs - lastPersistMs >= FLUSH_MS) {
            persist()
            lastPersistMs = nowMs
        }
    }

    @Synchronized
    fun flush() {
        publish()
        persist()
    }

    /** The last [count] days ending today, oldest first; days with no traffic are zero. */
    fun lastDays(list: List<DailyTraffic>, count: Int = 7): List<DailyTraffic> {
        val byDay = list.associateBy { it.epochDay }
        val end = today()
        return (count - 1 downTo 0).map { back ->
            val d = end - back
            byDay[d] ?: DailyTraffic(d, 0L, 0L)
        }
    }

    private fun trim(today: Long) {
        days.keys.filter { it <= today - KEEP_DAYS || it > today + 1 }.forEach(days::remove)
    }

    private fun publish() {
        mutable.value = days.entries.sortedBy { it.key }
            .map { DailyTraffic(it.key, it.value[0], it.value[1]) }
    }

    private fun persist() {
        val encoded = days.entries.joinToString(";") { "${it.key},${it.value[0]},${it.value[1]}" }
        prefs.edit().putString(KEY, encoded).apply()
    }

    companion object {
        private const val PREFS = "daily_traffic_v1"
        private const val KEY = "days"
        private const val KEEP_DAYS = 31L
        private const val FLUSH_MS = 60_000L
        private const val PUBLISH_MS = 2_000L

        fun dayOf(ms: Long): Long =
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

        fun today(): Long = LocalDate.now().toEpochDay()

        @Volatile private var instance: DailyTrafficStore? = null

        fun get(context: Context): DailyTrafficStore = instance ?: synchronized(this) {
            instance ?: DailyTrafficStore(context.applicationContext).also { instance = it }
        }
    }
}
