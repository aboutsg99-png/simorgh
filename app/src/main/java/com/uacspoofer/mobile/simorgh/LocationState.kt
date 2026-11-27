package com.uacspoofer.mobile.simorgh

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.direct.DirectEngineStore
import com.uacspoofer.mobile.engine.direct.DirectStatusStore
import com.uacspoofer.mobile.engine.foxy.FoxyEngineStore
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.simorgh.auto.AutoEngineStore
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the user chose: engine plus country (or config). "" country = automatic/fastest. */
@Immutable
data class Selection(
    val engine: EngineMode = EngineMode.XRAY_CF,
    val countryCode: String = "",
    val profileId: String = "",
    val profileName: String = "",
    val autoEngine: Boolean = false,
    /** Tir without its WireGuard exit has no country at all. */
    val countryless: Boolean = false,
)

/**
 * One source of truth for "where am I": home card, map, notification and widget read this.
 * [displayCode] is the real exit country once the IP check verified it, else the country
 * the running engine reports, else the user's selection (UI labels that as a selection).
 */
@Immutable
data class CurrentLocation(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val selection: Selection = Selection(),
    val runningEngine: EngineMode? = null,
    val exit: ExitCheck = ExitCheck(),
    val engineReportedCode: String = "",
) {
    val live: Boolean get() = connection == ConnectionState.CONNECTED || connection == ConnectionState.CONNECTING
    val engine: EngineMode get() = if (live && runningEngine != null) runningEngine else selection.engine
    val exitVerified: Boolean get() = connection == ConnectionState.CONNECTED && exit.known && exit.viaTunnel
    val connectedCode: String
        get() = when {
            exitVerified -> exit.countryCode
            connection == ConnectionState.CONNECTED -> engineReportedCode
            else -> ""
        }
    val displayCode: String get() = connectedCode.ifEmpty { selection.countryCode }
    /** True when what is shown is only the user's choice, not a measured exit. */
    val showingSelection: Boolean get() = connectedCode.isEmpty()
}

object LocationState {
    private val revision = MutableStateFlow(0)
    @Volatile private var started = false
    private var profileListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val mutableSelection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = mutableSelection
    private val mutableCurrent = MutableStateFlow(CurrentLocation())
    val current: StateFlow<CurrentLocation> = mutableCurrent

    fun bumpProfiles() { revision.value = revision.value + 1 }

    /** Screenshot tests only: show a fixed location without starting the flows. */
    @androidx.annotation.VisibleForTesting
    internal fun setForPreview(location: CurrentLocation) { mutableSelection.value = location.selection; mutableCurrent.value = location }

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        val app = context.applicationContext
        val engines = EngineModeStore.get(app)
        val tor = TorEngineStore.get(app)
        val pow = PowEngineStore.get(app)
        val direct = DirectEngineStore.get(app)
        val foxy = FoxyEngineStore.get(app)
        val auto = AutoEngineStore.get(app)
        val profiles = ProfileStore(app)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> bumpProfiles() }
        profileListener = listener
        runCatching {
            app.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        }
        val engineSide = combine(engines.mode, tor.settings, pow.settings, direct.settings, foxy.settings) { mode, t, p, d, f ->
            arrayOf<Any>(mode, t.exitCountryCode, p.exitCountryCode, d.wireGuardOverDirect, d.exitCountryCode, f.countryCode)
        }
        val selectionFlow = combine(engineSide, revision, auto.settings.map { it.enabled }.distinctUntilChanged()) { e, _, autoOn ->
            val mode = e[0] as EngineMode
            val profile = if (mode.isXray) runCatching { profiles.selectedProfile() }.getOrNull() else null
            val code = when {
                mode.isTor -> e[1] as String
                mode.isPow -> e[2] as String
                mode.isDirect -> if (e[3] as Boolean) e[4] as String else ""
                mode.isFoxy -> e[5] as String
                else -> profile?.country?.countryCode.orEmpty()
            }.trim().uppercase(Locale.ROOT).takeIf { it.length == 2 }.orEmpty()
            Selection(
                engine = mode,
                countryCode = code,
                profileId = profile?.id.orEmpty(),
                profileName = profile?.name.orEmpty(),
                autoEngine = autoOn,
                countryless = mode.isDirect && !(e[3] as Boolean),
            )
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, Selection())
        val reported = combine(FoxyStatusStore.status, PowStatusStore.status, DirectStatusStore.status) { f, p, d ->
            Triple(f.countryCode, p.exitRegion, d.exitCountryCode)
        }
        scope.launchCollect(selectionFlow) { mutableSelection.value = it }
        val currentFlow = combine(
            selectionFlow,
            ConnectionStateStore.state,
            ActiveEngineStore.engine,
            ExitIpChecker.state,
            reported,
        ) { sel, conn, running, exit, rep ->
            val engine = if ((conn == ConnectionState.CONNECTED || conn == ConnectionState.CONNECTING) && running != null) running else sel.engine
            val engineCode = when {
                engine.isFoxy -> rep.first
                engine.isPow -> rep.second
                engine.isDirect -> rep.third
                else -> ""
            }.trim().uppercase(Locale.ROOT).takeIf { it.length == 2 }.orEmpty()
            CurrentLocation(conn, sel, running, exit, engineCode)
        }.distinctUntilChanged()
        scope.launchCollect(currentFlow) { mutableCurrent.value = it }
    }

    private const val PROFILE_PREFS = "uac_proxy_profiles_v2"
}

private fun <T> CoroutineScope.launchCollect(flow: kotlinx.coroutines.flow.Flow<T>, block: (T) -> Unit) {
    launch { flow.collect { block(it) } }
}

/** Country name in Persian (or English), from the platform's CLDR data. */
fun countryName(code: String?, persian: Boolean = true): String {
    if (code.isNullOrEmpty()) return ""
    val cache = if (persian) countryNamesFa else countryNamesEn
    cache[code]?.let { return it }
    val iso = code.trim().uppercase(Locale.ROOT)
    val name = if (iso.length != 2) {
        ""
    } else {
        // ICU display-name lookups are slow enough to show up in list scrolling; cache them.
        Locale("", iso).getDisplayCountry(if (persian) PERSIAN else Locale.ENGLISH).trim()
            .let { if (it.isEmpty() || it.equals(iso, ignoreCase = true)) iso else it }
    }
    cache[code] = name
    return name
}

private val PERSIAN = Locale("fa")
private val countryNamesFa = java.util.concurrent.ConcurrentHashMap<String, String>()
private val countryNamesEn = java.util.concurrent.ConcurrentHashMap<String, String>()
