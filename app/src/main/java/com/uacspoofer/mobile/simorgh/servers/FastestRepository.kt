package com.uacspoofer.mobile.simorgh.servers

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.foxy.FoxyAccount
import com.uacspoofer.mobile.engine.foxy.FoxyAccountState
import com.uacspoofer.mobile.engine.foxy.FoxyPing
import com.uacspoofer.mobile.engine.foxy.ServerListClient
import com.uacspoofer.mobile.engine.foxy.VpnCountry
import com.uacspoofer.mobile.profiles.ProfileLatencyCache
import com.uacspoofer.mobile.profiles.ProfileLatencyTester
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.SubscriptionStore
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.vpn.ConnectionMetricsStore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RowSource { CONFIG, SUBSCRIPTION, RUBAH, LIVE }

/** One measurable way out: a config, a Mozilla country, or the live tunnel. */
@Immutable
data class FastRow(
    val key: String,
    val engine: EngineMode,
    val source: RowSource,
    val countryCode: String,
    val title: String,
    val tag: String,
    val profileId: String = "",
    val pingMs: Long? = null,
    val measuring: Boolean = false,
    val failed: Boolean = false,
    val measuredAtMs: Long = 0L,
)

@Immutable
data class FastestState(
    val rows: List<FastRow> = emptyList(),
    val running: Boolean = false,
    val rubahSignedIn: Boolean = false,
    val lastRunMs: Long = 0L,
) {
    /** Measured rows first, fastest first; then measuring; then failed. */
    val sorted: List<FastRow>
        get() = rows.sortedWith(
            compareBy<FastRow>({ if (it.pingMs != null) 0 else if (it.measuring) 1 else 2 }, { it.pingMs ?: Long.MAX_VALUE }, { it.title }),
        )
}

/**
 * The «پرسرعت‌ها» list: every source with a real per-server measurement, merged and
 * sorted by ping. Rows appear as measurements land. Parvaz configs use the existing
 * real delay test (temporary Xray + HTTP probe), one at a time; Rubah countries use
 * FoxyPing (TCP + TLS) only after the user signed in with their own Firefox account.
 * Sayeh/Shahpar/Tir cannot be measured per country before connecting, so they only
 * appear as the live tunnel row with its real tunnel ping.
 */
object FastestRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(FastestState())
    val state: StateFlow<FastestState> = mutable.asStateFlow()
    private var job: Job? = null
    private var foxyCountries: List<VpnCountry> = emptyList()

    fun foxyCountryList(): List<VpnCountry> = foxyCountries

    /** Lists rows with cached real measurements, without measuring anything. */
    fun loadCached(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val rows = baseRows(app, measuring = false)
            mutable.update { s -> s.copy(rows = mergeKeep(s.rows, rows), rubahSignedIn = rubahSignedIn(app)) }
        }
    }

    fun pingAll(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            mutable.update { it.copy(running = true, rubahSignedIn = rubahSignedIn(app)) }
            try {
                val rows = baseRows(app, measuring = true)
                mutable.update { s -> s.copy(rows = rows.map { r -> s.rows.firstOrNull { it.key == r.key }?.let { old -> r.copy(pingMs = old.pingMs) } ?: r }) }
                val parts = listOf(
                    launch { measureConfigs(app) },
                    launch { measureRubah(app) },
                )
                parts.joinAll()
                addLiveRow(app)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            } finally {
                mutable.update { s ->
                    s.copy(
                        running = false,
                        lastRunMs = System.currentTimeMillis(),
                        rows = s.rows.map { if (it.measuring) it.copy(measuring = false, failed = it.pingMs == null) else it },
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        mutable.update { s -> s.copy(running = false, rows = s.rows.map { it.copy(measuring = false) }) }
    }

    private fun mergeKeep(old: List<FastRow>, fresh: List<FastRow>): List<FastRow> =
        fresh.map { r -> old.firstOrNull { it.key == r.key }?.let { o -> if (o.pingMs != null) r.copy(pingMs = o.pingMs, measuredAtMs = o.measuredAtMs) else r } ?: r }

    private suspend fun rubahSignedIn(app: Context): Boolean {
        val account = FoxyAccount.get(app)
        val status = account.status.value.state
        if (status == FoxyAccountState.SIGNED_IN) return true
        if (status == FoxyAccountState.SIGNED_OUT) return false
        return account.hasStoredSession()
    }

    private suspend fun baseRows(app: Context, measuring: Boolean): List<FastRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<FastRow>()
        val store = ProfileStore(app)
        val library = runCatching { store.snapshot() }.getOrNull()
        val subs = runCatching { SubscriptionStore(app).all() }.getOrDefault(emptyList())
        val cache = runCatching {
            ProfileLatencyCache(app).snapshot(library?.customProfiles?.map { it.id }?.toHashSet() ?: emptySet())
        }.getOrDefault(emptyMap())
        library?.customProfiles?.forEach { p ->
            val sub = subs.firstOrNull { p.id in it.profileIds }
            val code = p.country.countryCode.orEmpty().uppercase(Locale.ROOT)
            out += FastRow(
                key = "cfg:${p.id}",
                engine = EngineMode.XRAY_CF,
                source = if (sub != null) RowSource.SUBSCRIPTION else RowSource.CONFIG,
                countryCode = code,
                title = p.name,
                tag = if (sub != null) "${sub.name.ifBlank { "اشتراک" }} (پرواز)" else "کانفیگ شخصی (پرواز)",
                profileId = p.id,
                pingMs = cache[p.id],
                measuring = measuring,
            )
        }
        if (rubahSignedIn(app)) {
            val client = ServerListClient(app)
            if (foxyCountries.isEmpty()) {
                foxyCountries = runCatching { client.loadCachedCountries() }.getOrDefault(emptyList())
            }
            if (foxyCountries.isEmpty() || (measuring && !client.hasFreshCache())) {
                runCatching { client.fetchCountries() }.getOrNull()?.let { foxyCountries = it }
            }
            foxyCountries.forEach { c ->
                out += FastRow(
                    key = "foxy:${c.code}",
                    engine = EngineMode.FOXY_FIREFOX,
                    source = RowSource.RUBAH,
                    countryCode = c.code.uppercase(Locale.ROOT).take(2),
                    title = c.code,
                    tag = "موزیلا (روباه)",
                    measuring = measuring,
                )
            }
        }
        out
    }

    private suspend fun measureConfigs(app: Context) {
        val tester = ProfileLatencyTester(app)
        val cache = ProfileLatencyCache(app)
        val library = runCatching { ProfileStore(app).snapshot() }.getOrNull() ?: return
        for (p in library.customProfiles) {
            val ms = try {
                tester.measure(p)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (ms != null) runCatching { cache.put(p.id, ms) }
            publish("cfg:${p.id}", ms)
        }
    }

    private suspend fun measureRubah(app: Context) {
        if (!rubahSignedIn(app) || foxyCountries.isEmpty()) return
        val countries = foxyCountries
        val targets = countries.associate { c ->
            c.code to ServerListClient.candidatesForCountry(countries, c.code).shuffled().take(2)
        }
        // Rank per country so rows land progressively instead of after the whole sweep.
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            targets.forEach { (code, candidates) ->
                launch {
                    gate.acquire()
                    try {
                        val best = if (candidates.isEmpty()) null else FoxyPing.rank(candidates, parallel = 2)
                            .firstOrNull { it.second.reachable }?.second?.tlsMs?.toLong()
                        publish("foxy:$code", best)
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }

    private fun addLiveRow(app: Context) {
        val location = LocationState.current.value
        if (ConnectionStateStore.state.value != ConnectionState.CONNECTED) return
        val engine = location.engine
        if (engine.isXray || engine.isFoxy) return // covered by their own rows
        val code = location.connectedCode
        val ms = ConnectionMetricsStore.metrics.value.latencyMs ?: return
        if (code.isEmpty()) return
        val name = when {
            engine.isTor -> "سایه"
            engine.isPow -> "شاهپر"
            else -> "تیر"
        }
        val row = FastRow(
            key = "live:${engine.id}", engine = engine, source = RowSource.LIVE, countryCode = code,
            title = code, tag = "$name · اتصال فعلی", pingMs = ms, measuredAtMs = System.currentTimeMillis(),
        )
        mutable.update { s -> s.copy(rows = s.rows.filterNot { it.key == row.key } + row) }
    }

    private fun publish(key: String, ms: Long?) {
        mutable.update { s ->
            s.copy(rows = s.rows.map {
                if (it.key == key) it.copy(pingMs = ms ?: it.pingMs.takeIf { false }, measuring = false, failed = ms == null, measuredAtMs = System.currentTimeMillis()) else it
            })
        }
    }
}
