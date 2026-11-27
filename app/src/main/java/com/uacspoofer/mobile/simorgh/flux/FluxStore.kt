package com.uacspoofer.mobile.simorgh.flux

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** What the user decides for Flux: three things, everything else Flux decides. */
@Immutable
data class FluxPrefs(
    /** Flux picks the config / tunnel inside each engine Auto tries. */
    val enabled: Boolean = true,
    /** ISO country code, or null for «هر کشوری». */
    val country: String? = null,
    val ipMode: IpMode = IpMode.BOTH,
    /** Routes of one engine tried for real before Auto moves on to the next engine. */
    val failover: Int = 2,
    /** Routes measured in parallel before the first connect. */
    val raceSize: Int = 4,
)

/**
 * Flux preferences (SharedPreferences) and its memory (`noBackupFilesDir/flux/state.json`,
 * device-only: it is about the networks this phone used).
 */
class FluxStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val file = File(File(app.noBackupFilesDir, "flux").apply { mkdirs() }, "state.json")
    private val mutablePrefs = MutableStateFlow(readPrefs())
    val preferences: StateFlow<FluxPrefs> = mutablePrefs.asStateFlow()
    @Volatile private var cached: FluxState? = null

    fun prefs(): FluxPrefs = mutablePrefs.value

    @Synchronized
    fun updatePrefs(transform: (FluxPrefs) -> FluxPrefs) {
        val next = transform(mutablePrefs.value).let {
            it.copy(
                country = it.country?.uppercase()?.takeIf { c -> c.length == 2 },
                failover = it.failover.coerceIn(1, 5),
                raceSize = it.raceSize.coerceIn(0, 8),
            )
        }
        mutablePrefs.value = next
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_COUNTRY, next.country)
            .putString(KEY_MODE, next.ipMode.code)
            .putInt(KEY_FAILOVER, next.failover)
            .putInt(KEY_RACE, next.raceSize)
            .apply()
    }

    @Synchronized
    fun state(): FluxState = cached ?: FluxState.fromJson(runCatching { file.readText() }.getOrNull()).also { cached = it }

    @Synchronized
    fun update(transform: (FluxState) -> FluxState) {
        val next = FluxMemory.prune(transform(state()), System.currentTimeMillis())
        cached = next
        runCatching {
            val tmp = File(file.parentFile, "state.json.tmp")
            tmp.writeText(next.toJson().toString())
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
    }

    @Synchronized
    fun forgetAll() {
        cached = FluxState()
        runCatching { file.delete() }
    }

    private fun readPrefs() = FluxPrefs(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        country = prefs.getString(KEY_COUNTRY, null),
        ipMode = IpMode.of(prefs.getString(KEY_MODE, null)),
        failover = prefs.getInt(KEY_FAILOVER, 2),
        raceSize = prefs.getInt(KEY_RACE, 4),
    )

    companion object {
        private const val PREFS = "simorgh_flux_v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_COUNTRY = "country"
        private const val KEY_MODE = "ip_mode"
        private const val KEY_FAILOVER = "failover"
        private const val KEY_RACE = "race"

        @Volatile private var instance: FluxStore? = null

        fun get(context: Context): FluxStore = instance ?: synchronized(this) {
            instance ?: FluxStore(context).also { instance = it }
        }
    }
}
