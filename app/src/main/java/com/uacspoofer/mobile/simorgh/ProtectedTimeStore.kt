package com.uacspoofer.mobile.simorgh

import android.content.Context
import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class ProtectedDays(
    /** epochDay -> protected seconds, finished sessions only. */
    val seconds: Map<Long, Long> = emptyMap(),
    /** Start of the running session, or 0. */
    val sessionStartMs: Long = 0L,
) {
    /** Seconds per day for the last [count] days, oldest first, including the running session. */
    fun lastDays(nowMs: Long, count: Int = 7): List<Pair<Long, Long>> {
        val today = dayOf(nowMs)
        val live = HashMap<Long, Long>()
        if (sessionStartMs in 1 until nowMs) splitInto(sessionStartMs, nowMs, live)
        return (count - 1 downTo 0).map { back ->
            val day = today - back
            day to ((seconds[day] ?: 0L) + (live[day] ?: 0L))
        }
    }

    companion object {
        fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

        fun splitInto(startMs: Long, endMs: Long, into: MutableMap<Long, Long>) {
            var cursor = startMs
            val zone = ZoneId.systemDefault()
            while (cursor < endMs) {
                val day = dayOf(cursor)
                val nextMidnight = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(endMs, nextMidnight)
                into[day] = (into[day] ?: 0L) + (end - cursor) / 1000L
                cursor = end
            }
        }
    }
}

/**
 * Real protected (connected) time per day. Event driven: one write when a session ends,
 * nothing runs while connected. Keeps 30 days.
 */
class ProtectedTimeStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simorgh_protected_time_v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val days: StateFlow<ProtectedDays> = mutable.asStateFlow()

    @Synchronized
    fun sessionStarted(nowMs: Long = System.currentTimeMillis()) {
        if (mutable.value.sessionStartMs > 0L) return
        mutable.value = mutable.value.copy(sessionStartMs = nowMs)
        prefs.edit().putLong(KEY_START, nowMs).apply()
    }

    @Synchronized
    fun sessionEnded(nowMs: Long = System.currentTimeMillis()) {
        val start = mutable.value.sessionStartMs
        if (start <= 0L) return
        val map = HashMap(mutable.value.seconds)
        if (nowMs > start) ProtectedDays.splitInto(start, nowMs, map)
        val today = ProtectedDays.dayOf(nowMs)
        map.keys.removeAll { it < today - KEEP_DAYS }
        mutable.value = ProtectedDays(map, 0L)
        prefs.edit()
            .putLong(KEY_START, 0L)
            .putString(KEY_DAYS, map.entries.joinToString(";") { "${it.key},${it.value}" })
            .apply()
    }

    private fun read(): ProtectedDays = runCatching {
        val map = HashMap<Long, Long>()
        prefs.getString(KEY_DAYS, "").orEmpty().split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size == 2) {
                val d = parts[0].toLongOrNull()
                val s = parts[1].toLongOrNull()
                if (d != null && s != null && s >= 0) map[d] = s
            }
        }
        // A session left open by a killed process is closed at its start (counts nothing).
        ProtectedDays(map, 0L)
    }.getOrDefault(ProtectedDays())

    companion object {
        private const val KEY_DAYS = "days"
        private const val KEY_START = "start"
        private const val KEEP_DAYS = 30

        @Volatile private var instance: ProtectedTimeStore? = null

        fun get(context: Context): ProtectedTimeStore = instance ?: synchronized(this) {
            instance ?: ProtectedTimeStore(context.applicationContext).also { instance = it }
        }
    }
}
