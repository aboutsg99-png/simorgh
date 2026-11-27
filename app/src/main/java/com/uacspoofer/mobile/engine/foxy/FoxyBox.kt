package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import com.uacspoofer.mobile.engine.tor.TorExec
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class FoxyBinaryMissingException : IllegalStateException("libfoxybox.so is not bundled")
class FoxyUnusableException(message: String) : IllegalStateException(message)

/**
 * Runs the Foxy sing-box fork (Vauth/sing-box v1.0.0-masque, GPL-3.0) as a separate
 * executable, not as an in-process gomobile AAR. Blackdey already ships two gomobile
 * runtimes (libv2ray and Psiphon, patched to load different libgojni copies); a third
 * gomobile AAR would add another `go.Seq` class set and Go runtime in the same process.
 * A child process avoids every JNI/class/symbol clash and a sing-box crash cannot take
 * the app down: the coordinator sees the process exit and recovers or falls back.
 *
 * The child runs with Blackdey's UID, and Blackdey excludes its own package from the VPN,
 * so its sockets to Mozilla's edge never loop through the tunnel.
 */
internal class FoxyBox(context: Context) {
    private val appContext = context.applicationContext
    private val workDir = File(appContext.filesDir, "foxy").apply { mkdirs() }
    private val configFile = File(workDir, "config.json")
    private val tail = ConcurrentLinkedDeque<String>()
    @Volatile private var process: Process? = null
    @Volatile private var pumpThread: Thread? = null
    @Volatile var port: Int = 0
        private set

    fun binary(): File? = File(appContext.applicationInfo.nativeLibraryDir.orEmpty(), BINARY)
        .takeIf { it.isFile }

    fun isRunning(): Boolean = runCatching { process?.isAlive == true }.getOrDefault(false)

    fun lastLines(): String = tail.joinToString("\n")

    /** Blocking; call from Dispatchers.IO. Throws when the core does not come up in time. */
    @Synchronized
    fun start(configJson: String, listenPort: Int, readyTimeoutMs: Long = 8_000L) {
        stopLocked()
        val bin = binary() ?: throw FoxyBinaryMissingException()
        writePrivate(configFile, configJson)
        tail.clear()
        val args = listOf("run", "-c", configFile.absolutePath, "-D", workDir.absolutePath, "--disable-color")
        val attempts = listOfNotNull(
            TorExec.argv(bin, args),
            TorExec.detectLinker()?.let { TorExec.argv(bin, args, linker = it) },
        ).distinct()
        var started: Process? = null
        var lastError: Throwable? = null
        for (argv in attempts) {
            val result = runCatching {
                ProcessBuilder(argv).directory(workDir).redirectErrorStream(true).also {
                    it.environment()["HOME"] = workDir.absolutePath
                    it.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
                }.start()
            }
            if (result.isSuccess) {
                started = result.getOrThrow()
                break
            }
            lastError = result.exceptionOrNull()
        }
        val proc = started ?: throw IllegalStateException("Could not start Foxy core", lastError)
        process = proc
        port = listenPort
        pumpThread = Thread({
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        tail.addLast(sanitize(line).take(300))
                        while (tail.size > 40) tail.pollFirst()
                    }
                }
            }
        }, "foxy-core-log").apply { isDaemon = true; start() }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs)
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) {
                val reason = lastLines().lineSequence().lastOrNull { it.contains("FATAL") || it.contains("ERROR") }
                    ?: lastLines().takeLast(300)
                stopLocked()
                throw IllegalStateException("Foxy core exited: $reason")
            }
            if (portOpen(listenPort)) {
                AppLogger.i(TAG, "core listening on 127.0.0.1:$listenPort")
                return
            }
            Thread.sleep(100)
        }
        stopLocked()
        throw IllegalStateException("Foxy core did not open 127.0.0.1:$listenPort in time")
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val proc = process ?: return
        process = null
        port = 0
        runCatching { proc.destroy() }
        val exited = runCatching { proc.waitFor(1_500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) runCatching { proc.destroyForcibly() }
        runCatching { proc.inputStream.close() }
        runCatching { proc.outputStream.close() }
        pumpThread?.interrupt()
        pumpThread = null
        // The config holds a short-lived proxy pass; do not leave it on disk.
        runCatching { configFile.delete() }
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress("127.0.0.1", port), 150); true }
    }.getOrDefault(false)

    private fun writePrivate(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(false, false)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun sanitize(line: String): String =
        line.replace(Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer ***")

    companion object {
        private const val TAG = "FoxyBox"
        const val BINARY = "libfoxybox.so"
    }
}

/** sing-box JSON for one attempt. Only fields that exist in Vauth/sing-box v1.0.0-masque. */
internal object FoxyBoxConfig {
    /** How long sing-box waits for the edge's ACK of each ClientHello piece before sending on. */
    const val FRAGMENT_FALLBACK_DELAY = "500ms"

    fun build(
        candidate: ProxyCandidate,
        edgeAddress: String?,
        version: Int,
        proxyPass: String,
        listenPort: Int,
        paused: Boolean,
        directDomains: List<String>,
        iranRuleSetPath: String? = null,
        antiFilter: AntiFilterMethod = AntiFilterMethod.PLAIN,
    ): String {
        require(version in 1..3) { "HTTP version must be 1, 2 or 3" }
        require(proxyPass.isNotBlank() && proxyPass.none { it == '\r' || it == '\n' }) { "Invalid proxy pass" }
        val root = JSONObject()
        root.put("log", JSONObject().put("level", "warn").put("timestamp", false))
        root.put("dns", JSONObject()
            .put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local")))
            .put("final", "local"))
        root.put("inbounds", JSONArray().put(JSONObject()
            .put("type", "mixed")
            .put("tag", "mixed-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", listenPort)))

        val tls = JSONObject().put("enabled", true).put("server_name", candidate.host)
        if (version != 3) {
            // uTLS is TCP-only; HTTP/3 uses the QUIC stack's own TLS.
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "firefox"))
            // Both are applied by the fork's uTLS client on the TCP connection; QUIC ignores them.
            when (antiFilter) {
                AntiFilterMethod.FRAGMENT -> tls
                    .put("fragment", true)
                    .put("fragment_fallback_delay", FRAGMENT_FALLBACK_DELAY)
                AntiFilterMethod.RECORD_FRAGMENT -> tls.put("record_fragment", true)
                else -> Unit
            }
        }
        val proxy = JSONObject()
            .put("type", "http")
            .put("tag", "proxy-out")
            .put("server", edgeAddress ?: candidate.host)
            .put("server_port", candidate.port)
            .put("version", version)
            // Blackdey steps H3 -> H2 -> H1.1 itself after measuring each one,
            // so sing-box must not silently downgrade inside an attempt.
            .put("disable_version_fallback", true)
            // Static header: the CLI has no Libbox.setBearerToken; the pass is rotated by
            // restarting the core (see FoxyConnectionCoordinator.renewLoop).
            .put("dynamic_bearer_token", false)
            .put("headers", JSONObject().put("Proxy-Authorization", "Bearer $proxyPass"))
            .put("tls", tls)
        if (edgeAddress == null) proxy.put("domain_resolver", "local")
        if (version == 2) {
            proxy.put("max_concurrent_streams", 100)
            proxy.put("stream_receive_window", "16 MB")
            proxy.put("connection_receive_window", "32 MB")
            proxy.put("idle_timeout", "30s")
            proxy.put("keep_alive_period", "15s")
        }
        root.put("outbounds", JSONArray()
            .put(proxy)
            .put(JSONObject().put("type", "direct").put("tag", "direct-out")))

        val rules = JSONArray()
        rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct-out"))
        val domains = directDomains.map { it.trim().trimStart('.').lowercase() }
            .filter { FoxyHosts.isValidEdgeHost(it) }
            .distinct()
            .take(500)
        if (domains.isNotEmpty()) {
            rules.put(JSONObject().put("domain_suffix", JSONArray(domains)).put("outbound", "direct-out"))
        }
        val route = JSONObject()
        if (iranRuleSetPath != null) {
            // "Iranian sites direct": .ir, geosite category-ir and geoip:ir (FoxyIranRules).
            route.put("rule_set", JSONArray().put(JSONObject()
                .put("type", "local")
                .put("tag", "ir-direct")
                .put("format", "source")
                .put("path", iranRuleSetPath)))
            rules.put(JSONObject().put("rule_set", "ir-direct").put("outbound", "direct-out"))
        }
        root.put("route", route
            .put("rules", rules)
            .put("final", if (paused) "direct-out" else "proxy-out")
            .put("default_domain_resolver", "local"))
        return root.toString()
    }
}
