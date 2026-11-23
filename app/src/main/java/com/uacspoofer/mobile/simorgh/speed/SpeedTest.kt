package com.uacspoofer.mobile.simorgh.speed

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.vpn.LivePing
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

enum class SpeedProvider(val titleFa: String) { OOKLA("بین‌الملل (Ookla)"), SHATEL("داخلی (شاتل)") }

enum class SpeedPhase { IDLE, FINDING, PING, DOWNLOAD, UPLOAD, DONE, FAILED }

@Immutable
data class SpeedServer(
    val provider: SpeedProvider,
    val id: String,
    val name: String,
    val sponsor: String,
    val country: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val pingUrl: String,
    val latencyMs: Long? = null,
) {
    val label: String get() = listOf(sponsor, name).filter { it.isNotBlank() }.joinToString(" · ")
}

@Immutable
data class SpeedLive(
    val phase: SpeedPhase = SpeedPhase.IDLE,
    val provider: SpeedProvider = SpeedProvider.OOKLA,
    val server: SpeedServer? = null,
    val viaTunnel: Boolean = false,
    val engine: EngineMode? = null,
    /** Live value for the gauge, Mbps. */
    val liveMbps: Double = 0.0,
    /** 0..1 progress of the current phase. */
    val progress: Float = 0f,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val error: String = "",
)

@Immutable
data class SpeedResult(
    val atMs: Long,
    val provider: SpeedProvider,
    val server: String,
    val downMbps: Double,
    val upMbps: Double,
    val pingMs: Double,
    val jitterMs: Double,
    val viaTunnel: Boolean,
    val engineId: String,
)

/**
 * Real throughput test. Ookla: public server list, nearest by measured latency, the
 * server's /hello, /download and /upload endpoints. Shatel: its LibreSpeed backend
 * (garbage.php / empty.php). Through the tunnel's local SOCKS when connected, the plain
 * network otherwise. Each phase runs several parallel streams for a fixed time; the
 * first [WARMUP_MS] (TCP slow start, buffers filling) is discarded and the result is
 * bytes over the remaining window.
 */
object SpeedTestRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(SpeedLive())
    val live: StateFlow<SpeedLive> = mutable.asStateFlow()
    private var job: Job? = null

    private const val WARMUP_MS = 2_000L
    private const val DOWNLOAD_MS = 10_000L
    private const val UPLOAD_MS = 9_000L
    private const val SAMPLE_MS = 100L
    private const val SHATEL_BASE = "https://speed-interior.shatel.ir/"

    fun running(): Boolean = job?.isActive == true

    fun reset(provider: SpeedProvider) {
        if (running()) return
        mutable.value = SpeedLive(provider = provider)
    }

    fun cancel() {
        job?.cancel()
        job = null
        val current = mutable.value
        if (current.phase != SpeedPhase.DONE) mutable.value = SpeedLive(provider = current.provider)
    }

    fun start(context: Context, provider: SpeedProvider, onDone: (SpeedResult) -> Unit) {
        if (running()) return
        val app = context.applicationContext
        job = scope.launch {
            val tunnel = ConnectionStateStore.state.value == ConnectionState.CONNECTED
            val engine = if (tunnel) LivePing.routeEngine(app) else null
            val proxy = if (tunnel) {
                val (host, port) = LivePing.socksEndpoint(app)
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            } else {
                Proxy.NO_PROXY
            }
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(if (engine?.isTor == true) 20L else 10L, TimeUnit.SECONDS)
                .readTimeout(15L, TimeUnit.SECONDS)
                .writeTimeout(15L, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .build()
            var state = SpeedLive(SpeedPhase.FINDING, provider, viaTunnel = tunnel, engine = engine)
            mutable.value = state
            try {
                val server = when (provider) {
                    SpeedProvider.OOKLA -> findOokla(client)
                    SpeedProvider.SHATEL -> findShatel(client)
                } ?: throw IOException(
                    if (provider == SpeedProvider.SHATEL) {
                        "سرور شاتل در دسترس نیست" + if (tunnel) " (از داخل تونل معمولاً باز نمی‌شود؛ بدون اتصال امتحان کنید)" else ""
                    } else {
                        "سرور Ookla پیدا نشد"
                    },
                )
                state = state.copy(phase = SpeedPhase.PING, server = server)
                mutable.value = state
                val (ping, jitter) = measurePing(client, server.pingUrl)
                state = state.copy(pingMs = ping, jitterMs = jitter, phase = SpeedPhase.DOWNLOAD, progress = 0f)
                mutable.value = state
                val streamsDown = if (provider == SpeedProvider.OOKLA) 6 else 4
                val down = transfer(client, server, upload = false, streams = streamsDown, durationMs = DOWNLOAD_MS) { mbps, p ->
                    mutable.value = mutable.value.copy(liveMbps = mbps, progress = p)
                }
                state = mutable.value.copy(downMbps = down, liveMbps = 0.0, phase = SpeedPhase.UPLOAD, progress = 0f)
                mutable.value = state
                val up = transfer(client, server, upload = true, streams = 4, durationMs = UPLOAD_MS) { mbps, p ->
                    mutable.value = mutable.value.copy(liveMbps = mbps, progress = p)
                }
                state = mutable.value.copy(upMbps = up, liveMbps = 0.0, phase = SpeedPhase.DONE, progress = 1f)
                mutable.value = state
                onDone(
                    SpeedResult(
                        atMs = System.currentTimeMillis(), provider = provider, server = server.label,
                        downMbps = down, upMbps = up, pingMs = ping, jitterMs = jitter,
                        viaTunnel = tunnel, engineId = engine?.id.orEmpty(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutable.value = mutable.value.copy(
                    phase = SpeedPhase.FAILED, liveMbps = 0.0,
                    error = error.message?.takeIf { it.any { ch -> ch in '\u0600'..'\u06FF' } } ?: "اتصال به سرور تست ناموفق بود",
                )
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
    }

    // ---- server discovery ----

    private suspend fun findOokla(client: OkHttpClient): SpeedServer? = coroutineScope {
        val body = get(client, "https://www.speedtest.net/api/js/servers?engine=js&https_functional=true&limit=10")
            ?: return@coroutineScope null
        val list = JSONArray(body)
        val servers = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim()
            if (host.isEmpty()) return@mapNotNull null
            val base = "https://$host"
            SpeedServer(
                provider = SpeedProvider.OOKLA,
                id = o.optString("id"),
                name = o.optString("name"),
                sponsor = o.optString("sponsor"),
                country = o.optString("cc"),
                downloadUrl = "$base/download?nocache=%s&size=25000000",
                uploadUrl = "$base/upload?nocache=%s",
                pingUrl = "$base/hello?nocache=%s",
            )
        }
        val gate = Semaphore(5)
        servers.map { s ->
            async {
                gate.withPermit { s.copy(latencyMs = withTimeoutOrNull(6_000L) { probeLatency(client, s.pingUrl) }) }
            }
        }.awaitAll().filter { it.latencyMs != null }.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
    }

    private suspend fun findShatel(client: OkHttpClient): SpeedServer? {
        for (prefix in listOf("backend/", "")) {
            val ping = "${SHATEL_BASE}${prefix}empty.php?r=%s"
            val latency = withTimeoutOrNull(7_000L) { probeLatency(client, ping) } ?: continue
            return SpeedServer(
                provider = SpeedProvider.SHATEL, id = "shatel", name = "تهران", sponsor = "شاتل", country = "IR",
                downloadUrl = "${SHATEL_BASE}${prefix}garbage.php?r=%s&ckSize=100",
                uploadUrl = "${SHATEL_BASE}${prefix}empty.php?r=%s",
                pingUrl = ping,
                latencyMs = latency,
            )
        }
        return null
    }

    // ---- measurements ----

    private fun url(template: String): String = template.replace("%s", UUID.randomUUID().toString().take(12))

    private suspend fun get(client: OkHttpClient, url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull()
    }

    /** Best of three real HTTP round trips on a warm connection, ms. Null if unreachable. */
    private suspend fun probeLatency(client: OkHttpClient, template: String): Long? = withContext(Dispatchers.IO) {
        var best: Long? = null
        repeat(3) { i ->
            val t = System.nanoTime()
            val ok = runCatching {
                client.newCall(Request.Builder().url(url(template)).build()).execute().use { r ->
                    r.body?.bytes()
                    r.code in 200..299
                }
            }.getOrDefault(false)
            if (!ok) return@withContext if (i == 0) null else best
            val ms = (System.nanoTime() - t) / 1_000_000L
            if (i > 0) best = minOf(best ?: Long.MAX_VALUE, ms) // first trip includes connect + TLS
        }
        best
    }

    /** Ping = median of 10 warm round trips, jitter = mean absolute difference between neighbours. */
    private suspend fun measurePing(client: OkHttpClient, template: String): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val samples = ArrayList<Double>()
        repeat(11) { i ->
            ensureActive()
            val t = System.nanoTime()
            val ok = runCatching {
                client.newCall(Request.Builder().url(url(template)).build()).execute().use { r ->
                    r.body?.bytes(); r.code in 200..299
                }
            }.getOrDefault(false)
            val ms = (System.nanoTime() - t) / 1e6
            if (ok && i > 0) samples += ms
        }
        if (samples.isEmpty()) throw IOException("پینگ سرور تست جواب نداد")
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        val jitter = if (samples.size < 2) 0.0 else samples.zipWithNext { a, b -> abs(a - b) }.average()
        median to jitter
    }

    private suspend fun transfer(
        client: OkHttpClient,
        server: SpeedServer,
        upload: Boolean,
        streams: Int,
        durationMs: Long,
        onLive: (Double, Float) -> Unit,
    ): Double = coroutineScope {
        val bytes = AtomicLong(0L)
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        val calls = java.util.Collections.synchronizedList(ArrayList<Call>())
        val workers = (0 until streams).map {
            launch(Dispatchers.IO) {
                while (isActive && System.nanoTime() < deadline) {
                    val request = if (upload) {
                        Request.Builder().url(url(server.uploadUrl)).post(CountingBody(bytes, deadline)).build()
                    } else {
                        Request.Builder().url(url(server.downloadUrl)).header("Cache-Control", "no-cache").build()
                    }
                    val call = client.newCall(request)
                    calls += call
                    try {
                        call.execute().use { response ->
                            if (!upload) {
                                val source = response.body?.source() ?: return@use
                                val buffer = okio.Buffer()
                                while (System.nanoTime() < deadline) {
                                    val n = source.read(buffer, 64 * 1024L)
                                    if (n < 0) break
                                    bytes.addAndGet(n)
                                    buffer.clear()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        if (System.nanoTime() < deadline) delay(150L)
                    } finally {
                        calls -= call
                    }
                }
            }
        }
        // Sampler: live gauge from the last second, result from the post-warm-up window.
        val times = ArrayList<Long>()
        val totals = ArrayList<Long>()
        var warmBytes = -1L
        var warmAt = 0L
        while (System.nanoTime() < deadline) {
            delay(SAMPLE_MS)
            val now = System.nanoTime()
            val total = bytes.get()
            times += now; totals += total
            val elapsed = (now - start) / 1_000_000L
            if (warmBytes < 0 && elapsed >= WARMUP_MS) { warmBytes = total; warmAt = now }
            // live: bytes over the last ~1s
            var j = times.size - 1
            while (j > 0 && now - times[j] < 1_000_000_000L) j--
            val dt = (now - times[j]) / 1e9
            val live = if (dt > 0.05) (total - totals[j]) * 8 / dt / 1e6 else 0.0
            onLive(live, (elapsed.toFloat() / durationMs).coerceIn(0f, 1f))
        }
        val end = System.nanoTime()
        val endBytes = bytes.get()
        synchronized(calls) { calls.forEach { runCatching { it.cancel() } } }
        workers.forEach { it.cancel() }
        if (warmBytes < 0 || end <= warmAt) throw IOException("زمان تست کافی نبود")
        val mbps = (endBytes - warmBytes) * 8 / ((end - warmAt) / 1e9) / 1e6
        if (endBytes <= 0L) throw IOException(if (upload) "آپلود انجام نشد" else "دانلود انجام نشد")
        mbps.coerceAtLeast(0.0)
    }

    /** Upload body: random-ish bytes until the phase deadline, counted as they are written. */
    private class CountingBody(private val counter: AtomicLong, private val deadline: Long) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = UPLOAD_CHUNK
        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            while (written < UPLOAD_CHUNK) {
                if (System.nanoTime() >= deadline) throw IOException("phase over")
                val n = minOf(BLOCK.size.toLong(), UPLOAD_CHUNK - written).toInt()
                sink.write(BLOCK, 0, n)
                sink.flush()
                written += n
                counter.addAndGet(n.toLong())
            }
        }

        companion object {
            const val UPLOAD_CHUNK = 8L * 1024 * 1024
            val BLOCK: ByteArray = ByteArray(64 * 1024).also { java.util.Random(7).nextBytes(it) }
        }
    }
}

/** Saved results, newest first, 40 max. */
class SpeedHistoryStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simorgh_speed_history_v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val results: StateFlow<List<SpeedResult>> = mutable.asStateFlow()

    @Synchronized
    fun add(result: SpeedResult) {
        val next = (listOf(result) + mutable.value).take(40)
        mutable.value = next
        write(next)
    }

    @Synchronized
    fun clear() {
        mutable.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun read(): List<SpeedResult> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SpeedResult(
                atMs = o.optLong("at"),
                provider = runCatching { SpeedProvider.valueOf(o.optString("p")) }.getOrDefault(SpeedProvider.OOKLA),
                server = o.optString("s"),
                downMbps = o.optDouble("d", 0.0),
                upMbps = o.optDouble("u", 0.0),
                pingMs = o.optDouble("pi", 0.0),
                jitterMs = o.optDouble("j", 0.0),
                viaTunnel = o.optBoolean("t"),
                engineId = o.optString("e"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<SpeedResult>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().put("at", r.atMs).put("p", r.provider.name).put("s", r.server)
                    .put("d", r.downMbps).put("u", r.upMbps).put("pi", r.pingMs).put("j", r.jitterMs)
                    .put("t", r.viaTunnel).put("e", r.engineId),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "results"
        @Volatile private var instance: SpeedHistoryStore? = null
        fun get(context: Context): SpeedHistoryStore = instance ?: synchronized(this) {
            instance ?: SpeedHistoryStore(context.applicationContext).also { instance = it }
        }
    }
}
