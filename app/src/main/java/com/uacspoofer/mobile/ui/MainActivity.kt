package com.uacspoofer.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.location.GpsSpoofRuntime
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.ui.theme.UacSniSpooferTheme
import com.uacspoofer.mobile.vpn.AutoConnectCoordinator
import com.uacspoofer.mobile.vpn.AutoConnectOrigin
import com.uacspoofer.mobile.vpn.ConnectionMetricsStore
import com.uacspoofer.mobile.vpn.LivePing
import com.uacspoofer.mobile.vpn.MonthlyTrafficStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /**
     * The connect attempt the permission prompts belong to. A cancel (or any newer tap)
     * moves the state away from CONNECTING, and a late prompt result must not then start
     * the service anyway. Survives recreation so a consent granted after process death
     * still connects.
     */
    private var awaitingConsent = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val wasAwaiting = awaitingConsent
        awaitingConsent = false
        val granted = result.resultCode == Activity.RESULT_OK ||
            runCatching { VpnService.prepare(this) == null }.getOrDefault(false)
        val state = ConnectionStateStore.state.value
        when {
            granted && state == ConnectionState.CONNECTING -> startVpnService()
            // Process was recreated while the system dialog was up: the store starts fresh.
            granted && wasAwaiting && ConnectionStateStore.tryBeginConnect() -> startVpnService()
            granted -> Unit // cancelled meanwhile
            state == ConnectionState.CONNECTING -> ConnectionStateStore.markErrorNow("اجازه‌ی VPN داده نشد")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The user may have cancelled while the notification prompt was open.
        if (ConnectionStateStore.state.value == ConnectionState.CONNECTING) continueConnectionStart()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_CONSENT, awaitingConsent)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        super.applyOverrideConfiguration(overrideConfiguration?.let(AppFontScale::lock))
    }

    override fun getResources(): Resources {
        val resources = super.getResources()
        AppFontScale.lock(resources)
        return resources
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(AppFontScale.lock(newConfig))
        AppFontScale.lock(resources)
        applyShellOrientation()
    }

    private fun applyShellOrientation() {
        requestedOrientation = if (BuildConfig.TV_MODE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingConsent = savedInstanceState?.getBoolean(STATE_AWAITING_CONSENT, false) == true
        if (!com.uacspoofer.mobile.security.IntegrityGuard.isTrusted(this)) {
            enableEdgeToEdge()
            setContent { UacSniSpooferTheme { TamperScreen() } }
            return
        }
        applyShellOrientation()
        GpsSpoofRuntime.attach(this)
        NetworkGuardStore.get(this)
        MonthlyTrafficStore.get(this)
        AutoConnectCoordinator.ensureWatching(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val state = ConnectionStateStore.state.collectAsStateWithLifecycle().value
            UacSniSpooferTheme {
                TvFocusProvider {
                    com.uacspoofer.mobile.ui.shell.SimorghShell(
                        state = state,
                        onConnect = ::beginConnect,
                        onDisconnect = ::beginDisconnect,
                        onSwitchProfile = ::beginProfileSwitch,
                        onMinimize = { moveTaskToBack(true) },
                        onCloseApp = { closeAppFromBack(state) },
                    )
                }
            }
        }
        if (savedInstanceState == null) handleQuickTileIntent(intent)
        if (savedInstanceState == null) {
            window.decorView.post { AutoConnectCoordinator.tryStart(this, AutoConnectOrigin.APP_START) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runForegroundPingLoop()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickTileIntent(intent)
    }

    private suspend fun runForegroundPingLoop() = coroutineScope {
        var awaitingFirst = true
        while (isActive) {
            if (ConnectionStateStore.state.value != ConnectionState.CONNECTED) {
                awaitingFirst = true
                // Suspend until connected instead of waking every 400 ms while idle.
                ConnectionStateStore.state.first { it == ConnectionState.CONNECTED }
                continue
            }
            if (awaitingFirst) {
                delay(LivePing.SETTLE_MS)
                if (ConnectionStateStore.state.value != ConnectionState.CONNECTED) continue
                awaitingFirst = false
            }
            LivePing.awaitChipSlot(this@MainActivity) {
                isActive && ConnectionStateStore.state.value == ConnectionState.CONNECTED
            }
            if (!isActive || ConnectionStateStore.state.value != ConnectionState.CONNECTED) continue
            if (ConnectionMetricsStore.metrics.value.latencyMs == null) {
                ConnectionMetricsStore.beginLatencyMeasurement()
            }
            val sample = withContext(Dispatchers.IO) {
                runCatching { LivePing.measure(this@MainActivity) }.getOrNull()
            }
            if (sample != null) {
                ConnectionMetricsStore.publishLivePing(sample)
            } else if (ConnectionMetricsStore.metrics.value.latencyMs == null) {
                ConnectionMetricsStore.finishLatencyMeasurement()
            }
            delay(LivePing.INTERVAL_MS)
        }
    }

    private fun beginConnect() {
        if (!ConnectionStateStore.tryBeginConnect()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueConnectionStart()
        }
    }

    private fun handleQuickTileIntent(source: Intent?) {
        if (source?.getBooleanExtra(EXTRA_CONNECT_FROM_QUICK_TILE, false) != true) return
        source.removeExtra(EXTRA_CONNECT_FROM_QUICK_TILE)
        window.decorView.post { beginConnect() }
    }

    private fun continueConnectionStart() {
        if (AdvancedSettingsStore(this).snapshot().connectionMode == CONNECTION_MODE_PROXY) {
            startVpnService()
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        try {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                startVpnService()
            } else {
                awaitingConsent = true
                vpnPermissionLauncher.launch(prepareIntent)
            }
        } catch (_: Throwable) {
            awaitingConsent = false
            ConnectionStateStore.markErrorNow("اجازه‌ی VPN در دسترس نیست")
        }
    }

    private fun startVpnService() {
        if (ConnectionStateStore.state.value != ConnectionState.CONNECTING) return
        try {
            VpnController.start(this)
        } catch (_: Throwable) {
            ConnectionStateStore.markErrorNow("سرویس اتصال شروع نشد")
        }
    }

    private fun beginDisconnect() {
        val autoWasRunning = com.uacspoofer.mobile.simorgh.auto.AutoConnector.running()
        com.uacspoofer.mobile.simorgh.auto.AutoConnector.cancel()
        if (!ConnectionStateStore.tryBeginDisconnect()) {
            if (autoWasRunning) runCatching { VpnController.stop(this) }
            return
        }
        try {
            VpnController.stop(this)
        } catch (_: Throwable) {
            ConnectionStateStore.markDisconnected()
        }
    }

    private fun beginProfileSwitch() {
        try {
            VpnController.switchProfile(this)
        } catch (_: Throwable) {
            ConnectionStateStore.markError()
        }
    }

    private fun closeAppFromBack(state: ConnectionState) {
        if (state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING) {
            runCatching { VpnController.close(this) }
        }
        finishAndRemoveTask()
    }

    companion object {
        const val EXTRA_CONNECT_FROM_QUICK_TILE = "com.uacspoofer.mobile.extra.CONNECT_FROM_QUICK_TILE"
        private const val STATE_AWAITING_CONSENT = "simorgh.awaitingVpnConsent"
    }
}
