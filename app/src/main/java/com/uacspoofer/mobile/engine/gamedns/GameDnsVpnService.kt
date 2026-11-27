package com.uacspoofer.mobile.engine.gamedns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.ui.MainActivity
import com.uacspoofer.mobile.vpn.AppRoutingPreferences
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Game DNS mode: a VPN that carries ONLY DNS (one /32 route to a virtual resolver address) and
 * answers it from the resolver the race picked. Everything else - the game itself - goes
 * straight out on the phone's line, so there is no tunnel latency. Exclusive with the main
 * tunnel (Android runs one VPN at a time); starting one stops the other.
 */
class GameDnsVpnService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val s = GameDnsStore.get(this).snapshot()
        // startForegroundService() obliges startForeground() even when we bail out at once,
        // otherwise Android kills the app (ForegroundServiceDidNotStartInTimeException).
        startForegroundCompat(s.primary.orEmpty())
        val primary = s.primary ?: run { shutdown(); return START_NOT_STICKY }
        runCatching { start(primary, s.secondary) }.onFailure {
            AppLogRepository.error(LogSource.SERVICE, "[GameDNS] start failed", it)
            shutdown()
        }
        return START_STICKY
    }

    private fun start(primary: String, secondary: String?) {
        stopTun()
        val builder = Builder()
            .setSession("Simorgh Game DNS")
            .setMtu(1500)
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)
        runCatching { AppRoutingPreferences.applyTo(builder, this) }
        // Simorgh's own sockets (the forwarders below) must never enter this VPN.
        runCatching { builder.addDisallowedApplication(packageName) }
        val fd = builder.establish() ?: error("VPN permission missing")
        tun = fd
        val upstreams = listOfNotNull(primary, secondary).map { InetSocketAddress(InetAddress.getByName(it), 53) }
        mutableRunning.value = primary
        AppLogRepository.success(LogSource.SERVICE, "[GameDNS] on, resolver $primary")
        worker = Thread({ loop(fd, upstreams) }, "game-dns").apply { isDaemon = true; start() }
    }

    private fun loop(fd: ParcelFileDescriptor, upstreams: List<InetSocketAddress>) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32 * 1024)
        while (!Thread.currentThread().isInterrupted && tun === fd) {
            val n = try { input.read(buf) } catch (_: Throwable) { break }
            if (n <= 0) continue
            val q = GameDnsPacket.parse(buf, n) ?: continue
            pool.execute {
                val answer = forward(q.payload, upstreams) ?: return@execute
                val packet = GameDnsPacket.response(q, answer.first, answer.second)
                runCatching { synchronized(output) { output.write(packet) } }
            }
        }
    }

    private fun forward(payload: ByteArray, upstreams: List<InetSocketAddress>): Pair<ByteArray, Int>? {
        for (u in upstreams) {
            val r = runCatching {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 2_000
                    s.send(DatagramPacket(payload, payload.size, u))
                    val b = ByteArray(4096)
                    val p = DatagramPacket(b, b.size)
                    s.receive(p)
                    b to p.length
                }
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    private fun stopTun() {
        val old = tun
        tun = null
        worker?.interrupt()
        worker = null
        runCatching { old?.close() }
    }

    private fun shutdown() {
        stopTun()
        mutableRunning.value = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onRevoke() { shutdown() }
    override fun onDestroy() { stopTun(); mutableRunning.value = null; pool.shutdownNow(); super.onDestroy() }

    private fun startForegroundCompat(primary: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "DNS بازی", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GameDnsVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_uac)
            .setContentTitle("DNS بازی روشن است")
            .setContentText("فقط DNS از سیمرغ می‌گذرد · $primary")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "خاموش", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "simorgh_game_dns"
        private const val NOTIFICATION_ID = 7406
        const val ACTION_STOP = "com.uacspoofer.mobile.GAME_DNS_STOP"
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"

        private val mutableRunning = MutableStateFlow<String?>(null)
        /** The resolver in use, or null when Game DNS is off. */
        val running: StateFlow<String?> = mutableRunning.asStateFlow()

        fun start(context: Context) {
            val i = Intent(context, GameDnsVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, GameDnsVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
