package com.uacspoofer.mobile.engine.wireguard

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled `libxray.so` as a child process with a complete Xray JSON config (used by
 * the WireGuard engine and its probes). Like Parvaz's core it runs outside the VPN (Simorgh
 * excludes its own package), so its sockets never loop through the tunnel.
 *
 * The config holds a private key: it is written owner-only and deleted as soon as the core is
 * listening (Xray has read it by then).
 */
internal class XrayRawCore(context: Context, private val tag: String) {
    private val app = context.applicationContext
    private val dir = File(app.noBackupFilesDir, "xray-raw").apply { mkdirs() }
    private val tail = ConcurrentLinkedDeque<String>()
    @Volatile private var process: Process? = null

    fun isRunning(): Boolean = process?.let(::alive) == true

    fun lastLines(): String = tail.joinToString("\n")

    /** Blocking; call from Dispatchers.IO. */
    @Synchronized
    fun start(configJson: String, port: Int, readyTimeoutMs: Long = 6_000L) {
        stopLocked()
        val binary = File(app.applicationInfo.nativeLibraryDir.orEmpty(), "libxray.so")
        check(binary.isFile && binary.length() > 0) { "libxray.so is missing" }
        val config = File(dir, "$tag-$port.json")
        writePrivate(config, configJson)
        tail.clear()
        val proc = try {
            ProcessBuilder(binary.absolutePath, "run", "-config", config.absolutePath)
                .directory(app.filesDir)
                .redirectErrorStream(true)
                .also { it.environment()["XRAY_LOCATION_ASSET"] = app.filesDir.absolutePath }
                .start()
        } catch (error: Throwable) {
            config.delete()
            throw error
        }
        process = proc
        Thread({
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val clean = line.take(300)
                        tail.addLast(clean)
                        while (tail.size > 40) tail.pollFirst()
                        if (clean.contains("[Warning]") || clean.contains("[Error]") || clean.contains("failed")) {
                            AppLogRepository.info(LogSource.XRAY, "[$tag] $clean")
                        }
                    }
                }
            }
        }, "xray-raw-$tag").apply { isDaemon = true; start() }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs)
        try {
            while (System.nanoTime() < deadline) {
                check(alive(proc)) { "Xray exited: " + (tail.lastOrNull { it.contains("rror") || it.contains("failed") } ?: tail.lastOrNull().orEmpty()) }
                if (portOpen(port)) return
                Thread.sleep(50)
            }
            error("Xray did not open 127.0.0.1:$port in time")
        } catch (error: Throwable) {
            stopLocked()
            throw error
        } finally {
            config.delete()
        }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val proc = process ?: return
        process = null
        runCatching { proc.destroy() }
        // Process.isAlive / waitFor(timeout) / destroyForcibly are API 26; minSdk is 24.
        var waited = 0
        while (alive(proc) && waited < 2_500) { Thread.sleep(25); waited += 25 }
        if (alive(proc)) runCatching { proc.destroy() }
        runCatching { proc.inputStream.close() }
        runCatching { proc.outputStream.close() }
    }

    private fun alive(p: Process): Boolean = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress("127.0.0.1", port), 120); true }
    }.getOrDefault(false)

    private fun writePrivate(file: File, text: String) {
        file.delete()
        file.createNewFile()
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        file.writeText(text)
    }
}
