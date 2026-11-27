package com.uacspoofer.mobile.extras

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.core.location.LocationManagerCompat
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY

/**
 * Starts the tunnel when the phone joins a Wi-Fi network that is not trusted.
 *
 * Battery: registers a [ConnectivityManager] network callback with a
 * PendingIntent, so the system wakes [UntrustedWifiReceiver] only when a
 * Wi-Fi network becomes available. No polling, no persistent service, no
 * deprecated CONNECTIVITY_ACTION broadcast. The registration is dropped by the
 * OS on reboot or app update, so [ExtrasBootReceiver] and app start re-arm it.
 *
 * Location: in [WifiTrustMode.ANY_WIFI] no permission is used. Reading the
 * Wi-Fi name (SSID) for [WifiTrustMode.TRUSTED_LIST] needs location permission
 * on Android 8.1+ (and "allow all the time" when the app is in the background
 * on Android 10+). If the name cannot be read, the network is treated as
 * untrusted, which is the same as the "any Wi-Fi" mode.
 */
internal object UntrustedWifiWatcher {
    private const val REQUEST_CODE = 41210
    const val ACTION_WIFI_AVAILABLE = "com.uacspoofer.mobile.extras.WIFI_AVAILABLE"

    private fun connectivity(context: Context): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UntrustedWifiReceiver::class.java).setAction(ACTION_WIFI_AVAILABLE)
        // Must be mutable: the system adds EXTRA_NETWORK / EXTRA_NETWORK_REQUEST when it fires.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Arms or disarms the callback to match the current setting. Safe to call often. */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (ExtrasSettingsStore.get(app).snapshot().untrustedWifiAutoConnect) arm(app) else disarm(app)
    }

    private fun arm(context: Context) {
        val cm = connectivity(context) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        // Registering again with the same PendingIntent replaces the earlier registration.
        runCatching { cm.registerNetworkCallback(request, pendingIntent(context)) }
            .onFailure { AppLogRepository.warning(LogSource.SERVICE, "Untrusted Wi-Fi watch failed", it) }
    }

    private fun disarm(context: Context) {
        val cm = connectivity(context) ?: return
        runCatching { cm.unregisterNetworkCallback(pendingIntent(context)) }
        ExtrasSettingsStore.get(context).lastHandledNetwork = 0L
    }

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasFineLocation(context)
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun locationServiceOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** SSID of the current Wi-Fi, or null when Android hides it (no location permission or location off). */
    fun currentSsid(context: Context, network: Network? = null): String? {
        if (!hasFineLocation(context)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && network != null) {
            val info = runCatching { connectivity(context)?.getNetworkCapabilities(network)?.transportInfo }.getOrNull()
            ExtrasSettingsStore.normalizeSsid((info as? WifiInfo)?.ssid)?.let { return it }
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        return ExtrasSettingsStore.normalizeSsid(runCatching { wifi.connectionInfo?.ssid }.getOrNull())
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Called off the main thread (see [UntrustedWifiReceiver]). */
    @WorkerThread
    fun onWifiAvailable(context: Context, network: Network?) {
        val app = context.applicationContext
        val store = ExtrasSettingsStore.get(app)
        val settings = store.snapshot()
        if (!settings.untrustedWifiAutoConnect) {
            disarm(app)
            return
        }
        val cm = connectivity(app) ?: return
        val target = network ?: return
        val caps = runCatching { cm.getNetworkCapabilities(target) }.getOrNull() ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        // One join of one network triggers at most one connect; a manual disconnect on it is respected.
        val handle = target.networkHandle
        if (handle == store.lastHandledNetwork) return
        store.lastHandledNetwork = handle

        if (settings.wifiTrustMode == WifiTrustMode.TRUSTED_LIST) {
            val ssid = currentSsid(app, target)
            if (ssid != null && ssid in settings.trustedSsids) {
                AppLogRepository.info(LogSource.SERVICE, "Untrusted Wi-Fi: trusted network, no auto-connect")
                return
            }
        }

        val state = ConnectionStateStore.state.value
        if (state != ConnectionState.DISCONNECTED && state != ConnectionState.ERROR) return

        val proxyMode = AdvancedSettingsStore(app).snapshot().connectionMode == CONNECTION_MODE_PROXY
        val prepared = proxyMode || runCatching { VpnService.prepare(app) == null }.getOrDefault(false)
        if (!prepared || !ExtrasNotifications.canPost(app)) {
            askToConnect(app)
            return
        }
        // ConnectionStateStore and the service start are touched on the main thread only (quick, non-blocking calls).
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
              runCatching {
                val now = ConnectionStateStore.state.value
                if (now != ConnectionState.DISCONNECTED && now != ConnectionState.ERROR) return@runCatching
                if (!ConnectionStateStore.tryBeginConnect()) return@runCatching
                AppLogRepository.info(LogSource.SERVICE, "Untrusted Wi-Fi: auto-connect starting")
                try {
                    VpnController.start(app)
                } catch (error: Throwable) {
                    // Android 12+ may refuse a background foreground-service start; let the user finish with one tap.
                    ConnectionStateStore.markDisconnected()
                    AppLogRepository.warning(LogSource.SERVICE, "Untrusted Wi-Fi: background start refused", error)
                    askToConnect(app)
                }
              }
            } finally {
                done.countDown()
            }
        }
        // Keep the broadcast alive until the start was handed to the system (bounded, never on the main thread).
        runCatching { done.await(2, TimeUnit.SECONDS) }
    }

    private fun askToConnect(context: Context) {
        ExtrasNotifications.post(
            context,
            ExtrasNotifications.ID_WIFI_CONNECT,
            "وای‌فای ناامن",
            "به شبکهٔ وای‌فایی وصل شدید که در فهرست مورد اعتماد نیست. برای روشن کردن بلک دی لمس کنید.",
            connectOnTap = true,
        )
    }
}

/**
 * Woken by the PendingIntent network callback. onReceive runs on the main
 * thread, so the work (binder calls, prefs, SSID lookup) moves to a single
 * background thread via goAsync(); no wake lock is taken.
 */
class UntrustedWifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UntrustedWifiWatcher.ACTION_WIFI_AVAILABLE) return
        val network: Network? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
            }
        }.getOrNull()
        val app = context.applicationContext
        val pending = goAsync()
        worker.execute {
            try {
                UntrustedWifiWatcher.onWifiAvailable(app, network)
            } catch (error: Throwable) {
                runCatching { AppLogRepository.warning(LogSource.SERVICE, "Untrusted Wi-Fi handler failed", error) }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "simorgh-wifi-watch").apply { isDaemon = true } }
    }
}

/** Re-arms the Wi-Fi callback after reboot or app update (the OS drops PendingIntent callbacks then). */
class ExtrasBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext
                val pending = goAsync()
                Thread {
                    try {
                        UntrustedWifiWatcher.sync(app)
                    } catch (_: Throwable) {
                    } finally {
                        runCatching { pending.finish() }
                    }
                }.start()
            }
        }
    }
}
