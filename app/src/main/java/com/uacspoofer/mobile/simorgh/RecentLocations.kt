package com.uacspoofer.mobile.simorgh

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.engine.EngineMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A place the user actually connected to: engine + exit/selected country (+ config). */
@Immutable
data class RecentLocation(
    val engine: EngineMode,
    val countryCode: String,
    val selectedCode: String,
    val profileId: String,
    val profileName: String,
    val atMs: Long,
)

class RecentLocations private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simorgh_recent_v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val items: StateFlow<List<RecentLocation>> = mutable.asStateFlow()

    @Synchronized
    fun add(item: RecentLocation) {
        val next = (listOf(item) + mutable.value.filterNot {
            it.engine == item.engine && it.selectedCode == item.selectedCode && it.profileId == item.profileId
        }).take(6)
        mutable.value = next
        val arr = JSONArray()
        next.forEach {
            arr.put(
                JSONObject().put("e", it.engine.id).put("c", it.countryCode).put("s", it.selectedCode)
                    .put("p", it.profileId).put("n", it.profileName).put("t", it.atMs),
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    /** Exit country became known after the entry was written. */
    @Synchronized
    fun updateLatestCountry(code: String) {
        val first = mutable.value.firstOrNull() ?: return
        if (first.countryCode == code) return
        add(first.copy(countryCode = code))
    }

    private fun read(): List<RecentLocation> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val engine = EngineMode.entries.firstOrNull { it.id == o.optString("e") } ?: return@mapNotNull null
            RecentLocation(engine, o.optString("c"), o.optString("s"), o.optString("p"), o.optString("n"), o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: RecentLocations? = null
        fun get(context: Context): RecentLocations = instance ?: synchronized(this) {
            instance ?: RecentLocations(context.applicationContext).also { instance = it }
        }
    }
}
